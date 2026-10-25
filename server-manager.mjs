#!/usr/bin/env node
/**
 * MedPad Server Manager v1.9.12 — start / stop / restart / status / GUI
 *
 * The ONE place where MedPad services are started and stopped (v1.9.11):
 *
 *   CLI:  node server-manager.mjs start [--open|--no-open]
 *         node server-manager.mjs stop
 *         node server-manager.mjs restart
 *         node server-manager.mjs status
 *   GUI:  node server-manager.mjs gui [--port 3198]
 *         (serves ui/manager.html on 127.0.0.1 — start/stop/restart buttons,
 *          live status, opens in the browser automatically)
 *
 * Behaviour requested by the user (v1.9.11):
 *  - start/stop must work from BOTH the CLI (start-server.bat / .sh) and a
 *    GUI (server-manager.bat / .sh -> this file in gui mode);
 *  - after EVERY start the MAIN PAGE opens automatically in the default
 *    browser (services.mjs openBrowser) — disable with --no-open.
 *
 * v1.9.12 (process-safety audit):
 *  - children are spawned with ABSOLUTE script paths (realtime/index.js,
 *    panel server.js, cloudflared --config/--pidfile inside this dir) so
 *    services.mjs can PROVE they belong to this install before killing;
 *  - .medpad.pids is written in v2 format {pid, kind, script, startedAtMs}
 *    (a reused PID with a different start time is never killed);
 *  - panel env (socket mode / realtime url) is read from panel/.env.
 *
 * Technical notes:
 *  - panel + realtime are spawned DETACHED with output redirected to
 *    logs/panel.log and logs/realtime.log, so they keep running after this
 *    process (and its console window) exits. PIDs land in .medpad.pids,
 *    which services.mjs stopMedPadServices() uses for a reliable stop.
 *  - ports come from medpad-config.json (written by the installer), so a
 *    custom --port / --rt-port chosen in the wizard is fully respected.
 *  - domain tunnels (tunnel-state.json kind=domain + tunnel-creds/) are
 *    started together with the panel when the cloudflared binary exists;
 *    quick tunnels are started best-effort (they need internet).
 */
import { spawn } from 'child_process'
import http from 'http'
import net from 'net'
import { existsSync, readFileSync, writeFileSync, mkdirSync, openSync, fstatSync } from 'fs'
import path from 'path'
import url from 'url'
import { stopMedPadServices, readConfig, openBrowser } from './services.mjs'

const __dirname = path.dirname(url.fileURLToPath(import.meta.url))
const argv = process.argv.slice(2)
const cmd = (argv[0] || 'help').toLowerCase()
const cfg = readConfig(__dirname)
const PANEL_PORT = Number(cfg.panelPort) || 3000
const RT_PORT = Number(cfg.rtPort) || 3003
const PANEL_URL = `http://localhost:${PANEL_PORT}`
const MANAGER_VER = '1.9.12'

const say = (m) => console.log(m)
const ok = (m) => console.log(`[OK] ${m}`)
const warn = (m) => console.log(`[WARN] ${m}`)
const err = (m) => console.log(`[X] ${m}`)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** panel/.env values the spawned services need (token + db + socket mode). */
function panelEnv() {
  const out = { SOCKET_TOKEN: '', DATABASE_URL: '', MEDPAD_SOCKET_MODE: 'direct', REALTIME_URL: `http://127.0.0.1:${RT_PORT}` }
  try {
    const s = readFileSync(path.join(__dirname, 'panel', '.env'), 'utf8')
    out.SOCKET_TOKEN = ((s.match(/^SOCKET_TOKEN=([^\r\n]+)/m) || [])[1] || '').trim()
    out.DATABASE_URL = ((s.match(/^DATABASE_URL=([^\r\n]+)/m) || [])[1] || '').trim()
    out.MEDPAD_SOCKET_MODE = ((s.match(/^MEDPAD_SOCKET_MODE=([^\r\n]+)/m) || [])[1] || '').trim() || 'direct'
    out.REALTIME_URL = ((s.match(/^REALTIME_URL=([^\r\n]+)/m) || [])[1] || '').trim() || `http://127.0.0.1:${RT_PORT}`
  } catch { /* not installed yet */ }
  return out
}

