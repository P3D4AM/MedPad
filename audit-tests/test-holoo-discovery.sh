#!/usr/bin/env bash
# test-holoo-discovery.sh — HOLOO-LIVE-API-DISCOVERY (Nov 2026): offline
# proof of the discovery-layer claims in docs/HOLOO-LIVE-API-DISCOVERY.md.
#
# Runs the REAL installed bundle against the EXISTING explicit local mock
# (audit-tests/holoo-mock.mjs — NOT modified by this suite) and locks the
# following findings WITHOUT any real Holoo connection, WITHOUT touching
# the sync engine, and WITHOUT any real customer data (every record is
# synthetic — bBAP… prefixes are invented):
#
#   D0  fresh install + boot + admin login + mock up
#   D1  DOCUMENTED INVOICE CONTRACT (official PDF 2020/2021 + docx-era
#       clients): mock serves {"invoice":[{Code, Type, SanadCode,
#       CustomerName, CustomerErpCode, Date, Time, SumNaghd, SumNesiyeh,
#       SumDiscount, SumCheck, SumLevy, SumScot, SumPrice, ErpCode}]} →
#       the panel's read-only probe must unwrap wrapper `invoice` and see
#       the documented keys — i.e. IF the live service answers the
#       filtered GET /Invoice/Invoice probe with the documented shape,
#       MedPad's extractor is ALREADY compatible (no code change needed).
#   D2  DOCUMENTED GetCustPay CONTRACT: mock serves {"report":[{Code,
#       Name, Price, Date, Mandeh}]} → probe unwraps wrapper `report`
#       with the documented payment fields (§۳.۶ of the discovery doc).
#   D3  LIVE EMPTY-RESPONSE CLASSIFICATION (§۴.۳): the owner's live probe
#       observed HTTP 200 + an EMPTY OBJECT for GetCustPay. Mock replays
#       exactly that ({}) and the array-empty variant ({"report":[]}) →
#       the probe must classify them as a REAL EMPTY RESULT (ok=true,
#       count=0, kind=unrecognized/wrapped) — NOT as a parsing failure
#       (invalid-response) and NOT as an HTTP error.
#   D4  LIVE-SCALE PARITY (§۵): 2,294 synthetic customers + 414 synthetic
#       products with the LIVE wrappers (Customer / product) → probe
#       counts exactly 2294 / 414 and reports pagination.detected=false
#       (no page/total signals) — the offline replica of the owner's
#       live observation.
#   D5  PAGINATION POSITIVE CONTROL: the same list WITH pagination
#       metadata (TotalCount/Page/PageCount) → the detector MUST flip to
#       detected=true + suspectedIncomplete=true. This control is what
#       makes the D4 negative meaningful: the detector is not simply
#       asleep.
#   D6  DISCOVERY DOC VALIDATION: docs/HOLOO-LIVE-API-DISCOVERY.md —
#       every detailed endpoint section (۳.۱..۳.۶) carries the 10 required
#       attributes (Route/Method/params/Headers/response shape/Wrapper/
#       pagination/record count/verification status/limitations) and the
#       summary-table statuses use only the mandated vocabulary
#       (VERIFIED / PARTIALLY VERIFIED / NOT VERIFIED / FAILED; write
#       endpoints are policy-excluded خارج از دامنه).
#
# Note on mock-vs-live polarity (documented in the discovery doc): the
# mock models GET /Invoice as readable and GET /Invoice/Invoice as 404;
# the LIVE deployment showed the opposite (404 on /Invoice, timeout on
# /Invoice/Invoice). That difference is a mock fixture choice from Task
# 12 — it does not affect this suite, which asserts extraction behavior,
# not route availability.
#
# Deterministic: fresh install; curl + python3. No network beyond
# 127.0.0.1. Read-only towards the panel APIs (settings PUT + probe POST
# only — the sync engine is never triggered).
#
# Run:  bash test-holoo-discovery.sh [PKG_DIR]   (HL_DISC_WORK=<dir> overrides)
set -u
PKG="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PKG="$(cd "$PKG" 2>/dev/null && pwd)" || { echo "PKG_DIR not found"; exit 1; }
PASS=0; FAIL=0; SKIP=0
t()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
f()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
s()  { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }
chk() { if eval "$2" >/dev/null 2>&1; then t "$1"; else f "$1"; fi; }
py() { python3 -c "$1"; }

W="${HL_DISC_WORK:-/tmp/thldisc}"
rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
[ -f "$PKG/docs/HOLOO-LIVE-API-DISCOVERY.md" ] || { echo "discovery doc missing: $PKG/docs/HOLOO-LIVE-API-DISCOVERY.md"; exit 1; }
echo "== PKG: $PKG =="; echo "== WORKDIR: $W =="

