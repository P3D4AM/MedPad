#!/usr/bin/env bash
# test-phase2-daily.sh — phase 2 COMPLETION (package 13):
#
#   §6  self-service password change from the app (server validation,
#       session-invalidation policy, rate limiting, audit rows)
#   §2  report text edit + APPEND-ONLY version history (app API, panel API,
#       edited badge, history dialog) + upgrade path from pre-package-13 DB
#   §3  panel audio player (Playwright UI: play / pause / resume-from-pause /
#       stop / speed / single-flight / error state for missing files)
#
# Covers the «فاز دو» gaps found by the phase-zero roadmap audit:
# notification tap-through (§1) and server-change (§5) are ANDROID-side and
# are tested with the Android unit suite; this file covers everything that
# lives in the panel/server.
#
# Deterministic: fresh non-interactive install; curl + python3; Playwright
# with headless Chromium (audio layer SKIPPED loudly when unavailable).
# Run:  bash test-phase2-daily.sh [PKG_DIR]
#       P2D_WORK=<dir> overrides the work directory.
#       P2D_OLD_DB=<file> overrides the pre-package-13 template source
#       (default: git tag v1.9.13-dev.3 inside PKG_DIR — the last release
#       before package 13; skipped loudly when neither exists).
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${P2D_WORK:-/tmp/tp2d}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3303; RT_PORT=3306; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_PASS='P2Dpass123!'

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── PD0: fresh install + boot + fixtures ─────────────────────
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
chk "PD0 fresh DB has CheckinNoteVersion (new template)" \
  "py \"import sqlite3;c=sqlite3.connect('$W/inst/panel/db/custom.db');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='CheckinNoteVersion'\\\").fetchone() else 1)\""

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o $W/al.json
chk "PD0 admin login" "grep -q '\"ok\":true' $W/al.json"

mkemp() { curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\",\"name\":\"$3\",\"phone\":\"$4\"}" -o $W/emp_$1.json; }
mkemp e_ali  alipass123 'E Ali'  551
mkemp e_sara sarapass123 'E Sara' 552
mkemp e_reza rezapass123 'E Reza' 553
EMP=$(py "import json;print(json.load(open('$W/emp_e_ali.json'))['employee']['id'])")
chk "PD0 employees created" "[ -n '$EMP' ]"

mkcus() { curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d "{\"name\":\"$1\",\"lat\":$2,\"lng\":$3,\"type\":\"clinic\"}" -o $W/cus_$4.json; }
mkcus 'P2D Clinic'   35.70 51.40 c1
mkcus 'P2D Pharmacy' 35.71 51.41 c2
mkcus 'P2D Lab'      35.72 51.42 c3
mkcus 'P2D Store'    35.73 51.43 c4
mkcus 'P2D Mall'     35.74 51.44 c5
CUS1=$(py "import json;print(json.load(open('$W/cus_c1.json'))['customer']['id'])")
CUS2=$(py "import json;print(json.load(open('$W/cus_c2.json'))['customer']['id'])")
CUS3=$(py "import json;print(json.load(open('$W/cus_c3.json'))['customer']['id'])")
CUS4=$(py "import json;print(json.load(open('$W/cus_c4.json'))['customer']['id'])")
CUS5=$(py "import json;print(json.load(open('$W/cus_c5.json'))['customer']['id'])")
for c in $CUS1 $CUS2 $CUS3 $CUS4 $CUS5; do
  curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
    -d "{\"employeeId\":\"$EMP\",\"customerId\":\"$c\"}" -o /dev/null
done
chk "PD0 customers + assignments" "[ -n '$CUS5' ]"

apptok() { curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | py "import json,sys;print(json.load(sys.stdin)['token'])"; }
TOK=$(apptok e_ali alipass123)
TOK2=$(apptok e_ali alipass123)   # second session (revocation target)
TOK_SARA=$(apptok e_sara sarapass123)
TOK_REZA=$(apptok e_reza rezapass123)
chk "PD0 app logins (2 sessions for e_ali)" "[ -n '$TOK' ] && [ -n '$TOK2' ]"
echo "$TOK" > $W/tok; echo "$TOK2" > $W/tok2; echo "$EMP" > $W/emp

