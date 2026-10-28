#!/usr/bin/env bash
# test-panel-bell-ui.sh — package 5 / phase 5-C:
# the notification-center BELL UI in a REAL browser (Playwright/Chromium).
#
# Proves the 5-C implementation against the 5-A defensive requirements
# (WORKLOG Task ID: 36) and the 5-B API contract:
#   BU0  fresh install + boot + fixtures (NO notifications yet)
#   BU1  login -> dashboard; bell button exists; NO badge; popover shows the
#        EMPTY state (bell-empty)
#   BU2  realtime (H4): in-page real events (assignment + check-in) -> the
#        socket event triggers a refetch -> badge shows 2 WITHOUT any reload
#   BU3  popover: list of 2, both unread (2 mark-one buttons)
#   BU4  mark-ONE (H1): badge 2->1, that row loses its mark-one button but
#        stays listed (now read)
#   BU5  realtime again (H4): second assignment + check-in -> badge 3
#   BU6  «همه خوانده شد» (H1): mark-all -> badge DISAPPEARS (refetched to 0),
#        all 3 rows stay listed, no mark-one buttons, NOT the empty state
#   BU7  reopen: rows persist as read
#   BU8  expired session (H3): /api/logout from the page -> opening the bell
#        shows bell-session-expired (never a silent failure) -> relogin
#        button returns to the login screen
#
# All automation goes through stable data-testid selectors — Persian strings
# are visual labels only (the H2 lesson from 5-A).
#
# Deterministic: fresh non-interactive install; curl + python3; Playwright
# with headless Chromium (SKIPPED loudly when no browser is available).
# Run:  bash test-panel-bell-ui.sh [PKG_DIR]
#       P5UI_WORK=<dir> overrides the work directory.
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${P5UI_WORK:-/tmp/medpad-p5ui}"
rm -rf "$W"; mkdir -p "$W"
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="
echo "== WORKDIR: $W =="

PANEL_PORT=3283; RT_PORT=3286; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_USER="admin"; ADMIN_PASS='P5Cpass123!'

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── BU0: install + boot + fixtures (no notifications yet) ────
echo "== BU0: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir "$W/inst" \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user "$ADMIN_USER" --admin-pass "$ADMIN_PASS" > "$W/install.log" 2>&1)
chk "BU0 install exit 0 (medpad-config.json)" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f "$W/inst/medpad-config.json" ]; then
  echo "install failed — aborting"; tail -20 "$W/install.log"; exit 1
fi

(cd "$W/inst" && node server-manager.mjs start --no-open > "$W/boot.log" 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "BU0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c "$W/cj" -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" -o "$W/admin-login.json"
chk "BU0 admin login (API)" "grep -q '\"ok\":true' $W/admin-login.json"

curl -s -b "$W/cj" -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"p5ui_e1","password":"e1pass123","name":"P5UI Emp 1","phone":"551"}' -o "$W/emp1.json"
E1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
chk "BU0 employee created" "[ -n '$E1' ]"

CUS1=""; CUS2=""; CUS3=""
for i in 1 2 3; do
  curl -s -b "$W/cj" -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d "{\"name\":\"P5UI Customer $i\",\"lat\":35.70,\"lng\":51.40,\"type\":\"clinic\"}" -o "$W/cus$i.json"
done
CUS1=$(py "import json;print(json.load(open('$W/cus1.json'))['customer']['id'])")
CUS2=$(py "import json;print(json.load(open('$W/cus2.json'))['customer']['id'])")
CUS3=$(py "import json;print(json.load(open('$W/cus3.json'))['customer']['id'])")
chk "BU0 3 customers created" "[ -n '$CUS1' ] && [ -n '$CUS2' ] && [ -n '$CUS3' ]"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"p5ui_e1","password":"e1pass123"}' -o "$W/app1.json"
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
chk "BU0 app login (tracker token)" "[ -n '$TOK1' ]"

# no notifications exist yet (fixture creates none)
curl -s -b "$W/cj" "$BASE/api/panel-notifications" -o "$W/p0.json"
chk "BU0 zero notifications at start (unread=0)" "[ \"\$(py \"import json;print(json.load(open('$W/p0.json'))['unread'])\")\" = '0' ]"

# ── BU1..BU8: the browser session ────────────────────────────
if python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null; then
  cat > "$W/p5ui_browser.py" <<'PYBROWSER'
import json, sys, time
from playwright.sync_api import sync_playwright

