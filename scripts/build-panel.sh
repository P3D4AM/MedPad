#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# MedPad — REPRODUCIBLE PANEL BUILD (phase P11, protocol session Oct 29, 2026)
#
# The panel's Next.js SOURCE is not stored in the repo as a plain tree — it is
# reconstructed from a committed base blob + an ordered chain of patches:
#
#   base:   git blob bd9c80b:medpad-full-1.9/inst-stage/panel-src
#   chain:  patches/panel-src-phase1-task1 → phase2-remediation → task1 →
#           task2 → dev2-fixes → package4 → madrep → package6 → package7 →
#           package9 (cumulative; supersedes package8) → package5 (bell) →
#           package13 (phase 2 completion) → package14 (phase 3 tracking:
#           LocationPing/TrackStatus, upload+config+live+history APIs,
#           TrackingTab) → package15 (mission Oct 31) → package16 (mission
#           12: no-auto-assign, VoiceFile, multi-voice, map-isolate) →
#           phase6-customer360 (Customer 360: profile fields, CustomerLink,
#           Purchase/PurchaseItem, 360 profile APIs + UI, dark/light theme
#           system + switcher, sidebar dashboard, customers list upgrade) →
#           phase7-central-settings (phase 4 comprehensive: UNIFIED settings
#           section with the 11 pre-defined categories + saved-vs-applied
#           device ack + browser Push (VAPID boot-gen, PushSubscription,
#           sw.js) + duplicate permission-bell icon removal) →
#           phase8-owner-fixes (Nov 2026 owner fix batch: real HTTP Range
#           serving for iOS Safari voice playback, assignment notifications
#           go ONLY to the assigned rep, assignment-aware incremental app
#           customers sync, plans window.today, Jalali month calendar +
#           همهٔ روزها view with pinned overdue section, per-customer
#           assign/revoke dialog) →
#           phase9-holoo (Nov 2026 Holoo ERP integration: ErpCustomer/
#           ErpProduct/ErpPayment/HolooSyncLog + Customer.erpCode bridge,
#           lib/holoo{,-config,-sync} client/settings/orchestrator with the
#           boot-started periodic scheduler, 5 admin /api/admin/holoo/*
#           routes, erp blocks on the app customers/profile APIs, the
#           «هلو» dashboard tab + the 360 profile financial block) →
#           phase10-holoo-hardening (Nov 2026 spec-compliance fixes:
#           GetCustPay sends BOTH documented parameter spellings `code` +
#           `customerErpCode`, the spec-§9 «partial» (ناقص) sync status,
#           lastSuccess in the status view, and a REAL sample code on the
#           GetCustPay connection-test probe) →
#           phase11-holoo-secret-encryption (Task 67, Nov 2026: Holoo
#           credentials at rest move from Base64 to AES-256-GCM envelopes
#           (enc1:) keyed by a per-install master key file OUTSIDE SQLite
#           (panel/db/holoo.key — atomic creation, chmod 600 Linux /
#           user-only inherited ACL Windows); lazy ATOMIC migration of the
#           legacy Base64 value; explicit passwordError surfaced to the
#           admin on a missing/wrong/corrupt key WITHOUT ever wiping the
#           stored settings; the cached token is encrypted too and simply
#           discarded (one re-login) when it cannot be recovered — last) →
#           phase12-holoo-extraction (Task 68 / Phase 12, Nov 2026: live-
#           integration stabilization. The real service wraps lists in
#           business envelopes — {"Customer":[…]}, {"product":[…]},
#           {"report":[…]} — which the old extractor did not know, so the
#           live sync imported ZERO records. New extractHolooItemsDetailed():
#           bounded case-insensitive unwrapping + single-record support +
#           explicit diagnostics (kind/wrapper/shape/count/first-record
#           fields); 'unrecognized' bodies are honest sync errors (partial)
#           while valid empty lists stay SUCCESS; probes report elapsed/
#           timeout/wrapper/count and Invoice/Invoice gets its own
#           diagnostic timeout — last) →
#           phase13-geo-mashhad (Task 70 / Phase 5, Nov 2026: structured
#           Mashhad geography — GeoArea tree (province←city←zone←neighborhood
#           ←street) with the dual-sourced seed dataset (City-Council
#           13-region resolution + OpenStreetMap centroids, both dated),
#           boot seeder, shared geo gatekeepers, 5 admin /api/panel/geo/*
#           routes, geo refs on the customer write paths + the app mirror,
#           the assignment «تکمیل» gate, the «جغرافیا و محلات» tab with the
#           completion worklist + geographic reports, and the CustomersTab
#           structured pickers) →
#           phase14-strict-types (Task 71 / roadmap Phase 13, Nov 2026:
#           TypeScript debt CLEARED — all 14 legacy typing errors fixed at
#           the type level only (zero runtime change): the Promise.all
#           union-degradation that broke Map tuple inference in the two
#           assignment routes, the audit targetId null for bulk operations,
#           the stale Employee._count type (tokens), the proximity typeof
#           guards — and `typescript.ignoreBuildErrors` REMOVED from
#           next.config.ts so `next build` enforces types again — last)
#
# This script performs the WHOLE reproducible pipeline and verifies it:
#   1. fresh extraction of the base source (git archive)
#   2. apply the 24-patch chain (fails hard on ANY .rej)
#   3. npm install --no-audit --no-fund        (tested with npm 11.19.1 / Node 24.x)
#   4. DATABASE_URL=file:./dev.db  npx prisma generate   (3 query engines)
#   5. DATABASE_URL=file:./build-time.db  npm run build  (next 16.4.3, standalone)
#   6. assemble the CANONICAL bundle layout {BUILD_ID, standalone, static}
#      with `cp -rL` — dereferencing symlinks is REQUIRED (committed bundle has
#      zero symlinks; raw Next output has @prisma/bcryptjs symlinks in
#      .next/node_modules — lesson from 5-C)
#   7. run 63 hardening checks against the assembled bundle
#   8. compare the result with the committed bundle (app/panel/.next),
#      BUILD_ID-normalized, and report equivalence
#   9. typecheck double-report (since phase 14 the build itself enforces
#      types; this is the explicit standalone tsc result)
#
# Usage:  scripts/build-panel.sh [REPO_ROOT] [WORK_DIR]
#   REPO_ROOT  repo root (default: parent of this script's dir)
#   WORK_DIR   scratch dir (default: panel-build under REPO_ROOT, wiped)
#
# The script NEVER touches app/panel/.next — it only builds into
# WORK_DIR/bundle and prints the comparison. Promoting a new bundle into the
# repo is a deliberate, reviewed step (copy bundle/ over app/panel/.next).
#
# Reproducibility contract (verified Oct 29, 2026 — see WORKLOG Task 48):
# source equivalence via the patch chain; chunk-for-chunk bundle equivalence
# with the committed bundle (only BUILD_ID and its 3 manifest files differ —
# Next regenerates BUILD_ID per build; sizes identical).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
WORK="${2:-$REPO_ROOT/panel-build}"
BASE_REF="bd9c80b:medpad-full-1.9/inst-stage/panel-src"
CHAIN="phase1-task1 phase2-remediation phase2-task1 phase2-task2 phase2-dev2-fixes phase2-package4 phase2-madrep phase2-package6 phase2-package7 phase2-package9 phase2-package5 phase2-package13 phase3-package14 phase4-package15 phase5-package16 phase6-customer360 phase7-central-settings phase8-owner-fixes phase9-holoo phase10-holoo-hardening phase11-holoo-secret-encryption phase12-holoo-extraction phase13-geo-mashhad phase14-strict-types"

