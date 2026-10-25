#!/usr/bin/env bash
# test-v1912.sh — MedPad v1.9.12 AUDIT test suite (phases 3-7 verification).
# Run:  bash test-v1912.sh [PKG_DIR]
# Default PKG_DIR = the REPO ROOT (since phase 0 the hardened v1.9.12 lives
# at the root; the old WIP package under medpad-full-1.9/ was removed when
# the owner reorganized the repo).
#
# Suite (all must PASS):
#  A1  compiled panel contains NO medpad-dev-token / admin123 literals
#  A2  realtime without token FAILS FAST (exit != 0, clear error)
#  A3  realtime with MEDPAD_DEV=1 starts (dev token allowed, loud warning)
#  A4  realtime reads SOCKET_TOKEN from ../panel/.env (v1.9.12 path)
#  T1  fresh non-interactive install with custom ports (3110/3113)
#  T2  medpad-config.json + helpers copied + rt-port patch + no stale 3003
#  T2W generated Windows helpers (start/stop/server-manager.bat) ASCII+CRLF
#  T3  boot: panel 200, font 200, login with chosen password 200,
#      admin123 REJECTED, mint token, /emit bad token 401,
#      socket anonymous REJECTED + authenticated ACCEPTED
#  T3R rate limiting: 5 bad logins -> 429 with retry-after
#  T4  stop -> ports really free
#  T5  update over a RUNNING install (auto-stop, db+uploads preserved)
#  T6  .env owner-only (600), db dir 700, SOCKET_TOKEN CSPRNG format
#  T7  syntax check all .mjs + realtime/index.js ESM import
#  T8  CF module: mock-server confirm flow (plan -> MEDPAD_CF_CONFIRM_REQUIRED -> apply)
#  A1W shipped install.bat/install.ps1 are pure ASCII + CRLF (rule 4 / bug #6)
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
# Absolutize: a relative PKG (e.g. ".") would break the node_modules
# symlinks below and turn A2/A3 into module-not-found false results.
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi }
W=/tmp/t1912; rm -rf $W; mkdir -p $W

[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

# ── A1: compiled panel is clean of dev fallbacks ──
# NOTE: the string 'medpad-dev-token' legitimately exists behind the
# MEDPAD_DEV=1 gate; the VULNERABLE pattern is the ungated
# `SOCKET_TOKEN||"medpad-dev-token"` fallback which must be gone.
chk "A1 no ungated dev-token fallback in compiled chunks" "! grep -rq 'SOCKET_TOKEN||\"medpad-dev-token\"' '$PKG/app/panel/.next/standalone/.next/server/chunks/' 2>/dev/null"
chk "A1 no admin123 in compiled server chunks" "! grep -rq 'admin123' '$PKG/app/panel/.next/standalone/.next/server/chunks/' 2>/dev/null"
chk "A1 rate limiting compiled in" "grep -rq 'retry-after' '$PKG/app/panel/.next/standalone/.next/server/chunks/' 2>/dev/null"

# ── A1W: Windows scripts are pure ASCII + CRLF (golden rule 4 / bug #6) ──
# A bare-LF .bat breaks in cmd.exe (labels/goto misparse) — this exact bug
# shipped once (v1.9.6 era) and silently regressed in the repo afterwards.
win_script_ok() {
  node -e '
    const d = require("fs").readFileSync(process.argv[1]);
    let bad = "";
    for (let i = 0; i < d.length; i++) {
      if (d[i] > 127) { bad = "non-ASCII byte"; break; }
      if (d[i] === 10 && (i === 0 || d[i-1] !== 13)) { bad = "bare LF (not CRLF)"; break; }
    }
    if (bad) { console.error(process.argv[1] + ": " + bad); process.exit(1); }
    process.exit(0);
  ' "$1"
}
for wf in install.bat install.ps1; do
  if [ -f "$PKG/$wf" ]; then
    chk "A1W $wf pure ASCII + CRLF" "win_script_ok '$PKG/$wf'"
  fi
done

# ── A2: realtime without token fails fast ──
mkdir -p $W/rt-noenv/panel $W/rt-noenv/realtime
cp "$PKG/app/realtime/index.js" $W/rt-noenv/realtime/
cp "$PKG/app/realtime/package.json" $W/rt-noenv/realtime/ 2>/dev/null || true
ln -sfn "$PKG/app/realtime/node_modules" $W/rt-noenv/realtime/node_modules
(cd $W/rt-noenv/realtime && env -u SOCKET_TOKEN -u MEDPAD_DEV REALTIME_PORT=31991 node index.js > $W/a2.log 2>&1)
chk "A2 realtime exits non-zero without token" "[ \$? -ne 0 ]"
chk "A2 clear FATAL message" "grep -q 'FATAL: SOCKET_TOKEN' $W/a2.log"

# ── A3: MEDPAD_DEV=1 allows the dev token (with warning) ──
(cd $W/rt-noenv/realtime && env -u SOCKET_TOKEN MEDPAD_DEV=1 REALTIME_PORT=31992 node index.js > $W/a3.log 2>&1 & echo $! > $W/a3.pid)
sleep 2
chk "A3 dev mode starts (health ok)" "curl -s http://127.0.0.1:31992/health | grep -q '\"ok\":true'"
chk "A3 loud dev warning" "grep -q 'DEV MODE' $W/a3.log"
kill $(cat $W/a3.pid) 2>/dev/null; sleep 0.5

# ── A4: token read from ../panel/.env ──
mkdir -p $W/rt-env/panel $W/rt-env/realtime
cp "$PKG/app/realtime/index.js" $W/rt-env/realtime/
ln -sfn "$PKG/app/realtime/node_modules" $W/rt-env/realtime/node_modules
printf 'SOCKET_TOKEN=envfile-secret-123\n' > $W/rt-env/panel/.env
(cd $W/rt-env/realtime && env -u SOCKET_TOKEN REALTIME_PORT=31993 node index.js > $W/a4.log 2>&1 & echo $! > $W/a4.pid)
sleep 2
chk "A4 realtime up via .env token" "curl -s http://127.0.0.1:31993/health | grep -q '\"ok\":true'"
chk "A4 /emit with .env token 200" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:31993/emit -H 'content-type: application/json' -d '{\"token\":\"envfile-secret-123\",\"event\":\"t\"}')\" = 200 ]"
chk "A4 /emit wrong token 401" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:31993/emit -H 'content-type: application/json' -d '{\"token\":\"wrong\",\"event\":\"t\"}')\" = 401 ]"
kill $(cat $W/a4.pid) 2>/dev/null; sleep 0.5

