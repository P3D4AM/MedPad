#!/usr/bin/env bash
# test-panel-notifications-api.sh — package 5 / phase 5-B:
# the panel notification API CONTRACT — list / counters / read-marking /
# access policy — against a REAL installed panel.
#
# Phase 5-A findings this suite locks in (WORKLOG Task ID: 36):
#   (1) `unread` counted only the last-100 window (148 unread -> said 100);
#       5-B adds the whole-table `totalUnread` (ADDITIVE — `unread` semantics
#       stay untouched so every package-4 consumer keeps working).
#   (2) `ids: []`, `ids: "str"`, `ids: [1,2]` and a broken-JSON body all
#       fell through to mark-ALL — one malformed UI request could silently
#       wipe the read state of the whole table. 5-B makes them explicit
#       400s that mark NOTHING (tightened BEFORE the first consumer — the
#       5-C bell — exists).
#
# Checks:
#   PN0    fresh install + boot + fixtures (3 reps, 50 customers, app token)
#   PN0.5  contract probe: does the bundle carry the 5-B additions?
#          -> MODE=full (5-B contract) | MODE=baseline (pre-5-B bundle:
#          the 5-B-only checks are SKIPped with a loud note — running this
#          suite against the repo BEFORE the 5-D bundle rebuild is expected
#          to land in baseline mode; the package-4 baseline still must pass)
#   PN1    empty state: ok:true, notifications=[], unread=0 (+totalUnread=0)
#   PN2    real events (2 assignments + 1 check-in) -> 3 rows, both counters
#          agree below the window; localId REPLAY adds no duplicate (eventKey)
#   PN3    mark-ONE with a real id -> readAt set, counters drop
#   PN4    mark-ONE with an unknown id -> 200, idempotent no-op
#   PN5    mark-ALL ({}) -> 200, unread=0 (+totalUnread=0), all rows read
#   PN6    401 gates: no cookie, garbage cookie, and a VALID APP TOKEN (the
#          reps' tracker token must NEVER read/mark the panel's notifications)
#   PN7    [full] malformed ids hardening: [] / "str" / [1,2] / broken JSON /
#          array body -> 400 each AND the counters are untouched after all
#   PN8    [full] ids cap: 1001 ids -> 400; 1000 well-formed ids -> 200 no-op
#   PN9    [full] window vs whole-table: 148 fresh real events -> list=100,
#          unread=100 (window), totalUnread=148 (truth); mark-one -> 99/147;
#          mark-all -> 0/0; DB proof with the server stopped (0 unread rows)
#   PN10   persistence: restart -> list/counters survive (unread=0, total=0)
#
# Deterministic: fresh non-interactive install; curl + python3 only.
# Run:  bash test-panel-notifications-api.sh [PKG_DIR]
#       P5API_WORK=<dir> overrides the work directory.
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2"; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${P5API_WORK:-/tmp/medpad-p5api}"
rm -rf "$W"; mkdir -p "$W"
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="
echo "== WORKDIR: $W =="

PANEL_PORT=3273; RT_PORT=3276; BASE="http://127.0.0.1:$PANEL_PORT"
ADMIN_USER="admin"; ADMIN_PASS='P5Bpass123!'
DB="$W/inst/panel/db/custom.db"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

panellist() { curl -s -b "$W/cj" "$BASE/api/panel-notifications" -o "$1"; }
unread_of()  { py "import json;print(json.load(open('$1'))['unread'])"; }
total_of()   { py "import json;print(json.load(open('$1'))['totalUnread'])"; }

# ── PN0: install + boot + fixtures ───────────────────────────
echo "== PN0: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir "$W/inst" \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user "$ADMIN_USER" --admin-pass "$ADMIN_PASS" > "$W/install.log" 2>&1)
chk "PN0 install exit 0 (medpad-config.json)" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f "$W/inst/medpad-config.json" ]; then
  echo "install failed — aborting"; tail -20 "$W/install.log"; exit 1
fi

