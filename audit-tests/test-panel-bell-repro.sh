#!/usr/bin/env bash
# test-panel-bell-repro.sh — package 5 / phase 5-A:
# STABILIZE + REPRODUCE the lost «همه خوانده شد» (mark-all-read) timeout.
#
# Background (phase zero, Oct 29 2026): package 5's bell UI was NEVER committed;
# the in-flight sandbox code (NotificationsBell.tsx + rebuilt bundle +
# test-panel-bell-ui.sh) was lost to a sandbox reset. The only hard evidence of
# a defect: a browser E2E failure at 11:38 Oct 28 — a TIMEOUT on the
# mark-all-read button. The mark-all API itself was healthy via curl (NI6).
#
# This script rebuilds the test infrastructure from the COMMITTED bundle and
# replays the failure surface at every layer, timing everything:
#
#   PR0  fresh install + boot + fixtures (infrastructure rebuilt from repo)
#   PR1  real events -> panel notifications; mark-ONE read (ids[]) works
#   PR2  mark-ALL on a small backlog — timed; unread -> 0
#   PR3  >100-window load (148 real notifications): window semantics of the
#        unread counter (take:100) + mark-ALL updates the WHOLE table (DB proof)
#   PR4  synthetic heavy load (5000 unread rows, inserted with the server
#        stopped): worst-case mark-all timing bound
#   PR5  race: mark-ALL concurrent with 10 parallel GETs (consistency)
#   PR6  401 gates without a session
#   PR7  BROWSER replay (Playwright/Chromium): login UI -> dashboard -> the
#        exact fetch pattern a bell UI performs, timed from INSIDE the page;
#        proves whether the committed stack can time out at all + documents at
#        browser level that the base bundle has NO bell-center UI (only the
#        browser-permission bell) and NO mark-all button.
#
# Deterministic: fresh non-interactive install; curl + python3 (+ playwright
# for PR7 — PR7 is SKIPPED with an explicit message if no browser is present).
# Run:  bash test-panel-bell-repro.sh [PKG_DIR]
#       P5A_WORK=<dir> overrides the work directory.
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${P5A_WORK:-/home/z/my-project/medpad-work/p5a-repro}"
rm -rf "$W"; mkdir -p "$W"
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="
echo "== WORKDIR: $W =="

PANEL_PORT=3263; RT_PORT=3266; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_USER="admin"; ADMIN_PASS='P5Apass123!'
DB="$W/inst/panel/db/custom.db"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# timed <label> <expected_code> <curl args...>  -> sets HTTP_CODE, TIME_T
timed() {
  local label="$1" expect="$2"; shift 2
  local meta
  meta=$(curl -s -o "$W/last.body" -w '%{http_code} %{time_total}' "$@")
  HTTP_CODE="${meta%% *}"; TIME_T="${meta##* }"
  echo "[TIME] $label: http=$HTTP_CODE time=${TIME_T}s"
  echo "$TIME_T $label" >> "$W/times.txt"
  [ "$HTTP_CODE" = "$expect" ]
}

panellist() { curl -s -b "$W/cj" "$BASE/api/panel-notifications" -o "$1"; }
unread_of() { py "import json;print(json.load(open('$1'))['unread'])"; }

# ── PR0: install + boot + fixtures ───────────────────────────
echo "== PR0: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir "$W/inst" \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user "$ADMIN_USER" --admin-pass "$ADMIN_PASS" > "$W/install.log" 2>&1)
chk "PR0 install exit 0 (medpad-config.json)" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f "$W/inst/medpad-config.json" ]; then
  echo "install failed — aborting"; tail -20 "$W/install.log"; exit 1
fi

