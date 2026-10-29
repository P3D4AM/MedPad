#!/usr/bin/env node
/**
 * MedPad Offline Installer v1.9.12 — main pipeline (win-x64 / linux-x64)
 *
 * 100% OFFLINE: Node.js, dependencies, Prisma engines and the production
 * build are all bundled. No npm install, no next build, no downloads.
 *
 * v1.9.12 changes (audit & hardening release):
 *  - PROCESS SAFETY: foreign programs on our ports are NEVER killed. Every
 *    port listener is classified first; anything that cannot be PROVEN to
 *    belong to this install dir is left alone and the installer stops with
 *    a clear error. Upgrade kills only ever hit proven-own processes
 *    (absolute script paths / cwd / recorded start times — services.mjs).
 *  - PASSWORD HARDENING: public targets (vps/cloudflare) REQUIRE a strong
 *    unique admin password; fresh local installs without one get a random
 *    generated password printed once (admin123 is gone). Hashes are bcrypt
 *    now (panel rebuilt from source); legacy SHA-256 hashes still log in
 *    and are transparently upgraded on the next successful login.
 *  - SAMPLE DATA IS OPT-IN (ali/sara/mahla + sample customers only when the
 *    user explicitly asks: --with-sample-data / wizard checkbox, default NO).
 *  - SENSITIVE FILE PERMISSIONS: .env, db and tunnel-creds are owner-only
 *    (chmod 600/700 on Linux, re-applied on every upgrade; icacls best
 *    effort on Windows). No tokens in generated .bat scripts anymore.
 *  - CLOUDFLARE SAFETY: DNS changes are planned/listed BEFORE any change,
 *    need explicit confirmation, are backed up and rolled back on failure.
 *  - RT-PORT PATCH now also fixes the gateway-mode rewrites embedded in
 *    standalone server.js AND .next/routes-manifest.json; tunnel installs
 *    switch to gateway socket mode so the dashboard works through the
 *    public hostname.
 *  - (phase 0) FRESH-CLONE SAFETY NET: when the package ships no database
 *    (db/custom.db is git-ignored), the empty template at db/template.db is
 *    used, so installing straight from a git clone works; a package with
 *    neither file stops with a clear "incomplete package" error.
 *  - test.2/test.3 FIXES (the two owner-reported crashes of test.1):
 *    EPERM on the database copy -> copyFileRobust() (10 retries with growing
 *    backoff, stream + cmd-copy fallbacks, stale sqlite sidecar cleanup,
 *    broken-db quarantine). Windows ACL hardening now runs LAST (after
 *    Step 7), grants via the ACCOUNT SID, verifies access with real file
 *    probes and auto-reverts with icacls /reset - it can never break the
 *    install again. The admin password travels via ENV (never argv), the
 *    DB finalize retries 3x and fails LOUD, and the browser wizard shows a
 *    global JS-error banner + captures [RANDOM-ADMIN-PASS].
 *
 * v1.9.11 changes (user request: bring back the interactive installer):
 *  - INTERACTIVE MENU on launch (no args): the CLI now asks WHERE to install
 *    (1: this computer / 2: Linux VPS / 3: Cloudflare) and HOW (1: browser
 *    installer GUI - opens automatically, 2: CLI wizard in this console).
 *  - BROWSER INSTALLER (wizard.mjs + ui/installer.html): a localhost-only
 *    GUI that asks the same questions, collects admin username/password,
 *    panel port (+ advanced realtime port) and runs the install while
 *    streaming live logs. Starts automatically from the menu.
 *  - CONFIGURABLE PORTS: --port (panel) and --rt-port (realtime). Custom
 *    panel ports are threaded through every generated start/stop script and
 *    medpad-config.json. A custom realtime port safely patches the ONE
 *    compiled client chunk that hardcodes :3003 (exact-pattern replace,
 *    verified) plus the server-side fallback literal; the standalone server
 *    also gets REALTIME_URL env. Default 3000/3003 = zero patching.
 *  - SERVER MANAGER (server-manager.mjs + ui/manager.html): start/stop/
 *    restart/status from BOTH the CLI (start-server.bat / stop-server.bat /
 *    start-server.sh / stop-server.sh) and a GUI (server-manager.bat /
 *    server-manager.sh). After every start the MAIN PAGE OPENS AUTOMATICALLY
 *    in the default browser (services.mjs openBrowser).
 *  - medpad-config.json: written at the end of every install (version,
 *    target, admin user, ports, tunnel info). services.mjs uses it to stop
 *    the right ports; the wizard and the manager use it for status/URLs.
 *  - VPS TARGET: on Linux installs as a headless server (systemd units
 *    medpad-panel / medpad-realtime / medpad-tunnel generated, auto-enabled
 *    when running as root). On Windows it prints a step-by-step deploy guide
 *    (scp/WinSCP + install.sh) and optionally installs locally as well.
 *  - Passwords/tokens passed by the wizard travel via ENV
 *    (MEDPAD_ADMIN_PASS / MEDPAD_CF_TOKEN), never via argv.
 *  - "Start the panel now?" question at the end of an interactive install
 *    (spawns server-manager.mjs start --open => browser opens the panel).
 *
 * v1.9.10 changes (kept — user-reported bug "package incomplete"):
 *  - APP BUNDLE VERSION CHECK: verifies app/APP-VERSION + structure BEFORE
 *    any file is touched and BEFORE services are stopped (v1.9.9 user had
 *    copied new installer files into an OLD extracted package).
 *  - tunnel-state.json corruption no longer crashes the installer.
 *  - fresh-install copy uses cpRobust; legacy migration is non-fatal.
 *
 * v1.9.9 (kept): self-hosted Vazirmatn font — zero external CDN assets.
 * v1.9.8 (kept): EPERM fix (real stopMedPadServices call + rmRobust/cpRobust),
 *  verified data backups before replacing panel, DEP0190 fix (no shell:true).
 * v1.9.7 (kept): pure-ASCII console output, install INTO the package folder,
 *  start.bat/stop.bat aliases, legacy ../medpad-install migration.
 *
 * 7 steps: menu -> node -> prepare -> env+database+admin -> verify ->
 *          address mode -> start scripts -> health
 * Targets: local | vps | cloudflare   (address modes: local | quick/domain)
 *
 * CLI: node install.mjs [--target local|vps|cloudflare] [--gui]
 *                      [--non-interactive] [--mode local] [--dir "C:\path"]
 *                      [--admin-user admin] [--admin-pass secret]
 *                      [--port 3000] [--rt-port 3003] [--with-sample-data]
 *                      [--cf-token ..] [--cf-domain ..] [--subdomain panel]
 *                      [--cf-confirm]  (confirm destructive Cloudflare DNS changes)
 *                      [--node "C:\path\to\node.exe"]   (when Node came from the bundle)
 */
import { spawn, spawnSync } from 'child_process'
import net from 'net'
import http from 'http'
import { randomBytes } from 'crypto'
import {
  chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync, rmSync, cpSync, readdirSync,
  renameSync, statSync, copyFile, openSync, closeSync,
} from 'fs'
import path from 'path'
import url from 'url'
import readline from 'readline'
import os from 'os'
import { stopMedPadServices, classifyPortOwners, portBusy, readConfig, openBrowser } from './services.mjs'
import { ensureMedPadFirewall } from './lib/firewall.mjs'


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

const __dirname = path.dirname(url.fileURLToPath(import.meta.url))
export const INSTALLER_VER = '1.9.13-dev.4'
const PANEL_PORT_DEFAULT = 3000
const RT_PORT_DEFAULT = 3003
const LOG = path.join(__dirname, 'install.log')

// ─── tiny CLI output helpers (ASCII only — Windows console safe) ──────
const step = (n, t) => console.log(`\n===== Step ${n} of 7: ${t} =====`)
const info = (m) => console.log(m)
const ok = (m) => console.log(`[OK] ${m}`)
const warn = (m) => console.log(`[WARN] ${m}`)
const err = (m) => console.log(`[X] ${m}`)
const log = (m) => { try { appendFileSync(LOG, `${new Date().toISOString()} ${m}\n`) } catch { /* ignore */ } }

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`)
  if (i >= 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--')) return process.argv[i + 1]
  if (i >= 0) return true
  return def
}
const NON_INTERACTIVE = !!process.argv.includes('--non-interactive')

/** keep the window open on every exit path (v1.9.4 anti-flash-close pattern) */
async function pause() {
  if (NON_INTERACTIVE) return
  await ask('Press Enter to close this window...')
  closeStdin()
}
async function die(msg, code = 1) {
  err(msg)
  log(`FAIL ${msg}`)
  await pause()
  process.exit(code)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** v1.9.11: ask() with env fallback — secrets (admin password, Cloudflare
 *  token) arrive via MEDPAD_ADMIN_PASS / MEDPAD_CF_TOKEN from the browser
 *  wizard so they never appear in a process command line. */
function argOrEnv(name, envName) {
  const a = arg(name)
  if (a !== undefined && a !== true) return a
  if (process.env[envName]) return process.env[envName]
  return undefined
}

/** v1.9.11: reserve a free TCP port (wizard handshake). Resolves 0 on error. */
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

/** v1.9.11: is a localhost HTTP endpoint answering? (best-effort probe) */
function httpUp(port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/health', timeout: timeoutMs }, (res) => {
      res.resume()
      resolve(true)
    })
    req.on('timeout', () => { req.destroy(); resolve(false) })
    req.on('error', () => resolve(false))
  })
}

/** v1.9.11: launch the browser installer (wizard.mjs) as a DETACHED process
 *  that survives this console closing. Reserves the port first, passes it via
 *  env, then verifies the wizard answers /health before printing the URL. */
async function launchWizard(target) {
  const port = await findFreePort(3199)
  if (!port) { warn('Could not reserve a port for the browser installer.'); return false }
  try {
    const child = spawn(process.execPath, [path.join(__dirname, 'wizard.mjs')], {
      cwd: __dirname,
      detached: true,
      stdio: 'ignore',
      env: { ...process.env, MEDPAD_WIZARD_PORT: String(port), MEDPAD_WIZARD_TARGET: target || '' },
    })
    child.unref()
  } catch (e) {
    warn(`Could not start the browser installer: ${e.message}`)
    return false
  }
  const urlStr = `http://localhost:${port}`
  info('[..] Starting the browser installer...')
  for (let i = 0; i < 20; i++) {
    await sleep(500)
    if (await httpUp(port)) {
      ok(`Browser installer is running: ${urlStr}`)
      openBrowser(urlStr)
      log(`wizard started on :${port} (target=${target || 'ask'})`)
      return true
    }
  }
  warn(`The browser installer did not answer on ${urlStr} - try running it manually:`)
  warn(`    node wizard.mjs`)
  return false
}

/** v1.9.8: Windows-safe recursive remove.
 *  EPERM/EBUSY on Windows is usually caused by (a) a still-running server
 *  holding files, (b) a console/Explorer window whose CWD is inside the
 *  folder, or (c) a transient antivirus/indexer lock. Strategy:
 *  rmSync with Node's built-in retries -> 3 outer attempts with backoff ->
 *  rename the folder out of the way (<name>.stale-<ts>) -> give up (false). */
