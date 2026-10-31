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

# mission item 7 (Oct 31): the one-open-check-in rule — the server now
# REJECTS a second open visit (409) instead of silently auto-closing the
# previous one. Sequential check-in scenarios therefore close their visit
# before the next one (voice is REQUIRED on checkout — tiny fixture file).
printf 'fake-audio' > $W/voice.m4a
closevisit() { curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $1" \
  -F "note=between-scenarios fixture close" -F "localId=cv-$RANDOM-$RANDOM" \
  -F "voice=@$W/voice.m4a;type=audio/mp4" -o /dev/null; }

# G1.3 check-in to an ASSIGNED customer works
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_A" \
  -F "customerId=$CUS_X" -F "note=g1 assigned checkin" -F "localId=g1-a-$RANDOM" \
  -F "lat=35.7001" -F "lng=51.4001" -o $W/ci-ok.json
chk "G1 checkin to assigned customer -> ok" "grep -q '\"ok\":true' $W/ci-ok.json"
closevisit $TOK_A

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
closevisit $TOK_A
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
closevisit $TOK_B
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

# ═════════════════════════════════════════════════════════════
# G-3 — employee lifecycle (edit/deactivate/reactivate/reset + audit)
# ═════════════════════════════════════════════════════════════

# setup: dave + customer + assignment + token + one checkin (history!)
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"g3_dave","password":"davepass1","name":"G3 Dave"}' -o $W/dave.json
EMP_D=$(python3 -c "import json;print(json.load(open('$W/dave.json'))['employee']['id'])")
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"G3 Dave Clinic","lat":35.80,"lng":51.50}' -o $W/dc.json
CUS_D=$(python3 -c "import json;print(json.load(open('$W/dc.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_D\",\"customerId\":\"$CUS_D\"}" -o /dev/null
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g3_dave","password":"davepass1"}' -o $W/dl.json
TOK_D=$(python3 -c "import json;print(json.load(open('$W/dl.json'))['token'])")
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_D" \
  -F "customerId=$CUS_D" -F "note=g3 dave history checkin" -F "localId=g3-d-$RANDOM" \
  -F "lat=35.8001" -F "lng=51.5001" -o /dev/null
closevisit $TOK_D
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_D" "$BASE/api/app/config")
chk "G3 dave token works pre-deactivation" "[ \"$CODE\" = 200 ]"

# G3.1 edit (name/phone) — applied, hash never leaked, audited
curl -s -b $W/cj -X PUT "$BASE/api/employees/$EMP_D" -H 'content-type: application/json' \
  -d '{"name":"G3 Dave Renamed","phone":"09350000000"}' -o $W/dedit.json
chk "G3 edit name/phone -> ok" "grep -q '\"ok\":true' $W/dedit.json"
chk "G3 edit response has new name" "grep -q 'G3 Dave Renamed' $W/dedit.json"
chk "G3 edit response leaks no hash" "! grep -qE '\"password\"|bcrypt\\$' $W/dedit.json"

# G3.2 validation: empty name / short password -> 400
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/employees/$EMP_D" \
  -H 'content-type: application/json' -d '{"name":"  "}')
chk "G3 edit empty name -> 400" "[ \"$CODE\" = 400 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/employees/$EMP_D" \
  -H 'content-type: application/json' -d '{"password":"abc"}')
chk "G3 edit short password -> 400" "[ \"$CODE\" = 400 ]"

# G3.3 lifecycle endpoints require an admin session
for EP in "PUT /api/employees/$EMP_D" "POST /api/employees/$EMP_D/deactivate" "POST /api/employees/$EMP_D/reactivate" "DELETE /api/employees/$EMP_D" "GET /api/audit-log"; do
  METHOD=${EP%% *}; URL=${EP#* }
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X $METHOD "$BASE$URL" \
    -H 'content-type: application/json' -d '{}')
  chk "G3 unauthorized $METHOD $URL -> 401" "[ \"$CODE\" = 401 ]"
done
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/employees/$EMP_D/deactivate" \
  -H "X-Medpad-Token: $TOK_A2")
chk "G3 deactivate with employee token -> 401" "[ \"$CODE\" = 401 ]"

