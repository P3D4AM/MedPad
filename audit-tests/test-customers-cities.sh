#!/usr/bin/env bash
# test-customers-cities.sh — package 8 (phase 2 / task 5): customer form
# completion + OFFLINE Iran cities dataset, end-to-end against a REAL
# installed panel. Run:  bash test-customers-cities.sh [PKG_DIR]
#
#   CC0  fresh install + boot + fixtures (admin + 2 employees, customers
#        with and without city)
#   CC1  panel create WITH a valid (city, province) pair -> stored & echoed;
#        GET /api/customers exposes city/province
#   CC2  server-side validation gates: no session -> 401; unknown city ->
#        400 Persian error; city without province -> 400; valid city with
#        WRONG province -> 400; ZWNJ-stripped input matches canonically
#   CC3  create WITHOUT city -> null pair (back-compat default)
#   CC4  edit flows: PUT sets pair; PUT city:"" clears BOTH; PUT without
#        the field leaves the pair untouched
#   CC5  app (rep) create with a valid pair via /api/app/customers/create
#        -> stored + auto-assigned to creator
#   CC6  app edit with a valid pair -> updated; stale updatedAt -> 409 LWW
#   CC7  app edit WITHOUT the city field -> existing pair untouched
#   CC8  app sync GET returns city/province for assigned customers; the
#        other rep never sees unassigned customers (G-1 regression)
#   CC9  duplicate city names across provinces (نیر/اردبیل vs نیر/یزد)
#        both valid & distinct — the PAIR is the key
#   CC10 UPGRADE PATH: strip province/city columns (pre-pkg8 database)
#        -> restart -> instrumentation re-adds them; existing rows readable
#        with null city; a fresh edit-with-city works; app token survives
#
# Deterministic: fresh non-interactive install in /tmp; curl + python3 only.
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W=/tmp/tcity; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3283; RT_PORT=3286; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── CC0: install + boot + fixtures ───────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'City123!' > $W/install.log 2>&1)
chk "CC0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "CC0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"City123!"}' -o $W/admin-login.json
chk "CC0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

for i in 1 2; do
  curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
    -d "{\"username\":\"c_emp$i\",\"password\":\"emp${i}pass123\",\"name\":\"City Emp$i\",\"phone\":\"77$i\"}" -o $W/emp$i.json
done
EMP1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "CC0 employees created" "[ -n '$EMP1' ] && [ -n '$EMP2' ]"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"c_emp1","password":"emp1pass123"}' -o $W/app1.json
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"c_emp2","password":"emp2pass123"}' -o $W/app2.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
TOK2=$(py "import json;print(json.load(open('$W/app2.json'))['token'])")
chk "CC0 app logins" "[ -n '$TOK1' ] && [ -n '$TOK2' ]"