async function rmRobust(target, label = 'folder') {
  if (!existsSync(target)) return true
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      rmSync(target, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 })
      if (!existsSync(target)) return true
    } catch (e) {
      log(`rm ${target} attempt ${attempt}: ${e.code || e.message}`)
    }
    if (attempt < 3) {
      info(`[..] "${label}" is locked by another program - retrying (${attempt}/3)...`)
      await sleep(1500)
    }
  }
  const stale = `${target}.stale-${Date.now()}`
  try {
    renameSync(target, stale)
    warn(`"${label}" could not be deleted - it was moved to "${path.basename(stale)}" instead (safe to delete manually later)`)
    log(`rename fallback: ${target} -> ${stale}`)
    return true
  } catch (e) {
    log(`rename fallback for ${target} failed: ${e.code || e.message}`)
  }
  return false
}

/** v1.9.8: copy with retry (transient Windows locks). */
async function cpRobust(src, dest, label = 'copy') {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      cpSync(src, dest, { recursive: true, force: true })
      return
    } catch (e) {
      log(`cp ${src} -> ${dest} attempt ${attempt}: ${e.code || e.message}`)
      if (attempt < 3) {
        info(`[..] ${label} is locked by another program - retrying (${attempt}/3)...`)
        await sleep(1500)
      } else throw e
    }
  }
}

// ── v1.9.12-test.2: EPERM-proof single-file copy ───────────────────────
// Root cause of the v1.9.12-test.1 Step-3 crash (owner report): antivirus /
// Windows Defender / the search indexer hold a short exclusive handle on a
// FRESHLY WRITTEN .db file, so an immediate fs.copyFile(template ->
// custom.db) died with EPERM. Such locks are transient (usually < 2s), so we
// retry with growing backoff, then fall back to a read/write stream copy,
// then to cmd copy. Every successful path is size-verified.
const copyFileP = (s, d) => new Promise((res, rej) => copyFile(s, d, (e) => (e ? rej(e) : res())))
async function copyFileRobust(src, dest, label = 'file copy') {
  // 0) a stale destination (from a crashed run) can itself be the lock:
  //    remove it with Node's built-in retries first.
  for (let i = 0; i < 5 && existsSync(dest); i++) {
    try { rmSync(dest, { force: true, maxRetries: 5, retryDelay: 250 }) } catch (e) { log(`stale dest rm: ${e.code || e.message}`) }
    if (existsSync(dest)) await sleep(400)
  }
  const delays = [300, 600, 1000, 1500, 2000, 2000, 3000, 3000, 4000, 5000]
  let lastErr = null
  for (let attempt = 0; attempt < delays.length; attempt++) {
    try {
      await copyFileP(src, dest)
      if (statSync(dest).size === statSync(src).size) return // verified copy
      throw new Error('size mismatch after copy')
    } catch (e) {
      lastErr = e
      log(`copyFile ${src} -> ${dest} attempt ${attempt + 1}: ${e.code || e.message}`)
      if (attempt < delays.length - 1) {
        info(`[..] ${label} is locked by another program (antivirus scan?) - retrying (${attempt + 1}/${delays.length})...`)
        await sleep(delays[attempt])
      }
    }
  }
  // fallback 1: stream through Node (different Windows code path)
  try {
    const buf = readFileSync(src)
    writeFileSync(dest, buf)
    if (statSync(dest).size === buf.length) return
    throw new Error('size mismatch after stream copy')
  } catch (e) { lastErr = e; log(`stream fallback failed: ${e.code || e.message}`) }
  // fallback 2 (win32): let cmd.exe try
  if (process.platform === 'win32') {
    const r = spawnSync('cmd.exe', ['/d', '/s', '/c', `copy /y "${src}" "${dest}"`], { encoding: 'utf8', timeout: 30000 })
    try { if ((r.status === 0) && existsSync(dest) && statSync(dest).size === statSync(src).size) return } catch { /* fallthrough */ }
    log(`cmd copy fallback: status=${r.status}`)
  }
  throw lastErr
}

// ── v1.9.12-test.2: SAFE Windows ACL hardening ─────────────────────────
// Restricts panel/.env and panel/db to the CURRENT user. v1.9.12-test.1
// did this with a name-based grant BEFORE the database was created and a
// mismatched/mangled ACL made the very next file operation die with EPERM.
// This rewrite: (a) runs LAST, after every file operation is finished,
// (b) grants via the ACCOUNT SID (*S-1-5-...) - immune to username,
//     localization and Microsoft-account quirks,
// (c) VERIFIES access with real file operations afterwards,
// (d) auto-reverts with "icacls /reset" if verification fails,
// (e) never, ever fails the install (warn-only).
function winUserSid() {
  try {
    const r = spawnSync('whoami', ['/user', '/fo', 'csv', '/nh'], { encoding: 'utf8', timeout: 10000 })
    const m = String(r.stdout || '').match(/(S-1-5-\d(?:-\d+)+)/)
    if (m) return m[1]
  } catch { /* fallthrough */ }
  return null
}

function icaclsRun(args) {
  try {
    const r = spawnSync('icacls', args, { encoding: 'utf8', timeout: 20000 })
    return { code: r.status, out: String(r.stdout || '') + String(r.stderr || '') }
  } catch (e) {
    return { code: -1, out: String(e.message) }
  }
}

/** real access verification - never trust an icacls exit code alone */
function aclVerify(target, isDir) {
  try {
    if (isDir) {
      const probe = path.join(target, `.acl-probe-${Date.now()}`)
      writeFileSync(probe, 'ok')
      readFileSync(probe, 'utf8')
      rmSync(probe, { force: true, maxRetries: 3, retryDelay: 200 })
      return !existsSync(probe)
    }
    const fd = openSync(target, 'r+') // needs read AND write rights
    closeSync(fd)
    return true
  } catch (e) {
    log(`acl verify failed for ${target}: ${e.code || e.message}`)
    return false
  }
}

async function restrictAcl(label, target, isDir) {
  const sid = winUserSid()
  if (!sid) {
    warn(`ACL: could not determine your Windows user SID - ${label} keeps its default permissions (non-fatal)`)
    return false
  }
  const grant = isDir ? `*${sid}:(OI)(CI)(F)` : `*${sid}:(F)`
  const r = icaclsRun([target, '/inheritance:r', '/grant:r', grant])
  if (r.code !== 0) {
    log(`icacls restrict ${label} -> ${r.out}`)
    warn(`ACL: could not restrict ${label} - it keeps its default permissions (non-fatal)`)
    return false
  }
  await sleep(400) // let the new ACL settle before verifying
  if (!aclVerify(target, isDir)) {
    warn(`ACL: restricting ${label} would break access - restoring inherited permissions`)
    const rr = icaclsRun([target, '/reset'])
    await sleep(400)
    if (!aclVerify(target, isDir)) {
      warn(`ACL: ${label} is still not accessible - right-click it > Properties > Security > Advanced > Reset permissions`)
      return false
    }
    log(`icacls /reset recovered ${label} (exit ${rr.code})`)
    return false
  }
  ok(`${label}: Windows ACL restricted to the current user`)
  return true
}

/** resolve the node executable — system node or the bundled runtime (Linux) */
function findNode() {
  const fromArg = arg('node')
  if (fromArg && existsSync(fromArg)) return fromArg
  // v1.9.8 FIX: process.execPath is the node binary that is ALREADY running
  // this script — always a valid absolute path, works with no shell (kills
  // the Node 24 DEP0190 warning caused by spawnSync('node', {shell:true})).
  if (process.execPath && existsSync(process.execPath)) return process.execPath
  const rt = path.join(__dirname, 'runtime')
  if (existsSync(rt)) {
    for (const d of readdirSync(rt)) {
      const exe = process.platform === 'win32'
        ? path.join(rt, d, 'node.exe')
        : path.join(rt, d, 'bin', 'node')
      if (existsSync(exe)) return exe
    }
  }
  return null
}

/** v1.9.10: read the app bundle version manifest (app/APP-VERSION). */
function readAppVersion(appDir) {
  try { return readFileSync(path.join(appDir, 'APP-VERSION'), 'utf8').trim() } catch { return null }
}

/** v1.9.10: compare dotted versions numerically ('1.9.10' > '1.9.9'). */
function cmpVer(a, b) {
  const pa = String(a).split('.').map((n) => parseInt(n, 10) || 0)
  const pb = String(b).split('.').map((n) => parseInt(n, 10) || 0)
  const len = Math.max(pa.length, pb.length)
  for (let i = 0; i < len; i++) {
    const d = (pa[i] || 0) - (pb[i] || 0)
    if (d !== 0) return d
  }
  return 0
}

/** v1.9.10: the clear error for the "new installer inside an old package
 *  folder" trap. Must fire BEFORE anything is modified. */
async function dieOldAppBundle(appDir, detail) {
  const appVer = readAppVersion(appDir)
  log(`FAIL app bundle check: ${detail} (appVer=${appVer === null ? 'none' : appVer})`)
  await die([
    `${detail}`,
    `  Installer version: v${INSTALLER_VER} - app bundle in this folder: ${appVer === null ? 'OLDER than v1.9.9 (no version file)' : `v${appVer}`}`,
    '  Most likely cause: the new installer files were copied into an OLD',
    '  extracted package (the "app" folder here is outdated).',
    '  HOW TO FIX:',
    '    1. Download the new complete ZIP and extract ALL of it to a folder',
    '    2. Run install.bat from THAT new folder',
    '       (or copy the ENTIRE "app" folder from the new package over this one)',
    '  NOTHING has been changed - your panel, database and files are untouched.',
  ].join('\n'))
}

/** v1.9.11 → v1.9.12: patch the prebuilt panel for a custom realtime port.
 *  The compiled CLIENT bundle contains the literal `location.hostname}:3003`
 *  exactly once and the SERVER chunks contain `http://127.0.0.1:3003` as an
 *  env-overridable fallback. v1.9.12 FIX: the GATEWAY-mode rewrites are
 *  ALSO hardcoded in TWO more places — standalone `server.js` (an embedded
 *  copy of the rewrites) and `.next/routes-manifest.json` — and both are
 *  patched now too; proxy mode previously kept pointing at 3003.
 *  All replaced with exact-string split/join (no regex surprises), and the
 *  result verified. The panel build itself is NOT rebuilt. */
