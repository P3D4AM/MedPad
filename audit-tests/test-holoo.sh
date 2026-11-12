#!/usr/bin/env bash
# test-holoo.sh — Holoo integration (phase 1): connection settings, REAL
# login + read-only endpoint probe, the sync engine (upsert by ErpCode,
# contentHash change detection, NO-DELETE, ownership separation, linking
# WITHOUT auto-assignment), the erp blocks on the app APIs, the boot
# scheduler — all against the REAL installed bundle with an EXPLICIT
# local MOCK of the Holoo service (audit-tests/holoo-mock.mjs).
#
#   H0  fresh install + boot; the four Holoo tables + Customer.erpCode +
#       Purchase.holooJson created by the boot migration
#   H1  settings API: 401 without session; defaults; Persian 400s for a
#       bad base URL / out-of-range interval; PUT persists; the password
#       NEVER comes back (hasPassword only); audit row written
#   H2  connection test: 400 while unconfigured; with credentials a REAL
#       login against the mock + the 5 read-only endpoint probes (the
#       GetCustPay probe carries a REAL sample code + BOTH documented
#       parameter spellings — spec §4 `code` and the public-client
#       `customerErpCode`); the mock log proves the password traveled
#       BASE64 (never in plain)
#   H3  manual sync: ErpCustomer/ErpProduct/ErpPayment upserts, Purchase
#       (source='holoo') + the single total line + holooJson, Jalali AND
#       Gregorian invoice dates, explicit blacklist → active=false while
#       the MedPad Customer.active stays TRUE, pre-seeded same-name
#       customer LINKED (Arabic-ي normalization), ZERO auto-assignments
#   H4  app APIs: /api/app/customers carries the erp block (mandeh/credit)
#       for ASSIGNED customers; the 360 profile carries erpAccount +
#       erpPayments; an unassigned rep gets 403
#   H5  change detection + NO-DELETE: mutate the mock state (a customer
#       vanishes, another changes, an invoice total changes) → re-sync →
#       the vanished row SURVIVES (absence is never a delete), the changed
#       rows update, the log counts are honest
#   H5b PARTIAL sync (spec §9 vocabulary موفق/ناقص/ناموفق): GetCustPay
#       fails for every customer (mock failPay → 500) → the run is honestly
#       «partial», the error is recorded, and the healthy sections' data
#       (customers/products/invoices + prior balances) SURVIVES intact
#   H6  the boot scheduler: enable (interval=1 min) → an INCREMENTAL run
#       appears with NO manual trigger (slow — up to ~2 minutes)
#   H7  security sweep: the plain password / tokens appear in NO API
#       response, NO mock log line, NO HolooSyncLog error text; the stored
#       password is an enc1: AES-256-GCM envelope (Task 67) — never plain,
#       never raw Base64
#   H9  credential encryption (Task 67): per-install key file created
#       OUTSIDE SQLite (panel/db/holoo.key, 32 bytes, mode 600); stored
#       password AND cached token are enc1 envelopes; a WRONG key or a
#       MISSING key surfaces an explicit Persian passwordError WITHOUT
#       wiping the stored settings (DB value byte-identical); settings
#       survive a panel restart; a legacy Base64 value migrates atomically
#       on first read (and the migrated value still logs in); an UPGRADE
#       re-run of the installer keeps the key byte-identical; no holoo.key
#       ever ships inside the bundle
#   H8  bundle artifacts: the 5 /api/admin/holoo/* routes ship; Holoo tab
#       + profile financial-block markers in the client chunks
#
# Deterministic: fresh install; curl + python3 (sqlite3 via python3).
# Run:  bash test-holoo.sh [PKG_DIR]   (HL_WORK=<dir> overrides)
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2" >/dev/null 2>&1; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${HL_WORK:-/tmp/thl}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="; echo "== WORKDIR: $W =="

PANEL_PORT=3341; RT_PORT=3344; BASE="http://127.0.0.1:$PANEL_PORT"
MOCK_PORT=8399
ADMIN_PASS='HLpass123!'
HOLOO_PASS='HolooPass123!'
DB="$W/inst/panel/db/custom.db"
MOCK_STATE="$W/holoo-state.json"
MOCK_LOG="$W/holoo-requests.log"