(cd "$W/inst" && node server-manager.mjs start --no-open > "$W/boot.log" 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "PN0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

curl -s -c "$W/cj" -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" -o "$W/admin-login.json"
chk "PN0 admin login" "grep -q '\"ok\":true' $W/admin-login.json"

E1=""; E2=""; E3=""
for i in 1 2 3; do
  curl -s -b "$W/cj" -X POST $BASE/api/employees -H 'content-type: application/json' \
    -d "{\"username\":\"p5b_e$i\",\"password\":\"e${i}pass123\",\"name\":\"P5B Emp $i\",\"phone\":\"55$i\"}" -o "$W/emp$i.json"
done
E1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
E2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
E3=$(py "import json;print(json.load(open('$W/emp3.json'))['employee']['id'])")
chk "PN0 employees created" "[ -n '$E1' ] && [ -n '$E2' ] && [ -n '$E3' ]"

CUS_IDS=""
for i in $(seq 1 50); do
  curl -s -b "$W/cj" -X POST $BASE/api/customers -H 'content-type: application/json' \
    -d "{\"name\":\"P5B Customer $i\",\"lat\":35.70,\"lng\":51.40,\"type\":\"clinic\"}" -o "$W/cus$i.json"
  CID=$(py "import json;print(json.load(open('$W/cus$i.json'))['customer']['id'])" 2>/dev/null)
  CUS_IDS="$CUS_IDS $CID"
done
N_CUS=$(echo $CUS_IDS | wc -w)
chk "PN0 50 customers created" "[ '$N_CUS' = '50' ]"

curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"p5b_e1","password":"e1pass123"}' -o "$W/app1.json"
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
chk "PN0 app login (EMP1 tracker token)" "[ -n '$TOK1' ]"

# ── PN0.5: contract probe (5-B additions present?) ───────────
echo "== PN0.5: contract probe =="
panellist "$W/probe.json"
if py "import json;exit(0 if 'totalUnread' in json.load(open('$W/probe.json')) else 1)" 2>/dev/null; then
  MODE=full
  t "PN0.5 bundle carries the 5-B contract (totalUnread present) — MODE=full"
else
  MODE=baseline
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  echo "!! PRE-5-B BUNDLE DETECTED: no totalUnread in the API response."
  echo "!! The 5-B-only checks (PN7/PN8/PN9 + totalUnread assertions) are"
  echo "!! SKIPped. Baseline (package-4) checks still must all pass. This is"
  echo "!! the expected result when running against the repo BEFORE the 5-D"
  echo "!! bundle rebuild — NOT a test failure by itself."
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  s "PN0.5 5-B contract absent — MODE=baseline (5-B checks skip)"
fi

# ── PN1: empty state ─────────────────────────────────────────
echo "== PN1: empty state =="
panellist "$W/p1.json"
chk "PN1 ok:true" "grep -q '\"ok\":true' $W/p1.json"
chk "PN1 empty list" "py \"import json;exit(0 if json.load(open('$W/p1.json'))['notifications']==[] else 1)\""
chk "PN1 unread == 0" "[ \"\$(unread_of $W/p1.json)\" = '0' ]"
if [ "$MODE" = full ]; then
  chk "PN1 totalUnread == 0" "[ \"\$(total_of $W/p1.json)\" = '0' ]"
fi

# ── PN2: real events + replay dedup ──────────────────────────
echo "== PN2: real events (assignment x2 + check-in) =="
C1=$(echo $CUS_IDS | cut -d' ' -f1); C2=$(echo $CUS_IDS | cut -d' ' -f2)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$E1\",\"customerId\":\"$C1\"}")
chk "PN2 assignment 1 -> 200" "[ '$CODE' = '200' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$E1\",\"customerId\":\"$C2\"}")
chk "PN2 assignment 2 -> 200" "[ '$CODE' = '200' ]"

LOCAL_ID="p5b-ci-$(date +%s)"
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$C1" -F "note=p5b checkin" -F "localId=$LOCAL_ID" \
  -F "lat=35.7001" -F "lng=51.4001" -o "$W/ci.json"
chk "PN2 check-in accepted" "grep -q '\"ok\":true' $W/ci.json"

# replay the SAME localId -> duplicate check-in, must NOT notify again
curl -s -X POST $BASE/api/app/checkin -H "X-Medpad-Token: $TOK1" \
  -F "customerId=$C1" -F "note=p5b checkin" -F "localId=$LOCAL_ID" \
  -F "lat=35.7001" -F "lng=51.4001" -o "$W/ci2.json"
chk "PN2 localId replay is a duplicate" "grep -q '\"duplicate\":true' $W/ci2.json"

panellist "$W/p2.json"
chk "PN2 list has >=3 notifications" "py \"import json;exit(0 if len(json.load(open('$W/p2.json'))['notifications'])>=3 else 1)\""
chk "PN2 unread == 3" "[ \"\$(unread_of $W/p2.json)\" = '3' ]"
chk "PN2 checkin replay added NO second notification" "py \"import json;d=json.load(open('$W/p2.json'));exit(0 if sum(1 for n in d['notifications'] if n['kind']=='checkin')==1 else 1)\""
if [ "$MODE" = full ]; then
  chk "PN2 totalUnread == 3 (agrees with window below 100)" "[ \"\$(total_of $W/p2.json)\" = '3' ]"
