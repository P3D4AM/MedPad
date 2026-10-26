#!/usr/bin/env bash
# test-remediation.sh — MedPad phase 1 / task 2 REMEDIATION suite (G-1..G-6).
# Run:  bash test-remediation.sh [PKG_DIR]
#
# Focused security/functional tests for the six remediated findings. Each
# section boots nothing by itself — the suite installs a FRESH panel (like
# test-functional-core.sh) and drives the real HTTP API the same way the
# Android app and the admin browser do.
#
#   G1  customer authorization: employees see/check-in/edit ONLY customers
#       assigned to them (server-side); admins see everything.
#   G2  app-token expiration (admin-configurable TTL) + immediate
#       server-side revocation (admin only).
#   G5  admin logout requires an authenticated admin session; separate
#       current-session and all-sessions scopes.
#   G3  employee lifecycle: edit/deactivate/reactivate/revoke-tokens/
#       password-reset + audit log + history preservation.
#   G4  audio-file authorization: own-record + assigned-customer only;
#       orphaned files are admin-only (with an admin review listing).
#   G6  GPS validation: no customer-coordinate substitution, explicit
#       missing-location marker, configurable accuracy tolerance and
#       radius policy (warn by default, strict mode configurable).
#
# All checks run against a FRESH non-interactive install in /tmp — no real
# user data, deterministic, no external tools (curl + python3-sqlite3 only).
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }

W=/tmp/tremed; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3250; RT_PORT=3253; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── install (fresh, non-interactive) ─────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'RemedT2!pass' > $W/install.log 2>&1)
chk "R0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

# ── boot ─────────────────────────────────────────────────────
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do
  curl -s -o /dev/null "$BASE/" && break; sleep 1
done
chk "R0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

# admin session
curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"RemedT2!pass"}' -o $W/admin-login.json
chk "R0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

# ═════════════════════════════════════════════════════════════
# G-1 — customer authorization & assignment enforcement
# ═════════════════════════════════════════════════════════════
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"g1_alice","password":"alicepass1","name":"G1 Alice"}' -o $W/e1.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"g1_bob","password":"bobpass11","name":"G1 Bob"}' -o $W/e2.json
EMP_A=$(python3 -c "import json;print(json.load(open('$W/e1.json'))['employee']['id'])")
EMP_B=$(python3 -c "import json;print(json.load(open('$W/e2.json'))['employee']['id'])")

curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"G1 Clinic X","lat":35.70,"lng":51.40}' -o $W/c1.json
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"G1 Pharmacy Y","lat":35.71,"lng":51.41}' -o $W/c2.json
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"G1 Office Z (unassigned)","lat":35.72,"lng":51.42}' -o $W/c3.json
CUS_X=$(python3 -c "import json;print(json.load(open('$W/c1.json'))['customer']['id'])")
CUS_Y=$(python3 -c "import json;print(json.load(open('$W/c2.json'))['customer']['id'])")
CUS_Z=$(python3 -c "import json;print(json.load(open('$W/c3.json'))['customer']['id'])")

curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_A\",\"customerId\":\"$CUS_X\"}" -o /dev/null
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_B\",\"customerId\":\"$CUS_Y\"}" -o /dev/null

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_alice","password":"alicepass1"}' -o $W/al.json
TOK_A=$(python3 -c "import json;print(json.load(open('$W/al.json'))['token'])")
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_bob","password":"bobpass11"}' -o $W/bo.json
TOK_B=$(python3 -c "import json;print(json.load(open('$W/bo.json'))['token'])")

# G1.1 alice's customer list: ONLY the assigned customer
curl -s -H "X-Medpad-Token: $TOK_A" "$BASE/api/app/customers" -o $W/ac.json
chk "G1 alice sees assigned X" "grep -q '$CUS_X' $W/ac.json"
chk "G1 alice does NOT see Y (assigned to bob)" "! grep -q '$CUS_Y' $W/ac.json"
chk "G1 alice does NOT see Z (unassigned)" "! grep -q '$CUS_Z' $W/ac.json"
chk "G1 alice assignedIds == exactly her X" \
  "python3 -c \"import json;d=json.load(open('$W/ac.json'));assert d['assignedIds']==['$CUS_X'],d['assignedIds']\" 2>/dev/null"
