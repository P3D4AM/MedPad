#!/usr/bin/env bash
# test-assignment-requests.sh — package 6 (phase 2 / task 4): customer
# assignment REQUESTS, end-to-end against a REAL installed panel.
# Run:  bash test-assignment-requests.sh [PKG_DIR]
#
#   AR0  fresh install + boot + fixtures (2 employees, 3 customers, plans)
#   AR1  submission gates: 401 (no token) / 400 (no id, long note) / 404
#   AR2  happy path THROUGH THE REAL UX: emp2 sees a MASKED plan for an
#        unassigned customer -> extracts its customerId -> requests it;
#        admin bell gets a kind='request' PanelNotification
#   AR3  duplicate pending -> 409; already-assigned -> 409
#   AR4  admin list (names resolved, pendingCount) + 401 gate + bad status
#   AR5  employee GET: own requests only; PENDING request masks the customer
#        name (assigned:false, customerName:null) — no unmasking on request
#   AR6  APPROVE: assignment created, request approved, employee notified
#        (AppNotification), customer now in the rep's /api/app/customers
#        sync + customerName visible in the request list
#   AR7  decided request is terminal: approve/reject again -> 409
#   AR8  REJECT with adminNote: rep sees rejected+note, notification body
#        carries NO customer name (masking), no assignment created
#   AR9  re-request after rejection is allowed (partial index only covers
#        pending rows)
#   AR10 supersede: admin assigns manually while request pending -> approve
#        returns alreadyAssigned, NO duplicate Assignment row
#   AR11 deleted customer: approve -> 409, request stays pending
#   AR12 audit rows: assignment_request.create (actor=employee) / .approve /
#        .reject present in /api/audit-log
#   AR13 PERSISTENCE: server restart -> requests survive
#   AR14 UPGRADE PATH: drop the AssignmentRequest table (pre-pkg6 database)
#        -> restart -> instrumentation recreates table+partial index and a
#        new request works
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

W=/tmp/tareq; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3263; RT_PORT=3266; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── AR0: install + boot + fixtures ───────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'Areq123!' > $W/install.log 2>&1)
chk "AR0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "AR0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"Areq123!"}' -o $W/admin-login.json
chk "AR0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"r_ali","password":"alipass123","name":"R Ali","phone":"555"}' -o $W/emp1.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"r_sara","password":"sarapass123","name":"R Sara","phone":"556"}' -o $W/emp2.json
EMP1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "AR0 employees created" "[ -n '$EMP1' ] && [ -n '$EMP2' ]"

curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"Req Clinic","lat":35.70,"lng":51.40,"type":"clinic"}' -o $W/cus1.json
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"Req Pharmacy","lat":35.71,"lng":51.41,"type":"pharmacy"}' -o $W/cus2.json
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"Req Doomed","lat":35.72,"lng":51.42,"type":"doctor"}' -o $W/cus3.json
CUS1=$(py "import json;print(json.load(open('$W/cus1.json'))['customer']['id'])")
CUS2=$(py "import json;print(json.load(open('$W/cus2.json'))['customer']['id'])")
CUS3=$(py "import json;print(json.load(open('$W/cus3.json'))['customer']['id'])")
chk "AR0 customers created" "[ -n '$CUS1' ] && [ -n '$CUS2' ] && [ -n '$CUS3' ]"

# emp1 already owns CUS2 (for the already-assigned + reject tests)
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS2\"}" -o $W/asg1.json
chk "AR0 emp1 owns CUS2" "grep -q '\"ok\":true' $W/asg1.json"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"r_ali","password":"alipass123"}' -o $W/app1.json
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"r_sara","password":"sarapass123"}' -o $W/app2.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
TOK2=$(py "import json;print(json.load(open('$W/app2.json'))['token'])")
chk "AR0 app logins" "[ -n '$TOK1' ] && [ -n '$TOK2' ]"

