#!/usr/bin/env bash
# test-functional-core.sh — MedPad phase 1 / task 1 FUNCTIONAL core suite.
# Run:  bash test-functional-core.sh [PKG_DIR]
#
# Unlike test-v1912.sh (installer/security pipeline), this suite boots a real
# panel and drives the FUNCTIONAL core over HTTP the same way the Android app
# and the admin browser do:
#
#   F1  app auth: bad password 401, good login 200+token, no-token 401,
#       deactivated employee locked out
#   F2  password hashes never serialized (GET/POST/PUT employees)
#   F3  employee DELETE: with history -> 409 (data preserved), without -> 200
#   F4  check-in happy path + localId idempotency (duplicate:true, same row)
#   F5  offline queue timestamps (clientAt): event time honored within the
#       sanity window, ignored outside it / garbage; visit start/end follow
#       the event time; checkout stamps its own clientAt
#   F6  checkout validation: short note 400, no open visit 409
#   F7  data isolation: employee reports contain only own rows; media 401
#   F8  app-login rate limiting (5 failures -> 429 + retry-after)
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

W=/tmp/tfunccore; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3240; RT_PORT=3243; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── install (fresh, non-interactive) ─────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'FuncCore1!' > $W/install.log 2>&1)
chk "F0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

# ── boot ─────────────────────────────────────────────────────
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do
  curl -s -o /dev/null "$BASE/" && break; sleep 1
done
chk "F0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

# admin session
curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"FuncCore1!"}' -o $W/admin-login.json
chk "F0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

# ── setup: two employees + two customers via the REAL admin API ──
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"ff_alice","password":"alicepass1","name":"Alice Test","phone":"555"}' -o $W/emp1.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"ff_bob","password":"bobpass11","name":"Bob Test","phone":"556"}' -o $W/emp2.json
EMP1=$(python3 -c "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(python3 -c "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "F0 employees created (ids returned)" "[ -n '$EMP1' ] && [ -n '$EMP2' ]"

curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"FuncCore Clinic A","lat":35.70,"lng":51.40,"type":"clinic"}' -o $W/cus1.json
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"FuncCore Pharmacy B","lat":35.71,"lng":51.41,"type":"pharmacy"}' -o $W/cus2.json
CUS1=$(python3 -c "import json;print(json.load(open('$W/cus1.json'))['customer']['id'])")
CUS2=$(python3 -c "import json;print(json.load(open('$W/cus2.json'))['customer']['id'])")
chk "F0 customers created" "[ -n '$CUS1' ] && [ -n '$CUS2' ]"

# ── F2: password hashes never serialized ────────────────────
curl -s -b $W/cj $BASE/api/employees -o $W/emps.json
chk "F2 GET employees: no password hash" "! grep -qE '\"password\"|bcrypt\\$' $W/emps.json"
chk "F2 POST employee: no password hash" "! grep -qE '\"password\"|bcrypt\\$' $W/emp1.json"
curl -s -b $W/cj -X PUT $BASE/api/employees/$EMP2 -H 'content-type: application/json' \
  -d '{"phone":"557"}' -o $W/emp2-put.json
chk "F2 PUT employee: no password hash" "! grep -qE '\"password\"|bcrypt\\$' $W/emp2-put.json"
chk "F2 PUT employee: update applied" "grep -q '\"557\"' $W/emp2-put.json"

# ── F1: app auth ─────────────────────────────────────────────
CODE=$(curl -s -o $W/app-login-bad.json -w '%{http_code}' -X POST $BASE/api/app/login \
  -H 'content-type: application/json' -d '{"username":"ff_alice","password":"WRONG"}')
chk "F1 wrong password -> 401" "[ \"$CODE\" = 401 ]"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"ff_alice","password":"alicepass1"}' -o $W/app-login1.json
chk "F1 employee login ok" "grep -q '\"ok\":true' $W/app-login1.json && grep -q 'token' $W/app-login1.json"
TOK1=$(python3 -c "import json;print(json.load(open('$W/app-login1.json'))['token'])")

CODE=$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/app/customers)
chk "F1 customers without token -> 401" "[ \"$CODE\" = 401 ]"

# deactivated employee is locked out (also exercises PUT active=false)
curl -s -b $W/cj -X PUT $BASE/api/employees/$EMP2 -H 'content-type: application/json' \
  -d '{"active":false}' -o /dev/null
CODE=$(curl -s -o $W/app-login2.json -w '%{http_code}' -X POST $BASE/api/app/login \
  -H 'content-type: application/json' -d '{"username":"ff_bob","password":"bobpass11"}')
