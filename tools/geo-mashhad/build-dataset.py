#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MedPad — Mashhad geographic dataset builder (Phase 5 / فاز پنج).

Merges two independent, DATED sources into the seed module consumed by the
panel boot seeder (src/lib/geo-seed.ts imports src/lib/geo-mashhad.ts):

  1. OFFICIAL zone→neighborhood lists (the OPERATIVE 13-region division —
     «مرزبندی ۱۳گانه کماکان ملاک عمل است»; the 17-region reorganization of
     مصوبه ۱۹۶۶۳/۹۶/۵/ش has NOT been executed):
       source: mashhad-city.ir/mahale170 (retrieved 2026-11-18), itself based
       on Mashhad City Council resolution ۱۹۶۶۳/۹۶/۵/ش (۱۳۹۶/۱۱/۱۹).
     Zones: منطقه ۱ … منطقه ۱۲ + منطقه ثامن (the shrine core).
     NOTE: zone→neighborhood lists are typed from that page AS PUBLISHED.

  2. OpenStreetMap place nodes (neighborhood centroids) + named primary/
     secondary highways (main streets) — Overpass extracts under raw/
       source: © OpenStreetMap contributors, ODbL (extracted 2026-11-18).

Merge rules (documented, deterministic):
  * normalization mirrors src/lib/iran-cities.ts normalizeFa exactly:
    Persian digits→ASCII, ZWNJ/BOM/RLM strip, ي→ی, ك→ک, whitespace collapse.
  * official names: a parenthetical suffix «نام (نام‌دیگر)» is split into the
    canonical name + an ALIAS match key; a bare standalone entry always wins
    over an alias of the same normalized text.
  * an OSM place matching an official name or alias (normalized) merges INTO
    that neighborhood (gains coordinates; src 'both' if the official entry
    also existed, else 'osm').
  * an OSM place with no official match becomes its own neighborhood with
    zone=null (parent = the city directly — the model allows a neighborhood
    under a zone OR under the city; unassigned ones surface honestly in the
    reports under «بدون محدوده»).
  * the same name appearing in TWO different zones' official lists creates
    TWO nodes (per-parent uniqueness — city-neighborhood names legitimately
    repeat across zones, exactly like city names across provinces); OSM
    coordinates attach to the first.
  * streets keep ONLY the name + class (primary|secondary); they seed as
    children of the city (a street spans neighborhoods; assigning it to a
    single one would be fabricated data). Street coverage is as complete as
    the extracts in raw/ (some eastern/southern quadrants timed out on the
    mirror — documented, refreshable by re-running the Overpass queries).