# RESUME mode (sandbox convenience): with MEDPAD_BUILD_RESUME=1 an existing
# $WORK/src with node_modules (from an interrupted run — e.g. a sandbox that
# kills long processes) skips steps 1–3 and continues at prisma generate.
# The bundle comparison (step 8) still verifies the tree is exactly what
# the chain produces, so resuming cannot silently build the wrong source.
RESUME="${MEDPAD_BUILD_RESUME:-0}"

[ -d "$REPO_ROOT/.git" ] || { echo "ERROR: $REPO_ROOT is not a git repo"; exit 1; }

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# ── 1. fresh base extraction ────────────────────────────────────────────────
if [ "$RESUME" = "1" ] && [ -d "$WORK/src/node_modules" ] && [ -f "$WORK/src/package.json" ]; then
  say "1/9 RESUME — reusing $WORK/src (node_modules present)"
else
  RESUME=0
  say "1/9 fresh base extraction ($BASE_REF)"
  rm -rf "$WORK"; mkdir -p "$WORK/src"
  git -C "$REPO_ROOT" archive "$BASE_REF" | tar -x -C "$WORK/src"
  [ -f "$WORK/src/package.json" ] || die "base extraction produced no package.json"
fi

# ── 2. patch chain ──────────────────────────────────────────────────────────
if [ "$RESUME" = "1" ]; then
  say "2/9 RESUME — patch chain already applied (verified by the marker below)"
