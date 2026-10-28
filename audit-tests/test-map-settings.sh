#!/usr/bin/env bash
# test-map-settings.sh — package 9 (phase 2 / task 6): map completion —
# default-city admin setting + app config resolution + zoom/my-location
# support (server side), end-to-end against a REAL installed panel.
# Run:  bash test-map-settings.sh [PKG_DIR]
#
#   MS0  fresh install + boot + fixtures (admin + employee app token)
#   MS1  GET /api/admin/settings -> ok, defaultCity initially null
#   MS2  PUT valid pair -> canonical echo; persisted (GET confirms)
#   MS3  /api/app/config -> defaultCity RESOLVED with dataset coords
#   MS4  invalid pair / wrong province / non-object -> 400 Persian errors
#   MS5  null clears the setting (GET null; app config null)
#   MS6  mixed PUT (numeric setting + defaultCity) — both paths work
#   MS7  401 gates: admin routes without session; app config without token
#   MS8  audit row written (settings.update mentions defaultCity, no secrets)
#   MS9  persistence: server restart -> setting survives
#   MS10 corrupt stored value (simulated pre-existing junk row) -> falls
#        back to null safely, never breaks the app config
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

W=/tmp/tmapset; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3293; RT_PORT=3296; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── MS0: install + boot + fixtures ───────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'Mapset123!' > $W/install.log 2>&1)
chk "MS0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "MS0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"Mapset123!"}' -o $W/admin-login.json
chk "MS0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"m_emp1","password":"emp1pass123","name":"Map Emp1","phone":"881"}' -o $W/emp1.json
chk "MS0 employee created" "grep -q '\"ok\":true' $W/emp1.json"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"m_emp1","password":"emp1pass123"}' -o $W/app1.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
chk "MS0 app login" "[ -n '$TOK1' ]"

# ── MS1: initial state ───────────────────────────────────────
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/s0.json
chk "MS1 GET settings ok" "grep -q '\"ok\":true' $W/s0.json"
chk "MS1 defaultCity initially null" "py \"import json;d=json.load(open('$W/s0.json'));exit(0 if d.get('defaultCity') is None else 1)\""