# report to be edited later (2 edits -> 2 versions)
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK" \
  -F "customerId=$CUS1" -F "note=گزارش اولیه ویزیت" -F "lat=35.7001" -F "lng=51.4001" \
  -F "localId=p2d-lid-1" -o $W/ch_edit.json
CH_EDIT=$(py "import json;print(json.load(open('$W/ch_edit.json'))['checkin']['id'])")
curl -s -X POST $BASE/api/app/report-edit -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d "{\"checkinId\":\"$CH_EDIT\",\"note\":\"گزارش اصلاح‌شده — موجودی شمارش شد\"}" -o /dev/null
curl -s -X POST $BASE/api/app/report-edit -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d "{\"checkinId\":\"$CH_EDIT\",\"note\":\"گزارش نهایی با جزئیات کامل\"}" -o /dev/null
echo "$CH_EDIT" > $W/ch_edit
chk "PD0 edited report (2 versions)" "grep -q '\"ok\":true' $W/ch_edit.json"

# 8-second PCM WAV (no proprietary codec — headless-Chromium safe)
py "
import struct, math, wave
w = wave.open('$W/tone8s.wav', 'wb')
w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000)
frames = bytearray()
for i in range(8000*8):
    frames += struct.pack('<h', int(12000 * math.sin(2*math.pi*440*i/8000)))
w.writeframes(bytes(frames)); w.close()
"
mkvoice() { curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK" \
  -F "customerId=$1" -F "note=$2" -F "lat=$3" -F "lng=$4" -F "localId=$5" \
  -F "voice=@$W/tone8s.wav;type=audio/wav" -o $6; }
mkvoice $CUS2 'گزارش صدای یک' 35.7101 51.4101 p2d-lid-2 $W/ch_v1.json
mkvoice $CUS4 'گزارش صدای دو' 35.7301 51.4301 p2d-lid-3 $W/ch_v2.json
mkvoice $CUS3 'گزارش با صدای گمشده' 35.7201 51.4201 p2d-lid-4 $W/ch_gone.json
CH_V1=$(py "import json;print(json.load(open('$W/ch_v1.json'))['checkin']['id'])")
CH_V2=$(py "import json;print(json.load(open('$W/ch_v2.json'))['checkin']['id'])")
CH_GONE=$(py "import json;print(json.load(open('$W/ch_gone.json'))['checkin']['id'])")
VGONE=$(py "import json;print(json.load(open('$W/ch_gone.json'))['checkin']['voicePath'])")
echo "$CH_V1" > $W/ch_v1; echo "$CH_V2" > $W/ch_v2; echo "$CH_GONE" > $W/ch_gone
chk "PD0 three voice check-ins" "[ -n '$CH_V1' ] && [ -n '$CH_V2' ] && [ -n '$CH_GONE' ]"
# delete the third file from disk -> media route 404s -> player error state
rm -f "$W/inst/panel/.next/standalone/uploads/$VGONE"
chk "PD0 gone-file really 404s" "! curl -s -b $W/cj -o /dev/null -w '%{http_code}' $BASE/api/media/$VGONE | grep -q 200"

# ── CP: §6 change-password ───────────────────────────────────
echo "== CP: §6 change-password =="
CP_URL="$BASE/api/app/change-password"
chk "CP1 no token -> 401" \
  "curl -s -o $W/cp1.json -w '%{http_code}' -X POST $CP_URL -H 'content-type: application/json' -d '{\"currentPassword\":\"alipass123\",\"newPassword\":\"P2Dnew456!\",\"confirmPassword\":\"P2Dnew456!\"}' | grep -q 401"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"currentPassword":"alipass123","newPassword":"P2Dnew456!","confirmPassword":"OTHER123!"}' -o $W/cp2.json
chk "CP2a confirm mismatch -> 400" "grep -q 'مطابقت ندارد' $W/cp2.json"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"currentPassword":"alipass123","newPassword":"short","confirmPassword":"short"}' -o $W/cp2b.json
chk "CP2b short new password -> 400" "grep -q '۸ نویسه' $W/cp2b.json"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"currentPassword":"alipass123","newPassword":"alipass123","confirmPassword":"alipass123"}' -o $W/cp2c.json
chk "CP2c same as current -> 400" "grep -q 'یکسان' $W/cp2c.json"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"newPassword":"P2Dnew456!","confirmPassword":"P2Dnew456!"}' -o $W/cp2d.json
chk "CP2d missing current -> 400" "grep -q 'نامعتبر' $W/cp2d.json"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"currentPassword":"WRONG","newPassword":"P2Dnew456!","confirmPassword":"P2Dnew456!"}' -o $W/cp3.json
chk "CP3 wrong current -> 403" "grep -q 'رمز فعلی نادرست' $W/cp3.json"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d '{"currentPassword":"alipass123","newPassword":"P2Dnew456!","confirmPassword":"P2Dnew456!"}' -o $W/cp4.json
chk "CP4 success (policy: revoke OTHERS, keep current)" \
  "grep -q '\"ok\":true' $W/cp4.json && grep -q '\"revokedSessions\":1' $W/cp4.json"