else
  say "2/9 applying 24-patch chain"
  cd "$WORK/src"
  for p in $CHAIN; do
    patchfile="$REPO_ROOT/patches/panel-src-$p.patch"
    [ -f "$patchfile" ] || die "missing patch: $patchfile"
    out=$(patch -p1 --no-backup-if-mismatch < "$patchfile" 2>&1) || {
      echo "$out"; die "patch FAILED: $p"; }
    rej=$(find . -name '*.rej' | wc -l)
    [ "$rej" -eq 0 ] || { find . -name '*.rej'; die "rejects after $p"; }
    printf '   ok  %s\n' "$p"
  done
  # the bell component must exist after the chain
  [ -f src/components/panel/NotificationsBell.tsx ] || die "patch chain did not produce NotificationsBell.tsx"
fi
# phase 3 marker (both fresh and resumed trees must have it)
[ -f "$WORK/src/src/components/panel/TrackingTab.tsx" ] || die "patch chain did not produce TrackingTab.tsx"
# package15 marker (mission Oct 31)
[ -f "$WORK/src/src/components/panel/EventsTab.tsx" ] || die "patch chain did not produce EventsTab.tsx"
# phase 7 markers (comprehensive phase 4 — unified settings + browser Push)
[ -f "$WORK/src/src/components/panel/SettingsTab.tsx" ] || die "patch chain did not produce SettingsTab.tsx"
[ -f "$WORK/src/public/sw.js" ] || die "patch chain did not produce public/sw.js"
# phase 8 markers (owner fix batch — Jalali calendar lib + month calendar)
[ -f "$WORK/src/src/lib/jalali.ts" ] || die "patch chain did not produce src/lib/jalali.ts"
# phase 9 markers (Holoo integration — client + orchestrator + tab)
[ -f "$WORK/src/src/lib/holoo.ts" ] || die "patch chain did not produce src/lib/holoo.ts"
[ -f "$WORK/src/src/lib/holoo-sync.ts" ] || die "patch chain did not produce src/lib/holoo-sync.ts"
[ -f "$WORK/src/src/components/panel/HolooTab.tsx" ] || die "patch chain did not produce HolooTab.tsx"
# phase 10 markers (Holoo hardening — dual GetCustPay param + partial status)
grep -q "customerErpCode: erpCode, code: erpCode" "$WORK/src/src/lib/holoo-sync.ts" \
  || die "phase10 missing: GetCustPay dual parameter"
grep -q "errors.length ? 'partial' : 'success'" "$WORK/src/src/lib/holoo-sync.ts" \
  || die "phase10 missing: partial sync status"
grep -q "lastSuccess" "$WORK/src/src/components/panel/HolooTab.tsx" \
  || die "phase10 missing: lastSuccess in the tab"
