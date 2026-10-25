#!/usr/bin/env node
/**
 * test-process-safety.mjs — Phase 0 verification: the v1.9.12 process-safety
 * guarantees of services.mjs.
 *
 *   P1..P6   ownsProcess()/normPath() unit tests — including the classic
 *            substring traps (/opt/med must NOT match /opt/medpad)
 *   P7       .medpad.pids reader accepts BOTH the v1 (legacy) and v2 formats
 *   P8  LIVE a FOREIGN listener on our port is classified foreign and is
 *            NEVER killed by stopMedPadServices() — it is still listening
 *            afterwards and reported in foreignBusy
 *   P9  LIVE a listener whose command line proves it belongs to the install
 *            dir IS classified ours and IS stopped (verified: port freed)
 *   P10 LIVE a saved v2 pid entry whose process belongs to the dir is killed;
 *            a legacy v1 pid entry that cannot be proven is SKIPPED, not killed
 *
 * Everything runs inside throwaway temp dirs with helper processes owned by
 * this test — no real MedPad install or user process is ever touched.
 *
 * Usage: node test-process-safety.mjs [pkgDir]   (default: repo root)
 */
import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { ownsProcess, normPath, readPids, classifyPortOwners, stopMedPadServices, pidFile } from '../services.mjs'

let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { console.log(`[PASS] ${name}`); pass++ } else { console.log(`[FAIL] ${name}`); fail++ } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)) })
  })
}
const stillListening = (port) => new Promise((resolve) => {
  const s = net.connect({ host: '127.0.0.1', port, timeout: 800 })
  s.once('connect', () => { s.destroy(); resolve(true) })
  s.once('error', () => resolve(false))
  s.once('timeout', () => { s.destroy(); resolve(false) })
})

// ── P1..P6: ownership heuristics (pure unit tests) ───────────────────
ok('P1 exact path in cmdline proves ownership', ownsProcess({ cmd: 'node /opt/medpad/server-manager.mjs start' }, '/opt/medpad') === true)
ok('P2 sibling dir with longer name NOT matched (substring trap)', ownsProcess({ cmd: 'node /opt/medpad-fork/server-manager.mjs' }, '/opt/medpad') === false)
ok('P3 shorter dir NOT matched (prefix trap)', ownsProcess({ cmd: 'node /opt/med/other.mjs' }, '/opt/medpad') === false)
ok('P4 cwd evidence proves ownership', ownsProcess({ cwd: '/opt/medpad', cmd: 'node server.js' }, '/opt/medpad') === true)
ok('P5 empty info / empty dir never match', ownsProcess({}, '/opt/medpad') === false && ownsProcess({ cmd: 'node x' }, '') === false)
ok('P6 normPath: backslashes normalized + trailing slash stripped', normPath('C:\\Apps\\MedPad\\') === 'C:/Apps/MedPad' && normPath('/opt/x/') === '/opt/x')

// ── P7: pid registry formats ─────────────────────────────────────────
{
  const d = mkdtempSync(path.join(os.tmpdir(), 'p0-ps-'))
  try {
    writeFileSync(pidFile(d), JSON.stringify({ pids: [123, 456] }))
    const v1 = readPids(d)
    ok('P7a v1 legacy pid entries readable', v1.entries.length === 2 && v1.entries.every((e) => e.legacy))
    writeFileSync(pidFile(d), JSON.stringify({ v: 2, pids: [{ pid: 777, kind: 'realtime', script: '/x/realtime/index.js', startedAtMs: 123456 }] }))
    const v2 = readPids(d)
    ok('P7b v2 pid entries readable with identity evidence', v2.entries.length === 1 && v2.entries[0].pid === 777 && v2.entries[0].script === '/x/realtime/index.js')
    writeFileSync(pidFile(d), '{ broken json')
    ok('P7c corrupted pid file -> empty, never throws', readPids(d).entries.length === 0)
  } finally { rmSync(d, { recursive: true, force: true }) }
}

// ── P8: a FOREIGN listener is never killed ───────────────────────────
// (this test process itself listens — its cmdline/cwd belong to the REPO,
//  definitely not to the throwaway install dir below)
{
  const d = mkdtempSync(path.join(os.tmpdir(), 'p0-ps-'))
  const foreignServer = net.createServer(() => {})
  await new Promise((r) => foreignServer.listen(0, '127.0.0.1', r))
  const fport = foreignServer.address().port
  try {
    const cls = await classifyPortOwners(d, [fport])
    ok('P8a foreign listener classified as FOREIGN', cls.foreign.length === 1 && cls.foreign[0].port === fport)
    const stopped = await stopMedPadServices(d, { ports: [fport] })
    ok('P8b stopMedPadServices reports it in foreignBusy', stopped.foreignBusy.some((b) => b.port === fport))
    ok('P8c foreign listener STILL ALIVE (never killed)', await stillListening(fport))
  } finally {
    foreignServer.close()
    rmSync(d, { recursive: true, force: true })
  }
}

// ── P9 + P10: proven-own processes ARE stopped ───────────────────────
{
  const d = mkdtempSync(path.join(os.tmpdir(), 'p0-ps-'))
  const port = await freePort()
  // a helper "service": script inside the install dir, listening on our port
  const script = path.join(d, 'sleeper.js')
  writeFileSync(script, `require('net').createServer(()=>{}).listen(${port}, '127.0.0.1'); setInterval(()=>{}, 1000)`)
  try {
    const child = spawn(process.execPath, [script], { cwd: d, stdio: 'ignore' })
    await sleep(700)
    ok('P9a helper service is listening', await stillListening(port))

    const cls = await classifyPortOwners(d, [port])
    ok('P9b our own listener classified as OURS (cmdline proves dir)', cls.ours.length === 1)

    // P10: saved v2 pid entry for the same child; plus an entry pointing at
    // THIS test process (whose cmdline does NOT reference d -> must be skipped)
    const foreignToD = process.pid
    writeFileSync(pidFile(d), JSON.stringify({ v: 2, pids: [
      { pid: child.pid, kind: 'realtime', script, startedAtMs: Date.now() },
      { pid: foreignToD, kind: 'legacy-ish' },
    ] }))
    const stopped = await stopMedPadServices(d, { ports: [port] })
    await sleep(800)
    ok('P10a own pid entry killed (helper process exited)', child.exitCode !== null || child.signalCode !== null)
    ok('P10b unprovable pid entry (this test) skipped, NOT killed', stopped.skipped.some((s) => s.pid === foreignToD))
    ok('P10c port actually freed after verified stop', !(await stillListening(port)))
    try { process.kill(foreignToD, 0); ok('P10d this test process still alive (nothing killed us)', true) } catch { ok('P10d this test process still alive (nothing killed us)', false) }
  } finally {
    rmSync(d, { recursive: true, force: true })
  }
}

console.log('')
if (fail === 0) console.log('PROCESS-SAFETY-ALL-PASS')
else console.log(`PROCESS-SAFETY-FAILURES: ${fail}`)
process.exit(fail === 0 ? 0 : 1)