chk "G1 alice customer list contains exactly 1 row" \
  "python3 -c \"import json;assert len(json.load(open('$W/ac.json'))['customers'])==1\" 2>/dev/null"

# G1.2 incremental sync (since=) also filters
curl -s -H "X-Medpad-Token: $TOK_A" "$BASE/api/app/customers?since=2020-01-01T00:00:00Z" -o $W/acs.json
chk "G1 incremental sync filters too" "! grep -q '$CUS_Z' $W/acs.json"

# G1.3 check-in to an ASSIGNED customer works
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_A" \
  -F "customerId=$CUS_X" -F "note=g1 assigned checkin" -F "localId=g1-a-$RANDOM" \
  -F "lat=35.7001" -F "lng=51.4001" -o $W/ci-ok.json
chk "G1 checkin to assigned customer -> ok" "grep -q '\"ok\":true' $W/ci-ok.json"

# G1.4/G1.5 check-in to customers NOT assigned to alice -> 403
CODE=$(curl -s -o $W/ci-y.json -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_A" -F "customerId=$CUS_Y" -F "note=g1 unassigned" \
  -F "localId=g1-y-$RANDOM" -F "lat=35.71" -F "lng=51.41")
chk "G1 checkin to bob's customer -> 403" "[ \"$CODE\" = 403 ]"
CODE=$(curl -s -o $W/ci-z.json -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_A" -F "customerId=$CUS_Z" -F "note=g1 unassigned" \
  -F "localId=g1-z-$RANDOM" -F "lat=35.72" -F "lng=51.42")
chk "G1 checkin to unassigned customer -> 403" "[ \"$CODE\" = 403 ]"
# and nothing was recorded
curl -s -b $W/cj "$BASE/api/checkins" -o $W/all-ci.json
chk "G1 rejected check-ins were NOT recorded" "! grep -q 'g1 unassigned' $W/all-ci.json"

# G1.6 nonexistent customer -> 404 (not 403: existence vs authorization)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_A" -F "customerId=doesnotexist" -F "note=x" \
  -F "localId=g1-n-$RANDOM" -F "lat=1" -F "lng=1")
chk "G1 checkin to nonexistent customer -> 404" "[ \"$CODE\" = 404 ]"

# G1.7 editing customers from the app requires assignment
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/app/customers/$CUS_Y" \
  -H "X-Medpad-Token: $TOK_A" -H 'content-type: application/json' \
  -d '{"name":"hijacked by alice"}')
chk "G1 app-edit of bob's customer -> 403" "[ \"$CODE\" = 403 ]"
CODE=$(curl -s -o $W/edit-x.json -w '%{http_code}' -X PUT "$BASE/api/app/customers/$CUS_X" \
  -H "X-Medpad-Token: $TOK_A" -H 'content-type: application/json' \
  -d '{"phone":"09120000000"}')
chk "G1 app-edit of OWN assigned customer -> 200" "[ \"$CODE\" = 200 ]"

# G1.8 unauthenticated access (direct API bypass attempt)
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/app/customers")
chk "G1 no token -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -F "customerId=$CUS_X" -F "note=no token" -F "localId=g1-nt-$RANDOM")
chk "G1 checkin without token -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/app/customers/$CUS_X" \
  -H 'content-type: application/json' -d '{"name":"no token edit"}')
chk "G1 app-edit without token -> 401" "[ \"$CODE\" = 401 ]"

# G1.9 forged token
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/app/customers" \
  -H "X-Medpad-Token: forged-token-value")
chk "G1 forged token -> 401" "[ \"$CODE\" = 401 ]"

# G1.10 admin sees ALL customers and the check-in (admin workflow intact)
curl -s -b $W/cj "$BASE/api/customers" -o $W/adm-c.json
chk "G1 admin sees all three customers" \
  "grep -q '$CUS_X' $W/adm-c.json && grep -q '$CUS_Y' $W/adm-c.json && grep -q '$CUS_Z' $W/adm-c.json"
