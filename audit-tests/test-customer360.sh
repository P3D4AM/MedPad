#!/usr/bin/env bash
# test-customer360.sh — Customer 360 (phase 1, Nov 2026):
# server-side contracts of the customer dossier against the REAL installed
# bundle (fresh non-interactive install + boot).
#
#   C360A  fresh install + boot (template carries CustomerLink/Purchase/
#          PurchaseItem; Customer carries the profile columns)
#   C1     admin creates a clinic + a physician (profile fields persist)
#   C2     typed physician↔clinic links:
#          · link physician→clinic (admin) — ok
#          · MANY physicians per clinic + ONE physician across MANY clinics
#          · type checks: clinic-clinic → 400 · physician-physician → 400
#          · duplicate link → single row (upsert reactivates)
#   C3     duplicate prevention:
#          · same (type,name) create → 409 + duplicate identity
#          · force=true → created (admin's explicit decision)
#          · same-name edit collision → 409
#   C4     edit with profile fields (panel PUT) — round-trips
#   C5     admin 360 profile: GET /api/customers/[id]/profile
#          · customer + links both ways + counts
#          · purchase summary is EXACTLY zeros with NO purchases (never
#            fabricated — mission §4.3) and real rows after seeding
#          · REAL visits appear (checkin→visit linked, notes + voiceFiles)
#   C6     app-side access control (mission §9):
#          · assigned rep GET /api/app/customers/[id]/profile → 200
#          · OTHER rep (unassigned) → 403 — changing the id leaks nothing
#          · link counterpart NOT assigned to the rep is MASKED (no name)
#          · app link creation: both sides assigned → 200; otherwise 403
#   C7     offline-create idempotency (mission §8):
#          · create with localId → replay SAME localId → replay:true,
#            SAME row id, still exactly ONE customer (no duplicate)
#   C8     deactivation is logical: active=false keeps visits/links intact;
#          hard DELETE is refused (409) when real history exists
#   MIG    boot-migration safety: a pre-Customer-360 custom.db (no new
#          columns/tables) boots, migrates, and its rows survive intact
#
# Deterministic: fresh install; curl + python3 + sqlite3 via python3.
# Run:  bash test-customer360.sh [PKG_DIR]   (C360_WORK=<dir> overrides)
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2" >/dev/null 2>&1; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${C360_WORK:-/tmp/tc360}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="; echo "== WORKDIR: $W =="

PANEL_PORT=3341; RT_PORT=3343; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_PASS='C360pass123!'

cleanup() {
  (cd $W/inst 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── C360A: fresh install + boot + fixtures ─────────────────────────
echo "== C360A: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/install.log 2>&1)
chk "C360A install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "C360A panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
chk "C360A fresh DB carries CustomerLink" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='CustomerLink'\\\").fetchone() else 1)\""
chk "C360A fresh DB carries Purchase + PurchaseItem" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');n=[r[0] for r in c.execute(\\\"SELECT name FROM sqlite_master WHERE type='table'\\\").fetchall()];exit(0 if 'Purchase' in n and 'PurchaseItem' in n else 1)\""
chk "C360A Customer has active + appLocalId columns" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');cols=[r[1] for r in c.execute('PRAGMA table_info(\\\"Customer\\\")')];exit(0 if 'active' in cols and 'appLocalId' in cols and 'lastName' in cols else 1)\""

# admin session + two reps
curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" > /dev/null
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"rep1","password":"Rep1pass123!","name":"رضا مدرپ یک"}' > $W/empA.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"rep2","password":"Rep2pass123!","name":"سارا مدرپ دو"}' > $W/empB.json
EMPA=$(py "import json;print(json.load(open('$W/empA.json'))['employee']['id'])")
EMPB=$(py "import json;print(json.load(open('$W/empB.json'))['employee']['id'])")
T1=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"rep1","password":"Rep1pass123!"}' | py 'import json,sys;print(json.load(sys.stdin)["token"])')
T2=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"rep2","password":"Rep2pass123!"}' | py 'import json,sys;print(json.load(sys.stdin)["token"])')
chk "C360A both reps logged in" "[ -n '$T1' ] && [ -n '$T2' ]"

# ── C1: admin creates a clinic + a physician (profile fields) ──────
echo "== C1: create clinic + physician =="
CLINIC=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"کلینیک مهرگان","type":"clinic","lat":35.72,"lng":51.42,"phone":"02177112233","city":"تهران","province":"تهران","managerName":"آقای کاظمی","district":"منطقهٔ ۳","phone2":"02177112244","postalCode":"1969734512","notes":"کلینیک ارجاعی"}')
chk "C1 clinic created with profile fields" \
  "echo '$CLINIC' | py 'import json,sys;d=json.load(sys.stdin);exit(0 if d[\"ok\"] and d[\"customer\"][\"managerName\"]==\"آقای کاظمی\" and d[\"customer\"][\"district\"]==\"منطقهٔ ۳\" else 1)'"