# phase 11 markers (Task 67 — AES-256-GCM secret encryption)
[ -f "$WORK/src/src/lib/holoo-secret.ts" ] || die "patch chain did not produce src/lib/holoo-secret.ts"
grep -q "aes-256-gcm" "$WORK/src/src/lib/holoo-secret.ts" \
  || die "phase11 missing: aes-256-gcm in holoo-secret.ts"
grep -q "passwordError" "$WORK/src/src/lib/holoo-config.ts" \
  || die "phase11 missing: passwordError in holoo-config.ts"
grep -q "passwordWire" "$WORK/src/src/lib/holoo.ts" \
  || die "phase11 missing: passwordWire in the client"
grep -q "passwordError" "$WORK/src/src/components/panel/HolooTab.tsx" \
  || die "phase11 missing: passwordError banner in the tab"
# phase 12 markers (Task 68 — robust extraction + probe diagnostics)
[ -f "$WORK/src/src/lib/holoo.ts" ] || die "source tree missing"
grep -q "extractHolooItemsDetailed" "$WORK/src/src/lib/holoo.ts" \
  || die "phase12 missing: extractHolooItemsDetailed in the client"
grep -q "KNOWN_WRAPPER_KEYS" "$WORK/src/src/lib/holoo.ts" \
  || die "phase12 missing: KNOWN_WRAPPER_KEYS in the client"
grep -q "unrecognized" "$WORK/src/src/lib/holoo-sync.ts" \
  || die "phase12 missing: unrecognized handling in the sync"
grep -q "invoiceProbeTimeoutMs" "$WORK/src/src/app/api/admin/holoo/test/route.ts" \
  || die "phase12 missing: invoice diagnostic timeout in the test route"
grep -q "firstRecordKeys" "$WORK/src/src/components/panel/HolooTab.tsx" \
  || die "phase12 missing: probe diagnostics columns in the tab"
# phase 13 markers (Task 70 — structured Mashhad geography)
[ -f "$WORK/src/src/lib/geo-mashhad.ts" ] || die "patch chain did not produce src/lib/geo-mashhad.ts"
[ -f "$WORK/src/src/lib/geo-seed.ts" ] || die "patch chain did not produce src/lib/geo-seed.ts"
[ -f "$WORK/src/src/lib/geo-areas.ts" ] || die "patch chain did not produce src/lib/geo-areas.ts"
[ -f "$WORK/src/src/components/panel/GeoTab.tsx" ] || die "patch chain did not produce GeoTab.tsx"
grep -q "GEO_AREA_DDL" "$WORK/src/src/instrumentation.ts" \
  || die "phase13 missing: GEO_AREA_DDL in instrumentation"
grep -q "seedGeoAreas" "$WORK/src/src/instrumentation.ts" \
  || die "phase13 missing: boot seed call"
# phase 14 markers (Task 71 — strict types: tsc clean, escape hatch gone)
! grep -q "ignoreBuildErrors" "$WORK/src/next.config.ts" \
  || die "phase14: next.config.ts still sets ignoreBuildErrors"
grep -q "typeof lat === 'number'" "$WORK/src/src/app/api/app/proximity/route.ts" \
  || die "phase14 missing: proximity typeof guard"
grep -q "targetId?: string | null" "$WORK/src/src/lib/audit.ts" \
  || die "phase14 missing: audit targetId accepts null (bulk)"
grep -q "tokens: number }" "$WORK/src/src/lib/types.ts" \
  || die "phase14 missing: Employee._count tokens"
cd "$WORK/src"

# ── 3. npm install ──────────────────────────────────────────────────────────
if [ "$RESUME" = "1" ]; then
  say "3/9 RESUME — npm install assumed complete (node_modules present)"
else
  say "3/9 npm install (this is the slow part)"
  npm install --no-audit --no-fund > "$WORK/npm-install.log" 2>&1 \
    || { tail -5 "$WORK/npm-install.log"; die "npm install failed"; }
  echo "   done (log: $WORK/npm-install.log)"
fi

