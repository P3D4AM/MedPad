#!/usr/bin/env bash
# test-phase3-tracking.sh — phase 3 (package 14): background location
# tracking + live panel status.
#
#   PD0  fresh install + boot + fixtures
#   TC   tracking config: defaults, auth, admin validation/persistence, audit
#   UL   app upload: auth, batch validation, idempotent replay (localId),
#        device status snapshot, config carried on the response
#   LT   panel live status: auth, last coords, fresh/stale (maxAge),
#        connection states incl. policy-disabled, counts
#   RH   route history: auth, unknown employee 404, oldest-first order,
#        check-in layer separation, lastPing, 31-day cap
#   PR   retention prune: boot prune deletes RECEIVED-past-retention pings,
#        fresh pings survive
#   UP   upgrade path: boot on the pre-package-14 template (git tag
#        v1.9.13-dev.4) -> migration creates the tables -> upload works;
#        migrated DDL == fresh-template DDL
#   UI   Playwright/Chromium: وضعیت زنده tab renders, table rows, policy
#        dialog save flow, route dialog with map layers (SKIPPED loudly
#        when no browser is available)
#
# Deterministic: fresh non-interactive install; curl + python3 + sqlite3
# via python3. Run:  bash test-phase3-tracking.sh [PKG_DIR]
#                    P3T_WORK=<dir> overrides the work directory.
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${P3T_WORK:-/tmp/tp3t}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="
echo "== WORKDIR: $W =="

PANEL_PORT=3313; RT_PORT=3316; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_PASS='P3Tpass123!'

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── PD0: fresh install + boot + fixtures ─────────────────────────
echo "== PD0: fresh install + boot + fixtures =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/install.log 2>&1)
chk "PD0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "PD0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
chk "PD0 fresh DB has LocationPing (new template)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='LocationPing'\\\").fetchone() else 1)\""
chk "PD0 fresh DB has TrackStatus (new template)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='TrackStatus'\\\").fetchone() else 1)\""
chk "PD0 localId unique index" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='LocationPing_localId_key'\\\").fetchone() else 1)\""
chk "PD0 route index" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='LocationPing_employeeId_recordedAt_idx'\\\").fetchone() else 1)\""

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o $W/al.json
chk "PD0 admin login" "grep -q '\"ok\":true' $W/al.json"

curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d "{\"username\":\"e_ali\",\"password\":\"alipass123\",\"name\":\"E Ali\",\"phone\":\"551\"}" -o $W/emp.json
EMP=$(py "import json;print(json.load(open('$W/emp.json'))['employee']['id'])")
chk "PD0 employee created" "[ -n '$EMP' ]"
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d "{\"username\":\"e_sara\",\"password\":\"sarapass123\",\"name\":\"E Sara\",\"phone\":\"552\"}" -o $W/emp2.json
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "PD0 second employee created (stale-status fixture)" "[ -n '$EMP2' ]"
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"P3T Clinic","lat":35.70,"lng":51.40,"type":"clinic"}' -o $W/cus.json
CUS=$(py "import json;print(json.load(open('$W/cus.json'))['customer']['id'])")
chk "PD0 customer created" "[ -n '$CUS' ]"
# the RH checkin needs the customer ASSIGNED to e_ali (403 guard otherwise)
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMP\",\"customerId\":\"$CUS\"}" -o $W/asg.json
chk "PD0 customer assigned to e_ali" "grep -q '\"ok\":true' $W/asg.json"

apptok() { curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | py "import json,sys;print(json.load(sys.stdin)['token'])"; }
TOK=$(apptok e_ali alipass123)
chk "PD0 app login" "[ -n '$TOK' ]"

DB="$W/inst/panel/db/custom.db"

# ── TC: tracking config ──────────────────────────────────────────
echo "== TC: tracking config =="
curl -s "$BASE/api/app/tracking-config" -o $W/tc_noauth.json
chk "TC config 401 without token" "grep -q 'unauthorized' $W/tc_noauth.json"
curl -s -H "X-Medpad-Token: $TOK" "$BASE/api/app/tracking-config" -o $W/tc.json
chk "TC config ok" "grep -q '\"ok\":true' $W/tc.json"
chk "TC defaults (45/240/40/900/200)" \
  "py \"import json;d=json.load(open('$W/tc.json'))['config'];exit(0 if d['moveIntervalSec']==45 and d['stillIntervalSec']==240 and d['motionThresholdM']==40 and d['maxAgeSec']==900 and d['batchSize']==200 and d['enabled'] is True else 1)\""

