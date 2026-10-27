#!/usr/bin/env bash
# test-notifications-infra.sh — package 4 (phase 2 / task 3):
# notification INFRASTRUCTURE end-to-end against a REAL panel.
# Run:  bash test-notifications-infra.sh [PKG_DIR]
#
#   NI0  fresh install + boot + fixtures
#   NI1  assignment -> employee + PANEL notification; retry cannot duplicate
#   NI2  check-in -> panel notification; localId REPLAY adds NO second one
#   NI3  check-out -> panel notification
#   NI4  plan create/reschedule/cancel -> employee notifications; reschedule
#        to the same time twice -> exactly ONE reschedule notification
#   NI5  admin broadcast -> reaches the employee; app self-create -> 405
#   NI6  panel notifications API: list + unread, mark one, mark all, 401 gates
#   NI7  PERSISTENCE: server restart -> everything still there
#   NI8  employee isolation (emp2 never sees emp1's notifications)
#   NI9  UPGRADE PATH: boot on the pre-package-4 database -> migrations apply
#        (eventKey column + PanelNotification table) and events still work
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

W=/tmp/tnotif; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3253; RT_PORT=3256; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── NI0: install + boot + fixtures ───────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'Notif123!' > $W/install.log 2>&1)
chk "NI0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "NI0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"Notif123!"}' -o $W/admin-login.json
chk "NI0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"n_ali","password":"alipass123","name":"N Ali","phone":"555"}' -o $W/emp1.json
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"n_sara","password":"sarapass123","name":"N Sara","phone":"556"}' -o $W/emp2.json
EMP1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "NI0 employees created" "[ -n '$EMP1' ] && [ -n '$EMP2' ]"

curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"Notif Clinic","lat":35.70,"lng":51.40,"type":"clinic"}' -o $W/cus1.json
CUS1=$(py "import json;print(json.load(open('$W/cus1.json'))['customer']['id'])")
chk "NI0 customer created" "[ -n '$CUS1' ]"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"n_ali","password":"alipass123"}' -o $W/app1.json
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"n_sara","password":"sarapass123"}' -o $W/app2.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
TOK2=$(py "import json;print(json.load(open('$W/app2.json'))['token'])")
chk "NI0 app logins" "[ -n '$TOK1' ] && [ -n '$TOK2' ]"

applist() { curl -s -H "X-Medpad-Token: $1" "$BASE/api/app/notifications" -o $2; }
panellist() { curl -s -b $W/cj "$BASE/api/panel-notifications" -o $1; }
count_kind() { py "import json;d=json.load(open('$1'));print(sum(1 for n in d['notifications'] if n['kind']=='$2'))"; }

# ── NI1: assignment notifications + dedup ────────────────────
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\"}" -o $W/asg.json
chk "NI1 assignment created" "grep -q '\"ok\":true' $W/asg.json"

applist "$TOK1" $W/app1_notifs.json
chk "NI1 employee sees the assignment notification" "py \"import json;d=json.load(open('$W/app1_notifs.json'));exit(0 if any(n['kind']=='assignment' and 'Notif Clinic' in n['body'] for n in d['notifications']) else 1)\""

panellist $W/panel_notifs.json
chk "NI1 PANEL got the assignment notification" "py \"import json;d=json.load(open('$W/panel_notifs.json'));exit(0 if any(n['kind']=='assignment' for n in d['notifications']) else 1)\""
PANEL_ASG=$(count_kind $W/panel_notifs.json assignment)
EMP_ASG=$(count_kind $W/app1_notifs.json assignment)

# retry the same assignment -> 409, no new notifications anywhere
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\"}")
chk "NI1 duplicate assignment -> 409" "[ '$CODE' = '409' ]"
applist "$TOK1" $W/app1_notifs2.json
panellist $W/panel_notifs2.json
chk "NI1 retry created NO duplicate employee notification" "py \"import json;d=json.load(open('$W/app1_notifs2.json'));print(sum(1 for n in d['notifications'] if n['kind']=='assignment'));exit(0)\" | grep -qx '$EMP_ASG'"
chk "NI1 retry created NO duplicate panel notification" "py \"import json;d=json.load(open('$W/panel_notifs2.json'));print(sum(1 for n in d['notifications'] if n['kind']=='assignment'));exit(0)\" | grep -qx '$PANEL_ASG'"