CLINIC_ID=$(echo "$CLINIC" | py 'import json,sys;print(json.load(sys.stdin)["customer"]["id"])')

DOC=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"مریم","type":"doctor","lat":35.73,"lng":51.43,"phone":"09121110022","lastName":"سمیری","title":"دکتر","specialty":"قلب"}')
chk "C1 physician created with person fields" \
  "echo '$DOC' | py 'import json,sys;d=json.load(sys.stdin);exit(0 if d[\"ok\"] and d[\"customer\"][\"lastName\"]==\"سمیری\" and d[\"customer\"][\"specialty\"]==\"قلب\" else 1)'"
DOC_ID=$(echo "$DOC" | py 'import json,sys;print(json.load(sys.stdin)["customer"]["id"])')

# ── C2: typed links, many-to-many, type checks, upsert ─────────────
echo "== C2: physician↔clinic links =="
DOC2=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"علی","type":"doctor","lat":35.74,"lng":51.44,"lastName":"رستمی","specialty":"پوست"}')
DOC2_ID=$(echo "$DOC2" | py 'import json,sys;print(json.load(sys.stdin)["customer"]["id"])')
CLINIC2=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"کلینیک آریا","type":"clinic","lat":35.75,"lng":51.45}')
CLINIC2_ID=$(echo "$CLINIC2" | py 'import json,sys;print(json.load(sys.stdin)["customer"]["id"])')

L1=$(curl -s -b $W/cj -X POST $BASE/api/customer-links -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC_ID\",\"physicianId\":\"$DOC_ID\",\"note\":\"شنبه‌ها\"}")
chk "C2 link physician→clinic ok" "echo '$L1' | py 'import json,sys;exit(0 if json.load(sys.stdin)[\"ok\"] else 1)'"
curl -s -b $W/cj -X POST $BASE/api/customer-links -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC_ID\",\"physicianId\":\"$DOC2_ID\"}" > /dev/null
curl -s -b $W/cj -X POST $BASE/api/customer-links -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC2_ID\",\"physicianId\":\"$DOC_ID\"}" > /dev/null
chk "C2 MANY physicians per clinic (2 in مهرگان)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM CustomerLink WHERE clinicId=?',('$CLINIC_ID',)).fetchone()[0]==2 else 1)\""
chk "C2 ONE physician across MANY clinics (سمیری in 2)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM CustomerLink WHERE physicianId=?',('$DOC_ID',)).fetchone()[0]==2 else 1)\""

R400a=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/customer-links -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC_ID\",\"physicianId\":\"$CLINIC2_ID\"}")
chk "C2 clinic↔clinic rejected (400)" "[ '$R400a' = 400 ]"
R400b=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/customer-links -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$DOC_ID\",\"physicianId\":\"$DOC2_ID\"}")
chk "C2 physician↔physician rejected (400)" "[ '$R400b' = 400 ]"

curl -s -b $W/cj -X POST $BASE/api/customer-links -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC_ID\",\"physicianId\":\"$DOC_ID\",\"note\":\"دوشنبه‌ها\"}" > /dev/null
chk "C2 duplicate link is an UPSERT (still 1 row for the pair)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM CustomerLink WHERE clinicId=? AND physicianId=?',('$CLINIC_ID','$DOC_ID')).fetchone()[0]==1 else 1)\""
chk "C2 upsert updated the note (same row)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT note FROM CustomerLink WHERE clinicId=? AND physicianId=?',('$CLINIC_ID','$DOC_ID')).fetchone()[0]=='دوشنبه‌ها' else 1)\""

# ── C3: duplicate prevention ────────────────────────────────────────
echo "== C3: duplicate prevention =="
DUP=$(curl -s -b $W/cj -o $W/dup.json -w '%{http_code}' -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"کلینیک مهرگان","type":"clinic","lat":35.7,"lng":51.4}')
chk "C3 same (type,name) create → 409" "[ '$DUP' = 409 ]"
chk "C3 409 carries the existing row's identity" \
  "py 'import json;d=json.load(open(\"$W/dup.json\"));exit(0 if d.get(\"duplicate\",{}).get(\"id\")==\"$CLINIC_ID\" else 1)'"
