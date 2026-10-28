/**
 * MedPad Windows Firewall helper (v1.9.13-dev.3)
 *
 * WHY THIS EXISTS (user report #4, Oct 26 2026): the Android app / phone
 * browser could not reach the panel on some Windows machines because
 * Windows Defender Firewall blocks INBOUND connections to node.exe by
 * default (the "Windows Defender Firewall has blocked some features of
 * this app" popup is easy to miss or cancel). User requirement:
 *   1. Add a firewall exception when the panel is installed.
 *   2. On EVERY service start, check in the background that the firewall
 *      is not blocking us — and if the rule was removed again, re-add it
 *      AUTOMATICALLY.
 *
 * HOW IT WORKS:
 *  - Three inbound ALLOW rules, all ASCII-named, all profile=any:
 *      "MedPad Panel (TCP <panelPort>)"     — port rule
 *      "MedPad Realtime (TCP <rtPort>)"     — port rule
 *      "MedPad Server (Node.js)"            — program rule for the exact
 *                                             node.exe that runs the panel
 *                                             (covers custom ports and
 *                                             Node upgrades)
 *  - Rules are verified by NAME + payload (LocalPort / Program) so a stale
 *    rule from a moved folder or a changed port is replaced, not trusted.
 *  - Checking (`netsh ... show rule`) does NOT need admin rights.
 *  - Adding needs admin: direct netsh first (installer runs elevated), and
 *    if that fails with an elevation error we fall back to a single UAC
 *    prompt via PowerShell Start-Process -Verb RunAs. If the user declines
 *    the prompt we return a clear, actionable warning — never throw.
 *  - Windows only. On Linux/macOS this module is a silent no-op.
 *
 * Terminal output stays PURE ASCII ENGLISH (project rule for console tools).
 */
import { spawnSync } from 'child_process'
import { existsSync, writeFileSync, unlinkSync } from 'fs'
import os from 'os'
import path from 'path'

const RULE_TAG = 'MedPad'

function isWindows() {
  return process.platform === 'win32'
}

/** run netsh and capture output (English locale assumed for parsing). */
function netsh(args, timeoutMs = 15000) {
  const r = spawnSync('netsh', args, {
    encoding: 'utf8',
    windowsHide: true,
    timeout: timeoutMs,
  })
  return {
    status: r.status,
    out: `${r.stdout || ''}\n${r.stderr || ''}`,
  }
}

/** Normalize a Windows path for comparison. */
function normPath(p) {
  return String(p || '').trim().toLowerCase().replace(/\//g, '\\')
}

/** The three rule descriptors for this install. */
export function firewallRules({ nodeExe, panelPort, rtPort }) {
  const rules = []
  const pp = Number(panelPort) || 3000
  const rp = Number(rtPort) || 3003
  rules.push({
    name: `${RULE_TAG} Panel (TCP ${pp})`,
    kind: 'port',
    port: pp,
    args: ['advfirewall', 'firewall', 'add', 'rule',
      `name=${RULE_TAG} Panel (TCP ${pp})`, 'dir=in', 'action=allow',
      'protocol=TCP', `localport=${pp}`, 'profile=any'],
    // verify: rule exists AND LocalPort matches (netsh prints "LocalPort:  3000")
    check: (out) => {
      const m = out.match(/^LocalPort:\s*(\d+)\s*$/m)
      return !!m && Number(m[1]) === pp
    },
  })
  rules.push({
    name: `${RULE_TAG} Realtime (TCP ${rp})`,
    kind: 'port',
    port: rp,
    args: ['advfirewall', 'firewall', 'add', 'rule',
      `name=${RULE_TAG} Realtime (TCP ${rp})`, 'dir=in', 'action=allow',
      'protocol=TCP', `localport=${rp}`, 'profile=any'],
    check: (out) => {
      const m = out.match(/^LocalPort:\s*(\d+)\s*$/m)
      return !!m && Number(m[1]) === rp
    },
  })
  if (nodeExe) {
    rules.push({
      name: `${RULE_TAG} Server (Node.js)`,
      kind: 'program',
      program: nodeExe,
      args: ['advfirewall', 'firewall', 'add', 'rule',
        `name=${RULE_TAG} Server (Node.js)`, 'dir=in', 'action=allow',
        `program=${nodeExe}`, 'profile=any'],
      // verify: rule exists AND Program matches this node.exe
      // (netsh prints "Program: C:\\Program Files\\nodejs\\node.exe")
      check: (out) => {
        const m = out.match(/^Program:\s*(.+?)\s*$/m)
        return !!m && normPath(m[1]) === normPath(nodeExe)
      },
    })
  }
  return rules
}

/** Does the rule exist AND match its expected payload? (no admin needed) */
function ruleMatches(rule) {
  const r = netsh(['advfirewall', 'firewall', 'show', 'rule', `name=${rule.name}`])
  if (r.status !== 0 || !r.out.includes(rule.name)) return false
  return rule.check(r.out)
}

/** Delete a rule (admin). Errors ignored — used before re-adding. */
function deleteRule(rule) {
  netsh(['advfirewall', 'firewall', 'delete', 'rule', `name=${rule.name}`])
}

/** True when the netsh output indicates we lack administrator rights. */
function needsElevation(res) {
  const t = (res.out || '').toLowerCase()
  return t.includes('requires elevation') || t.includes('run as administrator') ||
    t.includes('requested operation requires') || t.includes('access is denied')
}

/**
 * Add rules that are missing, via ONE elevated helper .cmd (single UAC
 * prompt). Writes a temp script, elevates it with PowerShell Start-Process
 * -Verb RunAs -Wait, then re-checks.
 */
function addRulesElevated(rules, log) {
  const tmp = path.join(os.tmpdir(), `medpad-fw-${Date.now()}.cmd`)
  const body = ['@echo off']
  for (const rule of rules) {
    body.push(`netsh advfirewall firewall delete rule name="${rule.name}" >nul 2>&1`)
    body.push(`netsh ${rule.args.map((a) => (a.includes(' ') ? `"${a}"` : a)).join(' ')} >nul 2>&1`)
  }
  body.push('exit /b 0')
  writeFileSync(tmp, body.join('\r\n') + '\r\n', 'ascii')
  try {
    const ps = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
      `Start-Process -FilePath '${tmp.replace(/'/g, "''")}' -Verb RunAs -Wait`],
    { encoding: 'utf8', windowsHide: true, timeout: 120000 })
    const declined = ps.status !== 0 || /canceled|cancelled/i.test(`${ps.stdout || ''}${ps.stderr || ''}`)
    return !declined
  } catch (e) {
    if (log) log(`elevated add failed: ${e.message}`)
    return false
  } finally {
    try { unlinkSync(tmp) } catch { /* best effort */ }
  }
}