cleanup() {
  (cd $W/inst 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

# ── the MOCK Holoo service (explicit, local, labelled) ─────────────────
cat > "$MOCK_STATE" << 'EOF'
{
  "customers": [
    {"ErpCode":"bBAPcust0001=","Code":"00001","Name":"داروخانه علی","Mandeh":1250000.5,"Credit":500000,"Mobile":"09151112233","Tel":"05138123456","City":"مشهد","Ostan":"خراسان رضوی","NationalId":"1234567890","IsActive":true},
    {"ErpCode":"bBAPcust0002=","Code":"00002","Name":"کلینیک مهر","Mandeh":0,"Credit":100000,"City":"مشهد","Ostan":"خراسان رضوی","IsActive":true},
    {"ErpCode":"bBAPcust0003=","Code":"00003","Name":"آزمایشگاه پویا","Mandeh":98000,"Credit":0,"City":"تهران","Ostan":"تهران","IsBlackList":true}
  ],
  "products": [
    {"ErpCode":"bBAPprod0001=","Code":"1001","Name":"دارو الف","Few":"عدد","BuyPrice":1000,"SellPrice":1500,"m_groupname":"مکمل"},
    {"ErpCode":"bBAPprod0002=","Code":"1002","Name":"دارو ب","SellPrice":25000,"m_groupname":"مکمل"}
  ],
  "invoices": [
    {"ErpCode":"bBAPinv0001=","Code":"10001","CustomerErpCode":"bBAPcust0001=","Date":"1404/08/15","SumPrice":5000000,"SumNaghd":2000000,"Nesiyeh":3000000,"Check":0,"Levy":0,"Scot":50000,"SanadCode":"4501","Type":"F","Time":"10:30:00"},
    {"ErpCode":"bBAPinv0002=","Code":"10002","CustomerErpCode":"bBAPcust0002=","Date":"2026-09-16","SumPrice":1500000,"SumNaghd":1500000,"Nesiyeh":0,"Scot":0,"SanadCode":"4502","Type":"F","Time":"12:00:00"},
    {"ErpCode":"bBAPinv0003=","Code":"10003","CustomerErpCode":"bBAPcust0003=","Date":"1404/07/01","SumPrice":98000,"SumNaghd":0,"Nesiyeh":98000,"SanadCode":"4503","Type":"K","Time":"09:15:00"}
  ],
  "payments": {
    "bBAPcust0001=": [
      {"Code":"88001","Name":"دریافت نقدی","Price":1000000,"Date":"1404/08/20","Mandeh":1250000.5},
      {"Code":"88002","Name":"چک دریافتی","Price":500000,"Date":"2026-10-01","Mandeh":1250000.5}
    ],
    "bBAPcust0002=": [
      {"Code":"88003","Name":"دریافت کارتی","Price":250000,"Date":"1404/08/25","Mandeh":0}
    ]
  }
}
EOF
node "$PKG/audit-tests/holoo-mock.mjs" $MOCK_PORT "$MOCK_STATE" "$MOCK_LOG" > $W/mock.log 2>&1 &
MOCK_PID=$!
for i in $(seq 1 15); do curl -s -o /dev/null "http://127.0.0.1:$MOCK_PORT/TncHoloo/api/Customer" && break; sleep 1; done
chk "mock up (401 without token is fine)" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$MOCK_PORT/TncHoloo/api/Customer | grep -q 401"

# ── H0: fresh install + boot ──────────────────────────────────────────
echo "== H0: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/install.log 2>&1)
chk "H0 install exit 0 (medpad-config.json)" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "H0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
chk "H0 ErpCustomer table (boot migration)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='ErpCustomer'\\\").fetchone() else 1)\""
chk "H0 ErpProduct table" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='ErpProduct'\\\").fetchone() else 1)\""
chk "H0 ErpPayment table" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='ErpPayment'\\\").fetchone() else 1)\""
chk "H0 HolooSyncLog table" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT name FROM sqlite_master WHERE name='HolooSyncLog'\\\").fetchone() else 1)\""
chk "H0 Customer.erpCode column + unique index" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');cols=[r[1] for r in c.execute('PRAGMA table_info(Customer)')];idx=[r[1] for r in c.execute('PRAGMA index_list(Customer)')];exit(0 if 'erpCode' in cols and 'Customer_erpCode_key' in idx else 1)\""
chk "H0 Purchase.holooJson column" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');cols=[r[1] for r in c.execute('PRAGMA table_info(Purchase)')];exit(0 if 'holooJson' in cols else 1)\""

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o $W/login.json
chk "H0 admin login" "py \"import json;exit(0 if json.load(open('$W/login.json'))['ok'] else 1)\""

# ── H1: settings API ──────────────────────────────────────────────────
echo "== H1: settings API =="
CODE=$(curl -s -o $W/h_noauth.json -w '%{http_code}' $BASE/api/admin/holoo/settings)
chk "H1 401 without session" "[ '$CODE' = '401' ]"
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_set0.json
chk "H1 defaults (enabled=false, interval=3, hasPassword=false, configured=false)" \
  "py \"import json;d=json.load(open('$W/h_set0.json'))['settings'];exit(0 if d['enabled'] is False and d['syncIntervalMin']==3 and d['hasPassword'] is False and d['configured'] is False else 1)\""
CODE=$(curl -s -b $W/cj -o $W/h_badurl.json -w '%{http_code}' -X PUT $BASE/api/admin/holoo/settings \
  -H 'content-type: application/json' -d '{"baseUrl":"192.168.1.10:8080"}')
chk "H1 bad baseUrl rejected (400, Persian)" "[ '$CODE' = '400' ]"
chk "H1 bad baseUrl message is Persian" "grep -q 'http://' $W/h_badurl.json"
CODE=$(curl -s -b $W/cj -o $W/h_badint.json -w '%{http_code}' -X PUT $BASE/api/admin/holoo/settings \
  -H 'content-type: application/json' -d '{"syncIntervalMin":0}')
chk "H1 interval 0 rejected (400)" "[ '$CODE' = '400' ]"
curl -s -b $W/cj -X PUT $BASE/api/admin/holoo/settings -H 'content-type: application/json' \
  -d "{\"baseUrl\":\"http://127.0.0.1:$MOCK_PORT/TncHoloo/api\",\"dbname\":\"HolooTest\",\"username\":\"holoadmin\",\"password\":\"$HOLOO_PASS\",\"syncIntervalMin\":5}" -o $W/h_set1.json
chk "H1 PUT persists (configured=true, isHttp=true)" \
  "py \"import json;d=json.load(open('$W/h_set1.json'))['settings'];exit(0 if d['configured'] and d['isHttp'] and d['syncIntervalMin']==5 else 1)\""
chk "H1 GET now shows hasPassword=true" \
  "curl -s -b $W/cj $BASE/api/admin/holoo/settings | py \"import json,sys;d=json.load(sys.stdin)['settings'];exit(0 if d['hasPassword'] and 'password' not in d else 1)\""
chk "H1 audit row holoo.settings.update" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM AuditLog WHERE action='holoo.settings.update'\\\").fetchone()[0]>=1 else 1)\""

# ── H2: REAL connection test against the mock ────────────────────────
echo "== H2: connection test =="
# first: break dbname → configured still true but login fails honestly
curl -s -b $W/cj -X PUT $BASE/api/admin/holoo/settings -H 'content-type: application/json' \
  -d '{"dbname":"WrongDB"}' > /dev/null
CODE=$(curl -s -b $W/cj -o $W/h_test_bad.json -w '%{http_code}' -X POST $BASE/api/admin/holoo/test)
chk "H2 wrong credentials → ok=false with the classified error" \
  "py \"import json;d=json.load(open('$W/h_test_bad.json'));exit(0 if not d['ok'] and 'ورود' in d.get('error','') else 1)\""
# restore the right dbname
curl -s -b $W/cj -X PUT $BASE/api/admin/holoo/settings -H 'content-type: application/json' \
  -d '{"dbname":"HolooTest"}' > /dev/null
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/h_test_ok.json
chk "H2 login ok + 4 readable endpoints probed (5th honestly 404)" \
  "py \"import json;d=json.load(open('$W/h_test_ok.json'));exit(0 if d['ok'] and len([e for e in d['endpoints'] if e['ok']])==4 and len(d['endpoints'])==5 else 1)\""
chk "H2 Customer probe reports array(3) shape" \
  "py \"import json;d=json.load(open('$W/h_test_ok.json'));e=[x for x in d['endpoints'] if x['path']=='Customer'][0];exit(0 if e['shape']=='array(3)' else 1)\""
chk "H2 Invoice/Invoice probe honestly 404 (fallback documented)" \
  "py \"import json;d=json.load(open('$W/h_test_ok.json'));e=[x for x in d['endpoints'] if x['path']=='Invoice/Invoice'][0];exit(0 if not e['ok'] and '404' in (e.get('error') or '') else 1)\""
chk "H2 GetCustPay probe used a REAL sample code (array(2) shape)" \
  "py \"import json;d=json.load(open('$W/h_test_ok.json'));e=[x for x in d['endpoints'] if x['path']=='Report/GetCustPay'][0];exit(0 if e['ok'] and e['shape']=='array(2)' else 1)\""
chk "H2 probe wire carried BOTH parameter spellings (code + customerErpCode)" \
  "grep -q 'Report/GetCustPay?customerErpCode=bBAPcust0001%3D&code=bBAPcust0001%3D' $MOCK_LOG"
HOLOO_PASS_B64=$(python3 -c "import base64;print(base64.b64encode('$HOLOO_PASS'.encode()).decode())")
chk "H2 mock saw the password as BASE64 (never plain)" "grep -q 'pass=$HOLOO_PASS_B64' $MOCK_LOG"
chk "H2 mock log has NO plain password" "! grep -q '$HOLOO_PASS' $MOCK_LOG"

# ── H3: manual sync ──────────────────────────────────────────────────
echo "== H3: manual sync =="
# pre-seed a MedPad customer whose name needs Arabic-ي normalization to match
SEED=$(curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"داروخانه علي","type":"pharmacy","lat":36.31,"lng":59.54,"phone":"05138445566","city":"مشهد","province":"خراسان رضوی"}')
SEED_ID=$(echo "$SEED" | py 'import json,sys;print(json.load(sys.stdin)["customer"]["id"])')
chk "H3 pre-seeded same-name customer created" "[ -n '$SEED_ID' ]"

CODE=$(curl -s -b $W/cj -o $W/h_sync1.json -w '%{http_code}' -X POST $BASE/api/admin/holoo/sync)
chk "H3 manual sync started (fire-and-forget)" "[ '$CODE' = '200' ]"
SYNC_DONE=1
for i in $(seq 1 60); do
  curl -s -b $W/cj $BASE/api/admin/holoo/status -o $W/h_status1.json
  if py "import json;d=json.load(open('$W/h_status1.json'))['sync']['lastRun'];exit(0 if d and d['kind']=='manual' and d['status']!='running' else 1)"; then SYNC_DONE=0; break; fi
  sleep 1
done
chk "H3 sync finished (≤60s)" "[ '$SYNC_DONE' = '0' ]"
chk "H3 last run status=success" \
  "py \"import json;d=json.load(open('$W/h_status1.json'))['sync']['lastRun'];exit(0 if d['status']=='success' else 1)\""
chk "H3 counts: 3 customers checked, 3 created" \
  "py \"import json;d=json.load(open('$W/h_status1.json'))['sync']['lastRun'];exit(0 if d['checked']>=8 and d['created']>=8 else 1)\""
chk "H3 ErpCustomer rows = 3" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute('SELECT COUNT(*) FROM ErpCustomer').fetchone()[0]==3 else 1)\""
chk "H3 ErpProduct rows = 2" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute('SELECT COUNT(*) FROM ErpProduct').fetchone()[0]==2 else 1)\""
chk "H3 ErpPayment rows = 3 (2+1 per customer)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute('SELECT COUNT(*) FROM ErpPayment').fetchone()[0]==3 else 1)\""
chk "H3 blacklist flag → ErpCustomer.active=false (explicit only)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT active FROM ErpCustomer WHERE erpCode='bBAPcust0003='\\\").fetchone()[0]==0 else 1)\""
chk "H3 balance from GetCustPay → mandeh=1250000.5" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT mandeh FROM ErpCustomer WHERE erpCode='bBAPcust0001='\\\").fetchone()[0]==1250000.5 else 1)\""
chk "H3 Purchase(source=holoo) = 3 with holooJson" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM Purchase WHERE source='holoo' AND holooJson IS NOT NULL\\\").fetchone()[0]==3 else 1)\""
chk "H3 invoice total line = SumPrice (5000000)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT pi.total FROM PurchaseItem pi JOIN Purchase p ON pi.purchaseId=p.id WHERE p.externalId='bBAPinv0001='\\\").fetchone()[0]==5000000 else 1)\""
chk "H3 Jalali date 1404/08/15 parsed (≈ 2025-11-06)" \
  "py \"import sqlite3,datetime;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT date FROM Purchase WHERE externalId='bBAPinv0001='\\\").fetchone()[0];d=datetime.datetime.utcfromtimestamp(v/1000);exit(0 if d.date().isoformat()=='2025-11-06' else 1)\""