# ── MS2: set a valid pair ────────────────────────────────────
CODE=$(curl -s -o $W/s1.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' \
  -d '{"defaultCity":{"city":"شیراز","province":"فارس"}}')
chk "MS2 PUT valid pair -> 200" "[ '$CODE' = '200' ]"
chk "MS2 canonical echo" "py \"import json;d=json.load(open('$W/s1.json'));dc=d['defaultCity'];exit(0 if dc['city']=='شیراز' and dc['province']=='فارس' else 1)\""
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/s2.json
chk "MS2 persisted (GET)" "py \"import json;d=json.load(open('$W/s2.json'));exit(0 if d['defaultCity'] and d['defaultCity']['city']=='شیراز' else 1)\""

# ── MS3: app config resolution ───────────────────────────────
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/config" -o $W/c1.json
chk "MS3 app config ok" "grep -q '\"ok\":true' $W/c1.json"
chk "MS3 defaultCity resolved with coords" "py \"import json;d=json.load(open('$W/c1.json'));dc=d['defaultCity'];exit(0 if dc and dc['city']=='شیراز' and dc['province']=='فارس' and abs(dc['lat']-29.5918)<0.01 and abs(dc['lng']-52.5837)<0.01 else 1)\""
chk "MS3 config still has panelVersion + employee" "py \"import json;d=json.load(open('$W/c1.json'));exit(0 if d['panelVersion'] and d['employee']['username']=='m_emp1' else 1)\""

# ── MS4: validation gates ────────────────────────────────────
CODE=$(curl -s -o $W/g1.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' \
  -d '{"defaultCity":{"city":"آتلانتیس","province":"فارس"}}')
chk "MS4 unknown city -> 400" "[ '$CODE' = '400' ]"
chk "MS4 Persian error mentions the list" "grep -q 'فهرست شهرهای ایران' $W/g1.json"
CODE=$(curl -s -o $W/g2.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' \
  -d '{"defaultCity":{"city":"شیراز","province":"تهران"}}')
chk "MS4 wrong province -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/g3.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' \
  -d '{"defaultCity":"شیراز"}')
chk "MS4 non-object value -> 400" "[ '$CODE' = '400' ]"
# the failed PUTs must NOT have changed the stored setting
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/s3.json
chk "MS4 failed PUTs left setting intact" "py \"import json;d=json.load(open('$W/s3.json'));exit(0 if d['defaultCity'] and d['defaultCity']['city']=='شیراز' else 1)\""

# ── MS5: clear with null ─────────────────────────────────────
CODE=$(curl -s -o $W/s4.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' \
  -d '{"defaultCity":null}')
chk "MS5 null clears -> 200" "[ '$CODE' = '200' ]"
chk "MS5 GET returns null" "py \"import json;d=json.load(open('$W/s4.json'));exit(0 if d['defaultCity'] is None else 1)\""
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/config" -o $W/c2.json
chk "MS5 app config defaultCity null" "py \"import json;d=json.load(open('$W/c2.json'));exit(0 if d.get('defaultCity') is None else 1)\""

# ── MS6: mixed PUT — numeric + defaultCity together ──────────
CODE=$(curl -s -o $W/s5.json -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' \
  -d '{"appTokenTtlHours":600,"defaultCity":{"city":"نیر","province":"یزد"}}')
chk "MS6 mixed PUT -> 200" "[ '$CODE' = '200' ]"
chk "MS6 both applied" "py \"import json;d=json.load(open('$W/s5.json'));exit(0 if d['settings']['appTokenTtlHours']==600 and d['defaultCity'] and d['defaultCity']['city']=='نیر' and d['defaultCity']['province']=='یزد' else 1)\""
# duplicate-name disambiguation: نیر/یزد must NOT resolve to اردبیل
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/config" -o $W/c3.json
chk "MS6 pair-key resolution (نیر/یزد not اردبیل)" "py \"import json;d=json.load(open('$W/c3.json'));dc=d['defaultCity'];exit(0 if dc and dc['province']=='یزد' and abs(dc['lat']-31.7)<0.01 else 1)\""

# ── MS7: 401 gates ───────────────────────────────────────────
CODE=$(curl -s -o $W/g4.json -w '%{http_code}' "$BASE/api/admin/settings")
chk "MS7 admin GET without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/g5.json -w '%{http_code}' -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"defaultCity":null}')
chk "MS7 admin PUT without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/g6.json -w '%{http_code}' "$BASE/api/app/config")
chk "MS7 app config without token -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/g7.json -w '%{http_code}' -H "X-Medpad-Token: bogus" "$BASE/api/app/config")
chk "MS7 app config bogus token -> 401" "[ '$CODE' = '401' ]"

# ── MS8: audit row ───────────────────────────────────────────
curl -s -b $W/cj "$BASE/api/audit-log" -o $W/al.json
chk "MS8 audit mentions defaultCity" "grep -q 'defaultCity' $W/al.json"
chk "MS8 audit has no password material" "! grep -q 'Mapset123' $W/al.json"

# ── MS9: persistence across restart ──────────────────────────
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1); sleep 2
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "MS9 panel restarted" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/s6.json
chk "MS9 setting survived restart" "py \"import json;d=json.load(open('$W/s6.json'));exit(0 if d['defaultCity'] and d['defaultCity']['city']=='نیر' else 1)\""
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/config" -o $W/c4.json
chk "MS9 app token + config after restart" "py \"import json;d=json.load(open('$W/c4.json'));exit(0 if d['defaultCity'] and d['defaultCity']['city']=='نیر' else 1)\""

# ── MS10: corrupt stored value falls back to null ────────────
DBFILE=$(find $W/inst -name "custom.db" | head -1)
[ -n "$DBFILE" ] || DBFILE=$(find $W/inst -path "*panel*" -name "*.db" ! -name "template.db" | head -1)
chk "MS10 found the live database" "[ -n '$DBFILE' ]"
if [ -n "$DBFILE" ]; then
  (cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1); sleep 2
  python3 - "$DBFILE" <<'PYEOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute("UPDATE Config SET value='{not json at all!!' WHERE key='defaultCity'")
db.commit()
print('   corrupted defaultCity row (simulating a bad manual edit)')
PYEOF
  (cd $W/inst && node server-manager.mjs start --no-open > $W/boot3.log 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  chk "MS10 panel boots with corrupt row" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
  curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/s7.json
  chk "MS10 corrupt value -> null fallback (no 500)" "py \"import json;d=json.load(open('$W/s7.json'));exit(0 if d.get('defaultCity') is None else 1)\""
  curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/config" -o $W/c5.json
  chk "MS10 app config still ok" "grep -q '\"ok\":true' $W/c5.json"
fi

echo
echo "== RESULT: $PASS pass / $FAIL fail =="
if [ $FAIL -gt 0 ]; then exit 1; fi
echo "ALL-MAPSETTINGS-CHECKS-PASS"