BASE, W, PASSWORD, TOK1, E1, CUS1, CUS2, CUS3 = sys.argv[1:9]
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

    # login
    page.goto(BASE + "/", wait_until="domcontentloaded")
    page.fill("#u", "admin")
    page.fill("#p", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_selector('button[title="اعلان‌های مرورگر"]')  # dashboard ready (base bundle feature)

    def badge_text():
        el = page.locator('[data-testid="bell-unread-count"]')
        return el.text_content() if el.count() else None

    # BU1: bell exists, no badge, empty state
    step("bu1_bell_button", lambda: page.locator('[data-testid="bell-button"]').count())
    step("bu1_no_badge", lambda: page.locator('[data-testid="bell-unread-count"]').count() == 0)
    step("bu1_socket_connected", lambda: (page.wait_for_selector(".bg-emerald-500", timeout=20000) is not None))
    page.click('[data-testid="bell-button"]')
    page.wait_for_selector('[data-testid="bell-empty"]')
    step("bu1_empty_state", lambda: True)
    page.keyboard.press("Escape")

    # in-page helpers (carry the admin session cookie)
    def assign(cus):
        return page.evaluate("""async (cus) => {
            const r = await fetch('/api/assignments', { method: 'POST',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({ employeeId: %s, customerId: cus }) });
            return r.status;
        }""" % json.dumps(E1), cus)

    def checkin(cus):
        return page.evaluate("""async (cus) => {
            const fd = new FormData();
            fd.append('customerId', cus);
            fd.append('note', 'p5ui checkin note');
            fd.append('localId', 'p5ui-' + Date.now() + '-' + Math.random().toString(36).slice(2,8));
            fd.append('lat', '35.7001'); fd.append('lng', '51.4001');
            const r = await fetch('/api/app/checkin', { method: 'POST',
                headers: { 'X-Medpad-Token': %s }, body: fd });
            const d = await r.json().catch(() => ({}));
            return [r.status, d.ok === true, d.duplicate === true];
        }""" % json.dumps(TOK1), cus)

    # BU2: realtime — assignment + check-in -> badge 2, no reload
    step("bu2_assign_status", lambda: assign(CUS1))
    step("bu2_checkin_status", lambda: checkin(CUS1))
    def wait_badge(expect, timeout=25000):
        page.wait_for_function(
            """(v) => { const el = document.querySelector('[data-testid="bell-unread-count"]');
                       return (el ? el.textContent : null) === v; }""",
            arg=str(expect), timeout=timeout)
        return True
    step("bu2_badge_2_live", lambda: wait_badge(2))

    # BU3: popover list of 2, both unread
    page.click('[data-testid="bell-button"]')
    page.wait_for_selector('[data-testid="bell-list"]')
    step("bu3_items_2", lambda: page.locator('[data-testid^="bell-item-"]').count())
    step("bu3_unread_buttons_2", lambda: page.locator('[data-testid^="bell-mark-one-"]').count())
    first_mark = page.locator('[data-testid^="bell-mark-one-"]').first
    marked_tid = first_mark.get_attribute("data-testid")
    out["bu4_marked_id"] = marked_tid.replace("bell-mark-one-", "") if marked_tid else None
    page.keyboard.press("Escape")

    # BU4: mark-one -> badge 1, that row read but still listed
    page.click('[data-testid="bell-button"]')
    page.wait_for_selector('[data-testid="bell-list"]')
    page.click(f'[data-testid="{marked_tid}"]')
    step("bu4_badge_1", lambda: wait_badge(1))
    step("bu4_row_still_listed", lambda: page.locator(f'[data-testid="bell-item-{out["bu4_marked_id"]}"]').count() == 1)
    step("bu4_row_now_read", lambda: page.locator(f'[data-testid="bell-mark-one-{out["bu4_marked_id"]}"]').count() == 0)
    step("bu4_listed_rows", lambda: page.locator('[data-testid^="bell-item-"]').count())
    page.keyboard.press("Escape")

    # BU5: realtime again -> badge 3
    step("bu5_assign_status", lambda: assign(CUS2))
    step("bu5_checkin_status", lambda: checkin(CUS2))
    step("bu5_badge_3_live", lambda: wait_badge(3))

    # BU6: mark-all -> badge disappears, rows stay listed and read
    page.click('[data-testid="bell-button"]')
    page.wait_for_selector('[data-testid="bell-list"]')
    page.screenshot(path=W + "/p5ui-list-unread.png")
    page.click('[data-testid="bell-mark-all"]')
    def badge_gone(timeout=20000):
        page.wait_for_function(
            """() => document.querySelector('[data-testid="bell-unread-count"]') === null""",
            timeout=timeout)
        return True
    step("bu6_badge_gone", lambda: badge_gone())
    step("bu6_rows_still_listed", lambda: page.locator('[data-testid^="bell-item-"]').count())
    step("bu6_rows_expect_4", lambda: page.locator('[data-testid^="bell-item-"]').count() == 4)
    step("bu6_no_mark_buttons", lambda: page.locator('[data-testid^="bell-mark-one-"]').count() == 0)
    step("bu6_not_empty_state", lambda: page.locator('[data-testid="bell-empty"]').count() == 0)
    page.keyboard.press("Escape")

    # BU7: reopen — rows persist as read
    page.click('[data-testid="bell-button"]')
    page.wait_for_selector('[data-testid="bell-list"]')
    step("bu7_rows_persist", lambda: page.locator('[data-testid^="bell-item-"]').count())
    page.keyboard.press("Escape")

    # BU8: expired session — explicit state, relogin returns to login screen
    step("bu8_logout_status", lambda: page.evaluate(
        "async () => (await fetch('/api/logout', { method: 'POST' })).status"))
    page.click('[data-testid="bell-button"]')
    page.wait_for_selector('[data-testid="bell-session-expired"]', timeout=15000)
    page.screenshot(path=W + "/p5ui-expired.png")
    step("bu8_expired_state", lambda: True)
    page.click('[data-testid="bell-relogin"]')
    page.wait_for_selector("#u", timeout=15000)
    step("bu8_back_to_login", lambda: True)

    browser.close()

print(json.dumps(out, ensure_ascii=False))
PYBROWSER
  python3 "$W/p5ui_browser.py" "$BASE" "$W" "$ADMIN_PASS" "$TOK1" "$E1" "$CUS1" "$CUS2" "$CUS3" \
    > "$W/bu.json" 2> "$W/bu.err"
  if [ -s "$W/bu.json" ] && py "import json;json.load(open('$W/bu.json'))" 2>/dev/null; then
    g() { py "import json;d=json.load(open('$W/bu.json'));print(d.get('$1',''))"; }
    chk "BU1 bell button present" "[ \"\$(g bu1_bell_button)\" = '1' ]"
    chk "BU1 no badge with zero notifications" "[ \"\$(g bu1_no_badge)\" = 'True' ]"
    chk "BU1 realtime socket connected" "[ \"\$(g bu1_socket_connected)\" = 'True' ]"
    chk "BU1 empty state shown (bell-empty)" "[ \"\$(g bu1_empty_state)\" = 'True' ]"
    chk "BU2 in-page assignment -> 200" "[ \"\$(g bu2_assign_status)\" = '200' ]"
    BU2_CI=$(g bu2_checkin_status)
    chk "BU2 in-page check-in -> ok" "echo '$BU2_CI' | grep -q '200, True'"
    chk "BU2 badge shows 2 LIVE (socket -> refetch, H4)" "[ \"\$(g bu2_badge_2_live)\" = 'True' ]"
    chk "BU3 popover lists 2 notifications" "[ \"\$(g bu3_items_2)\" = '2' ]"
    chk "BU3 both unread (2 mark-one buttons)" "[ \"\$(g bu3_unread_buttons_2)\" = '2' ]"
    chk "BU4 mark-one: badge 2->1 (refetch, H1)" "[ \"\$(g bu4_badge_1)\" = 'True' ]"
    chk "BU4 marked row stays listed" "[ \"\$(g bu4_row_still_listed)\" = 'True' ]"
    chk "BU4 marked row is now read" "[ \"\$(g bu4_row_now_read)\" = 'True' ]"
    chk "BU4 list still has 2 rows" "[ \"\$(g bu4_listed_rows)\" = '2' ]"
    chk "BU5 second assignment -> 200" "[ \"\$(g bu5_assign_status)\" = '200' ]"
    BU5_CI=$(g bu5_checkin_status)
    chk "BU5 second check-in -> ok" "echo '$BU5_CI' | grep -q '200, True'"
    chk "BU5 badge shows 3 LIVE (H4)" "[ \"\$(g bu5_badge_3_live)\" = 'True' ]"
    chk "BU6 mark-all: badge DISAPPEARS (refetch to 0, H1)" "[ \"\$(g bu6_badge_gone)\" = 'True' ]"
    chk "BU6 all 4 rows stay listed (2 BU2 + 2 BU5, one already read)" "[ \"\$(g bu6_rows_expect_4)\" = 'True' ]"
    chk "BU6 no mark-one buttons left" "[ \"\$(g bu6_no_mark_buttons)\" = 'True' ]"
    chk "BU6 not the empty state" "[ \"\$(g bu6_not_empty_state)\" = 'True' ]"
    chk "BU7 reopen: 4 read rows persist" "[ \"\$(g bu7_rows_persist)\" = '4' ]"
    chk "BU8 session-expired state shown (H3)" "[ \"\$(g bu8_expired_state)\" = 'True' ]"
    chk "BU8 relogin returns to login screen" "[ \"\$(g bu8_back_to_login)\" = 'True' ]"
    N_ERR=$(py "import json;print(len(json.load(open('$W/bu.json')).get('errors',[])))")
    chk "BU browser session had zero step errors" "[ '$N_ERR' = '0' ]"
    if [ "$N_ERR" != "0" ]; then
      echo "--- step errors (debug) ---"; py "import json;[print(' ', e) for e in json.load(open('$W/bu.json'))['errors']]"
    fi
  else
    f "BU browser run produced no result (see $W/bu.err)"
    tail -5 "$W/bu.err" 2>/dev/null
  fi
else
  s "BU1..BU8 browser checks (no Playwright/Chromium in this environment)"
  echo "!! install playwright + chromium to run the UI layer"
fi

echo
echo "=================================================="
echo "P5UI RESULT: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
echo "=================================================="
[ "$FAIL" = "0" ] || exit 1
echo "ALL-P5UI-CHECKS-PASS"
exit 0
