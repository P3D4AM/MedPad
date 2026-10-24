/**
 * MedPad services helper — v1.9.11
 *
 *  v1.9.11 changes:
 *   - config-aware ports: stopMedPadServices() now reads medpad-config.json
 *     (panelPort/rtPort) so custom ports chosen in the wizard are stopped too.
 *     Defaults stay 3000/3003. A ports[] override is available for installers
 *     that know both the OLD and the NEW port (union) during an upgrade.
 *   - readConfig() exported (used by server-manager / wizard / install).
 *   - openBrowser() exported — cross-platform best-effort "open in default
 *     browser" (Windows start / macOS open / Linux xdg-open). Used by the
 *     installer, the browser wizard and the server manager so the MAIN PAGE
 *     ALWAYS opens automatically after the server starts (v1.9.11 request).
 *
 *  v1.9.9 (kept): REAL verification after kill — the ports must actually be
 *  free; lsof can silently return nothing in containers (ss/netstat fallback).
 *  v1.9.2+ (kept): two-pass kill with saved pids.
 */
import { exec } from 'child_process'
import { promisify } from 'util'
import { existsSync, readFileSync } from 'fs'
import path from 'path'
const pexec = promisify(exec)

export function pidFile(dir) {
  return path.join(dir, '.medpad.pids')
}

export function readPids(dir) {
  try {
    return JSON.parse(readFileSync(pidFile(dir), 'utf8'))
  } catch {
    return { pids: [] }
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

function psList() {
  return new Promise((resolve) => {
    const cmd =
      process.platform === 'win32'
        ? 'tasklist /FO CSV /NH'
        : 'ps -eo pid=,comm='
    exec(cmd, { timeout: 8000 }, (err, stdout) => {
      if (err) return resolve([])
      const rows = stdout.split(/\r?\n/).filter(Boolean)
      const out = rows.map((line) => {
        if (process.platform === 'win32') {
          const m = line.match(/^"([^"]+)","(\d+)/)
          return m ? { name: m[1].toLowerCase(), pid: Number(m[2]) } : null
        }
        const parts = line.trim().split(/\s+/, 2)
        return { name: (parts[1] || '').toLowerCase(), pid: Number(parts[0]) }
      })
      resolve(out.filter(Boolean))
    })
  })
}

function kill(pid, force) {
  return new Promise((resolve) => {
    const cmd =
      process.platform === 'win32'
        ? `taskkill /PID ${pid} ${force ? '/F' : ''}`
        : `kill ${force ? '-9' : ''} ${pid}`.trim()
    exec(cmd, { timeout: 5000 }, () => resolve())
  })
}

/**
 * Kill processes belonging to a previous MedPad install (node/cloudflared/rt).
 * v1.9.11: ports come from medpad-config.json unless overridden explicitly
 * (installers pass the union of old+new ports during a port-changing upgrade).
 * Returns { killed: n, verified: bool } — verified=false means something
 * survived and the caller MUST warn loudly (root cause of the EPERM bug).
 */
export async function stopMedPadServices(dir, { ports = null, ourNames = ['node', 'node.exe', 'bun', 'bun.exe', 'cloudflared', 'cloudflared.exe'] } = {}) {
  const saved = readPids(dir).pids || []
  let killed = 0

  for (const pid of saved) {
    await kill(pid, false)
  }
  await new Promise((r) => setTimeout(r, 1200))
  for (const pid of saved) {
    await kill(pid, true)
  }

  // also kill anything listening on our ports (multi-fallback — see pidsOnPort)
  const portList = Array.isArray(ports) && ports.length > 0 ? [...new Set(ports)] : servicePorts(dir)
  const killedByPort = []
  for (const port of portList) {
    for (const pid of await pidsOnPort(port)) {
      await kill(pid, true)
      killedByPort.push(pid)
    }
  }

  // v1.9.9: REAL verification — the ports must actually be free afterwards.
  await new Promise((r) => setTimeout(r, 800))
  const stillBusy = []
  for (const port of portList) {
    if ((await pidsOnPort(port)).length > 0) stillBusy.push(port)
  }

  // verification pass (saved pids)
  const alive = await psList()
  const survivors = saved.filter((pid) => alive.some((p) => p.pid === pid))
  killed = saved.length - survivors.length + killedByPort.length
  const verified = survivors.length === 0 && stillBusy.length === 0
  return { killed, verified, survivors, stillBusy }
}

/** PIDs listening on a port — multiple fallbacks (lsof can silently return
 *  nothing in containers; fuser is often not installed; ss needs -p).
 *  v1.9.9: added ss -tlnp parsing + netstat fallback so the kill list is
 *  never silently empty on Linux. */
async function pidsOnPort(port) {
  if (process.platform === 'win32') {
    const { stdout } = await pexec(`netstat -ano | findstr :${port} | findstr LISTENING`).catch(() => ({ stdout: '' }))
    return [...stdout.matchAll(/\s(\d+)\s*$/gm)].map((m) => Number(m[1]))
  }
  // 1) lsof (fast, but can silently fail in containers)
  try {
    const { stdout } = await pexec(`lsof -t -i :${port} 2>/dev/null || true`)
    const pids = stdout.split(/\s+/).filter(Boolean).map(Number).filter((n) => Number.isFinite(n))
    if (pids.length > 0) return [...new Set(pids)]
  } catch { /* try next */ }
  // 2) ss -tlnp (iproute2 — present on virtually every modern Linux)
  try {
    const { stdout } = await pexec(`ss -tlnp 2>/dev/null || true`)
    const pids = []
    for (const line of stdout.split(/\r?\n/)) {
      if (new RegExp(`[:.]${port}\\s`).test(line)) {
        for (const m of line.matchAll(/pid=(\d+)/g)) pids.push(Number(m[1]))
      }
    }
    if (pids.length > 0) return [...new Set(pids)]
  } catch { /* try next */ }
  // 3) netstat (net-tools fallback)
  try {
    const { stdout } = await pexec(`netstat -tlnp 2>/dev/null || true`)
    const pids = []
    for (const line of stdout.split(/\r?\n/)) {
      if (new RegExp(`[:.]${port}\\s`).test(line)) {
        const m = line.match(/\s(\d+)\/\S*\s*$/)
        if (m) pids.push(Number(m[1]))
      }
    }
    return [...new Set(pids)]
  } catch {
    return []
  }
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