# G3.4 deactivate: immediate access loss + token revocation
curl -s -b $W/cj -X POST "$BASE/api/employees/$EMP_D/deactivate" -o $W/doff.json
chk "G3 deactivate -> ok with revokedTokens" \
  "python3 -c \"import json;d=json.load(open('$W/doff.json'));assert d['ok'] and d['revokedTokens']>=1\" 2>/dev/null"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_D" "$BASE/api/app/config")
chk "G3 app request IMMEDIATELY 401 after deactivate" "[ \"$CODE\" = 401 ]"

# G3.5 history preserved after deactivation
curl -s -b $W/cj "$BASE/api/checkins" -o $W/ci-after.json
chk "G3 check-in history preserved after deactivate" "grep -q 'g3 dave history checkin' $W/ci-after.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X DELETE "$BASE/api/employees/$EMP_D")
chk "G3 delete WITH history still 409 (history protected)" "[ \"$CODE\" = 409 ]"

# G3.6 reactivate: old token stays dead, new login works
curl -s -b $W/cj -X POST "$BASE/api/employees/$EMP_D/reactivate" -o $W/don.json
chk "G3 reactivate -> ok" "grep -q '\"ok\":true' $W/don.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_D" "$BASE/api/app/config")
chk "G3 revoked token NOT restored by reactivation" "[ \"$CODE\" = 401 ]"
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g3_dave","password":"davepass1"}' -o $W/dl2.json
TOK_D2=$(python3 -c "import json;print(json.load(open('$W/dl2.json'))['token'])" 2>/dev/null || echo "")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_D2" "$BASE/api/app/config")
chk "G3 fresh login after reactivation works" "[ \"$CODE\" = 200 ]"

# G3.7 password reset: revokes tokens, old password dead, new works
curl -s -b $W/cj -X PUT "$BASE/api/employees/$EMP_D" -H 'content-type: application/json' \
  -d '{"password":"davenewpass9"}' -o $W/dreset.json
chk "G3 password reset -> ok, no plaintext in response" \
  "grep -q '\"ok\":true' $W/dreset.json && ! grep -q 'davenewpass9' $W/dreset.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_D2" "$BASE/api/app/config")
chk "G3 tokens revoked by password reset" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/login \
  -H 'content-type: application/json' -d '{"username":"g3_dave","password":"davepass1"}')
chk "G3 old password rejected after reset" "[ \"$CODE\" = 401 ]"
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g3_dave","password":"davenewpass9"}' -o $W/dl3.json
chk "G3 new password login works" "grep -q '\"ok\":true' $W/dl3.json"

# G3.8 idempotence guards: deactivating an inactive employee -> 409
curl -s -b $W/cj -X POST "$BASE/api/employees/$EMP_D/deactivate" -o /dev/null # ensure inactive
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST "$BASE/api/employees/$EMP_D/deactivate")
chk "G3 deactivate already-inactive -> 409" "[ \"$CODE\" = 409 ]"
curl -s -b $W/cj -X POST "$BASE/api/employees/$EMP_D/reactivate" -o /dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST "$BASE/api/employees/$EMP_D/reactivate")
chk "G3 reactivate already-active -> 409" "[ \"$CODE\" = 409 ]"

# G3.9 audit trail: entries exist for the lifecycle actions, actor=admin,
# and NEVER contain the plaintext password
curl -s -b $W/cj "$BASE/api/audit-log?limit=100" -o $W/audit.json
for ACTION in employee.create employee.update employee.deactivate employee.reactivate employee.password-reset; do
  chk "G3 audit contains $ACTION" "grep -q '\"$ACTION\"' $W/audit.json"
done
chk "G3 audit actor is admin" "grep -q '\"actorId\":\"admin\"' $W/audit.json"
chk "G3 audit NEVER contains the reset password" "! grep -q 'davenewpass9' $W/audit.json"
chk "G3 audit NEVER contains bcrypt hashes" "! grep -q 'bcrypt\\$' $W/audit.json"
chk "G3 audit has timestamps" "grep -q '\"createdAt\"' $W/audit.json"

# G3.10 the admin account itself is untouched by employee lifecycle ops
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/stats")
chk "G3 admin session unaffected (no self-lockout)" "[ \"$CODE\" = 200 ]"

# ═════════════════════════════════════════════════════════════
# G-4 — audio file authorization + orphan handling
# ═════════════════════════════════════════════════════════════

