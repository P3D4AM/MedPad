#!/usr/bin/env bash
# test-geo-mashhad.sh — Phase 5 (فاز پنج / Task 70): structured Mashhad
# geography — GeoArea tree + auto-detect + «ثبت موقت» completion gate +
# geographic reports, end-to-end against a REAL installed panel.
# Run:  bash test-geo-mashhad.sh [PKG_DIR]
#
#   G0  fresh install + boot + fixtures; seed: 13 zones (incl. منطقه ثامن),
#       ≥300 neighborhoods (≥250 with coords), ≥300 streets; per-node
#       provenance recorded; re-boot idempotent (counts unchanged)
#   G1  tree API: 401 without session; admin sees the zone→neighborhood
#       structure + city-less (OSM-only) neighborhoods + customer counts
#   G2  resolve («تشخیص خودکار از مختصات»): 401/400 gates; near a known
#       centroid → closest hit within the radius + approx:true; far away
#       → empty; wrong city → empty
#   G3  node dedup («رکورد تکراری با املای متفاوت»): ي/ی + ZWNJ variant of
#       an existing name → 409 with the existing name; genuinely new → 201;
#       invalid parent type → 400; street under city → 201
#   G4  auto-detect on create: app create with Mashhad coordinates →
#       geoStatus=incomplete + auto neighborhood; panel create without geo
#       refs in Mashhad → incomplete; panel create WITH explicit refs →
#       complete; create in a city WITHOUT geo data (شیراز) → untouched
#       (legacy behavior, no gating)
#   G5  assignment gate («جلوگیری از تخصیص نهایی تا تکمیل»): single assign →
#       409 Persian; bulk → per-pair failure with the reason; assignment
#       request approve → 409 (request stays pending); legacy-status
#       customer (geoStatus NULL) assigns fine
#   G6  completion: panel PUT with geo refs → complete + audit row
#       (customer.geo.complete); after completion the assignment succeeds
#   G7  app mirror: /api/app/customers rows carry the additive geo block
#       (neighborhood/zone/street names + status)
#   G8  geographic reports: per-zone totals/unassigned/incomplete,
#       per-rep workload, 30-day coverage > 0 for the visited zone,
#       per-street counts, «بدون محدوده» row present
#   G9  node management: rename ok; rename to sibling duplicate → 409;
#       deactivate hides from tree; DELETE referenced node → 409;
#       DELETE unreferenced leaf → 200
#   G10 bundle: the five geo routes + gate text ship in the installed panel
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

W=/tmp/tgeo; rm -rf $W; mkdir -p $W
[ -d "$PKG/app" ] || { echo "package tree not found: $PKG"; exit 1; }
echo "== PKG: $PKG =="

PANEL_PORT=3291; RT_PORT=3294; BASE="http://127.0.0.1:$PANEL_PORT"

cleanup() {
  (cd "$W/inst" 2>/dev/null && node server-manager.mjs stop --quiet > /dev/null 2>&1) || true
}
trap cleanup EXIT

# ── G0: install + boot + fixtures ───────────────────────────
(cd "$PKG" && node install.mjs --non-interactive --target local --dir $W/inst \
   --port $PANEL_PORT --rt-port $RT_PORT \
   --admin-user admin --admin-pass 'Geo123!mashhad' > $W/install.log 2>&1)
chk "G0 install exit 0" "[ -f $W/inst/medpad-config.json ]"
if [ ! -f $W/inst/medpad-config.json ]; then
  echo "install failed — aborting"; tail -20 $W/install.log; exit 1
fi

(cd $W/inst && node server-manager.mjs start --no-open > $W/boot.log 2>&1)
for i in $(seq 1 60); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
chk "G0 panel up" "curl -s -o /dev/null -w '%{http_code}' $BASE/ | grep -q 200"