# ── NI2: check-in -> panel notification + replay dedup ───────
L1="ni-l1-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=notif test checkin" -F "localId=$L1" \
  -F "lat=35.7001" -F "lng=51.4001" -o $W/ci1.json
chk "NI2 check-in accepted" "grep -q '\"ok\":true' $W/ci1.json"
panellist $W/panel_n2.json
CHECKIN_N=$(count_kind $W/panel_n2.json checkin)
chk "NI2 panel got the check-in notification" "[ '$CHECKIN_N' = '1' ]"

# replay the same localId -> duplicate check-in, NO second notification
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$CUS1" -F "note=notif test checkin" -F "localId=$L1" \
  -F "lat=35.7002" -F "lng=51.4002" -o $W/ci1r.json
chk "NI2 replay is a duplicate" "grep -q '\"duplicate\":true' $W/ci1r.json"
panellist $W/panel_n3.json
chk "NI2 replay added NO second check-in notification" "py \"import json;d=json.load(open('$W/panel_n3.json'));print(sum(1 for n in d['notifications'] if n['kind']=='checkin'));exit(0)\" | grep -qx '1'"

# ── NI3: check-out -> panel notification ─────────────────────
sleep 92 # wait out the 90s dedup window so the checkout is a REAL new event
curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOK1" \
  -F "note=notif test checkout note" -F "localId=ni-co-$(date +%s)" \
  -F "lat=35.7003" -F "lng=51.4003" -F "voice=@$W/voice.m4a" -o $W/co.json 2>/dev/null
# the checkout route REQUIRES a voice file — create a tiny one first if missing
if ! grep -q '"ok":true' $W/co.json 2>/dev/null; then
  python3 -c "open('$W/voice.m4a','wb').write(b'\x00\x00\x00\x18ftypM4A ' + b'\x00'*32)"
  curl -s -X POST $BASE/api/app/checkout -H "X-Medpad-Token: $TOK1" \
    -F "note=notif test checkout note" -F "localId=ni-co2-$(date +%s)" \
    -F "lat=35.7003" -F "lng=51.4003" -F "voice=@$W/voice.m4a" -o $W/co.json
fi
chk "NI3 check-out accepted" "grep -q '\"ok\":true' $W/co.json"
panellist $W/panel_n4.json
chk "NI3 panel got the check-out notification" "py \"import json;d=json.load(open('$W/panel_n4.json'));exit(0 if any(n['kind']=='checkout' for n in d['notifications']) else 1)\""

# ── NI4: plan lifecycle -> employee notifications ────────────
TOMORROW=$(py "import datetime;print((datetime.date.today()+datetime.timedelta(days=1)).isoformat())")
T10=$(py "import datetime;print(datetime.datetime.strptime('$TOMORROW','%Y-%m-%d').replace(hour=10).isoformat())")
T14=$(py "import datetime;print(datetime.datetime.strptime('$TOMORROW','%Y-%m-%d').replace(hour=14).isoformat())")

curl -s -b $W/cj -X POST $BASE/api/planned-visits -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$CUS1\",\"scheduledAt\":\"$T10\"}" -o $W/plan.json
chk "NI4 plan created" "grep -q '\"ok\":true' $W/plan.json"
PLID=$(py "import json;print(json.load(open('$W/plan.json'))['plan']['id'])")
applist "$TOK1" $W/app1_p.json
chk "NI4 employee got the plan-create notification" "py \"import json;d=json.load(open('$W/app1_p.json'));exit(0 if any(n['kind']=='plan' and n['title']=='برنامهٔ بازدید جدید' for n in d['notifications']) else 1)\""
PLAN_CREATE_N=$(count_kind $W/app1_p.json plan)

curl -s -b $W/cj -X POST $BASE/api/planned-visits/$PLID/reschedule -H 'content-type: application/json' \
  -d "{\"scheduledAt\":\"$T14\"}" -o $W/resc.json
chk "NI4 reschedule ok" "grep -q '\"ok\":true' $W/resc.json"
# reschedule to the SAME time again -> allowed, but eventKey dedups the notice
curl -s -b $W/cj -X POST $BASE/api/planned-visits/$PLID/reschedule -H 'content-type: application/json' \
  -d "{\"scheduledAt\":\"$T14\"}" -o $W/resc2.json