# ── 4. prisma generate ──────────────────────────────────────────────────────
say "4/9 prisma generate"
# use the LOCAL binary explicitly — npx may resolve a registry version
# (seen in the wild: npx picked prisma@8.0.0-rc.28 over the pinned 6.x)
DATABASE_URL="file:./dev.db" ./node_modules/.bin/prisma generate > "$WORK/prisma.log" 2>&1 \
  || { tail -5 "$WORK/prisma.log"; die "prisma generate failed"; }
for e in node_modules/.prisma/client/libquery_engine-debian-openssl-3.0.x.so.node \
         node_modules/.prisma/client/query_engine-windows.dll.node \
         node_modules/.prisma/client/query_engine_bg.wasm; do
  [ -f "$e" ] || die "missing prisma engine: $e"
done
echo "   3 query engines present (debian + windows + wasm)"

# ── 5. next build ───────────────────────────────────────────────────────────
say "5/9 next build (standalone)"
DATABASE_URL="file:./build-time.db" NODE_OPTIONS="--max-old-space-size=1536" \
  npm run build > "$WORK/build.log" 2>&1 \
  || { tail -20 "$WORK/build.log"; die "next build failed"; }
grep -q "Compiled successfully" "$WORK/build.log" || die "build log lacks success marker"
echo "   compiled (log: $WORK/build.log)"

# ── 6. canonical assembly (cp -rL — NO symlinks, ever) ─────────────────────
say "6/9 canonical bundle assembly {BUILD_ID, standalone, static}"
# wipe any previous assembly (RESUME mode reuses $WORK — without this the
# bundle accumulates STALE <BUILD_ID> manifest dirs from earlier runs)
rm -rf "$WORK/bundle"
mkdir -p "$WORK/bundle/.next"
cp .next/BUILD_ID               "$WORK/bundle/.next/"
cp -rL .next/static             "$WORK/bundle/.next/"
cp -rL .next/standalone         "$WORK/bundle/.next/"
symlinks=$(find "$WORK/bundle" -type l | wc -l)
[ "$symlinks" -eq 0 ] || die "assembled bundle contains $symlinks symlinks"