/** append-mode log fd (truncated when huge) */
function logFd(name) {
  const dir = path.join(__dirname, 'logs')
  mkdirSync(dir, { recursive: true })
  const p = path.join(dir, name)
  try { if (existsSync(p) && fstatSync(openSync(p, 'r')).size > 5 * 1024 * 1024) writeFileSync(p, '') } catch { /* ignore */ }
  return openSync(p, 'a')
}

/** is the panel answering on its port? */
function panelHealthy(timeoutMs = 2000) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port: PANEL_PORT, path: '/', timeout: timeoutMs }, (res) => {
      res.resume()
      resolve(res.statusCode !== undefined && res.statusCode < 500)
    })
    req.on('timeout', () => { req.destroy(); resolve(false) })
    req.on('error', () => resolve(false))
  })
}

/** is anything listening on the realtime port? (TCP connect probe) */
function rtUp() {
  return new Promise((resolve) => {
    const s = net.connect({ host: '127.0.0.1', port: RT_PORT, timeout: 1200 })
    s.once('connect', () => { s.destroy(); resolve(true) })
    s.once('error', () => resolve(false))
    s.once('timeout', () => { s.destroy(); resolve(false) })
  })
}

/** cloudflared binary — bundled (bin/) or system-wide. Null when absent. */
function cloudflaredBin() {
  const local = path.join(__dirname, 'bin', process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared')
  if (existsSync(local)) return local
  const sys = process.platform === 'win32' ? 'cloudflared.exe' : '/usr/bin/cloudflared'
  const which = process.platform === 'win32' ? null : (existsSync(sys) ? sys : null)
  return which
}

/** start panel + realtime (detached, logged) — and the tunnel when configured */
async function startServers({ open = true } = {}) {
  if (await panelHealthy(1200)) {
    ok(`MedPad is already running: ${PANEL_URL}`)
    if (open) openBrowser(PANEL_URL)
    return { ok: true, already: true, url: PANEL_URL }
  }
  if (!existsSync(path.join(__dirname, 'panel', '.next', 'standalone', 'server.js'))) {
    err('MedPad is not installed yet - run install.bat / install.sh first')
    return { ok: false, error: 'not-installed' }
  }
  // clean leftovers holding our ports (verified kill from v1.9.9)
  await stopMedPadServices(__dirname, { ports: [PANEL_PORT, RT_PORT] })
  await sleep(500)

  const env = panelEnv()
  // v1.9.12: pid registry — v2 format with identity evidence (kind +
  // ABSOLUTE script path + spawn time) so services.mjs can prove ownership
  // and never kill a recycled PID from another program.
  const pidEntries = []
  const notePid = (pid, kind, script) => {
    if (!Number.isInteger(pid) || pid <= 1) return
    pidEntries.push({ pid, kind, script, startedAtMs: Date.now() })
  }

  // realtime first (the panel dashboard connects to it) — v1.9.12: ABSOLUTE
  // script path so the child's command line proves it belongs to this dir.
  try {
    const rtScript = path.join(__dirname, 'realtime', 'index.js')
    const rt = spawn(process.execPath, [rtScript], {
      cwd: __dirname, detached: true, stdio: ['ignore', logFd('realtime.log'), logFd('realtime.log')],
      env: { ...process.env, REALTIME_PORT: String(RT_PORT), SOCKET_TOKEN: env.SOCKET_TOKEN },
    })
    rt.unref()
    notePid(rt.pid, 'realtime', rtScript)
    ok(`Realtime service started (port ${RT_PORT})`)
  } catch (e) {
    err(`Could not start the realtime service: ${e.message}`)
    return { ok: false, error: String(e.message) }
  }
  await sleep(700)

  // panel — v1.9.12: ABSOLUTE script path (ownership proof for the killer).
  try {
    const panelScript = path.join(__dirname, 'panel', '.next', 'standalone', 'server.js')
    const panel = spawn(process.execPath, [panelScript], {
      cwd: __dirname, detached: true, stdio: ['ignore', logFd('panel.log'), logFd('panel.log')],
      env: {
        ...process.env,
        PORT: String(PANEL_PORT), HOSTNAME: '0.0.0.0',
        DATABASE_URL: env.DATABASE_URL, SOCKET_TOKEN: env.SOCKET_TOKEN,
        MEDPAD_SOCKET_MODE: env.MEDPAD_SOCKET_MODE, REALTIME_URL: env.REALTIME_URL,
      },
    })
    panel.unref()
    notePid(panel.pid, 'panel', panelScript)
  } catch (e) {
    err(`Could not start the panel: ${e.message}`)
    return { ok: false, error: String(e.message) }
  }

  // tunnel (domain: permanent cloudflared; quick: best-effort, needs internet)
  let publicUrl = cfg.tunnel?.publicUrl || ''
  try {
    const state = JSON.parse(readFileSync(path.join(__dirname, 'tunnel-state.json'), 'utf8'))
    const bin = cloudflaredBin()
    if (state.kind === 'domain' && existsSync(path.join(__dirname, 'tunnel-creds', 'config.yml'))) {
      if (bin) {
        // v1.9.12: ABSOLUTE --config path (ownership proof for the killer)
        const cfgAbs = path.join(__dirname, 'tunnel-creds', 'config.yml')
        const t = spawn(bin, ['--config', cfgAbs, 'tunnel', 'run'], {
          cwd: __dirname, detached: true, stdio: ['ignore', logFd('tunnel.log'), logFd('tunnel.log')],
        })
        t.unref()
        notePid(t.pid, 'tunnel', cfgAbs)
        publicUrl = publicUrl || `https://${state.hostname}`
        ok(`Cloudflare tunnel started (${publicUrl})`)
      } else {
        warn('Cloudflare tunnel skipped - cloudflared binary not found (install it or re-run the domain setup)')
      }
    } else if (state.kind === 'quick') {
      if (bin) {
        // v1.9.12: --pidfile inside our dir gives the command line a path
        // reference to this install (quick tunnels have no config file).
        const pidfileAbs = path.join(__dirname, '.cloudflared.pid')
        const t = spawn(bin, ['tunnel', '--url', `http://localhost:${PANEL_PORT}`, '--protocol', 'http2', '--pidfile', pidfileAbs], {
          cwd: __dirname, detached: true, stdio: ['ignore', logFd('tunnel.log'), logFd('tunnel.log')],
        })
        t.unref()
        notePid(t.pid, 'tunnel', pidfileAbs)
        ok('Quick tunnel started - the random public address appears in logs/tunnel.log')
      } else {
        warn('Quick tunnel skipped - no internet/cloudflared (the panel still works on your LAN)')
      }
    }
  } catch { /* no tunnel-state.json — plain local install */ }

  writeFileSync(path.join(__dirname, '.medpad.pids'), JSON.stringify({ v: 2, pids: pidEntries, at: new Date().toISOString() }))

  // wait for the panel to become healthy (first boot can take a while)
  say(`[..] Waiting for the panel on port ${PANEL_PORT} (up to 90s)...`)
  let healthy = false
  for (let i = 0; i < 90; i++) {
    await sleep(1000)
    if (await panelHealthy(1500)) { healthy = true; break }
  }
  if (!healthy) {
    err('The panel did not answer in time - check logs/panel.log')
    return { ok: false, error: 'panel-timeout', url: PANEL_URL }
  }
  ok(`MedPad is running: ${PANEL_URL}`)
  if (open) {
    openBrowser(PANEL_URL)
    ok('The panel page was opened in your browser')
  }
  const rtRunning = await rtUp()
  say('==============================================')
  say(`  Panel:    ${PANEL_URL}${rtRunning ? '' : '  (realtime NOT answering - check logs/realtime.log)'}`)
  if (publicUrl) say(`  Public:   ${publicUrl}`)
  say('  Stop:     stop-server.bat / stop-server.sh / server-manager GUI')
  say('==============================================')
  return { ok: true, url: PANEL_URL, publicUrl }
}

async function stopServers() {
  const stopped = await stopMedPadServices(__dirname, { ports: [PANEL_PORT, RT_PORT] })
  if (stopped.verified) ok('MedPad services stopped (panel + realtime + tunnel)')
  else {
    warn(`Some processes survived the stop (killed=${stopped.killed})`)
    if (stopped.stillBusy?.length) warn(`Ports still busy: ${stopped.stillBusy.map((b) => `${b.port} (${b.name || '?'}, pid ${b.pid})`).join(', ')}`)
    if (stopped.foreignBusy?.length) warn(`Ports held by OTHER programs (not killed): ${stopped.foreignBusy.map((b) => `${b.port} (${b.name || '?'}, pid ${b.pid})`).join(', ')}`)
    if (stopped.skipped?.length) warn(`Saved pid(s) not provably ours (left running): ${stopped.skipped.map((s) => s.pid).join(', ')}`)
  }
  return { ok: stopped.verified, killed: stopped.killed, stillBusy: stopped.stillBusy || [] }
}

async function statusObject() {
  const panel = await panelHealthy(1500)
  const rt = await rtUp()
  return {
    ok: true, managerVer: MANAGER_VER,
    panel: { running: panel, url: PANEL_URL, port: PANEL_PORT },
    realtime: { running: rt, port: RT_PORT },
    tunnel: cfg.tunnel || { kind: 'local' },
    config: { version: cfg.version || 'unknown', target: cfg.target || 'local', adminUser: cfg.adminUser || 'admin' },
    installed: existsSync(path.join(__dirname, 'panel', '.next', 'standalone', 'server.js')),
  }
}

// ─── GUI mode: serve ui/manager.html on 127.0.0.1 ─────────────────────
function findFreePort(start) {
  return new Promise((resolve) => {
    const tryPort = (p, left) => {
      const srv = net.createServer()
      srv.once('error', () => { if (left > 0) tryPort(p + 1, left - 1); else resolve(0) })
      srv.once('listening', () => srv.close(() => resolve(p)))
      srv.listen(p, '127.0.0.1')
    }
    tryPort(start, 20)
  })
}

async function guiMode() {
  const portFlag = argv.indexOf('--port')
  const port = portFlag > 0 ? Number(argv[portFlag + 1]) : await findFreePort(3198)
  if (!port) { err('Could not reserve a port for the manager GUI'); process.exit(1) }

  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url || '/', `http://localhost:${port}`)
    const json = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)) }

    if (req.method === 'GET' && (u.pathname === '/' || u.pathname === '/index.html')) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end(readFileSync(path.join(__dirname, 'ui', 'manager.html')))
      return
    }
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
    if (req.method === 'GET' && u.pathname === '/api/status') { return json(200, await statusObject()) }
    if (req.method === 'POST' && (u.pathname === '/api/start' || u.pathname === '/api/restart')) {
      if (u.pathname === '/api/restart') await stopServers()
      const r = await startServers({ open: true })
      return json(r.ok ? 200 : 500, r)
    }
    if (req.method === 'POST' && u.pathname === '/api/stop') { return json(200, await stopServers()) }
    if (req.method === 'POST' && u.pathname === '/api/open') { openBrowser(PANEL_URL); return json(200, { ok: true }) }
    if (req.method === 'POST' && u.pathname === '/api/quit') {
      json(200, { ok: true })
      setTimeout(() => { try { server.close() } catch { }; process.exit(0) }, 200)
      return
    }
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: false, error: 'not found' }))
  })

  server.listen(port, '127.0.0.1', () => {
    const u = `http://localhost:${port}`
    say('==============================================')
    say('  MedPad Server Manager (GUI)')
    say(`  Control page: ${u}`)
    say('  Keep this window open while using the manager.')
    say('==============================================')
    openBrowser(u)
  })
}