function patchRtPort(panelDir, rtPort) {
  if (!Number.isInteger(rtPort) || rtPort === RT_PORT_DEFAULT) return { client: 0, server: 0, gateway: 0 }
  const targets = [
    path.join(panelDir, '.next', 'standalone', '.next', 'static', 'chunks'),
    path.join(panelDir, '.next', 'standalone', '.next', 'server', 'chunks'),
  ]
  let clientHits = 0
  let serverHits = 0
  for (const dir of targets) {
    if (!existsSync(dir)) continue
    for (const f of readdirSync(dir)) {
      if (!f.endsWith('.js')) continue
      const p = path.join(dir, f)
      let s
      try { s = readFileSync(p, 'utf8') } catch { continue }
      if (s.includes('location.hostname}:3003')) {
        s = s.split('location.hostname}:3003').join(`location.hostname}:${rtPort}`)
        writeFileSync(p, s)
        clientHits++
      } else if (s.includes('http://127.0.0.1:3003')) {
        s = s.split('http://127.0.0.1:3003').join(`http://127.0.0.1:${rtPort}`)
        writeFileSync(p, s)
        serverHits++
      }
    }
  }
  // v1.9.12: gateway-mode rewrites (server.js embeds the manifest copy)
  let gatewayHits = 0
  for (const p of [
    path.join(panelDir, '.next', 'standalone', 'server.js'),
    path.join(panelDir, '.next', 'standalone', '.next', 'routes-manifest.json'),
  ]) {
    if (!existsSync(p)) continue
    let s
    try { s = readFileSync(p, 'utf8') } catch { continue }
    if (s.includes('http://127.0.0.1:3003')) {
      s = s.split('http://127.0.0.1:3003').join(`http://127.0.0.1:${rtPort}`)
      writeFileSync(p, s)
      gatewayHits++
    }
  }
  log(`rt-port patch: client=${clientHits} server=${serverHits} gateway=${gatewayHits} -> ${rtPort}`)
  return { client: clientHits, server: serverHits, gateway: gatewayHits }
}

/** v1.9.12: verify no stale realtime literals remain after patching —
 *  client chunk, server chunks AND the two gateway rewrite carriers. */
function verifyRtPatch(panelDir, rtPort) {
  if (rtPort === RT_PORT_DEFAULT) return true
  const dirs = [
    path.join(panelDir, '.next', 'standalone', '.next', 'static', 'chunks'),
    path.join(panelDir, '.next', 'standalone', '.next', 'server', 'chunks'),
  ]
  for (const dir of dirs) {
    if (!existsSync(dir)) continue
    for (const f of readdirSync(dir)) {
      if (!f.endsWith('.js')) continue
      try {
        const s = readFileSync(path.join(dir, f), 'utf8')
        if (s.includes('location.hostname}:3003') || s.includes('http://127.0.0.1:3003')) return false
      } catch { /* ignore */ }
    }
  }
  for (const p of [
    path.join(panelDir, '.next', 'standalone', 'server.js'),
    path.join(panelDir, '.next', 'standalone', '.next', 'routes-manifest.json'),
  ]) {
    if (!existsSync(p)) continue
    try {
      const s = readFileSync(p, 'utf8')
      if (s.includes('http://127.0.0.1:3003')) return false
    } catch { /* ignore */ }
  }
  return true
}

// ══════════════════════════════════════════════════════════════════════
// v1.9.11 INTERACTIVE MENU — "where do you want to install MedPad?"
// ══════════════════════════════════════════════════════════════════════
// v1.9.11 FIX (found in T8): sequential rl.question() calls lose answers
// on PIPED stdin — when all input arrives in one chunk, readline emits the
// 'line' events for lines 2..n before the next question() has registered
// its listener, so they are dropped and the process dies with an unsettled
// await (Node 24 exit 13). The fix: ONE readline interface with a
// PERMANENT 'line' listener feeding a queue; ask() prints the prompt and
// pulls the next line from the queue (works for TTY and pipes alike).
const ANSWERS = []
let lineWaiter = null
let EOF = false
let RL = null
function initStdin() {
  if (RL || NON_INTERACTIVE) return
  RL = readline.createInterface({ input: process.stdin, output: process.stdout })
  RL.on('line', (l) => {
    ANSWERS.push(l)
    if (lineWaiter) { const w = lineWaiter; lineWaiter = null; w() }
  })
  RL.on('close', () => {
    EOF = true
    if (lineWaiter) { const w = lineWaiter; lineWaiter = null; w() }
  })
}
function closeStdin() {
  try { if (RL) RL.close() } catch { /* ignore */ }
  RL = null
}

async function ask(question, def = '') {
  if (NON_INTERACTIVE) return def
  initStdin()
  process.stdout.write(`${question}${def ? ` [${def}]` : ''}: `)
  if (ANSWERS.length === 0 && !EOF) {
    await new Promise((res) => { lineWaiter = res })
  }
  const a = EOF && ANSWERS.length === 0 ? '' : (ANSWERS.shift() ?? '')
  return a.trim() || def
}

async function askPassword(question) {
  if (NON_INTERACTIVE) return ''
  // Plain echo (no TTY raw-mode tricks — the Windows console and piped test
  // input both stay reliable). This is a local, one-time installer question.
  return ask(question, '')
}

/** the target+interface menu shown when install.bat / install.sh / install.ps1
 *  is double-clicked without arguments. Returns the chosen settings, or exits
 *  the process when the user picks the browser installer (it runs detached). */
async function interactiveMenu() {
  console.log('')
  console.log('  Q1 - WHERE do you want to install MedPad?')
  console.log('')
  console.log('    1) This computer - local install')
  console.log('       (Windows PC or this Linux machine - panel on your LAN)')
  console.log('    2) Linux VPS - a remote server')
  console.log('       (headless server, starts on boot with systemd)')
  console.log('    3) Cloudflare - local install + public HTTPS address')
  console.log('       (tunnel - reachable from anywhere, needs internet)')
  console.log('')
  const t = await ask('Choose 1/2/3', '1')
  const target = t === '2' ? 'vps' : t === '3' ? 'cloudflare' : 'local'

  console.log('')
  console.log('  Q2 - HOW do you want to run the installer?')
  console.log('')
  console.log('    1) Browser installer (GUI) - recommended')
  console.log('       (a setup page opens in your browser automatically)')
  console.log('    2) Wizard in this console (CLI)')
  console.log('')
  const i = await ask('Choose 1/2', '1')
  log(`menu: target=${target} interface=${i === '1' ? 'gui' : 'cli'}`)

  if (i === '1') {
    const up = await launchWizard(target)
    if (up) {
      info('')
      info('  The browser installer is now running in your browser.')
      info('  You can close this window - the installer page keeps working.')
      await pause()
      process.exit(0)
    }
    warn('Falling back to the console wizard...')
  }

  // ── CLI wizard: collect the settings ──
  const s = { target, adminUser: 'admin', adminPass: '', panelPort: PANEL_PORT_DEFAULT, rtPort: RT_PORT_DEFAULT, tunnelKind: 'quick', cfToken: '', cfDomain: '', subdomain: 'panel', alsoLocal: true, sampleData: false }
  console.log('')
  s.adminUser = await ask('Admin username', 'admin')
  if (s.adminUser.length < 2) s.adminUser = 'admin'
  const isPublic = target === 'cloudflare' || target === 'vps'
  // v1.9.12: public targets REQUIRE a strong password (asked up to 3 times)
  for (let i = 0; i < 3; i++) {
    s.adminPass = await askPassword(isPublic
      ? 'Admin password (REQUIRED for a public install - min 10 chars, letters + digits)'
      : 'Admin password (blank = a random password is generated on a fresh install)')
    if (!isPublic) break
    if (passwordProblem(s.adminPass, s.adminUser) === null) break
    warn(`Password rejected: ${passwordProblem(s.adminPass, s.adminUser)}`)
    s.adminPass = ''
  }
  const portIn = await ask('Panel port', String(PANEL_PORT_DEFAULT))
  s.panelPort = Number.parseInt(portIn, 10)
  if (!Number.isInteger(s.panelPort) || s.panelPort < 1 || s.panelPort > 65535) s.panelPort = PANEL_PORT_DEFAULT
  const rtIn = await ask(`Realtime (socket) port [${RT_PORT_DEFAULT} - leave default unless it is taken]`, String(RT_PORT_DEFAULT))
  s.rtPort = Number.parseInt(rtIn, 10)
  if (!Number.isInteger(s.rtPort) || s.rtPort < 1 || s.rtPort > 65535) s.rtPort = RT_PORT_DEFAULT

  // v1.9.12: sample accounts are OPT-IN (default: no)
  s.sampleData = (await ask('Also install SAMPLE data (employees ali/sara/mahla with password 1234 + 3 sample customers)? (y/n)', 'n')).toLowerCase().startsWith('y')

  if (target === 'cloudflare') {
    console.log('')
    console.log('  Cloudflare tunnel type:')
    console.log('    1) Quick tunnel - random https://*.trycloudflare.com (needs internet)')
    console.log('    2) My own domain - fixed https://panel.your-domain (needs internet + API token)')
    const c = await ask('Choose 1/2', '1')
    s.tunnelKind = c === '2' ? 'domain' : 'quick'
    if (s.tunnelKind === 'domain') {
      s.cfToken = await ask('Cloudflare API Token')
      s.cfDomain = await ask('Your domain (e.g. example.com)')
      s.subdomain = await ask('Subdomain', 'panel')
    }
  }
  if (target === 'vps' && onWindows()) {
    // the VPS guide is printed by main(); here we only ask the extra question
    s.alsoLocal = (await ask('Also install on THIS computer now? (y/n)', 'n')).toLowerCase().startsWith('y')
  }
  return s
}

/** v1.9.11: the Windows -> VPS deploy guide (shown when target=vps on Windows). */
function printVpsGuide() {
  console.log(`
  ─────────────────────────────────────────────────────────────
  DEPLOYING TO A LINUX VPS (you are on Windows right now)
  ─────────────────────────────────────────────────────────────
  The full package must run ON the server. Steps:

    1. Copy this whole folder (or the ZIP) to the server:
         scp -r . root@YOUR-SERVER-IP:/opt/medpad
       (or use WinSCP / any SFTP tool to upload the folder)

    2. Connect to the server:
         ssh root@YOUR-SERVER-IP

    3. Run the installer ON the server:
         cd /opt/medpad
         bash install.sh
       The same menu appears - choose "2) Linux VPS".
       Node.js installs from the bundled tarball (100% offline),
       systemd services are created and the panel starts on boot.

    4. Open the panel:
         http://YOUR-SERVER-IP:3000        (or the port you chose)
       For a public HTTPS address choose the Cloudflare option
       during the install (quick tunnel or your own domain).

  The same ZIP works on both Windows and Linux - nothing else
  to download on the server.
  ─────────────────────────────────────────────────────────────`)
}

/** v1.9.11: "are we on Windows?" — real platform OR the test simulation */
const onWindows = () => process.platform === 'win32' || process.env.MEDPAD_SIM_WIN === '1'

/** v1.9.12: admin password policy. Returns null when the password is
 *  acceptable, otherwise a short reason (English — console-safe ASCII).
 *  Public targets (vps/cloudflare) enforce this; local installs warn. */