# setup: erin + customer + assignment + checkin WITH a voice file
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"g4_erin","password":"erinpass1","name":"G4 Erin"}' -o $W/erin.json
EMP_E=$(python3 -c "import json;print(json.load(open('$W/erin.json'))['employee']['id'])")
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"G4 Erin Pharmacy","lat":35.90,"lng":51.60}' -o $W/ec.json
CUS_E=$(python3 -c "import json;print(json.load(open('$W/ec.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_E\",\"customerId\":\"$CUS_E\"}" -o /dev/null
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g4_erin","password":"erinpass1"}' -o $W/el.json
TOK_E=$(python3 -c "import json;print(json.load(open('$W/el.json'))['token'])")
printf 'erin-voice-bytes-1234' > $W/erin-voice.m4a
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_E" \
  -F "customerId=$CUS_E" -F "note=g4 voice checkin" -F "localId=g4-e-$RANDOM" \
  -F "lat=35.9001" -F "lng=51.6001" -F "voice=@$W/erin-voice.m4a;type=audio/mp4" -o $W/eci.json
chk "G4 checkin with voice ok" "grep -q '\"ok\":true' $W/eci.json"
VPATH=$(python3 -c "import json;print(json.load(open('$W/eci.json'))['checkin']['voicePath'])")
chk "G4 voicePath returned" "[ -n \"$VPATH\" ]"

# G4.1 the OWNER (assigned) can download their audio
curl -s -H "X-Medpad-Token: $TOK_E" "$BASE/api/media/$VPATH" -o $W/got.m4a
chk "G4 owner downloads own audio (assigned customer)" "cmp -s $W/erin-voice.m4a $W/got.m4a"
# ... also via ?token= (the form the Android app uses)
curl -s "$BASE/api/media/$VPATH?token=$TOK_E" -o $W/got2.m4a
chk "G4 owner downloads via ?token= (app playback path)" "cmp -s $W/erin-voice.m4a $W/got2.m4a"

# G4.2 another employee (not owner, not assigned) -> 403
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_B2" "$BASE/api/media/$VPATH")
chk "G4 other employee -> 403" "[ \"$CODE\" = 403 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/media/$VPATH")
chk "G4 no auth -> 401" "[ \"$CODE\" = 401 ]"

# G4.3 admin can download everything
curl -s -b $W/cj "$BASE/api/media/$VPATH" -o $W/got-admin.m4a
chk "G4 admin downloads any audio" "cmp -s $W/erin-voice.m4a $W/got-admin.m4a"

# G4.4 assignment policy applies to OWN historical audio too
curl -s -b $W/cj -X DELETE "$BASE/api/assignments?employeeId=$EMP_E&customerId=$CUS_E" -o /dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_E" "$BASE/api/media/$VPATH")
chk "G4 own audio of UNASSIGNED customer -> 403" "[ \"$CODE\" = 403 ]"
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_E\",\"customerId\":\"$CUS_E\"}" -o /dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_E" "$BASE/api/media/$VPATH")
chk "G4 re-assigned -> owner access restored" "[ \"$CODE\" = 200 ]"

# G4.5 orphaned file: employees denied, admin-only review.
# NOTE: the RUNTIME uploads live at panel/.next/standalone/uploads (Next's
# standalone server.js chdirs there) — derive the dir from the real VPATH.
UP_ROOT="$W/inst/panel/.next/standalone/uploads"
printf 'orphan-audio-bytes' > "$UP_ROOT/voice/orphan-test-12345.m4a"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_E" "$BASE/api/media/voice/orphan-test-12345.m4a")
chk "G4 orphan: employee -> 404 (denied)" "[ \"$CODE\" = 404 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_B2" "$BASE/api/media/voice/orphan-test-12345.m4a")
chk "G4 orphan: another employee -> 404" "[ \"$CODE\" = 404 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/media/voice/orphan-test-12345.m4a")
chk "G4 orphan: admin can still download" "[ \"$CODE\" = 200 ]"

# G4.6 admin orphan listing shows it (with metadata), employees get 401
curl -s -b $W/cj "$BASE/api/admin/media-orphans" -o $W/orphans.json
chk "G4 orphan listing (admin) contains the file" \
  "python3 -c \"import json;o=[x['name'] for x in json.load(open('$W/orphans.json'))['orphans']];assert 'orphan-test-12345.m4a' in o\" 2>/dev/null"
chk "G4 orphan listing has size+mtime" \
  "python3 -c \"import json;o=json.load(open('$W/orphans.json'))['orphans'][0];assert o['size']>0 and 'modifiedAt' in o\" 2>/dev/null"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/admin/media-orphans")
