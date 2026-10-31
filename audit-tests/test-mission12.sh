#!/usr/bin/env bash
# test-mission12.sh — the Nov-2026 comprehensive mission (12 items):
# server-side contracts of items 1 (unassigned registration), 9 (multi
# voice per event) and 10 (validity gate) against the REAL installed
# bundle (fresh non-interactive install + boot), plus the VoiceFile
# boot-backfill for legacy single-file events.
#
#   M12A  fresh install + boot + fixtures (template carries VoiceFile)
#   MI1   item 1 — rep-registered customer lands UNASSIGNED:
#          · create via /api/app/customers/create → assigned:false,
#            ZERO Assignment rows for the creator
#          · NOT in the creator's /api/app/customers list
#          · check-in against it → 403 (existing G-1 enforcement)
#          · admin assigns via the panel API → appears + check-in works
#   MI9   item 9 — multiple recordings per event:
#          · multipart check-in with THREE voice parts → 3 VoiceFile rows
#            (seq 0..2), Checkin.voicePath = the FIRST (legacy consumers)
#          · localId REPLAY with the same parts → duplicate:true and NO
#            second file / row (idempotency — re-sync never duplicates)
#          · reports API returns the voiceFiles array + legacy voiceUrl
#          · media route: owner 200 per file · other rep 403
#          · checkout with TWO parts → 2 rows · checkout with NONE → 400
#          · panel /api/checkins carries voiceFiles
#   MI10  item 10 — validity gate server-side:
#          · a 0-byte voice part among valid ones is NEVER stored
#          · an event with ONLY a 0-byte part has NO voice at all
#   MIG   migration safety — legacy single-file events are backfilled:
#          · a hand-seeded Checkin.voicePath row gets ONE VoiceFile row
#            ('vfb_' id, same path, seq 0) after a server REBOOT, and the
#            backfill is idempotent on a second reboot (still exactly one)
#
# Deterministic: fresh install; curl + python3 + sqlite3 via python3.
# Run:  bash test-mission12.sh [PKG_DIR]   (M12_WORK=<dir> overrides)
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2" >/dev/null 2>&1; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${M12_WORK:-/tmp/tm12}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="; echo "== WORKDIR: $W =="

PANEL_PORT=3321; RT_PORT=3323; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_PASS='M12pass123!'