chk "G1 admin sees alice's checkin" "grep -q 'g1 assigned checkin' $W/all-ci.json"

# G1.11 proximity workflow for assigned customer still works
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/proximity \
  -H "X-Medpad-Token: $TOK_A" -H 'content-type: application/json' \
  -d "{\"customerId\":\"$CUS_X\",\"lat\":35.7001,\"lng\":51.4001}")
chk "G1 proximity for assigned customer -> 200" "[ \"$CODE\" = 200 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/proximity \
  -H "X-Medpad-Token: $TOK_A" -H 'content-type: application/json' \
  -d "{\"customerId\":\"$CUS_Y\",\"lat\":35.71,\"lng\":51.41}")
chk "G1 proximity for unassigned customer -> 403" "[ \"$CODE\" = 403 ]"

# G1.12 removing the assignment cuts access immediately (server-side)
curl -s -b $W/cj -X DELETE "$BASE/api/assignments?employeeId=$EMP_A&customerId=$CUS_X" -o /dev/null
curl -s -H "X-Medpad-Token: $TOK_A" "$BASE/api/app/customers" -o $W/ac2.json
chk "G1 after unassignment alice's list is empty" \
  "python3 -c \"import json;d=json.load(open('$W/ac2.json'));assert d['customers']==[] and d['assignedIds']==[]\" 2>/dev/null"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_A" -F "customerId=$CUS_X" -F "note=after unassign" \
  -F "localId=g1-u-$RANDOM" -F "lat=35.70" -F "lng=51.40")
chk "G1 checkin after unassignment -> 403" "[ \"$CODE\" = 403 ]"

# G1.13 replaying a localId whose customer is no longer assigned -> 403
# (the idempotency layer must not resurrect access to old data)
LID_G1="g1-replay-$RANDOM"
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_A\",\"customerId\":\"$CUS_X\"}" -o /dev/null
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_A" \
  -F "customerId=$CUS_X" -F "note=g1 replay base" -F "localId=$LID_G1" -o /dev/null
curl -s -b $W/cj -X DELETE "$BASE/api/assignments?employeeId=$EMP_A&customerId=$CUS_X" -o /dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_A" -F "customerId=$CUS_X" -F "note=g1 replay attempt" \
  -F "localId=$LID_G1")
chk "G1 localId replay after unassignment -> 403" "[ \"$CODE\" = 403 ]"

# G1.14 app-created customer auto-assigns the creator (workflow preserved)
curl -s -X POST "$BASE/api/app/customers/create" -H "X-Medpad-Token: $TOK_B" \
  -H 'content-type: application/json' \
  -d '{"name":"G1 Bob New Customer","lat":35.73,"lng":51.43}' -o $W/bc.json
chk "G1 app-create customer ok" "grep -q '\"ok\":true' $W/bc.json"
CUS_N=$(python3 -c "import json;print(json.load(open('$W/bc.json'))['customer']['id'])")
curl -s -H "X-Medpad-Token: $TOK_B" "$BASE/api/app/customers" -o $W/bc2.json
chk "G1 creator auto-assigned (sees own new customer)" "grep -q '$CUS_N' $W/bc2.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_B" -F "customerId=$CUS_N" -F "note=bob new customer" \
  -F "localId=g1-bn-$RANDOM" -F "lat=35.73" -F "lng=51.43")
chk "G1 checkin to own created customer -> ok" "[ \"$CODE\" = 200 ]"
curl -s -H "X-Medpad-Token: $TOK_A" "$BASE/api/app/customers" -o $W/ac3.json
chk "G1 other employee cannot see the new customer" "! grep -q '$CUS_N' $W/ac3.json"