(cd "$W/inst" && node server-manager.mjs start --no-open > "$W/boot.log" 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "PR0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c "$W/cj" -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" -o "$W/admin-login.json"
chk "PR0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

# employees EMP1..EMP3 + customers CUS1..CUS50 + app token for EMP1
E1=""; E2=""; E3=""
for i in 1 2 3; do
  curl -s -b "$W/cj" -X POST $BASE/api/employees -H 'content-type: application/json' \
    -d "{\"username\":\"p5a_e$i\",\"password\":\"e${i}pass123\",\"name\":\"P5A Emp $i\",\"phone\":\"55$i\"}" -o "$W/emp$i.json"
done
E1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
E2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
E3=$(py "import json;print(json.load(open('$W/emp3.json'))['employee']['id'])")
chk "PR0 employees created" "[ -n '$E1' ] && [ -n '$E2' ] && [ -n '$E3' ]"

CUS_IDS=""
for i in $(seq 1 50); do
  curl -s -b "$W/cj" -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d "{\"name\":\"P5A Customer $i\",\"lat\":35.70,\"lng\":51.40,\"type\":\"clinic\"}" -o "$W/cus$i.json"
  CID=$(py "import json;print(json.load(open('$W/cus$i.json'))['customer']['id'])" 2>/dev/null)
  CUS_IDS="$CUS_IDS $CID"
done
N_CUS=$(echo $CUS_IDS | wc -w)
chk "PR0 50 customers created" "[ '$N_CUS' = '50' ]"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"p5a_e1","password":"e1pass123"}' -o "$W/app1.json"
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
chk "PR0 app login (EMP1 token)" "[ -n '$TOK1' ]"

# ── PR1: real events -> notifications; mark-ONE read ─────────
echo "== PR1: real events + mark-one =="
C1=$(echo $CUS_IDS | cut -d' ' -f1); C2=$(echo $CUS_IDS | cut -d' ' -f2)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$E1\",\"customerId\":\"$C1\"}")
chk "PR1 assignment 1 -> 200" "[ '$CODE' = '200' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$E1\",\"customerId\":\"$C2\"}")
chk "PR1 assignment 2 -> 200" "[ '$CODE' = '200' ]"

curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$C1" -F "note=p5a checkin" -F "localId=p5a-ci-$(date +%s)" \
  -F "lat=35.7001" -F "lng=51.4001" -o "$W/ci.json"
chk "PR1 check-in accepted" "grep -q '\"ok\":true' $W/ci.json"

panellist "$W/p1.json"
chk "PR1 panel list has >=3 notifications" "py \"import json;exit(0 if len(json.load(open('$W/p1.json'))['notifications'])>=3 else 1)\""
chk "PR1 unread == 3" "[ \"\$(unread_of $W/p1.json)\" = '3' ]"

NID=$(py "import json;print(json.load(open('$W/p1.json'))['notifications'][0]['id'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d "{\"ids\":[\"$NID\"]}")
chk "PR1 mark ONE read -> 200" "[ '$CODE' = '200' ]"
panellist "$W/p1b.json"
chk "PR1 unread decreased to 2" "[ \"\$(unread_of $W/p1b.json)\" = '2' ]"
chk "PR1 that row now has readAt" "py \"import json;d=json.load(open('$W/p1b.json'));exit(0 if [n for n in d['notifications'] if n['id']=='$NID'][0]['readAt'] else 1)\""

# ── PR2: mark-ALL on small backlog — timed ───────────────────
echo "== PR2: mark-all (2 unread) — timed =="
timed "PR2 mark-all (2 unread)" 200 -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d '{}'
chk "PR2 mark-all -> 200" "[ '$HTTP_CODE' = '200' ]"
panellist "$W/p2.json"
chk "PR2 unread -> 0" "[ \"\$(unread_of $W/p2.json)\" = '0' ]"
chk "PR2 every row read (API view)" "py \"import json;d=json.load(open('$W/p2.json'));exit(0 if all(n['readAt'] for n in d['notifications']) else 1)\""

# ── PR3: >100-window load (148 real assignments) ─────────────
echo "== PR3: 148 real notifications (> take:100 window) =="
OK_COUNT=0; FAIL_COUNT=0
python3 - "$E1" "$E2" "$E3" $CUS_IDS > "$W/pairs.txt" <<'PYPAIRS'
import sys
e1, e2, e3 = sys.argv[1], sys.argv[2], sys.argv[3]
cus = sys.argv[4:]
pairs = []
pairs += [(e1, c) for c in cus[2:]]   # E1 x CUS3..CUS50 (48) — CUS1/CUS2 already assigned in PR1
pairs += [(e2, c) for c in cus]       # E2 x all 50
pairs += [(e3, c) for c in cus]       # E3 x all 50
for e, c in pairs: print(f"{e} {c}")
PYPAIRS
while read -r EE CC; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments </dev/null \
    -H 'content-type: application/json' -d "{\"employeeId\":\"$EE\",\"customerId\":\"$CC\"}")
  if [ "$CODE" = "200" ]; then OK_COUNT=$((OK_COUNT+1)); else FAIL_COUNT=$((FAIL_COUNT+1)); echo "  assignment $EE/$CC -> $CODE"; fi