cleanup() {
  (cd $W/inst 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── M12A: fresh install + boot + fixtures ─────────────────────────
echo "== M12A: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/install.log 2>&1)
chk "M12A install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "M12A panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
chk "M12A fresh DB has VoiceFile table (new template)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='VoiceFile'\\\").fetchone() else 1)\""
chk "M12A fresh DB has VoiceFile_path_key unique index" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='VoiceFile_path_key'\\\").fetchone() else 1)\""

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" > /dev/null
chk "M12A admin login" "[ -s $W/cj ]"

# fixtures: two reps + one assigned customer (for MI9)
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"m12a","password":"pass1234","name":"سارا محمدی"}' > $W/empA.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"m12b","password":"pass1234","name":"رضا احمدی"}' > $W/empB.json
EMPA=$(py "import json;print(json.load(open('$W/empA.json'))['employee']['id'])")
EMPB=$(py "import json;print(json.load(open('$W/empB.json'))['employee']['id'])")
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"m12a","password":"pass1234"}' > $W/loginA.json
TOKA=$(py "import json;print(json.load(open('$W/loginA.json'))['token'])")
TOKA=${TOKA//\"/}
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"m12b","password":"pass1234"}' > $W/loginB.json
TOKB=$(py "import json;print(json.load(open('$W/loginB.json'))['token'])")
TOKB=${TOKB//\"/}
chk "M12A fixtures: two reps" "[ -n '$TOKA' ] && [ -n '$TOKB' ]"

curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ مأموریت","type":"pharmacy","lat":35.7000,"lng":51.4000,"radius":500}' > $W/cusA.json
CUSA=$(py "import json;print(json.load(open('$W/cusA.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$CUSA\"}" > /dev/null
chk "M12A fixtures: assigned customer" "[ -n '$CUSA' ]"
# a SECOND customer for the MI10 flow (the 90s dedup window would otherwise
# answer every same-customer checkin with the ORIGINAL row)
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"کلینیک اعتبارسنجی","type":"clinic","lat":35.7200,"lng":51.4200,"radius":500}' > $W/cusB.json
CUSB=$(py "import json;print(json.load(open('$W/cusB.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$CUSB\"}" > /dev/null
chk "M12A fixtures: second assigned customer" "[ -n '$CUSB' ]"
# and a THIRD customer for the MI10 validity-gate flow (each check-in must
# land on a customer with no check-in in the last 90s, or the dedup window
# answers with the original row)
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"کلینیک گیت اعتبار","type":"clinic","lat":35.7300,"lng":51.4300,"radius":500}' > $W/cusC.json
CUSC=$(py "import json;print(json.load(open('$W/cusC.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$CUSC\"}" > /dev/null
chk "M12A fixtures: third assigned customer" "[ -n '$CUSC' ]"
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ صدای خالی","type":"pharmacy","lat":35.7400,"lng":51.4400,"radius":500}' > $W/cusD.json
CUSD=$(py "import json;print(json.load(open('$W/cusD.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$CUSD\"}" > /dev/null
chk "M12A fixtures: fourth assigned customer" "[ -n '$CUSD' ]"

# ── MI1: item 1 — rep-registered customer lands UNASSIGNED ────────
echo "== MI1: item 1 — unassigned registration =="
curl -s -X POST $BASE/api/app/customers/create -H 'content-type: application/json' -H "X-Medpad-Token: $TOKA" \
  -d '{"name":"کلینیک ثبت‌شده توسط مدرپ","type":"clinic","lat":35.7100,"lng":51.4100,"city":"تهران","province":"تهران"}' > $W/created.json
chk "MI1 create ok" "py \"import json;exit(0 if json.load(open('$W/created.json')).get('ok') else 1)\""
chk "MI1 response says assigned:false (honest contract)" \
  "py \"import json;exit(0 if json.load(open('$W/created.json')).get('assigned') is False else 1)\""
CUSN=$(py "import json;print(json.load(open('$W/created.json'))['customer']['id'])")
chk "MI1 NO Assignment row for the creator (unassigned state)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');n=c.execute('SELECT COUNT(*) FROM Assignment WHERE customerId=?',('$CUSN',)).fetchone()[0];exit(0 if n==0 else 1)\""
curl -s -H "X-Medpad-Token: $TOKA" "$BASE/api/app/customers" > $W/mylist.json
chk "MI1 creator's sync list does NOT include it" \
  "py \"import json;d=json.load(open('$W/mylist.json'));exit(0 if '$CUSN' not in d.get('assignedIds',[]) else 1)\""
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSN" -F "note=تلاش چک‌این روی مشتری بدون تخصیص" -F "localId=mi1-403-probe" -o $W/ci403.json -w '%{http_code}' > $W/ci403.code
chk "MI1 check-in against unassigned customer → 403" "grep -q 403 $W/ci403.code"
chk "MI1 403 carries the Persian policy text" "grep -q اختصاص $W/ci403.json"
# the panel API still shows the customer (with empty assignments → the UI badge)
curl -s -b $W/cj "$BASE/api/customers" > $W/panelcus.json
chk "MI1 panel list carries it with ZERO assignments (badge input)" \
  "py \"import json;d=json.load(open('$W/panelcus.json'));c=[x for x in d['customers'] if x['id']=='$CUSN'];exit(0 if c and len(c[0].get('assignments',[]))==0 else 1)\""
# admin assigns → the rep sees it and can check in
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$CUSN\"}" > /dev/null
curl -s -H "X-Medpad-Token: $TOKA" "$BASE/api/app/customers" > $W/mylist2.json
chk "MI1 after admin assignment it IS in the sync list" \
  "py \"import json;d=json.load(open('$W/mylist2.json'));exit(0 if '$CUSN' in d.get('assignedIds',[]) else 1)\""
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSN" -F "note=" -F "localId=mi1-after-assign" -o $W/ciOk.json -w '%{http_code}' > $W/ciOk.code
chk "MI1 check-in now succeeds (201/200)" "grep -qE '20[01]' $W/ciOk.code"
# close the visit so later flows start clean
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOKA" \
  -F "note=بستن ویزیت آزمایشی مأموریت" -F "localId=mi1-close" \
  -F "voice=@$W/created.json;filename=v1.m4a;type=audio/mp4" > /dev/null

# ── MI9: item 9 — multiple recordings per event ───────────────────
echo "== MI9: item 9 — multi-voice =="
printf 'AAAA-fake-m4a-take-one-AAAA' > $W/take1.m4a
printf 'BBBB-fake-m4a-take-two-BBBB' > $W/take2.m4a
printf 'CCCC-fake-m4a-take-three-CCCC' > $W/take3.m4a
LID="m12-multi-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSA" -F "note=چک‌این با سه ضبط" -F "localId=$LID" \
  -F "voice=@$W/take1.m4a;type=audio/mp4" \
  -F "voice=@$W/take2.m4a;type=audio/mp4" \
  -F "voice=@$W/take3.m4a;type=audio/mp4" > $W/ci3.json
chk "MI9 3-voice check-in ok" "py \"import json;exit(0 if json.load(open('$W/ci3.json')).get('ok') else 1)\""
CIID=$(py "import json;print(json.load(open('$W/ci3.json'))['checkin']['id'])")
chk "MI9 THREE VoiceFile rows, ordered seq 0,1,2" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');r=[x[0] for x in c.execute('SELECT seq FROM VoiceFile WHERE checkinId=? ORDER BY seq',('$CIID',))];exit(0 if r==[0,1,2] else 1)\""
chk "MI9 Checkin.voicePath = FIRST file (legacy consumers)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');vp=c.execute('SELECT voicePath FROM Checkin WHERE id=?',('$CIID',)).fetchone()[0];vf=c.execute('SELECT path FROM VoiceFile WHERE checkinId=? AND seq=0',('$CIID',)).fetchone()[0];exit(0 if vp and vp==vf else 1)\""
NFILES=$(py "import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');print(c.execute('SELECT COUNT(*) FROM VoiceFile WHERE checkinId=?',('$CIID',)).fetchone()[0])")
chk "MI9 three files exist on disk under uploads/voice" \
  "[ \$(ls $W/inst/panel/.next/standalone/uploads/voice/ | wc -l) -ge $NFILES ]"

# replay the SAME localId with the same parts → duplicate, nothing new
N_ALL=$(py "import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');print(c.execute('SELECT COUNT(*) FROM VoiceFile').fetchone()[0])")
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSA" -F "note=بازارسال" -F "localId=$LID" \
  -F "voice=@$W/take1.m4a;type=audio/mp4" \
  -F "voice=@$W/take2.m4a;type=audio/mp4" \
  -F "voice=@$W/take3.m4a;type=audio/mp4" > $W/ci3dup.json
chk "MI9 replay is duplicate:true" "py \"import json;exit(0 if json.load(open('$W/ci3dup.json')).get('duplicate') else 1)\""
chk "MI9 replay stored NO new files/rows (idempotent)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute('SELECT COUNT(*) FROM VoiceFile').fetchone()[0]==$N_ALL else 1)\""

# reports API: voiceFiles array + legacy voiceUrl
curl -s -H "X-Medpad-Token: $TOKA" "$BASE/api/app/reports" > $W/reports.json
chk "MI9 reports carry the 3-file voiceFiles array (own urls)" \
  "py \"import json;d=json.load(open('$W/reports.json'));r=[x for x in d['reports'] if x['id']=='$CIID'][0];vf=r.get('voiceFiles',[]);exit(0 if len(vf)==3 and all(f.get('url','').startswith('/api/media/voice/') for f in vf) else 1)\""
chk "MI9 reports still carry the legacy voiceUrl (first file)" \
  "py \"import json;d=json.load(open('$W/reports.json'));r=[x for x in d['reports'] if x['id']=='$CIID'][0];exit(0 if r.get('voiceUrl','').startswith('/api/media/voice/') else 1)\""

# media authorization: owner per file 200, other rep 403
VURL1=$(py "import json;d=json.load(open('$W/reports.json'));r=[x for x in d['reports'] if x['id']=='$CIID'][0];print(r['voiceFiles'][0]['url'])")
chk "MI9 media: owner fetches file 1 (200)" \
  "curl -s -o /dev/null -w '%{http_code}' -H 'X-Medpad-Token: $TOKA' '$BASE$VURL1' | grep -q 200"
chk "MI9 media: another rep is refused (403)" \
  "curl -s -o /dev/null -w '%{http_code}' -H 'X-Medpad-Token: $TOKB' '$BASE$VURL1' | grep -q 403"

# checkout with TWO voices (closes the multi-voice check-in's visit)
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOKA" \
  -F "note=چک‌اوت با دو ضبط مستقل" -F "localId=m12-co-2v" \
  -F "voice=@$W/take2.m4a;type=audio/mp4" \
  -F "voice=@$W/take3.m4a;type=audio/mp4" > $W/co2.json
chk "MI9 2-voice checkout ok" "py \"import json;exit(0 if json.load(open('$W/co2.json')).get('ok') else 1)\""
COID=$(py "import json;print(json.load(open('$W/co2.json'))['checkout']['id'])")
chk "MI9 checkout has TWO VoiceFile rows" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');n=c.execute('SELECT COUNT(*) FROM VoiceFile WHERE checkinId=?',('$COID',)).fetchone()[0];exit(0 if n==2 else 1)\""

# checkout without ANY voice → 400 (voice is required on checkout).
# A visit must be OPEN for the voice rule to be the rejection reason
# (otherwise the honest answer is 409 "no open visit") — open one on the
# second customer (outside the 90s same-customer dedup window).
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSB" -F "note=" -F "localId=m12-co0-open" > $W/co0open.json
curl -s -o $W/co0.json -w '%{http_code}' -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOKA" \
  -F "note=چک‌اوت بدون صدا نباید پذیرفته شود" -F "localId=m12-co-0v" > $W/co0.code
chk "MI9 voice-less checkout → 400 (required)" "grep -q 400 $W/co0.code"
# close the helper visit WITH a voice so later flows start clean
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOKA" \
  -F "note=بستن ویزیت کمکی آزمایشی" -F "localId=m12-co0-close" \
  -F "voice=@$W/take1.m4a;filename=v.m4a;type=audio/mp4" > /dev/null

# panel /api/checkins carries voiceFiles for the admin player
curl -s -b $W/cj "$BASE/api/checkins?limit=50" > $W/panelci.json
chk "MI9 panel checkins carry the voiceFiles array" \
  "py \"import json;d=json.load(open('$W/panelci.json'));r=[x for x in d['checkins'] if x['id']=='$CIID'][0];exit(0 if len(r.get('voiceFiles',[]))==3 else 1)\""

# ── MI10: item 10 — server-side validity gate ─────────────────────
echo "== MI10: item 10 — 0-byte parts never stored =="
printf '' > $W/empty.m4a
# check-in with one valid + one EMPTY part → only the valid one stored
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSC" -F "note=" -F "localId=m12-mixed-validity" \
  -F "voice=@$W/take1.m4a;type=audio/mp4" \
  -F "voice=@$W/empty.m4a;type=audio/mp4" > $W/cimix.json
MIXID=$(py "import json;print(json.load(open('$W/cimix.json'))['checkin']['id'])" 2>/dev/null)
chk "MI10 mixed check-in ok (fresh, not a dedup hit)" "py \"import json;d=json.load(open('$W/cimix.json'));exit(0 if d.get('ok') and not d.get('dedupWindow') else 1)\""
chk "MI10 the 0-byte part stored NOTHING (1 row only)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');n=c.execute('SELECT COUNT(*) FROM VoiceFile WHERE checkinId=?',('$MIXID',)).fetchone()[0];exit(0 if n==1 else 1)\""
# close the visit
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOKA" \
  -F "note=بستن ویزیت ترکیبی آزمایشی" -F "localId=m12-mix-close" \
  -F "voice=@$W/take1.m4a;filename=v.m4a;type=audio/mp4" > /dev/null
# check-in with ONLY an empty part → voice-less (voice optional on check-in)
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOKA" \
  -F "customerId=$CUSD" -F "note=" -F "localId=m12-only-empty" \
  -F "voice=@$W/empty.m4a;type=audio/mp4" > $W/cionly.json
ONLYID=$(py "import json;print(json.load(open('$W/cionly.json'))['checkin']['id'])" 2>/dev/null)
chk "MI10 empty-only check-in ok (fresh, not a dedup hit)" \
  "py \"import json;d=json.load(open('$W/cionly.json'));exit(0 if d.get('ok') and not d.get('dedupWindow') else 1)\""
chk "MI10 empty-only event has NO voicePath and ZERO VoiceFile rows" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');vp=c.execute('SELECT voicePath FROM Checkin WHERE id=?',('$ONLYID',)).fetchone()[0];n=c.execute('SELECT COUNT(*) FROM VoiceFile WHERE checkinId=?',('$ONLYID',)).fetchone()[0];exit(0 if vp is None and n==0 else 1)\""
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOKA" \
  -F "note=بستن ویزیت صوت-خالی آزمایشی" -F "localId=m12-onlyempty-close" \
  -F "voice=@$W/take1.m4a;filename=v.m4a;type=audio/mp4" > /dev/null

# ── MIG: migration safety — legacy backfill over a REBOOT ─────────
echo "== MIG: VoiceFile boot-backfill =="
# hand-seed a LEGACY single-file event (pre-migration shape) + its file
printf 'LEGACY-single-file-bytes' > $W/inst/panel/.next/standalone/uploads/voice/legacy_probe.m4a
python3 - <<PYEOF && echo "   legacy row seeded"
import sqlite3
c = sqlite3.connect('$W/inst/panel/db/custom.db')
c.execute("INSERT INTO Checkin (id, employeeId, customerId, kind, note, voicePath, createdAt, outOfRadius, locationMissing, geoBypassed) VALUES ('legacy_probe_1', '$EMPA', '$CUSA', 'checkin', 'legacy single-file event', 'voice/legacy_probe.m4a', '2026-10-01 10:00:00', 0, 1, 1)")
c.commit()
PYEOF
# REBOOT → instrumentation runs the backfill
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "MIG panel rebooted" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
chk "MIG backfill created ONE VoiceFile row for the legacy path (vfb_ id, seq 0)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');r=c.execute(\\\"SELECT id, seq FROM VoiceFile WHERE path='voice/legacy_probe.m4a'\\\").fetchall();exit(0 if len(r)==1 and r[0][0].startswith('vfb_') and r[0][1]==0 else 1)\""
chk "MIG backfill kept the legacy Checkin.voicePath untouched" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');vp=c.execute(\\\"SELECT voicePath FROM Checkin WHERE id='legacy_probe_1'\\\").fetchone()[0];exit(0 if vp=='voice/legacy_probe.m4a' else 1)\""
chk "MIG backfilled file still playable by its owner (media 200)" \
  "curl -s -o /dev/null -w '%{http_code}' -H 'X-Medpad-Token: $TOKA' '$BASE/api/media/voice/legacy_probe.m4a' | grep -q 200"
# SECOND reboot → backfill is idempotent
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot3.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "MIG second reboot: still exactly ONE row (idempotent)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');n=c.execute(\\\"SELECT COUNT(*) FROM VoiceFile WHERE path='voice/legacy_probe.m4a'\\\").fetchone()[0];exit(0 if n==1 else 1)\""

echo
echo "== mission-12 audit: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ $FAIL -eq 0 ] || exit 1
exit 0