# G1.15 employee with zero assignments gets an empty (valid) list
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"g1_none","password":"nonepass1","name":"No Assignments"}' -o /dev/null
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_none","password":"nonepass1"}' -o $W/nl.json
TOK_N=$(python3 -c "import json;print(json.load(open('$W/nl.json'))['token'])")
curl -s -H "X-Medpad-Token: $TOK_N" "$BASE/api/app/customers" -o $W/none.json
chk "G1 zero-assignment employee -> empty list, ok:true" \
  "python3 -c \"import json;d=json.load(open('$W/none.json'));assert d['ok'] and d['customers']==[]\" 2>/dev/null"

# ═════════════════════════════════════════════════════════════
# G-2 — app-token expiration + immediate server-side revocation
# ═════════════════════════════════════════════════════════════
DB=$W/inst/panel/db/custom.db

# G2.1 default TTL is 720 (30 days)
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/set.json
chk "G2 settings default appTokenTtlHours=720" \
  "python3 -c \"import json;assert json.load(open('$W/set.json'))['settings']['appTokenTtlHours']==720\" 2>/dev/null"

# G2.2 settings require an admin session
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/admin/settings")
chk "G2 settings GET without admin -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"appTokenTtlHours":10}')
chk "G2 settings PUT without admin -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/admin/settings" \
  -H "X-Medpad-Token: $TOK_A" -H 'content-type: application/json' -d '{"appTokenTtlHours":10}')
chk "G2 settings PUT with employee token -> 401" "[ \"$CODE\" = 401 ]"

# G2.3 invalid TTL values rejected (server-side validation)
for BAD in '-5' '8761' '12.5' 'abc'; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
    -H 'content-type: application/json' -d "{\"appTokenTtlHours\":\"$BAD\"}")
  chk "G2 settings PUT invalid value '$BAD' -> 400" "[ \"$CODE\" = 400 ]"
done
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"unknownSetting":5}')
chk "G2 settings PUT unknown key -> 400" "[ \"$CODE\" = 400 ]"

# G2.4 boundary values accepted
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"appTokenTtlHours":0}')
chk "G2 settings PUT boundary 0 -> 200" "[ \"$CODE\" = 200 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"appTokenTtlHours":8760}')
chk "G2 settings PUT boundary 8760 -> 200" "[ \"$CODE\" = 200 ]"

# G2.5 TTL=0 -> new token has NO expiry (pre-remediation behavior, opt-in)
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"appTokenTtlHours":0}' -o /dev/null
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_alice","password":"alicepass1"}' -o $W/al-ttl0.json
chk "G2 TTL=0 -> expiresAt null" \
  "python3 -c \"import json;assert json.load(open('$W/al-ttl0.json'))['expiresAt'] is None\" 2>/dev/null"
TOK_TTL0=$(python3 -c "import json;print(json.load(open('$W/al-ttl0.json'))['token'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL0" "$BASE/api/app/config")
chk "G2 TTL=0 token works" "[ \"$CODE\" = 200 ]"

# G2.6 TTL=1h -> new token carries expiresAt ~1h in the future
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"appTokenTtlHours":1}' -o /dev/null
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_alice","password":"alicepass1"}' -o $W/al-ttl1.json
chk "G2 TTL=1h -> expiresAt ~1h ahead" \
  "python3 -c \"
import json,datetime
d=json.load(open('$W/al-ttl1.json'))['expiresAt']
exp=datetime.datetime.fromisoformat(d.replace('Z','+00:00'))
now=datetime.datetime.now(datetime.timezone.utc)
assert 3500 < (exp-now).total_seconds() < 3700, (exp-now)\" 2>/dev/null"
TOK_TTL1=$(python3 -c "import json;print(json.load(open('$W/al-ttl1.json'))['token'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL1" "$BASE/api/app/config")
chk "G2 valid (unexpired) token works" "[ \"$CODE\" = 200 ]"

# G2.7 malformed token -> 401
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: malformed-token-xyz" "$BASE/api/app/config")
chk "G2 malformed token -> 401" "[ \"$CODE\" = 401 ]"

# G2.8 EXPIRED token -> 401 (age the token directly in SQLite, like time passing)
python3 - "$DB" "$TOK_TTL1" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute('UPDATE AppToken SET expiresAt = ? WHERE token = ?', (1710000000000, sys.argv[2]))
db.commit(); db.close()
EOF
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL1" "$BASE/api/app/config")
chk "G2 expired token -> 401 (immediately, no restart)" "[ \"$CODE\" = 401 ]"