chk "G4 orphan listing without admin -> 401" "[ \"$CODE\" = 401 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_E" "$BASE/api/admin/media-orphans")
chk "G4 orphan listing with employee token -> 401" "[ \"$CODE\" = 401 ]"

# G4.7 path traversal / identifier manipulation
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/media/voice/..%2F..%2Fserver.js")
chk "G4 traversal ../.. -> 400/404" "[ \"$CODE\" = 400 ] || [ \"$CODE\" = 404 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/media/voice/%2E%2E%5Cserver.js")
chk "G4 encoded traversal -> 400/404" "[ \"$CODE\" = 400 ] || [ \"$CODE\" = 404 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj "$BASE/api/media/other/whatever.m4a")
chk "G4 non-voice dir -> 404" "[ \"$CODE\" = 404 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_E" "$BASE/api/media/voice/nonexistent-file.m4a")
chk "G4 nonexistent file -> 404" "[ \"$CODE\" = 404 ]"
# the physical path must never be leaked in responses
chk "G4 responses leak no physical path" "! grep -q 'uploads' $W/orphans.json"

# G4.8 upload + playback regression via the reports flow (app path)
curl -s -H "X-Medpad-Token: $TOK_E" "$BASE/api/app/reports" -o $W/ereports.json
RURL=$(python3 -c "
import json
d = json.load(open('$W/ereports.json'))
r = [x for x in d['reports'] if x.get('voicePath')]
print(r[0]['voiceUrl'] if r else '')")
chk "G4 reports include voiceUrl" "[ -n \"$RURL\" ]"
curl -s -H "X-Medpad-Token: $TOK_E" "$BASE$RURL" -o $W/got3.m4a
chk "G4 playback regression: voiceUrl works for owner" "cmp -s $W/erin-voice.m4a $W/got3.m4a"

# G4.9 UPDATE-preservation regression: the installer must keep the RUNTIME
# voice uploads (panel/.next/standalone/uploads) across an update — this used
# to destroy every voice file because only the unused panel/uploads was kept.
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 1
VNAME=$(basename "$VPATH")
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'RemedT2!pass' > $W/update.log 2>&1)
chk "G4 update install exit 0" "[ -f $W/inst/medpad-config.json ]"
chk "G4 voice file SURVIVED the panel update" "[ -f \"$UP_ROOT/voice/$VNAME\" ]"
cmp -s $W/erin-voice.m4a "$UP_ROOT/voice/$VNAME"
chk "G4 voice content intact after update" "[ $? -eq 0 ]"
chk "G4 orphan file survived the update too" "[ -f \"$UP_ROOT/voice/orphan-test-12345.m4a\" ]"
chk "G4 database survived the update" "[ -f $W/inst/panel/db/custom.db ]"
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot3.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_E" "$BASE/api/app/config")
chk "G4 token still valid after update (db preserved)" "[ \"$CODE\" = 200 ]"
curl -s -H "X-Medpad-Token: $TOK_E" "$BASE/api/media/$VPATH" -o $W/got4.m4a
chk "G4 audio still downloadable after update" "cmp -s $W/erin-voice.m4a $W/got4.m4a"

# ═════════════════════════════════════════════════════════════
# G-6 — GPS validation + customer-distance policy
# ═════════════════════════════════════════════════════════════

# All G-6 check-ins target the SAME employee+customer, so each carries a
# staggered clientAt in the past — otherwise the server's 90s anti-double-
# submit dedup window returns duplicate:true and records nothing.
G6N=0
g6ago() { G6N=$((G6N+1)); python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=$((120-G6N*2)))).isoformat())"; }

# setup: frank + customer at a known point (radius default 150m)
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"g6_frank","password":"frankpass1","name":"G6 Frank"}' -o $W/frank.json
EMP_F=$(python3 -c "import json;print(json.load(open('$W/frank.json'))['employee']['id'])")
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"G6 Frank Clinic","lat":36.0000,"lng":52.0000}' -o $W/fc.json
CUS_F=$(python3 -c "import json;print(json.load(open('$W/fc.json'))['customer']['id'])")
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP_F\",\"customerId\":\"$CUS_F\"}" -o /dev/null
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g6_frank","password":"frankpass1"}' -o $W/fl.json
TOK_F=$(python3 -c "import json;print(json.load(open('$W/fl.json'))['token'])")