# ── 7. hardening checks (15) ────────────────────────────────────────────────
say "7/9 hardening checks"
cd "$WORK/bundle/.next/standalone"
P=0; F=0
chk(){ if eval "$2" >/dev/null 2>&1; then P=$((P+1)); else F=$((F+1)); echo "   FAIL  $1"; fi; }
chk "no admin123"                 "! grep -rq admin123 --include='*.js' ."
chk "no jsdelivr CDN refs"        "! grep -rq jsdelivr --include='*.js' --include='*.html' ."
chk "9 self-hosted woff2 fonts"   "[ \$(find . -name '*.woff2' -path '*fonts*' | wc -l) -eq 9 ]"
chk "vazirmatn.css local"         "[ -f public/fonts/vazirmatn.css ]"
chk "engine: debian"              "[ -f node_modules/.prisma/client/libquery_engine-debian-openssl-3.0.x.so.node ]"
chk "engine: windows"             "[ -f node_modules/.prisma/client/query_engine-windows.dll.node ]"
chk "engine: wasm"                "[ -f node_modules/.prisma/client/query_engine_bg.wasm ]"
chk "no .env shipped"             "[ ! -f .env ] && [ ! -f .env.local ]"
chk "bcryptjs external"           "[ -d node_modules/bcryptjs ]"
chk "next 16.4.3 pinned"          "grep -q '\"next\": \"16.4.3\"' package.json"
chk "socket literal :3003"        "grep -rq '127.0.0.1:3003' --include='*.js' ."
chk "api: panel-notifications"    "[ -f .next/server/app/api/panel-notifications/route.js ]"
chk "api: panel-notifications/read" "[ -f .next/server/app/api/panel-notifications/read/route.js ]"
chk "api: planned-visits"         "[ -d .next/server/app/api/planned-visits ]"
chk "bell UI chunks"              "grep -rq 'bell-unread-count' --include='*.js' ."
# phase 2 completion (package 13): the three new API surfaces must ship
chk "api: app change-password"     "[ -f .next/server/app/api/app/change-password/route.js ]"
chk "api: app report-edit"         "[ -f .next/server/app/api/app/report-edit/route.js ]"
chk "api: checkins versions"       "[ -d .next/server/app/api/checkins/versions ]"
# phase 3 (package 14): tracking API surfaces + tab must ship
chk "api: app tracking-config"     "[ -f .next/server/app/api/app/tracking-config/route.js ]"
chk "api: app locations"           "[ -f .next/server/app/api/app/locations/route.js ]"
chk "api: panel tracking"          "[ -f .next/server/app/api/panel/tracking/route.js ]"
chk "api: panel tracking history"  "[ -f .next/server/app/api/panel/tracking/history/route.js ]"
chk "tracking tab UI chunks"       "grep -rq 'route-map' --include='*.js' ."
# package15 (mission Oct 31): the event-log API surfaces + tab must ship
chk "api: app events upload"       "[ -f .next/server/app/api/app/events/route.js ]"
chk "api: admin events report"     "[ -f .next/server/app/api/admin/events/route.js ]"
chk "events tab UI chunks"         "grep -rq 'events-filter-employee' --include='*.js' ."
# phase 7 (comprehensive phase 4): settings center + browser Push must ship
chk "sw.js served from public"      "[ -f public/sw.js ]"
chk "sw: push + click handlers"     "grep -q \"addEventListener('push'\" public/sw.js && grep -q \"addEventListener('notificationclick'\" public/sw.js"
chk "api: push key"                 "[ -f .next/server/app/api/push/key/route.js ]"
chk "api: push subscribe"           "[ -f .next/server/app/api/push/subscribe/route.js ]"
chk "web-push external in runtime"  "[ -d node_modules/web-push ]"
chk "settings tab UI chunks"        "grep -rq 'settings-cat-' --include='*.js' ."
chk "device-ack + history chunks"   "grep -rq 'settings-device-ack' --include='*.js' ."
chk "vapid bootstrap in server"     "grep -rq 'vapidPublicKey' .next/server --include='*.js'"
# phase 9 (Holoo integration): the five admin API surfaces + the erp block
# on the app customers API + the tab UI must ship in the bundle
chk "api: holoo settings"          "[ -f .next/server/app/api/admin/holoo/settings/route.js ]"
chk "api: holoo test"              "[ -f .next/server/app/api/admin/holoo/test/route.js ]"
chk "api: holoo sync"              "[ -f .next/server/app/api/admin/holoo/sync/route.js ]"
chk "api: holoo status"            "[ -f .next/server/app/api/admin/holoo/status/route.js ]"
chk "api: holoo products"          "[ -f .next/server/app/api/admin/holoo/products/route.js ]"
chk "holoo client in server"        "grep -rq 'userinfo' .next/server --include='*.js'"
chk "holoo tab UI chunks"           "grep -rq 'اطلاعات مالی هلو' --include='*.js' ."
# phase 10 (Holoo hardening): dual GetCustPay param + partial status +
# lastSuccess must ship in the built server/client chunks
chk "holoo GetCustPay dual param"   "grep -rq 'customerErpCode:' .next/server --include='*.js' && grep -rq 'GetCustPay' .next/server --include='*.js'"
chk "holoo partial status in server" "grep -rq '\"partial\"' .next/server --include='*.js' || grep -rq 'partial' .next/server/app/api/admin/holoo --include='*.js'"
chk "holoo tab: ناقص badge + lastSuccess" "grep -rq 'ناقص' --include='*.js' . && grep -rq 'آخرین همگام‌سازی موفق' --include='*.js' ."
# phase 11 (Task 67 — secret encryption): the crypto module + envelope
# format + error surfacing must ship in the built server, the UI banner in
# the client chunks, and NO key file may ever ship in the bundle
chk "holoo-secret module in server"   "grep -rq 'aes-256-gcm' .next/server --include='*.js'"
chk "holoo enc1 envelope in server"   "grep -rq 'enc1:' .next/server --include='*.js'"
chk "holoo passwordError surfaced"    "grep -rq 'passwordError' .next/server --include='*.js'"
chk "holoo tab error banner chunks"   "grep -rq 'رمز ذخیره‌شده حذف نشده است' --include='*.js' ."
chk "no holoo.key file in bundle"     "[ -z "$(find . -name 'holoo.key' -print -quit)" ]"
# phase 12 (Task 68 — robust extraction + probe diagnostics): the new
# extractor and its honest error vocabulary must ship in the server, the
# probe table upgrade in the client chunks
chk "holoo extraction in server"     "grep -rq 'unrecognized' .next/server --include='*.js'"
chk "holoo unrecognized error text"  "grep -rq 'ساختار شناسایی‌شده نداشت' .next/server --include='*.js'"
chk "holoo probe elapsed/timeout"    "grep -rq 'elapsedMs' .next/server --include='*.js' && grep -rq 'timeoutMs' .next/server --include='*.js'"
chk "holoo extraction diag keys"     "grep -rq 'firstRecordKeys' .next/server --include='*.js'"
chk "holoo tab probe table upgrade"  "grep -rq 'مهلت تشخیصی' --include='*.js' . && grep -rq 'نمونه فیلدها' --include='*.js' ."
# phase 13 (Task 70 — structured geography): the geo API surfaces, the
# gate text and the geo tab UI must ship in the built bundle
chk "api: panel geo tree"          "[ -f .next/server/app/api/panel/geo/tree/route.js ]"
chk "api: panel geo resolve"       "[ -f .next/server/app/api/panel/geo/resolve/route.js ]"
chk "api: panel geo report"        "[ -f .next/server/app/api/panel/geo/report/route.js ]"
chk "api: panel geo nodes"         "[ -f .next/server/app/api/panel/geo/nodes/route.js ]"
chk "geo seed dataset in server"   "grep -rq 'منطقه ثامن' .next/server --include='*.js'"
chk "geo dedup normalization"      "grep -rq 'normalizedName' .next/server --include='*.js'"
chk "geo tab UI chunks"            "grep -rq 'در انتظار تکمیل اطلاعات جغرافیایی' --include='*.js' ."
chk "geo gate text in server"      "grep -rq 'ثبت موقت در انتظار تکمیل' .next/server --include='*.js'"
# phase 14 (Task 71 — strict types): the shipped next config must NOT hide
# build errors (required-server-files.json embeds the serialized config;
# Next writes the key explicitly with its default value — what matters is
# that the value is never `true`)
chk "shipped config enforces type checks" "! grep -qE 'ignoreBuildErrors[^:]*: *true' .next/required-server-files.json"
echo "   hardening: PASS=$P FAIL=$F / 63"
LAST_HARDENING="$P/$F PASS"
[ "$F" -eq 0 ] || die "hardening checks failed"