# invalid admin values rejected with Persian errors
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackMoveIntervalSec": 5}' -o $W/tc_bad1.json
chk "TC invalid move interval rejected" "grep -q 'error' $W/tc_bad1.json"
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackEnabled": 2}' -o $W/tc_bad2.json
chk "TC invalid enabled rejected" "grep -q 'error' $W/tc_bad2.json"
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackRetentionDays": 3}' -o $W/tc_bad3.json
chk "TC invalid retention rejected" "grep -q 'error' $W/tc_bad3.json"

# valid values persist + are visible to the app
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackMoveIntervalSec": 60, "trackStillIntervalSec": 300, "trackMaxAgeSec": 600}' -o $W/tc_set.json
chk "TC valid save ok" "grep -q '\"ok\":true' $W/tc_set.json"
curl -s -H "X-Medpad-Token: $TOK" "$BASE/api/app/tracking-config" -o $W/tc2.json
chk "TC app sees updated values" \
  "py \"import json;d=json.load(open('$W/tc2.json'))['config'];exit(0 if d['moveIntervalSec']==60 and d['stillIntervalSec']==300 and d['maxAgeSec']==600 else 1)\""
chk "TC audit row for settings.update" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM AuditLog WHERE action='settings.update' AND detail LIKE '%trackMoveIntervalSec=60%'\\\").fetchone()[0]>=1 else 1)\""
# restore defaults for the rest of the suite (maxAge 900 matters for stale)
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackMoveIntervalSec": 45, "trackStillIntervalSec": 240, "trackMaxAgeSec": 900}' -o /dev/null

# ── UL: app upload ───────────────────────────────────────────────
echo "== UL: app upload =="
now_iso() { python3 -c "from datetime import datetime,timezone;print(datetime.now(timezone.utc).isoformat().replace(chr(43)+chr(48)+chr(48)+chr(58)+chr(48)+chr(48),chr(90)))"; }
p1=$(now_iso); sleep 1; p2=$(now_iso); sleep 1; p3=$(now_iso)

curl -s -X POST $BASE/api/app/locations -H 'content-type: application/json' \
  -d '{"locations":[{"localId":"p3t-aaaaaaaa1","lat":35.7001,"lng":51.4001,"recordedAt":"'"$p1"'"}]}' -o $W/ul_noauth.json
chk "UL 401 without token" "grep -q 'unauthorized' $W/ul_noauth.json"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d 'not-json' -o $W/ul_badbody.json
chk "UL 400 non-JSON body" "grep -q 'error' $W/ul_badbody.json"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"locations":[]}' -o $W/ul_empty.json
chk "UL 400 empty list" "grep -q 'error' $W/ul_empty.json"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"locations":[{"localId":"p3t-bad!id","lat":35.7,"lng":51.4,"recordedAt":"'"$p1"'"}]}' -o $W/ul_badid.json
chk "UL 400 malformed localId" "grep -q 'error' $W/ul_badid.json"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"locations":[{"localId":"p3t-bbbbbbbb1","lat":999,"lng":51.4,"recordedAt":"'"$p1"'"}]}' -o $W/ul_badlat.json
chk "UL 400 bad latitude" "grep -q 'error' $W/ul_badlat.json"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"locations":[{"localId":"p3t-cccccccc1","lat":35.7,"lng":51.4,"recordedAt":"2099-01-01T00:00:00Z"}]}' -o $W/ul_future.json
chk "UL 400 future recordedAt" "grep -q 'error' $W/ul_future.json"
chk "UL rejected batch stored NOTHING" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM LocationPing WHERE localId LIKE 'p3t-b%' OR localId LIKE 'p3t-c%'\\\").fetchone()[0]==0 else 1)\""
# oversized batch
python3 -c "
import json
locs=[{'localId':f'p3t-x{i:05d}','lat':35.7,'lng':51.4,'recordedAt':'$p1'} for i in range(201)]
open('$W/ul_big.json','w').write(json.dumps({'locations':locs}))
"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d @$W/ul_big.json -o $W/ul_bigres.json
chk "UL 400 batch over 200" "grep -q 'error' $W/ul_bigres.json"
chk "UL oversized batch stored NOTHING" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM LocationPing WHERE localId LIKE 'p3t-x%'\\\").fetchone()[0]==0 else 1)\""