# G6.1 default settings present (gpsMaxAccuracyM=50, gpsStrictRadius=0)
curl -s -b $W/cj "$BASE/api/admin/settings" -o $W/g6set.json
chk "G6 default gpsMaxAccuracyM=50" \
  "python3 -c \"import json;s=json.load(open('$W/g6set.json'))['settings'];assert s['gpsMaxAccuracyM']==50 and s['gpsStrictRadius']==0\" 2>/dev/null"

# G6.2 missing GPS coordinates -> recorded WITHOUT location (never customer coords!)
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 no gps" -F "localId=g6-nogps-$RANDOM" -F "clientAt=$(g6ago)" -o $W/g6nogps.json
chk "G6 no-GPS checkin accepted (event preserved)" "grep -q '\"ok\":true' $W/g6nogps.json"
chk "G6 no-GPS: locationMissing=true" "grep -q '\"locationMissing\":true' $W/g6nogps.json"
python3 - "$W/g6nogps.json" 36.0 52.0 << 'EOF'
import json, sys
d = json.load(open(sys.argv[1]))['checkin']
cus_lat, cus_lng = float(sys.argv[2]), float(sys.argv[3])
assert d['lat'] is None and d['lng'] is None, d
# the customer's coordinates were NOT substituted
assert d['lat'] != cus_lat or True
sys.exit(0)
EOF
RC=$?
chk "G6 no-GPS: lat/lng are NULL (not the customer's coords)" "[ $RC -eq 0 ]"
closevisit $TOK_F

# G6.3 invalid coordinates -> same treatment
CODE=$(curl -s -o $W/g6bad.json -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_F" -F "customerId=$CUS_F" -F "note=g6 invalid coords" \
  -F "localId=g6-bad-$RANDOM" -F "lat=999" -F "lng=52.0" -F "clientAt=$(g6ago)")
chk "G6 invalid lat=999 -> accepted, locationMissing" \
  "[ \"$CODE\" = 200 ] && grep -q '\"locationMissing\":true' $W/g6bad.json"
closevisit $TOK_F
CODE=$(curl -s -o $W/g6bad2.json -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_F" -F "customerId=$CUS_F" -F "note=g6 nan coords" \
  -F "localId=g6-nan-$RANDOM" -F "lat=NaN" -F "lng=Infinity" -F "clientAt=$(g6ago)")
chk "G6 NaN/Infinity coords -> accepted, locationMissing" \
  "[ \"$CODE\" = 200 ] && grep -q '\"locationMissing\":true' $W/g6bad2.json"
closevisit $TOK_F

# G6.4 valid GPS near the customer -> recorded with accuracy + distance, in radius
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 good gps" -F "localId=g6-good-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=15" -o $W/g6good.json
chk "G6 good GPS accepted" "grep -q '\"ok\":true' $W/g6good.json"
chk "G6 good GPS: locationMissing=false" "grep -q '\"locationMissing\":false' $W/g6good.json"
chk "G6 accuracy stored (15)" "grep -q '\"accuracy\":15' $W/g6good.json"
python3 - "$W/g6good.json" << 'EOF'
import json, sys
d = json.load(open(sys.argv[1]))['checkin']
assert d['distanceM'] is not None and 0 < d['distanceM'] < 150, d
assert d['outOfRadius'] is False, d
sys.exit(0)
EOF
RC=$?
chk "G6 distance computed (Haversine, meters), in radius" "[ $RC -eq 0 ]"
closevisit $TOK_F

# G6.5 accuracy over tolerance (default 50m) -> location discarded
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 low accuracy" -F "localId=g6-lowacc-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=200" -o $W/g6lowacc.json
chk "G6 accuracy=200 > tolerance -> locationMissing" "grep -q '\"locationMissing\":true' $W/g6lowacc.json"
closevisit $TOK_F

# G6.6 impossible accuracy (<=0 / huge) -> location discarded even with tolerance off
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"gpsMaxAccuracyM":0}' -o /dev/null
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 zero acc" -F "localId=g6-zacc-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=0" -o $W/g6zacc.json
chk "G6 accuracy=0 (impossible) -> locationMissing even with tolerance off" "grep -q '\"locationMissing\":true' $W/g6zacc.json"
closevisit $TOK_F
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 huge acc" -F "localId=g6-hacc-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=999999" -o $W/g6hacc.json
chk "G6 accuracy=999999 (impossible) -> locationMissing" "grep -q '\"locationMissing\":true' $W/g6hacc.json"
closevisit $TOK_F

# G6.7 tolerance OFF + plausible accuracy -> location kept
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 tolerance off" -F "localId=g6-toff-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=300" -o $W/g6toff.json
chk "G6 tolerance off: accuracy=300 kept, location recorded" "grep -q '\"locationMissing\":false' $W/g6toff.json"
closevisit $TOK_F

# G6.8 out-of-radius (default warn policy): recorded + flagged, NOT rejected
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"gpsMaxAccuracyM":50}' -o /dev/null
# ~0.05 degrees away = ~5km — far outside the 150m radius
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 far away" -F "localId=g6-far-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.0500" -F "lng=52.0500" -F "accuracy=10" -o $W/g6far.json
chk "G6 out-of-radius (warn mode): event ACCEPTED" "grep -q '\"ok\":true' $W/g6far.json"
chk "G6 out-of-radius flagged outOfRadius=true" "grep -q '\"outOfRadius\":true' $W/g6far.json"
closevisit $TOK_F
python3 - "$W/g6far.json" << 'EOF'
import json, sys
d = json.load(open(sys.argv[1]))['checkin']
assert d['distanceM'] and d['distanceM'] > 1000, d
sys.exit(0)
EOF
RC=$?
chk "G6 distance recorded (>> radius)" "[ $RC -eq 0 ]"

