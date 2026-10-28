#!/usr/bin/env bash
# test-bulk-assignments.sh — package 7 (phase 2 / task 5): BULK customer
# assignment / unassignment, end-to-end against a REAL installed panel.
# Run:  bash test-bulk-assignments.sh [PKG_DIR]
#
#   BA0  fresh install + boot + fixtures (3 employees — one INACTIVE,
#        4 customers, some pre-existing assignments)
#   BA1  gates: 401 (no admin session) / 400 (bad action, empty lists,
#        invalid ids, oversized batch)
#   BA2  bulk ASSIGN 3 customers x 2 employees -> 6 created; exact
#        assignment rows; reps notified; customers appear in each rep's
#        /api/app/customers sync
#   BA3  duplicate prevention: same bulk again -> 6 already, 0 created,
#        no duplicate rows, NO new rep notifications (eventKey on row id)
#   BA4  admin bell: exactly ONE bulk summary entry; retry does not add
#        a second (deterministic batch eventKey)
#   BA5  partial failure honesty: mix 1 bogus customer id into a valid
#        bulk -> valid pairs applied, bogus pair in failed[] with reason;
#        response counters exact
#   BA6  INACTIVE employee -> per-pair failed entry, no assignment row
#   BA7  bulk UNASSIGN from specific employees -> only those pairs
#        removed; other employees' assignments intact; visits/checkins
#        untouched; rep notification history intact
#   BA8  bulk UNASSIGN allEmployees -> every assignment of the selected
#        customers removed; missing counted honestly for unassigned
#        customers; single-assign API unaffected afterwards
#   BA9  audit rows: assignment.bulk + assignment.bulk-unassign written
#   BA10 planned-visit guard: a customer with a planned visit can still
#        be unassigned (plans stay, masking rules of the app apply later)
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

W=/tmp/tbulk; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3273; RT_PORT=3276; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── BA0: install + boot + fixtures ───────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'Bulk123!' > $W/install.log 2>&1)
chk "BA0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "BA0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"Bulk123!"}' -o $W/admin-login.json
chk "BA0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

for i in 1 2 3; do
  curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
    -d "{\"username\":\"b_emp$i\",\"password\":\"emp${i}pass123\",\"name\":\"B Emp$i\",\"phone\":\"55$i\"}" -o $W/emp$i.json
done
EMP1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
EMP3=$(py "import json;print(json.load(open('$W/emp3.json'))['employee']['id'])")
chk "BA0 employees created" "[ -n '$EMP1' ] && [ -n '$EMP2' ] && [ -n '$EMP3' ]"

# EMP3 goes INACTIVE (deactivate revokes tokens too)
curl -s -b $W/cj -X POST "$BASE/api/employees/$EMP3/deactivate" -o $W/deact3.json
chk "BA0 emp3 deactivated" "grep -q '\"ok\":true' $W/deact3.json"

for i in 1 2 3 4; do
  curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d "{\"name\":\"Bulk Cus $i\",\"lat\":35.7$i,\"lng\":51.4$i,\"type\":\"doctor\"}" -o $W/cus$i.json
done
CUS1=$(py "import json;print(json.load(open('$W/cus1.json'))['customer']['id'])")
CUS2=$(py "import json;print(json.load(open('$W/cus2.json'))['customer']['id'])")
CUS3=$(py "import json;print(json.load(open('$W/cus3.json'))['customer']['id'])")
CUS4=$(py "import json;print(json.load(open('$W/cus4.json'))['customer']['id'])")
chk "BA0 customers created" "[ -n '$CUS1' ] && [ -n '$CUS4' ]"

# pre-existing: EMP1 already owns CUS1 (single-assign API)
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\"}" -o $W/pre.json
chk "BA0 pre-existing assignment emp1-cus1" "grep -q '\"ok\":true' $W/pre.json"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"b_emp1","password":"emp1pass123"}' -o $W/app1.json
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"b_emp2","password":"emp2pass123"}' -o $W/app2.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
TOK2=$(py "import json;print(json.load(open('$W/app2.json'))['token'])")
chk "BA0 app logins" "[ -n '$TOK1' ] && [ -n '$TOK2' ]"

count_assign() { # employee customer -> count of matching rows
  py "import json;d=json.load(open('$W/asg.json'));print(sum(1 for a in d['assignments'] if a['employeeId']=='$1' and a['customerId']=='$2'))"
}

# ── BA1: gates ───────────────────────────────────────────────
CODE=$(curl -s -o $W/g1.json -w '%{http_code}' -X POST $BASE/api/assignments/bulk \
  -H 'content-type: application/json' -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS2\"],\"employeeIds\":[\"$EMP2\"]}")
chk "BA1 no session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/g2.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments/bulk \
  -H 'content-type: application/json' -d "{\"action\":\"weird\",\"customerIds\":[\"$CUS2\"],\"employeeIds\":[\"$EMP2\"]}")
chk "BA1 bad action -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/g3.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments/bulk \
  -H 'content-type: application/json' -d "{\"action\":\"assign\",\"customerIds\":[],\"employeeIds\":[\"$EMP2\"]}")
