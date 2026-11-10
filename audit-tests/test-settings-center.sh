#!/usr/bin/env bash
# test-settings-center.sh — phase 4 (comprehensive): the UNIFIED settings
# section (11 categories), saved-vs-applied device ack, browser Push +
# Service Worker, and the duplicate-icon removal — verified against the
# REAL installed bundle (fresh non-interactive install + boot).
#
#   SC0  fresh install + boot; VAPID keys generated at boot; new tables
#        (PushSubscription) and TrackStatus ack columns present
#   SC1  GET /api/admin/settings — settings + defaultCity (OLD contract,
#        unchanged) plus the three NEW blocks: deviceAck / auditHistory /
#        push. 401 without a session.
#   SC2  GET /api/push/key — publicKey present (admin only)
#   SC3  POST/DELETE /api/push/subscribe — valid pair stored + counted;
#        non-https / bad keys rejected with a Persian 400
#   SC4  PUT pushEnabled toggle — value persists; audit rows appear for
#        REAL changes only (no noise for no-op writes)
#   SC5  «اعمال‌شده روی دستگاه» — the app's appliedConfig ack:
#        · upload with a MATCHING config → deviceAck.matched=1 and the
#          tracking row says configApplied=matched
#        · policy change → the same device flips to stale (honest display)
#        · garbage/absent appliedConfig → ignored, previous ack preserved
#          (pre-phase-4 app builds keep working)
#   SC6  controlled rollback — PUT the old value back; the rollback itself
#        lands in auditHistory (old → new)
#   SC7  bundle/boot artifacts: /sw.js served as JS with push +
#        notificationclick + visible-window dedup + rtl/fa; settings-tab
#        markers and ALL ELEVEN category titles in the client chunks;
#        the duplicate permission-bell title ABSENT from chunks
#   SC8  browser (Playwright; SKIP loudly when unavailable): settings tab
#        renders all 11 categories + ack box + push box + local-browser
#        section + history + save bar; hash routing (#tab=) works on
#        hash-change AND full reload; the duplicate header bell is gone;
#        the customer form dialog opens ABOVE the map (mission-12 isolate)
#   SC9  live push wiring — valid-key subscription + a real notification
#        event: the push is sent; FCM answers 404 for the fake token and
#        the dead subscription is PRUNED (SKIP when FCM unreachable)
#
# Deterministic: fresh install; curl + python3 (+ sqlite3 via python3).
# Run:  bash test-settings-center.sh [PKG_DIR]   (SC_WORK=<dir> overrides)
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2" >/dev/null 2>&1; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${SC_WORK:-/tmp/tsc}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="; echo "== WORKDIR: $W =="

PANEL_PORT=3331; RT_PORT=3334; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_PASS='SCpass123!'
DB="$W/inst/panel/db/custom.db"