chk "NI4 second reschedule to the SAME time ok" "grep -q '\"ok\":true' $W/resc2.json"
applist "$TOK1" $W/app1_p2.json
RESCHED_N=$(py "import json;d=json.load(open('$W/app1_p2.json'));print(sum(1 for n in d['notifications'] if n['kind']=='plan' and 'برنامه‌ریزی مجدد' in n['title']))")
chk "NI4 same-time reschedule notified exactly ONCE (eventKey dedup)" "[ '$RESCHED_N' = '1' ]"

curl -s -b $W/cj -X POST $BASE/api/planned-visits/$PLID/cancel -H 'content-type: application/json' \
  -d '{"reason":"تست"}' -o $W/cancel.json
chk "NI4 cancel ok" "grep -q '\"ok\":true' $W/cancel.json"
applist "$TOK1" $W/app1_p3.json
chk "NI4 employee got the plan-cancel notification" "py \"import json;d=json.load(open('$W/app1_p3.json'));exit(0 if any(n['kind']=='plan' and 'لغو' in n['title'] for n in d['notifications']) else 1)\""

# ── NI5: admin broadcast + app self-create restricted ────────
CODE=$(curl -s -o $W/broadcast.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/notifications \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP2\",\"title\":\"پیام مدیر\",\"body\":\"سلام\"}")
chk "NI5 admin broadcast -> 200" "[ '$CODE' = '200' ]"
applist "$TOK2" $W/app2_notifs.json
chk "NI5 emp2 received the broadcast" "py \"import json;d=json.load(open('$W/app2_notifs.json'));exit(0 if any(n['title']=='پیام مدیر' for n in d['notifications']) else 1)\""
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/notifications \
  -H "X-Medpad-Token: $TOK1" -H 'content-type: application/json' -d '{"title":"fake"}')
chk "NI5 app self-create -> 405 (removed)" "[ '$CODE' = '405' ]"

# ── NI6: panel notifications API + read states + gates ───────
panellist $W/panel_final.json
chk "NI6 panel list has unread > 0" "py \"import json;d=json.load(open('$W/panel_final.json'));exit(0 if d['unread']>0 else 1)\""
NID=$(py "import json;d=json.load(open('$W/panel_final.json'));print(d['notifications'][0]['id'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b $W/cj -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d "{\"ids\":[\"$NID\"]}")
chk "NI6 mark one read -> 200" "[ '$CODE' = '200' ]"
panellist $W/panel_r1.json
chk "NI6 that one is now read (unread decreased)" "py \"import json;d1=json.load(open('$W/panel_final.json'));d2=json.load(open('$W/panel_r1.json'));exit(0 if d2['unread']==d1['unread']-1 else 1)\""
curl -s -b $W/cj -X POST $BASE/api/panel-notifications/read -H 'content-type: application/json' -d '{}' -o /dev/null
panellist $W/panel_r2.json
chk "NI6 mark ALL read -> unread 0" "py \"import json;d=json.load(open('$W/panel_r2.json'));exit(0 if d['unread']==0 else 1)\""
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/panel-notifications")
chk "NI6 panel list without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/panel-notifications/read" -H 'content-type: application/json' -d '{}')
chk "NI6 mark-read without session -> 401" "[ '$CODE' = '401' ]"