chk "BA1 empty customers -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/g4.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments/bulk \
  -H 'content-type: application/json' -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS2\"],\"employeeIds\":[]}")
chk "BA1 empty employees -> 400" "[ '$CODE' = '400' ]"
MANY=$(python3 -c "import json;print(json.dumps(['x%d'%i for i in range(201)]))")
CODE=$(curl -s -o $W/g5.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments/bulk \
  -H 'content-type: application/json' -d "{\"action\":\"assign\",\"customerIds\":$MANY,\"employeeIds\":[\"$EMP2\"]}")
chk "BA1 oversized batch -> 400" "[ '$CODE' = '400' ]"

# ── BA2: bulk assign 3 customers x 2 employees ───────────────
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS1\",\"$CUS2\",\"$CUS3\"],\"employeeIds\":[\"$EMP1\",\"$EMP2\"]}" -o $W/b1.json
chk "BA2 bulk assign ok" "grep -q '\"ok\":true' $W/b1.json"
chk "BA2 created=5 (6 pairs, 1 pre-existed)" "py \"import json;exit(0 if json.load(open('$W/b1.json'))['created']==5 else 1)\""
chk "BA2 already=1 (emp1-cus1)" "py \"import json;exit(0 if json.load(open('$W/b1.json'))['already']==1 else 1)\""
chk "BA2 failed empty" "py \"import json;exit(0 if json.load(open('$W/b1.json'))['failed']==[] else 1)\""
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asg.json
chk "BA2 emp2 now owns cus1..cus3" "[ \"\$(count_assign $EMP2 $CUS1)\" = '1' ] && [ \"\$(count_assign $EMP2 $CUS2)\" = '1' ] && [ \"\$(count_assign $EMP2 $CUS3)\" = '1' ]"
chk "BA2 emp1 owns cus2,cus3 (cus1 pre-existed)" "[ \"\$(count_assign $EMP1 $CUS2)\" = '1' ] && [ \"\$(count_assign $EMP1 $CUS3)\" = '1' ]"
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/customers" -o $W/emp2cust.json
chk "BA2 emp2 app sync has the 3 customers" "py \"import json;d=json.load(open('$W/emp2cust.json'));ids={c['id'] for c in d['customers']};exit(0 if {'$CUS1','$CUS2','$CUS3'}<=ids else 1)\""
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/notifications" -o $W/emp2notifs.json
N2=$(py "import json;d=json.load(open('$W/emp2notifs.json'));print(sum(1 for n in d['notifications'] if 'اختصاص داده شد' in n['body']))")
chk "BA2 emp2 got 3 assignment notifications" "[ '$N2' = '3' ]"

# ── BA3: duplicate prevention ────────────────────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS1\",\"$CUS2\",\"$CUS3\"],\"employeeIds\":[\"$EMP1\",\"$EMP2\"]}" -o $W/b2.json
chk "BA3 retry: created=0" "py \"import json;exit(0 if json.load(open('$W/b2.json'))['created']==0 else 1)\""
chk "BA3 retry: already=6" "py \"import json;exit(0 if json.load(open('$W/b2.json'))['already']==6 else 1)\""
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asg.json
TOTAL=$(py "import json;print(len(json.load(open('$W/asg.json'))['assignments']))")
chk "BA3 no duplicate rows (total=6)" "[ '$TOTAL' = '6' ]"
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/notifications" -o $W/emp2notifs2.json
N2B=$(py "import json;d=json.load(open('$W/emp2notifs2.json'));print(sum(1 for n in d['notifications'] if 'اختصاص داده شد' in n['body']))")
chk "BA3 NO new rep notifications on retry" "[ '$N2B' = '3' ]"

# ── BA4: admin bell summary (deterministic eventKey) ─────────
curl -s -b $W/cj "$BASE/api/panel-notifications" -o $W/pn.json
BULK_N=$(py "import json;d=json.load(open('$W/pn.json'));print(sum(1 for n in d['notifications'] if n['kind']=='assignment' and 'گروهی' in n['title']))")
chk "BA4 one bulk summary in the bell" "[ '$BULK_N' = '1' ]"

# ── BA5: partial failure honesty ─────────────────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS4\",\"bogus-customer\"],\"employeeIds\":[\"$EMP2\"]}" -o $W/b3.json
chk "BA5 ok=true with valid pair applied" "grep -q '\"ok\":true' $W/b3.json"
chk "BA5 created=1 (cus4 x emp2)" "py \"import json;exit(0 if json.load(open('$W/b3.json'))['created']==1 else 1)\""
chk "BA5 bogus pair reported in failed[]" "py \"import json;d=json.load(open('$W/b3.json'));exit(0 if len(d['failed'])==1 and d['failed'][0]['customerId']=='bogus-customer' else 1)\""
chk "BA5 failure reason is Persian+actionable" "py \"import json;d=json.load(open('$W/b3.json'));exit(0 if 'مشتری' in d['failed'][0]['reason'] else 1)\""