fi

# ── PN3: mark-ONE with a real id ─────────────────────────────
echo "== PN3: mark-one =="
NID=$(py "import json;print(json.load(open('$W/p2.json'))['notifications'][0]['id'])")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d "{\"ids\":[\"$NID\"]}")
chk "PN3 mark ONE read -> 200" "[ '$CODE' = '200' ]"
panellist "$W/p3.json"
chk "PN3 unread decreased to 2" "[ \"\$(unread_of $W/p3.json)\" = '2' ]"
chk "PN3 that row now has readAt" "py \"import json;d=json.load(open('$W/p3.json'));exit(0 if [n for n in d['notifications'] if n['id']=='$NID'][0]['readAt'] else 1)\""
if [ "$MODE" = full ]; then
  chk "PN3 totalUnread decreased to 2" "[ \"\$(total_of $W/p3.json)\" = '2' ]"
fi

# ── PN4: mark-ONE with an unknown id (idempotent no-op) ──────
echo "== PN4: unknown id =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d '{"ids":["cnonexistent00000000000000"]}')
chk "PN4 unknown id -> 200 (idempotent no-op)" "[ '$CODE' = '200' ]"
panellist "$W/p4.json"
chk "PN4 unread unchanged (2)" "[ \"\$(unread_of $W/p4.json)\" = '2' ]"
if [ "$MODE" = full ]; then
  chk "PN4 totalUnread unchanged (2)" "[ \"\$(total_of $W/p4.json)\" = '2' ]"
fi

# ── PN5: mark-ALL ────────────────────────────────────────────
echo "== PN5: mark-all =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
  -H 'content-type: application/json' -d '{}')
chk "PN5 mark-all ({}) -> 200" "[ '$CODE' = '200' ]"
panellist "$W/p5.json"
chk "PN5 unread -> 0" "[ \"\$(unread_of $W/p5.json)\" = '0' ]"
chk "PN5 every row read (API view)" "py \"import json;d=json.load(open('$W/p5.json'));exit(0 if all(n['readAt'] for n in d['notifications']) else 1)\""
if [ "$MODE" = full ]; then
  chk "PN5 totalUnread -> 0" "[ \"\$(total_of $W/p5.json)\" = '0' ]"
fi

# ── PN6: 401 gates + access policy ───────────────────────────
echo "== PN6: 401 gates / access policy =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/panel-notifications")
chk "PN6 GET without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/panel-notifications/read" \
  -H 'content-type: application/json' -d '{}')
chk "PN6 POST without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H 'Cookie: medpad_admin=forged-token-value' \
  "$BASE/api/panel-notifications")
chk "PN6 GET with forged cookie -> 401" "[ '$CODE' = '401' ]"
# A VALID tracker token (the reps' app token) must NEVER touch the panel API
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Medpad-Token: $TOK1" "$BASE/api/panel-notifications")
chk "PN6 GET with tracker token -> 401 (reps cannot read panel notifications)" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/panel-notifications/read" \
  -H 'content-type: application/json' -H "X-Medpad-Token: $TOK1" -d '{}')
chk "PN6 POST with tracker token -> 401 (reps cannot mark them)" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/panel-notifications?token=$TOK1")
chk "PN6 GET with tracker token as ?token= -> 401 (no query-param bypass)" "[ '$CODE' = '401' ]"

# ── PN7: [full] malformed ids hardening ──────────────────────
echo "== PN7: malformed ids hardening (5-B) =="
if [ "$MODE" = full ]; then
  # fresh unread rows to guard: create one more real event
  C3=$(echo $CUS_IDS | cut -d' ' -f3)
  curl -s -o /dev/null -b "$W/cj" -X POST $BASE/api/assignments \
    -H 'content-type: application/json' -d "{\"employeeId\":\"$E2\",\"customerId\":\"$C3\"}"
  panellist "$W/p7.json"
  U7=$(unread_of "$W/p7.json"); T7=$(total_of "$W/p7.json")
  chk "PN7 guard row created (unread=1)" "[ '$U7' = '1' ]"

  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '{"ids":[]}')
  chk "PN7 ids:[] -> 400 (was: silent mark-ALL)" "[ '$CODE' = '400' ]"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '{"ids":"clxxstr"}')
  chk "PN7 ids:\"str\" -> 400 (was: silent mark-ALL)" "[ '$CODE' = '400' ]"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '{"ids":[1,2]}')
  chk "PN7 ids:[1,2] -> 400 (was: Prisma 500)" "[ '$CODE' = '400' ]"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '{broken json')
  chk "PN7 broken JSON body -> 400 (was: silent mark-ALL)" "[ '$CODE' = '400' ]"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '[1,2]')
  chk "PN7 JSON array body -> 400" "[ '$CODE' = '400' ]"
  curl -s -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '{"ids":[]}' -o "$W/p7err.json"
  chk "PN7 400 body says bad-ids" "grep -q 'bad-ids' $W/p7err.json"

  panellist "$W/p7b.json"
  chk "PN7 NOTHING was marked: unread unchanged (1)" "[ \"\$(unread_of $W/p7b.json)\" = '$U7' ]"
  chk "PN7 NOTHING was marked: totalUnread unchanged (1)" "[ \"\$(total_of $W/p7b.json)\" = '$T7' ]"