chk "F1 deactivated employee login -> 401" "[ \"$CODE\" = 401 ]"
# reactivate for later checks
curl -s -b $W/cj -X PUT $BASE/api/employees/$EMP2 -H 'content-type: application/json' \
  -d '{"active":true}' -o /dev/null

# ── F4: check-in happy path + idempotency ────────────────────
LID1="fcore-$RANDOM-1"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=functional suite checkin" -F "localId=$LID1" \
  -F "lat=35.7001" -F "lng=51.4001" -o $W/ci1.json
chk "F4 checkin ok" "grep -q '\"ok\":true' $W/ci1.json"
CI1=$(python3 -c "import json;print(json.load(open('$W/ci1.json'))['checkin']['id'])")

curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=REPLAY same localId" -F "localId=$LID1" \
  -o $W/ci1r.json
chk "F4 localId replay -> duplicate:true" "grep -q '\"duplicate\":true' $W/ci1r.json"
chk "F4 localId replay -> same row id" "grep -q '\"id\":\"$CI1\"' $W/ci1r.json"

curl -s -b $W/cj "$BASE/api/checkins" -o $W/checkins.json
chk "F4 admin sees the checkin" "grep -q '$CI1' $W/checkins.json"

# ── F5: offline-queue event time (clientAt) ──────────────────
# 5a: checkin with clientAt 3h ago (inside the 7-day window)
CA_3H=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(hours=3)).isoformat())")
LID2="fcore-$RANDOM-2"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS2" -F "note=offline queued 3h ago" -F "localId=$LID2" \
  -F "clientAt=$CA_3H" -o $W/ci2.json
python3 - "$W/ci2.json" "$CA_3H" << 'EOF'
import json, sys, datetime
d = json.load(open(sys.argv[1]))
created = datetime.datetime.fromisoformat(d["checkin"]["createdAt"].replace("Z","+00:00"))
client  = datetime.datetime.fromisoformat(sys.argv[2])
sys.exit(0 if abs((created - client).total_seconds()) < 120 else 1)
EOF
RC=$?
chk "F5 clientAt (3h ago) honored in checkin createdAt" "[ $RC -eq 0 ]"

# 5b: checkout with its own clientAt (2h ago) — voice REQUIRED
printf 'fake-audio' > $W/voice.m4a
CA_2H=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(hours=2)).isoformat())")
LID5="fcore-$RANDOM-5"
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOK1" \
  -F "note=leaving after visit done" -F "localId=$LID5" \
  -F "clientAt=$CA_2H" -F "voice=@$W/voice.m4a;type=audio/mp4" -o $W/co1.json
chk "F5 checkout with clientAt ok" "grep -q '\"ok\":true' $W/co1.json"
python3 - "$W/co1.json" "$CA_2H" << 'EOF'
import json, sys, datetime
d = json.load(open(sys.argv[1]))
created = datetime.datetime.fromisoformat(d["checkout"]["createdAt"].replace("Z","+00:00"))
client  = datetime.datetime.fromisoformat(sys.argv[2])
sys.exit(0 if abs((created - client).total_seconds()) < 120 else 1)
EOF
RC=$?
chk "F5 clientAt (2h ago) honored in checkout createdAt" "[ $RC -eq 0 ]"

# 5c: verify the VISIT row carries both event times (direct SQLite read)
# NOTE: Prisma's SQLite driver stores DateTime as epoch-MILLISECOND integers.
DB=$W/inst/panel/db/custom.db
python3 - "$DB" "$CA_3H" "$CA_2H" << 'EOF'
import sqlite3, sys, datetime
db = sqlite3.connect(sys.argv[1])
client_in  = datetime.datetime.fromisoformat(sys.argv[2])
client_out = datetime.datetime.fromisoformat(sys.argv[3])
def parse(v):
    if isinstance(v, (int, float)):                      # epoch ms (Prisma sqlite)
        return datetime.datetime.fromtimestamp(v / 1000, datetime.timezone.utc)
    return datetime.datetime.fromisoformat(str(v).replace("Z", "+00:00"))
rows = db.execute("select startAt, endAt, open from Visit").fetchall()
match = [r for r in rows if abs((parse(r[0]) - client_in).total_seconds()) < 120]
ok = len(match) == 1 and match[0][2] == 0 \
     and abs((parse(match[0][1]) - client_out).total_seconds()) < 120
db.close()
sys.exit(0 if ok else 1)
EOF
RC=$?
chk "F5 visit startAt~3h-ago + endAt~2h-ago, closed" "[ $RC -eq 0 ]"

# 5d: clientAt 30 days ago (outside window) -> server clock fallback
CA_30D=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(days=30)).isoformat())")
LID3="fcore-$RANDOM-3"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS2" -F "note=too old clientAt" -F "localId=$LID3" \
  -F "clientAt=$CA_30D" -o $W/ci3.json