done < "$W/pairs.txt"
echo "  assignments ok=$OK_COUNT failed=$FAIL_COUNT (expected 148 ok)"
chk "PR3 148 assignments created" "[ '$OK_COUNT' = '148' ] && [ '$FAIL_COUNT' = '0' ]"

EXPECTED_UNREAD=$OK_COUNT
panellist "$W/p3.json"
LIST_LEN=$(py "import json;print(len(json.load(open('$W/p3.json'))['notifications']))")
chk "PR3 list is CAPPED at 100 (take:100 window)" "[ '$LIST_LEN' = '100' ]"
chk "PR3 unread == 100 (window-relative, backlog is $EXPECTED_UNREAD)" "[ \"\$(unread_of $W/p3.json)\" = '100' ]"
DB_UNREAD=$(python3 -c "import sqlite3;print(sqlite3.connect('$DB').execute('SELECT COUNT(*) FROM PanelNotification WHERE readAt IS NULL').fetchone()[0])")
chk "PR3 DB total unread == $EXPECTED_UNREAD" "[ '$DB_UNREAD' = '$EXPECTED_UNREAD' ]"

timed "PR3 mark-all ($EXPECTED_UNREAD unread)" 200 -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d '{}'
chk "PR3 mark-all -> 200" "[ '$HTTP_CODE' = '200' ]"
panellist "$W/p3b.json"
chk "PR3 unread -> 0" "[ \"\$(unread_of $W/p3b.json)\" = '0' ]"
DB_CHECK=$(python3 -c "
import sqlite3
c = sqlite3.connect('$DB')
u = c.execute('SELECT COUNT(*) FROM PanelNotification WHERE readAt IS NULL').fetchone()[0]
t = c.execute('SELECT COUNT(*) FROM PanelNotification').fetchone()[0]
print(f'{u} {t}')")
chk "PR3 mark-all updated the WHOLE table (DB unread=0)" "[ \"\$(echo '$DB_CHECK' | cut -d' ' -f1)\" = '0' ]"
chk "PR3 DB total rows == $((EXPECTED_UNREAD + 3))" "[ \"\$(echo '$DB_CHECK' | cut -d' ' -f2)\" = '$((EXPECTED_UNREAD + 3))' ]"

# ── PR4: synthetic heavy load (5000 unread) — timing bound ───
echo "== PR4: synthetic 5000-row backlog =="
(cd "$W/inst" && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
PYINSERT=$(python3 - "$DB" 5000 <<'PYINS'
import sqlite3, sys, re, datetime, time
# Synthetic rows are placed at [now+1s, now+2s): newer than every existing row
# (so the take:100 window is fully synthetic for the PR4 checks) but OLDER than
# every real event created after the restart (PR5/PR7) — the first run of this
# script placed them ~100s in the future, which silently swallowed the later
# real notifications inside the take:100 window (lesson recorded in WORKLOG).
db_path, n = sys.argv[1], int(sys.argv[2])
db = sqlite3.connect(db_path)
row = db.execute("SELECT createdAt FROM PanelNotification ORDER BY rowid DESC LIMIT 1").fetchone()
if row is None:
    print("NO_SAMPLE_ROW"); sys.exit(2)
sample = row[0]
if isinstance(sample, (int, float)):
    base = int(time.time() * 1000) + 1000
    gen = lambda i: base + (i % 1000)
else:
    s = str(sample)
    m = re.match(r'^(\d{4}-\d{2}-\d{2})([T ])(\d{2}:\d{2}:\d{2})(?:\.(\d+))?', s)
    sep = m.group(2) if m else ' '
    frac = len(m.group(4)) if (m and m.group(4)) else 0
    base_dt = datetime.datetime.utcnow() + datetime.timedelta(seconds=1)
    def gen(i, base_dt=base_dt, sep=sep, frac=frac):
        v = base_dt + datetime.timedelta(milliseconds=(i % 1000))
        core = v.strftime('%Y-%m-%d' + sep + '%H:%M:%S')
        return core + ('.' + ('%06d' % v.microsecond)[:frac] if frac else '')
rows = [(f'p5a-syn-{i:05d}', f'p5a-synthetic-{i:05d}', 'info',
         'P5A synthetic load', f'synthetic row {i} for mark-all timing', None, None, None, gen(i))
        for i in range(n)]
db.executemany("INSERT INTO PanelNotification (id, eventKey, kind, title, body, employeeId, customerId, readAt, createdAt) VALUES (?,?,?,?,?,?,?,?,?)", rows)
db.commit()
left = db.execute("SELECT COUNT(*) FROM PanelNotification WHERE readAt IS NULL").fetchone()[0]
total = db.execute("SELECT COUNT(*) FROM PanelNotification").fetchone()[0]
print(f"OK UNREAD={left} TOTAL={total}")
PYINS
)
echo "  insert: $PYINSERT"
chk "PR4 5000 synthetic rows inserted (server stopped)" "echo '$PYINSERT' | grep -q 'OK UNREAD=5000'"
(cd "$W/inst" && node server-manager.mjs start --no-open > "$W/boot2.log" 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "PR4 panel back up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
sleep 3  # let wall-clock time pass the synthetic [now+1s, now+2s) window so PR5/PR7 real events sort newer
panellist "$W/p4.json"
chk "PR4 list still capped at 100" "[ \"\$(py \"import json;print(len(json.load(open('$W/p4.json'))['notifications']))\")\" = '100' ]"
chk "PR4 unread == 100 (window view of 5000)" "[ \"\$(unread_of $W/p4.json)\" = '100' ]"
timed "PR4 mark-all (5000 unread — worst case)" 200 -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d '{}'
chk "PR4 mark-all -> 200" "[ '$HTTP_CODE' = '200' ]"
panellist "$W/p4b.json"
chk "PR4 unread -> 0" "[ \"\$(unread_of $W/p4b.json)\" = '0' ]"
DB_UNREAD4=$(python3 -c "import sqlite3;print(sqlite3.connect('$DB').execute('SELECT COUNT(*) FROM PanelNotification WHERE readAt IS NULL').fetchone()[0])")
chk "PR4 DB unread -> 0 (whole 5000-row table marked)" "[ '$DB_UNREAD4' = '0' ]"

# ── PR5: race — mark-ALL concurrent with 10 parallel GETs ───
echo "== PR5: race (10 parallel GETs + mark-all) =="
# all 3x50 pairs from PR1/PR3 are used up -> create 10 FRESH customers for fresh pairs
RACE_OK=0; RACE_IDS=""
for i in $(seq 51 60); do
  curl -s -b "$W/cj" -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d "{\"name\":\"P5A Customer $i\",\"lat\":35.70,\"lng\":51.40,\"type\":\"clinic\"}" -o "$W/cus$i.json"
  CID=$(py "import json;print(json.load(open('$W/cus$i.json'))['customer']['id'])" 2>/dev/null)
  RACE_IDS="$RACE_IDS $CID"
done
N_RACE_CUS=$(echo $RACE_IDS | wc -w)
chk "PR5 10 fresh customers for the race" "[ '$N_RACE_CUS' = '10' ]"
for CC in $RACE_IDS; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments \
    -H 'content-type: application/json' -d "{\"employeeId\":\"$E1\",\"customerId\":\"$CC\"}")
  [ "$CODE" = "200" ] && RACE_OK=$((RACE_OK+1))
done
chk "PR5 10 fresh notifications" "[ '$RACE_OK' = '10' ]"
panellist "$W/p5.json"
chk "PR5 unread == 10 before race" "[ \"\$(unread_of $W/p5.json)\" = '10' ]"

for i in $(seq 1 10); do
  curl -s -b "$W/cj" -o "$W/race-get$i.json" -w "%{http_code}" "$BASE/api/panel-notifications" > "$W/race-get$i.code" &
done
MARKALL_META=$(curl -s -o "$W/race-markall.json" -w '%{http_code} %{time_total}' -b "$W/cj" \
  -X POST $BASE/api/panel-notifications/read -H 'content-type: application/json' -d '{}')
wait
echo "[TIME] PR5 mark-all (concurrent with 10 GETs): $MARKALL_META"
echo "${MARKALL_META##* } PR5 mark-all (concurrent)" >> "$W/times.txt"
chk "PR5 concurrent mark-all -> 200" "[ \"\$(echo '$MARKALL_META' | cut -d' ' -f1)\" = '200' ]"
RACE_GETS_OK=0
for i in $(seq 1 10); do
  [ "$(cat "$W/race-get$i.code")" = "200" ] && py "import json;json.load(open('$W/race-get$i.json'));d=1;exit(0)" 2>/dev/null && RACE_GETS_OK=$((RACE_GETS_OK+1))
done
chk "PR5 all 10 concurrent GETs -> 200 + valid JSON" "[ '$RACE_GETS_OK' = '10' ]"
panellist "$W/p5b.json"
chk "PR5 final unread -> 0" "[ \"\$(unread_of $W/p5b.json)\" = '0' ]"

# ── PR6: 401 gates without a session ─────────────────────────
echo "== PR6: 401 gates =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/panel-notifications")
chk "PR6 panel list without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/panel-notifications/read" \
  -H 'content-type: application/json' -d '{}')