/**
 * MAIN ENTRY — make sure the MedPad inbound rules exist and match.
 * Never throws. Returns a report:
 *   { skipped, ok, added: [names], failed: [names], note }
 *
 * @param {object} opts
 *   nodeExe   absolute path of the node.exe that runs the services
 *             (default: process.execPath)
 *   panelPort panel TCP port (default 3000)
 *   rtPort    realtime TCP port (default 3003)
 *   log       optional (msg) => void for verbose progress
 */
export async function ensureMedPadFirewall(opts = {}) {
  const nodeExe = opts.nodeExe || process.execPath
  const panelPort = Number(opts.panelPort) || 3000
  const rtPort = Number(opts.rtPort) || 3003
  const log = opts.log || (() => {})

  if (!isWindows()) {
    return { skipped: true, ok: true, added: [], failed: [], note: 'not-windows' }
  }
  if (!existsSync(nodeExe) && !/node(\.exe)?$/i.test(nodeExe)) {
    // program rule needs a real path; port rules still work
    log(`node exe not found (${nodeExe}) - adding port rules only`)
  }

  const all = firewallRules({ nodeExe, panelPort, rtPort })
  const missing = all.filter((r) => !ruleMatches(r))

  if (missing.length === 0) {
    log('firewall rules already in place (panel + realtime + node program)')
    return { skipped: false, ok: true, added: [], failed: [], note: 'already-ok' }
  }

  const added = []
  const failed = []

  // 1) direct attempt (works when the caller is elevated — e.g. installer)
  for (const rule of missing) {
    deleteRule(rule)
    const res = netsh(rule.args)
    if (res.status === 0) {
      added.push(rule.name)
    } else if (needsElevation(res)) {
      break // no rights — stop, handle all at once via elevation below
    } else {
      log(`netsh add failed for "${rule.name}": ${String(res.out).trim().split('\n')[0]}`)
      failed.push(rule.name)
    }
  }
  const stillMissing = all.filter((r) => !ruleMatches(r))
  if (stillMissing.length === 0) {
    return { skipped: false, ok: failed.length === 0, added, failed, note: 'added-direct' }
  }

  // 2) elevation fallback — ONE UAC prompt for all remaining rules
  log('firewall rules need administrator rights - showing a UAC prompt (allow it so phones can connect)')
  const accepted = addRulesElevated(stillMissing, log)
  for (const rule of stillMissing) {
    if (ruleMatches(rule)) added.push(rule.name)
    else failed.push(rule.name)
  }
  const ok = failed.length === 0
  return {
    skipped: false,
    ok,
    added,
    failed,
    note: accepted ? 'added-elevated' : 'uac-declined',
  }
}

/** Quick status probe for GUIs/tests: which rules exist and match. */
export function firewallStatus(opts = {}) {
  if (!isWindows()) return { skipped: true, rules: [] }
  const all = firewallRules({
    nodeExe: opts.nodeExe || process.execPath,
    panelPort: opts.panelPort || 3000,
    rtPort: opts.rtPort || 3003,
  })
  return {
    skipped: false,
    rules: all.map((r) => ({ name: r.name, kind: r.kind, present: ruleMatches(r) })),
  }
}