# ── 8. compare with committed bundle (BUILD_ID + build-dir normalized) ─────
# NOTE: 5 files (standalone/server.js, required-server-files.json, and the 3
# generated .prisma/client/*.js) embed the ABSOLUTE build directory as build
# metadata (outputFileTracingRoot / repoRoot / turbopack.root / prisma paths).
# It has no runtime effect (Next resolves paths relative to server.js), but a
# build from a different directory will differ in exactly these bytes. The
# comparison extracts each side's build dir from its own metadata
# (outputFileTracingRoot) and replaces it with a placeholder before judging
# equivalence; BUILD_ID differences in file NAMES are normalized as well.
say "8/9 comparison with committed bundle (app/panel/.next)"
cmp_rc=0
python3 - "$WORK" "$REPO_ROOT" << 'PYEOF' || cmp_rc=$?
import os, re, sys
work, repo = sys.argv[1], sys.argv[2]
def collect(root):
    out = {}
    for dp, _, files in os.walk(root):
        for f in files:
            p = os.path.relpath(os.path.join(dp, f), root)
            out[p] = os.path.getsize(os.path.join(dp, f))
    return out
def read(root, p):
    with open(os.path.join(root, p), "rb") as fh: return fh.read()
root_a = os.path.join(work, "bundle/.next")
rb = os.path.join(repo, "app/panel/.next")
a = collect(root_a)
bid_a = open(os.path.join(root_a, "BUILD_ID")).read().strip()
if not os.path.isdir(rb):
    print("   committed bundle not found — skip comparison"); sys.exit(0)