# a plan for emp2 on the UNASSIGNED CUS1 — the real UX path to discover it
TODAY=$(date +%Y-%m-%d)
curl -s -b $W/cj -X POST $BASE/api/planned-visits -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP2\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"${TODAY}T10:00:00.000Z\",\"durationMin\":30,\"priority\":\"normal\"}" -o $W/plan1.json
chk "AR0 plan created (emp2 -> CUS1, unassigned)" "grep -q '\"ok\":true' $W/plan1.json"

# ── AR1: submission gates ─────────────────────────────────────
CODE=$(curl -s -o $W/g1.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
  -H 'content-type: application/json' -d "{\"customerId\":\"$CUS1\"}")
chk "AR1 no token -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/g2.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
  -H "X-Medpad-Token: $TOK2" -H 'content-type: application/json' -d '{}')
chk "AR1 missing customerId -> 400" "[ '$CODE' = '400' ]"
LONGNOTE=$(python3 -c "print('x'*501)")
CODE=$(curl -s -o $W/g3.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
  -H "X-Medpad-Token: $TOK2" -H 'content-type: application/json' \
  -d "{\"customerId\":\"$CUS1\",\"note\":\"$LONGNOTE\"}")
chk "AR1 note >500 chars -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/g4.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
  -H "X-Medpad-Token: $TOK2" -H 'content-type: application/json' \
  -d '{"customerId":"no-such-customer"}')
chk "AR1 unknown customer -> 404" "[ '$CODE' = '404' ]"