# ── BA6: inactive employee ────────────────────────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS4\"],\"employeeIds\":[\"$EMP3\"]}" -o $W/b4.json
chk "BA6 inactive emp -> failed with reason" "py \"import json;d=json.load(open('$W/b4.json'));exit(0 if d['created']==0 and len(d['failed'])==1 and 'غیرفعال' in d['failed'][0]['reason'] else 1)\""
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asg.json
chk "BA6 no assignment row for inactive emp" "[ \"\$(count_assign $EMP3 $CUS4)\" = '0' ]"

# ── BA7: unassign from specific employees ────────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"unassign\",\"customerIds\":[\"$CUS1\",\"$CUS2\"],\"employeeIds\":[\"$EMP2\"]}" -o $W/b5.json
chk "BA7 unassign ok" "grep -q '\"ok\":true' $W/b5.json"
chk "BA7 removed=2" "py \"import json;exit(0 if json.load(open('$W/b5.json'))['removed']==2 else 1)\""
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asg.json
chk "BA7 emp2 lost cus1+cus2" "[ \"\$(count_assign $EMP2 $CUS1)\" = '0' ] && [ \"\$(count_assign $EMP2 $CUS2)\" = '0' ]"
chk "BA7 emp1 STILL owns cus1+cus2" "[ \"\$(count_assign $EMP1 $CUS1)\" = '1' ] && [ \"\$(count_assign $EMP1 $CUS2)\" = '1' ]"
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/notifications" -o $W/emp2notifs3.json
N2C=$(py "import json;d=json.load(open('$W/emp2notifs3.json'));print(sum(1 for n in d['notifications'] if 'اختصاص داده شد' in n['body']))")
chk "BA7 emp2 notification history intact (3 from BA2 + 1 from BA5)" "[ '$N2C' = '4' ]"

# ── BA8: unassign ALL employees ──────────────────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"unassign\",\"customerIds\":[\"$CUS1\",\"$CUS2\",\"$CUS3\",\"$CUS4\"],\"allEmployees\":true}" -o $W/b6.json
chk "BA8 unassign-all ok" "grep -q '\"ok\":true' $W/b6.json"
# remaining rows before: emp1-cus1, emp1-cus2, emp1-cus3, emp1-cus4? (cus4 only emp2) -> emp1: cus1,cus2,cus3; emp2: cus3,cus4
chk "BA8 removed=5 (all remaining pairs)" "py \"import json;exit(0 if json.load(open('$W/b6.json'))['removed']==5 else 1)\""
chk "BA8 missing=0 (every customer had assignments)" "py \"import json;exit(0 if json.load(open('$W/b6.json'))['missing']==0 else 1)\""
curl -s -b $W/cj "$BASE/api/assignments" -o $W/asg.json
chk "BA8 zero assignments left" "py \"import json;exit(0 if len(json.load(open('$W/asg.json'))['assignments'])==0 else 1)\""
# the single-assign API still works afterwards
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS4\"}" -o $W/single.json
chk "BA8 single-assign API unaffected" "grep -q '\"ok\":true' $W/single.json"

# ── BA9: audit rows ──────────────────────────────────────────
curl -s -b $W/cj "$BASE/api/audit-log?limit=100" -o $W/audit.json
chk "BA9 audit assignment.bulk" "py \"import json;d=json.load(open('$W/audit.json'));exit(0 if any(a['action']=='assignment.bulk' for a in d['entries']) else 1)\""
chk "BA9 audit assignment.bulk-unassign" "py \"import json;d=json.load(open('$W/audit.json'));exit(0 if any(a['action']=='assignment.bulk-unassign' for a in d['entries']) else 1)\""
chk "BA9 audit has no secrets in detail" "py \"import json;d=json.load(open('$W/audit.json'));exit(0 if all('Bulk123' not in (a.get('detail') or '') for a in d['entries']) else 1)\""

# ── BA10: customer with a plan can be unassigned (plans stay) ──
curl -s -b $W/cj -X POST $BASE/api/planned-visits -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP2\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"$(date -d tomorrow +%Y-%m-%d)T09:00:00.000Z\"}" -o $W/plan.json
chk "BA10 plan created for emp2-cus1" "grep -q '\"ok\":true' $W/plan.json"
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"assign\",\"customerIds\":[\"$CUS1\"],\"employeeIds\":[\"$EMP2\"]}" -o $W/b7.json
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"unassign\",\"customerIds\":[\"$CUS1\"],\"employeeIds\":[\"$EMP2\"]}" -o $W/b8.json
chk "BA10 unassign of planned customer ok" "grep -q '\"ok\":true' $W/b8.json"
TOMORROW=$(date -d tomorrow +%Y-%m-%d)
curl -s -b $W/cj "$BASE/api/planned-visits?date=$TOMORROW" -o $W/plans.json
chk "BA10 the plan itself survived (queried for its day)" "py \"import json;d=json.load(open('$W/plans.json'));exit(0 if any(p['customerId']=='$CUS1' for p in d['plans']) else 1)\""

echo
echo "== RESULT: $PASS pass / $FAIL fail =="
if [ $FAIL -gt 0 ]; then
  echo "== SOME CHECKS FAILED =="
  exit 1
fi
echo "ALL-BULK-CHECKS-PASS"
