/**
 * MedPad services helper — v1.9.12 (PROCESS-SAFETY REWRITE)
 *
 *  v1.9.12 changes (mission "critical process management fix"):
 *   - PROOF OF OWNERSHIP before every kill. A process is only stopped when it
 *     can be PROVEN to belong to THIS install directory:
 *       * Linux:   /proc/<pid>/cmdline + /proc/<pid>/cwd are read directly.
 *       * Windows: one PowerShell CIM (or wmic) query returns pid ->
 *                  { name, commandline, creationDate }; ownership = the
 *                  command line / script path references our install dir
 *                  (server-manager.mjs v1.9.12 spawns children with ABSOLUTE
 *                  script paths, and quick tunnels get --pidfile <our dir>).
 *       * Saved pids (.medpad.pids v2) store { pid, kind, script, startedAtMs }
 *                  — a reused PID (different start time / command line) is
 *                  NEVER killed.
 *   - Foreign listeners on our ports are REPORTED, never killed. The caller
 *     (installer) fails with a clear error instead.
 *   - Exact port matching everywhere. The old Windows `findstr :3000`
 *     substring match (which also matched :30000 / remote ports) is replaced
 *     by a full netstat parse with an exact local-address port comparison.
 *   - The old `ourNames` NAME heuristic is REMOVED — "a process named
 *     node.exe" proves nothing. Identity requires path/start-time evidence.
 *
 *  v1.9.11 (kept): config-aware ports, readConfig(), openBrowser().
 *  v1.9.9 (kept): real verification after kill (ports must actually be free).
 *  v1.9.2+ (kept): two-pass kill with saved pids.
 */
import { exec } from 'child_process'
import { promisify } from 'util'
import { existsSync, readFileSync, readdirSync, readlinkSync } from 'fs'
import path from 'path'
const pexec = promisify(exec)

export function pidFile(dir) {
  return path.join(dir, '.medpad.pids')
}

/**
 * Read .medpad.pids — supports BOTH formats:
 *   v1 (v1.9.11): { pids: [123, 456] }                    (unverified entries)
 *   v2 (v1.9.12): { v: 2, pids: [{pid, kind, script, startedAtMs}] }
 * Returns { entries: [{ pid, kind?, script?, startedAtMs?, legacy? }] }
 */
export function readPids(dir) {
  try {
    const raw = JSON.parse(readFileSync(pidFile(dir), 'utf8'))
    const list = Array.isArray(raw?.pids) ? raw.pids : []
    const entries = list.map((p) =>
      typeof p === 'number'
        ? { pid: p, legacy: true }
        : { pid: Number(p?.pid), kind: p?.kind || '', script: p?.script || '', startedAtMs: Number(p?.startedAtMs) || 0 }
    ).filter((e) => Number.isInteger(e.pid) && e.pid > 1)
    return { entries }
  } catch {
    return { entries: [] }
  }
}

/** v1.9.11: read medpad-config.json — returns {} when absent/corrupted. */
export function readConfig(dir) {
  try {
    return JSON.parse(readFileSync(path.join(dir, 'medpad-config.json'), 'utf8'))
  } catch {
    return {}
  }
}

/** v1.9.11: the ports this install actually uses (config-aware). */
export function servicePorts(dir) {
  const c = readConfig(dir)
  const panel = Number(c.panelPort)
  const rt = Number(c.rtPort)
  const ports = []
  if (Number.isInteger(panel) && panel > 0 && panel < 65536) ports.push(panel)
  if (Number.isInteger(rt) && rt > 0 && rt < 65536) ports.push(rt)
  if (ports.length === 0) ports.push(3000, 3003)
  return [...new Set(ports)]
}

/** v1.9.11: open a URL in the default browser — best-effort, never throws. */
export function openBrowser(urlStr) {
  try {
    if (process.platform === 'win32') {
      exec(`start "" "${urlStr}"`, { timeout: 8000 }, () => {})
    } else if (process.platform === 'darwin') {
      exec(`open "${urlStr}"`, { timeout: 8000 }, () => {})
    } else {
      exec(`xdg-open "${urlStr}" 2>/dev/null || true`, { timeout: 8000 }, () => {})
    }
    return true
  } catch {
    return false
  }
}

// ─── path helpers ─────────────────────────────────────────────────────

/** normalized comparable path: forward slashes, no trailing slash,
 *  lowercase on Windows (its filesystem is case-insensitive). */
export function normPath(p) {
  let s = String(p || '').replace(/\\/g, '/').replace(/\/+$/, '')
  if (process.platform === 'win32') s = s.toLowerCase()
  return s
}