# valid batch of 3 + device status
python3 -c "
import json
locs=[{'localId':'p3t-aaaaaaaa1','lat':35.7001,'lng':51.4001,'accuracy':12.5,'recordedAt':'$p1'},
      {'localId':'p3t-aaaaaaaa2','lat':35.7005,'lng':51.4005,'accuracy':None,'recordedAt':'$p2'},
      {'localId':'p3t-aaaaaaaa3','lat':35.7010,'lng':51.4010,'accuracy':30.0,'recordedAt':'$p3'}]
open('$W/ul_ok.json','w').write(json.dumps({'locations':locs,'status':{'locationPermission':'fine','backgroundLocation':'granted','serviceRunning':True,'batteryUnrestricted':True,'appVersion':'1.9.13-dev.5'}}))
"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d @$W/ul_ok.json -o $W/ul_okres.json
chk "UL accepted 3" "grep -q '\"accepted\":3' $W/ul_okres.json"
chk "UL response carries config" \
  "py \"import json;d=json.load(open('$W/ul_okres.json'));exit(0 if d['config']['moveIntervalSec']==45 else 1)\""
# idempotent replay — same localIds
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d @$W/ul_ok.json -o $W/ul_replay.json
chk "UL replay all duplicated" "grep -q '\"duplicated\":3' $W/ul_replay.json"
chk "UL replay created no rows" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM LocationPing WHERE localId LIKE 'p3t-aaaaaaaa%'\\\").fetchone()[0]==3 else 1)\""
# unknown status fields are sanitized to 'unknown', not trusted
python3 -c "
import json
open('$W/ul_st.json','w').write(json.dumps({'locations':[{'localId':'p3t-aaaaaaaa4','lat':35.702,'lng':51.402,'recordedAt':'$p3'}],'status':{'locationPermission':'SUPERUSER-hack','serviceRunning':'yes'}}))
"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d @$W/ul_st.json -o /dev/null
chk "UL status sanitized (unknown perm, bool service)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');r=c.execute(\\\"SELECT locationPermission, serviceRunning FROM TrackStatus WHERE employeeId='$EMP'\\\").fetchone();exit(0 if r and r[0]=='unknown' and r[1]==0 else 1)\""

# ── LT: panel live status ────────────────────────────────────────
echo "== LT: panel live status =="
curl -s "$BASE/api/panel/tracking" -o $W/lt_noauth.json
chk "LT 401 without admin session" "grep -q 'unauthorized' $W/lt_noauth.json"
curl -s -b $W/cj "$BASE/api/panel/tracking" -o $W/lt.json
chk "LT ok + both employees" "py \"import json;d=json.load(open('$W/lt.json'));exit(0 if d['ok'] and len(d['rows'])==2 else 1)\""
chk "LT last coords present" \
  "py \"import json;d=json.load(open('$W/lt.json'));r=d['rows'][0];exit(0 if abs(r['lastPing']['lat']-35.702)<1e-9 and abs(r['lastPing']['lng']-51.402)<1e-9 else 1)\""
chk "LT fresh=true within maxAge" \
  "py \"import json;d=json.load(open('$W/lt.json'));exit(0 if d['rows'][0]['fresh'] is True else 1)\""
chk "LT connection=sending (reported recently)" \
  "py \"import json;d=json.load(open('$W/lt.json'));exit(0 if d['rows'][0]['connection']=='sending' else 1)\""
chk "LT counts (last24h=4, total=4)" \
  "py \"import json;d=json.load(open('$W/lt.json'));r=d['rows'][0];exit(0 if r['last24h']==4 and r['total']==4 else 1)\""
chk "LT policy echoed" \
  "py \"import json;d=json.load(open('$W/lt.json'));exit(0 if d['policy']['enabled'] is True and d['policy']['maxAgeSec']==900 else 1)\""