# ── T7 (early, cheap): syntax ──
for m in install.mjs services.mjs server-manager.mjs wizard.mjs tunnel.mjs; do
  chk "T7 syntax $m" "node --check '$PKG/$m'"
done

# ── T1: fresh install custom ports ──
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/t1 --port 3110 --rt-port 3113 --admin-user admin --admin-pass 'Str0ngPass311' > $W/t1.log 2>&1)
chk "T1 install exit 0" "[ \$? -eq 0 ]"
chk "T1 INSTALL COMPLETE banner" "grep -q 'INSTALL COMPLETE' $W/t1.log"
chk "T1 no [X] failures" "! grep -q '^\\[X\\]' $W/t1.log"

# ── T2: config + helpers + rt patch ──
chk "T2 medpad-config.json" "test -f $W/t1/medpad-config.json"
chk "T2 config ports+target" "python3 -c \"import json;c=json.load(open('$W/t1/medpad-config.json'));assert c['panelPort']==3110 and c['rtPort']==3113 and c['target']=='local'\""
chk "T2 helper scripts copied" "test -f $W/t1/server-manager.mjs -a -f $W/t1/wizard.mjs -a -d $W/t1/ui"
chk "T2 rt patch applied (client chunk)" "grep -rq 'location.hostname}:3113' $W/t1/panel/.next/standalone/.next/static/chunks/"
chk "T2 no stale 3003 client pattern" "! grep -rq 'location.hostname}:3003' $W/t1/panel/.next/standalone/.next/static/chunks/"
chk "T2 gateway rewrites patched" "! grep -rq 'http://127.0.0.1:3003' $W/t1/panel/.next/standalone/server.js $W/t1/panel/.next/standalone/.next/routes-manifest.json"
for wf in start.bat stop.bat server-manager.bat; do
  [ -f $W/t1/$wf ] && chk "T2W generated $wf pure ASCII + CRLF" "win_script_ok $W/t1/$wf"
done

# ── T6: permissions + token quality ──
chk "T6 .env mode 600" "[ \"\$(stat -c %a $W/t1/panel/.env)\" = 600 ]"
chk "T6 db dir mode 700" "[ \"\$(stat -c %a $W/t1/panel/db)\" = 700 ]"
chk "T6 SOCKET_TOKEN CSPRNG format (medpad- + 48 hex)" "grep -qE '^SOCKET_TOKEN=medpad-[0-9a-f]{48}$' $W/t1/panel/.env"