# G2.9 pre-remediation token (expiresAt NULL) keeps working — no mass logout on deploy
python3 - "$DB" "$TOK_TTL0" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute('UPDATE AppToken SET expiresAt = NULL, revokedAt = NULL WHERE token = ?', (sys.argv[2],))
db.commit(); db.close()
EOF
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL0" "$BASE/api/app/config")
chk "G2 legacy token (expiresAt NULL) still valid" "[ \"$CODE\" = 200 ]"

# G2.10 ADMIN REVOCATION — immediate effect
curl -s -b $W/cj -X POST "$BASE/api/employees/$EMP_A/tokens/revoke" -o $W/rev.json
chk "G2 admin revoke-all -> ok with count" \
  "python3 -c \"import json;d=json.load(open('$W/rev.json'));assert d['ok'] and d['revoked']>=1\" 2>/dev/null"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL0" "$BASE/api/app/config")
chk "G2 revoked token -> 401 IMMEDIATELY" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL0" "$BASE/api/app/customers")
chk "G2 revoked token rejected on ANOTHER endpoint too" "[ \"$CODE\" = 401 ]"

# G2.11 unauthorized revocation attempts
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/employees/$EMP_A/tokens/revoke")
chk "G2 revoke without admin session -> 401" "[ \"$CODE\" = 401 ]"
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_bob","password":"bobpass11"}' -o $W/bo2.json
TOK_B2=$(python3 -c "import json;print(json.load(open('$W/bo2.json'))['token'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/employees/$EMP_A/tokens/revoke" \
  -H "X-Medpad-Token: $TOK_B2")
chk "G2 revoke with employee token -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_B2" "$BASE/api/employees/$EMP_A/tokens")
chk "G2 token LIST with employee token -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/employees/$EMP_A/tokens")
chk "G2 token LIST without session -> 401" "[ \"$CODE\" = 401 ]"

# G2.12 token list never leaks the secret
curl -s -b $W/cj "$BASE/api/employees/$EMP_A/tokens" -o $W/toks.json
chk "G2 token list has metadata only (no secret)" "! grep -q '\"token\"' $W/toks.json"
chk "G2 token list shows revokedAt set" "grep -q 'revokedAt' $W/toks.json"

# G2.13 after revocation the employee can log in again (new token works)
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g1_alice","password":"alicepass1"}' -o $W/al-new.json
TOK_A2=$(python3 -c "import json;print(json.load(open('$W/al-new.json'))['token'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_A2" "$BASE/api/app/config")
chk "G2 re-login after revocation -> new token works" "[ \"$CODE\" = 200 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_TTL0" "$BASE/api/app/config")
chk "G2 old revoked token stays dead" "[ \"$CODE\" = 401 ]"

# G2.14 corrupt stored setting falls back to the safe default (no unsafe widening)
python3 - "$DB" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute("UPDATE Config SET value='garbage' WHERE key='appTokenTtlHours'")
db.commit(); db.close()
EOF
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/set2.json
chk "G2 corrupt stored TTL -> safe default 720" \
  "python3 -c \"import json;assert json.load(open('$W/set2.json'))['settings']['appTokenTtlHours']==720\" 2>/dev/null"

# G2.15 runtime migration: an OLD (pre-G-2) database gains the columns on boot
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 1
python3 - "$DB" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
# simulate the pre-G-2 schema: drop the two new columns
for col in ('expiresAt', 'revokedAt'):
    try: db.execute(f'ALTER TABLE AppToken DROP COLUMN "{col}"')
    except Exception as e: print('drop skipped:', e)