chk "H3 Gregorian date 2026-09-16 parsed as-is" \
  "py \"import sqlite3,datetime;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT date FROM Purchase WHERE externalId='bBAPinv0002='\\\").fetchone()[0];d=datetime.datetime.utcfromtimestamp(v/1000);exit(0 if d.date().isoformat()=='2026-09-16' else 1)\""
chk "H3 same-name customer LINKED via Arabic-ي normalization" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT erpCode FROM Customer WHERE id='$SEED_ID'\\\").fetchone()[0]=='bBAPcust0001=' else 1)\""
chk "H3 linked customer's operational fields UNTOUCHED (phone/type/city)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');r=c.execute(\\\"SELECT phone,type,city,active FROM Customer WHERE id='$SEED_ID'\\\").fetchone();exit(0 if r[0]=='05138445566' and r[1]=='pharmacy' and r[2]=='مشهد' and r[3]==1 else 1)\""
chk "H3 NO auto-assignment (Assignment count = 0)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute('SELECT COUNT(*) FROM Assignment').fetchone()[0]==0 else 1)\""
chk "H3 MedPad Customer.active stays TRUE for the blacklisted one" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT c.active FROM Customer c JOIN ErpCustomer e ON c.erpCode=e.erpCode WHERE e.erpCode='bBAPcust0003='\\\").fetchone()[0]==1 else 1)\""
chk "H3 every MedPad customer is now Holoo-linked (3 total, 0 unlinked)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');total=c.execute('SELECT COUNT(*) FROM Customer').fetchone()[0];nulls=c.execute('SELECT COUNT(*) FROM Customer WHERE erpCode IS NULL').fetchone()[0];exit(0 if total==3 and nulls==0 else 1)\""
chk "H3 every sync GetCustPay fetch carried BOTH parameter spellings" \
  "TOTAL=\$(grep -c 'Report/GetCustPay?customerErpCode=' $MOCK_LOG); SINGLE=\$(grep -c 'Report/GetCustPay?customerErpCode=[^&]* auth' $MOCK_LOG); [ \"\$TOTAL\" -ge 4 ] && [ \"\$SINGLE\" -eq 0 ]"