chk "PR6 mark-read without session -> 401" "[ '$CODE' = '401' ]"

# ── PR7: BROWSER replay (the lost E2E's failure surface) ─────
echo "== PR7: browser replay (Playwright/Chromium) =="
# 3 fresh notifications so the browser session sees unread > 0 (fresh pairs: E2 x CUS61..63)
B_OK=0
for i in $(seq 61 63); do
  curl -s -b "$W/cj" -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d "{\"name\":\"P5A Customer $i\",\"lat\":35.70,\"lng\":51.40,\"type\":\"clinic\"}" -o "$W/cus$i.json"
  CID=$(py "import json;print(json.load(open('$W/cus$i.json'))['customer']['id'])" 2>/dev/null)
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments \
    -H 'content-type: application/json' -d "{\"employeeId\":\"$E2\",\"customerId\":\"$CID\"}")
  [ "$CODE" = "200" ] && B_OK=$((B_OK+1))
done
chk "PR7 3 fresh notifications for the browser session" "[ '$B_OK' = '3' ]"

if python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null; then
  cat > "$W/p5a_browser.py" <<'PYBROWSER'
import json, sys, time
from playwright.sync_api import sync_playwright

BASE, W, PASSWORD = sys.argv[1], sys.argv[2], sys.argv[3]
out = {"errors": []}
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        console, netlog = [], []
        page.on("console", lambda m: console.append(f"{m.type}: {m.text}"))
        def on_response(r):
            if "panel-notifications" in r.url:
                netlog.append({"url": r.url.split("/")[-1], "status": r.status})
        page.on("response", on_response)

        t0 = time.time()
        page.goto(BASE + "/", wait_until="domcontentloaded", timeout=30000)
        page.wait_for_selector("#u", timeout=20000)
        out["login_form_ms"] = round((time.time() - t0) * 1000)

        page.fill("#u", "admin")
        page.fill("#p", PASSWORD)
        page.click("button[type=submit]")
        page.wait_for_selector('button[title="اعلان‌های مرورگر"]', timeout=20000)
        out["dashboard_ms"] = round((time.time() - t0) * 1000)

        # document (at browser level) what the base bundle ships:
        out["permission_bell_count"] = page.locator('button[title="اعلان‌های مرورگر"]').count()
        out["markall_button_count"] = page.locator('button:has-text("خوانده")').count()

        # the EXACT fetch pattern a bell UI performs, timed from inside the page
        meas = page.evaluate("""async () => {
            const one = async (fn) => {
                const t0 = performance.now(); const r = await fn();
                return [Math.round(performance.now() - t0), r];
            };
            const [t1, l1] = await one(() => fetch('/api/panel-notifications').then(r => r.json()));
            const [t2, m]  = await one(() => fetch('/api/panel-notifications/read',
                { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })
                .then(r => r.json()));
            const [t3, l2] = await one(() => fetch('/api/panel-notifications').then(r => r.json()));
            return { ok1: l1.ok, t_list1: t1, unread1: l1.unread, n1: l1.notifications.length,
                     markall_ok: (m && m.ok === true), t_markall: t2,
                     t_list2: t3, unread2: l2.unread };
        }""")
        out.update(meas)
        out["flow_total_ms"] = meas["t_list1"] + meas["t_markall"] + meas["t_list2"]
        out["netlog"] = netlog
        page.screenshot(path=W + "/p5a-dashboard.png", full_page=True)
        out["console_messages"] = console[:40]
        browser.close()
