#!/usr/bin/env bash
# test-planned-visit-flow.sh — package 3 (phase 2 / task 3):
# END-TO-END planned-visit lifecycle against a REAL running panel.
# Run:  bash test-planned-visit-flow.sh [PKG_DIR]
#
# Exercises the complete «گردش برنامهٔ بازدید» the owner asked for:
#   PV0  fresh install + boot + admin login + employees/customers/assignments
#   PV1  admin creates plans (valid), input validation, duplicate guard 409
#   PV2  app receives own plans; cross-employee isolation; employeeId spoof
#        ignored; unassigned customer masked; range validation
#   PV3  check-in links the EARLIEST scheduled plan of the same day (done +
#        visitId) and the app refetch reflects it
#   PV4  localId replay = idempotent (no second visit/plan link); a second
#        pair's plan links independently; needs_reschedule plan NEVER linked
#   PV5  cancelled plan never linked by a later same-day check-in
#   PV6  reschedule propagates to the app (new time, back to scheduled)
#   PV7  cancel propagates to the app (final cancelled state)
#   PV8  admin enrichment: actualVisit surfaces an unlinked real visit
#   PV9  security: admin/app plan APIs require auth (401 gates)
#
# Deterministic: fresh non-interactive install in /tmp, no real user data,
# curl + python3 only. Server and test share the same host clock/timezone.
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W=/tmp/tpvflow; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3247; RT_PORT=3250; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── PV0: install + boot + admin login + fixtures ─────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'PVFlow1!' > $W/install.log 2>&1)
chk "PV0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do
  curl -s -o /dev/null "$BASE/" && break; sleep 1
done
chk "PV0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"PVFlow1!"}' -o $W/admin-login.json
chk "PV0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"pv_ali","password":"alipass123","name":"PV Ali","phone":"555"}' -o $W/emp1.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"pv_sara","password":"sarapass123","name":"PV Sara","phone":"556"}' -o $W/emp2.json
EMP1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "PV0 employees created" "[ -n '$EMP1' ] && [ -n '$EMP2' ]"

curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"PV Clinic One","lat":35.70,"lng":51.40,"type":"clinic"}' -o $W/cus1.json
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"PV Pharmacy Two","lat":35.71,"lng":51.41,"type":"pharmacy"}' -o $W/cus2.json
CUS1=$(py "import json;print(json.load(open('$W/cus1.json'))['customer']['id'])")
CUS2=$(py "import json;print(json.load(open('$W/cus2.json'))['customer']['id'])")
chk "PV0 customers created" "[ -n '$CUS1' ] && [ -n '$CUS2' ]"

# assignment: emp1 -> cus1 AND cus2. emp2 -> nothing.
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\"}" -o $W/asg1.json
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS2\"}" -o $W/asg2.json
chk "PV0 assignments created (emp1 -> cus1+cus2)" "grep -q '\"ok\":true' $W/asg1.json && grep -q '\"ok\":true' $W/asg2.json"

# app tokens
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"pv_ali","password":"alipass123"}' -o $W/app1.json
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"pv_sara","password":"sarapass123"}' -o $W/app2.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
TOK2=$(py "import json;print(json.load(open('$W/app2.json'))['token'])")
chk "PV0 app logins" "[ -n '$TOK1' ] && [ -n '$TOK2' ]"

# local day keys shared with the server (same host tz)
TODAY=$(py "import datetime;print(datetime.date.today().isoformat())")
TOMORROW=$(py "import datetime;print((datetime.date.today()+datetime.timedelta(days=1)).isoformat())")
AT_TODAY()   { py "import datetime,sys;d=datetime.datetime.strptime('$TODAY','%Y-%m-%d').replace(hour=$1);print(d.isoformat())"; }
AT_TOMORROW(){ py "import datetime;d=datetime.datetime.strptime('$TOMORROW','%Y-%m-%d').replace(hour=$1);print(d.isoformat())"; }