Output: a TypeScript module (deterministic ordering: zones by number,
neighborhoods alphabetically within zone then zone-less, streets
alphabetically). Re-runnable: python3 build-dataset.py [--out PATH]
"""
import json, glob, argparse, re, sys, os

RETRIEVED = "2026-11-18"
OFFICIAL_SRC = ("مصوبه شورای اسلامی شهر مشهد ۱۹۶۶۳/۹۶/۵/ش — مرزبندی ۱۳گانه "
                "(mashhad-city.ir/mahale170، بازیابی ۱۴۰۵-۰۸-۲۷)")
OSM_SRC = "© OpenStreetMap contributors (ODbL) — استخراج Overpass"

# ── official 13-zone structure (typed from mashhad-city.ir, retrieved above) ──
ZONES = [
    ("منطقه ۱", "MHD-1", [
        "کوی دکتری", "راهنمایی", "فلسطین", "سجاد", "ارشاد", "آبکوه (سعدآباد)",
        "گوهرشاد", "کلاهدوز", "سعدآباد", "صاحب‌الزمان", "احمدآباد",
    ]),
    ("منطقه ۲", "MHD-2", [
        "فرامرز عباسی", "بهاران", "کارخانه قند (جانبازان)", "عامل",
        "حضرت ابوطالب (گلبرگ)", "حضرت عبدالمطلب (قائم ع)", "کوی امیرالمؤمنین",
        "فدک", "هدایت", "ایثارگران", "شفا", "سمزقند", "شهید هنرور",
        "شهید مطهری", "حسین باشی", "آیت‌الله عبادی", "کاشانی", "زرکش",
        "مشهدقلی", "نوده", "حجت", "خین عرب", "قدس", "نوید", "اسماعیل‌آباد",
    ]),
    ("منطقه ۳", "MHD-3", [
        "خیرآباد", "فاطمیه", "گاز", "مسلم", "رسالت", "راه‌آهن", "بیلان",
        "بهمن", "خواجه ربیع", "مهر مادر", "مهر مادر شمالی ۱", "قرقی",
        "مهرگان", "عباس‌آباد", "دروی", "سیس‌آباد", "طبرسی شمالی",
    ]),
    ("منطقه ۴", "MHD-4", [
        "ایثار", "فجر", "وحید", "تلگرد", "کوی مهدی", "گلشور (بهشت)",
        "طلاب", "پنج‌تن", "التیمور", "رده", "شهید قربانی",
    ]),
    ("منطقه ۵", "MHD-5", [
        "مهدی‌آباد", "ثامن", "امیرالمؤمنین", "شهید آوینی", "نیزه",
        "مهرآباد", "جلالیه", "رضائیه", "سجادیه", "کوی سلمان", "حسین‌آباد",
    ]),
    ("منطقه ۶", "MHD-6", [
        "شهرک شیرین", "آقا مصطفی خمینی", "امیرآباد", "شهید معقول",
        "کشاورز", "شهید رجایی", "پورسینا", "کنه بیست", "اروند", "انصار",
        "شهید باهنر", "شهید بسک‌آبادی", "چهنو", "مصلی", "کارمندان دوم",
        "کارمندان اول", "شهید رستمی", "شیرودی", "کوی ۲۲ بهمن",
    ]),
    ("منطقه ۷", "MHD-7", [
        "کوی پلیس", "کوشش", "مقدم", "۱۷ شهریور", "کارگران",
        "پروین اعتصامی", "طرق ۲", "زارعین", "ابوذر", "انقلاب",
        "بهارستان", "خلج", "قائم", "المهدی", "سیدی", "عسکریه", "عنصری",
    ]),
    ("منطقه ۸", "MHD-8", [
        "سرشور", "جنت", "آبشار", "بهشتی", "امام خمینی", "امام رضا",
    ]),
    ("منطقه ۹", "MHD-9", [
        "طالقانی", "زکریا", "سرافرازان", "چهارچشمه", "کوثر", "رضاشهر",
        "نوفل لوشاتو", "نیروی هوایی", "شقایق ۱", "شقایق ۲", "گلدیس",
        "ولیعصر", "لادن", "اقبال", "آب و برق", "هنرستان", "هاشمیه",
    ]),
    ("منطقه ۱۰", "MHD-10", [
        "رازی", "شریعتی", "شاهد", "فرهنگیان", "رسالت", "لشگر", "امامیه",
        "حجاب", "ایثارگران", "استاد یوسفی", "امام هادی", "خاتم الانبیاء",
    ]),
    ("منطقه ۱۱", "MHD-11", [
        "آزادشهر", "سیدرضی", "دانش‌آموز", "دانشجو شریف", "فارغ‌التحصیلان",
        "تربیت", "شهید رضوی", "زیباشهر",
    ]),
    ("منطقه ۱۲", "MHD-12", [
        "وکیل‌آباد", "امیریه", "الهیه", "مجیدیه", "جاهدشهر", "نمایشگاه",
        "صفی‌آباد", "رحمانیه", "نقویه", "صادقیه", "کلاته برفی",
        "چهاربرج", "فردوسی",
    ]),
    ("منطقه ثامن", "MHD-SAMEN", [
        "بالاخیابان", "پایین‌خیابان",
    ]),
]

def normalize_fa(s: str) -> str:
    """Mirror of src/lib/iran-cities.ts normalizeFa (same order & rules)."""
    out = []
    for ch in s:
        o = ord(ch)
        if 0x06F0 <= o <= 0x06F9:            # Persian digits → ASCII
            out.append(chr(o - 0x06F0 + 0x30))
        elif ch in "\u200c\u200f\u200e\ufeff":  # ZWNJ / RLM / LRM / BOM → strip
            continue
        elif ch == "ي":
            out.append("ی")
        elif ch == "ك":
            out.append("ک")
        else:
            out.append(ch)
    return re.sub(r"\s+", " ", "".join(out)).strip()

def split_paren(name: str):
    """«نام (نام‌دیگر)» → (canonical, alias|None); standalone wins over alias."""
    m = re.match(r"^(.*?)\s*\((.*?)\)\s*$", name)
    if m:
        return m.group(1).strip(), m.group(2).strip()
    return name.strip(), None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None, help="output .ts path")
    ap.add_argument("--raw", default=os.path.join(os.path.dirname(__file__), "raw"))
    args = ap.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))

    # ── 1. official entries (dedup inside a zone, split aliases) ──────────
    official = []          # {name, zone, src:'official'}
    alias_index = {}       # normAlias → official entry (first wins)
    name_index = {}        # normName  → official entry (first wins)
    for zname, zcode, hoods in ZONES:
        seen_in_zone = set()
        for h in hoods:
            canon, alias = split_paren(h)
            key = normalize_fa(canon)
            if not key or key in seen_in_zone:
                continue
            seen_in_zone.add(key)
            entry = {"name": canon, "zone": zname, "zoneCode": zcode}
            official.append(entry)
            if key not in name_index:
                name_index[key] = entry
            if alias:
                akey = normalize_fa(alias)
                if akey and akey not in alias_index:
                    alias_index[akey] = entry

    # ── 2. OSM places (name → coords; first node wins, nodes over ways) ──
    osm_places = {}        # normName → {name, lat, lng}
    for f in sorted(glob.glob(os.path.join(args.raw, "op*.json"))):
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception as e:
            print(f"  (skip {os.path.basename(f)}: {e})")
            continue
        for e in d.get("elements", []):
            t = e.get("tags") or {}
            nm = t.get("name")
            if not nm:
                continue
            c = e.get("center") or e
            lat, lng = c.get("lat"), c.get("lon")
            if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                continue
            k = normalize_fa(nm)
            if not k or k in osm_places:
                continue
            osm_places[k] = {"name": nm, "lat": round(lat, 5), "lng": round(lng, 5)}

    # ── 3. merge ───────────────────────────────────────────────────────────
    neighborhoods = []     # ordered result
    merged = {}            # normKey → result entry (per (zone,name))
    osm_matched = set()
    for entry in official:
        k = normalize_fa(entry["name"])
        hit = osm_places.get(k) or osm_places.get(normalize_fa(k))
        alt = alias_index.get(k)  # official name may itself be an alias elsewhere — no-op here
        if not hit:
            # try the official entry's alias against OSM names
            canon, alias = split_paren(entry["name"])
            if alias:
                ak = normalize_fa(alias)
                if ak in osm_places:
                    hit = osm_places[ak]
        rec = {"name": entry["name"], "zone": entry["zone"], "zoneCode": entry["zoneCode"]}
        if hit:
            rec.update(lat=hit["lat"], lng=hit["lng"])
            rec["src"] = "both"
            osm_matched.add(normalize_fa(hit["name"]))
        else:
            rec["src"] = "official"
        neighborhoods.append(rec)
    # zone names normalized — OSM ALSO maps the municipal districts
    # themselves as place nodes («منطقه ۱» … «منطقه ثامن»); those ARE the
    # zones, not neighborhoods, and must never seed as city-level
    # neighborhoods (they would collide with the zone nodes and corrupt
    # their provenance through the upsert).
    zone_keys = {normalize_fa(z[0]) for z in ZONES}
    # OSM-only places (no official match by name or alias)
    for k, p in sorted(osm_places.items(), key=lambda kv: kv[1]["name"]):
        if k in osm_matched or k in name_index or k in alias_index:
            continue
        if k in zone_keys:
            continue
        neighborhoods.append({
            "name": p["name"], "zone": None, "zoneCode": None,
            "lat": p["lat"], "lng": p["lng"], "src": "osm",
        })

    # ── 4. streets (name + class; primary wins over secondary on dup) ────
    RANK = {"primary": 0, "primary_link": 1, "secondary": 2, "secondary_link": 3}
    streets = {}
    for f in sorted(glob.glob(os.path.join(args.raw, "st*.json"))):
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        for e in d.get("elements", []):
            t = e.get("tags") or {}
            nm = t.get("name")
            hw = t.get("highway")
            if not nm or hw not in RANK:
                continue
            k = normalize_fa(nm)
            if k not in streets or RANK[hw] < RANK[streets[k]["cls"]]:
                streets[k] = {"name": nm, "cls": hw}
    street_list = sorted(streets.values(), key=lambda s: s["name"])

    # counts for the console summary
    with_coords = sum(1 for n in neighborhoods if "lat" in n)
    by_src = {}
    for n in neighborhoods:
        by_src[n["src"]] = by_src.get(n["src"], 0) + 1

    # ── 5. emit TypeScript ─────────────────────────────────────────────────
    def esc(s: str) -> str:
        return json.dumps(s, ensure_ascii=False)

    lines = []
    lines.append("/**")
    lines.append(" * Mashhad structured geography — GENERATED SEED DATASET (Phase 5 / فاز پنج).")
    lines.append(" *")
    lines.append(" * DO NOT HAND-EDIT — regenerate with tools/geo-mashhad/build-dataset.py")
    lines.append(" * (raw Overpass extracts live in tools/geo-mashhad/raw/; the official")
    lines.append(" * 13-zone lists are embedded in that script, with sources).")
    lines.append(" *")
    lines.append(f" * Sources (both DATED, recorded per node in the DB seed):")
    lines.append(f" *  - zones & official neighborhood lists: {OFFICIAL_SRC}")
    lines.append(f" *  - coordinates & streets: {OSM_SRC} {RETRIEVED}")
    lines.append(" *")
    lines.append(" * Accuracy notes (deliberate, honest):")
    lines.append(" *  - neighborhood coordinates are place-node CENTROIDS, not boundaries;")
    lines.append(" *    «auto-detect from coordinates» = nearest centroid within a documented")
    lines.append(" *    radius — an approximation, labeled as such in the UI.")
    lines.append(" *  - OSM-only neighborhoods carry zone=null (no fabricated zone mapping);")
    lines.append(" *    they surface in reports under «بدون محدوده».")
    lines.append(" *  - streets are city-level (a street spans neighborhoods; no fake")
    lines.append(" *    street→neighborhood assignment is made).")
    lines.append(" */")
    lines.append("")
    lines.append("export interface GeoSeedZone {")
    lines.append("  name: string")
    lines.append("  code: string")
    lines.append("}")
    lines.append("")
    lines.append("export interface GeoSeedNeighborhood {")
    lines.append("  name: string")
    lines.append("  zone: string | null // null = OSM-only, no official zone mapping")
    lines.append("  lat?: number")
    lines.append("  lng?: number")
    lines.append("  src: 'official' | 'osm' | 'both' // provenance of THIS node")
    lines.append("}")
    lines.append("")
    lines.append("export interface GeoSeedStreet {")
    lines.append("  name: string")
    lines.append("  cls: 'primary' | 'primary_link' | 'secondary' | 'secondary_link'")
    lines.append("}")
    lines.append("")
    lines.append("export const MASHHAD_GEO: {")
    lines.append("  city: string")
    lines.append("  province: string")
    lines.append("  officialSource: string")
    lines.append("  osmSource: string")
    lines.append("  zones: GeoSeedZone[]")
    lines.append("  neighborhoods: GeoSeedNeighborhood[]")
    lines.append("  streets: GeoSeedStreet[]")
    lines.append("} = {")
    lines.append(f"  city: {esc('مشهد')},")
    lines.append(f"  province: {esc('خراسان رضوی')},")
    lines.append(f"  officialSource: {esc(OFFICIAL_SRC)},")
    lines.append(f"  osmSource: {esc(OSM_SRC + ' — ' + RETRIEVED)},")
    lines.append("  zones: [")
    for zname, zcode, _ in ZONES:
        lines.append(f"    {{ name: {esc(zname)}, code: {esc(zcode)} }},")
    lines.append("  ],")
    lines.append("  neighborhoods: [")
    order = {"both": 0, "official": 1, "osm": 2}
    for n in sorted(neighborhoods, key=lambda n: (order[n["src"]] if n["zone"] else 3, n["zone"] or "ﷲ", n["name"])):
        coord = f", lat: {n['lat']}, lng: {n['lng']}" if "lat" in n else ""
        lines.append(f"    {{ name: {esc(n['name'])}, zone: {esc(n['zone']) if n['zone'] else 'null'}{coord}, src: {esc(n['src'])} }},")
    lines.append("  ],")
    lines.append("  streets: [")
    for s in street_list:
        lines.append(f"    {{ name: {esc(s['name'])}, cls: {esc(s['cls'])} }},")
    lines.append("  ],")
    lines.append("}")
    lines.append("")

    out = args.out or os.path.join(here, "..", "..", "panel-build", "src", "src", "lib", "geo-mashhad.ts")
    out = os.path.abspath(out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"zones: {len(ZONES)} | neighborhoods: {len(neighborhoods)} "
          f"(with coords: {with_coords}; by src: {by_src}) | streets: {len(street_list)}")
    print(f"written: {out}")

if __name__ == "__main__":
    sys.exit(main())