chk "CP5 second session revoked -> 401" \
  "curl -s -o /dev/null -w '%{http_code}' -H 'X-Medpad-Token: $TOK2' $BASE/api/app/reports | grep -q 401"
chk "CP6 current device still valid -> 200" \
  "curl -s -o /dev/null -w '%{http_code}' -H 'X-Medpad-Token: $TOK' $BASE/api/app/reports | grep -q 200"
chk "CP7a old password login -> 401" \
  "curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/app/login -H 'content-type: application/json' -d '{\"username\":\"e_ali\",\"password\":\"alipass123\"}' | grep -q 401"
chk "CP7b new password login -> ok" \
  "curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' -d '{\"username\":\"e_ali\",\"password\":\"P2Dnew456!\"}' | grep -q '\"ok\":true'"
# rate limit: 5 wrong attempts on e_reza -> lockout (even correct password)
LOCKED=0
for i in 1 2 3 4 5; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $CP_URL -H "X-Medpad-Token: $TOK_REZA" \
    -H 'content-type: application/json' -d '{"currentPassword":"WRONG","newPassword":"Whatever123!","confirmPassword":"Whatever123!"}')
  [ "$CODE" = "403" ] || LOCKED=$((LOCKED+1))
done
chk "CP8a five wrong attempts all 403" "[ '$LOCKED' = '0' ]"
curl -s -X POST $CP_URL -H "X-Medpad-Token: $TOK_REZA" -H 'content-type: application/json' \
  -d '{"currentPassword":"rezapass123","newPassword":"Whatever123!","confirmPassword":"Whatever123!"}' -o $W/cp8.json
chk "CP8b locked out even with CORRECT password (429)" "grep -q 'تلاش‌های ناموفق' $W/cp8.json"
curl -s -b $W/cj "$BASE/api/audit-log?limit=50" -o $W/audit.json
chk "CP9a audit: success row for password-change" "grep -q 'employee.password-change' $W/audit.json"
chk "CP9b audit: failure row too (wrong current)" "grep -q 'wrong current password' $W/audit.json"
chk "CP9c audit NEVER contains the new password" "! grep -q 'P2Dnew456' $W/audit.json"

# ── RE: §2 report edit + version history ─────────────────────
echo "== RE: §2 report edit + version history =="
# fresh note (new report, never edited) — new note must be distinguishable
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK" \
  -F "customerId=$CUS5" -F "note=یادداشت تازه بدون ویرایش" -F "lat=35.7401" -F "lng=51.4401" \
  -F "localId=p2d-lid-5" -o $W/ch_fresh.json
CH_FRESH=$(py "import json;print(json.load(open('$W/ch_fresh.json'))['checkin']['id'])")
curl -s -b $W/cj "$BASE/api/checkins?limit=500" -o $W/list.json
chk "RE1 fresh report: versionCount 0 (new note != edit)" \
  "py \"import json;d=json.load(open('$W/list.json'));c=[x for x in d['checkins'] if x['id']=='$CH_FRESH'][0];exit(0 if c['noteVersionCount']==0 else 1)\""
chk "RE1b edited report: versionCount 2 in panel list" \
  "py \"import json;d=json.load(open('$W/list.json'));c=[x for x in d['checkins'] if x['id']=='$CH_EDIT'][0];exit(0 if c['noteVersionCount']==2 else 1)\""
curl -s -X POST $BASE/api/app/report-edit -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d "{\"checkinId\":\"$CH_EDIT\",\"note\":\"گزارش نهایی با جزئیات کامل\"}" -o $W/re2.json
chk "RE2 no-op edit -> changed:false, NO new version" \
  "grep -q '\"changed\":false' $W/re2.json && grep -q '\"versionCount\":2' $W/re2.json"