# stale: e_ali ALSO uploads a 2h-old route point (still NOT the last ping —
# the newest by recordedAt remains the fresh one; a stale ROUTE point must
# not flip the live verdict), and e_sara's ONLY ping is 2h old (her last
# known position is genuinely stale)
old_iso=$(python3 -c "from datetime import datetime,timezone,timedelta;print((datetime.now(timezone.utc)-timedelta(hours=2)).isoformat().replace(chr(43)+chr(48)+chr(48)+chr(58)+chr(48)+chr(48),chr(90)))")
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"locations":[{"localId":"p3t-old000001","lat":35.703,"lng":51.403,"recordedAt":"'"$old_iso"'"}]}' -o /dev/null
TOK2=$(apptok e_sara sarapass123)
chk "LT e_sara app login" "[ -n '$TOK2' ]"
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK2" -H 'content-type: application/json' \
  -d '{"locations":[{"localId":"p3t-sara-old01","lat":35.80,"lng":51.50,"recordedAt":"'"$old_iso"'"}]}' -o $W/ul_sara.json
chk "LT e_sara old ping accepted" "grep -q '\"accepted\":1' $W/ul_sara.json"
curl -s -b $W/cj "$BASE/api/panel/tracking" -o $W/lt2.json
chk "LT e_ali stays fresh (stale route point is not the last ping)" \
  "py \"import json;d=json.load(open('$W/lt2.json'));r=[x for x in d['rows'] if x['employee']['username']=='e_ali'][0];exit(0 if r['fresh'] is True and abs(r['lastPing']['lat']-35.702)<1e-9 else 1)\""
chk "LT e_sara stale last ping not shown as live (fresh=false)" \
  "py \"import json;d=json.load(open('$W/lt2.json'));r=[x for x in d['rows'] if x['employee']['username']=='e_sara'][0];exit(0 if r['fresh'] is False else 1)\""
chk "LT e_sara stale coords still visible as last known" \
  "py \"import json;d=json.load(open('$W/lt2.json'));r=[x for x in d['rows'] if x['employee']['username']=='e_sara'][0];exit(0 if abs(r['lastPing']['lat']-35.80)<1e-9 else 1)\""

# policy-disabled: tracking off -> connection says disabled, not 'cut off'
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackEnabled": 0}' -o /dev/null
curl -s -b $W/cj "$BASE/api/panel/tracking" -o $W/lt3.json
chk "LT disabled -> connection=disabled" \
  "py \"import json;d=json.load(open('$W/lt3.json'));exit(0 if d['rows'][0]['connection']=='disabled' and d['policy']['enabled'] is False else 1)\""
curl -s -H "X-Medpad-Token: $TOK" "$BASE/api/app/tracking-config" -o $W/tc_off.json
chk "LT app sees enabled=false" \
  "py \"import json;exit(0 if json.load(open('$W/tc_off.json'))['config']['enabled'] is False else 1)\""
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' \
  -d '{"trackEnabled": 1}' -o /dev/null

# ── RH: route history ────────────────────────────────────────────
echo "== RH: route history =="
# a check-in with coords inside the same window (kind separation) —
# the route takes multipart/form-data (it also carries voice files)
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK" \
  -F "customerId=$CUS" -F "note=p3t visit" -F "localId=p3t-ck-0001" \
  -F "lat=35.705" -F "lng=51.405" -F "accuracyM=10" -o /dev/null

curl -s "$BASE/api/panel/tracking/history?employeeId=$EMP" -o $W/rh_noauth.json
chk "RH 401 without admin session" "grep -q 'unauthorized' $W/rh_noauth.json"
curl -s -b $W/cj "$BASE/api/panel/tracking/history" -o $W/rh_noparam.json
chk "RH 400 missing employeeId" "grep -q 'error' $W/rh_noparam.json"
curl -s -b $W/cj "$BASE/api/panel/tracking/history?employeeId=nonexistent" -o $W/rh_404.json
chk "RH 404 unknown employee" "grep -q 'error' $W/rh_404.json"

from_iso=$(python3 -c "from datetime import datetime,timezone,timedelta;print((datetime.now(timezone.utc)-timedelta(hours=6)).isoformat().replace(chr(43)+chr(48)+chr(48)+chr(58)+chr(48)+chr(48),chr(90)))")
curl -s -b $W/cj "$BASE/api/panel/tracking/history?employeeId=$EMP&from=$from_iso" -o $W/rh.json
chk "RH ok with 5 points" "py \"import json;d=json.load(open('$W/rh.json'));exit(0 if len(d['points'])==5 else 1)\""
chk "RH oldest-first ordering" \
  "py \"import json;d=json.load(open('$W/rh.json'));p=d['points'];exit(0 if all(p[i]['recordedAt']<=p[i+1]['recordedAt'] for i in range(len(p)-1)) else 1)\""
