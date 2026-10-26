#!/usr/bin/env node
/**
 * MedPad Browser Installer (wizard) v1.9.13-dev.2 — GUI backend, localhost only.
 *
 * Brought back by user request: the installer can run either in the CLI or
 * as a SETUP PAGE IN THE BROWSER that opens automatically (install.mjs
 * launches this file detached and opens the browser).
 *
 * Serves ui/installer.html and exposes:
 *   GET  /                  the installer page (Persian RTL, no external assets)
 *   GET  /fonts/*           self-hosted Vazirmatn (v1.9.9 rule: zero CDN)
 *   GET  /api/status        package/platform/existing-install/config info
 *   POST /api/install       run the offline installer with the chosen settings
 *                           (admin password / CF token travel via ENV, never argv)
 *                           -> streams the installer output live (text/plain)
 *   POST /api/start         start the servers (server-manager.mjs start --open)
 *   POST /api/stop          stop the servers
 *   POST /api/manager       launch the Server Manager GUI (detached)
 *   POST /api/open          open the panel page in the browser (server side)
 *   GET  /health            liveness probe (used by install.mjs)
 *
 * Security: binds 127.0.0.1 ONLY. On a headless Linux VPS use an SSH tunnel:
 *   ssh -L 3199:localhost:3199 user@server
 *
 * v1.9.12 (phase 0 hardening) — binding to the loopback interface alone does
 * NOT stop BROWSER-borne attacks, so three extra gates protect every request:
 *   1. Host header must be localhost / 127.0.0.1 / [::1]   (DNS rebinding)
 *   2. Origin header, when present, must be this exact local origin (CSRF —
 *      a malicious website can otherwise still POST to http://127.0.0.1:3199)
 *   3. Sec-Fetch-Site, when present, must be same-origin/none  (modern browsers)
 * Non-browser clients (curl, tests, install.mjs probes) send none of these
 * headers and are not affected. POST /api/install additionally requires
 * Content-Type: application/json (a text/plain cross-origin POST would
 * otherwise skip the CORS preflight and still be parsed as JSON).
 */
import http from 'http'
import net from 'net'
import os from 'os'
import { spawn } from 'child_process'
import { existsSync, readFileSync } from 'fs'
import path from 'path'
import url from 'url'
import { readConfig, openBrowser, portBusy } from './services.mjs'

const __dirname = path.dirname(url.fileURLToPath(import.meta.url))
const WIZARD_VER = '1.9.13-dev.2'

/** v1.9.13-dev.2: private IPv4 addresses of this machine (phone/app URL). */
function lanAddresses() {
  const out = []
  try {
    for (const ifaces of Object.values(os.networkInterfaces())) {
      for (const ni of ifaces || []) {
        if (ni.family === 'IPv4' && !ni.internal) {
          const ip = ni.address
          if (/^(10\.|172\.(1[6-9]|2\d|3[01])\.|192\.168\.)/.test(ip)) out.push(ip)
        }
      }
    }
  } catch { /* best-effort */ }
  return [...new Set(out)].slice(0, 3)
}
const PORT = Number(process.env.MEDPAD_WIZARD_PORT || process.env.SETUP_PORT || 3199)

function installerVer() {
  try { return readFileSync(path.join(__dirname, 'app', 'APP-VERSION'), 'utf8').trim() } catch { return null }
}
function readTunnelState() {
  try { return JSON.parse(readFileSync(path.join(__dirname, 'tunnel-state.json'), 'utf8')) } catch { return null }
}
function readBody(req, limit = 100 * 1024) {
  // v1.9.12 (phase 0): on an oversized body the request is DRAINED (not
  // destroyed) so the rejection can still be answered with a clean HTTP 400 —
  // destroying the socket here used to surface as ECONNRESET on the client.
  return new Promise((resolve, reject) => {
    let size = 0
    let done = false
    const chunks = []
    req.on('data', (c) => {
      if (done) return // drain after rejection — never buffer
      size += c.length
      if (size > limit) {
        done = true
        chunks.length = 0
        reject(new Error('body too large'))
        return
      }
      chunks.push(c)
    })
    req.on('end', () => {
      if (done) return
      done = true
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')) } catch { reject(new Error('invalid json')) }
    })
    req.on('error', (e) => {
      if (done) return
      done = true
      reject(e)
    })
  })
}
function portNumber(v, def) {
  const n = Number.parseInt(v, 10)
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : def
}

// ── v1.9.12 (phase 0): browser-origin gates ──────────────────────────
const LOCAL_HOSTNAMES = new Set(['localhost', '127.0.0.1', '[::1]'])

/** Gate 1 — Host validation. Blocks DNS-rebinding domains (attacker.com
 *  resolving to 127.0.0.1) from serving our page under their origin. */
function hostAllowed(hostHeader) {
  if (typeof hostHeader !== 'string' || hostHeader.trim() === '') return false
  let h = hostHeader.trim().toLowerCase()
  if (h.startsWith('[')) h = h.slice(0, h.indexOf(']') + 1)      // [::1]:3199
  else if (h.includes(':')) h = h.replace(/:\d+$/, '')           // localhost:3199
  return LOCAL_HOSTNAMES.has(h)
}