/** Does the running process `info` ({cmd, cwd}) belong to the MedPad install
 *  at `dir`? Requires a STRONG signal — a path reference to this install
 *  dir (as a path segment, never a bare substring: '/opt/med' never matches
 *  '/opt/medpad/...' because the next char must be '/'). */
export function ownsProcess(info, dir) {
  const d = normPath(dir)
  if (!d) return false
  const cmd = normPath(info?.cmd || '')
  const cwd = normPath(info?.cwd || '')
  const cmdHit = cmd !== '' && (cmd.includes(d + '/') || cmd === d || cmd.startsWith(d + ' ') || cmd.includes(' ' + d + '/') || cmd.includes(' ' + d + ' '))
  const cwdHit = cwd !== '' && (cwd === d || cwd.startsWith(d + '/'))
  return cmdHit || cwdHit
}

// ─── process inventory (identity map) ─────────────────────────────────

function procInfoLinux(pid) {
  try {
    const cmd = readFileSync(`/proc/${pid}/cmdline`, 'utf8').replace(/\0/g, ' ').trim()
    let cwd = ''
    try { cwd = readlinkSync(`/proc/${pid}/cwd`) } catch { /* needs same-user/root */ }
    let name = ''
    try { name = readFileSync(`/proc/${pid}/comm`, 'utf8').trim() } catch { /* ignore */ }
    let startedAtMs = 0
    try {
      const stat = readFileSync(`/proc/${pid}/stat`, 'utf8')
      const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ')
      const startTicks = Number(fields[19]) // field 22 (state is field 3 → index 19)
      const btime = Number((readFileSync('/proc/stat', 'utf8').match(/btime (\d+)/) || [])[1])
      if (startTicks > 0 && btime > 0) startedAtMs = Math.round((btime + startTicks / 100) * 1000)
    } catch { /* ignore */ }
    return { pid, cmd, cwd, name, startedAtMs }
  } catch {
    return null
  }
}

/** Windows: one query returns ALL processes with command line + creation
 *  date. PowerShell CIM first (JSON, precise); wmic CSV as fallback. */
async function procMapWindows() {
  // 1) PowerShell — structured JSON, works on every supported Windows
  try {
    const ps = [
      `Get-CimInstance Win32_Process | Select-Object ProcessId,Name,`,
      `@{n='CT';e={[string]$_.CreationDate.ToString('yyyy-MM-dd HH:mm:ss.fff')}},`,
      `@{n='CL';e={[string]$_.CommandLine}} | ConvertTo-Json -Compress`,
    ].join('')
    const { stdout } = await pexec(
      `powershell -NoProfile -NonInteractive -Command "${ps.replace(/"/g, '\\"')}"`,
      { timeout: 25000, maxBuffer: 16 * 1024 * 1024 }
    )
    const arr = stdout.trim().startsWith('[') ? JSON.parse(stdout) : [JSON.parse(stdout)]
    const map = new Map()
    for (const p of arr) {
      if (!p || !Number.isInteger(p.ProcessId)) continue
      let startedAtMs = 0
      const m = String(p.CT || '').match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})$/)
      if (m) startedAtMs = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], +m[7]) - new Date().getTimezoneOffset() * 0 // local time → epoch (tz offset unknown here; matched only, never compared to linux values)
      map.set(p.ProcessId, { pid: p.ProcessId, name: String(p.Name || '').toLowerCase(), cmd: String(p.CL || ''), cwd: '', startedAtMs })
    }
    if (map.size > 0) return map
  } catch { /* fall through */ }
  // 2) wmic — deprecated but still shipped on most Windows
  try {
    const { stdout } = await pexec('wmic process get ProcessId,Name,CommandLine,CreationDate /format:csv', {
      timeout: 25000, maxBuffer: 16 * 1024 * 1024,
    })
    const map = new Map()
    const lines = stdout.split(/\r?\n/).filter((l) => l.trim().includes(','))
    for (const line of lines) {
      // CSV: Node,CommandLine,CreationDate,Name,ProcessId (wmic sorts alphabetically)
      const cols = line.split(',').map((c) => c.trim())
      if (cols.length < 5) continue
      const pid = Number(cols[cols.length - 1])
      if (!Number.isInteger(pid) || pid <= 0) continue
      const name = cols[cols.length - 2].toLowerCase()
      const creation = cols[cols.length - 3] // 20261024153045.123456+240
      let startedAtMs = 0
      const cm = creation.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\.(\d{3})/)
      if (cm) startedAtMs = Date.UTC(+cm[1], +cm[2] - 1, +cm[3], +cm[4], +cm[5], +cm[6], +cm[7])
      const cmd = cols.slice(1, cols.length - 3).join(',')
      map.set(pid, { pid, name, cmd, cwd: '', startedAtMs })
    }
    if (map.size > 0) return map
  } catch { /* fall through */ }
  return new Map()
}