# ── CC1: panel create WITH city ──────────────────────────────
CODE=$(curl -s -o $W/c1.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ شهرستانی","type":"pharmacy","address":"خیابان معین","phone":"07132222","city":"شیراز","province":"فارس","lat":29.5918,"lng":52.5837,"radius":200}')
chk "CC1 create with city -> 200" "[ '$CODE' = '200' ]"
CUS1=$(py "import json;d=json.load(open('$W/c1.json'));print(d['customer']['id'] if d.get('ok') else '')" 2>/dev/null)
chk "CC1 city stored" "py \"import json;d=json.load(open('$W/c1.json'));c=d['customer'];exit(0 if c['city']=='شیراز' and c['province']=='فارس' else 1)\""
curl -s -b $W/cj "$BASE/api/customers" -o $W/all1.json
chk "CC1 GET /api/customers exposes pair" "py \"import json;d=json.load(open('$W/all1.json'));exit(0 if any(c['id']=='$CUS1' and c.get('city')=='شیراز' and c.get('province')=='فارس' for c in d['customers']) else 1)\""

# ── CC2: validation gates ────────────────────────────────────
CODE=$(curl -s -o $W/g1.json -w '%{http_code}' -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"بدون نشست","city":"شیراز","province":"فارس","lat":29.6,"lng":52.6}')
chk "CC2 no session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/g2.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"شهر خیالی","city":"آتلانتیس","province":"فارس","lat":29.6,"lng":52.6}')
chk "CC2 unknown city -> 400" "[ '$CODE' = '400' ]"
chk "CC2 unknown city Persian error" "grep -q 'فهرست شهرهای ایران' $W/g2.json"
CODE=$(curl -s -o $W/g3.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"شهر بدون استان","city":"شیراز","lat":29.6,"lng":52.6}')
chk "CC2 city without province -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/g4.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"استان غلط","city":"شیراز","province":"تهران","lat":29.6,"lng":52.6}')
chk "CC2 valid city + wrong province -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/g5.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"بدون نیم‌فاصله","city":"رباطکریم","province":"تهران","lat":35.48,"lng":51.03}')
chk "CC2 ZWNJ-stripped city -> 200 (normalized match)" "[ '$CODE' = '200' ]"
chk "CC2 canonical form stored" "py \"import json;d=json.load(open('$W/g5.json'));exit(0 if d['customer']['city']=='رباط‌کریم' else 1)\""
CUSZ=$(py "import json;print(json.load(open('$W/g5.json'))['customer']['id'])")

# ── CC3: create WITHOUT city ─────────────────────────────────
CODE=$(curl -s -o $W/c3.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"مشتری قدیمی","type":"doctor","lat":35.70,"lng":51.42}')
chk "CC3 create without city -> 200" "[ '$CODE' = '200' ]"
chk "CC3 null pair" "py \"import json;d=json.load(open('$W/c3.json'));c=d['customer'];exit(0 if c.get('city') is None and c.get('province') is None else 1)\""
CUS2=$(py "import json;print(json.load(open('$W/c3.json'))['customer']['id'])")

# ── CC4: edit flows ──────────────────────────────────────────
CODE=$(curl -s -o $W/e1.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/customers/$CUS2" \
  -H 'content-type: application/json' \
  -d '{"city":"یزد","province":"یزد"}')
chk "CC4 PUT sets pair" "py \"import json;d=json.load(open('$W/e1.json'));c=d['customer'];exit(0 if c['city']=='یزد' and c['province']=='یزد' else 1)\""
CODE=$(curl -s -o $W/e2.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/customers/$CUS2" \
  -H 'content-type: application/json' \
  -d '{"name":"مشتری قدیمی ویرایش‌شده"}')
chk "CC4 PUT without city field -> pair untouched" "py \"import json;d=json.load(open('$W/e2.json'));c=d['customer'];exit(0 if c['city']=='یزد' and c['province']=='یزد' and c['name']=='مشتری قدیمی ویرایش‌شده' else 1)\""
CODE=$(curl -s -o $W/e3.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/customers/$CUS2" \
  -H 'content-type: application/json' \
  -d '{"city":"","province":""}')
chk "CC4 PUT city:'' clears BOTH" "py \"import json;d=json.load(open('$W/e3.json'));c=d['customer'];exit(0 if c.get('city') is None and c.get('province') is None else 1)\""
CODE=$(curl -s -o $W/e4.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/customers/$CUS2" \
  -H 'content-type: application/json' \
  -d '{"city":"شهر ناموجود","province":"فارس"}')
chk "CC4 PUT invalid pair -> 400" "[ '$CODE' = '400' ]"

# ── CC5: app create with city ────────────────────────────────
CODE=$(curl -s -o $W/a1.json -w '%{http_code}' -X POST $BASE/api/app/customers/create \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' \
  -d '{"name":"درمانگاه اپ","type":"clinic","address":"بلوار امیرکبیر","phone":"03533344","city":"کرمان","province":"کرمان","lat":30.2839,"lng":57.0834}')
chk "CC5 app create with city -> 200" "[ '$CODE' = '200' ]"
CUS3=$(py "import json;print(json.load(open('$W/a1.json'))['customer']['id'])")
chk "CC5 pair stored + canonical" "py \"import json;d=json.load(open('$W/a1.json'));c=d['customer'];exit(0 if c['city']=='کرمان' and c['province']=='کرمان' else 1)\""
CODE=$(curl -s -o $W/a2.json -w '%{http_code}' -X POST $BASE/api/app/customers/create \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' \
  -d '{"name":"اپ شهر خراب","city":"نانا","province":"فارس","lat":30.2,"lng":57.0}')
chk "CC5 app create invalid city -> 400" "[ '$CODE' = '400' ]"
chk "CC5 app create invalid city Persian error" "grep -q 'فهرست شهرهای ایران' $W/a2.json"

# ── CC6: app edit with city (+ LWW) ──────────────────────────
NOW=$(py "import json;print(json.load(open('$W/a1.json'))['customer']['updatedAt'])")
CODE=$(curl -s -o $W/a3.json -w '%{http_code}' -X PUT "$BASE/api/app/customers/$CUS3" \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' \
  -d "{\"city\":\"بردسیر\",\"province\":\"کرمان\",\"updatedAt\":\"$NOW\"}")
chk "CC6 app edit sets new city" "py \"import json;d=json.load(open('$W/a3.json'));c=d['customer'];exit(0 if c['city']=='بردسیر' and c['province']=='کرمان' else 1)\""
STALE="2020-01-01T00:00:00.000Z"
CODE=$(curl -s -o $W/a4.json -w '%{http_code}' -X PUT "$BASE/api/app/customers/$CUS3" \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' \
  -d "{\"city\":\"شیراز\",\"province\":\"فارس\",\"updatedAt\":\"$STALE\"}")
chk "CC6 stale updatedAt -> 409 LWW conflict" "[ '$CODE' = '409' ]"

# ── CC7: app edit WITHOUT city field ─────────────────────────
NOW2=$(py "import json;print(json.load(open('$W/a3.json'))['customer']['updatedAt'])")
CODE=$(curl -s -o $W/a5.json -w '%{http_code}' -X PUT "$BASE/api/app/customers/$CUS3" \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' \
  -d "{\"phone\":\"03599999\",\"updatedAt\":\"$NOW2\"}")
chk "CC7 pair untouched by city-less edit" "py \"import json;d=json.load(open('$W/a5.json'));c=d['customer'];exit(0 if c['city']=='بردسیر' and c['phone']=='03599999' else 1)\""

# ── CC8: app sync returns pair; G-1 isolation ────────────────
# assign CUS1 (شیراز, admin-created, unassigned) to EMP1. CUS3 is ALREADY
# assigned to EMP1 — the app-create route auto-assigns the creator.
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\"}" -o $W/asg1.json
chk "CC8 fixtures assigned" "grep -q '\"ok\":true' $W/asg1.json"
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/customers" -o $W/sync1.json
chk "CC8 sync has city for assigned" "py \"import json;d=json.load(open('$W/sync1.json'));exit(0 if any(c['id']=='$CUS1' and c.get('city')=='شیراز' and c.get('province')=='فارس' for c in d['customers']) else 1)\""
chk "CC8 sync has updated city for app-edited" "py \"import json;d=json.load(open('$W/sync1.json'));exit(0 if any(c['id']=='$CUS3' and c.get('city')=='بردسیر' for c in d['customers']) else 1)\""
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/customers" -o $W/sync2.json
chk "CC8 other rep sees none (G-1)" "py \"import json;d=json.load(open('$W/sync2.json'));exit(0 if len(d['customers'])==0 else 1)\""

# ── CC9: duplicate city names across provinces ───────────────
CODE=$(curl -s -o $W/d1.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"نیر شمالی","city":"نیر","province":"اردبیل","lat":38.04,"lng":47.89}')
chk "CC9 نیر/اردبیل -> 200" "[ '$CODE' = '200' ]"
CODE=$(curl -s -o $W/d2.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"نیر جنوبی","city":"نیر","province":"یزد","lat":31.7,"lng":53.94}')
chk "CC9 نیر/یزد -> 200" "[ '$CODE' = '200' ]"
chk "CC9 both stored with own provinces" "py \"import json;a=json.load(open('$W/d1.json'))['customer'];b=json.load(open('$W/d2.json'))['customer'];exit(0 if a['province']=='اردبیل' and b['province']=='یزد' and a['city']==b['city']=='نیر' else 1)\""

# ── CC10: upgrade path (pre-pkg8 database) ───────────────────
DBFILE=$(find $W/inst -name "custom.db" | head -1)
[ -n "$DBFILE" ] || DBFILE=$(find $W/inst -path "*panel*" -name "*.db" ! -name "template.db" | head -1)
chk "CC10 found the live database" "[ -n '$DBFILE' ]"
if [ -n "$DBFILE" ]; then
  (cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1); sleep 2
  python3 - "$DBFILE" <<'PYEOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
sql = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='Customer'").fetchone()[0]
old = sql.replace('    "province" TEXT,\n    "city" TEXT,\n', '')
assert old != sql, 'province/city columns not found — unexpected DDL'
db.execute('DROP TABLE IF EXISTS "Customer_v8"')
db.execute(old.replace('CREATE TABLE "Customer"', 'CREATE TABLE "Customer_v8"'))
db.execute('INSERT INTO "Customer_v8" ("id","name","type","address","phone","lat","lng","radius","updatedAt","createdAt") '
           'SELECT "id","name","type","address","phone","lat","lng","radius","updatedAt","createdAt" FROM "Customer"')
db.execute('DROP TABLE "Customer"')
db.execute('ALTER TABLE "Customer_v8" RENAME TO "Customer"')
db.commit()
print('   stripped province/city (simulating a pre-package-8 install)')
PYEOF
  (cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  chk "CC10 panel restarted on old-schema db" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
  curl -s -b $W/cj "$BASE/api/customers" -o $W/all2.json
  chk "CC10 existing customers readable (null city)" "py \"import json;d=json.load(open('$W/all2.json'));exit(0 if any(c['id']=='$CUS1' and c.get('city') is None for c in d['customers']) else 1)\""
  curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/customers" -o $W/sync3.json
  chk "CC10 app token survives, sync works" "grep -q '\"ok\":true' $W/sync3.json"
  CODE=$(curl -s -o $W/m1.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/customers/$CUS1" \
    -H 'content-type: application/json' -d '{"city":"شیراز","province":"فارس"}')
  chk "CC10 edit-with-city works after migration" "py \"import json;d=json.load(open('$W/m1.json'));exit(0 if d.get('ok') and d['customer']['city']=='شیراز' else 1)\""
fi

echo
echo "== RESULT: $PASS pass / $FAIL fail =="
if [ $FAIL -gt 0 ]; then exit 1; fi
echo "ALL-CITY-CHECKS-PASS"