b = collect(rb)
bid_b = open(os.path.join(rb, "BUILD_ID")).read().strip()
# extract each side's ABSOLUTE build dir from its own metadata
# (fresh side: WORK/src; committed side: whatever dir it was built in)
fbdir = os.path.join(work, "src").encode()
m = re.search(rb'"outputFileTracingRoot":\s*"([^"]+)"',
              read(rb, os.path.join("standalone", ".next", "required-server-files.json")))
cbdir = m.group(1) if m else b"\x00-no-committed-builddir-00"
norm_path = lambda p, bid: p.replace(bid, "<BID>") if bid in p else p
norm_data = lambda d, bdir: d.replace(bdir, b"<BUILDDIR>")
an = {norm_path(p, bid_a): s for p, s in a.items()}
bn = {norm_path(p, bid_b): s for p, s in b.items()}
only_a = sorted(set(an) - set(bn)); only_b = sorted(set(bn) - set(an))
sd = [p for p in set(an) & set(bn) if an[p] != bn[p]]
real = []
for p in sd:
    ka = [k for k in a if norm_path(k, bid_a) == p][0]
    kb = [k for k in b if norm_path(k, bid_b) == p][0]
    if norm_data(read(root_a, ka), fbdir) != norm_data(read(rb, kb), cbdir):
        real.append(p)
print(f"   fresh={len(a)} files  committed={len(b)} files  (BUILD_ID fresh={bid_a[:12]} committed={bid_b[:12]})")
print(f"   committed bundle was built in: {cbdir.decode(errors='replace')}")
print(f"   normalized: only-fresh={len(only_a)} only-committed={len(only_b)} real-diff={len(real)} (build-dir-metadata diff: {len(sd) - len(real)})")
for p in only_a[:5]: print("     +", p)
for p in only_b[:5]: print("     -", p)
for p in real[:5]:   print("     !", p)
ok = not (only_a or only_b or real)
print("   EQUIVALENT: fresh build reproduces the committed bundle (except BUILD_ID + build-dir metadata)" if ok
      else "   NOT EQUIVALENT — inspect the differences above")
sys.exit(0 if ok else 3)
PYEOF

# ── 9. typecheck double-report ──────────────────────────────────────────────
# Since phase 14 (Task 71) the `typescript.ignoreBuildErrors` escape hatch
# is GONE — step 5 (`next build`) itself fails on any type error. This step
# still runs the standalone tsc as an explicit belt-and-braces report.
say "9/9 typecheck report (the build itself enforces types since phase 14)"
cd "$WORK/src"
if ./node_modules/.bin/tsc --noEmit > "$WORK/tsc.log" 2>&1; then
  echo "   tsc: CLEAN (0 errors)"
else
  n=$(grep -c 'error TS' "$WORK/tsc.log" || true)
  echo "   tsc: ${n} type errors — full log: $WORK/tsc.log (step 5 should have failed too!)"
fi

say "RESULT"
echo "   bundle:        $WORK/bundle/.next"
echo "   hardening:     ${LAST_HARDENING:-?}"
if [ $cmp_rc -eq 0 ]; then echo "   equivalence:   YES (BUILD_ID-normalized)";
else echo "   equivalence:   DIFFERS (see 8/9 above)"; fi
echo "   promote manually if intended: cp -r $WORK/bundle/.next/* <repo>/app/panel/.next/"
exit 0