db.commit()
cols = [r[1] for r in db.execute('PRAGMA table_info(AppToken)')]
assert 'expiresAt' not in cols and 'revokedAt' not in cols, cols
db.close()
EOF
RC=$?
chk "G2 old schema simulated (columns dropped)" "[ $RC -eq 0 ]"
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
python3 - "$DB" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
cols = [r[1] for r in db.execute('PRAGMA table_info(AppToken)')]
db.close()
sys.exit(0 if ('expiresAt' in cols and 'revokedAt' in cols) else 1)
EOF
RC=$?
chk "G2 migration re-added columns after reboot (instrumentation)" "[ $RC -eq 0 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_A2" "$BASE/api/app/config")
chk "G2 auth works against the migrated DB" "[ \"$CODE\" = 200 ]"

# ═════════════════════════════════════════════════════════════
# G-5 — authenticated admin logout (current + all sessions)
# ═════════════════════════════════════════════════════════════

# G5.1 unauthenticated logout -> 401 (was: 200 for anyone on the network)
CODE=$(curl -s -o $W/lo1.json -w '%{http_code}' -X POST "$BASE/api/logout")
chk "G5 logout without session -> 401" "[ \"$CODE\" = 401 ]"

# G5.2 employee app token is NOT an admin session (header form)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/logout" -H "X-Medpad-Token: $TOK_A2")
chk "G5 logout with employee token (header) -> 401" "[ \"$CODE\" = 401 ]"

# G5.3 a non-admin/foreign value in the admin cookie is rejected
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/logout" \
  -H "Cookie: medpad_admin=$TOK_A2")
chk "G5 logout with employee token as admin cookie -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/logout" \
  -H "Cookie: medpad_admin=forged-session-value")
chk "G5 logout with forged admin cookie -> 401" "[ \"$CODE\" = 401 ]"

# G5.4 authenticated CURRENT-session logout -> 200, scope 'current'.
# The untrusted body must be ignored: the target is always the CALLER's own
# session — no parameter can select another administrator's session.
CODE=$(curl -s -b $W/cj -o $W/lo2.json -w '%{http_code}' -X POST "$BASE/api/logout" \
  -H 'content-type: application/json' -d '{"session":"someone-elses","user":"other"}')
chk "G5 current-session logout -> 200 (untrusted body ignored)" "[ \"$CODE\" = 200 ]"
chk "G5 current-session logout scope=current" "grep -q '\"scope\":\"current\"' $W/lo2.json"

# G5.5 the session is REALLY invalidated afterwards
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/stats")
chk "G5 admin request after logout -> 401" "[ \"$CODE\" = 401 ]"

# G5.6 logging out again (now unauthenticated) -> 401, not a silent 200
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST "$BASE/api/logout")
chk "G5 repeat logout after invalidation -> 401" "[ \"$CODE\" = 401 ]"

# G5.7 unauthenticated logout-all -> 401
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/logout-all")
chk "G5 logout-all without session -> 401" "[ \"$CODE\" = 401 ]"

# G5.9 re-login, then authenticated ALL-sessions logout
curl -s -c $W/cj2 -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"RemedT2!pass"}' -o /dev/null
CODE=$(curl -s -b $W/cj2 -o $W/lo3.json -w '%{http_code}' -X POST "$BASE/api/logout-all")
chk "G5 all-sessions logout -> 200" "[ \"$CODE\" = 200 ]"
chk "G5 all-sessions logout scope=all" "grep -q '\"scope\":\"all\"' $W/lo3.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj2 "$BASE/api/stats")
chk "G5 admin request after logout-all -> 401" "[ \"$CODE\" = 401 ]"

# G5.10 the admin can always log back in (workflow preserved)
curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"RemedT2!pass"}' -o $W/adm-re.json
chk "G5 admin re-login after logouts works" "grep -q '\"ok\":true' $W/adm-re.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/stats")
chk "G5 re-authenticated admin request -> 200" "[ \"$CODE\" = 200 ]"

# ── teardown ─────────────────────────────────────────────────
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 1
chk "R0 ports free after stop" "! ss -tln 2>/dev/null | grep -q ':$PANEL_PORT '"

echo ""
echo "=============================================="
echo "  RESULT: $PASS PASS / $FAIL FAIL"
echo "=============================================="
[ "$FAIL" = 0 ]