// ─── CLI dispatch ──────────────────────────────────────────────────────
if (cmd === 'start') {
  startServers({ open: !argv.includes('--no-open') })
    .then((r) => process.exit(r.ok ? 0 : 1))
    .catch((e) => { err(`start failed: ${e.message}`); process.exit(1) })
} else if (cmd === 'stop') {
  stopServers().then((r) => process.exit(r.ok ? 0 : 1)).catch((e) => { err(`stop failed: ${e.message}`); process.exit(1) })
} else if (cmd === 'restart') {
  ;(async () => { await stopServers(); await sleep(800); const r = await startServers({ open: true }); process.exit(r.ok ? 0 : 1) })()
    .catch((e) => { err(`restart failed: ${e.message}`); process.exit(1) })
} else if (cmd === 'status') {
  statusObject().then((s) => { console.log(JSON.stringify(s, null, 2)); process.exit(0) })
} else if (cmd === 'gui') {
  guiMode()
} else {
  say(`MedPad Server Manager v${MANAGER_VER}
Usage:
  node server-manager.mjs start [--no-open]   start panel+realtime (opens the page)
  node server-manager.mjs stop                stop everything (verified)
  node server-manager.mjs restart             stop + start
  node server-manager.mjs status              JSON status
  node server-manager.mjs gui [--port N]      control page in the browser`)
  process.exit(0)
}