/** Gate 2 — Origin validation (CSRF). Browsers always send Origin on
 *  cross-origin POSTs; same-origin fetches send our own local origin.
 *  Absent Origin = a non-browser client (curl/tests) → allowed. */
function originAllowed(originHeader, port) {
  if (typeof originHeader !== 'string' || originHeader.trim() === '') return true
  const o = originHeader.trim().toLowerCase().replace(/\/+$/, '')
  return o === `http://localhost:${port}` || o === `http://127.0.0.1:${port}` || o === `http://[::1]:${port}`
}

/** Gate 3 — Fetch-Metadata (defense in depth, modern browsers only). */
function fetchSiteAllowed(v) {
  if (typeof v !== 'string' || v.trim() === '') return true
  const s = v.trim().toLowerCase()
  return s === 'same-origin' || s === 'same-site' || s === 'none'
}

/** The three gates, applied to an incoming request. Returns a rejection
 *  reason string or null when the request may proceed. */
function originGateRejection(req, port) {
  if (!hostAllowed(req.headers.host)) return 'forbidden: unexpected Host header'
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    if (!originAllowed(req.headers.origin, port)) return 'forbidden: cross-origin request rejected'
    if (!fetchSiteAllowed(req.headers['sec-fetch-site'])) return 'forbidden: cross-site request rejected'
  }
  return null
}