else
  s "PN7 malformed-ids hardening (pre-5-B bundle)"
fi

# ── PN8: [full] ids length cap ───────────────────────────────
echo "== PN8: ids cap (5-B) =="
if [ "$MODE" = full ]; then
  python3 - "$W/p1001.json" 1001 <<'PYJ'
import json, sys
open(sys.argv[1], 'w').write(json.dumps({"ids": [f"cdummy{i:06d}" for i in range(int(sys.argv[2]))]}))
PYJ
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' --data-binary @"$W/p1001.json")
  chk "PN8 1001 ids -> 400 (cap)" "[ '$CODE' = '400' ]"
  python3 - "$W/p1000.json" 1000 <<'PYJ'
import json, sys
open(sys.argv[1], 'w').write(json.dumps({"ids": [f"cdummy{i:06d}" for i in range(int(sys.argv[2]))]}))
PYJ
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' --data-binary @"$W/p1000.json")
  chk "PN8 1000 well-formed ids -> 200 (boundary ok, no-op)" "[ '$CODE' = '200' ]"
  panellist "$W/p8.json"
  chk "PN8 counts untouched after both (unread=1)" "[ \"\$(unread_of $W/p8.json)\" = '1' ]"
  chk "PN8 counts untouched after both (totalUnread=1)" "[ \"\$(total_of $W/p8.json)\" = '1' ]"
else
  s "PN8 ids cap (pre-5-B bundle)"
fi

# ── PN9: [full] window vs whole-table counters ───────────────
echo "== PN9: window vs totalUnread (147 fresh events + 1 guard = 148 unread) =="
if [ "$MODE" = full ]; then
  # 147 NEW assignment events, each a pair never assigned before in this run
  # (a repeat pair would still answer 200 but the eventKey dedup in
  # notifyAdmin() would swallow its notification — keeping the count exact):
  #   E1 x CUS3..CUS50 (48) — E1 only had CUS1/CUS2 (PN2)
  #   E2 x all except CUS3 (49) — E2 only had CUS3 (PN7)
  #   E3 x all 50 — E3 had none
  python3 - "$E1" "$E2" "$E3" $CUS_IDS > "$W/pairs.txt" <<'PYPAIRS'