# G6.9 strict mode: out-of-radius -> 422 with actionable Persian error
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"gpsStrictRadius":1}' -o /dev/null
CODE=$(curl -s -o $W/g6strict.json -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_F" -F "customerId=$CUS_F" -F "note=g6 strict reject" \
  -F "localId=g6-strict-$RANDOM" -F "lat=36.0500" -F "lng=52.0500" -F "accuracy=10" -F "clientAt=$(g6ago)")
chk "G6 strict mode: out-of-radius -> 422" "[ \"$CODE\" = 422 ]"
chk "G6 strict rejection message mentions the distance" "grep -q 'شعاع' $W/g6strict.json"
# ... while an in-radius check-in still works in strict mode
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkin \
  -H "X-Medpad-Token: $TOK_F" -F "customerId=$CUS_F" -F "note=g6 strict ok" \
  -F "localId=g6-strictok-$RANDOM" -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=10" -F "clientAt=$(g6ago)")
chk "G6 strict mode: in-radius check-in still works" "[ \"$CODE\" = 200 ]"
closevisit $TOK_F
# back to warn mode for the rest of the suite
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"gpsStrictRadius":0}' -o /dev/null

# G6.10 settings validation for the GPS keys
for BADG in '-1' '501' '7.5' 'x'; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
    -H 'content-type: application/json' -d "{\"gpsMaxAccuracyM\":\"$BADG\"}")
  chk "G6 gpsMaxAccuracyM='$BADG' -> 400" "[ \"$CODE\" = 400 ]"
done
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"gpsStrictRadius":2}')
chk "G6 gpsStrictRadius=2 -> 400" "[ \"$CODE\" = 400 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X PUT "$BASE/api/admin/settings" \
  -H 'content-type: application/json' -d '{"gpsMaxAccuracyM":0}')
chk "G6 gpsMaxAccuracyM boundary 0 -> 200" "[ \"$CODE\" = 200 ]"
curl -s -b $W/cj -X PUT "$BASE/api/admin/settings" -H 'content-type: application/json' \
  -d '{"gpsMaxAccuracyM":50}' -o /dev/null

# G6.11 configurable per-customer radius: shrink to 10m -> the 'good' fix is now outside
curl -s -b $W/cj -X PUT "$BASE/api/customers/$CUS_F" -H 'content-type: application/json' \
  -d '{"radius":10}' -o /dev/null
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 small radius" -F "localId=g6-sr-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=10" -o $W/g6sr.json
chk "G6 per-customer radius=10m: same fix now outOfRadius" "grep -q '\"outOfRadius\":true' $W/g6sr.json"
closevisit $TOK_F
curl -s -b $W/cj -X PUT "$BASE/api/customers/$CUS_F" -H 'content-type: application/json' \
  -d '{"radius":150}' -o /dev/null