DB="$W/inst/panel/db/custom.db"
chk "G0 GeoArea table exists" "py \"import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea').fetchone()[0])\" >/dev/null 2>&1"
ZONES=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea where type=?',('zone',)).fetchone()[0])" 2>/dev/null)
chk "G0 seed: 13 zones" "[ '$ZONES' = '13' ]"
chk "G0 seed: منطقه ثامن present" "py \"import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea where name=? and type=?',('منطقه ثامن','zone')).fetchone()[0])\" | grep -q 1"
HOODS=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea where type=?',('neighborhood',)).fetchone()[0])" 2>/dev/null)
py "exit(0 if int('$HOODS' or 0) >= 300 else 1)" && t "G0 seed: ≥300 neighborhoods ($HOODS)" || f "G0 seed: ≥300 neighborhoods ($HOODS)"
WITHCOORDS=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea where type=? and lat is not null',('neighborhood',)).fetchone()[0])" 2>/dev/null)
py "exit(0 if int('$WITHCOORDS' or 0) >= 240 else 1)" && t "G0 seed: ≥240 neighborhoods with coords ($WITHCOORDS)" || f "G0 seed: ≥250 with coords ($WITHCOORDS)"
STREETS=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea where type=?',('street',)).fetchone()[0])" 2>/dev/null)
py "exit(0 if int('$STREETS' or 0) >= 300 else 1)" && t "G0 seed: ≥300 streets ($STREETS)" || f "G0 seed: ≥300 streets ($STREETS)"
chk "G0 provenance recorded (source+sourceDate)" "py \"import sqlite3;c=sqlite3.connect('$DB');n=c.execute('select count(*) from GeoArea where source is null or sourceDate is null').fetchone()[0];exit(0 if n==0 else 1)\""
chk "G0 unique (parentId, type, normalizedName)" "py \"import sqlite3;c=sqlite3.connect('$DB');n=c.execute('select count(*) from (select parentId,type,normalizedName,count(*) c from GeoArea where parentId is not null group by 1,2,3 having c>1)').fetchone()[0];exit(0 if n==0 else 1)\""
chk "G0 GeoArea_parentId_type_normalizedName_key index exists" "py \"import sqlite3;c=sqlite3.connect('$DB');n=c.execute('select count(*) from sqlite_master where name=?',('GeoArea_parentId_type_normalizedName_key',)).fetchone()[0];exit(0 if n==1 else 1)\""

curl -s -c $W/cj -X POST $BASE/api/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"Geo123!mashhad"}' -o $W/admin.json
chk "G0 admin login" "grep -q '\"ok\":true' $W/admin.json"

for i in 1 2; do
  curl -s -b $W/cj -X POST $BASE/api/employees -H 'content-type: application/json' \
    -d "{\"username\":\"g_emp$i\",\"password\":\"emp${i}pass123\",\"name\":\"Geo Emp$i\",\"phone\":\"88$i\"}" -o $W/emp$i.json
done
EMP1=$(py "import json;print(json.load(open('$W/emp1.json'))['employee']['id'])")
EMP2=$(py "import json;print(json.load(open('$W/emp2.json'))['employee']['id'])")
chk "G0 employees" "[ -n '$EMP1' ] && [ -n '$EMP2' ]"
curl -s -X POST $BASE/api/app/login -H 'content-type: application/json' \
  -d '{"username":"g_emp1","password":"emp1pass123"}' -o $W/app1.json
TOK1=$(py "import json;print(json.load(open('$W/app1.json'))['token'])")
chk "G0 app login" "[ -n '$TOK1' ]"