# ── PV1: creation + validation + duplicate guard ─────────────
mkplan() { # employee customer time extra-json -> writes $W/plan_X.json
  curl -s -b $W/cj -X POST $BASE/api/planned-visits -H 'content-type: application/json' \
    -d "{\"employeeId\":\"$1\",\"customerId\":\"$2\",\"scheduledAt\":\"$3\"$4}" -o $W/plan_$5.json
}
mkplan "$EMP1" "$CUS1" "$(AT_TODAY 8)"  '' A
chk "PV1 create plan A (today 08:00 emp1+cus1)" "grep -q '\"ok\":true' $W/plan_A.json && py \"import json;d=json.load(open('$W/plan_A.json'))['plan'];exit(0 if d['status']=='scheduled' else 1)\""
PA=$(py "import json;print(json.load(open('$W/plan_A.json'))['plan']['id'])")

mkplan "$EMP1" "$CUS1" "$(AT_TODAY 15)" '' B
PB=$(py "import json;print(json.load(open('$W/plan_B.json'))['plan']['id'])")
chk "PV1 create plan B (today 15:00 — double-booked pair)" "[ -n '$PB' ]"

mkplan "$EMP1" "$CUS1" "$(AT_TOMORROW 10)" '' D
PD=$(py "import json;print(json.load(open('$W/plan_D.json'))['plan']['id'])")
chk "PV1 create plan D (tomorrow 10:00)" "[ -n '$PD' ]"

mkplan "$EMP2" "$CUS2" "$(AT_TODAY 13)" '' E
PE=$(py "import json;print(json.load(open('$W/plan_E.json'))['plan']['id'])")
chk "PV1 create plan E (emp2+cus2 — emp2 NOT assigned)" "[ -n '$PE' ]"

mkplan "$EMP1" "$CUS2" "$(AT_TODAY 12)" '' F
PF=$(py "import json;print(json.load(open('$W/plan_F.json'))['plan']['id'])")
chk "PV1 create plan F (today 12:00 emp1+cus2)" "[ -n '$PF' ]"

mkplan "$EMP1" "$CUS2" "$(AT_TODAY 9)" '' G
PG=$(py "import json;print(json.load(open('$W/plan_G.json'))['plan']['id'])")
chk "PV1 create plan G (today 09:00 emp1+cus2 — the linkable one)" "[ -n '$PG' ]"

mkplan "$EMP1" "$CUS1" "$(AT_TODAY 16)" '' C
PC=$(py "import json;print(json.load(open('$W/plan_C.json'))['plan']['id'])")
chk "PV1 create plan C (today 16:00 — will be cancelled)" "[ -n '$PC' ]"

# duplicate of A -> 409
CODE=$(curl -s -o $W/dup.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"$(AT_TODAY 8)\"}")
chk "PV1 duplicate plan -> 409 (no duplicate rows)" "[ '$CODE' = '409' ]"

# invalid inputs -> 400
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits \
  -H 'content-type: application/json' -d "{\"employeeId\":\"ghost\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"$(AT_TODAY 8)\"}")
chk "PV1 unknown employee -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"not-a-date\"}")
chk "PV1 invalid date -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"$(AT_TODAY 8)\",\"durationMin\":9999}")
chk "PV1 out-of-range duration -> 400" "[ '$CODE' = '400' ]"

# ── PV2: app visibility + isolation + masking + validation ──
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/planned-visits?from=$TODAY&to=$TOMORROW" -o $W/app1_plans.json
chk "PV2 emp1 sees plan A scheduled+assigned" "py \"import json;ps=[p for p in json.load(open('$W/app1_plans.json'))['plans']];a=[p for p in ps if p['id']=='$PA'];exit(0 if len(a)==1 and a[0]['status']=='scheduled' and a[0]['customer']['assigned'] and a[0]['customer']['name']=='PV Clinic One' else 1)\""
chk "PV2 emp1 sees B, F, G, C, D; NOT E (emp2's)" "py \"import json;ps=[p['id'] for p in json.load(open('$W/app1_plans.json'))['plans']];exit(0 if all(x in ps for x in ['$PB','$PF','$PG','$PC','$PD']) and '$PE' not in ps else 1)\""

curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/planned-visits?from=$TODAY&to=$TOMORROW" -o $W/app2_plans.json
chk "PV2 emp2 sees ONLY E, customer masked (assigned=false, name null)" "py \"import json;ps=[p for p in json.load(open('$W/app2_plans.json'))['plans']];exit(0 if len(ps)==1 and ps[0]['id']=='$PE' and ps[0]['customer']['assigned']==False and ps[0]['customer']['name'] is None else 1)\""

# employeeId spoof ignored (token identity wins)
curl -s -H "X-Medpad-Token: $TOK2" "$BASE/api/app/planned-visits?from=$TODAY&to=$TOMORROW&employeeId=$EMP1" -o $W/app2_spoof.json
chk "PV2 employeeId param spoof ignored" "py \"import json;ps=[p['id'] for p in json.load(open('$W/app2_spoof.json'))['plans']];exit(0 if '$PA' not in ps and '$PE' in ps else 1)\""

# range validation
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK1" \
  "$BASE/api/app/planned-visits?from=2026-01-01&to=2026-12-31")
chk "PV2 range > 31 days -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK1" \
  "$BASE/api/app/planned-visits?date=garbage")
chk "PV2 bad date -> 400" "[ '$CODE' = '400' ]"

# ── F -> needs_reschedule (before the check-in phase) ───────
CODE=$(curl -s -o $W/f_resc.json -w '%{http_code}' -b $W/cj -X PUT $BASE/api/planned-visits/$PF \
  -H 'content-type: application/json' -d '{"status":"needs_reschedule"}')
chk "PV1/PV4 set F -> needs_reschedule" "[ '$CODE' = '200' ]"

# ── PV3: check-in links the EARLIEST scheduled plan ─────────
L1="pv-l1-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=plan flow test one" -F "localId=$L1" \
  -F "lat=35.7001" -F "lng=51.4001" -o $W/ci1.json
chk "PV3 check-in #1 accepted" "grep -q '\"ok\":true' $W/ci1.json"

curl -s -b $W/cj "$BASE/api/planned-visits?date=$TODAY&employeeId=$EMP1" -o $W/admin_after1.json
chk "PV3 plan A -> done + visitId (earliest of the pair)" "py \"import json;ps=[p for p in json.load(open('$W/admin_after1.json'))['plans']];a=[p for p in ps if p['id']=='$PA'][0];exit(0 if a['status']=='done' and a['visitId'] else 1)\""
chk "PV3 plan B still scheduled (link takes earliest first)" "py \"import json;ps=[p for p in json.load(open('$W/admin_after1.json'))['plans']];b=[p for p in ps if p['id']=='$PB'][0];exit(0 if b['status']=='scheduled' else 1)\""

curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/planned-visits?from=$TODAY&to=$TODAY" -o $W/app1_after1.json
chk "PV3 app refetch: A done + visitStartLocal" "py \"import json;ps=[p for p in json.load(open('$W/app1_after1.json'))['plans']];a=[p for p in ps if p['id']=='$PA'][0];exit(0 if a['status']=='done' and a.get('visitStartLocal') else 1)\""

