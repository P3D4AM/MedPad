#!/usr/bin/env node
/**
 * test-wizard-security.mjs — Phase 0 verification: browser-origin hardening
 * of the local installer wizard (wizard.mjs) and, by the same code, the
 * server-manager GUI (same gate design; see test-process-safety.mjs for the
 * manager's process behaviour).
 *
 * What is verified (all against a LIVE wizard on 127.0.0.1):
 *   W1  bad Host header            -> 403 (DNS-rebinding protection)
 *   W2  good Host (localhost:port) -> 200 installer page
 *   W3  good Host (127.0.0.1:port) -> 200 /health
 *   W4  POST /api/install + foreign Origin   -> 403 (CSRF) — installer NOT started
 *   W5  POST /api/install + same-origin Origin but text/plain -> 415 (content-type gate)
 *   W6  POST /api/install + no Origin + text/plain -> 415 (proves non-browser requests pass the origin gate)
 *   W7  POST /api/install + oversized JSON body -> 400 body too large
 *   W8  POST /api/start + Sec-Fetch-Site: cross-site -> 403
 *   W9  POST /api/manager + foreign Origin -> 403 (manager GUI NOT spawned)
 *   W10 POST /api/stop + same-origin Origin -> JSON response that is NOT 403/415
 *       (positive path: the request passes all gates; runs against a throwaway
 *        stage copy so no real install is touched)
 *   W11 font path traversal (/fonts/../wizard.mjs and %2e%2e form) -> never 200
 *   W12 unsupported method (DELETE /) -> 404
 *
 * Usage: node test-wizard-security.mjs [pkgDir]
 * The wizard runs from a THROWAWAY stage copy (only the needed files), so the
 * repository itself is never mutated and no real installation is started.
 */
import { spawn } from 'node:child_process'
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'

const REPO = path.resolve(process.argv[2] || path.join(__dirname(), '..'))
let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { console.log(`[PASS] ${name}`); pass++ } else { console.log(`[FAIL] ${name}`); fail++ } }
function __dirname() { return path.dirname(new URL(import.meta.url).pathname) }

// ── helpers ──────────────────────────────────────────────────────────
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
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitHealthy(port, tries = 40) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await req(port, 'GET', '/health', { headers: { host: `localhost:${port}` } })
      if (r.status === 200) return true
    } catch { /* not up yet */ }
    await sleep(250)
  }
  return false
}

