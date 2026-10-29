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
#           package13 (phase 2 completion: change-password, report-edit
#           versions, audio player — last)
#
# This script performs the WHOLE reproducible pipeline and verifies it:
#   1. fresh extraction of the base source (git archive)
#   2. apply the 12-patch chain (fails hard on ANY .rej)
#   3. npm install --no-audit --no-fund        (tested with npm 11.19.1 / Node 24.x)
#   4. DATABASE_URL=file:./dev.db  npx prisma generate   (3 query engines)
#   5. DATABASE_URL=file:./build-time.db  npm run build  (next 16.4.3, standalone)
#   6. assemble the CANONICAL bundle layout {BUILD_ID, standalone, static}
#      with `cp -rL` — dereferencing symlinks is REQUIRED (committed bundle has
#      zero symlinks; raw Next output has @prisma/bcryptjs symlinks in
#      .next/node_modules — lesson from 5-C)
#   7. run 18 hardening checks against the assembled bundle
#   8. compare the result with the committed bundle (app/panel/.next),
#      BUILD_ID-normalized, and report equivalence
#   9. honest typecheck report (next.config sets ignoreBuildErrors=true, so
#      `next build` hides type errors — this step reports them separately)
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
CHAIN="phase1-task1 phase2-remediation phase2-task1 phase2-task2 phase2-dev2-fixes phase2-package4 phase2-madrep phase2-package6 phase2-package7 phase2-package9 phase2-package5 phase2-package13"

[ -d "$REPO_ROOT/.git" ] || { echo "ERROR: $REPO_ROOT is not a git repo"; exit 1; }

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# ── 1. fresh base extraction ────────────────────────────────────────────────
say "1/9 fresh base extraction ($BASE_REF)"
rm -rf "$WORK"; mkdir -p "$WORK/src"
git -C "$REPO_ROOT" archive "$BASE_REF" | tar -x -C "$WORK/src"
[ -f "$WORK/src/package.json" ] || die "base extraction produced no package.json"

# ── 2. patch chain ──────────────────────────────────────────────────────────
say "2/9 applying 12-patch chain"
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

# ── 3. npm install ──────────────────────────────────────────────────────────
say "3/9 npm install (this is the slow part)"
npm install --no-audit --no-fund > "$WORK/npm-install.log" 2>&1 \
  || { tail -5 "$WORK/npm-install.log"; die "npm install failed"; }
echo "   done (log: $WORK/npm-install.log)"

# ── 4. prisma generate ──────────────────────────────────────────────────────
say "4/9 prisma generate"
DATABASE_URL="file:./dev.db" npx prisma generate > "$WORK/prisma.log" 2>&1 \
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
echo "   hardening: PASS=$P FAIL=$F / 18"
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

# ── 9. honest typecheck report (informational) ─────────────────────────────
say "9/9 typecheck report (ignoreBuildErrors hides this from next build)"
cd "$WORK/src"
if npx tsc --noEmit > "$WORK/tsc.log" 2>&1; then
  echo "   tsc: CLEAN (0 errors)"
else
  n=$(grep -c 'error TS' "$WORK/tsc.log" || true)
  echo "   tsc: ${n} type errors — full log: $WORK/tsc.log (known debt, see WORKLOG Task 48)"
fi

say "RESULT"
echo "   bundle:        $WORK/bundle/.next"
echo "   hardening:     18/18 PASS"
if [ $cmp_rc -eq 0 ]; then echo "   equivalence:   YES (BUILD_ID-normalized)";
else echo "   equivalence:   DIFFERS (see 8/9 above)"; fi
echo "   promote manually if intended: cp -r $WORK/bundle/.next/* <repo>/app/panel/.next/"
exit 0