# ── NI7: persistence across restart ──────────────────────────
BEFORE=$(py "import json;d=json.load(open('$W/panel_r2.json'));print(len(d['notifications']))")
(cd $W/inst && node server-manager.mjs restart --no-open > $W/restart.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "NI7 panel back after restart" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
panellist $W/panel_after.json
chk "NI7 notifications survived the restart (DB, not memory)" "py \"import json;d=json.load(open('$W/panel_after.json'));print(len(d['notifications']));exit(0)\" | grep -qx '$BEFORE'"
applist "$TOK1" $W/app1_after.json
chk "NI7 employee notifications survived too" "py \"import json;d=json.load(open('$W/app1_after.json'));exit(0 if any(n['kind']=='plan' for n in d['notifications']) else 1)\""

# ── NI8: employee isolation ───────────────────────────────────
applist "$TOK2" $W/app2_final.json
chk "NI8 emp2 never sees emp1's plan/assignment notifications" "py \"import json;d=json.load(open('$W/app2_final.json'));exit(0 if not any(n['kind'] in ('plan','assignment') for n in d['notifications']) else 1)\""

# ── NI9: upgrade path from the pre-package-4 database ────────
(cd "$W/inst" && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
# the DB the installer created was seeded from the NEW template; replace it
# with the OLD (pre-package-4) shape to prove the migration path
OLD_DB="$W/old-template.db"
( cd /home/z/my-project/medpad-repo && git show HEAD:app/panel/db/template.db > "$OLD_DB" ) 2>/dev/null
if [ -s "$OLD_DB" ]; then
  HAS_OLD=$(python3 -c "
import sqlite3
c = sqlite3.connect('$OLD_DB')
cols = [x[1] for x in c.execute('PRAGMA table_info(AppNotification)')]
t = c.execute(\"SELECT name FROM sqlite_master WHERE name='PanelNotification'\").fetchone()
print(1 if 'eventKey' not in cols and not t else 0)
")
  chk "NI9 old template really lacks eventKey/PanelNotification" "[ '$HAS_OLD' = '1' ]"
  # preserve THIS install's admin credentials across the DB swap (the old
  # template carries a different/empty admin config — the swap must test the
  # SCHEMA upgrade, not fail on credentials)
  python3 -c "
import sqlite3, pickle
src = sqlite3.connect('$W/inst/panel/db/custom.db')
data = {}
for t in ('Config', 'Employee', 'Customer', 'Assignment', 'AppToken'):
    data[t] = src.execute(f'SELECT * FROM {t}').fetchall()
    data[t + '_cols'] = [d[1] for d in src.execute(f'PRAGMA table_info({t})')]
pickle.dump(data, open('$W/keep-rows.pkl', 'wb'))
"
  # swap the DB file — and remove the stale WAL/SHM sidecars, otherwise
  # SQLite replays the old server's write-ahead log over the swapped base
  # and silently clobbers the restore below (found the hard way)
  rm -f "$W/inst/panel/db/custom.db-wal" "$W/inst/panel/db/custom.db-shm"
  cp -f "$OLD_DB" "$W/inst/panel/db/custom.db"
  python3 -c "
import sqlite3, pickle
data = pickle.load(open('$W/keep-rows.pkl', 'rb'))
dst = sqlite3.connect('$W/inst/panel/db/custom.db')
for t in ('Config', 'Employee', 'Customer', 'Assignment', 'AppToken'):
    cols = data[t + '_cols']
    ph = ','.join('?' * len(cols))
    dst.executemany(f'INSERT OR REPLACE INTO {t} ({\",\".join(cols)}) VALUES ({ph})', data[t])
dst.commit()
"
  (cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  chk "NI9 panel boots on the OLD database" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
  # migrations applied?
  MIGRATED=$(python3 -c "
import sqlite3
c = sqlite3.connect('$W/inst/panel/db/custom.db')
cols = [x[1] for x in c.execute('PRAGMA table_info(AppNotification)')]
t = c.execute(\"SELECT name FROM sqlite_master WHERE name='PanelNotification'\").fetchone()
print(1 if 'eventKey' in cols and t else 0)
")
  chk "NI9 migration applied eventKey + PanelNotification" "[ '$MIGRATED' = '1' ]"
  # and a new event still works on the migrated DB
  curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
    -d '{"username":"admin","password":"Notif123!"}' -o /dev/null
  curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
    -d "{\"employeeId\":\"$EMP2\",\"customerId\":\"$CUS1\"}" -o $W/asg2.json
  chk "NI9 new assignment on migrated DB ok" "grep -q '\"ok\":true' $W/asg2.json"
  panellist $W/panel_mig.json
  chk "NI9 notification written on migrated DB" "py \"import json;d=json.load(open('$W/panel_mig.json'));exit(0 if len(d['notifications'])>0 else 1)\""
else
  f "NI9 old template could not be extracted from git"
fi

echo
echo "=================================================="
echo "NOTIFICATIONS-INFRA: PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ] && echo "ALL-NOTIF-CHECKS-PASS" || echo "SOME-CHECKS-FAILED"
echo "=================================================="
exit $([ $FAIL -eq 0 ] && echo 0 || echo 1)
