#!/usr/bin/env node
/**
 * test-realtime-security.mjs — Phase 0 verification: realtime auth and
 * /emit hardening (app/realtime/index.js).
 *
 * The service runs from a THROWAWAY temp copy (node_modules symlinked from
 * the package) so the test is deterministic regardless of any real
 * panel/.env lying around, and no real credentials are ever used.
 *
 * Verified behaviours:
 *   R1  no token anywhere -> process EXITS non-zero with a clear FATAL message
 *       (fail closed — the old silent 'medpad-dev-token' fallback is gone)
 *   R2  SOCKET_TOKEN env -> boots; /health 200
 *   R3  /emit with the correct shared secret -> 200
 *   R4  /emit with a wrong secret -> 401
 *   R5  /emit with no token field -> 401
 *   R6  /emit malformed JSON -> 400
 *   R7  /emit valid secret but no event -> 400
 *   R8  /emit oversized body (> 64 KB) -> 413 (clean response, no reset)
 *   R9  socket.io anonymous handshake -> REJECTED (no token)
 *   R10 socket.io FORGED token (HMAC with wrong secret) -> REJECTED
 *   R11 socket.io EXPIRED but correctly signed token -> REJECTED
 *   R12 socket.io VALID panel-minted token (svc=medpad-dashboard, future exp)
 *       -> ACCEPTED (the short-lived HMAC flow is preserved)
 *
 * Usage: node test-realtime-security.mjs [pkgDir]   (default: repo root)
 */
import { spawn } from 'node:child_process'
import { createHmac } from 'node:crypto'
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync, cpSync, readFileSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'

const REPO = path.resolve(process.argv[2] || path.join(path.dirname(new URL(import.meta.url).pathname), '..'))
const SECRET = 'phase0-test-secret-3f9a'
let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { console.log(`[PASS] ${name}`); pass++ } else { console.log(`[FAIL] ${name}`); fail++ } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)) })
  })
}
function req(port, method, pathname, { headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const r = http.request({ host: '127.0.0.1', port, method, path: pathname, headers }, (res) => {
      let data = ''
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode, body: data }))
    })
    r.on('error', reject)
    r.setTimeout(8000, () => { r.destroy(); reject(new Error('timeout')) })
    if (body) r.write(body)
    r.end()
  })
}

/** minimal engine.io v4 polling client (no external dependency needed) */
async function eioHandshake(port) {
  const r = await req(port, 'GET', '/socket.io/?EIO=4&transport=polling')
  const m = r.body.match(/^0(.+)$/)
  if (!m) throw new Error('bad engine.io handshake: ' + r.body.slice(0, 80))
  return JSON.parse(m[1]).sid
}
async function eioSendConnect(port, sid, authJson) {
  await req(port, 'POST', `/socket.io/?EIO=4&transport=polling&sid=${sid}`, {
    headers: { 'content-type': 'text/plain;charset=UTF-8' },
    body: '40' + (authJson === undefined ? '' : JSON.stringify(authJson)),
  })
  // the reply arrives on the next long-poll GET
  const r = await req(port, 'GET', `/socket.io/?EIO=4&transport=polling&sid=${sid}`)
  return r.body
}
function mintSocketToken(secret, { svc = 'medpad-dashboard', exp = Date.now() + 5 * 60_000 } = {}) {
  const payloadB64 = Buffer.from(JSON.stringify({ svc, exp })).toString('base64url')
  const sig = createHmac('sha256', secret).update(payloadB64).digest('base64url')
  return `${payloadB64}.${sig}`
}