PANEL_PORT=3353; RT_PORT=3356; BASE="http://127.0.0.1:$PANEL_PORT"
MOCK_PORT=8413
ADMIN_PASS='HLdisc123!'
HOLOO_PASS='HolooPass123!'
DB="$W/inst/panel/db/custom.db"
MOCK_STATE="$W/holoo-state.json"
MOCK_LOG="$W/holoo-requests.log"
DOC="$PKG/docs/HOLOO-LIVE-API-DISCOVERY.md"

cleanup() {
  (cd $W/inst 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

# ── the MOCK Holoo service (existing file, unmodified; ALL DATA SYNTHETIC) ─
# State: 2,294 customers + 414 products (the owner's LIVE counts, synthetic
# content), documented invoice + GetCustPay shapes via raw modes.
python3 - "$MOCK_STATE" << 'SEOF'
import json, sys
n_cust, n_prod = 2294, 414
customers = []
for i in range(1, n_cust + 1):
    customers.append({
        "ErpCode": f"bBAPd{i:05d}=", "Code": f"{i:05d}",
        "Name": f"مشتری آزمایشی {i}",
        "BedSarfasl": f"103{i:04d}", "BesSarfasl": f"401{i:04d}",
        "Mandeh": 0, "Credit": 0, "City": "مشهد", "Ostan": "خراسان رضوی",
        "IsActive": True,
    })
products = []
for i in range(1, n_prod + 1):
    products.append({
        "ErpCode": f"bBAPp{i:05d}=", "Code": f"{i:05d}",
        "Name": f"کالای آزمایشی {i}", "Few": "عدد",
        "BuyPrice": 100, "SellPrice": 150, "m_groupname": "آزمایشی",
    })
state = {
    "customers": customers,
    "products": products,
    "invoices": [],
    "payments": {},
    "modes": {"Invoice": "raw", "GetCustPay": "raw"},
    # the DOCUMENTED invoice-list shape (official PDF /Invoice section)
    "rawInvoice": {"invoice": [{
        "Code": "10001", "Type": "2", "SanadCode": "4501",
        "CustomerName": "مشتری آزمایشی ۱", "CustomerErpCode": "bBAPd00001=",
        "Date": "2026/11/10", "Time": "12:45:41",
        "SumNaghd": "0.0", "SumNesiyeh": "1000", "SumDiscount": "0.0",
        "SumCheck": "0.0", "SumLevy": "0.0", "SumScot": "0.0",
        "SumPrice": "3.02352E8", "ErpCode": "bBAPinv0001=",
    }]},
    # the DOCUMENTED GetCustPay shape (official PDF Report section)
    "rawGetCustPay": {"report": [{
        "Code": "00001", "Name": "مشتری آزمایشی ۱",
        "Price": "1530000.0", "Date": "2026/11/10", "Mandeh": "0.0",
    }]},
}
json.dump(state, open(sys.argv[1], 'w'), ensure_ascii=False)
print(f"state written: {n_cust} customers, {n_prod} products (ALL SYNTHETIC)")
SEOF
node "$PKG/audit-tests/holoo-mock.mjs" $MOCK_PORT "$MOCK_STATE" "$MOCK_LOG" > $W/mock.log 2>&1 &
MOCK_PID=$!
for i in $(seq 1 15); do curl -s -o /dev/null "http://127.0.0.1:$MOCK_PORT/TncHoloo/api/Customer" && break; sleep 1; done
chk "D0 mock up (401 without token is fine)" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$MOCK_PORT/TncHoloo/api/Customer | grep -q 401"

# ── D0: fresh install + boot + login ─────────────────────────────────
echo "== D0: fresh install + boot =="
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass "$ADMIN_PASS" > $W/install.log 2>&1)
chk "D0 install exit 0 (medpad-config.json)" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 40); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "D0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"
curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" -o $W/login.json
chk "D0 admin login" "py \"import json;exit(0 if json.load(open('$W/login.json'))['ok'] else 1)\""
curl -s -b $W/cj -X PUT $BASE/api/admin/holoo/settings -H 'content-type: application/json' \
  -d "{\"baseUrl\":\"http://127.0.0.1:$MOCK_PORT/TncHoloo/api\",\"dbname\":\"HolooTest\",\"username\":\"holoadmin\",\"password\":\"$HOLOO_PASS\",\"syncIntervalMin\":5}" -o $W/h_set.json
chk "D0 holoo settings saved (configured=true)" \
  "py \"import json;d=json.load(open('$W/h_set.json'))['settings'];exit(0 if d['configured'] else 1)\""
chk "D0 sync stays disabled in this suite (no background runs)" \
  "py \"import json;d=json.load(open('$W/h_set.json'))['settings'];exit(0 if d['enabled'] is False else 1)\""

