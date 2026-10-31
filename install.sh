#!/usr/bin/env bash
# MedPad Offline Installer v1.9.13-dev.6 - Linux bootstrap
# 100% offline: extracts the bundled Node.js tarball when no system node exists,
# then runs the main offline pipeline (no npm, no build, no downloads).
# v1.9.12: install.mjs shows a MENU - target (this machine / Linux VPS /
# Cloudflare) and interface (browser installer GUI or CLI wizard).
set -u
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG="$DIR/install.log"
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

echo ""
echo "=============================================="
echo "  MedPad Offline Installer v1.9.13-dev.6 (linux)"
echo "  A menu follows: install target + interface."
echo "=============================================="
echo ""

# v1.9.8 FIX (user bug: exit 127 "alias node='winpty node.exe': command not
# found"): this script is the LINUX installer. When it is run inside Git Bash
# / MSYS / Cygwin on Windows, the Linux pipeline must not run at all (wrong
# binaries + bash alias traps). Detect and point the user to install.bat.
# (MEDPAD_SIM_UNAME is a test-only override for this detection.)
UNAME_S="${MEDPAD_SIM_UNAME:-$(uname -s 2>/dev/null || echo '')}"
case "$UNAME_S" in
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    echo "[X] You are running the LINUX installer on Windows (Git Bash / MSYS)."
    echo "    Close this window and run install.bat instead:"
    echo "    double-click install.bat in the extracted package folder (Explorer)."
    log "ABORT windows environment detected: uname=$UNAME_S"
    read -r -p "Press Enter to close..." _
    exit 1
  ;;
esac

# pre-flight
if [ ! -f "$DIR/install.mjs" ]; then
  echo "[X] install.mjs not found next to install.sh - package incomplete."
  log "PREFLIGHT-FAIL install.mjs missing"
  read -r -p "Press Enter to close..." _
  exit 1
fi
if [ ! -f "$DIR/wizard.mjs" ] || [ ! -f "$DIR/server-manager.mjs" ]; then
  echo "[X] wizard.mjs / server-manager.mjs missing - package incomplete."
  log "PREFLIGHT-FAIL helper scripts missing"
  read -r -p "Press Enter to close..." _
  exit 1
fi

# v1.9.8 FIX: resolve node via PATH ONLY (type -P). Using `command -v node`
# can return a bash ALIAS DEFINITION STRING (e.g. "alias node='winpty
# node.exe'" from a Git Bash .bashrc) instead of a real path; executing that
# string fails with "command not found" and exit code 127. `type -P` only
# searches $PATH for real executables and ignores aliases/functions.
NODE_EXE="$(type -P node 2>/dev/null || true)"
if [ -n "$NODE_EXE" ] && [ -x "$NODE_EXE" ]; then
  log "using system node: $NODE_EXE"
else
  NODE_EXE=""
  echo "[..] Node.js not found - extracting the bundled offline runtime..."
  log "node missing - extracting bundled tarball"
  mkdir -p "$DIR/runtime"
  TARXZ=$(ls "$DIR"/offline/node-*.tar.xz 2>/dev/null | head -1)
  if [ -z "${TARXZ:-}" ]; then
    echo "[X] offline/node-*.tar.xz not found - package incomplete."
    log "FAIL bundled tarball not found"
    read -r -p "Press Enter to close..." _
    exit 1
  fi
  if ! tar -xJf "$TARXZ" -C "$DIR/runtime" 2>>"$LOG"; then
    echo "[X] tarball extraction failed (xz-utils required: sudo apt install xz-utils)"
    log "FAIL tar extract failed"
    read -r -p "Press Enter to close..." _
    exit 1
  fi
  NODE_EXE=$(ls "$DIR"/runtime/node-*/bin/node 2>/dev/null | head -1)
  if [ -z "${NODE_EXE:-}" ] || [ ! -x "$NODE_EXE" ]; then
    echo "[X] node binary not found inside runtime/."
    log "FAIL runtime node not found"
    read -r -p "Press Enter to close..." _
    exit 1
  fi
  log "OK extracted runtime node: $NODE_EXE"
fi

NODEV="$("$NODE_EXE" --version 2>/dev/null || echo '?')"
echo "[OK] Node.js $NODEV"
log "node=$NODE_EXE ver=$NODEV"

echo "[..] Running offline pipeline..."
"$NODE_EXE" "$DIR/install.mjs" --node "$NODE_EXE" "$@"
EC=$?
log "install.mjs exit=$EC"
echo ""
if [ $EC -ne 0 ]; then
  echo "[X] Install failed with code $EC - see install.log"
else
  echo "[OK] Install finished."
  echo "     Start:  ./start-server.sh   (panel page opens in the browser)"
  echo "     Stop:   ./stop-server.sh"
  echo "     GUI:    ./server-manager.sh (start/stop buttons in the browser)"
fi
if [ -t 0 ]; then read -r -p "Press Enter to close..." _; fi
exit $EC