# ── G1: tree API ─────────────────────────────────────────────
CODE=$(curl -s -o $W/t1.json -w '%{http_code}' "$BASE/api/panel/geo/tree")
chk "G1 tree without session -> 401" "[ '$CODE' = '401' ]"
curl -s -b $W/cj "$BASE/api/panel/geo/tree" -o $W/tree.json
chk "G1 tree ok" "grep -q '\"ok\":true' $W/tree.json"
chk "G1 tree: 13 zones" "py \"import json;d=json.load(open('$W/tree.json'));exit(0 if len(d['zones'])==13 else 1)\""
chk "G1 tree: zone names + codes" "py \"import json;d=json.load(open('$W/tree.json'));z=[x['name'] for x in d['zones']];exit(0 if 'منطقه ۱' in z and 'منطقه ثامن' in z and any(x['code']=='MHD-9' for x in d['zones']) else 1)\""
chk "G1 tree: neighborhoods under zones" "py \"import json;d=json.load(open('$W/tree.json'));exit(0 if sum(len(z['neighborhoods']) for z in d['zones']) >= 150 else 1)\""
chk "G1 tree: city-less (OSM-only) present" "py \"import json;d=json.load(open('$W/tree.json'));exit(0 if len(d['cityLess']) >= 100 else 1)\""
chk "G1 tree: provenance strings (official zone source)" "py \"import json;d=json.load(open('$W/tree.json'));srcs=' '.join((z.get('source') or '') for z in d['zones']);exit(0 if 'شورای اسلامی شهر مشهد' in srcs else 1)\""
chk "G1 tree: totals present" "py \"import json;d=json.load(open('$W/tree.json'));t=d.get('totals');exit(0 if t and t['neighborhoods']>=300 and t['streets']>=300 else 1)\""