# ── probe round 1: documented contracts + live-scale parity ──────────
echo "== D1+D2+D4: probe round 1 (documented shapes + live-scale lists) =="
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/probe1.json
chk "D0 probe round 1 ok (login + 5 endpoints)" \
  "py \"import json;d=json.load(open('$W/probe1.json'));exit(0 if d['ok'] and len(d['endpoints'])==5 else 1)\""

echo "== D1: documented invoice contract extraction =="
chk "D1 Invoice: 200 + wrapped {invoice:array(1)}" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Invoice'][0];exit(0 if e['ok'] and e['status']==200 and e['shape']=='object{invoice:array(1)}' and e['kind']=='wrapped' else 1)\""
chk "D1 Invoice: wrapper 'invoice' + count 1" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Invoice'][0];exit(0 if e['wrapper']=='invoice' and e['count']==1 else 1)\""
chk "D1 Invoice: documented fields visible in the probe's 12-key diagnostic window" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Invoice'][0];k=e.get('firstRecordKeys') or [];need=['Code','Type','SanadCode','CustomerName','CustomerErpCode','Date','Time','SumNaghd','SumNesiyeh','SumDiscount','SumCheck','SumLevy'];exit(0 if k[:12]==need else 1)\""
chk "D1 Invoice/Invoice: mock models the fallback as an honest 404 (live shows timeout — see doc §۴.۲)" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Invoice/Invoice'][0];exit(0 if (not e['ok']) and e.get('errorKind')=='http' and e.get('status')==404 else 1)\""

echo "== D2: documented GetCustPay contract extraction =="
chk "D2 GetCustPay: 200 + wrapped {report:array(1)}" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Report/GetCustPay'][0];exit(0 if e['ok'] and e['status']==200 and e['shape']=='object{report:array(1)}' and e['kind']=='wrapped' else 1)\""
chk "D2 GetCustPay: wrapper 'report' + documented fields (Code/Name/Price/Date/Mandeh)" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Report/GetCustPay'][0];k=e.get('firstRecordKeys') or [];exit(0 if e['wrapper']=='report' and e['count']==1 and all(x in k for x in ['Code','Name','Price','Date','Mandeh']) else 1)\""
chk "D2 the wire today still carries BOTH spellings with the ErpCode value (doc §۴.۳ evidence)" \
  "grep -q 'Report/GetCustPay?customerErpCode=bBAPd00001%3D&code=bBAPd00001%3D' $MOCK_LOG"

echo "== D4: live-scale parity (2294/414, wrappers, no pagination signals) =="
chk "D4 Customer: count exactly 2294 with live wrapper 'Customer'" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Customer'][0];exit(0 if e['ok'] and e['wrapper']=='Customer' and e['count']==2294 else 1)\""
chk "D4 Customer: pagination NOT detected (no page/total signals — live parity)" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Customer'][0];p=e.get('pagination') or {};exit(0 if p.get('detected') is False else 1)\""
chk "D4 Product: count exactly 414 with live lowercase wrapper 'product'" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Product'][0];exit(0 if e['ok'] and e['wrapper']=='product' and e['count']==414 else 1)\""
chk "D4 Product: pagination NOT detected" \
  "py \"import json;d=json.load(open('$W/probe1.json'));e=[x for x in d['endpoints'] if x['path']=='Product'][0];p=e.get('pagination') or {};exit(0 if p.get('detected') is False else 1)\""

# ── D3: live empty-response classification ───────────────────────────
echo "== D3: live empty-object classification =="
python3 - "$MOCK_STATE" << 'PEOF'
import json, sys
p = sys.argv[1]
st = json.load(open(p))
st['rawGetCustPay'] = {}          # the EXACT live observation: HTTP 200 + {}
json.dump(st, open(p, 'w'), ensure_ascii=False)
PEOF
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/probe2.json
chk "D3a live shape {} → 200 + REAL EMPTY RESULT (ok=true, count=0)" \
  "py \"import json;d=json.load(open('$W/probe2.json'));e=[x for x in d['endpoints'] if x['path']=='Report/GetCustPay'][0];exit(0 if e['ok'] and e['status']==200 and e['count']==0 else 1)\""
chk "D3a classified 'unrecognized' — NOT a parsing failure (invalid-response) nor HTTP error" \
  "py \"import json;d=json.load(open('$W/probe2.json'));e=[x for x in d['endpoints'] if x['path']=='Report/GetCustPay'][0];exit(0 if e['kind']=='unrecognized' else 1)\""