curl -s -X POST $BASE/api/app/report-edit -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d "{\"checkinId\":\"$CH_EDIT\",\"note\":\"ویرایش سوم — تسویه حساب انجام شد\"}" -o $W/re3.json
chk "RE3 third edit -> versionCount 3" "grep -q '\"versionCount\":3' $W/re3.json"
curl -s -b $W/cj "$BASE/api/checkins/versions?id=$CH_EDIT" -o $W/vers.json
chk "RE4a append-only: 3 rows" \
  "py \"import json;d=json.load(open('$W/vers.json'));exit(0 if len(d['versions'])==3 else 1)\""
chk "RE4b oldest row kept ORIGINAL text (history not rewritten)" \
  "py \"import json;d=json.load(open('$W/vers.json'));v=d['versions'][-1];exit(0 if v['oldNote']=='گزارش اولیه ویزیت' and v['newNote'].startswith('گزارش اصلاح') else 1)\""
chk "RE4c newest first + editor + timestamp" \
  "py \"import json;d=json.load(open('$W/vers.json'));v=d['versions'][0];exit(0 if 'تسویه' in v['newNote'] and v['editor']['name']=='E Ali' and v['createdAt'] else 1)\""
chk "RE5 other employee -> 404 (no existence leak)" \
  "curl -s -o $W/re5.json -w '%{http_code}' -X POST $BASE/api/app/report-edit -H 'X-Medpad-Token: $TOK_SARA' -H 'content-type: application/json' -d '{\"checkinId\":\"$CH_EDIT\",\"note\":\"hack\"}' | grep -q 404"
py "import json;print(json.dumps({'checkinId':'$CH_EDIT','note':'x'*5001}))" > $W/long.json
chk "RE6 over 5000 chars -> 400" \
  "curl -s -o $W/re6.json -w '%{http_code}' -X POST $BASE/api/app/report-edit -H 'X-Medpad-Token: $TOK' -H 'content-type: application/json' -d @$W/long.json | grep -q 400"
chk "RE7 versions API without session -> 401" \
  "curl -s -o /dev/null -w '%{http_code}' $BASE/api/checkins/versions?id=$CH_EDIT | grep -q 401"
chk "RE7b versions API unknown id -> 404" \
  "curl -s -o /dev/null -w '%{http_code}' -b $W/cj '$BASE/api/checkins/versions?id=nope' | grep -q 404"
curl -s -H "X-Medpad-Token: $TOK" "$BASE/api/app/reports" -o $W/myreports.json
chk "RE8 app reports list reflects the edited text" \
  "py \"import json;d=json.load(open('$W/myreports.json'));r=[x for x in d['reports'] if x['id']=='$CH_EDIT'][0];exit(0 if 'تسویه' in r['note'] else 1)\""

# ── AP: §3 audio player UI (Playwright) ──────────────────────
echo "== AP: §3 audio player UI =="
if python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null; then
  cat > "$W/p2d_browser.py" <<'PYBROWSER'
import json, sys, time
from playwright.sync_api import sync_playwright

BASE, W, PASSWORD = sys.argv[1], sys.argv[2], sys.argv[3]
CH_V1 = open(f"{W}/ch_v1").read().strip()
CH_V2 = open(f"{W}/ch_v2").read().strip()
CH_GONE = open(f"{W}/ch_gone").read().strip()
CH_EDIT = open(f"{W}/ch_edit").read().strip()

out = {"errors": []}
def step(name, fn):
    try:
        out[name] = fn()
    except Exception as e:
        out["errors"].append(f"{name}: {e!r}")
        out[name] = None