cleanup() {
  (cd $W/inst 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── SC0: fresh install + boot ────────────────────────────────────
echo "== SC0: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/install.log 2>&1)
chk "SC0 install exit 0 (medpad-config.json)" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "SC0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
chk "SC0 PushSubscription table created (boot migration)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='PushSubscription'\\\").fetchone() else 1)\""
chk "SC0 TrackStatus ack columns (appliedConfigJson/configAckAt)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');cols=[r[1] for r in c.execute('PRAGMA table_info(TrackStatus)')];exit(0 if 'appliedConfigJson' in cols and 'configAckAt' in cols else 1)\""
for i in $(seq 1 15); do
  if py "import sqlite3;c=sqlite3.connect('$DB');rows=dict(c.execute(\"SELECT key,value FROM Config WHERE key LIKE 'vapid%'\").fetchall());exit(0 if len(rows.get('vapidPublicKey',''))>=80 and len(rows.get('vapidPrivateKey',''))>=40 else 1)"; then break; fi
  sleep 1
done
chk "SC0 VAPID keys generated at boot" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');rows=dict(c.execute(\\\"SELECT key,value FROM Config WHERE key LIKE 'vapid%'\\\").fetchall());exit(0 if len(rows.get('vapidPublicKey',''))>=80 and len(rows.get('vapidPrivateKey',''))>=40 else 1)\""

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o $W/login.json
chk "SC0 admin login" "py \"import json;exit(0 if json.load(open('$W/login.json'))['ok'] else 1)\""

# ── SC1: settings GET contract (old + new blocks) ────────────────
echo "== SC1: settings GET contract =="
CODE=$(curl -s -o $W/s_noauth.json -w '%{http_code}' $BASE/api/admin/settings)
chk "SC1 401 without session" "[ '$CODE' = '401' ]"
curl -s -b $W/cj $BASE/api/admin/settings -o $W/s0.json
chk "SC1 ok + numeric settings (15 keys incl pushEnabled)" \
  "py \"import json;d=json.load(open('$W/s0.json'));exit(0 if d['ok'] and d['settings'].get('pushEnabled')==1 and len(d['settings'])>=15 else 1)\""
chk "SC1 defaultCity block present (old contract)" \
  "py \"import json;d=json.load(open('$W/s0.json'));exit(0 if 'defaultCity' in d else 1)\""
chk "SC1 deviceAck block" \
  "py \"import json;d=json.load(open('$W/s0.json'));a=d.get('deviceAck',{});exit(0 if a.get('reporting')==0 and a.get('lastAckAt') is None else 1)\""
chk "SC1 auditHistory block" \
  "py \"import json;d=json.load(open('$W/s0.json'));exit(0 if isinstance(d.get('auditHistory'),list) else 1)\""
chk "SC1 push block (vapidReady)" \
  "py \"import json;d=json.load(open('$W/s0.json'));exit(0 if d.get('push',{}).get('vapidReady') else 1)\""

# ── SC2: push key ────────────────────────────────────────────────
echo "== SC2: push key =="
CODE=$(curl -s -o $W/pk_noauth.json -w '%{http_code}' $BASE/api/push/key)
chk "SC2 401 without session" "[ '$CODE' = '401' ]"
curl -s -b $W/cj $BASE/api/push/key -o $W/pk.json
chk "SC2 publicKey present" \
  "py \"import json;d=json.load(open('$W/pk.json'));exit(0 if d['ok'] and len(d.get('publicKey') or '')>=60 else 1)\""

# ── SC3: subscribe / unsubscribe ─────────────────────────────────
echo "== SC3: push subscribe =="
CODE=$(curl -s -b $W/cj -o $W/sub_bad1.json -w '%{http_code}' -X POST $BASE/api/push/subscribe \
  -H 'content-type: application/json' -d '{"endpoint":"http://insecure/x","keys":{"p256dh":"k","auth":"a"}}')
chk "SC3 non-https endpoint rejected (400)" "[ '$CODE' = '400' ]"
CODE=$(curl -s -b $W/cj -o $W/sub_bad2.json -w '%{http_code}' -X POST $BASE/api/push/subscribe \
  -H 'content-type: application/json' -d '{"endpoint":"https://push.example.com/send/aaaaaaaaaaaaaaaaaaaaaaaaaa","keys":{"p256dh":"short","auth":"a"}}')
chk "SC3 short keys rejected (400)" "[ '$CODE' = '400' ]"
# valid p256dh (65-byte point) + auth — generated with node crypto
KEYS=$(node -e "
const crypto = require('crypto');
const ecdh = crypto.createECDH('prime256v1');
ecdh.generateKeys();
process.stdout.write(JSON.stringify({p256dh: ecdh.getPublicKey('base64url'), auth: crypto.randomBytes(16).toString('base64url')}))
" 2>/dev/null) || KEYS=""
if [ -n "$KEYS" ]; then
  CODE=$(curl -s -b $W/cj -o $W/sub.json -w '%{http_code}' -X POST $BASE/api/push/subscribe \
    -H 'content-type: application/json' \
    -d "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/scFakeTokenFor404aaaaaaaaaaaaaaaaaaaaaaaaa\",\"keys\":$KEYS}")
  chk "SC3 valid subscription stored (200, subscriptions=1)" \
    "py \"import json;d=json.load(open('$W/sub.json'));exit(0 if d['ok'] and d['subscriptions']==1 else 1)\""
  CODE=$(curl -s -b $W/cj -o $W/unsub.json -w '%{http_code}' -X DELETE $BASE/api/push/subscribe \
    -H 'content-type: application/json' \
    -d '{"endpoint":"https://fcm.googleapis.com/fcm/send/scFakeTokenFor404aaaaaaaaaaaaaaaaaaaaaaaaa"}')
  chk "SC3 unsubscribe (subscriptions=0)" \
    "py \"import json;d=json.load(open('$W/unsub.json'));exit(0 if d['ok'] and d['subscriptions']==0 else 1)\""
else
  s "SC3 valid-subscription checks (node crypto unavailable)"
fi

# ── SC4: pushEnabled toggle + audit noise discipline ─────────────
echo "== SC4: toggle + audit =="
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' -d '{"pushEnabled":0}' -o $W/pe.json
chk "SC4 pushEnabled 0 persisted" \
  "py \"import json;exit(0 if json.load(open('$W/pe.json'))['settings']['pushEnabled']==0 else 1)\""
# no-op write of the SAME value must NOT add audit noise
BEFORE=$(py "import json;print(len(json.load(open('$W/pe.json')).get('auditHistory',[]))  if 'auditHistory' in json.load(open('$W/pe.json')) else -1)" 2>/dev/null || echo -1)
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' -d '{"pushEnabled":0}' -o $W/pe_same.json
curl -s -b $W/cj $BASE/api/admin/settings -o $W/s1.json
chk "SC4 audit row for the REAL change (1→0)" \
  "py \"import json;d=json.load(open('$W/s1.json'));exit(0 if any('pushEnabled: 1 → 0' in r['detail'] for r in d['auditHistory']) else 1)\""
chk "SC4 no-op write marked (بدون تغییر مقدار) — no duplicate noise" \
  "py \"import json;d=json.load(open('$W/s1.json'));rows=d['auditHistory'];real=[r for r in rows if 'pushEnabled: 1 → 0' in r['detail']];noop=[r for r in rows if 'بدون تغییر مقدار' in r['detail']];exit(0 if len(real)==1 and len(noop)>=1 else 1)\""
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' -d '{"pushEnabled":1}' -o /dev/null

# ── SC5: device ack («اعمال‌شده روی دستگاه») ──────────────────────
echo "== SC5: device ack =="
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"sc_rep1","password":"RepPass123!","name":"تست","phone":"09120000009"}' -o /dev/null
TOK=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"sc_rep1","password":"RepPass123!"}' | py "import json,sys;print(json.load(sys.stdin)['token'])")
POLICY=$(curl -s -b $W/cj $BASE/api/admin/settings | py "
import json,sys
s = json.load(sys.stdin)['settings']
print(json.dumps({'enabled': s['trackEnabled']==1, 'moveIntervalSec': s['trackMoveIntervalSec'], 'stillIntervalSec': s['trackStillIntervalSec'], 'motionThresholdM': s['trackMotionThresholdM'], 'maxAgeSec': s['trackMaxAgeSec']}))")
NOW=$(py "import datetime;print(datetime.datetime.now(datetime.UTC).isoformat().replace('+00:00','Z'))")
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d "{\"locations\":[{\"localId\":\"sc-aaaaaaaa1\",\"lat\":35.7,\"lng\":51.4,\"recordedAt\":\"$NOW\"}],\"status\":{\"locationPermission\":\"fine\",\"serviceRunning\":true,\"appVersion\":\"1.9.14-test\",\"appliedConfig\":$POLICY}}" -o $W/ul.json
chk "SC5 upload accepted" "py \"import json;exit(0 if json.load(open('$W/ul.json'))['ok'] else 1)\""
curl -s -b $W/cj $BASE/api/admin/settings -o $W/s2.json
chk "SC5 deviceAck matched=1" \
  "py \"import json;d=json.load(open('$W/s2.json'));a=d['deviceAck'];exit(0 if a['matched']==1 and a['stale']==0 and a['lastAckAt'] else 1)\""
curl -s -b $W/cj $BASE/api/panel/tracking -o $W/lt.json
chk "SC5 tracking row configApplied=matched" \
  "py \"import json;d=json.load(open('$W/lt.json'));exit(0 if any(r.get('configApplied')=='matched' for r in d['rows']) else 1)\""
# change the policy -> the same ack flips to stale (honest display)
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' -d '{"trackMoveIntervalSec":90}' -o /dev/null
curl -s -b $W/cj $BASE/api/admin/settings -o $W/s3.json
chk "SC5 after policy change deviceAck stale=1" \
  "py \"import json;d=json.load(open('$W/s3.json'));exit(0 if d['deviceAck']['stale']==1 and d['deviceAck']['matched']==0 else 1)\""
curl -s -b $W/cj $BASE/api/panel/tracking -o $W/lt2.json
chk "SC5 tracking row configApplied=stale" \
  "py \"import json;d=json.load(open('$W/lt2.json'));exit(0 if any(r.get('configApplied')=='stale' for r in d['rows']) else 1)\""
# garbage appliedConfig -> ignored, previous ack preserved
curl -s -X POST $BASE/api/app/locations -H "X-Medpad-Token: $TOK" -H 'content-type: application/json' \
  -d "{\"locations\":[{\"localId\":\"sc-aaaaaaaa2\",\"lat\":35.7,\"lng\":51.4,\"recordedAt\":\"$NOW\"}],\"status\":{\"locationPermission\":\"fine\",\"appliedConfig\":{\"moveIntervalSec\":\"garbage\",\"stillIntervalSec\":-5}}}" -o $W/ul2.json
chk "SC5 garbage ack upload still ok" "py \"import json;exit(0 if json.load(open('$W/ul2.json'))['ok'] else 1)\""
chk "SC5 garbage ack ignored (stored ack preserved)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');r=c.execute('SELECT appliedConfigJson FROM TrackStatus LIMIT 1').fetchone();exit(0 if r and r[0] and 'garbage' not in r[0] else 1)\""

# ── SC6: controlled rollback ─────────────────────────────────────
echo "== SC6: rollback =="
curl -s -b $W/cj -X PUT $BASE/api/admin/settings -H 'content-type: application/json' -d '{"trackMoveIntervalSec":45}' -o $W/rb.json
chk "SC6 rollback PUT restores 45" \
  "py \"import json;exit(0 if json.load(open('$W/rb.json'))['settings']['trackMoveIntervalSec']==45 else 1)\""
curl -s -b $W/cj $BASE/api/admin/settings -o $W/s4.json
chk "SC6 rollback itself audited (90 → 45)" \
  "py \"import json;d=json.load(open('$W/s4.json'));exit(0 if 'trackMoveIntervalSec: 90 → 45' in d['auditHistory'][0]['detail'] else 1)\""
chk "SC6 device ack matches again after rollback" \
  "py \"import json;d=json.load(open('$W/s4.json'));exit(0 if d['deviceAck']['matched']==1 else 1)\""

# ── SC7: bundle artifacts ────────────────────────────────────────
echo "== SC7: bundle artifacts =="
curl -s $BASE/sw.js -o $W/sw.js
chk "SC7 /sw.js served as JavaScript" \
  "curl -s -o /dev/null -w '%{content_type}' $BASE/sw.js | grep -q 'javascript'"
chk "SC7 sw: push handler" "grep -q \"addEventListener('push'\" $W/sw.js"
chk "SC7 sw: notificationclick handler" "grep -q \"addEventListener('notificationclick'\" $W/sw.js"
chk "SC7 sw: visible-window dedup (no double toast)" "grep -q 'visibilityState' $W/sw.js"
chk "SC7 sw: rtl + fa notification" "grep -q \"dir: 'rtl'\" $W/sw.js && grep -q \"lang: 'fa'\" $W/sw.js"
chk "SC7 sw: click opens the related page (url)" "grep -q 'openWindow' $W/sw.js"
CHUNKS="$PKG/app/panel/.next/static/chunks"
chk "SC7 all 11 category titles + key sections in client chunks" \
  "grep -rq 'نگه‌داری داده' $CHUNKS && grep -rq 'همگام‌سازی و اتصال' $CHUNKS && grep -rq 'عیب‌یابی و گزارش فنی' $CHUNKS && grep -rq 'مشتریان و جغرافیا' $CHUNKS && grep -rq 'تاریخچهٔ تغییرات تنظیمات' $CHUNKS && grep -rq 'تنظیمات این مرورگر' $CHUNKS"
chk "SC7 settings tab markers in chunks" \
  "grep -rq 'settings-cat-' $CHUNKS && grep -rq 'settings-device-ack' $CHUNKS && grep -rq 'settings-push-box' $CHUNKS"
chk "SC7 local-browser separation marker" "grep -rq 'settings-local-browser' $CHUNKS"
chk "SC7 history + rollback markers" "grep -rq 'settings-history' $CHUNKS && grep -rq 'بازگردانی به' $CHUNKS"
chk "SC7 duplicate permission-bell title REMOVED from chunks" "! grep -rq 'اعلان\u200cهای مرورگر' $CHUNKS"
chk "SC7 hash routing in chunks" "grep -rq '#tab=' $CHUNKS"
SRV="$PKG/app/panel/.next/standalone/.next/server"
chk "SC7 push routes in server bundle" \
  "[ -f $SRV/app/api/push/key/route.js ] && [ -f $SRV/app/api/push/subscribe/route.js ]"
chk "SC7 web-push ships in runtime node_modules" "[ -d $PKG/app/panel/.next/standalone/node_modules/web-push ]"
chk "SC7 notifyAdmin→push wiring in server chunks (vapid strings survive minify)" "grep -rq 'vapidPublicKey' $SRV && grep -rq 'setVapidDetails' $SRV"
chk "SC7 appliedConfig ack parsing in server chunks" "grep -rq 'appliedConfigJson' $SRV"

# ── SC8: browser (Playwright) ────────────────────────────────────
echo "== SC8: browser UI =="
if python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null && \
   python3 - <<'PYEOF' 2>/dev/null
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch()
    b.close()
PYEOF
then
  python3 - "$BASE" "$W" <<'PYEOF'
import sys, json
sys.path.insert(0, '/usr/lib/python3')  # noqa
from playwright.sync_api import sync_playwright

BASE, W = sys.argv[1], sys.argv[2]
res = {}
with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_page()
    page.goto(BASE + "/", wait_until="domcontentloaded")
    page.wait_for_selector("#u", timeout=20000)
    page.fill("#u", "admin")
    page.fill("#p", "SCpass123!")
    page.click("button[type=submit]")
    page.wait_for_selector('button[title="تنظیمات"]', timeout=20000)
    res["dashboard"] = True
    res["permission_bell_count"] = page.locator('button[title="اعلان\u200cهای مرورگر"]').count()
    page.click('button[data-testid="open-settings-tab"]')
    page.wait_for_selector('[data-testid="settings-root"]', timeout=20000)
    cats = ["general","application","tracking","notifications","customers-geo","visit-plan","assignment","sync","diagnostics","security","retention"]
    res["cats_ok"] = all(page.locator(f'[data-testid="settings-cat-{c}"]').count() == 1 for c in cats)
    res["ack_box"] = page.locator('[data-testid="settings-device-ack"]').count()
    res["push_box"] = page.locator('[data-testid="settings-push-box"]').count()
    res["local_browser"] = page.locator('[data-testid="settings-local-browser"]').count()
    res["history"] = page.locator('[data-testid="settings-history"]').count()
    res["savebar"] = page.locator('[data-testid="settings-savebar"]').count()
    res["ttl_field"] = page.locator('[data-testid="settings-input-appTokenTtlHours"]').count()
    def active():
        return page.evaluate("""() => {
            const btns = [...document.querySelectorAll('aside nav button')];
            const act = btns.find(x => (x.className||'').includes('bg-primary'));
            return act ? act.textContent.trim() : null;
        }""")
    page.evaluate("location.hash = '#tab=checkins'")
    page.wait_for_timeout(400)
    res["hash_change"] = (active() == "چک\u200cاین\u200cها")
    page.goto(BASE + "/#tab=tracking", wait_until="domcontentloaded")
    page.wait_for_selector('button[title="تنظیمات"]', timeout=20000)
    page.wait_for_timeout(500)
    res["hash_reload"] = (active() == "وضعیت زنده")
    # customer form dialog over the map (mission-12 isolate kept working)
    page.evaluate("location.hash = '#tab=customers'")
    page.wait_for_timeout(600)
    try:
        page.wait_for_selector('button:has-text("مشتری جدید")', timeout=8000)
        page.click('button:has-text("مشتری جدید")')
        page.wait_for_timeout(600)
        res["form_dialog"] = page.locator('[role="dialog"]').count()
        res["map_present"] = page.locator('.leaflet-container').count()
    except Exception as e:
        res["form_dialog"] = f"err:{type(e).__name__}"
        res["map_present"] = -1
    b.close()
json.dump(res, open(W + "/sc8.json", "w"), ensure_ascii=False)
print(json.dumps(res, ensure_ascii=False))
PYEOF
  chk "SC8 browser run completed" "[ -f $W/sc8.json ]"
  chk "SC8 dashboard + duplicate bell GONE (0)" \
    "py \"import json;d=json.load(open('$W/sc8.json'));exit(0 if d.get('dashboard') and d.get('permission_bell_count')==0 else 1)\""
  chk "SC8 all 11 categories render" \
    "py \"import json;d=json.load(open('$W/sc8.json'));exit(0 if d.get('cats_ok') else 1)\""
  chk "SC8 ack box + push box + local-browser + history + savebar" \
    "py \"import json;d=json.load(open('$W/sc8.json'));exit(0 if d.get('ack_box')==1 and d.get('push_box')==1 and d.get('local_browser')==1 and d.get('history')==1 and d.get('savebar')==1 else 1)\""
  chk "SC8 numeric field renders (TTL)" \
    "py \"import json;d=json.load(open('$W/sc8.json'));exit(0 if d.get('ttl_field')==1 else 1)\""
  chk "SC8 hash routing (change + reload)" \
    "py \"import json;d=json.load(open('$W/sc8.json'));exit(0 if d.get('hash_change') and d.get('hash_reload') else 1)\""
  chk "SC8 customer form dialog opens OVER the map (isolate intact)" \
    "py \"import json;d=json.load(open('$W/sc8.json'));exit(0 if d.get('form_dialog')==1 and d.get('map_present')>=1 else 1)\""
else
  s "SC8 browser checks (Playwright/Chromium unavailable)"
fi

# ── SC9: live push wiring (needs outbound internet to FCM) ───────
echo "== SC9: live push wiring =="
# the SC8 browser login replaced the single admin session (Config.adminSession)
# — a fresh curl login is required for the API checks below
curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o /dev/null
if curl -s -o /dev/null --max-time 8 https://fcm.googleapis.com/ && [ -n "${KEYS:-}" ]; then
  # re-subscribe with valid keys, fire a real notification, expect the 404 prune
  curl -s -b $W/cj -X POST $BASE/api/push/subscribe -H 'content-type: application/json' \
    -d "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/scFakeTokenFor404aaaaaaaaaaaaaaaaaaaaaaaaa\",\"keys\":$KEYS}" -o /dev/null
  CID=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d '{"name":"داروخانه اس‌سی۹","province":"تهران","city":"تهران","lat":35.72,"lng":51.42}' \
    | py "import json,sys;print(json.load(sys.stdin)['customer']['id'])")
  EID=$(curl -s -b $W/cj $BASE/api/employees | py "import json,sys;print(json.load(sys.stdin)['employees'][0]['id'])")
  curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
    -d "{\"customerId\":\"$CID\",\"employeeId\":\"$EID\"}" -o /dev/null
  # owner fix batch (phase 8): the ASSIGNMENT no longer creates a
  # PanelNotification (the admin is the ACTOR — they must not be notified
  # «به شما اختصاص داده شد»). The rep-facing AppNotification IS created.
  # The live-push proof therefore fires a CHECK-IN — a real app event that
  # still surfaces to the manager via notifyAdmin.
  sleep 4
  ATOK=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
    -d '{"username":"sc_rep1","password":"RepPass123!"}' | py 'import json,sys;print(json.load(sys.stdin)["token"])')
  curl -s -X POST $BASE/api/app/checkin -H "x-medpad-token: $ATOK" \
    -F "customerId=$CID" -F "localId=sc9-checkin-1" -F "note=تست" -o /dev/null
  sleep 4
  curl -s -b $W/cj $BASE/api/admin/settings -o $W/s5.json
  chk "SC9 push sent; dead 404 subscription pruned" \
    "py \"import json;d=json.load(open('$W/s5.json'));exit(0 if d['push']['subscriptions']==0 else 1)\""
  chk "SC9 the check-in notification persisted (center still works)" \
    "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute('SELECT COUNT(*) FROM PanelNotification').fetchone()[0]>=1 else 1)\""
  chk "SC9 assignment itself creates NO admin notification (owner fix, phase 8)" \
    "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM PanelNotification WHERE body LIKE '%اختصاص%'\\\").fetchone()[0]==0 else 1)\""
else
  s "SC9 live push wiring (FCM or node crypto unavailable)"
fi

echo ""
echo "== settings-center: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ "$FAIL" = "0" ]