# ── H4: app APIs carry the erp blocks ────────────────────────────────
echo "== H4: app APIs =="
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"rep1","password":"Rep1pass123!","name":"رضا مدرپ"}' > $W/empA.json
EMPA=$(py "import json;print(json.load(open('$W/empA.json'))['employee']['id'])")
T1=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"rep1","password":"Rep1pass123!"}' | py 'import json,sys;print(json.load(sys.stdin)["token"])')
chk "H4 rep logged in" "[ -n '$T1' ]"
curl -s -b $W/cj -X POST $BASE/api/assignments -H 'content-type: application/json' \
  -d "{\"employeeId\":\"$EMPA\",\"customerId\":\"$SEED_ID\"}" > /dev/null
curl -s -H "x-medpad-token: $T1" "$BASE/api/app/customers" -o $W/h_app_cust.json
chk "H4 erp block (mandeh/credit) for the assigned customer" \
  "py \"import json;d=json.load(open('$W/h_app_cust.json'));cs=[c for c in d['customers'] if c['id']=='$SEED_ID'];exit(0 if cs and cs[0]['erp'] and cs[0]['erp']['mandeh']==1250000.5 and cs[0]['erp']['credit']==500000 else 1)\""
chk "H4 only ASSIGNED customers returned (1)" \
  "py \"import json;d=json.load(open('$W/h_app_cust.json'));exit(0 if len(d['customers'])==1 else 1)\""