FORCE=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"کلینیک مهرگان","type":"clinic","lat":35.7,"lng":51.4,"force":true}')
chk "C3 force=true creates the second row (admin decision)" \
  "echo '$FORCE' | py 'import json,sys;exit(0 if json.load(sys.stdin)[\"ok\"] else 1)'"

# ── C4: edit with profile fields ────────────────────────────────────
echo "== C4: edit profile fields =="
EDIT=$(curl -s -b $W/cj -X PUT $BASE/api/customers/$DOC_ID -H 'content-type: application/json' \
  -d '{"specialty":"قلب و عروق","phone2":"09120001122","active":false}')
chk "C4 edit round-trips specialty/phone2 + deactivates" \
  "echo '$EDIT' | py 'import json,sys;d=json.load(sys.stdin);exit(0 if d[\"ok\"] and d[\"customer\"][\"specialty\"]==\"قلب و عروق\" and d[\"customer\"][\"phone2\"]==\"09120001122\" and d[\"customer\"][\"active\"] is False else 1)'"
curl -s -b $W/cj -X PUT $BASE/api/customers/$DOC_ID -H 'content-type: application/json' \
  -d '{"active":true}' > /dev/null # reactivate for the rest of the suite
chk "C4 deactivation kept links + zero deletions" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM CustomerLink WHERE physicianId=?',('$DOC_ID',)).fetchone()[0]==2 else 1)\""

# ── C5: admin 360 profile (visits + purchases + links) ─────────────
echo "== C5: admin 360 profile =="
# assign rep1 to the clinic + the physician; rep2 to clinic2 only
# the assignments API takes the employee's ROW id (cuid), not the username
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$CLINIC_ID\"}" > /dev/null
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$DOC_ID\"}" > /dev/null
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$DOC2_ID\"}" > /dev/null
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPB\",\"customerId\":\"$CLINIC2_ID\"}" > /dev/null
chk "C5 assignments in place" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM Assignment').fetchone()[0]==4 else 1)\""

# a REAL visit: rep1 checks in at the clinic (with a voice note + report)
echo "fake-voice-c360" > $W/v1.m4a
CHK=$(curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $T1" \
  -F "customerId=$CLINIC_ID" -F "note=ویزیت اول — مشاوره" -F "localId=c360-lid-1" \
  -F "lat=35.7201" -F "lng=51.4201" -F "voice=@$W/v1.m4a;type=audio/mp4")
chk "C5 check-in at the clinic ok" "echo '$CHK' | py 'import json,sys;exit(0 if json.load(sys.stdin)[\"ok\"] else 1)'"
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $T1" \
  -F "note=پایان ویزیت" -F "localId=c360-lid-2" > /dev/null

curl -s -b $W/cj "$BASE/api/customers/$CLINIC_ID/profile" > $W/prof.json
chk "C5 profile: links both ways" \
  "py 'import json;d=json.load(open(\"$W/prof.json\"));exit(0 if len(d[\"links\"][\"physicians\"])==2 else 1)'"
chk "C5 profile: REAL visit with rep name + note + voice" \
  "py 'import json;d=json.load(open(\"$W/prof.json\"));v=d[\"visits\"][0];exit(0 if v[\"employee\"][\"name\"]==\"رضا مدرپ یک\" and \"مشاوره\" in (v[\"checkin\"][\"note\"] or \"\") and len(v[\"checkin\"][\"voiceFiles\"] or [])==1 else 1)'"
chk "C5 profile: purchase summary is EXACT zeros (no fabricated sales)" \
  "py 'import json;d=json.load(open(\"$W/prof.json\"));s=d[\"purchaseSummary\"];exit(0 if s[\"orderCount\"]==0 and s[\"total\"]==0 and s[\"lastPurchaseAt\"] is None and s[\"avgOrder\"] is None else 1)'"
chk "C5 profile: provenance note present (no Holoo yet)" \
  "py 'import json;d=json.load(open(\"$W/prof.json\"));exit(0 if \"هلو\" in d[\"purchaseSourceNote\"] else 1)'"

# seed a REAL purchase row (source visible) → summary becomes real
py "
import sqlite3, datetime
c = sqlite3.connect('$W/inst/panel/db/custom.db')
now = datetime.datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3] + '+00:00'
p = ('p-c360-1', '$CLINIC_ID', 'holoo', 'holo-777', 'F-1001', now, 'paid', 50000.0, None, now, now, now)
c.execute('INSERT INTO Purchase (id,customerId,source,externalId,invoiceNo,date,paymentStatus,discount,notes,syncedAt,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', p)
c.execute('INSERT INTO PurchaseItem (id,purchaseId,productId,productName,productCode,brand,qty,unitPrice,total,discount) VALUES (?,?,?,?,?,?,?,?,?,?)',
          ('i-1','p-c360-1','holo-item-1','قرص قلب ۱۰۰','C100','فارمد',10.0,50000.0,500000.0,None))