except Exception as e:
    out["errors"].append(repr(e))
print(json.dumps(out, ensure_ascii=False))
PYBROWSER
  python3 "$W/p5a_browser.py" "$BASE" "$W" "$ADMIN_PASS" > "$W/pr7.json" 2>"$W/pr7.err"
  if [ -s "$W/pr7.json" ] && py "import json;json.load(open('$W/pr7.json'))" 2>/dev/null; then
    g() { py "import json;d=json.load(open('$W/pr7.json'));print(d.get('$1',''))"; }
    echo "  PR7 detail: login_form=$(g login_form_ms)ms dashboard=$(g dashboard_ms)ms | list1=$(g t_list1)ms unread1=$(g unread1) n1=$(g n1) | markall=$(g t_markall)ms ok=$(g markall_ok) | list2=$(g t_list2)ms unread2=$(g unread2) | total=$(g flow_total_ms)ms"
    echo "  PR7 UI census: permission-bell=$(g permission_bell_count) mark-all-button=$(g markall_button_count) console-msgs=$(py "import json;print(len(json.load(open('$W/pr7.json')).get('console_messages',[])))")"
    [ -n "$(g t_markall)" ] && echo "$(python3 -c "print($(g t_markall)/1000)") PR7 browser mark-all" >> "$W/times.txt"
    chk "PR7 browser: no runtime errors" "[ \"\$(g errors)\" = '[]' ]"
    chk "PR7 browser: login -> dashboard rendered" "[ -n \"\$(g dashboard_ms)\" ]"
    chk "PR7 browser: base bundle ships the permission bell (1)" "[ \"\$(g permission_bell_count)\" = '1' ]"
    chk "PR7 browser: NO mark-all button in the committed bundle" "[ \"\$(g markall_button_count)\" = '0' ]"
    chk "PR7 browser: GET list ok" "[ \"\$(g ok1)\" = 'True' ]"
    chk "PR7 browser: list capped at 100 (window)" "[ \"\$(g n1)\" = '100' ]"
    chk "PR7 browser: unread > 0 before mark-all" "py \"import json;exit(0 if json.load(open('$W/pr7.json'))['unread1']>0 else 1)\""
    chk "PR7 browser: mark-all ok:true" "[ \"\$(g markall_ok)\" = 'True' ]"
    chk "PR7 browser: unread == 0 after mark-all" "[ \"\$(g unread2)\" = '0' ]"
    chk "PR7 browser: every call < 5000ms" "py \"import json;d=json.load(open('$W/pr7.json'));exit(0 if max(d['t_list1'],d['t_markall'],d['t_list2'])<5000 else 1)\""
    chk "PR7 browser: whole flow < 10000ms (NO timeout)" "py \"import json;exit(0 if json.load(open('$W/pr7.json'))['flow_total_ms']<10000 else 1)\""
  else
    f "PR7 browser script produced no valid result"; [ -s "$W/pr7.err" ] && tail -5 "$W/pr7.err"
  fi
else
  s "PR7 browser replay — no playwright/chromium in this environment (recorded honestly)"
fi

# ── summary ──────────────────────────────────────────────────
echo
echo "=================================================="
echo "PANEL-BELL-REPRO (phase 5-A): PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
if [ -s "$W/times.txt" ]; then
  echo "slowest measured call: $(sort -k1 -g "$W/times.txt" | tail -1 | awk '{print $2 " — " $1 "s"}')"
fi
if [ $FAIL -eq 0 ]; then
  echo "P5A-REPRO-CLEAN — the committed stack did NOT reproduce the timeout at any tested layer;"
  echo "root cause is bounded to the LOST UI implementation / test harness (see WORKLOG Task 36)."
else
  echo "SOME-CHECKS-FAILED — see the [FAIL] lines above."
fi
echo "=================================================="
cp "$W/times.txt" "$W/p5a-timing-evidence.txt" 2>/dev/null || true
exit $([ $FAIL -eq 0 ] && echo 0 || echo 1)