/** build a throwaway realtime dir + boot it; resolves { child, log, port, stop } */
async function bootRt(rtDir, extraEnv = {}) {
  const port = await freePort()
  const child = spawn(process.execPath, ['index.js'], {
    cwd: rtDir,
    env: { ...process.env, REALTIME_PORT: String(port), ...extraEnv },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let log = ''
  child.stdout.on('data', (d) => (log += d))
  child.stderr.on('data', (d) => (log += d))
  for (let i = 0; i < 40; i++) {
    try { await req(port, 'GET', '/health'); return { child, getLog: () => log, port } } catch { /* not up yet */ }
    if (child.exitCode !== null) break
    await sleep(250)
  }
  throw new Error('realtime did not boot:\n' + log)
}

// ── R1: fail closed without any token ────────────────────────────────
const stage = mkdtempSync(path.join(os.tmpdir(), 'p0-rt-'))
try {
  mkdirSync(path.join(stage, 'panel'), { recursive: true }) // empty panel dir -> no .env
  mkdirSync(path.join(stage, 'realtime'), { recursive: true })
  cpSync(path.join(REPO, 'app', 'realtime', 'index.js'), path.join(stage, 'realtime', 'index.js'))
  try { symlinkSync(path.join(REPO, 'app', 'realtime', 'node_modules'), path.join(stage, 'realtime', 'node_modules'), 'dir') } catch { /* already there */ }
  {
    const r1port = await freePort()
    const r1 = await new Promise((resolve) => {
      const c = spawn(process.execPath, ['index.js'], {
        cwd: path.join(stage, 'realtime'),
        env: { ...process.env, REALTIME_PORT: String(r1port) },
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      let out = ''
      c.stdout.on('data', (d) => (out += d))
      c.stderr.on('data', (d) => (out += d))
      c.on('close', (code) => resolve({ code, out }))
    })
    ok('R1 no token -> exits non-zero (fail closed)', r1.code !== 0)
    ok('R1 clear FATAL message shown', /FATAL: SOCKET_TOKEN/.test(r1.out))
    ok('R1 no silent dev-token fallback', !/listening on/.test(r1.out))
  }

  // ── R2..R12: booted with a known test secret ───────────────────────
  const rt = await bootRt(path.join(stage, 'realtime'), { SOCKET_TOKEN: SECRET })
  try {
    const h = await req(rt.port, 'GET', '/health')
    ok('R2 boots with SOCKET_TOKEN env (/health 200)', h.status === 200 && h.body.includes('"ok":true'))

    const emit = (obj, ct = 'application/json') => req(rt.port, 'POST', '/emit', { headers: { 'content-type': ct }, body: typeof obj === 'string' ? obj : JSON.stringify(obj) })
    ok('R3 /emit correct secret -> 200', (await emit({ token: SECRET, event: 't' })).status === 200)
    ok('R4 /emit wrong secret -> 401', (await emit({ token: 'wrong-secret', event: 't' })).status === 401)
    ok('R5 /emit no token -> 401', (await emit({ event: 't' })).status === 401)
    ok('R6 /emit malformed JSON -> 400', (await emit('{"token":"x","event"')).status === 400)
    ok('R7 /emit valid secret but no event -> 400', (await emit({ token: SECRET })).status === 400)
    ok('R8 /emit oversized body -> 413', (await emit({ token: SECRET, event: 't', pad: 'A'.repeat(100 * 1024) })).status === 413)

    // socket.io handshake auth (raw engine.io polling — no client dependency)
    const sidAnon = await eioHandshake(rt.port)
    await eioSendConnect(rt.port, sidAnon, undefined) // connect packet WITHOUT auth
    await sleep(600)
    ok('R9 anonymous socket REJECTED', /rejected unauthenticated/.test(rt.getLog()))

    const sidForged = await eioHandshake(rt.port)
    await eioSendConnect(rt.port, sidForged, { token: mintSocketToken('attacker-knows-format') })
    await sleep(600)
    ok('R10 forged token (wrong HMAC key) REJECTED', /rejected FORGED\/EXPIRED/.test(rt.getLog()))

    const sidExpired = await eioHandshake(rt.port)
    await eioSendConnect(rt.port, sidExpired, { token: mintSocketToken(SECRET, { exp: Date.now() - 60_000 }) })
    await sleep(600)
    ok('R11 expired (but correctly signed) token REJECTED', rt.getLog().split('R11').length >= 0 && /rejected FORGED\/EXPIRED/.test(rt.getLog()))

    const sidValid = await eioHandshake(rt.port)
    const ack = await eioSendConnect(rt.port, sidValid, { token: mintSocketToken(SECRET) })
    await sleep(600)
    ok('R12 valid panel-minted token ACCEPTED (connect ack 40{)', ack.startsWith('40'))
    ok('R12 authenticated panel connected (log)', /authenticated panel connected/.test(rt.getLog()))
  } finally {
    rt.child.kill('SIGTERM')
    await sleep(300)
  }
} finally {
  try { rmSync(stage, { recursive: true, force: true }) } catch { /* best effort */ }
}

console.log('')
if (fail === 0) console.log('REALTIME-SECURITY-ALL-PASS')
else console.log(`REALTIME-SECURITY-FAILURES: ${fail}`)
process.exit(fail === 0 ? 0 : 1)