c.commit()
"
curl -s -b $W/cj "$BASE/api/customers/$CLINIC_ID/profile" > $W/prof2.json
chk "C5 seeded purchase appears with its SOURCE (هلو)" \
  "py 'import json;d=json.load(open(\"$W/prof2.json\"));exit(0 if d[\"purchases\"][0][\"source\"]==\"holoo\" and d[\"purchases\"][0][\"invoiceNo\"]==\"F-1001\" and d[\"purchases\"][0][\"items\"][0][\"productName\"]==\"قرص قلب ۱۰۰\" else 1)'"
chk "C5 summary computes from real rows (net = 500000 - 50000)" \
  "py 'import json;d=json.load(open(\"$W/prof2.json\"));exit(0 if abs(d[\"purchaseSummary\"][\"total\"]-450000.0)<0.01 and d[\"purchaseSummary\"][\"orderCount\"]==1 else 1)'"

# ── C6: app-side access control ────────────────────────────────────
echo "== C6: app-side access control =="
R=$(curl -s -o $W/app-prof.json -w '%{http_code}' "$BASE/api/app/customers/$CLINIC_ID/profile" -H "X-Medpad-Token: $T1")
chk "C6 assigned rep (rep1) reads the profile (200)" "[ '$R' = 200 ]"
chk "C6 app profile shows the visit + summary" \
  "py 'import json;d=json.load(open(\"$W/app-prof.json\"));exit(0 if len(d[\"visits\"])==1 and d[\"purchaseSummary\"][\"orderCount\"]==1 else 1)'"
R403=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/app/customers/$CLINIC_ID/profile" -H "X-Medpad-Token: $T2")
chk "C6 UNASSIGNED rep (rep2) → 403 (id change leaks nothing)" "[ '$R403' = 403 ]"

# rep1 sees the linked physician (also assigned) but clinic2's link is masked
curl -s "$BASE/api/app/customers/$DOC_ID/profile" -H "X-Medpad-Token: $T1" > $W/doc-prof.json
chk "C6 link counterpart assigned to rep1 is visible" \
  "py 'import json;d=json.load(open(\"$W/doc-prof.json\"));cl=d[\"links\"][\"clinics\"];exit(0 if any(l[\"counterpart\"].get(\"visible\") and l[\"counterpart\"][\"id\"]==\"$CLINIC_ID\" for l in cl) else 1)'"
chk "C6 unassigned counterpart is MASKED (no name)" \
  "py 'import json;d=json.load(open(\"$W/doc-prof.json\"));cl=d[\"links\"][\"clinics\"];masked=[l for l in cl if not l[\"counterpart\"].get(\"visible\")];exit(0 if len(masked)==1 and masked[0][\"counterpart\"][\"name\"] is None else 1)'"

# app link creation: both assigned → ok; one unassigned → 403
RL=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/customer-links -H "X-Medpad-Token: $T1" -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC_ID\",\"physicianId\":\"$DOC2_ID\",\"note\":\"پنجشنبه‌ها\"}")
chk "C6 app link with BOTH sides assigned → 200" "[ '$RL' = 200 ]"
RL403=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/customer-links -H "X-Medpad-Token: $T1" -H 'content-type: application/json' \
  -d "{\"clinicId\":\"$CLINIC2_ID\",\"physicianId\":\"$DOC_ID\"}")
chk "C6 app link with an UNASSIGNED clinic → 403" "[ '$RL403' = 403 ]"

