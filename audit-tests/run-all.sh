#!/usr/bin/env bash
# run-all.sh — run EVERY MedPad audit/security test suite in one command.
#
# Usage:
#   bash audit-tests/run-all.sh            # phase-0 suites vs repo root + full v1.9.12 suite
#   bash audit-tests/run-all.sh <pkgDir>   # phase-0 suites vs <pkgDir>  + full suite vs <pkgDir>
#
# Suites:
#   test-process-safety.mjs    ownership heuristics + never-kill-foreign (live)
#   test-wizard-security.mjs   browser-origin hardening of the installer wizard
#   test-realtime-security.mjs fail-closed auth + /emit limits + socket handshake
#   test-v1912.sh              the full phase 3-7 install/boot/login/CF suite
#   test-functional-core.sh    phase 1 / task 1 functional core (auth, checkin
#                              idempotency, offline clientAt, ACLs, isolation)
#
# NOTE: test-process-safety.mjs always imports services.mjs from the REPO
# (../services.mjs relative to this folder); the pkgDir argument applies to
# the wizard/realtime suites and to test-v1912.sh / test-functional-core.sh.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG="${1:-}"
RC=0

echo "==== [1/5] process safety ===="
node "$HERE/test-process-safety.mjs" || RC=1
echo "==== [2/5] wizard security ===="
node "$HERE/test-wizard-security.mjs" ${PKG:+"$PKG"} || RC=1
echo "==== [3/5] realtime security ===="
node "$HERE/test-realtime-security.mjs" ${PKG:+"$PKG"} || RC=1
echo "==== [4/5] full v1.9.12 suite ===="
bash "$HERE/test-v1912.sh" ${PKG:+"$PKG"} || RC=1
echo "==== [5/5] functional core (phase 1 / task 1) ===="
bash "$HERE/test-functional-core.sh" ${PKG:+"$PKG"} || RC=1

echo ""
if [ "$RC" = "0" ]; then echo "ALL-SUITES-PASS"; else echo "SOME-SUITES-FAILED"; fi
exit $RC