export function passwordProblem(pw, username) {
  if (!pw || typeof pw !== 'string') return 'missing'
  if (pw.length < 10) return 'too short (minimum 10 characters)'
  if (/^(admin123|password|12345678|1234567890|medpad123|qwerty123|letmein123|passw0rd1)$/i.test(pw)) return 'a well-known weak password'
  if (username && pw.toLowerCase() === String(username).toLowerCase()) return 'the same as the username'
  if (!/[a-z]/i.test(pw) || !/\d/.test(pw)) return 'must contain letters AND digits'
  return null
}

/** v1.9.12: lock a sensitive file/dir down to the owner. Linux: chmod
 *  600/700 (fatal when it fails — fail secure). Windows: DEFERRED — the ACL
 *  hardening runs at the very END of the install via restrictAcl() (after
 *  Step 7). Doing it here with a username-based grant, BEFORE the database
 *  copy/seed, was the root cause of the EPERM crash of v1.9.12-test.1. */
async function lockDown(target, kind, label) {
  if (process.platform === 'win32' || process.env.MEDPAD_SIM_WIN === '1') {
    return // Windows: see restrictAcl() after Step 7 (v1.9.12-test.2 fix)
  }
  try {
    chmodSync(target, kind === 'dir' ? 0o700 : 0o600)
    ok(`${label}: owner-only permissions (${kind === 'dir' ? '700' : '600'})`)
  } catch (e) {
    await die(`Could not restrict permissions on the sensitive ${label} (${e.code || e.message}). The file would stay readable by every user on this machine - install stopped. Fix the filesystem issue and run install again.`)
  }
}