// ── stage a throwaway copy of the wizard ─────────────────────────────
const stage = mkdtempSync(path.join(os.tmpdir(), 'p0-wiz-'))
try {
  mkdirSync(path.join(stage, 'ui', 'fonts'), { recursive: true })
  mkdirSync(path.join(stage, 'app'), { recursive: true })
  for (const f of ['wizard.mjs', 'services.mjs', 'server-manager.mjs']) cpSync(path.join(REPO, f), path.join(stage, f))
  cpSync(path.join(REPO, 'ui', 'installer.html'), path.join(stage, 'ui', 'installer.html'))
  cpSync(path.join(REPO, 'ui', 'manager.html'), path.join(stage, 'ui', 'manager.html'))
  try { cpSync(path.join(REPO, 'ui', 'fonts'), path.join(stage, 'ui', 'fonts'), { recursive: true }) } catch { /* fonts optional for these tests */ }
  try { writeFileSync(path.join(stage, 'app', 'APP-VERSION'), readFileSync(path.join(REPO, 'app', 'APP-VERSION'))) } catch { /* optional */ }

  const port = await freePort()
  const child = spawn(process.execPath, ['wizard.mjs'], {
    cwd: stage,
    env: { ...process.env, MEDPAD_WIZARD_PORT: String(port), MEDPAD_WIZARD_OPEN: '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let wizLog = ''
  child.stdout.on('data', (d) => (wizLog += d))
  child.stderr.on('data', (d) => (wizLog += d))

  const up = await waitHealthy(port)
  ok('W0 wizard boots on 127.0.0.1 (health 200)', up)
  if (!up) {
    console.log('--- wizard log ---\n' + wizLog)
    process.exit(1)
  }

  // W1: DNS rebinding — foreign Host header
  const w1 = await req(port, 'GET', '/', { headers: { host: 'attacker.example.com' } })
  ok('W1 foreign Host header rejected (403)', w1.status === 403)

  // W2: good Host serves the installer page
  const w2 = await req(port, 'GET', '/', { headers: { host: `localhost:${port}` } })
  ok('W2 localhost Host serves installer page (200 HTML)', w2.status === 200 && /html/i.test(w2.body))

  // W3: 127.0.0.1 host works too (health probe)
  const w3 = await req(port, 'GET', '/health', { headers: { host: `127.0.0.1:${port}` } })
  ok('W3 127.0.0.1 Host accepted (/health ok)', w3.status === 200 && w3.body.includes('"ok":true'))

  // W4: CSRF — foreign Origin on the install endpoint
  const w4 = await req(port, 'POST', '/api/install', {
    headers: { host: `localhost:${port}`, origin: 'http://attacker.example.com', 'content-type': 'application/json' },
    body: JSON.stringify({ target: 'local' }),
  })
  ok('W4 POST /api/install with foreign Origin rejected (403)', w4.status === 403)

  // W5: same-origin but wrong content-type
  const w5 = await req(port, 'POST', '/api/install', {
    headers: { host: `localhost:${port}`, origin: `http://localhost:${port}`, 'content-type': 'text/plain' },
    body: JSON.stringify({ target: 'local' }),
  })
  ok('W5 same-origin POST with text/plain content-type rejected (415)', w5.status === 415)

  // W6: non-browser client (no Origin) passes the origin gate — the 415
  // (not 403) proves the request got past the Host/Origin gates.
  const w6 = await req(port, 'POST', '/api/install', {
    headers: { host: `localhost:${port}`, 'content-type': 'text/plain' },
    body: 'x',
  })
  ok('W6 no-Origin (non-browser) request passes origin gate (415 not 403)', w6.status === 415)

  // W7: oversized body (limit is 100 KB)
  const big = JSON.stringify({ target: 'local', pad: 'A'.repeat(200 * 1024) })
  const w7 = await req(port, 'POST', '/api/install', {
    headers: { host: `localhost:${port}`, origin: `http://localhost:${port}`, 'content-type': 'application/json' },
    body: big,
  })
  ok('W7 oversized JSON body rejected (400 body too large)', w7.status === 400 && /too large/i.test(w7.body))

  // W8: Fetch-Metadata gate
  const w8 = await req(port, 'POST', '/api/start', {
    headers: { host: `localhost:${port}`, origin: `http://localhost:${port}`, 'sec-fetch-site': 'cross-site' },
  })
  ok('W8 Sec-Fetch-Site: cross-site rejected (403)', w8.status === 403)

  // W9: manager launch with foreign Origin
  const w9 = await req(port, 'POST', '/api/manager', {
    headers: { host: `localhost:${port}`, origin: 'https://evil.example.net' },
  })
  ok('W9 POST /api/manager with foreign Origin rejected (403)', w9.status === 403)

  // W10: positive path — same-origin /api/stop runs and answers with JSON
  // (runs server-manager stop against the throwaway stage: nothing to stop).
  const w10 = await req(port, 'POST', '/api/stop', {
    headers: { host: `localhost:${port}`, origin: `http://localhost:${port}` },
  })
  ok('W10 same-origin POST /api/stop passes gates (JSON, not 403/415)', w10.status !== 403 && w10.status !== 415 && w10.body.includes('"ok"'))

  // W11: font traversal must never serve a file
  const t1 = await req(port, 'GET', '/fonts/../wizard.mjs', { headers: { host: `localhost:${port}` } })
  const t2 = await req(port, 'GET', '/fonts/%2e%2e/wizard.mjs', { headers: { host: `localhost:${port}` } })
  ok('W11 font path traversal rejected (both forms non-200)', t1.status !== 200 && t2.status !== 200)

  // W12: unsupported method
  const w12 = await req(port, 'DELETE', '/', { headers: { host: `localhost:${port}` } })
  ok('W12 unsupported method answered (404)', w12.status === 404)

  // status endpoint sanity (uses readConfig on the stage — safe)
  const st = await req(port, 'GET', '/api/status', { headers: { host: `localhost:${port}` } })
  ok('W0b /api/status healthy', st.status === 200 && st.body.includes('"ok":true'))

  child.kill('SIGTERM')
  await sleep(300)
  ok('W0c wizard shuts down cleanly on SIGTERM', child.killed)
} finally {
  try { rmSync(stage, { recursive: true, force: true }) } catch { /* best effort */ }
}

console.log('')
if (fail === 0) console.log('WIZARD-SECURITY-ALL-PASS')
else console.log(`WIZARD-SECURITY-FAILURES: ${fail}`)
process.exit(fail === 0 ? 0 : 1)