# mission item 7 (Oct 31): the one-open-check-in rule — the server now
# REJECTS a second open visit (409) instead of silently auto-closing the
# previous one. Sequential check-in scenarios therefore close their visit
# before the next one (voice is REQUIRED on checkout — tiny fixture file).
printf 'fake-audio' > $W/voice.m4a
closevisit() { curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $1" \
  -F "note=between-scenarios fixture close" -F "localId=cv-$RANDOM-$RANDOM" \
  -F "voice=@$W/voice.m4a;type=audio/mp4" -o /dev/null; }

# ── PV4: replay idempotency + multi-plan linking + F never linked ──
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=plan flow test one" -F "localId=$L1" \
  -F "lat=35.7002" -F "lng=51.4002" -o $W/ci1r.json
chk "PV4 replay same localId -> duplicate:true" "grep -q '\"duplicate\":true' $W/ci1r.json"
chk "PV4 replay created NO second visit" "py \"import json;d1=json.load(open('$W/ci1.json'));dr=json.load(open('$W/ci1r.json'));exit(0 if d1['checkin']['id']==dr['checkin']['id'] else 1)\""
closevisit $TOK1

# check-in #2 at cus2 -> links G (earliest scheduled at cus2); F is needs_reschedule -> NEVER linked
L3="pv-l3-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS2" -F "note=plan flow test two" -F "localId=$L3" \
  -F "lat=35.7101" -F "lng=51.4101" -o $W/ci2.json
chk "PV4 check-in #2 (cus2) accepted" "grep -q '\"ok\":true' $W/ci2.json"
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/planned-visits?from=$TODAY&to=$TODAY" -o $W/app1_after2.json
chk "PV4 G done + F untouched (needs_reschedule, no visitId)" "py \"import json;ps=[p for p in json.load(open('$W/app1_after2.json'))['plans']];g=[p for p in ps if p['id']=='$PG'][0];f=[p for p in ps if p['id']=='$PF'][0];exit(0 if g['status']=='done' and g.get('visitId') and f['status']=='needs_reschedule' and not f.get('visitId') else 1)\""
closevisit $TOK1

# check-in #3 back at cus1 (dedup 'recent' is now cus2 -> passes) -> links B
L2="pv-l2-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=plan flow test three" -F "localId=$L2" \
  -F "lat=35.7003" -F "lng=51.4003" -o $W/ci3.json
chk "PV4 check-in #3 (cus1 again) accepted" "grep -q '\"ok\":true' $W/ci3.json"
curl -s -b $W/cj "$BASE/api/planned-visits?date=$TODAY&employeeId=$EMP1" -o $W/admin_after3.json
chk "PV4 B done with a DIFFERENT visitId than A" "py \"import json;ps=[p for p in json.load(open('$W/admin_after3.json'))['plans']];a=[p for p in ps if p['id']=='$PA'][0];b=[p for p in ps if p['id']=='$PB'][0];exit(0 if a['status']=='done' and b['status']=='done' and a['visitId']!=b['visitId'] else 1)\""
closevisit $TOK1

# ── PV5: cancelled plan never linked ─────────────────────────
CODE=$(curl -s -o $W/c_cancel.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits/$PC/cancel \
  -H 'content-type: application/json' -d '{"reason":"تست لغو"}')
chk "PV5 cancel C -> 200" "[ '$CODE' = '200' ]"
# A further check-in at cus1 — the only plans left there are done (A, B) and
# cancelled (C), so the new visit must stay UNLINKED. The 90s server dedup
# window would silently swallow it (same employee+customer as check-in #3
# minutes ago), so wait it out — the price of testing the real dedup rule.
echo "(waiting out the 90s server dedup window so check-in #4 creates a real visit...)"
sleep 92
L4="pv-l4-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=plan flow test four" -F "localId=$L4" \
  -F "lat=35.7004" -F "lng=51.4004" -o $W/ci4.json
chk "PV5 check-in #4 accepted" "grep -q '\"ok\":true' $W/ci4.json"
curl -s -b $W/cj "$BASE/api/planned-visits?date=$TODAY&employeeId=$EMP1" -o $W/admin_after4.json
chk "PV5 C stays cancelled, no visitId (cancelled never linked)" "py \"import json;ps=[p for p in json.load(open('$W/admin_after4.json'))['plans']];c=[p for p in ps if p['id']=='$PC'][0];exit(0 if c['status']=='cancelled' and not c.get('visitId') else 1)\""
chk "PV5 C's note preserved with the cancel reason appended" "py \"import json;ps=[p for p in json.load(open('$W/admin_after4.json'))['plans']];c=[p for p in ps if p['id']=='$PC'][0];exit(0 if 'تست لغو' in (c.get('note') or '') else 1)\""

# ── PV6: reschedule propagates ───────────────────────────────
CODE=$(curl -s -o $W/d_resc.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits/$PD/reschedule \
  -H 'content-type: application/json' -d "{\"scheduledAt\":\"$(AT_TOMORROW 14)\"}")
chk "PV6 reschedule D (tomorrow 10:00 -> 14:00)" "[ '$CODE' = '200' ]"
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/planned-visits?from=$TOMORROW&to=$TOMORROW" -o $W/app1_d.json
chk "PV6 app sees D at the NEW time, scheduled" "py \"import json;ps=[p for p in json.load(open('$W/app1_d.json'))['plans']];d=[p for p in ps if p['id']=='$PD'][0];exit(0 if d['status']=='scheduled' and d['timeLocal']=='14:00' else 1)\""
chk "PV6 D dayKey = tomorrow" "py \"import json;ps=[p for p in json.load(open('$W/app1_d.json'))['plans']];d=[p for p in ps if p['id']=='$PD'][0];exit(0 if d['dayKey']=='$TOMORROW' else 1)\""

# ── PV7: cancel propagates ───────────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits/$PD/cancel \
  -H 'content-type: application/json' -d '{"reason":"پایان تست"}')