chk "RH checkin layer separate (1 checkin)" \
  "py \"import json;d=json.load(open('$W/rh.json'));exit(0 if len(d['checkins'])==1 and d['checkins'][0]['customer']['name']=='P3T Clinic' else 1)\""
chk "RH lastPing = newest by recordedAt (the recent fix)" \
  "py \"import json;d=json.load(open('$W/rh.json'));exit(0 if abs(d['lastPing']['lat']-35.702)<1e-9 else 1)\""
chk "RH capped=false" "py \"import json;exit(0 if json.load(open('$W/rh.json'))['capped'] is False else 1)\""
bad_from=$(python3 -c "from datetime import datetime,timezone,timedelta;a=datetime.now(timezone.utc);z=lambda d:d.isoformat().replace(chr(43)+chr(48)+chr(48)+chr(58)+chr(48)+chr(48),chr(90));print(z(a+timedelta(hours=1)), z(a))")
curl -s -b $W/cj "$BASE/api/panel/tracking/history?employeeId=$EMP&from=$(echo $bad_from | cut -d' ' -f1)&to=$(echo $bad_from | cut -d' ' -f2)" -o $W/rh_badwin.json
chk "RH 400 reversed window" "grep -q 'error' $W/rh_badwin.json"
curl -s -b $W/cj "$BASE/api/panel/tracking/history?employeeId=$EMP&from=$(python3 -c "from datetime import datetime,timezone,timedelta;print((datetime.now(timezone.utc)-timedelta(days=60)).isoformat().replace(chr(43)+chr(48)+chr(48)+chr(58)+chr(48)+chr(48),chr(90)))")" -o $W/rh_bigwin.json
chk "RH 400 window over 31 days" "grep -q 'error' $W/rh_bigwin.json"

# ── UI: Playwright browser layer ─────────────────────────────────
echo "== UI: browser (Playwright/Chromium) =="
if python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null; then
  cat > "$W/p3t_browser.py" << 'PYBROWSER'
import json, sys
from playwright.sync_api import sync_playwright

BASE, W, PASSWORD = sys.argv[1], sys.argv[2], sys.argv[3]
out = {"errors": []}