async function startServer() {
  // port selection: MEDPAD_WIZARD_PORT was reserved by install.mjs — trust it,
  // otherwise scan upward from 3199 so double-launches do not collide.
  let port = PORT
  if (!process.env.MEDPAD_WIZARD_PORT) {
    for (let p = PORT; p < PORT + 20; p++) {
      if (!(await portBusy(p))) { port = p; break }
    }
  }

  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url || '/', `http://localhost:${port}`)
    const json = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)) }

    // ── v1.9.12 (phase 0): Host/Origin/Fetch-Metadata gates (see header) ──
    const gate = originGateRejection(req, port)
    if (gate) return json(403, { ok: false, error: gate })

    // ── static: the installer page ──
    if (req.method === 'GET' && (u.pathname === '/' || u.pathname === '/index.html')) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end(readFileSync(path.join(__dirname, 'ui', 'installer.html')))
      return
    }

    // ── static: self-hosted font (traversal-safe) ──
    if (req.method === 'GET' && u.pathname.startsWith('/fonts/')) {
      const MIME = { '.css': 'text/css; charset=utf-8', '.woff2': 'font/woff2' }
      const rel = path.normalize(u.pathname).replace(/^([/\\])+/, '')
      const file = rel.includes('..') ? '' : path.join(__dirname, 'ui', rel)
      const ext = path.extname(file).toLowerCase()
      if (file && MIME[ext]) {
        try {
          res.writeHead(200, { 'content-type': MIME[ext], 'cache-control': 'public, max-age=86400' })
          res.end(readFileSync(file))
          return
        } catch { /* fall through */ }
      }
    }

    if (req.method === 'GET' && u.pathname === '/health') {
      return json(200, { ok: true, service: 'medpad-wizard', ver: WIZARD_VER })
    }

    // ── status for the page (adapts the UI) ──
    if (req.method === 'GET' && u.pathname === '/api/status') {
      const cfg = readConfig(__dirname)
      const existing = existsSync(path.join(__dirname, 'panel', 'db', 'custom.db'))
      return json(200, {
        ok: true,
        wizardVer: WIZARD_VER,
        installerVer: installerVer(),
        platform: process.platform,
        isWindows: process.platform === 'win32',
        preselectedTarget: process.env.MEDPAD_WIZARD_TARGET || null,
        existingInstall: existing,
        config: cfg && Object.keys(cfg).length ? { panelPort: cfg.panelPort || 3000, rtPort: cfg.rtPort || 3003, adminUser: cfg.adminUser || 'admin', version: cfg.version } : null,
        panelPortBusy: await portBusy(Number(cfg.panelPort) || 3000),
        rtPortBusy: await portBusy(Number(cfg.rtPort) || 3003),
        tunnel: readTunnelState(),
        installed: existsSync(path.join(__dirname, 'panel', '.next', 'standalone', 'server.js')),
        // v1.9.13-dev.2 (user report #2): LAN addresses of this machine —
        // exactly what the owner must type into the Android app / phone
        // browser (localhost never works from another device).
        lanUrls: lanAddresses().map((ip) => `http://${ip}:${Number(cfg.panelPort) || 3000}`),
      })
    }

    // ── run the installer (streamed) ──
    if (req.method === 'POST' && u.pathname === '/api/install') {
      // v1.9.12 (phase 0): require a JSON content type — a cross-origin
      // text/plain body skips the CORS preflight yet would still parse as
      // JSON below, so the gate above alone is not enough.
      const ct = String(req.headers['content-type'] || '').toLowerCase()
      if (!ct.startsWith('application/json')) {
        return json(415, { ok: false, error: 'content-type must be application/json' })
      }
      let body
      try { body = await readBody(req) } catch (e) { return json(400, { ok: false, error: e.message }) }

      const target = ['local', 'vps', 'cloudflare'].includes(body.target) ? body.target : 'local'
      const args = ['install.mjs', '--non-interactive', '--target', target]
      args.push('--admin-user', String(body.adminUser || 'admin').slice(0, 32))
      args.push('--port', String(portNumber(body.panelPort, 3000)))
      args.push('--rt-port', String(portNumber(body.rtPort, 3003)))
      if (body.dir && typeof body.dir === 'string' && body.dir.length < 300) args.push('--dir', body.dir)
      // v1.9.12: sample data is OPT-IN; destructive Cloudflare DNS changes
      // need the explicit confirmation checkbox from the page.
      if (body.sampleData === true) args.push('--with-sample-data')
      if (target === 'cloudflare' && body.cfConfirm === true) args.push('--cf-confirm')
      if (target === 'cloudflare') {
        const kind = body.tunnelKind === 'domain' ? 'domain' : 'quick'
        args.push('--tunnel-kind', kind)
        if (kind === 'domain') {
          if (body.cfDomain) args.push('--cf-domain', String(body.cfDomain).slice(0, 200))
          if (body.subdomain) args.push('--subdomain', String(body.subdomain).slice(0, 100))
        }
      }
      // secrets via ENV (never on the command line)
      const env = { ...process.env }
      if (body.adminPass && typeof body.adminPass === 'string') env.MEDPAD_ADMIN_PASS = body.adminPass.slice(0, 128)
      if (body.cfToken && typeof body.cfToken === 'string') env.MEDPAD_CF_TOKEN = body.cfToken.slice(0, 300)

      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
      const child = spawn(process.execPath, args, { cwd: __dirname, env })
      child.stdout.on('data', (d) => res.write(d))
      child.stderr.on('data', (d) => res.write(d))
      child.on('error', (e) => { res.write(`\n[X] spawn failed: ${e.message}\n`); res.end() })
      child.on('close', (code) => {
        res.write(`\n[installer exited with code ${code}]\n`)
        res.end()
      })
      req.on('close', () => { try { child.kill() } catch { /* noop */ } })
      return
    }

    // ── start / stop / manager / open ──
    if (req.method === 'POST' && u.pathname === '/api/start') {
      const r = await runManager(['start', '--open'])
      return json(r.ok ? 200 : 500, r)
    }
    if (req.method === 'POST' && u.pathname === '/api/stop') {
      const r = await runManager(['stop'])
      return json(r.ok ? 200 : 500, r)
    }
    if (req.method === 'POST' && u.pathname === '/api/manager') {
      try {
        const child = spawn(process.execPath, ['server-manager.mjs', 'gui'], { cwd: __dirname, detached: true, stdio: 'ignore' })
        child.unref()
        // give it a moment, then probe a small range of default ports
        await new Promise((r) => setTimeout(r, 1500))
        for (const p of [3198, 3199, 3200]) {
          if (await probe(p)) return json(200, { ok: true, url: `http://localhost:${p}` })
        }
        return json(200, { ok: true, url: 'http://localhost:3198' })
      } catch (e) {
        return json(500, { ok: false, error: String(e.message) })
      }
    }
    if (req.method === 'POST' && u.pathname === '/api/open') {
      const cfg = readConfig(__dirname)
      const p = Number(cfg.panelPort) || 3000
      openBrowser(`http://localhost:${p}`)
      return json(200, { ok: true, url: `http://localhost:${p}` })
    }

    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: false, error: 'not found' }))
  })

  server.listen(port, '127.0.0.1', () => {
    console.log(`[OK] MedPad browser installer ready -> http://localhost:${port}`)
    if (process.env.MEDPAD_WIZARD_OPEN === '1') openBrowser(`http://localhost:${port}`)
  })
  server.on('error', (e) => {
    console.error(`[X] wizard server error: ${e.message}`)
    process.exit(1)
  })
}

/** run server-manager.mjs and collect its output (timeout 3 min) */
function runManager(args) {
  return new Promise((resolve) => {
    let out = ''
    const child = spawn(process.execPath, ['server-manager.mjs', ...args], { cwd: __dirname })
    const t = setTimeout(() => { try { child.kill() } catch { }; resolve({ ok: false, output: out + '\n[timeout]' }) }, 180000)
    child.stdout.on('data', (d) => { out += d })
    child.stderr.on('data', (d) => { out += d })
    child.on('close', (code) => { clearTimeout(t); resolve({ ok: code === 0, output: out }) })
    child.on('error', (e) => { clearTimeout(t); resolve({ ok: false, output: String(e.message) }) })
  })
}

function probe(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/api/status', timeout: 1200 }, (res) => { res.resume(); resolve(true) })
    req.on('timeout', () => { req.destroy(); resolve(false) })
    req.on('error', () => resolve(false))
  })
}

startServer()