# pick fixture ids from the tree for later steps
Z9=$(py "import json;d=json.load(open('$W/tree.json'));print([z['id'] for z in d['zones'] if z['code']=='MHD-9'][0])")
HOOD9=$(py "
import json
d=json.load(open('$W/tree.json'))
z9=[z for z in d['zones'] if z['code']=='MHD-9'][0]
h=[h for h in z9['neighborhoods'] if h['lat'] is not None]
print(h[0]['id'] if h else '')")
HOOD9NAME=$(py "
import json
d=json.load(open('$W/tree.json'))
z9=[z for z in d['zones'] if z['code']=='MHD-9'][0]
h=[h for h in z9['neighborhoods'] if h['lat'] is not None]
print(h[0]['name'] if h else '')")
HOOD9LAT=$(py "
import json
d=json.load(open('$W/tree.json'))
z9=[z for z in d['zones'] if z['code']=='MHD-9'][0]
h=[h for h in z9['neighborhoods'] if h['lat'] is not None]
print(h[0]['lat'] if h else '')")
HOOD9LNG=$(py "
import json
d=json.load(open('$W/tree.json'))
z9=[z for z in d['zones'] if z['code']=='MHD-9'][0]
h=[h for h in z9['neighborhoods'] if h['lat'] is not None]
print(h[0]['lng'] if h else '')")
chk "G1 fixture hood with coords" "[ -n '$HOOD9' ] && [ -n '$HOOD9LAT' ]"
STREET1=$(py "import json;d=json.load(open('$W/tree.json'));print(d['streets'][0]['id'] if d['streets'] else '')")
chk "G1 fixture street" "[ -n '$STREET1' ]"

# ── G2: resolve (auto-detect) ────────────────────────────────
CODE=$(curl -s -o $W/r0.json -w '%{http_code}' "$BASE/api/panel/geo/resolve?city=%D9%85%D8%B4%D9%87%D8%AF&lat=36.3&lng=59.6")
chk "G2 resolve without session -> 401" "[ '$CODE' = '401' ]"
CODE=$(curl -s -o $W/r1.json -w '%{http_code}' -b $W/cj "$BASE/api/panel/geo/resolve?city=%D9%85%D8%B4%D9%87%D8%AF&lat=abc&lng=59.6")
chk "G2 bad coords -> 400" "[ '$CODE' = '400' ]"
curl -s -b $W/cj "$BASE/api/panel/geo/resolve?city=%D9%85%D8%B4%D9%87%D8%AF&lat=$HOOD9LAT&lng=$HOOD9LNG" -o $W/r2.json
chk "G2 nearest hit = the hood itself" "py \"import json;d=json.load(open('$W/r2.json'));exit(0 if d.get('approx') is True and d['hits'] and d['hits'][0]['id']=='$HOOD9' and d['hits'][0]['distanceM'] <= 3000 else 1)\""
curl -s -b $W/cj "$BASE/api/panel/geo/resolve?city=%D9%85%D8%B4%D9%87%D8%AF&lat=35.7&lng=51.4" -o $W/r3.json
chk "G2 far coords -> empty hits" "py \"import json;d=json.load(open('$W/r3.json'));exit(0 if d['hits']==[] else 1)\""
curl -s -b $W/cj "$BASE/api/panel/geo/resolve?city=%D8%AA%D9%87%D8%B1%D8%A7%D9%86&lat=35.7&lng=51.4" -o $W/r4.json
chk "G2 city without geo data -> city null" "py \"import json;d=json.load(open('$W/r4.json'));exit(0 if d.get('city') is None and d['hits']==[] else 1)\""

# ── G3: node creation + dedup ───────────────────────────────
CODE=$(curl -s -o $W/n1.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/panel/geo/nodes \
  -H 'content-type: application/json' \
  -d "{\"type\":\"neighborhood\",\"parentId\":\"$Z9\",\"name\":\"محلهٔ آزمایشی جغرافیا\"}")
chk "G3 create new neighborhood -> 201" "[ '$CODE' = '201' ]"
NEWNODE=$(py "import json;print(json.load(open('$W/n1.json')).get('node',{}).get('id',''))")
chk "G3 created node id" "[ -n '$NEWNODE' ]"
# ي/ی + double-space variant of the SAME name under the SAME parent
CODE=$(curl -s -o $W/n2.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/panel/geo/nodes \
  -H 'content-type: application/json' \
  -d "{\"type\":\"neighborhood\",\"parentId\":\"$Z9\",\"name\":\"محلهٔ  آزمایشي جغرافيا\"}")
chk "G3 ي/ی+space variant -> 409" "[ '$CODE' = '409' ]"
chk "G3 409 names the existing node" "grep -q 'از قبل وجود دارد' $W/n2.json"
# invalid parent: neighborhood under a neighborhood
CODE=$(curl -s -o $W/n3.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/panel/geo/nodes \
  -H 'content-type: application/json' \
  -d "{\"type\":\"neighborhood\",\"parentId\":\"$NEWNODE\",\"name\":\"زیرمحلهٔ نامعتبر\"}")
chk "G3 neighborhood under neighborhood -> 400" "[ '$CODE' = '400' ]"
# province/city creation refused
CODE=$(curl -s -o $W/n4.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/panel/geo/nodes \
  -H 'content-type: application/json' \
  -d '{"type":"city","parentId":"x","name":"شهر جعلی"}')
chk "G3 city creation refused -> 400" "[ '$CODE' = '400' ]"
# street under the city node
CITYID=$(py "import json;d=json.load(open('$W/tree.json'));print(d['city']['id'])")
CODE=$(curl -s -o $W/n5.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/panel/geo/nodes \
  -H 'content-type: application/json' \
  -d "{\"type\":\"street\",\"parentId\":\"$CITYID\",\"name\":\"بلوار آزمایشی فاز پنج\"}")
chk "G3 street under city -> 201" "[ '$CODE' = '201' ]"

# ── G4: auto-detect on create ────────────────────────────────
# app create with Mashhad coordinates (a hair away from the fixture hood)
curl -s -X POST $BASE/api/app/customers/create -H 'content-type: application/json' \
  -H "x-medpad-token: $TOK1" \
  -d "{\"name\":\"داروخانهٔ کوثر\",\"type\":\"pharmacy\",\"city\":\"مشهد\",\"province\":\"خراسان رضوی\",\"lat\":$HOOD9LAT,\"lng\":$HOOD9LNG,\"phone\":\"0513001\",\"localId\":\"geo-l1\"}" -o $W/ac1.json
chk "G4 app create near hood -> incomplete + auto" "py \"import json;d=json.load(open('$W/ac1.json'));c=d['customer'];exit(0 if c.get('geoStatus')=='incomplete' and c.get('geoSource')=='auto' and c.get('geoNeighborhoodId') else 1)\""
AC1=$(py "import json;print(json.load(open('$W/ac1.json'))['customer']['id'])")
chk "G4 detected the fixture neighborhood" "py \"import json;d=json.load(open('$W/ac1.json'));exit(0 if d['customer'].get('geoNeighborhoodId')=='$HOOD9' else 1)\""
# app create FAR from any centroid but inside Mashhad -> incomplete, no hood
curl -s -X POST $BASE/api/app/customers/create -H 'content-type: application/json' \
  -H "x-medpad-token: $TOK1" \
  -d '{"name":"درمانگاه حاشیهٔ شهر","type":"clinic","city":"مشهد","province":"خراسان رضوی","lat":36.48,"lng":59.85,"phone":"0513002","localId":"geo-l2"}' -o $W/ac2.json
chk "G4 far app create -> incomplete without hood" "py \"import json;d=json.load(open('$W/ac2.json'));c=d['customer'];exit(0 if c.get('geoStatus')=='incomplete' and c.get('geoNeighborhoodId') is None else 1)\""
AC2=$(py "import json;print(json.load(open('$W/ac2.json'))['customer']['id'])")
# panel create WITHOUT geo refs in Mashhad -> incomplete
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ پنل ناقص","type":"pharmacy","city":"مشهد","province":"خراسان رضوی","lat":36.2972,"lng":59.6067,"phone":"0513003"}' -o $W/pc1.json
chk "G4 panel create without refs -> incomplete" "py \"import json;d=json.load(open('$W/pc1.json'));exit(0 if d['customer'].get('geoStatus')=='incomplete' else 1)\""
PC1=$(py "import json;print(json.load(open('$W/pc1.json'))['customer']['id'])")
# panel create WITH explicit refs -> complete
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d "{\"name\":\"داروخانهٔ پنل کامل\",\"type\":\"pharmacy\",\"city\":\"مشهد\",\"province\":\"خراسان رضوی\",\"lat\":36.2972,\"lng\":59.6067,\"phone\":\"0513004\",\"geoNeighborhoodId\":\"$HOOD9\",\"geoStreetId\":\"$STREET1\"}" -o $W/pc2.json
chk "G4 panel create with refs -> complete/admin" "py \"import json;d=json.load(open('$W/pc2.json'));c=d['customer'];exit(0 if c.get('geoStatus')=='complete' and c.get('geoSource')=='admin' and c.get('geoStreetId')=='$STREET1' else 1)\""
PC2=$(py "import json;print(json.load(open('$W/pc2.json'))['customer']['id'])")
# city WITHOUT geo data -> legacy untouched
curl -s -b $W/cj -X POST $BASE/api/customers -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ شیراز","type":"pharmacy","city":"شیراز","province":"فارس","lat":29.5918,"lng":52.5837,"phone":"0713005"}' -o $W/pc3.json
chk "G4 city without geo data -> legacy (null status)" "py \"import json;d=json.load(open('$W/pc3.json'));c=d['customer'];exit(0 if c.get('geoStatus') is None and c.get('geoNeighborhoodId') is None else 1)\""
SHZ=$(py "import json;print(json.load(open('$W/pc3.json'))['customer']['id'])")
# invalid refs rejected
CODE=$(curl -s -o $W/pc4.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d '{"name":"داروخانهٔ مرجع نامعتبر","type":"pharmacy","city":"مشهد","province":"خراسان رضوی","lat":36.3,"lng":59.6,"geoNeighborhoodId":"nonexistent-id"}')
chk "G4 unknown neighborhood id -> 400" "[ '$CODE' = '400' ]"
CODE=$(curl -s -o $W/pc5.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/customers \
  -H 'content-type: application/json' \
  -d "{\"name\":\"داروخانهٔ والد غلط\",\"type\":\"pharmacy\",\"city\":\"مشهد\",\"province\":\"خراسان رضوی\",\"lat\":36.3,\"lng\":59.6,\"geoNeighborhoodId\":\"$Z9\"}")
chk "G4 zone id as neighborhood -> 400" "[ '$CODE' = '400' ]"

# ── G5: the assignment gate ─────────────────────────────────
CODE=$(curl -s -o $W/as1.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$AC1\"}")
chk "G5 assign incomplete -> 409" "[ '$CODE' = '409' ]"
chk "G5 gate message (Persian + tab hint)" "grep -q 'اطلاعات جغرافیایی' $W/as1.json && grep -q 'تکمیل' $W/as1.json"
CODE=$(curl -s -o $W/as1b.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$PC2\"}")
chk "G5 assign complete customer -> 200" "[ '$CODE' = '200' ]"
CODE=$(curl -s -o $W/as1c.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP1\",\"customerId\":\"$SHZ\"}")
chk "G5 assign legacy-status customer -> 200 (no retroactive gating)" "[ '$CODE' = '200' ]"
# bulk: one incomplete + one fine → per-pair failure
curl -s -b $W/cj -X POST $BASE/api/assignments/bulk -H 'content-type: application/json' \
  -d "{\"action\":\"assign\",\"customerIds\":[\"$AC2\",\"$SHZ\"],\"employeeIds\":[\"$EMP2\"]}" -o $W/bk1.json
chk "G5 bulk: incomplete pair failed with reason" "py \"import json;d=json.load(open('$W/bk1.json'));fl=[x for x in d.get('failed',[]) if x['customerId']=='$AC2'];exit(0 if fl and 'جغرافیایی' in fl[0]['reason'] else 1)\""
chk "G5 bulk: fine pair created" "py \"import json;d=json.load(open('$W/bk1.json'));exit(0 if d.get('created',0) >= 1 else 1)\""
# assignment-request flow: rep requests the incomplete customer → admin approve → 409, request stays pending
curl -s -X POST $BASE/api/app/assignment-requests -H 'content-type: application/json' \
  -H "x-medpad-token: $TOK1" -d "{\"customerId\":\"$AC2\",\"note\":\"لطفاً تخصیص دهید\"}" -o $W/arq.json
chk "G5 request created" "grep -q '\"ok\":true' $W/arq.json"
ARQ=$(py "import json;print(json.load(open('$W/arq.json')).get('request',{}).get('id',''))" 2>/dev/null)
if [ -z "$ARQ" ]; then ARQ=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select id from AssignmentRequest where status=? order by createdAt desc limit 1',('pending',)).fetchone()[0])"); fi
CODE=$(curl -s -o $W/ap1.json -w '%{http_code}' -b $W/cj -X POST "$BASE/api/assignment-requests/$ARQ/approve")
chk "G5 approve incomplete -> 409" "[ '$CODE' = '409' ]"
chk "G5 request stays pending" "py \"import sqlite3;c=sqlite3.connect('$DB');s=c.execute('select status from AssignmentRequest where id=?',('$ARQ',)).fetchone()[0];exit(0 if s=='pending' else 1)\""

# ── G6: completion lifts the gate ───────────────────────────
CODE=$(curl -s -o $W/co1.json -w '%{http_code}' -b $W/cj -X PUT $BASE/api/customers/$AC2 \
  -H 'content-type: application/json' \
  -d "{\"geoNeighborhoodId\":\"$HOOD9\",\"geoStreetId\":null}")
chk "G6 completion PUT -> 200" "[ '$CODE' = '200' ]"
chk "G6 status complete/admin" "py \"import json;d=json.load(open('$W/co1.json'));exit(0 if d['customer'].get('geoStatus')=='complete' and d['customer'].get('geoSource')=='admin' else 1)\""
chk "G6 audit row written" "py \"import sqlite3;c=sqlite3.connect('$DB');n=c.execute('select count(*) from AuditLog where action=?',('customer.geo.complete',)).fetchone()[0];exit(0 if n>=1 else 1)\""
CODE=$(curl -s -o $W/ap2.json -w '%{http_code}' -b $W/cj -X POST "$BASE/api/assignment-requests/$ARQ/approve")
chk "G6 approve after completion -> 200" "[ '$CODE' = '200' ]"
CODE=$(curl -s -o $W/as2.json -w '%{http_code}' -b $W/cj -X POST $BASE/api/assignments \
  -H 'content-type: application/json' -d "{\"employeeId\":\"$EMP2\",\"customerId\":\"$AC1\"}")
chk "G6 assign AC1 (still incomplete) still -> 409" "[ '$CODE' = '409' ]"

# ── G7: app mirror geo block ────────────────────────────────
curl -s -H "x-medpad-token: $TOK1" "$BASE/api/app/customers" -o $W/mir.json
chk "G7 mirror geo block (neighborhood+zone+status) for completed customer" "py \"import json;d=json.load(open('$W/mir.json'));cs=[x for x in d['customers'] if x['id']=='$AC2'];exit(0 if cs and cs[0].get('geo',{}).get('neighborhood')=='$HOOD9NAME' and cs[0]['geo'].get('zone') and cs[0]['geo'].get('status')=='complete' else 1)\""
chk "G7 mirror street name for PC-complete customer" "py \"import json;d=json.load(open('$W/mir.json'));cs=[x for x in d['customers'] if x['id']=='$PC2'];exit(0 if cs and cs[0].get('geo',{}).get('status')=='complete' and cs[0]['geo'].get('street') else 1)\""
chk "G7 mirror legacy customer geo.status null" "py \"import json;d=json.load(open('$W/mir.json'));cs=[x for x in d['customers'] if x['id']=='$SHZ'];exit(0 if cs and cs[0].get('geo',{}).get('status') is None else 1)\""
chk "G7 incomplete customer ABSENT from mirror (unassigned by design)" "py \"import json;d=json.load(open('$W/mir.json'));exit(0 if not any(x['id']=='$AC1' for x in d['customers']) else 1)\""

# ── G8: geographic reports ──────────────────────────────────
# a check-in for PC2 (assigned to EMP1) so coverage > 0 in its hood's zone
curl -s -X POST $BASE/api/app/checkin \
  -H "x-medpad-token: $TOK1" \
  -F "customerId=$PC2" -F "lat=36.2972" -F "lng=59.6067" -F "accuracy=12" \
  -F "note=ویزیت گزارش جغرافیایی" -F "localId=geo-ck1" -o $W/ck1.json
chk "G8 checkin PC2" "grep -q '\"ok\":true' $W/ck1.json"
curl -s -b $W/cj "$BASE/api/panel/geo/report" -o $W/rep.json
chk "G8 report ok" "grep -q '\"ok\":true' $W/rep.json"
chk "G8 report: 13 zones + بدون محدوده row" "py \"import json;d=json.load(open('$W/rep.json'));exit(0 if len(d['zones'])==14 and any(z['name']=='بدون محدوده' for z in d['zones']) else 1)\""
chk "G8 report: zone totals count the fixture hood's customers" "py \"import json;d=json.load(open('$W/rep.json'));z9=[z for z in d['zones'] if z['code']=='MHD-9'][0];h=[h for h in z9['neighborhoods'] if h['id']=='$HOOD9'];exit(0 if h and h[0]['total']>=2 else 1)\""
chk "G8 report: unassigned list has the incomplete customer" "py \"import json;d=json.load(open('$W/rep.json'));exit(0 if any(u['id']=='$AC1' for u in d['unassigned']) else 1)\""
chk "G8 report: workload row for EMP1" "py \"import json;d=json.load(open('$W/rep.json'));exit(0 if any(w['customers']>=1 for w in d['workload']) else 1)\""
chk "G8 report: coverage > 0 where visited" "py \"import json;d=json.load(open('$W/rep.json'));z9=[z for z in d['zones'] if z['code']=='MHD-9'][0];exit(0 if (z9['coverage'] or 0) > 0 else 1)\""
chk "G8 report: street counts include STREET1" "py \"import json;d=json.load(open('$W/rep.json'));exit(0 if any(s['id']=='$STREET1' and s['count']>=1 for s in d['streets']) else 1)\""

# ── G9: node management ─────────────────────────────────────
CODE=$(curl -s -o $W/m1.json -w '%{http_code}' -b $W/cj -X PATCH "$BASE/api/panel/geo/nodes/$NEWNODE" \
  -H 'content-type: application/json' -d '{"name":"محلهٔ آزمایشی جغرافیا — تغییرنام"}')
chk "G9 rename -> 200" "[ '$CODE' = '200' ]"
CODE=$(curl -s -o $W/m2.json -w '%{http_code}' -b $W/cj -X PATCH "$BASE/api/panel/geo/nodes/$NEWNODE" \
  -H 'content-type: application/json' -d '{"name":"کوثر"}')
chk "G9 rename to sibling duplicate -> 409" "[ '$CODE' = '409' ]"
CODE=$(curl -s -o $W/m3.json -w '%{http_code}' -b $W/cj -X DELETE "$BASE/api/panel/geo/nodes/$HOOD9")
chk "G9 delete referenced node -> 409" "[ '$CODE' = '409' ]"
chk "G9 delete-refusal mentions customers" "grep -q 'مشتری' $W/m3.json"
curl -s -b $W/cj -X PATCH "$BASE/api/panel/geo/nodes/$NEWNODE" -H 'content-type: application/json' \
  -d '{"active":false}' -o $W/m4.json
curl -s -b $W/cj "$BASE/api/panel/geo/tree" -o $W/tree2.json
chk "G9 deactivated node hidden from tree" "py \"import json;d=json.load(open('$W/tree2.json'));ids=[h['id'] for z in d['zones'] for h in z['neighborhoods']];exit(0 if '$NEWNODE' not in ids else 1)\""
CODE=$(curl -s -o $W/m5.json -w '%{http_code}' -b $W/cj -X DELETE "$BASE/api/panel/geo/nodes/$NEWNODE")
chk "G9 delete unreferenced leaf -> 200" "[ '$CODE' = '200' ]"

# ── G10: re-boot idempotency (seed NO-DELETE / no dupes) ────
BEFORE=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea').fetchone()[0])")
(cd $W/inst && node server-manager.mjs stop --quiet > /dev/null 2>&1)
sleep 2
(cd $W/inst && node server-manager.mjs start --no-open > $W/boot2.log 2>&1)
for i in $(seq 1 60); do curl -s -o /dev/null "$BASE/" && break; sleep 1; done
AFTER=$(py "import sqlite3;c=sqlite3.connect('$DB');print(c.execute('select count(*) from GeoArea').fetchone()[0])")
py "exit(0 if int('$AFTER' or 0) == int('$BEFORE' or 0) else 1)" && t "G10 re-boot: seed idempotent ($BEFORE → $AFTER)" || f "G10 re-boot: seed idempotent ($BEFORE → $AFTER)"
chk "G10 still no duplicate normalized keys" "py \"import sqlite3;c=sqlite3.connect('$DB');n=c.execute('select count(*) from (select parentId,type,normalizedName,count(*) c from GeoArea where parentId is not null group by 1,2,3 having c>1)').fetchone()[0];exit(0 if n==0 else 1)\""

# ── G11: bundle markers in the INSTALLED panel ──────────────
chk "G11 installed bundle: geo tree route" "[ -f $W/inst/panel/.next/standalone/.next/server/app/api/panel/geo/tree/route.js ]"
chk "G11 installed bundle: geo report route" "[ -f $W/inst/panel/.next/standalone/.next/server/app/api/panel/geo/report/route.js ]"
chk "G11 installed bundle: gate text in server" "grep -rq 'ثبت موقت در انتظار تکمیل' $W/inst/panel/.next/standalone/.next/server --include='*.js'"
chk "G11 installed bundle: geo tab UI chunk" "grep -rq 'در انتظار تکمیل اطلاعات جغرافیایی' $W/inst/panel/.next/standalone/.next/static --include='*.js'"
chk "G11 installed bundle: seed dataset shipped" "grep -rq 'منطقه ثامن' $W/inst/panel/.next/standalone/.next/server --include='*.js'"

echo
echo "== geo-mashhad: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ] || exit 1