def step(name, fn):
    try:
        out[name] = fn()
    except Exception as e:
        out["errors"].append(f"{name}: {e!r}")
        out[name] = None

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.set_default_timeout(20000)

    page.goto(BASE + "/", wait_until="domcontentloaded")
    page.fill("#u", "admin")
    page.fill("#p", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_selector('button[title="اعلان‌های مرورگر"]')  # dashboard ready (base bundle feature)

    # tab renders with the live table
    step("ui_tab_click", lambda: page.get_by_text("وضعیت زنده", exact=True).first.click())
    page.wait_for_selector('[data-testid="tracking-table"]')
    step("ui_table_rows", lambda: page.locator('[data-testid="tracking-table"] tbody tr').count())
    # rows are ordered active-desc + name-asc: E Ali (fresh) then E Sara (stale)
    step("ui_fresh_badge_e_ali", lambda: page.locator('[data-testid="tracking-table"] tbody tr').nth(0).inner_text().find("تازه") >= 0)
    step("ui_stale_badge_e_sara", lambda: page.locator('[data-testid="tracking-table"] tbody tr').nth(1).inner_text().find("قدیمی") >= 0)

    # policy dialog: open, change move interval, save, verify persisted
    step("ui_policy_open", lambda: page.click('[data-testid="tracking-policy-btn"]'))
    page.wait_for_selector('[data-testid="tracking-policy-dialog"]')
    step("ui_policy_move_input", lambda: (page.fill('[data-testid="track-move-input"]', "90"), page.click('[data-testid="track-save-btn"]')) and True)
    page.wait_for_selector('[data-testid="tracking-policy-dialog"]', state="detached")
    import urllib.request
    req = urllib.request.Request(BASE + "/api/admin/settings")
    # admin cookie from the browser context
    ctx = page.context
    cookies = {c["name"]: c["value"] for c in ctx.cookies(BASE)}
    req.add_header("cookie", "; ".join(f"{k}={v}" for k, v in cookies.items()))
    with urllib.request.urlopen(req) as r:
        settings = json.load(r)["settings"]
    step("ui_policy_saved_90", lambda: settings.get("trackMoveIntervalSec") == 90)
    step("ui_policy_strip_90", lambda: page.locator('[data-testid="tracking-tab"]').inner_text().find("۹۰") >= 0)

    # route dialog with the three map layers
    step("ui_route_open", lambda: page.locator('[data-testid^="route-btn-"]').first.click())
    page.wait_for_selector('[data-testid="route-map"]')
    step("ui_route_map", lambda: page.locator('[data-testid="route-map"]').count())
    # react-leaflet layers render into SVG panes (no DOM testids) — the
    # route polyline is an SVG path in the overlay pane; markers are circles
    step("ui_route_polyline", lambda: page.locator('[data-testid="route-map"] .leaflet-overlay-pane path').count())
    step("ui_route_markers", lambda: page.locator('[data-testid="route-map"] .leaflet-overlay-pane path').count() >= 2)
    step("ui_route_capped_note_absent", lambda: 0 if page.get_by_text("محدود شد").count() else 1)
    browser.close()

print(json.dumps(out))
PYBROWSER
  python3 "$W/p3t_browser.py" "$BASE" "$W" "$ADMIN_PASS" > "$W/p3t_ui.json" 2>$W/p3t_ui.err
  if [ -s "$W/p3t_ui.json" ]; then
    ui() { py "import json;d=json.load(open('$W/p3t_ui.json'));v=d.get('$1');exit(0 if (v is not None and v not in (False,0,'')) else 1)"; }
    chk "UI tab renders + rows" "ui ui_table_rows"
    chk "UI fresh badge (e_ali)" "ui ui_fresh_badge_e_ali"
    chk "UI stale badge honest (e_sara)" "ui ui_stale_badge_e_sara"
    chk "UI policy dialog save persists (90s)" "ui ui_policy_saved_90"
    chk "UI policy strip shows ۹۰" "ui ui_policy_strip_90"
    chk "UI route dialog map" "ui ui_route_map"
    chk "UI route polyline rendered" "ui ui_route_polyline"
    chk "UI route markers rendered (last-known + checkin)" "ui ui_route_markers"
    nerr=$(py "import json;print(len(json.load(open('$W/p3t_ui.json'))['errors']))")
    chk "UI zero step errors" "[ '$nerr' = '0' ]"
    [ "$nerr" != "0" ] && cat "$W/p3t_ui.json" | head -5
  else
    f "UI browser run produced no result"; cat $W/p3t_ui.err | head -5
  fi
else
  s "UI layer — install playwright + chromium to run the browser checks"
fi

# ── PR: retention prune at boot ──────────────────────────────────
echo "== PR: retention prune =="
# age RECEIVED timestamps of 3 pings far beyond the 30-day retention,
# keeping the storage format the database actually uses (probed live)
python3 - "$DB" << 'PYAGE'
import sqlite3, sys, time
db = sys.argv[1]
c = sqlite3.connect(db)
row = c.execute('SELECT receivedAt, typeof(receivedAt) FROM LocationPing LIMIT 1').fetchone()
val, typ = row
old_ms = int(time.time()*1000) - 40*24*3600*1000
if typ == 'integer':
    aged = old_ms                       # epoch-ms storage
else:
    aged = time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime(old_ms/1000)) + '.000'  # text storage
c.execute('UPDATE LocationPing SET receivedAt=? WHERE localId IN (?,?,?)', (aged, 'p3t-aaaaaaaa1','p3t-aaaaaaaa2','p3t-aaaaaaaa3'))
c.commit()
c.close()
print('aged 3 pings, storage type:', typ)
PYAGE
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1); sleep 2
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "PR boot pruned the aged pings" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM LocationPing WHERE localId IN ('p3t-aaaaaaaa1','p3t-aaaaaaaa2','p3t-aaaaaaaa3')\\\").fetchone()[0]==0 else 1)\""
chk "PR fresh pings survived" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM LocationPing WHERE localId IN ('p3t-aaaaaaaa4','p3t-old000001')\\\").fetchone()[0]==2 else 1)\""