python3 - "$MOCK_STATE" << 'PEOF'
import json, sys
p = sys.argv[1]
st = json.load(open(p))
st['rawGetCustPay'] = {"report": []}   # the array-empty variant
json.dump(st, open(p, 'w'), ensure_ascii=False)
PEOF
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/probe3.json
chk "D3b live variant {report:[]} → 200 + wrapped with count 0" \
  "py \"import json;d=json.load(open('$W/probe3.json'));e=[x for x in d['endpoints'] if x['path']=='Report/GetCustPay'][0];exit(0 if e['ok'] and e['status']==200 and e['kind']=='wrapped' and e['wrapper']=='report' and e['count']==0 else 1)\""

# ── D5: pagination positive control ──────────────────────────────────
echo "== D5: pagination positive control =="
python3 - "$MOCK_STATE" << 'PEOF'
import json, sys
p = sys.argv[1]
st = json.load(open(p))
st.pop('rawInvoice', None)
st.pop('rawGetCustPay', None)
st['modes'] = {"Customer": "paginated"}   # full list + TotalCount/Page/PageCount
st['pagTotal'] = 6882                     # 3x the received count
json.dump(st, open(p, 'w'), ensure_ascii=False)
PEOF
curl -s -b $W/cj -X POST $BASE/api/admin/holoo/test -o $W/probe4.json
chk "D5 WITH pagination metadata the detector flips to detected=true" \
  "py \"import json;d=json.load(open('$W/probe4.json'));e=[x for x in d['endpoints'] if x['path']=='Customer'][0];p=e.get('pagination') or {};exit(0 if p.get('detected') is True else 1)\""
chk "D5 totalCount 6882 > received 2294 → suspectedIncomplete=true (D4 negative is meaningful)" \
  "py \"import json;d=json.load(open('$W/probe4.json'));e=[x for x in d['endpoints'] if x['path']=='Customer'][0];p=e.get('pagination') or {};exit(0 if p.get('totalCount')==6882 and p.get('suspectedIncomplete') is True else 1)\""

# ── D6: discovery doc validation ─────────────────────────────────────
echo "== D6: discovery doc contract validation =="
D6RC=0
python3 - "$DOC" > $W/d6.log 2>&1 << 'PEOF' || D6RC=1
import re, sys
doc = open(sys.argv[1], encoding='utf-8').read()
problems = []

# (1) every DETAILED endpoint section ۳.۱..۳.۶ carries the 10 required attributes
#     (sections ۳.۷..۳.۱۰ are compact summary sections by design — not checked here)
required = ["Route", "HTTP Method", "پارامتر", "Headers", "شکل پاسخ",
            "Wrapper", "صفحه‌بندی", "تعداد رکورد", "وضعیت تأیید", "محدودیت"]
sections = re.split(r'\n### ', doc)
detailed = []
for s in sections:
    m = re.match(r'۳\.(\d+)', s)
    if m and 1 <= int(m.group(1)) <= 6:
        detailed.append(s)
if len(detailed) != 6:
    problems.append(f"expected exactly 6 detailed sections (۳.۱..۳.۶), found {len(detailed)}")
for s in detailed:
    title = s.split('\n', 1)[0]
    for req in required:
        if req not in s:
            problems.append(f"section {title}: missing attribute '{req}'")

# (2) summary-table statuses (column 4) use only the mandated vocabulary
allowed = ["VERIFIED", "PARTIALLY VERIFIED", "NOT VERIFIED", "FAILED", "خارج از دامنه"]
rows = re.findall(r'^\|\s*\d+\s*\|[^|]*\|[^|]*\|\s*([^|]*)', doc, re.M)
if len(rows) < 10:
    problems.append(f"expected >= 10 summary rows, found {len(rows)}")
for r in rows:
    status = r.replace('*', '').strip()
    if not any(status == a or status.startswith(a + ' ') or status.startswith(a + '(') for a in allowed):
        problems.append(f"summary row status not in vocabulary: '{status}'")

# (3) the four live observations are recorded verbatim
for needle in ["۲٬۲۹۴", "۴۱۴", "404", "۳۰٬۰۰۰"]:
    if needle not in doc:
        problems.append(f"live observation missing from doc: '{needle}'")

if problems:
    print("D6 PROBLEMS:")
    for p in problems:
        print(" -", p)
    sys.exit(1)
print(f"D6 OK: {len(detailed)} detailed sections x 10 attributes, {len(rows)} summary rows status-checked")
PEOF
chk "D6 discovery doc: 10 attributes per endpoint + mandated status vocabulary + live numbers" "[ '$D6RC' = '0' ]"
[ "$D6RC" = '0' ] || cat $W/d6.log

# ── security sweep (no secrets anywhere in this suite's artifacts) ───
chk "SEC the plain Holoo password never appears in any probe response" \
  "! grep -q '$HOLOO_PASS' $W/probe1.json $W/probe2.json $W/probe3.json $W/probe4.json"

echo
echo "== RESULT: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ "$FAIL" = '0' ]