/** pid → { pid, name, cmd, cwd, startedAtMs } for every process we can see. */
export async function processMap() {
  if (process.platform === 'win32') return procMapWindows()
  const map = new Map()
  try {
    for (const f of readdirSync('/proc')) {
      if (!/^\d+$/.test(f)) continue
      const info = procInfoLinux(Number(f))
      if (info) map.set(info.pid, info)
    }
  } catch { /* ignore */ }
  return map
}

// ─── kill / ports ─────────────────────────────────────────────────────

function kill(pid, force) {
  return new Promise((resolve) => {
    const cmd =
      process.platform === 'win32'
        ? `taskkill /PID ${pid} ${force ? '/F' : ''}`
        : `kill ${force ? '-9' : ''} ${pid}`.trim()
    exec(cmd, { timeout: 5000 }, () => resolve())
  })
}

/** PIDs LISTENING on exactly `port` — every platform parses the LOCAL
 *  address column and compares the port as a whole token (v1.9.12: no more
 *  findstr substring matches, no remote-address matches). */
async function pidsOnPort(port) {
  const portStr = String(port)
  if (process.platform === 'win32') {
    const { stdout } = await pexec('netstat -ano', { timeout: 15000, maxBuffer: 8 * 1024 * 1024 }).catch(() => ({ stdout: '' }))
    const pids = []
    for (const line of stdout.split(/\r?\n/)) {
      const m = line.trim().match(/^TCP\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)$/)
      if (!m) continue
      if (m[3].toUpperCase() !== 'LISTENING') continue
      const local = m[1] // e.g. 0.0.0.0:3000 / [::]:3000
      const c = local.lastIndexOf(':')
      if (c < 0 || local.slice(c + 1) !== portStr) continue
      pids.push(Number(m[4]))
    }
    return [...new Set(pids)]
  }
  // 1) lsof — LISTEN-only (v1.9.12: -sTCP:LISTEN so outbound connections to
  //    a remote :port are never mistaken for listeners)
  try {
    const { stdout } = await pexec(`lsof -t -i :${port} -sTCP:LISTEN 2>/dev/null || true`)
    const pids = stdout.split(/\s+/).filter(Boolean).map(Number).filter((n) => Number.isFinite(n))
    if (pids.length > 0) return [...new Set(pids)]
  } catch { /* try next */ }
  // 2) ss -tlnpH — exact local-address port parse
  try {
    const { stdout } = await pexec('ss -tlnpH 2>/dev/null || true')
    const pids = []
    for (const line of stdout.split(/\r?\n/)) {
      const cols = line.trim().split(/\s+/)
      // [state, recv-q, send-q, local, peer, ...process] or without state col
      const local = cols.find((c, i) => i >= 2 && (c.includes(`:${portStr}`) || c.includes(`]:${portStr}`)))
      if (!local) continue
      const c = local.lastIndexOf(':')
      if (c < 0 || local.slice(c + 1) !== portStr) continue
      for (const m of line.matchAll(/pid=(\d+)/g)) pids.push(Number(m[1]))
    }
    if (pids.length > 0) return [...new Set(pids)]
  } catch { /* try next */ }
  // 3) netstat -tlnp — exact local-address port parse
  try {
    const { stdout } = await pexec('netstat -tlnp 2>/dev/null || true')
    const pids = []
    for (const line of stdout.split(/\r?\n/)) {
      const cols = line.trim().split(/\s+/)
      if (cols.length < 4) continue
      const local = cols[3]
      const c = local.lastIndexOf(':')
      if (c < 0 || local.slice(c + 1) !== portStr) continue
      const m = line.match(/\s(\d+)\/\S*\s*$/)
      if (m) pids.push(Number(m[1]))
    }
    return [...new Set(pids)]
  } catch {
    return []
  }
}

// ─── the stop logic (v1.9.12) ──────────────────────────────────────────

/** classify listeners on `ports` into OURS (provable members of this
 *  install) vs FOREIGN (anything else). Exported so the installer can fail
 *  BEFORE stopping anything or touching a single file. */