# G6.12 old app compatibility: no accuracy field at all -> location kept (no filtering)
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 legacy app no accuracy" -F "localId=g6-leg-$RANDOM" -F "clientAt=$(g6ago)" \
  -F "lat=36.000100" -F "lng=52.000100" -o $W/g6leg.json
chk "G6 old app (no accuracy field): location recorded" \
  "grep -q '\"locationMissing\":false' $W/g6leg.json && grep -q '\"accuracy\":null' $W/g6leg.json"

# G6.13 checkout also records location status (and no substitution)
printf 'g6-voice' > $W/g6v.m4a
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOK_F" \
  -F "note=g6 checkout with location" -F "localId=g6-co-$RANDOM" \
  -F "lat=36.000100" -F "lng=52.000100" -F "accuracy=12" \
  -F "voice=@$W/g6v.m4a;type=audio/mp4" -o $W/g6co.json
chk "G6 checkout records location + accuracy" \
  "grep -q '\"locationMissing\":false' $W/g6co.json && grep -q '\"accuracy\":12' $W/g6co.json"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK_F" \
  -F "customerId=$CUS_F" -F "note=g6 reopen for checkout test" -F "localId=g6-re-$RANDOM" -F "clientAt=$(g6ago)" -o /dev/null
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOK_F" \
  -F "note=g6 checkout without location" -F "localId=g6-co2-$RANDOM" \
  -F "voice=@$W/g6v.m4a;type=audio/mp4" -o $W/g6co2.json
chk "G6 checkout without GPS -> locationMissing, nulls" \
  "grep -q '\"locationMissing\":true' $W/g6co2.json"

# G6.14 direct SQLite verification of the stored rows
python3 - "$DB" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
rows = db.execute("select lat, lng, accuracy, distanceM, outOfRadius, locationMissing from Checkin where note like 'g6 %' order by createdAt").fetchall()
nogps = [r for r in rows if r[5] == 1]
good  = [r for r in rows if r[0] is not None and r[5] == 0]
# every locationMissing row has NULL coords; every located row has real coords
assert all(r[0] is None and r[1] is None and r[2] is None for r in nogps), rows
assert all(abs(r[0] - 36.0) < 0.1 for r in good), rows
# no stored coordinate equals the customer's exact point (36.0000, 52.0000)
assert not any(abs(r[0] - 36.0) < 1e-9 and abs(r[1] - 52.0) < 1e-9 for r in rows if r[0] is not None), rows
db.close()
EOF
RC=$?
chk "G6 SQLite: rows consistent (nulls when missing, real device coords only)" "[ $RC -eq 0 ]"

# G6.15 runtime migration for EXISTING databases: drop the new Checkin
# columns (simulating a pre-G-6 install) -> reboot -> columns restored
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 1
python3 - "$DB" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
for col in ('accuracy', 'distanceM', 'outOfRadius', 'locationMissing'):
    try: db.execute(f'ALTER TABLE Checkin DROP COLUMN "{col}"')
    except Exception as e: print('drop skipped:', e)
db.commit()
cols = [r[1] for r in db.execute('PRAGMA table_info(Checkin)')]
assert 'accuracy' not in cols and 'locationMissing' not in cols, cols
db.close()
EOF
RC=$?
chk "G6 pre-G-6 schema simulated (Checkin columns dropped)" "[ $RC -eq 0 ]"
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot4.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
python3 - "$DB" << 'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
cols = [r[1] for r in db.execute('PRAGMA table_info(Checkin)')]
db.close()
need = {'accuracy', 'distanceM', 'outOfRadius', 'locationMissing'}
sys.exit(0 if need.issubset(set(cols)) else 1)
EOF
RC=$?
chk "G6 migration restored Checkin columns after reboot" "[ $RC -eq 0 ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK_F" "$BASE/api/app/config")
chk "G6 auth works against the migrated DB" "[ \"$CODE\" = 200 ]"
curl -s -b $W/cj "$BASE/api/checkins?limit=300" -o $W/g6list.json
chk "G6 admin checkins list carries the new fields" "grep -q 'locationMissing' $W/g6list.json"

# ── teardown ─────────────────────────────────────────────────
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 1
chk "R0 ports free after stop" "! ss -tln 2>/dev/null | grep -q ':$PANEL_PORT '"

echo ""
echo "=============================================="
echo "  RESULT: $PASS PASS / $FAIL FAIL"
echo "=============================================="
[ "$FAIL" = 0 ]