curl -s -H "x-medpad-token: $T1" "$BASE/api/app/customers/$SEED_ID/profile" -o $W/h_app_prof.json
chk "H4 profile erpAccount + erpPayments" \
  "py \"import json;d=json.load(open('$W/h_app_prof.json'));exit(0 if d['erpAccount'] and d['erpAccount']['mandeh']==1250000.5 and len(d['erpPayments'])==2 else 1)\""
chk "H4 profile purchases include the holoo invoice" \
  "py \"import json;d=json.load(open('$W/h_app_prof.json'));exit(0 if any(p.get('source')=='holoo' for p in d['purchases']) else 1)\""
# a second, unassigned rep must get 403 on the profile
curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
  -d '{"username":"rep2","password":"Rep2pass123!","name":"سارا مدرپ"}' > /dev/null
T2=$(curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"rep2","password":"Rep2pass123!"}' | py 'import json,sys;print(json.load(sys.stdin)["token"])')
CODE=$(curl -s -o $W/h_app_403.json -w '%{http_code}' -H "x-medpad-token: $T2" "$BASE/api/app/customers/$SEED_ID/profile")
chk "H4 unassigned rep → 403" "[ '$CODE' = '403' ]"
# admin-side 360 profile also carries the blocks
curl -s -b $W/cj "$BASE/api/customers/$SEED_ID/profile" -o $W/h_admin_prof.json
chk "H4 admin 360 profile erpAccount present" \
  "py \"import json;d=json.load(open('$W/h_admin_prof.json'));exit(0 if d.get('erpAccount') and d['erpAccount']['code']=='00001' else 1)\""

# ── H5: change detection + NO-DELETE ─────────────────────────────────
echo "== H5: change detection + no-delete =="
python3 - "$MOCK_STATE" << 'PYEOF'
import json, sys
p = sys.argv[1]
st = json.load(open(p))
# cust0002's balance changes (customer list AND its payment report — the
# report is authoritative for mandeh); its credit changes too; cust0003
# VANISHES from the response (no-delete!); invoice 10002's total changes
for c in st['customers']:
    if c['ErpCode'] == 'bBAPcust0002=':
        c['Mandeh'] = 777000
        c['Credit'] = 222000
for pay in st['payments'].get('bBAPcust0002=', []):
    pay['Mandeh'] = 777000
st['customers'] = [c for c in st['customers'] if c['ErpCode'] != 'bBAPcust0003=']
for i in st['invoices']:
    if i['ErpCode'] == 'bBAPinv0002=':
        i['SumPrice'] = 1800000