chk "PV7 cancel D -> 200" "[ '$CODE' = '200' ]"
curl -s -H "X-Medpad-Token: $TOK1" "$BASE/api/app/planned-visits?from=$TOMORROW&to=$TOMORROW" -o $W/app1_d2.json
chk "PV7 app sees D cancelled" "py \"import json;ps=[p for p in json.load(open('$W/app1_d2.json'))['plans']];d=[p for p in ps if p['id']=='$PD'][0];exit(0 if d['status']=='cancelled' else 1)\""
# reschedule of a cancelled plan must be rejected (final state)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits/$PD/reschedule \
  -H 'content-type: application/json' -d "{\"scheduledAt\":\"$(AT_TOMORROW 16)\"}")
chk "PV7 reschedule after cancel -> 409 (cancelled is final)" "[ '$CODE' = '409' ]"

# ── PV8: admin enrichment — actualVisit for an unlinked visit ──
# check-in #4 (L4) created a visit with no plan link left at cus1 — C was
# already cancelled, so the admin day view should surface it as actualVisit
# on... the C row (same employee+customer pair, same day).
chk "PV8 unlinked visit surfaced via actualVisit" "py \"import json;ps=[p for p in json.load(open('$W/admin_after4.json'))['plans']];c=[p for p in ps if p['id']=='$PC'][0];av=c.get('actualVisit');exit(0 if av and av.get('id') else 1)\""

# ── PV9: auth gates ──────────────────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/planned-visits?date=$TODAY")
chk "PV9 admin plans API without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/app/planned-visits?from=$TODAY&to=$TODAY")
chk "PV9 app plans API without token -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: bogus" "$BASE/api/app/planned-visits?from=$TODAY&to=$TODAY")
chk "PV9 app plans API bogus token -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/planned-visits \
  -H 'content-type: application/json' -d '{}')
chk "PV9 empty plan body -> 400 (not 500)" "[ '$CODE' = '400' ]"

echo
echo "=================================================="
echo "PLANNED-VISIT-FLOW: PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ] && echo "ALL-FLOW-CHECKS-PASS" || echo "SOME-CHECKS-FAILED"
echo "=================================================="
exit $([ $FAIL -eq 0 ] && echo 0 || echo 1)
