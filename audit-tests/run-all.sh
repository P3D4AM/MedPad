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
#   test-remediation.sh        phase 1 / task 2 remediation suite (G-1..G-6:
#                              assignment enforcement, token expiry/revocation,
#                              admin logout auth, employee lifecycle, audio
#                              authorization, GPS validation)
#
# NOTE: test-process-safety.mjs always imports services.mjs from the REPO
# (../services.mjs relative to this folder); the pkgDir argument applies to
# the wizard/realtime suites and to test-v1912.sh / test-functional-core.sh.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG="${1:-}"
RC=0

echo "==== [1/9] process safety ===="
node "$HERE/test-process-safety.mjs" || RC=1
echo "==== [2/9] wizard security ===="
node "$HERE/test-wizard-security.mjs" ${PKG:+"$PKG"} || RC=1
echo "==== [3/9] realtime security ===="
node "$HERE/test-realtime-security.mjs" ${PKG:+"$PKG"} || RC=1
echo "==== [4/9] full v1.9.12 suite ===="
bash "$HERE/test-v1912.sh" ${PKG:+"$PKG"} || RC=1
echo "==== [5/9] functional core (phase 1 / task 1) ===="
bash "$HERE/test-functional-core.sh" ${PKG:+"$PKG"} || RC=1
echo "==== [6/9] remediation (phase 1 / task 2) ===="
bash "$HERE/test-remediation.sh" ${PKG:+"$PKG"} || RC=1
echo "==== [7/9] mission 12 (Nov 2026) ===="
bash "$HERE/test-mission12.sh" ${PKG:+"$PKG"} || RC=1
echo "==== [8/9] customer 360 (phase 1) ===="
bash "$HERE/test-customer360.sh" ${PKG:+"$PKG"} || RC=1
echo "==== [9/9] settings center (phase 4) ===="
bash "$HERE/test-settings-center.sh" ${PKG:+"$PKG"} || RC=1

echo ""
if [ "$RC" = "0" ]; then echo "ALL-SUITES-PASS"; else echo "SOME-SUITES-FAILED"; fi
exit $RC