json.dump(st, open(p, 'w'), ensure_ascii=False, indent=1)
PYEOF
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/sync -o /dev/null
SYNC2=1
for i in $(seq 1 60); do
  curl -s -b $W/cj $BASE/api/admin/holoo/status -o $W/h_status2.json
  if py "import json;d=json.load(open('$W/h_status2.json'))['sync'];lr=d['lastRun'];exit(0 if lr['kind']=='manual' and lr['status']!='running' and len(d['recentLogs'])>=2 else 1)"; then SYNC2=0; break; fi
  sleep 1
done
chk "H5 second sync finished" "[ '$SYNC2' = '0' ]"
chk "H5 vanished customer SURVIVES (absence is never a delete)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT COUNT(*) FROM ErpCustomer WHERE erpCode='bBAPcust0003='\\\").fetchone()[0]==1 else 1)\""
chk "H5 changed customer updated (mandeh 0 → 777000 via the payment report)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT mandeh FROM ErpCustomer WHERE erpCode='bBAPcust0002='\\\").fetchone()[0]==777000 else 1)\""
chk "H5 changed customer's credit updated via the customer list (100000 → 222000)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT credit FROM ErpCustomer WHERE erpCode='bBAPcust0002='\\\").fetchone()[0]==222000 else 1)\""
chk "H5 unchanged customer untouched (hash-stable)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT mandeh FROM ErpCustomer WHERE erpCode='bBAPcust0001='\\\").fetchone()[0]==1250000.5 else 1)\""
chk "H5 changed invoice total line updated (1500000 → 1800000)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT pi.total FROM PurchaseItem pi JOIN Purchase p ON pi.purchaseId=p.id WHERE p.externalId='bBAPinv0002='\\\").fetchone()[0]==1800000 else 1)\""
chk "H5 log counts honest (updated ≥ 2)" \
  "py \"import json;d=json.load(open('$W/h_status2.json'))['sync']['lastRun'];exit(0 if d['updated']>=2 else 1)\""
chk "H5 run stays SUCCESS — the vanished customer's invoice found its durable link" \
  "py \"import json;d=json.load(open('$W/h_status2.json'))['sync']['lastRun'];exit(0 if d['status']=='success' and len(d['errors'])==0 else 1)\""

# ── H5b: PARTIAL sync (spec §9 — a failing section never destroys ─────
#    healthy data; the run is honestly labeled ناقص)
echo "== H5b: partial sync (GetCustPay outage) =="
python3 - "$MOCK_STATE" << 'PYEOF'
import json, sys
p = sys.argv[1]
st = json.load(open(p))
st['failPay'] = True  # GetCustPay → 500 for EVERY customer (MOCK outage)
json.dump(st, open(p, 'w'), ensure_ascii=False, indent=1)
PYEOF
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/sync -o /dev/null
SYNC3=1
for i in $(seq 1 60); do
  curl -s -b $W/cj $BASE/api/admin/holoo/status -o $W/h_status4.json
  if py "import json;d=json.load(open('$W/h_status4.json'))['sync'];lr=d['lastRun'];exit(0 if lr['kind']=='manual' and lr['status']!='running' and len(d['recentLogs'])>=3 else 1)"; then SYNC3=0; break; fi
  sleep 1
done
chk "H5b partial sync finished" "[ '$SYNC3' = '0' ]"
chk "H5b run status=partial (ناقص — spec §9 vocabulary)" \
  "py \"import json;d=json.load(open('$W/h_status4.json'))['sync']['lastRun'];exit(0 if d['status']=='partial' else 1)\""
chk "H5b the GetCustPay failure is recorded as a safe classified error" \
  "py \"import json;d=json.load(open('$W/h_status4.json'))['sync']['lastRun'];exit(0 if len(d['errors'])>=1 and any('GetCustPay' in e for e in d['errors']) else 1)\""
chk "H5b healthy sections' data SURVIVES (3 customers, 2 products)" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute('SELECT COUNT(*) FROM ErpCustomer').fetchone()[0]==3 and c.execute('SELECT COUNT(*) FROM ErpProduct').fetchone()[0]==2 else 1)\""
chk "H5b prior balances untouched by the failed payment section" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');exit(0 if c.execute(\\\"SELECT mandeh FROM ErpCustomer WHERE erpCode='bBAPcust0001='\\\").fetchone()[0]==1250000.5 else 1)\""
chk "H5b lastSuccess still points at the last CLEAN run" \
  "py \"import json;d=json.load(open('$W/h_status4.json'))['sync'];exit(0 if d['lastSuccess'] and d['lastSuccess']['checked']>0 else 1)\""
# restore the mock for the scheduler phase
python3 - "$MOCK_STATE" << 'PYEOF'
import json, sys
p = sys.argv[1]
st = json.load(open(p))
st.pop('failPay', None)
json.dump(st, open(p, 'w'), ensure_ascii=False, indent=1)
PYEOF

# ── H6: the boot scheduler (slow — real incremental run, no trigger) ──
echo "== H6: scheduler =="
curl -s -b $W/cj -X PUT $BASE/api/admin/holoo/settings -H 'content-type: application/json' \
  -d '{"enabled":true,"syncIntervalMin":1}' > /dev/null