python3 - "$W/ci3.json" << 'EOF'
import json, sys, datetime
d = json.load(open(sys.argv[1]))
created = datetime.datetime.fromisoformat(d["checkin"]["createdAt"].replace("Z","+00:00"))
now     = datetime.datetime.now(datetime.timezone.utc)
sys.exit(0 if abs((created - now).total_seconds()) < 120 else 1)
EOF
RC=$?
chk "F5 stale clientAt (30d) falls back to now" "[ $RC -eq 0 ]"

# 5e: garbage clientAt -> server clock fallback
LID4="fcore-$RANDOM-4"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS2" -F "note=garbage time" -F "localId=$LID4" \
  -F "clientAt=not-a-date" -o $W/ci4.json
python3 - "$W/ci4.json" << 'EOF'
import json, sys, datetime
d = json.load(open(sys.argv[1]))
created = datetime.datetime.fromisoformat(d["checkin"]["createdAt"].replace("Z","+00:00"))
now     = datetime.datetime.now(datetime.timezone.utc)
sys.exit(0 if abs((created - now).total_seconds()) < 120 else 1)
EOF
RC=$?
chk "F5 garbage clientAt falls back to now" "[ $RC -eq 0 ]"

# ── F6: checkout validation ──────────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkout \
  -H "X-Medpad-Token: $TOK1" -F "note=short" -F "localId=fcore-short-$RANDOM" \
  -F "voice=@$W/voice.m4a;type=audio/mp4")
chk "F6 short note -> 400" "[ \"$CODE\" = 400 ]"

# close the open visit left by the fallback checkins (valid checkout, now)
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOK1" \
  -F "note=closing the fallback-test visit" -F "localId=fcore-close-$RANDOM" \
  -F "voice=@$W/voice.m4a;type=audio/mp4" -o $W/co2.json
chk "F6 closing checkout ok" "grep -q '\"ok\":true' $W/co2.json"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/checkout \
  -H "X-Medpad-Token: $TOK1" -F "note=no open visit exists now" -F "localId=fcore-noopen-$RANDOM" \
  -F "voice=@$W/voice.m4a;type=audio/mp4")
chk "F6 no open visit -> 409" "[ \"$CODE\" = 409 ]"

# ── F3: employee delete (AFTER history exists) ───────────────
CODE=$(curl -s -o $W/del1.json -w '%{http_code}' -b $W/cj -X DELETE $BASE/api/employees/$EMP1)
chk "F3 delete employee WITH history -> 409" "[ \"$CODE\" = 409 ]"
chk "F3 409 body is actionable (mentions history)" "grep -q 'سابقه' $W/del1.json"

# employee without history deletes fine
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"ff_temp","password":"temppass1","name":"Temp NoHistory"}' -o $W/emp3.json
EMP3=$(python3 -c "import json;print(json.load(open('$W/emp3.json'))['employee']['id'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X DELETE $BASE/api/employees/$EMP3)
chk "F3 delete employee WITHOUT history -> 200" "[ \"$CODE\" = 200 ]"

# ── F7: data isolation ───────────────────────────────────────
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"ff_bob","password":"bobpass11"}' -o $W/app-login2b.json
TOK2=$(python3 -c "import json;print(json.load(open('$W/app-login2b.json'))['token'])" 2>/dev/null || echo "")
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/reports" -o $W/rep2.json
chk "F7 bob's reports exclude alice's checkins" "! grep -q '$CI1' $W/rep2.json"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/media/voice/nonexistent.m4a")
chk "F7 media without auth -> 401" "[ \"$CODE\" = 401 ]"

# ── F8: app-login rate limiting ──────────────────────────────
# 5 failures on a THIRD username -> 429 (alice/bob tokens must stay usable)
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"ff_rl","password":"rlpass111","name":"RL Test"}' -o /dev/null
LAST=000
for i in 1 2 3 4 5 6; do
  LAST=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/login \
    -H 'content-type: application/json' -d '{"username":"ff_rl","password":"bad"}')
done
chk "F8 app-login rate limit 429 after repeated failures" "[ \"$LAST\" = 429 ]"
RA=$(curl -s -D - -o /dev/null -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"ff_rl","password":"bad"}' | grep -ci '^retry-after:')
chk "F8 retry-after header present" "[ \"$RA\" -ge 1 ]"

# ── teardown ─────────────────────────────────────────────────
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 1
chk "F0 ports free after stop" "! ss -tln 2>/dev/null | grep -q ':$PANEL_PORT '"

echo ""
echo "=============================================="
echo "  RESULT: $PASS PASS / $FAIL FAIL"
echo "=============================================="
[ "$FAIL" = 0 ]