export async function classifyPortOwners(dir, ports) {
  const map = await processMap()
  const ours = []
  const foreign = []
  const unknown = [] // listening pid we could not inspect
  for (const port of [...new Set(ports || [])]) {
    for (const pid of await pidsOnPort(port)) {
      const info = map.get(pid)
      if (!info) { unknown.push({ port, pid }); continue }
      if (ownsProcess(info, dir)) ours.push({ port, pid, name: info.name })
      else foreign.push({ port, pid, name: info.name || 'unknown', cmd: String(info.cmd || '').slice(0, 120) })
    }
  }
  return { ours, foreign, unknown }
}

/** saved-pid entry → proven? (identity via command line, or via recorded
 *  script path, or via exact start-time match — never name alone) */
function pidEntryProven(entry, info, dir) {
  if (!info) return false
  if (ownsProcess(info, dir)) return true
  if (entry.script && normPath(info.cmd).includes(normPath(entry.script))) return true
  if (entry.startedAtMs && info.startedAtMs && Math.abs(info.startedAtMs - entry.startedAtMs) <= 2000) {
    // same pid at the same start moment — a reused PID would differ by far
    // more than 2s; still require a plausible process name for the kind
    const n = String(info.name || '')
    if (n.startsWith('node') || n.startsWith('bun') || n.startsWith('cloudflared')) return true
  }
  return false
}

/**
 * Stop the MedPad services of THIS install — and ONLY this install.
 *
 * v1.9.12 guarantees:
 *  - every kill target is first PROVEN to belong to `dir` (see ownsProcess);
 *  - .medpad.pids entries that cannot be proven (stale, reused PID, another
 *    program) are skipped and reported in `skipped`, never killed;
 *  - processes listening on our ports that are NOT provably ours are never
 *    killed — they are reported in `foreignBusy` (caller decides: the
 *    installer stops with a clear error).
 *
 * Returns { killed, verified, survivors, stillBusy, foreignBusy, skipped }.
 */
export async function stopMedPadServices(dir, { ports = null } = {}) {
  const result = { killed: 0, verified: true, survivors: [], stillBusy: [], foreignBusy: [], skipped: [] }

  // ── 1) saved pids (identity-checked) ──
  const { entries } = readPids(dir)
  let map = await processMap()
  const toKill = []
  for (const e of entries) {
    const info = map.get(e.pid)
    if (!info) continue // already gone — fine
    if (pidEntryProven(e, info, dir)) toKill.push({ pid: e.pid, info })
    else result.skipped.push({ pid: e.pid, name: info.name || 'unknown', note: e.legacy ? 'legacy pid entry, ownership unprovable' : 'ownership unprovable' })
  }
  for (const t of toKill) await kill(t.pid, false)
  await new Promise((r) => setTimeout(r, 1000))
  for (const t of toKill) await kill(t.pid, true)
  await new Promise((r) => setTimeout(r, 400))

  // ── 2) port listeners (identity-checked) ──
  const portList = Array.isArray(ports) && ports.length > 0 ? [...new Set(ports)] : servicePorts(dir)
  map = await processMap() // refresh — pids may have changed
  for (const port of portList) {
    for (const pid of await pidsOnPort(port)) {
      const info = map.get(pid)
      if (!info) { result.skipped.push({ port, pid, note: 'listener could not be inspected' }); continue }
      if (ownsProcess(info, dir)) {
        await kill(pid, true)
        result.killed++
      } else {
        result.foreignBusy.push({ port, pid, name: info.name || 'unknown', cmd: String(info.cmd || '').slice(0, 120) })
      }
    }
  }

  // ── 3) verification — which of OUR ports are still held, and by whom ──
  await new Promise((r) => setTimeout(r, 800))
  map = await processMap()
  for (const port of portList) {
    for (const pid of await pidsOnPort(port)) {
      const info = map.get(pid)
      const knownForeign = result.foreignBusy.some((f) => f.port === port && f.pid === pid)
      if (knownForeign) continue // already reported — never killed
      if (info && ownsProcess(info, dir)) result.survivors.push({ port, pid })
      else result.stillBusy.push({ port, pid, name: info?.name || 'unknown' })
    }
  }
  result.killed += toKill.filter((t) => !map.has(t.pid)).length
  result.verified = result.survivors.length === 0 && result.stillBusy.length === 0
  return result
}

/** Is anything listening on port? (best-effort, multi-fallback — v1.9.9) */
export async function portBusy(port) {
  try {
    return (await pidsOnPort(port)).length > 0
  } catch {
    return false
  }
}

export { existsSync }