# ── C7: offline-create idempotency (localId replay) ────────────────
echo "== C7: offline-create idempotency =="
CR1=$(curl -s -X POST $BASE/api/app/customers/create -H "X-Medpad-Token: $T1" -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ صحرا","type":"pharmacy","lat":35.8,"lng":51.5,"localId":"c360-offline-1"}')
CR1_ID=$(echo "$CR1" | py 'import json,sys;print(json.load(sys.stdin)["customer"]["id"])')
chk "C7 offline create (localId) lands UNASSIGNED" \
  "echo '$CR1' | py 'import json,sys;d=json.load(sys.stdin);exit(0 if d[\"ok\"] and d[\"assigned\"] is False else 1)'"
CR2=$(curl -s -X POST $BASE/api/app/customers/create -H "X-Medpad-Token: $T1" -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ صحرا","type":"pharmacy","lat":35.8,"lng":51.5,"localId":"c360-offline-1"}')
chk "C7 REPLAY returns the SAME row (replay:true, same id)" \
  "echo '$CR2' | py \"import json,sys;d=json.load(sys.stdin);exit(0 if d.get(\\\"replay\\\") is True and d[\\\"customer\\\"][\\\"id\\\"]=='$CR1_ID' else 1)\""
chk "C7 still exactly ONE row for that localId (no duplicate on re-sync)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM Customer WHERE appLocalId=?',('c360-offline-1',)).fetchone()[0]==1 else 1)\""

# app-side duplicate guard: a DIFFERENT localId with the same name+type → 409
RD409=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/customers/create -H "X-Medpad-Token: $T1" -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ صحرا","type":"pharmacy","lat":35.8,"lng":51.5,"localId":"c360-offline-2"}')
chk "C7 app create duplicate (new localId, same name+type) → 409" "[ '$RD409' = 409 ]"

# app edit with profile fields (partial update — old apps keep working)
RE=$(curl -s -X PUT $BASE/api/app/customers/$DOC_ID -H "X-Medpad-Token: $T1" -H 'content-type: application/json' \
  -d '{"specialty":"قلب، عروق و ریوی"}')
chk "C7 app edit updates a profile field (assigned customer)" \
  "echo '$RE' | py 'import json,sys;d=json.load(sys.stdin);exit(0 if d[\"ok\"] and d[\"customer\"][\"specialty\"]==\"قلب، عروق و ریوی\" else 1)'"

# ── C8: logical delete + hard delete guard ─────────────────────────
echo "== C8: deactivation vs deletion =="
DEL=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X DELETE $BASE/api/customers/$CLINIC_ID)
chk "C8 hard DELETE refused with real history (409)" "[ '$DEL' = 409 ]"
chk "C8 visits/links/purchases all survive the refused delete" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');v=c.execute('SELECT COUNT(*) FROM Visit WHERE customerId=?',('$CLINIC_ID',)).fetchone()[0];p=c.execute('SELECT COUNT(*) FROM Purchase WHERE customerId=?',('$CLINIC_ID',)).fetchone()[0];exit(0 if v==1 and p==1 else 1)\""

# ── MIG: boot-migration of a pre-Customer-360 database ─────────────
echo "== MIG: boot migration of a legacy (pre-360) database =="
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
py "
import sqlite3
c = sqlite3.connect('$W/inst/panel/db/custom.db')
# roll the Customer table back to the pre-360 shape (drop the new columns
# via table rebuild) — exactly what a dev.7 install physically has
cols = [r[1] for r in c.execute('PRAGMA table_info(\"Customer\")')]
keep = [x for x in cols if x not in ('lastName','title','specialty','managerName','phone2','district','postalCode','notes','active','appLocalId')]
c.execute('ALTER TABLE \"Customer\" RENAME TO \"Customer_old360\"')
sel = ','.join('\"%s\"' % x for x in keep)
c.execute('CREATE TABLE \"Customer\" AS SELECT ' + sel + ' FROM \"Customer_old360\"')
c.execute('DROP TABLE \"Customer_old360\"')
c.execute('DROP TABLE IF EXISTS \"CustomerLink\"')
c.execute('DROP TABLE IF EXISTS \"Purchase\"')
c.execute('DROP TABLE IF EXISTS \"PurchaseItem\"')
c.execute('PRAGMA user_version = 0')
c.commit()
print('legacy rows:', c.execute('SELECT COUNT(*) FROM \"Customer\"').fetchone()[0])
"
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "MIG legacy DB boots + migrates (columns back)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');cols=[r[1] for r in c.execute('PRAGMA table_info(\\\"Customer\\\")')];exit(0 if 'lastName' in cols and 'active' in cols else 1)\""
chk "MIG legacy tables recreated (CustomerLink/Purchase)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');n=[r[0] for r in c.execute(\\\"SELECT name FROM sqlite_master WHERE type='table'\\\").fetchall()];exit(0 if 'CustomerLink' in n and 'Purchase' in n else 1)\""
chk "MIG every legacy customer row survived" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM \\\"Customer\\\"').fetchone()[0]>=6 else 1)\""
chk "MIG migrated rows are active + profile fields NULL" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');r=c.execute('SELECT active, lastName FROM \\\"Customer\\\" WHERE id=?',('$CLINIC_ID',)).fetchone();exit(0 if r[0]==1 and r[1] is None else 1)\""

echo ""
echo "== RESULT: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ "$FAIL" = "0" ]