SCHED=1
for i in $(seq 1 130); do
  curl -s -b $W/cj $BASE/api/admin/holoo/status -o $W/h_status3.json
  if py "import json;d=json.load(open('$W/h_status3.json'))['sync'];exit(0 if any(l['kind']=='incremental' for l in d['recentLogs']) else 1)"; then SCHED=0; break; fi
  sleep 1
done
chk "H6 scheduler fired an INCREMENTAL run on its own (≤ ~2 min)" "[ '$SCHED' = '0' ]"

# ── H7: security sweep ────────────────────────────────────────────────
echo "== H7: security sweep =="
chk "H7 plain password in NO captured API response" \
  "! grep -rq '$HOLOO_PASS' $W/*.json"
chk "H7 plain password in NO mock log line" \
  "! grep -q '$HOLOO_PASS' $MOCK_LOG"
chk "H7 no token material in API responses" \
  "! grep -rq 'mock-token-' $W/h_set*.json $W/h_test*.json $W/h_status*.json"
chk "H7 HolooSyncLog error texts carry no credentials" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');rows=[r[0] or '' for r in c.execute('SELECT errors FROM HolooSyncLog')];exit(0 if not any('$HOLOO_PASS' in x or 'mock-token' in x for x in rows) else 1)\""
chk "H7 stored password is an enc1: envelope (AES-256-GCM, Task 67)" \
  "py \"import sqlite3,base64;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT value FROM Config WHERE key='holoo.password'\\\").fetchone()[0];exit(0 if v.startswith('enc1:') and v.count(':')==3 and v!=base64.b64encode('$HOLOO_PASS'.encode()).decode() else 1)\""
chk "H7 raw wire Base64 of the password is NOT stored anywhere in Config" \
  "py \"import sqlite3,base64;c=sqlite3.connect('$DB');b64=base64.b64encode('$HOLOO_PASS'.encode()).decode();rows=[r[0] or '' for r in c.execute('SELECT value FROM Config')];exit(0 if not any(r==b64 for r in rows) else 1)\""

# ── H9: credential encryption (Task 67) ─────────────────────────────
echo "== H9: AES-256-GCM secret encryption =="
KEYF="$W/inst/panel/db/holoo.key"
chk "H9 per-install key file exists OUTSIDE SQLite (panel/db/holoo.key)" "[ -f '$KEYF' ]"
chk "H9 key is exactly 32 bytes" "[ \"\$(stat -c %s '$KEYF')\" = '32' ]"
chk "H9 key file mode is owner-only (600)" "[ \"\$(stat -c %a '$KEYF')\" = '600' ]"
chk "H9 NO holoo.key inside the shipped bundle" "[ -z \"\$(find $W/inst/panel/.next -name 'holoo.key' -print -quit)\" ]"
chk "H9 cached token is an enc1: envelope too" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT value FROM Config WHERE key='holoo.token'\\\").fetchone()[0];exit(0 if v.startswith('enc1:') else 1)\""

# wrong key → explicit Persian passwordError, settings NOT wiped
KEYHASH_BEFORE=$(sha256sum "$KEYF" | cut -d' ' -f1)
PASSHASH_BEFORE=$(python3 -c "import sqlite3,hashlib;c=sqlite3.connect('$DB');v=c.execute(\"SELECT value FROM Config WHERE key='holoo.password'\").fetchone()[0];print(hashlib.sha256(v.encode()).hexdigest())")
mv "$KEYF" "$KEYF.orig"
head -c 32 /dev/urandom > "$KEYF"; chmod 600 "$KEYF"
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_wrongkey.json
chk "H9 wrong key → passwordError surfaced (Persian)" \
  "py \"import json;d=json.load(open('$W/h_wrongkey.json'))['settings'];exit(0 if d['hasPassword'] and d.get('passwordError') and 'کلید' in d['passwordError'] and not d['configured'] else 1)\""
chk "H9 wrong key → stored value byte-identical (NOT wiped)" \
  "py \"import sqlite3,hashlib;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT value FROM Config WHERE key='holoo.password'\\\").fetchone()[0];exit(0 if hashlib.sha256(v.encode()).hexdigest()=='$PASSHASH_BEFORE' else 1)\""
chk "H9 wrong key → passwordError carries NO credential/key material" \
  "py \"import json,base64;d=json.load(open('$W/h_wrongkey.json'))['settings'];e=d.get('passwordError') or '';exit(0 if '$HOLOO_PASS' not in e and base64.b64encode('$HOLOO_PASS'.encode()).decode() not in e else 1)\""