import sys
e1, e2, e3 = sys.argv[1], sys.argv[2], sys.argv[3]
cus = sys.argv[4:]
pairs = []
pairs += [(e1, c) for c in cus[2:]]
pairs += [(e2, c) for c in cus if c != cus[2]]
pairs += [(e3, c) for c in cus]
assert len(pairs) == 147, len(pairs)
for e, c in pairs: print(f"{e} {c}")
PYPAIRS
  N_PAIRS=$(wc -l < "$W/pairs.txt")
  OK_COUNT=0; FAIL_COUNT=0
  while read -r EE CC; do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$W/cj" -X POST $BASE/api/assignments </dev/null \
      -H 'content-type: application/json' -d "{\"employeeId\":\"$EE\",\"customerId\":\"$CC\"}")
    if [ "$CODE" = "200" ]; then OK_COUNT=$((OK_COUNT+1)); else FAIL_COUNT=$((FAIL_COUNT+1)); echo "  assignment $EE/$CC -> $CODE"; fi
  done < "$W/pairs.txt"
  echo "  assignments ok=$OK_COUNT failed=$FAIL_COUNT (pairs=$N_PAIRS)"
  chk "PN9 all fresh assignments created" "[ '$FAIL_COUNT' = '0' ] && [ '$OK_COUNT' = '$N_PAIRS' ]"

  # notifications now: 3 (PN2) + 1 (PN7) + N_PAIRS, unread = 1 + N_PAIRS (146 or 147)
  EXPECT_TOTAL=$((1 + OK_COUNT))
  panellist "$W/p9.json"
  LIST_LEN=$(py "import json;print(len(json.load(open('$W/p9.json'))['notifications']))")
  chk "PN9 list capped at 100 (window)" "[ '$LIST_LEN' = '100' ]"
  chk "PN9 window unread == 100" "[ \"\$(unread_of $W/p9.json)\" = '100' ]"
  chk "PN9 totalUnread == $EXPECT_TOTAL (whole table — THE 5-B FIX)" "[ \"\$(total_of $W/p9.json)\" = '$EXPECT_TOTAL' ]"

  NEWEST=$(py "import json;print(json.load(open('$W/p9.json'))['notifications'][0]['id'])")
  curl -s -o /dev/null -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d "{\"ids\":[\"$NEWEST\"]}"
  panellist "$W/p9b.json"
  chk "PN9 after mark-one: window unread 99" "[ \"\$(unread_of $W/p9b.json)\" = '99' ]"
  chk "PN9 after mark-one: totalUnread $((EXPECT_TOTAL-1))" "[ \"\$(total_of $W/p9b.json)\" = '$((EXPECT_TOTAL-1))' ]"

  curl -s -o /dev/null -b "$W/cj" -X POST $BASE/api/panel-notifications/read \
    -H 'content-type: application/json' -d '{}'
  panellist "$W/p9c.json"
  chk "PN9 after mark-all: unread 0" "[ \"\$(unread_of $W/p9c.json)\" = '0' ]"
  chk "PN9 after mark-all: totalUnread 0" "[ \"\$(total_of $W/p9c.json)\" = '0' ]"

  # DB proof with the server stopped (whole table, not just the window)
  (cd "$W/inst" && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
  DB_CHECK=$(python3 -c "
import sqlite3
c = sqlite3.connect('$DB')
u = c.execute('SELECT COUNT(*) FROM PanelNotification WHERE readAt IS NULL').fetchone()[0]
t = c.execute('SELECT COUNT(*) FROM PanelNotification').fetchone()[0]
print(f'{u} {t}')")
  DB_U=$(echo "$DB_CHECK" | cut -d' ' -f1); DB_T=$(echo "$DB_CHECK" | cut -d' ' -f2)
  EXPECT_ROWS=$((4 + OK_COUNT))   # 3 (PN2) + 1 (PN7) + fresh
  chk "PN9 DB proof: unread rows == 0 (whole table)" "[ '$DB_U' = '0' ]"
  chk "PN9 DB proof: total rows == $EXPECT_ROWS" "[ '$DB_T' = '$EXPECT_ROWS' ]"
  # boot again for PN10
  (cd "$W/inst" && node server-manager.mjs start --no-open > "$W/boot2.log" 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
else
  s "PN9 window-vs-total semantics (pre-5-B bundle)"
fi

# ── PN10: persistence across restart ─────────────────────────
echo "== PN10: persistence =="
if [ "$MODE" = full ]; then
  panellist "$W/p10.json"
  chk "PN10 after restart: unread 0" "[ \"\$(unread_of $W/p10.json)\" = '0' ]"
  chk "PN10 after restart: totalUnread 0" "[ \"\$(total_of $W/p10.json)\" = '0' ]"
  chk "PN10 list still has the rows (100 window)" "py \"import json;exit(0 if len(json.load(open('$W/p10.json'))['notifications'])==100 else 1)\""
else
  # baseline: restart ourselves to prove persistence of the package-4 state
  (cd "$W/inst" && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
  (cd "$W/inst" && node server-manager.mjs start --no-open > "$W/boot2.log" 2>&1)
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
  panellist "$W/p10.json"
  chk "PN10 after restart: unread 0 (persisted)" "[ \"\$(unread_of $W/p10.json)\" = '0' ]"
  chk "PN10 rows survive restart" "py \"import json;exit(0 if len(json.load(open('$W/p10.json'))['notifications'])>=3 else 1)\""
fi

echo
echo "=================================================="
echo "P5API RESULT: PASS=$PASS FAIL=$FAIL SKIP=$SKIP (MODE=$MODE)"
echo "=================================================="
[ "$FAIL" = "0" ] || exit 1
if [ "$MODE" = full ]; then echo "ALL-P5API-CHECKS-PASS"; else echo "P5API-BASELINE-PASS (pre-5-B bundle: 5-B checks skipped)"; fi
exit 0