FA = "۰۱۲۳۴۵۶۷۸۹"
def fa_secs(text):
    m, s = text.split(" / ")[0].split(":")
    mv = int("".join(str(FA.index(c)) if c in FA else c for c in m) or 0)
    sv = int("".join(str(FA.index(c)) if c in FA else c for c in s) or 0)
    return mv * 60 + sv

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.set_default_timeout(20000)
    page.goto(BASE + "/", wait_until="domcontentloaded")
    page.fill("#u", "admin")
    page.fill("#p", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_selector('button[title="اعلان‌های مرورگر"]')
    page.click("text=چک‌این‌ها")
    page.wait_for_selector(f'[data-testid="audio-player-{CH_V1}"]')

    def status(ch):
        return page.locator(f'[data-testid="audio-status-{ch}"]').get_attribute("data-state")
    def timetext(ch):
        return page.locator(f'[data-testid="audio-time-{ch}"]').text_content()

    # AP1 play -> playing, time advances
    page.click(f'[data-testid="audio-play-{CH_V1}"]')
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V1}"][data-state="playing"]', timeout=8000)
    deadline = time.time() + 8
    while time.time() < deadline:
        if fa_secs(timetext(CH_V1)) > 0:
            break
        page.wait_for_timeout(300)
    step("ap1_playing_time_advances", lambda: fa_secs(timetext(CH_V1)) > 0)
    step("ap1_duration_loaded", lambda: "۸" in timetext(CH_V1))

    # AP2 pause -> paused; resume continues from position
    page.click(f'[data-testid="audio-pause-{CH_V1}"]')
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V1}"][data-state="paused"]')
    t_pause = fa_secs(timetext(CH_V1))
    page.click(f'[data-testid="audio-play-{CH_V1}"]')
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V1}"][data-state="playing"]')
    page.wait_for_timeout(700)
    t_resume = fa_secs(timetext(CH_V1))
    step("ap2_resume_from_position", lambda: t_resume >= t_pause)
    step("ap2_still_playing", lambda: status(CH_V1) == "playing")

    # AP3 speed cycle 1x -> 1.25x
    s0 = page.locator(f'[data-testid="audio-speed-{CH_V1}"]').text_content()
    page.click(f'[data-testid="audio-speed-{CH_V1}"]')
    s1 = page.locator(f'[data-testid="audio-speed-{CH_V1}"]').text_content()
    step("ap3_speed_cycle", lambda: (s0 == "×۱" and s1 == "×۱٫۲۵"))

    # AP4 stop -> idle, time reset
    page.click(f'[data-testid="audio-stop-{CH_V1}"]')
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V1}"][data-state="idle"]', state="attached")
    step("ap4_stop_resets", lambda: fa_secs(timetext(CH_V1)) == 0)

    # AP5 single-flight: play V1 then V2 -> V1 paused, V2 playing
    page.click(f'[data-testid="audio-play-{CH_V1}"]')
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V1}"][data-state="playing"]', timeout=8000)
    page.click(f'[data-testid="audio-play-{CH_V2}"]')
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V2}"][data-state="playing"]', timeout=8000)
    page.wait_for_selector(f'[data-testid="audio-status-{CH_V1}"][data-state="paused"]', timeout=8000)
    step("ap5_single_flight", lambda: True)
    page.click(f'[data-testid="audio-stop-{CH_V2}"]')

    # AP6 missing file -> explicit error + retry
    page.click(f'[data-testid="audio-play-{CH_GONE}"]')
    page.wait_for_selector(f'[data-testid="audio-error-{CH_GONE}"]', timeout=8000)
    step("ap6_error_text", lambda: "موجود نیست" in page.locator(f'[data-testid="audio-error-{CH_GONE}"]').text_content())
    step("ap6_retry_button", lambda: page.locator(f'[data-testid="audio-retry-{CH_GONE}"]').count() == 1)
    page.click(f'[data-testid="audio-retry-{CH_GONE}"]')
    page.wait_for_selector(f'[data-testid="audio-error-{CH_GONE}"]', timeout=8000)
    step("ap6_retry_keeps_error_state", lambda: True)

    # AP7 edited badge + history dialog
    step("ap7_badge", lambda: page.locator(f'[data-testid="note-edited-badge-{CH_EDIT}"]').count() == 1)
    page.click(f'[data-testid="note-history-{CH_EDIT}"]')
    page.wait_for_selector('[data-testid="note-history-dialog"]')
    page.wait_for_selector('[data-testid="note-history-row-0"]')
    rows = page.locator('[data-testid^="note-history-row-"]').count()
    new0 = page.locator('[data-testid="note-history-new-0"]').text_content()
    old_last = page.locator(f'[data-testid="note-history-old-{rows-1}"]').text_content()
    editor0 = page.locator('[data-testid="note-history-editor-0"]').text_content()
    step("ap7_dialog_rows", lambda: rows == 3)
    step("ap7_newest_first", lambda: "تسویه" in new0)
    step("ap7_original_at_bottom", lambda: old_last == "گزارش اولیه ویزیت")
    step("ap7_editor", lambda: editor0 == "E Ali")

    browser.close()