# missing key → explicit error, still not wiped
rm -f "$KEYF"
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_nokey.json
chk "H9 missing key → explicit Persian passwordError" \
  "py \"import json;d=json.load(open('$W/h_nokey.json'))['settings'];exit(0 if d.get('passwordError') and 'کلید' in d['passwordError'] and d['hasPassword'] else 1)\""
chk "H9 missing key → stored value still intact" \
  "py \"import sqlite3,hashlib;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT value FROM Config WHERE key='holoo.password'\\\").fetchone()[0];exit(0 if hashlib.sha256(v.encode()).hexdigest()=='$PASSHASH_BEFORE' else 1)\""
# restore the REAL key → everything works again, no re-entry needed
mv "$KEYF.orig" "$KEYF"
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_keyback.json
chk "H9 key restored → configured again, no error" \
  "py \"import json;d=json.load(open('$W/h_keyback.json'))['settings'];exit(0 if d['configured'] and not d.get('passwordError') and d['hasPassword'] else 1)\""

# restart → settings survive (envelope + key both persist)
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_restart.json
chk "H9 settings survive a panel restart (configured, no error)" \
  "py \"import json;d=json.load(open('$W/h_restart.json'))['settings'];exit(0 if d['configured'] and d['hasPassword'] and not d.get('passwordError') else 1)\""
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/h_test_restart.json
chk "H9 login still works after restart (decrypt ok)" \
  "py \"import json;d=json.load(open('$W/h_test_restart.json'));exit(0 if d['ok'] else 1)\""

# legacy Base64 (≤ v1.9.15-dev.1) → atomic migration on first read
HOLOO_PASS_B64_H9=$(python3 -c "import base64;print(base64.b64encode('$HOLOO_PASS'.encode()).decode())")
python3 -c "import sqlite3;c=sqlite3.connect('$DB');c.execute(\"UPDATE Config SET value='$HOLOO_PASS_B64_H9' WHERE key='holoo.password'\");c.commit()"
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_legacy.json
chk "H9 legacy Base64 migrated to enc1 on first read" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT value FROM Config WHERE key='holoo.password'\\\").fetchone()[0];exit(0 if v.startswith('enc1:') else 1)\""
chk "H9 legacy migration keeps the admin view healthy (configured, no error)" \
  "py \"import json;d=json.load(open('$W/h_legacy.json'))['settings'];exit(0 if d['configured'] and d['hasPassword'] and not d.get('passwordError') else 1)\""
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/h_test_legacy.json
chk "H9 migrated value still logs in (wire form preserved)" \
  "py \"import json;d=json.load(open('$W/h_test_legacy.json'));exit(0 if d['ok'] else 1)\""

# upgrade re-run → the key file is kept byte-identical
KEYHASH_UP=$(sha256sum "$KEYF" | cut -d' ' -f1)
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/upgrade.log 2>&1)
chk "H9 upgrade install exit 0" "[ -f $W/inst/medpad-config.json ]"
chk "H9 upgrade keeps the key file byte-identical" "[ '$KEYHASH_UP' = '$(sha256sum "$KEYF" | cut -d' ' -f1)' ]"
chk "H9 upgrade re-applies owner-only mode (600) on the key" "[ \"\$(stat -c %a '$KEYF')\" = '600' ]"
chk "H9 upgrade keeps the key inside panel/db (not recreated elsewhere)" "[ -f '$KEYF' ] && [ -z \"\$(find $W/inst -name 'holoo.key' -not -path '$KEYF' -print -quit)\" ]"
chk "H9 upgrade keeps the encrypted password in the DB" \
  "py \"import sqlite3;c=sqlite3.connect('$DB');v=c.execute(\\\"SELECT value FROM Config WHERE key='holoo.password'\\\").fetchone()[0];exit(0 if v.startswith('enc1:') else 1)\""
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot3.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
curl -s -b $W/cj $BASE/api/admin/holoo/settings -o $W/h_afterupgrade.json
chk "H9 after upgrade: settings still configured, no error" \
  "py \"import json;d=json.load(open('$W/h_afterupgrade.json'))['settings'];exit(0 if d['configured'] and d['hasPassword'] and not d.get('passwordError') else 1)\""

# ── H8: bundle artifacts ─────────────────────────────────────────────
echo "== H8: bundle artifacts =="
B="$PKG/app/panel/.next"
H8_OK=0
for r in settings test sync status products; do [ -f "$B/standalone/.next/server/app/api/admin/holoo/$r/route.js" ] || H8_OK=1; done
chk "H8 the 5 holoo API routes ship in the installed bundle" "[ '$H8_OK' = '0' ]"
chk "H8 holoo tab markers in client chunks" \
  "grep -rq 'هلو (حسابداری)' $B/static --include='*.js'"
chk "H8 profile financial block marker in chunks" \
  "grep -rq 'اطلاعات مالی هلو' $B/static --include='*.js'"

echo
echo "== RESULT: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ "$FAIL" = '0' ]