async function main() {
  const t0 = Date.now()
  console.log('==========================================')
  console.log(`   MedPad Offline Installer v${INSTALLER_VER}`)
  console.log('   Full offline bundle - no internet needed')
  console.log('==========================================')
  log(`installer v${INSTALLER_VER} start, cwd=${__dirname}`)

  // v1.9.7 FIX: install INTO the package folder itself, so start.bat appears
  // right next to install.bat. --dir still overrides for custom paths.
  const installDir = path.resolve(arg('dir', __dirname))
  info(`Install folder: ${installDir}`)
  log(`install dir=${installDir}`)
  mkdirSync(installDir, { recursive: true })

  // OneDrive warning (EPERM root cause)
  if (/onedrive/i.test(installDir)) {
    warn('The install folder is inside OneDrive - OneDrive locks Prisma files!')
    warn('Strongly recommended: move the install outside OneDrive (e.g. C:\\MedPad)')
  }

  // v1.9.10 FIX (user bug): verify the app/ bundle BEFORE anything else -
  // BEFORE services are stopped and BEFORE a single file is modified.
  const appDir = path.join(__dirname, 'app')
  const appVer = readAppVersion(appDir)
  const srcFontCss = path.join(appDir, 'panel', '.next', 'standalone', 'public', 'fonts', 'vazirmatn.css')
  const srcFontsOk = existsSync(srcFontCss)
  if (!existsSync(path.join(appDir, 'panel', '.next', 'BUILD_ID'))) {
    await dieOldAppBundle(appDir, 'The app bundle ("app" folder) is missing or incomplete - no panel build found.')
  }
  if (!srcFontsOk) {
    await dieOldAppBundle(appDir, 'The app bundle ("app" folder) in this package is OUTDATED (pre-v1.9.9, no self-hosted font).')
  }
  if (appVer === null) {
    warn('App bundle has no APP-VERSION file (older packaging) - structure checks passed, continuing.')
    log('app bundle: no APP-VERSION file (structure OK)')
  } else if (cmpVer(appVer, INSTALLER_VER) !== 0) {
    warn(`App bundle v${appVer} does not match installer v${INSTALLER_VER} - this works, but for a complete update extract the FULL new ZIP.`)
    log(`app bundle version mismatch: app=${appVer} installer=${INSTALLER_VER} (continuing)`)
  } else {
    ok(`App bundle v${appVer} matches installer (verified before any file was touched)`)
    log(`app bundle v${appVer} verified`)
  }

  // ── v1.9.11: resolve target + settings (menu / args / env) ──
  const prevConfig = readConfig(installDir) // OLD ports must be stopped too
  let target = typeof arg('target') === 'string' ? arg('target') : undefined
  let menu = null
  if (target && !['local', 'vps', 'cloudflare'].includes(target)) {
    await die(`Unknown --target "${target}" (use local | vps | cloudflare)`)
  }
  if (!NON_INTERACTIVE && !target && !arg('mode') && !arg('gui')) {
    menu = await interactiveMenu()
    target = menu.target
  } else if (arg('gui')) {
    // --gui jumps straight to the browser installer
    const up = await launchWizard(typeof arg('target') === 'string' ? arg('target') : '')
    if (up) { await pause(); process.exit(0) }
    warn('Falling back to the console wizard...')
    menu = await interactiveMenu()
    target = menu.target
  }

  const adminUser = (menu?.adminUser || arg('admin-user') || 'admin').trim() || 'admin'
  let adminPass = menu?.adminPass || argOrEnv('admin-pass', 'MEDPAD_ADMIN_PASS') || undefined
  const panelPortRaw = Number.parseInt(menu?.panelPort || arg('port') || PANEL_PORT_DEFAULT, 10)
  const panelPort = Number.isInteger(panelPortRaw) && panelPortRaw > 0 && panelPortRaw < 65536 ? panelPortRaw : PANEL_PORT_DEFAULT
  const rtPortRaw = Number.parseInt(menu?.rtPort || arg('rt-port') || RT_PORT_DEFAULT, 10)
  const rtPort = Number.isInteger(rtPortRaw) && rtPortRaw > 0 && rtPortRaw < 65536 ? rtPortRaw : RT_PORT_DEFAULT
  let tunnelKind = menu?.tunnelKind || (typeof arg('tunnel-kind') === 'string' ? arg('tunnel-kind') : undefined)
  const sampleData = !!(menu?.sampleData || arg('with-sample-data'))

  // v1.9.11: VPS chosen on Windows -> guide first; local install only if asked
  if (target === 'vps' && onWindows()) {
    printVpsGuide()
    const alsoLocal = menu ? menu.alsoLocal : (NON_INTERACTIVE ? false : (await ask('Also install on THIS computer now? (y/n)', 'n')).toLowerCase().startsWith('y'))
    if (!alsoLocal) {
      ok('Nothing was changed on this computer. Follow the guide above to deploy on your VPS.')
      log('vps guide shown, no local install requested')
      await pause()
      process.exit(0)
    }
    target = 'local' // local install as well; tunnel/public mode is a Step 5 choice
  }
  if (target === 'cloudflare') {
    if (!tunnelKind) tunnelKind = NON_INTERACTIVE ? 'quick' : 'quick'
  }
  info(`Install target: ${target} (panel port ${panelPort}, realtime port ${rtPort})`)
  log(`target=${target} panelPort=${panelPort} rtPort=${rtPort} admin=${adminUser} sampleData=${sampleData}`)

  // ══ v1.9.12 PASSWORD POLICY (before anything is stopped or touched) ══
  const isPublicTarget = target === 'cloudflare' || target === 'vps'
  const hasExistingDb = existsSync(path.join(installDir, 'panel', 'db', 'custom.db'))
  let generatedPassword = false
  if (adminPass) {
    const prob = passwordProblem(adminPass, adminUser)
    if (prob) {
      if (isPublicTarget) {
        log(`FAIL weak password on public target: ${prob}`)
        await die(`Admin password rejected (${prob}). A strong, unique password is REQUIRED for a public install (vps/cloudflare). Nothing has been changed.`)
      }
      warn(`Admin password is weak (${prob}) - change it after the first login.`)
    }
  } else if (!hasExistingDb) {
    if (isPublicTarget) {
      log('FAIL no password on public target + fresh install')
      await die('A strong admin password is REQUIRED for a public install (vps/cloudflare). Pass --admin-pass (or the wizard password field / MEDPAD_ADMIN_PASS). Nothing has been changed.')
    }
    // v1.9.12: fresh LOCAL install without a password -> random one, printed
    // exactly once on the console (never written to install.log or any file).
    adminPass = 'Mp-' + randomBytes(9).toString('base64url')
    generatedPassword = true
  }

  // ══ v1.9.12 PROCESS SAFETY: classify port owners BEFORE stopping anything.
  // A foreign program on a needed port is NEVER killed — the install stops
  // here with a clear error, before a single file has been changed.
  const stopPorts = [...new Set([panelPort, rtPort, ...(prevConfig.panelPort ? [Number(prevConfig.panelPort)] : []), ...(prevConfig.rtPort ? [Number(prevConfig.rtPort)] : [])])].filter((p) => Number.isInteger(p) && p > 0 && p < 65536)
  {
    let cls
    try { cls = await classifyPortOwners(installDir, stopPorts) } catch (e) { cls = { foreign: [] }; warn(`Port ownership check failed (non-fatal): ${e.message}`) }
    if (cls.foreign && cls.foreign.length > 0) {
      log(`FAIL foreign port holders: ${cls.foreign.map((f) => `${f.port}:${f.name}(${f.pid})`).join(', ')}`)
      await die([
        'Another program (NOT MedPad) is already using the port(s) this install needs:',
        ...cls.foreign.map((f) => `    port ${f.port}:   ${f.name}   (pid ${f.pid})${f.cmd ? `  -  ${f.cmd}` : ''}`),
        '',
        '  MedPad did NOT stop that program and NOTHING has been changed.',
        '  HOW TO FIX - pick one:',
        '    1. Close that program, then run the installer again.',
        '    2. Install MedPad on different ports:   --port <number>  and/or  --rt-port <number>',
        '       (the wizard asks for both ports on the settings page).',
      ].join('\n'))
    }
  }

  // v1.9.8 FIX (user EPERM bug): STOP any running MedPad services BEFORE
  // touching any files. v1.9.11: stop the UNION of old + new ports so a
  // port-changing upgrade does not leave the old server running.
  // v1.9.12: stopMedPadServices only ever kills processes PROVEN to belong
  // to this install dir (see services.mjs) — foreign programs were already
  // rejected above, before anything was touched.
  info('Stopping previous MedPad services (if any are running)...')
  try {
    const stopped = await stopMedPadServices(installDir, { ports: stopPorts })
    if (stopped.killed > 0) ok(`Stopped ${stopped.killed} leftover MedPad process(es)`)
    if (stopped.skipped && stopped.skipped.length > 0) {
      warn(`Saved pid(s) could NOT be proven to belong to this install - they were LEFT RUNNING (not killed):`)
      for (const s of stopped.skipped) warn(`    pid ${s.pid}${s.name ? ` (${s.name})` : ''} - ${s.note}`)
      warn('If one of them really is an old MedPad server, close it manually and run install again.')
      log(`unprovable pids skipped: ${stopped.skipped.map((s) => s.pid).join(',')}`)
    }
    if (!stopped.verified) {
      warn('Some MedPad-related processes could not be stopped automatically.')
      if (stopped.stillBusy && stopped.stillBusy.length > 0) {
        warn(`Port(s) still busy afterwards: ${stopped.stillBusy.map((b) => `${b.port} (${b.name}, pid ${b.pid})`).join(', ')} - a process is holding them.`)
      }
      if (stopped.foreignBusy && stopped.foreignBusy.length > 0) {
        warn(`Port(s) held by programs that are NOT this MedPad install (they were NOT killed): ${stopped.foreignBusy.map((b) => `${b.port} (${b.name}, pid ${b.pid})`).join(', ')}`)
      }
      warn('If the next steps report EPERM/locked errors: close ALL MedPad and node.js windows, then run install again.')
    }
    log(`service stop: killed=${stopped.killed} verified=${stopped.verified}${stopped.stillBusy && stopped.stillBusy.length ? ` stillBusy=${JSON.stringify(stopped.stillBusy)}` : ''}`)
  } catch (e) {
    warn(`Service stop check failed (non-fatal): ${e.message}`)
  }
  await sleep(800) // give Windows a moment to release file handles

  // ═══ Step 1 — Node.js ═══════════════════════════════════
  step(1, 'Node.js')
  const nodeExe = findNode()
  if (!nodeExe) {
    await die('Node.js not found - the offline bundle is broken (check runtime/offline folder)')
  }
  const v = spawnSync(nodeExe, ['--version'], { encoding: 'utf8' })
  const nodeVer = (v.stdout || '').trim()
  ok(`Node.js ${nodeVer} ready (${nodeExe === 'node' ? 'system' : 'bundled offline'})`)
  log(`node=${nodeExe} ver=${nodeVer}`)

  // ═══ Step 2 — prepare files (pre-built panel + realtime) ══
  step(2, 'Prepare files')
  const srcPanel = path.join(appDir, 'panel')
  const srcRt = path.join(appDir, 'realtime')
  if (!existsSync(path.join(srcPanel, '.next', 'BUILD_ID'))) {
    await die('Prebuilt panel build not found in the bundle - package incomplete (app/panel/.next/BUILD_ID)')
  }
  if (!existsSync(path.join(srcRt, 'index.js'))) {
    await die('Realtime service not found in the bundle (app/realtime/index.js)')
  }
  const panelDir = path.join(installDir, 'panel')
  const rtDir = path.join(installDir, 'realtime')

  // v1.9.8 DATA RESCUE: if a previous upgrade crashed midway, the user's
  // database lives ONLY in .keep-tmp. Restore it into panel/ before anything.
  const keepTmp = path.join(installDir, '.keep-tmp')
  if (existsSync(path.join(keepTmp, 'db', 'custom.db'))) {
    const liveDb = path.join(panelDir, 'db', 'custom.db')
    const tmpDb = path.join(keepTmp, 'db', 'custom.db')
    let needsRescue = !existsSync(liveDb)
    if (!needsRescue) {
      try { needsRescue = statSync(liveDb).size !== statSync(tmpDb).size } catch { needsRescue = true }
    }
    if (needsRescue) {
      warn('An interrupted previous upgrade was detected - restoring your saved data first')
      mkdirSync(panelDir, { recursive: true })
      for (const d of ['db', 'uploads', '.env']) {
        const s = path.join(keepTmp, d)
        if (existsSync(s)) await cpRobust(s, path.join(panelDir, d), `restore ${d}`)
      }
      // G-4 (phase 1 / task 2): the RUNTIME voice uploads live inside the
      // standalone tree (Next's server.js chdirs there) — restore them too.
      const su = path.join(keepTmp, 'standalone-uploads')
      if (existsSync(su)) {
        mkdirSync(path.join(panelDir, '.next', 'standalone'), { recursive: true })
        await cpRobust(su, path.join(panelDir, '.next', 'standalone', 'uploads'), 'restore standalone-uploads')
      }
      ok('Your data was restored from the interrupted-upgrade backup')
      log('rescue: restored db/uploads/.env (+standalone uploads) from .keep-tmp')
    }
  }

  let keepDb = existsSync(path.join(panelDir, 'db', 'custom.db'))
  let migrated = false
  let tunnelUnit = false // v1.9.11: hoisted — used in the final summary too
  if (existsSync(panelDir)) {
    info('Existing install detected - updating files, keeping database and uploads')
    log('upgrade mode — preserving db/uploads/.env')
    const tmp = keepTmp
    if (!await rmRobust(tmp, '.keep-tmp (old leftover)')) {
      await die('Could not clear the temporary backup folder - close any program using it and run install again')
    }
    // v1.9.8 DATA SAFETY: back up AND VERIFY before touching the old panel.
    // G-4 (phase 1 / task 2): also back up the REAL runtime uploads at
    // panel/.next/standalone/uploads — voice files used to be destroyed on
    // every update because only the (unused) panel/uploads was preserved.
    let backedUp = true
    for (const d of ['db', 'uploads', '.env']) {
      const s = path.join(panelDir, d)
      if (existsSync(s)) {
        try {
          await cpRobust(s, path.join(tmp, d), `backup ${d}`)
        } catch (e) {
          backedUp = false
          err(`Could not back up "${d}": ${e.code || e.message}`)
        }
      }
    }
    const suSrc = path.join(panelDir, '.next', 'standalone', 'uploads')
    if (existsSync(suSrc)) {
      try {
        await cpRobust(suSrc, path.join(tmp, 'standalone-uploads'), 'backup standalone-uploads')
      } catch (e) {
        backedUp = false
        err(`Could not back up voice uploads: ${e.code || e.message}`)
      }
    }
    const dbSrc = path.join(panelDir, 'db', 'custom.db')
    if (backedUp && existsSync(dbSrc)) {
      const dbBak = path.join(tmp, 'db', 'custom.db')
      if (!existsSync(dbBak) || statSync(dbBak).size !== statSync(dbSrc).size) backedUp = false
    }
    if (!backedUp) {
      await die('Backing up your data failed - nothing was changed. Close all MedPad/node.js windows and run install again (your files are untouched).')
    }
    if (!await rmRobust(panelDir, 'panel')) {
      await die([
        'Cannot replace the "panel" folder - it is locked by another program.',
        '  1) Close ALL MedPad / node.js windows (or run stop-server.bat, or restart the PC)',
        '  2) Close any terminal or Explorer window that is open INSIDE the panel folder',
        '  3) Run install.bat again',
        '  Your data is safe - a verified backup is in the .keep-tmp folder.',
      ].join('\n'))
    }
    await cpRobust(srcPanel, panelDir, 'new panel files')
    for (const d of ['db', 'uploads', '.env']) {
      const s = path.join(tmp, d)
      if (existsSync(s)) await cpRobust(s, path.join(panelDir, d), `restore ${d}`)
    }
    // G-4: restore the runtime voice uploads into the fresh standalone tree
    const suBak = path.join(tmp, 'standalone-uploads')
    if (existsSync(suBak)) {
      mkdirSync(path.join(panelDir, '.next', 'standalone'), { recursive: true })
      await cpRobust(suBak, path.join(panelDir, '.next', 'standalone', 'uploads'), 'restore standalone-uploads')
    }
    if (!await rmRobust(tmp, '.keep-tmp')) {
      warn('The .keep-tmp backup folder could not be removed - you can delete it manually later')
    }
  } else {
    // v1.9.10 FIX: fresh path uses the retry wrapper too.
    await cpRobust(srcPanel, panelDir, 'new panel files')
    if (installDir === __dirname) {
      const legacyDb = path.join(__dirname, '..', 'medpad-install', 'panel')
      if (existsSync(path.join(legacyDb, 'db', 'custom.db'))) {
        try {
          for (const d of ['db', 'uploads', '.env']) {
            const s = path.join(legacyDb, d)
            if (existsSync(s)) await cpRobust(s, path.join(panelDir, d), `legacy ${d}`)
          }
          migrated = true
          keepDb = true
          ok('Existing data migrated from the old medpad-install folder')
          log('legacy db/uploads/.env migrated from ../medpad-install')
        } catch (e) {
          warn(`Legacy data migration failed (non-fatal): ${e.code || e.message}`)
          log(`legacy migration failed: ${e.code || e.message}`)
        }
      }
    }
  }
  if (!await rmRobust(rtDir, 'realtime')) {
    await die('Cannot replace the "realtime" folder - it is locked by another program. Close all MedPad/node.js windows and run install again.')
  }
  await cpRobust(srcRt, rtDir, 'realtime service')
  ok(`Panel + Realtime service staged in ${installDir}${keepDb ? ' (previous database kept)' : migrated ? ' (data migrated from old install)' : ''}`)

  // ═══ Step 3 — env + database + admin (offline seed) ══════
  step(3, 'Database and admin account')
  const envPath = path.join(panelDir, '.env')
  let env = existsSync(envPath) ? readFileSync(envPath, 'utf8') : ''
  const dbFile = path.join(panelDir, 'db', 'custom.db').replace(/\\/g, '/')
  if (!env.includes('DATABASE_URL=')) env += `\nDATABASE_URL=file:${dbFile}\n`
  // v1.9.12 (audit phase 3): SOCKET_TOKEN is generated with the CSPRNG
  // (randomBytes — the old Math.random() pair was predictable) and an
  // existing EMPTY/BROKEN value is replaced instead of being kept.
  if (!/^SOCKET_TOKEN=\S/m.test(env)) {
    env = env.replace(/^SOCKET_TOKEN=.*$/m, '').replace(/\n{3,}/g, '\n\n')
    env += `\nSOCKET_TOKEN=medpad-${randomBytes(24).toString('hex')}\n`
  }
  // v1.9.12: tunnel installs use GATEWAY socket mode — the browser connects
  // same-origin /socket.io and Next.js proxies it to the realtime service, so
  // the dashboard ALSO works through the public Cloudflare hostname (direct
  // mode would try hostname:3003 which the tunnel does not expose). Local
  // installs keep direct mode. Re-written on every install/upgrade so a
  // target change is always reflected.
  const socketMode = target === 'cloudflare' ? 'gateway' : 'direct'
  env = env.replace(/^MEDPAD_SOCKET_MODE=.*$/m, '').replace(/\n{3,}/g, '\n\n')
  env += `\nMEDPAD_SOCKET_MODE=${socketMode}\n`
  env = env.replace(/^REALTIME_URL=.*$/m, '').replace(/\n{3,}/g, '\n\n')
  env += `\nREALTIME_URL=http://127.0.0.1:${rtPort}\n`
  writeFileSync(envPath, env)
  await lockDown(envPath, 'file', 'panel/.env') // v1.9.12: owner-only, also fixes upgraded installs
  // v1.9.12 (audit phase 3): NO dev-token fallback. The token was just
  // written with randomBytes above; if it cannot be read back the install
  // is in an unknown state and must stop (fail secure) — the systemd units
  // below embed this value and must never carry a public constant.
  const socketToken = (env.match(/^SOCKET_TOKEN=(\S+)/m) || [])[1]
  if (!socketToken) {
    log('FAIL: SOCKET_TOKEN missing from panel/.env after writing it')
    await die('Internal error: the generated .env has no usable SOCKET_TOKEN - the install was stopped before anything else was changed. Re-run the installer.')
  }
  mkdirSync(path.join(panelDir, 'db'), { recursive: true })
  mkdirSync(path.join(panelDir, 'uploads', 'voice'), { recursive: true })
  await lockDown(path.join(panelDir, 'db'), 'dir', 'panel/db') // v1.9.12
  try { const dbp = path.join(panelDir, 'db', 'custom.db'); if (existsSync(dbp)) await lockDown(dbp, 'file', 'panel/db/custom.db') } catch { /* fresh install - no db yet */ }
  // v1.9.12 (phase 0): fresh-clone safety net. The empty SQLite template
  // ships as app/panel/db/template.db (db/custom.db is git-ignored so real
  // user data can never enter git). When the staged install has NO database
  // yet — e.g. installing straight from a fresh `git clone` — start from the
  // empty template instead of letting the seed fail with a confusing
  // "table does not exist" Prisma error later.
  //
  // v1.9.12-test.2 FIX (EPERM crash of test.1, owner report): the copy is
  // now wrapped in copyFileRobust() — antivirus / Windows Defender / the
  // search indexer hold a short exclusive handle on freshly written .db
  // files, so the previous plain cpSync died with EPERM at Step 3. A 0-byte
  // / tiny custom.db (leftover of a crashed run) is quarantined aside
  // (non-destructive), and stale sqlite sidecars are removed first so a
  // crashed run can never poison the fresh database.
  if (existsSync(path.join(panelDir, 'db', 'custom.db'))) {
    let dbSize = 0
    try { dbSize = statSync(path.join(panelDir, 'db', 'custom.db')).size } catch { dbSize = 0 }
    if (dbSize < 8192) {
      const corruptBak = path.join(panelDir, 'db', `custom.db.broken-${Date.now()}`)
      try {
        renameSync(path.join(panelDir, 'db', 'custom.db'), corruptBak)
        warn('An empty/broken database file was found (leftover from an interrupted install) - it was renamed to ' + path.basename(corruptBak))
        log(`renamed broken db to ${path.basename(corruptBak)}`)
      } catch (e) {
        await die(`The old database file is broken AND locked (${e.code || e.message}). Close all MedPad/node.js windows and run install again.`)
      }
    }
  }
  if (!existsSync(path.join(panelDir, 'db', 'custom.db'))) {
    const tplSrc = [path.join(panelDir, 'db', 'template.db'), path.join(srcPanel, 'db', 'template.db')].find(existsSync)
    if (!tplSrc) {
      await die('The offline package is incomplete: no database and no empty template found (expected app/panel/db/template.db). Nothing has been changed.')
    }
    // stale sqlite sidecar files would poison the fresh database
    for (const junk of ['-journal', '-wal', '-shm'].map((s) => path.join(panelDir, 'db', `custom.db${s}`))) {
      try { rmSync(junk, { force: true, maxRetries: 5, retryDelay: 200 }) } catch (e) { log(`junk rm ${path.basename(junk)}: ${e.code || e.message}`) }
    }
    try {
      await copyFileRobust(tplSrc, path.join(panelDir, 'db', 'custom.db'), 'database template')
    } catch (e) {
      await die([
        `Creating the database failed: ${e.code || e.message}`,
        '  This is almost always a temporary antivirus lock:',
        '    1) Add an exclusion for this folder in your antivirus (Windows Security > Virus & threat protection > Manage settings > Exclusions)',
        '    2) Or just run install.bat again - the copy retries 10 times and usually succeeds on the 2nd run.',
      ].join('\n'))
    }
    await lockDown(path.join(panelDir, 'db', 'custom.db'), 'file', 'panel/db/custom.db')
    ok('Empty database template installed (fresh start)')
  }
  info('Config file (.env) is ready')

  // seed — uses the VENDORED prisma client + bcryptjs inside .next/standalone
  // (offline). v1.9.12: bcrypt hashes (bcrypt$...); sample employees/customers
  // only when the user explicitly opted in.
  // v1.9.12-test.2 hardening: the admin password travels via ENV (never on
  // the node command line, where it would be visible in process listings),
  // the finalize step retries 3 times with backoff, and a failure is LOUD —
  // a silent seed failure on a fresh install would leave the panel with NO
  // usable admin login and no explanation.
  const standaloneDir = path.join(panelDir, '.next', 'standalone')
  let seed = null
  for (let attempt = 1; attempt <= 3; attempt++) {
    seed = spawnSync(nodeExe, ['--input-type=module', '-e', `
      import bcrypt from 'bcryptjs';
      import { PrismaClient } from '@prisma/client';
      const db = new PrismaClient();
      const hashPw = (s) => 'bcrypt$' + bcrypt.hashSync(s, 10);
      const cfg = { adminUser: process.env.MEDPAD_ADMIN_USER, panelVersion: process.env.MEDPAD_PANEL_VERSION, apkVersion: '1.9.1' };
      if (process.env.MEDPAD_ADMIN_PASS) cfg.adminPassHash = hashPw(process.env.MEDPAD_ADMIN_PASS);
      for (const [key, value] of Object.entries(cfg)) await db.config.upsert({ where: { key }, update: { value }, create: { key, value } });
      const count = await db.employee.count();
      if (count === 0 && process.env.MEDPAD_SAMPLE_DATA === '1') {
        const names = [['ali','علی محمدی'],['sara','سارا احمدی'],['mahla','مهلا کریمی']];
        const emp = [];
        for (const [username, name] of names) emp.push(await db.employee.create({ data: { username, name, password: hashPw('1234') } }));
        const cus = [];
        const cs = [['دکتر رضایی — کلینیک سلامت',35.7448,51.3752],['دکتر محمدی — داروخانه نور',35.7010,51.3910],['دکتر کریمی — مطب شمال',35.7166,51.4160]];
        for (const [name, lat, lng] of cs) cus.push(await db.customer.create({ data: { name, lat, lng, address: 'تهران' } }));
        for (let i = 0; i < emp.length; i++) { await db.assignment.create({ data: { employeeId: emp[i].id, customerId: cus[i].id } }); await db.assignment.create({ data: { employeeId: emp[i].id, customerId: cus[(i+1)%3].id } }); }
      }
      await db.$disconnect();
      console.log('seed-ok');
    `], { cwd: standaloneDir, stdio: 'pipe', encoding: 'utf8', env: {
      ...process.env,
      DATABASE_URL: `file:${dbFile}`,
      MEDPAD_ADMIN_USER: String(adminUser),
      MEDPAD_PANEL_VERSION: String(INSTALLER_VER),
      ...(adminPass ? { MEDPAD_ADMIN_PASS: adminPass } : {}),
      ...(sampleData ? { MEDPAD_SAMPLE_DATA: '1' } : {}),
    } })
    if (seed.stdout?.includes('seed-ok')) break
    log(`seed attempt ${attempt}/3 failed: ${(seed.stderr || seed.stdout || 'no output').slice(-200)}`)
    if (attempt < 3) await sleep(1200 * attempt)
  }
  if (seed.stdout?.includes('seed-ok')) ok(`Admin account ready (user: ${adminUser}${sampleData ? ' + sample data' : ''})`)
  else {
    warn('')
    warn('  =====================================================')
    warn('  [!] THE ADMIN ACCOUNT COULD NOT BE WRITTEN TO THE DB')
    warn('  =====================================================')
    warn('  The panel may not accept any login until this succeeds.')
    warn('  FIX: close all MedPad windows and simply run install.bat')
    warn('  again (your data is preserved - only the admin account')
    warn('  is re-applied on the next run).')
    warn('  Technical detail: ' + (seed.stderr || seed.stdout || 'no output').slice(-300))
    log('seed failed after 3 attempts')
  }
  // v1.9.12: a freshly generated random password is shown EXACTLY ONCE here
  // (console only — never logged, never written to any file).
  if (generatedPassword && seed.stdout?.includes('seed-ok')) {
    info('')
    info('  ==================================================')
    info('  SAVE THIS NOW - your generated admin password:')
    info(`      ${adminPass}`)
    info('  (shown only this once - it is NOT stored anywhere')
    info('   in plain text; reset it with --admin-pass if lost)')
    info('  ==================================================')
    // v1.9.12-test.2: machine-readable line for the browser installer
    // (installer.html parses it and shows the password in the final summary).
    console.log(`[RANDOM-ADMIN-PASS] ${adminPass}`)
  }

  // ═══ Step 4 — verify pre-built app + realtime port patch ══
  step(4, 'Verify prebuilt build (offline)')
  const standaloneDir2 = path.join(panelDir, '.next', 'standalone')
  const standalone = path.join(standaloneDir2, 'server.js')
  if (!existsSync(standalone)) await die('standalone server.js not found - package incomplete')
  const engDir = path.join(standaloneDir2, 'node_modules', '.prisma', 'client')
  const engines = existsSync(engDir) ? readdirSync(engDir).filter((f) => f.includes('query_engine')) : []
  if (engines.length === 0) await die('Prisma engine not found in the bundle')
  ok(`Production build verified (${engines.length} bundled Prisma engines: ${engines.join(', ')})`)
  const rtModules = existsSync(path.join(rtDir, 'node_modules', 'socket.io'))
  if (!rtModules) await die('Realtime service dependencies not found in the bundle')
  ok('Bundled Realtime dependencies verified')
  const fontCss = path.join(standaloneDir2, 'public', 'fonts', 'vazirmatn.css')
  if (!existsSync(fontCss)) {
    await dieOldAppBundle(appDir, 'Self-hosted font missing from the installed panel (public/fonts/vazirmatn.css).')
  }
  const fontFiles = readdirSync(path.dirname(fontCss)).filter((f) => f.endsWith('.woff2'))
  if (fontFiles.length < 9) await die(`Self-hosted font files incomplete (${fontFiles.length}/9 woff2)`)
  ok(`Self-hosted Vazirmatn font verified (${fontFiles.length} woff2 weights - no CDN dependency)`)
  // v1.9.11: custom realtime port -> patch the compiled client chunk
  // (exact literal) + server fallback literal + BOTH gateway rewrite
  // carriers, then verify.
  if (rtPort !== RT_PORT_DEFAULT) {
    const patch = patchRtPort(panelDir, rtPort)
    if (patch.client < 1 || patch.gateway < 2 || !verifyRtPatch(panelDir, rtPort)) {
      await die(`Patching the panel for realtime port ${rtPort} failed - the build does not match the expected pattern. Re-run with the default port ${RT_PORT_DEFAULT}.`)
    }
    ok(`Panel patched for realtime port ${rtPort} (${patch.client} client chunk, ${patch.server} server chunks, ${patch.gateway} gateway rewrite files - direct AND proxy modes)`)
  }
  try { await cpRobust(envPath, path.join(standaloneDir2, '.env'), 'standalone .env mirror'); await lockDown(path.join(standaloneDir2, '.env'), 'file', 'standalone .env mirror') } catch { /* non-fatal */ }
  log(`verified: standalone + ${engines.length} prisma engines + realtime modules (rtPort=${rtPort})`)

  // ═══ Step 5 — address mode ═══════════════════════════════
  step(5, 'Access address mode')
  let mode = typeof arg('mode') === 'string' ? arg('mode') : undefined
  if (!mode) mode = target === 'cloudflare' ? 'tunnel' : 'local'
  if (!tunnelKind && typeof arg('tunnel-kind') === 'string') tunnelKind = arg('tunnel-kind')
  if (!tunnelKind) tunnelKind = 'quick'
  const statePath = path.join(installDir, 'tunnel-state.json')
  // v1.9.10 FIX: a corrupted tunnel-state.json is disposable cache.
  let state = {}
  try {
    state = JSON.parse(readFileSync(statePath, 'utf8'))
  } catch (e) {
    if (existsSync(statePath)) {
      warn('tunnel-state.json was corrupted - it has been reset (non-fatal).')
      log(`tunnel-state.json parse failed, reset: ${e.message}`)
    }
    state = {}
  }

  if (mode === 'local') {
    state.kind = 'local'
    info(`Local network mode - address: http://localhost:${panelPort}`)
  } else if (tunnelKind === 'domain') {
    warn('Domain mode needs internet and Cloudflare - it is NOT included in the offline bundle; choose local mode if you have no internet')
    const cfToken = argOrEnv('cf-token', 'MEDPAD_CF_TOKEN') || (menu?.cfToken) || (await ask('Cloudflare API Token'))
    const cfDomain = arg('cf-domain') || (menu?.cfDomain) || (await ask('Your domain (e.g. example.com)'))
    const subdomain = arg('subdomain') || (menu?.subdomain) || (await ask('Subdomain', 'panel'))
    if (!cfToken || !cfDomain) {
      warn('Token or domain not provided - falling back to local network mode')
      state.kind = 'local'
    } else {
      const { setupDomainTunnel, formatDnsPlan } = await import('./lib/cloudflare.mjs')
      // v1.9.12: DNS changes are PLANNED, LISTED and CONFIRMED before anything
      // is modified; a restorable backup is written before the first change.
      const cfConfirmFlag = !!arg('cf-confirm')
      let cfConfirmed = cfConfirmFlag
      let attempt = 0
      while (true) {
        attempt++
        try {
          const result = await setupDomainTunnel({
            token: cfToken, domain: cfDomain, subdomain,
            state: { tunnelId: state.tunnelId, service: `http://localhost:${panelPort}` },
            backupDir: path.join(installDir, 'tunnel-creds'),
            confirm: cfConfirmed,
          })
          const credDir = path.join(installDir, 'tunnel-creds')
          mkdirSync(credDir, { recursive: true })
          const credFile = path.join(credDir, `${result.tunnelId}.json`)
          writeFileSync(credFile, JSON.stringify({ AccountTag: result.accountTag, TunnelSecret: result.tunnelToken.split(':').pop(), TunnelID: result.tunnelId }, null, 2))
          writeFileSync(path.join(credDir, 'config.yml'),
            `tunnel: ${result.tunnelId}\ncredentials-file: ${credFile.replace(/\\/g, '/')}\nprotocol: http2\n\ningress:\n  - hostname: ${result.hostname}\n    service: http://localhost:${panelPort}\n  - service: http_status:404\n`)
          state.kind = 'domain'; state.tunnelId = result.tunnelId; state.hostname = result.hostname
          state.publicUrl = `https://${result.hostname}`
          ok(`Your fixed address: ${state.publicUrl}`)
          break
        } catch (e) {
          if (e && e.code === 'MEDPAD_CF_CONFIRM_REQUIRED') {
            // destructive changes detected — show the plan, ask for explicit confirmation
            info('')
            info('  The following Cloudflare DNS changes are needed:')
            for (const line of formatDnsPlan(e.plan)) info(`    ${line}`)
            info('  (A restorable backup is written to tunnel-creds/ before ANY change.)')
            if (NON_INTERACTIVE) {
              if (cfConfirmFlag) { /* flag given but plan changed? confirm again is impossible non-interactively */ }
              err('DNS changes need explicit confirmation, but this is a non-interactive install.')
              err('Re-run from the browser wizard and tick the DNS confirmation box, or add --cf-confirm.')
              warn('Falling back to local network mode')
              state.kind = 'local'
              break
            }
            cfConfirmed = (await ask(`Type YES to confirm these DNS changes for ${e.plan.hostname}`, '')).trim().toUpperCase() === 'YES'
            if (!cfConfirmed) {
              warn('You did not confirm - no DNS record was changed. Falling back to local network mode.')
              log('cf dns plan NOT confirmed - local mode')
              state.kind = 'local'
              break
            }
            continue // retry with confirm=true
          }
          err(`Domain setup failed: ${e.message}`)
          if (e && e.rollbackNote) warn(e.rollbackNote)
          warn('Falling back to local network mode')
          state.kind = 'local'
          break
        }
      }
    }
  } else {
    state.kind = 'quick'
    info('Quick tunnel: the random address is shown when the server starts (needs internet)')
  }
  writeFileSync(statePath, JSON.stringify(state, null, 2))

  // v1.9.12: tunnel credentials are secrets — owner-only, every install.
  if (existsSync(path.join(installDir, 'tunnel-creds'))) {
    await lockDown(path.join(installDir, 'tunnel-creds'), 'dir', 'tunnel-creds')
    for (const f of readdirSync(path.join(installDir, 'tunnel-creds'))) {
      const p = path.join(installDir, 'tunnel-creds', f)
      try { if (statSync(p).isFile()) await lockDown(p, 'file', `tunnel-creds/${f}`) } catch { /* ignore */ }
    }
  }

  // ═══ Step 6 — start scripts + server manager + config ════
  step(6, 'Create start scripts and server manager')
  const win = onWindows()
  const startBat = path.join(installDir, 'start-server.bat')
  const stopBat = path.join(installDir, 'stop-server.bat')
  const startSh = path.join(installDir, 'start-server.sh')
  const nodeInBat = nodeExe === 'node' ? 'node' : nodeExe

  // v1.9.11: helper scripts the generated launchers depend on. When installing
  // into a custom --dir, copy them there as well (default: package dir = install
  // dir, they are already in place).
  if (installDir !== __dirname) {
    for (const f of ['services.mjs', 'server-manager.mjs', 'wizard.mjs', 'tunnel.mjs']) {
      const src = path.join(__dirname, f)
      if (existsSync(src)) { try { await cpRobust(src, path.join(installDir, f), f) } catch { /* non-fatal */ } }
    }
    for (const d of ['ui', 'lib']) {
      const src = path.join(__dirname, d)
      if (existsSync(src)) { try { await cpRobust(src, path.join(installDir, d), d) } catch { /* non-fatal */ } }
    }
  }

  if (win) {
    // v1.9.6 FIX (kept): generated .bat files must be ASCII-only + CRLF.
    const crlf = (s) => s.replace(/\n/g, '\r\n')
    // v1.9.11: start/stop now go through the SERVER MANAGER so the panel
    // starts detached, the main page OPENS AUTOMATICALLY in the browser and
    // stopping is reliable (pids + ports + verification).
    writeFileSync(startBat, crlf(`@echo off
chcp 65001 >nul
title MedPad Start v${INSTALLER_VER}
cd /d "%~dp0"
echo ============================================
echo  MedPad v${INSTALLER_VER} - starting services...
echo  The panel page will open in your browser.
echo ============================================
"${nodeInBat}" server-manager.mjs start --open
echo.
pause
`), 'utf8')
    writeFileSync(stopBat, crlf(`@echo off
chcp 65001 >nul
title MedPad Stop
cd /d "%~dp0"
echo Stopping MedPad services...
"${nodeInBat}" server-manager.mjs stop
echo.
pause
`), 'utf8')
    // v1.9.11: the GUI server manager (start/stop buttons + status)
    writeFileSync(path.join(installDir, 'server-manager.bat'), crlf(`@echo off
chcp 65001 >nul
title MedPad Server Manager (GUI)
cd /d "%~dp0"
echo ============================================
echo  MedPad Server Manager v${INSTALLER_VER}
echo  A control page opens in your browser.
echo  Keep this window open while using it.
echo ============================================
"${nodeInBat}" server-manager.mjs gui
echo.
pause
`), 'utf8')
    // v1.9.7: simple aliases with the names users look for first
    writeFileSync(path.join(installDir, 'start.bat'), crlf(`@echo off
title MedPad Start
rem v1.9.7: start.bat is an alias for start-server.bat
call "%~dp0start-server.bat"
`), 'utf8')
    writeFileSync(path.join(installDir, 'stop.bat'), crlf(`@echo off
title MedPad Stop (alias)
rem v1.9.7: stop.bat is an alias for stop-server.bat
call "%~dp0stop-server.bat"
`), 'utf8')
    // kept for compatibility: manual realtime launcher (server-manager starts
    // realtime itself; this is only for direct debugging). v1.9.12: NO token
    // here — realtime/index.js reads SOCKET_TOKEN from ../panel/.env when
    // the env var is not set, so generated .bat files stay secret-free.
    writeFileSync(path.join(installDir, 'start-realtime.bat'), crlf(`@echo off
chcp 65001 >nul
title MedPad-Realtime
cd /d "%~dp0"
set "REALTIME_PORT=${rtPort}"
"${nodeInBat}" realtime\\index.js
pause
`), 'utf8')
    ok('start.bat / start-server.bat / stop.bat / stop-server.bat / server-manager.bat / start-realtime.bat created (ASCII + CRLF)')
  }
  writeFileSync(startSh, `#!/usr/bin/env bash
# MedPad start v${INSTALLER_VER} - starts panel + realtime via the server
# manager (detached, logs in logs/), waits until healthy, then opens the
# panel page in the browser.
cd "$(dirname "$0")"
NODE_EXE='${nodeExe === 'node' ? 'node' : nodeExe}'
[ -x "$NODE_EXE" ] || NODE_EXE=node
exec "$NODE_EXE" server-manager.mjs start --open
`, 'utf8')
  writeFileSync(path.join(installDir, 'stop-server.sh'), `#!/usr/bin/env bash
# MedPad stop v${INSTALLER_VER} - stops panel + realtime (+ tunnel) reliably.
cd "$(dirname "$0")"
NODE_EXE='${nodeExe === 'node' ? 'node' : nodeExe}'
[ -x "$NODE_EXE" ] || NODE_EXE=node
exec "$NODE_EXE" server-manager.mjs stop
`, 'utf8')
  writeFileSync(path.join(installDir, 'server-manager.sh'), `#!/usr/bin/env bash
# MedPad Server Manager (GUI) v${INSTALLER_VER} - a control page opens in
# your browser with start/stop/restart buttons and live status.
cd "$(dirname "$0")"
NODE_EXE='${nodeExe === 'node' ? 'node' : nodeExe}'
[ -x "$NODE_EXE" ] || NODE_EXE=node
exec "$NODE_EXE" server-manager.mjs gui
`, 'utf8')
  try { chmodSync(startSh, 0o755); chmodSync(path.join(installDir, 'stop-server.sh'), 0o755); chmodSync(path.join(installDir, 'server-manager.sh'), 0o755) } catch { /* ignore */ }
  ok('start-server.sh / stop-server.sh / server-manager.sh created')

  // v1.9.11: medpad-config.json — single source of truth for ports/target/
  // tunnel. services.mjs, wizard.mjs and server-manager.mjs all read it.
  const cfgPath = path.join(installDir, 'medpad-config.json')
  const cfg = {
    version: INSTALLER_VER,
    target: target || 'local',
    adminUser,
    panelPort,
    rtPort,
    installedAt: new Date().toISOString(),
    tunnel: { kind: state.kind || 'local', hostname: state.hostname || '', publicUrl: state.publicUrl || '' },
  }
  try { writeFileSync(cfgPath, JSON.stringify(cfg, null, 2)); ok(`medpad-config.json written (panel :${panelPort}, realtime :${rtPort})`) } catch (e) { warn(`could not write medpad-config.json: ${e.message}`) }

  // v1.9.11: VPS target on Linux -> systemd services (auto-start on boot)
  if (target === 'vps' && !onWindows()) {
    const nodeAbs = nodeExe === 'node' ? (spawnSync('sh', ['-c', 'command -v node'], { encoding: 'utf8' }).stdout || 'node').trim() : nodeExe
    const unitPanel = `[Unit]
Description=MedPad Panel (Next.js standalone)
After=network.target medpad-realtime.service

[Service]
Type=simple
WorkingDirectory=${installDir}
Environment=PORT=${panelPort}
Environment=HOSTNAME=0.0.0.0
Environment=DATABASE_URL=file:${dbFile}
Environment=SOCKET_TOKEN=${socketToken}
Environment=MEDPAD_SOCKET_MODE=${socketMode}
Environment=REALTIME_URL=http://127.0.0.1:${rtPort}
ExecStart=${nodeAbs} ${installDir}/panel/.next/standalone/server.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
`
    const unitRt = `[Unit]
Description=MedPad Realtime (socket.io, authenticated)
After=network.target

[Service]
Type=simple
WorkingDirectory=${installDir}
Environment=REALTIME_PORT=${rtPort}
Environment=SOCKET_TOKEN=${socketToken}
ExecStart=${nodeAbs} ${installDir}/realtime/index.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
`
    writeFileSync(path.join(installDir, 'medpad-panel.service'), unitPanel)
    writeFileSync(path.join(installDir, 'medpad-realtime.service'), unitRt)
    ok('systemd units created: medpad-panel.service + medpad-realtime.service')
    // v1.9.12: the units embed SOCKET_TOKEN — lock them to the owner (the
    // copies in /etc/systemd/system are chmod'd too, right after cpSync).
    for (const u of ['medpad-panel.service', 'medpad-realtime.service']) {
      try { await lockDown(path.join(installDir, u), 'file', u) } catch { /* best-effort */ }
    }
    tunnelUnit = false
    if (state.kind === 'domain' && existsSync(path.join(installDir, 'tunnel-creds', 'config.yml'))) {
      const cfdBin = existsSync('/usr/bin/cloudflared') ? '/usr/bin/cloudflared' : (existsSync(path.join(installDir, 'bin', 'cloudflared')) ? path.join(installDir, 'bin', 'cloudflared') : '/usr/bin/cloudflared')
      writeFileSync(path.join(installDir, 'medpad-tunnel.service'), `[Unit]
Description=MedPad Cloudflare Tunnel (${state.hostname})
After=network.target medpad-panel.service

[Service]
Type=simple
ExecStart=${cfdBin} --config ${installDir}/tunnel-creds/config.yml tunnel run
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
`)
      tunnelUnit = true
      ok('systemd unit created: medpad-tunnel.service (Cloudflare domain tunnel)')
    }
    const isRoot = typeof process.geteuid === 'function' && process.geteuid() === 0
    if (isRoot) {
      const enable = NON_INTERACTIVE ? true : (await ask('Install + enable the systemd services now (start on boot)? (y/n)', 'y')).toLowerCase().startsWith('y')
      if (enable) {
        for (const u of ['medpad-realtime', 'medpad-panel', ...(tunnelUnit ? ['medpad-tunnel'] : [])]) {
          try { cpSync(path.join(installDir, `${u}.service`), `/etc/systemd/system/${u}.service`); chmodSync(`/etc/systemd/system/${u}.service`, 0o600) } catch (e) { warn(`could not copy ${u}.service: ${e.message}`) }
        }
        spawnSync('systemctl', ['daemon-reload'], { stdio: 'ignore' })
        for (const u of ['medpad-realtime', 'medpad-panel', ...(tunnelUnit ? ['medpad-tunnel'] : [])]) {
          spawnSync('systemctl', ['enable', '--now', u], { stdio: 'ignore' })
        }
        ok('systemd services installed and enabled (medpad-panel, medpad-realtime' + (tunnelUnit ? ', medpad-tunnel' : '') + ')')
        log('systemd services enabled')
      } else {
        info('Skipped systemd registration - the unit files are in this folder if you want them later.')
      }
    } else {
      info('To run as a boot service (recommended on a VPS), as root run:')
      info(`    sudo cp ${installDir}/medpad-*.service /etc/systemd/system/ && sudo systemctl daemon-reload`)
      info('    sudo systemctl enable --now medpad-realtime medpad-panel' + (tunnelUnit ? ' medpad-tunnel' : ''))
    }
  }

  // ═══ v1.9.13-dev.2 (user report #4): Windows Firewall exception ═══
  // Phones / the Android app connect to this PC over the LAN — Windows
  // blocks inbound connections to node.exe by default. Add our ALLOW rules
  // now (the installer normally runs elevated via install.bat UAC, so no
  // extra prompt appears). Every service start re-checks and re-adds them
  // automatically (server-manager.mjs) if they are removed again.
  if (process.platform === 'win32') {
    info('Adding Windows Firewall exception (panel + realtime + node)...')
    try {
      const fw = await ensureMedPadFirewall({
        nodeExe: process.execPath, panelPort, rtPort,
        log: (m) => info(`firewall: ${m}`),
      })
      if (fw.ok && fw.added.length) {
        ok(`Windows Firewall rules added: ${fw.added.join(', ')}`)
        log(`firewall rules added: ${fw.added.join(', ')}`)
      } else if (fw.ok) {
        ok('Windows Firewall rules already in place')
      } else {
        warn(`Windows Firewall rules could not be added: ${fw.failed.join(', ')} (${fw.note})`)
        warn('  The panel may be unreachable from your phone / the Android app.')
        warn('  Fix: run install.bat as Administrator, or allow the UAC prompt on the next start-server.')
        log(`firewall rules failed: ${fw.failed.join(', ')} (${fw.note})`)
      }
    } catch (e) {
      warn(`Firewall step skipped: ${e.message} (non-fatal)`)
      log(`firewall exception: ${e.stack || e.message}`)
    }
  }

  // ═══ Step 7 — health check ═══════════════════════════════
  step(7, 'Health check')
  if (await portBusy(panelPort)) warn(`Port ${panelPort} is already in use - close the previous start-server first`)
  if (panelPort === rtPort) warn(`Panel and realtime use the SAME port ${panelPort} - change one of them!`)
  ok('All offline components verified')

  // ═══ v1.9.12-test.2: Windows ACL hardening (LAST step, best-effort) ═══
  // Runs only on real Windows, AFTER every file operation has finished,
  // using the account SID. Verified with real file access probes;
  // auto-reverts with icacls /reset if the restriction would break
  // anything. Never fatal — this can no longer kill an install.
  if (process.platform === 'win32') {
    info('Hardening file permissions (Windows ACL)...')
    try {
      await restrictAcl('panel/.env', envPath, false)
      await restrictAcl('panel/db', path.join(panelDir, 'db'), true)
    } catch (e) {
      warn(`ACL hardening skipped: ${e.code || e.message} (non-fatal)`)
      log(`acl hardening exception: ${e.stack || e.message}`)
    }
  }

  const mins = ((Date.now() - t0) / 60000).toFixed(1)
  console.log(`
==============================================
  MedPad v${INSTALLER_VER} - OFFLINE INSTALL COMPLETE  (${mins} min)
==============================================
${win ? `  START THE PANEL:  double-click  "${path.join(installDir, 'start.bat')}"
                    (the panel page opens in your browser automatically)
  STOP THE PANEL:   double-click  stop-server.bat
  GUI CONTROL:      double-click  server-manager.bat  (start/stop buttons)
` : `  START THE PANEL:  bash ${path.join(installDir, 'start-server.sh')}
                    (the panel page opens in your browser automatically)
  STOP THE PANEL:   bash ${path.join(installDir, 'stop-server.sh')}
  GUI CONTROL:      bash ${path.join(installDir, 'server-manager.sh')}  (start/stop buttons)
${target === 'vps' ? `  BOOT SERVICE:    systemd units medpad-panel + medpad-realtime${tunnelUnit ? ' + medpad-tunnel' : ''} were created
` : ''}`}
  Panel (local):    http://localhost:${panelPort}
  Socket:           :${rtPort} (admin-authenticated only)
  Admin login:      ${adminUser} / ${generatedPassword ? 'the RANDOM password printed above (save it!)' : adminPass ? '(the password you chose)' : '(your existing password)'}
${state.kind === 'domain' ? `  Public address:   ${state.publicUrl}
` : ''}  Phone / app URL:  ${lanAddresses().map((ip) => `http://${ip}:${panelPort}`).join('   ') || 'http://<this-computer-IP>:' + panelPort}
                     (use this in the Android app and the phone browser - same Wi-Fi;
                      the app logs in with an EMPLOYEE account created in the panel)
  Security:         dashboard socket accepts only authenticated admin sessions
  Logs:             ${LOG}
${migrated ? '  NOTE:             your data was migrated from the old medpad-install folder\n' : ''}`)
  log(`install complete in ${mins}min (target=${target} port=${panelPort}/${rtPort})`)

  // v1.9.11: offer to start right away — the panel page then opens in the
  // browser automatically (the whole point of the new start flow).
  if (!NON_INTERACTIVE) {
    const now = (await ask('Start the panel now? (y/n)', 'y')).toLowerCase().startsWith('y')
    if (now) {
      info('Starting MedPad...')
      const mgr = spawnSync(nodeExe, ['server-manager.mjs', 'start', '--open'], { cwd: installDir, encoding: 'utf8', timeout: 180000 })
      process.stdout.write(mgr.stdout || '')
      if (mgr.status !== 0) warn(`server-manager exited with code ${mgr.status} - run start-server.bat to try again`)
    }
  }
  await pause()
}

main().catch(async (e) => {
  err(`Unexpected error: ${e.message}`)
  log(`CRASH ${e.stack || e.message}`)
  await pause()
  process.exit(1)
})