print(json.dumps(out, ensure_ascii=False))
PYBROWSER
  python3 "$W/p2d_browser.py" "$BASE" "$W" "$ADMIN_PASS" > "$W/ap_result.json" 2>"$W/ap_err.log"
  if [ -s "$W/ap_result.json" ]; then
    for k in ap1_playing_time_advances ap1_duration_loaded ap2_resume_from_position \
             ap2_still_playing ap3_speed_cycle ap4_stop_resets ap5_single_flight \
             ap6_error_text ap6_retry_button ap6_retry_keeps_error_state \
             ap7_badge ap7_dialog_rows ap7_newest_first ap7_original_at_bottom ap7_editor; do
      chk "AP $k" "py \"import json;d=json.load(open('$W/ap_result.json'));exit(0 if d.get('$k') is True else 1)\""
    done
    chk "AP browser zero step errors" \
      "py \"import json;d=json.load(open('$W/ap_result.json'));exit(0 if not d['errors'] else 1)\""
  else
    f "AP browser run produced no result"; cat "$W/ap_err.log" | tail -5
  fi
else
  s "AP audio UI layer — playwright not installed"
fi

# ── UP: upgrade path from the pre-package-13 database ─────────
echo "== UP: upgrade path (pre-package-13 DB) =="
OLD_DB="$W/old-template.db"
if [ -z "${P2D_OLD_DB:-}" ]; then
  ( cd "$PKG" && git show v1.9.13-dev.3:app/panel/db/template.db > "$OLD_DB" ) 2>/dev/null || true
else
  cp -f "$P2D_OLD_DB" "$OLD_DB" 2>/dev/null || true
fi
if [ -s "$OLD_DB" ]; then
  LACKS=$(py "
import sqlite3
c = sqlite3.connect('$OLD_DB')
t = c.execute(\"SELECT name FROM sqlite_master WHERE name='CheckinNoteVersion'\").fetchone()
print(1 if not t else 0)
")
  chk "UP1 old template really lacks CheckinNoteVersion" "[ '$LACKS' = '1' ]"
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
  (cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  chk "UP2 panel boots on the OLD database" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
  MIG=$(py "
import sqlite3
c = sqlite3.connect('$W/inst/panel/db/custom.db')
t = c.execute(\"SELECT name FROM sqlite_master WHERE name='CheckinNoteVersion'\").fetchone()
i = c.execute(\"SELECT name FROM sqlite_master WHERE name='CheckinNoteVersion_checkinId_idx'\").fetchone()
print(1 if (t and i) else 0)
")
  chk "UP3 migration applied table + index at boot" "[ '$MIG' = '1' ]"
  # edit works on the migrated DB
  curl -s -c $W/cj2 -X POST $BASE/api/login -H 'content-type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o /dev/null
  TOK=$(apptok e_ali P2Dnew456!)
  curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK" \
    -F "customerId=$CUS5" -F "note=گزارش پس از ارتقا" -F "lat=35.7401" -F "lng=51.4401" \
    -F "localId=p2d-lid-6" -o $W/up_ch.json
  UP_CH=$(py "import json;print(json.load(open('$W/up_ch.json'))['checkin']['id'])")
  curl -s -X POST $BASE/api/app/report-edit -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
    -d "{\"checkinId\":\"$UP_CH\",\"note\":\"گزارش پس از ارتقا — ویرایش‌شده\"}" -o $W/up_edit.json
  chk "UP4 edit works on migrated DB" "grep -q '\"versionCount\":1' $W/up_edit.json"
  curl -s -b $W/cj2 "$BASE/api/checkins/versions?id=$UP_CH" -o $W/up_vers.json
  chk "UP5 versions readable on migrated DB" \
    "py \"import json;d=json.load(open('$W/up_vers.json'));exit(0 if len(d['versions'])==1 and 'ارتقا' in d['versions'][0]['oldNote'] else 1)\""
else
  s "UP upgrade path — pre-package-13 template not available (no git tag / no P2D_OLD_DB)"
fi

echo ""
echo "=================================================="
echo "PHASE2-DAILY: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
if [ "$FAIL" = "0" ]; then echo "ALL-P2D-CHECKS-PASS"; fi
echo "=================================================="