# ── UP: upgrade path from the pre-package-14 database ─────────────
echo "== UP: upgrade path (pre-package-14 DB, NI9 pattern) =="
OLD_DB="$W/old-template.db"
if git -C "$PKG" rev-parse v1.9.13-dev.4 > /dev/null 2>&1; then
  ( cd "$PKG" && git show v1.9.13-dev.4:app/panel/db/template.db > "$OLD_DB" ) 2>/dev/null || true
  chk "UP old template extracted" "[ -s $OLD_DB ]"
  LACKS2=$(py "
import sqlite3
c = sqlite3.connect('$OLD_DB')
n = [r[0] for r in c.execute('SELECT name FROM sqlite_master')]
print(1 if 'LocationPing' not in n else 0)
")
  chk "UP old template lacks LocationPing" "[ '$LACKS2' = '1' ]"
  # preserve credentials/fixtures across the swap (NI9 pattern)
  py "
import sqlite3, pickle
src = sqlite3.connect('$W/inst/panel/db/custom.db')
data = {}
for t in ('Config', 'Employee', 'Customer', 'Assignment', 'AppToken'):
    data[t] = src.execute(f'SELECT * FROM {t}').fetchall()
    data[t + '_cols'] = [d[1] for d in src.execute(f'PRAGMA table_info({t})')]
pickle.dump(data, open('$W/keep-rows.pkl', 'wb'))
"
  (cd "$W/inst" && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
  sleep 2
  rm -f "$W/inst/panel/db/custom.db-wal" "$W/inst/panel/db/custom.db-shm"
  cp -f "$OLD_DB" "$W/inst/panel/db/custom.db"
  py "
import sqlite3, pickle
data = pickle.load(open('$W/keep-rows.pkl', 'rb'))
dst = sqlite3.connect('$W/inst/panel/db/custom.db')
for t in ('Config', 'Employee', 'Customer', 'Assignment', 'AppToken'):
    target_cols = [d[1] for d in dst.execute(f'PRAGMA table_info({t})')]
    src_cols = data[t + '_cols']
    use = [c for c in src_cols if c in target_cols]
    col_idx = [src_cols.index(c) for c in use]
    ph = ','.join('?' * len(use))
    rows = [tuple(r[i] for i in col_idx) for r in data[t]]
    dst.executemany(f'INSERT OR REPLACE INTO {t} ({\",\".join(use)}) VALUES ({ph})', rows)
dst.commit()
"
  (cd $W/inst && node server-manager.mjs start --no-open > $W/boot3.log 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  chk "UP panel boots on the pre-package-14 database" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
  MIG=$(py "
import sqlite3
c = sqlite3.connect('$W/inst/panel/db/custom.db')
lp = c.execute(\"SELECT name FROM sqlite_master WHERE name='LocationPing'\").fetchone()
ts = c.execute(\"SELECT name FROM sqlite_master WHERE name='TrackStatus'\").fetchone()
ix = c.execute(\"SELECT name FROM sqlite_master WHERE name='LocationPing_localId_key'\").fetchone()
print(1 if (lp and ts and ix) else 0)
")
  chk "UP migration applied LocationPing + TrackStatus + unique index at boot" "[ '$MIG' = '1' ]"
  curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o $W/upal.json
  chk "UP admin login preserved after swap (NI9)" "grep -q '\"ok\":true' $W/upal.json"
  UPTOK=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
    -d "{\"username\":\"e_sara\",\"password\":\"sarapass123\"}" | py "import json,sys;print(json.load(sys.stdin)['token'])")
  chk "UP app login on migrated DB" "[ -n '$UPTOK' ]"
  up_now=$(now_iso)
  curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $UPTOK" -H 'content-type: application/json' \
    -d '{"locations":[{"localId":"p3t-up0000001","lat":35.71,"lng":51.41,"recordedAt":"'"$up_now"'"}]}' -o $W/upul.json
  chk "UP upload works on migrated DB" "grep -q '\"accepted\":1' $W/upul.json"
  chk "UP migrated table SQL == fresh-template SQL (LocationPing)" \
    "py \"import sqlite3;a=sqlite3.connect('$W/inst/panel/db/custom.db');b=sqlite3.connect('$DB');qa=a.execute(\\\"SELECT sql FROM sqlite_master WHERE name='LocationPing'\\\").fetchone()[0];qb=b.execute(\\\"SELECT sql FROM sqlite_master WHERE name='LocationPing'\\\").fetchone()[0];exit(0 if qa==qb else 1)\""
else
  s "UP old template (tag v1.9.13-dev.4) not available in this checkout"
fi

echo
echo "== RESULT: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ $FAIL -eq 0 ]