# ── AR2: happy path through the real UX (masked plan -> request) ──
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/planned-visits?date=$TODAY" -o $W/emp2plans.json
MASKED_CUS=$(py "
import json
d = json.load(open('$W/emp2plans.json'))
cus = [p['customerId'] for p in d['plans'] if not p['customer']['assigned']]
print(cus[0] if cus else '')")
chk "AR2 emp2 sees the masked unassigned customer in plans" "[ '$MASKED_CUS' = '$CUS1' ]"
MASKED_NAME=$(py "
import json
d = json.load(open('$W/emp2plans.json'))
p = [p for p in d['plans'] if not p['customer']['assigned']][0]
print(p['customer']['name'])")
chk "AR2 masked plan hides the customer name (name null)" "[ '$MASKED_NAME' = 'None' ]"

curl -s -X POST $BASE/api/app/assignment-requests -H "X-Medpad-Token: $TOK2" \
  -H 'content-type: application/json' \
  -d "{\"customerId\":\"$MASKED_CUS\",\"note\":\"لطفاً این کلینیک به من اختصاص یابد\"}" -o $W/req1.json
chk "AR2 request submitted" "grep -q '\"ok\":true' $W/req1.json"
REQ1=$(py "import json;print(json.load(open('$W/req1.json'))['request']['id'])")
chk "AR2 request is pending" "py \"import json;exit(0 if json.load(open('$W/req1.json'))['request']['status']=='pending' else 1)\""

curl -s -b $W/cj "$BASE/api/panel-notifications" -o $W/pn1.json
chk "AR2 admin bell got a kind=request notification" "py \"import json;d=json.load(open('$W/pn1.json'));exit(0 if any(n['kind']=='request' for n in d['notifications']) else 1)\""

# ── AR3: duplicates ───────────────────────────────────────────
CODE=$(curl -s -o $W/dup1.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
  -H "X-Medpad-Token: $TOK2" -H 'content-type: application/json' -d "{\"customerId\":\"$CUS1\"}")
chk "AR3 duplicate pending -> 409" "[ '$CODE' = '409' ]"
CODE=$(curl -s -o $W/dup2.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' -d "{\"customerId\":\"$CUS2\"}")
chk "AR3 already-assigned customer -> 409" "[ '$CODE' = '409' ]"

# ── AR4: admin list + gates ───────────────────────────────────
curl -s -b $W/cj "$BASE/api/assignment-requests" -o $W/arlist.json
chk "AR4 admin list ok" "grep -q '\"ok\":true' $W/arlist.json"
chk "AR4 pendingCount = 1" "py \"import json;exit(0 if json.load(open('$W/arlist.json'))['pendingCount']==1 else 1)\""
chk "AR4 names resolved (Sara -> Req Clinic)" "py \"import json;d=json.load(open('$W/arlist.json'));r=d['requests'][0];exit(0 if r['employee']['name']=='R Sara' and r['customer']['name']=='Req Clinic' else 1)\""
CODE=$(curl -s -o $W/ar401.json -w '%{http_code}' "$BASE/api/assignment-requests")
chk "AR4 admin list without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/arbad.json -w '%{http_code}' -b $W/cj "$BASE/api/assignment-requests?status=weird")
chk "AR4 invalid status -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/ap401.json -w '%{http_code}' -X POST "$BASE/api/assignment-requests/$REQ1/approve")
chk "AR4 approve without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/rj401.json -w '%{http_code}' -X POST "$BASE/api/assignment-requests/$REQ1/reject" \
  -H 'content-type: application/json' -d '{}')
chk "AR4 reject without session -> 401" "[ '$CODE' = '401' ]"

# ── AR5: employee list — isolation + masking ──────────────────
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/assignment-requests" -o $W/emp2reqs.json
chk "AR5 emp2 sees own request" "py \"import json;d=json.load(open('$W/emp2reqs.json'));exit(0 if len(d['requests'])==1 and d['requests'][0]['id']=='$REQ1' else 1)\""
chk "AR5 PENDING request masks the customer name" "py \"import json;d=json.load(open('$W/emp2reqs.json'));r=d['requests'][0];exit(0 if r['customerName'] is None and r['assigned']==False else 1)\""
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/assignment-requests" -o $W/emp1reqs.json
chk "AR5 emp1 sees no requests (isolation)" "py \"import json;exit(0 if len(json.load(open('$W/emp1reqs.json'))['requests'])==0 else 1)\""

# ── AR6: approve ──────────────────────────────────────────────
curl -s -b $W/cj -X POST "$BASE/api/assignment-requests/$REQ1/approve" -o $W/ap1.json
chk "AR6 approve ok" "grep -q '\"ok\":true' $W/ap1.json"
chk "AR6 approve was a NEW assignment (not alreadyAssigned)" "grep -q '\"alreadyAssigned\":false' $W/ap1.json"
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asglist.json
chk "AR6 assignment emp2-CUS1 exists" "py \"import json;d=json.load(open('$W/asglist.json'));exit(0 if any(a['employeeId']=='$EMP2' and a['customerId']=='$CUS1' for a in d['assignments']) else 1)\""
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/customers" -o $W/emp2cust.json
chk "AR6 customer now in emp2's sync" "py \"import json;d=json.load(open('$W/emp2cust.json'));exit(0 if any(c['id']=='$CUS1' for c in d['customers']) else 1)\""
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/assignment-requests" -o $W/emp2reqs2.json
chk "AR6 request approved + name unmasked for its owner" "py \"import json;d=json.load(open('$W/emp2reqs2.json'));r=d['requests'][0];exit(0 if r['status']=='approved' and r['customerName']=='Req Clinic' else 1)\""
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/notifications" -o $W/emp2notifs.json
chk "AR6 emp2 notified of the approval" "py \"import json;d=json.load(open('$W/emp2notifs.json'));exit(0 if any('تأیید شد' in n['title'] and 'Req Clinic' in n['body'] for n in d['notifications']) else 1)\""

# ── AR7: terminal state ───────────────────────────────────────
CODE=$(curl -s -o $W/ap2.json -w '%{http_code}' -b $W/cj -X POST "$BASE/api/assignment-requests/$REQ1/approve")
chk "AR7 re-approve -> 409" "[ '$CODE' = '409' ]"
CODE=$(curl -s -o $W/rj2.json -w '%{http_code}' -b $W/cj -X POST "$BASE/api/assignment-requests/$REQ1/reject" \
  -H 'content-type: application/json' -d '{}')
chk "AR7 reject after approve -> 409" "[ '$CODE' = '409' ]"

# ── AR8: reject with a note ────────────────────────────────────
curl -s -X POST $BASE/api/app/assignment-requests -H "X-Medpad-Token: $TOK2" \
  -H 'content-type: application/json' -d "{\"customerId\":\"$CUS2\"}" -o $W/req2.json
chk "AR8 emp2 requests CUS2 (owned by emp1)" "grep -q '\"ok\":true' $W/req2.json"
REQ2=$(py "import json;print(json.load(open('$W/req2.json'))['request']['id'])")
curl -s -b $W/cj -X POST "$BASE/api/assignment-requests/$REQ2/reject" \
  -H 'content-type: application/json' \
  -d '{"adminNote":"این داروخانه به مدرپ دیگری اختصاص دارد"}' -o $W/rj1.json
chk "AR8 reject ok" "grep -q '\"ok\":true' $W/rj1.json"
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/assignment-requests" -o $W/emp2reqs3.json
chk "AR8 emp2 sees rejected + adminNote" "py \"import json;d=json.load(open('$W/emp2reqs3.json'));r=[x for x in d['requests'] if x['id']=='$REQ2'][0];exit(0 if r['status']=='rejected' and 'مدرپ دیگری' in r['adminNote'] else 1)\""
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/notifications" -o $W/emp2notifs2.json
chk "AR8 notification body has NO customer name (masking)" "py \"import json;d=json.load(open('$W/emp2notifs2.json'));ns=[n for n in d['notifications'] if 'رد شد' in n['title']];exit(0 if ns and all('Req Pharmacy' not in n['body'] for n in ns) else 1)\""
chk "AR8 no assignment created for the rejected request" "py \"import json;d=json.load(open('$W/asglist.json'));exit(0 if not any(a['employeeId']=='$EMP2' and a['customerId']=='$CUS2' for a in d['assignments']) else 1)\""

# ── AR9: re-request after rejection is allowed ────────────────
curl -s -X POST $BASE/api/app/assignment-requests -H "X-Medpad-Token: $TOK2" \
  -H 'content-type: application/json' -d "{\"customerId\":\"$CUS2\"}" -o $W/req3.json
chk "AR9 fresh request after rejection allowed" "grep -q '\"ok\":true' $W/req3.json"
REQ3=$(py "import json;print(json.load(open('$W/req3.json'))['request']['id'])")

# ── AR10: manual assignment races the request ─────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP2\",\"customerId\":\"$CUS2\"}" -o $W/asg2.json
chk "AR10 admin manually assigns CUS2 to emp2" "grep -q '\"ok\":true' $W/asg2.json"
curl -s -b $W/cj -X POST "$BASE/api/assignment-requests/$REQ3/approve" -o $W/ap3.json
chk "AR10 approve returns ok" "grep -q '\"ok\":true' $W/ap3.json"
chk "AR10 approve reports alreadyAssigned" "grep -q '\"alreadyAssigned\":true' $W/ap3.json"
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asglist2.json
CNT=$(py "import json;d=json.load(open('$W/asglist2.json'));print(sum(1 for a in d['assignments'] if a['employeeId']=='$EMP2' and a['customerId']=='$CUS2'))")
chk "AR10 exactly ONE assignment (no duplicate)" "[ '$CNT' = '1' ]"

# ── AR11: deleted customer ────────────────────────────────────
curl -s -X POST $BASE/api/app/assignment-requests -H "X-Medpad-Token: $TOK1" \
  -H 'content-type: application/json' -d "{\"customerId\":\"$CUS3\"}" -o $W/req4.json
chk "AR11 emp1 requests CUS3" "grep -q '\"ok\":true' $W/req4.json"
REQ4=$(py "import json;print(json.load(open('$W/req4.json'))['request']['id'])")
curl -s -b $W/cj -X DELETE "$BASE/api/customers/$CUS3" -o $W/del3.json
chk "AR11 customer deleted" "grep -q '\"ok\":true' $W/del3.json"
CODE=$(curl -s -o $W/ap4.json -w '%{http_code}' -b $W/cj -X POST "$BASE/api/assignment-requests/$REQ4/approve")
chk "AR11 approve on deleted customer -> 409" "[ '$CODE' = '409' ]"
curl -s -b $W/cj "$BASE/api/assignment-requests?status=pending" -o $W/pend.json
chk "AR11 request stays pending" "py \"import json;d=json.load(open('$W/pend.json'));exit(0 if any(r['id']=='$REQ4' and r['customer'] is None for r in d['requests']) else 1)\""

# ── AR12: audit rows ──────────────────────────────────────────
curl -s -b $W/cj "$BASE/api/audit-log?limit=100" -o $W/audit.json
chk "AR12 audit: create (employee actor)" "py \"import json;d=json.load(open('$W/audit.json'));exit(0 if any(a['action']=='assignment_request.create' for a in d['entries']) else 1)\""
chk "AR12 audit: approve" "py \"import json;d=json.load(open('$W/audit.json'));exit(0 if any(a['action']=='assignment_request.approve' for a in d['entries']) else 1)\""
chk "AR12 audit: reject" "py \"import json;d=json.load(open('$W/audit.json'));exit(0 if any(a['action']=='assignment_request.reject' for a in d['entries']) else 1)\""

# ── AR13: persistence across restart ─────────────────────────
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1); sleep 2
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "AR13 panel restarted" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/assignment-requests" -o $W/emp2reqs4.json
chk "AR13 requests survive restart" "py \"import json;d=json.load(open('$W/emp2reqs4.json'));exit(0 if len(d['requests'])>=3 else 1)\""

# ── AR14: upgrade path (pre-pkg6 database) ────────────────────
DBFILE=$(find $W/inst -name "custom.db" | head -1)
[ -n "$DBFILE" ] || DBFILE=$(find $W/inst -path "*panel*" -name "*.db" ! -name "template.db" | head -1)
chk "AR14 found the live database" "[ -n '$DBFILE' ]"
if [ -n "$DBFILE" ]; then
  (cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1); sleep 2
  python3 - "$DBFILE" <<'PYEOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute('DROP INDEX IF EXISTS "AssignmentRequest_pending_key"')
db.execute('DROP TABLE IF EXISTS "AssignmentRequest"')
db.commit()
print('   dropped AssignmentRequest (simulating a pre-package-6 install)')
PYEOF
  (cd $W/inst && node server-manager.mjs start --no-open > $W/boot3.log 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  chk "AR14 panel restarted on the old-schema db" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
  # the table was intentionally dropped EMPTY (a pre-pkg6 install has no
  # requests) — the migration must recreate it; OTHER data (customers,
  # employees, assignments) was never touched and stays readable:
  curl -s -b $W/cj "$BASE/api/customers" -o $W/cuscheck.json
  chk "AR14 existing customers intact after migration" "py \"import json;d=json.load(open('$W/cuscheck.json'));exit(0 if any(c['id']=='$CUS1' for c in d['customers']) else 1)\""
  curl -s -b $W/cj "$BASE/api/assignment-requests?status=pending" -o $W/pend2.json
  chk "AR14 request list works on migrated db" "grep -q '\"ok\":true' $W/pend2.json"
  curl -s -X POST $BASE/api/app/assignment-requests -H "X-Medpad-Token: $TOK1" \
    -H 'content-type: application/json' -d "{\"customerId\":\"$CUS1\"}" -o $W/req5.json
  chk "AR14 new request works after migration" "grep -q '\"ok\":true' $W/req5.json"
  REQ5=$(py "import json;print(json.load(open('$W/req5.json'))['request']['id'])")
  CODE=$(curl -s -o $W/dup5.json -w '%{http_code}' -X POST $BASE/api/app/assignment-requests \
    -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' -d "{\"customerId\":\"$CUS1\"}")
  chk "AR14 partial dedup index recreated (duplicate -> 409)" "[ '$CODE' = '409' ]"
fi

echo
echo "== RESULT: $PASS pass / $FAIL fail =="
if [ $FAIL -gt 0 ]; then
  echo "== SOME CHECKS FAILED =="
  exit 1
fi
echo "ALL-AREQ-CHECKS-PASS"