# ── T3: boot + security ──
(cd $W/t1 && node server-manager.mjs start --no-open > $W/t3.log 2>&1)
chk "T3 start exit 0" "[ \$? -eq 0 ]"
chk "T3 panel 200" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3110/ | grep -q 200"
chk "T3 font 200" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3110/fonts/vazirmatn.css | grep -q 200"
curl -s -c $W/cj -X POST http://127.0.0.1:3110/api/login -H 'content-type: application/json' -d '{"username":"admin","password":"Str0ngPass311"}' -o $W/login.json
chk "T3 login (chosen password) 200 ok" "grep -q '\"ok\":true' $W/login.json"
chk "T3 admin123 REJECTED" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:3110/api/login -H 'content-type: application/json' -d '{\"username\":\"admin\",\"password\":\"admin123\"}')\" != 200 ]"
chk "T3 mint socket token" "curl -s -b $W/cj http://127.0.0.1:3110/api/admin/socket-auth | grep -q '\"ok\":true'"
chk "T3 /emit bad token 401" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:3113/emit -H 'content-type: application/json' -d '{\"token\":\"x\",\"event\":\"e\"}')\" = 401 ]"

# T3R: rate limiting (use a WRONG password 6 times; expect 429 by the 6th)
CODE6=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:3110/api/login -H 'content-type: application/json' -d '{"username":"admin","password":"wrongpass1"}')
for i in 1 2 3 4 5 6; do
  CODE6=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:3110/api/login -H 'content-type: application/json' -d '{"username":"admin","password":"wrongpass1"}')
done
chk "T3R rate limit 429 after repeated failures" "[ \"$CODE6\" = 429 ]"
RA=$(curl -s -D - -o /dev/null -X POST http://127.0.0.1:3110/api/login -H 'content-type: application/json' -d '{"username":"admin","password":"x"}' | grep -i '^retry-after:' | wc -l)
chk "T3R retry-after header present" "[ \"$RA\" -ge 1 ]"

# socket: anonymous rejected, authenticated accepted
SID=$(curl -s "http://127.0.0.1:3113/socket.io/?EIO=4&transport=polling" | sed 's/.*"sid":"\([^"]*\)".*/\1/')
curl -s -X POST "http://127.0.0.1:3113/socket.io/?EIO=4&transport=polling&sid=$SID" --data-raw '40' > /dev/null
TOKEN=$(curl -s -b $W/cj http://127.0.0.1:3110/api/admin/socket-auth | sed 's/.*"token":"\([^"]*\)".*/\1/')
SID2=$(curl -s "http://127.0.0.1:3113/socket.io/?EIO=4&transport=polling" | sed 's/.*"sid":"\([^"]*\)".*/\1/')
curl -s -X POST "http://127.0.0.1:3113/socket.io/?EIO=4&transport=polling&sid=$SID2" --data-raw "40{\"token\":\"$TOKEN\"}" > /dev/null
sleep 1
chk "T3 socket: anonymous rejected + auth accepted (rt log)" "grep -q 'rejected unauthenticated' $W/t1/logs/realtime.log && grep -q 'authenticated panel connected' $W/t1/logs/realtime.log"

# ── T4: stop ──
(cd $W/t1 && node server-manager.mjs stop > $W/t4.log 2>&1)
chk "T4 stop exit 0" "[ \$? -eq 0 ]"
sleep 1
chk "T4 ports free" "! ss -tln 2>/dev/null | grep -qE ':3110 |:3113 '"

# ── T5: update over a running install ──
(cd $W/t1 && node server-manager.mjs start --no-open > $W/t5-boot.log 2>&1)
mkdir -p $W/t1/panel/uploads/voice && echo 'KEEP-ME-3112' > $W/t1/panel/uploads/voice/marker.txt
python3 -c "
import sqlite3; c=sqlite3.connect('$W/t1/panel/db/custom.db')
c.execute(\"INSERT OR REPLACE INTO Config(key,value) VALUES('auditMarker','KEEP-DB-3112')\"); c.commit()"
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/t1 --port 3110 --rt-port 3113 > $W/t5.log 2>&1)
chk "T5 update exit 0" "[ \$? -eq 0 ]"
chk "T5 uploads marker kept" "grep -q 'KEEP-ME-3112' $W/t1/panel/uploads/voice/marker.txt"
chk "T5 db marker kept" "python3 -c \"import sqlite3;c=sqlite3.connect('$W/t1/panel/db/custom.db');assert c.execute(\\\"SELECT value FROM Config WHERE key='auditMarker'\\\").fetchone()[0]=='KEEP-DB-3112'\""
chk "T5 .env still 600" "[ \"\$(stat -c %a $W/t1/panel/.env)\" = 600 ]"
(cd $W/t1 && node server-manager.mjs stop > /dev/null 2>&1)

# ── T8: CF confirm flow against a mock API ──
node $(dirname "$0")/test-cf-mock.mjs "$PKG" > $W/t8.log 2>&1
chk "T8 CF mock flow PASS" "grep -q 'CF-MOCK-ALL-PASS' $W/t8.log"

echo ""
echo "=============================================="
echo "  RESULT: $PASS PASS / $FAIL FAIL"
echo "=============================================="
[ $FAIL -eq 0 ]
