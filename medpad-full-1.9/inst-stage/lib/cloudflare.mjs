/**
 * MedPad Cloudflare integration — Named Tunnel automation (v1.9.3 → v1.9.12)
 *
 * Flow: verify token → resolve account (via /accounts or zone) → find zone
 *       → create-or-reuse named tunnel → ingress (hostname→localhost) →
 *       DNS CNAME (proxied) → credentials file.
 *
 * v1.9.12 SAFETY REWRITE (audit phase 5 — "Cloudflare operation safety"):
 *  - DNS changes are PLANNED before anything is modified: the records that
 *    currently exist on the target hostname are listed exactly, and the
 *    intended mutations (create / replace / delete) are returned as a PLAN.
 *  - A plan that would REPLACE or DELETE an existing record is DESTRUCTIVE
 *    and requires explicit confirmation: setupDomainTunnel({confirm:false})
 *    throws error code MEDPAD_CF_CONFIRM_REQUIRED carrying .plan — nothing
 *    DNS-related has been touched at that point (tunnel/ingress steps are
 *    scoped to our own tunnel and are reversible; see notes below).
 *  - Before the first DNS mutation a RESTORABLE BACKUP of every existing
 *    record on the hostname is written to
 *    <backupDir>/dns-backup-<hostname>-<timestamp>.json (full record
 *    objects). restoreDnsBackup() can replay it (recreate deleted records,
 *    restore replaced contents).
 *  - A failure in the middle of applying changes triggers a best-effort
 *    rollback of the changes already applied, and the thrown error carries
 *    .rollbackNote with the exact half-completed state and how to recover.
 *  - The API token is NEVER written to disk and NEVER logged — it lives in
 *    memory for the duration of the call only. Errors never include it.
 *
 * Non-DNS steps & why they are safe to run before the DNS confirmation:
 *  - tunnel create/reuse: scoped to tunnels named `medpad` (or the state's
 *    existing tunnel id) — never touches other tunnels;
 *  - ingress (tunnel configuration PUT): only ever rewrites the ingress of
 *    THAT one tunnel, and a tunnel with no matching DNS record simply
 *    serves nothing.
 */
import { mkdirSync, writeFileSync, readFileSync } from 'fs'

const CF_API_BASE_DEFAULT = 'https://api.cloudflare.com/client/v4'

function apiBase() {
  // lazy — allows tests to point at a mock server
  return process.env.CF_API_BASE || CF_API_BASE_DEFAULT
}

export class CloudflareError extends Error {
  constructor(message, { status, errors, code, rollbackNote, plan } = {}) {
    super(message)
    this.name = 'CloudflareError'
    this.status = status
    this.errors = errors || []
    if (code) this.code = code
    if (rollbackNote) this.rollbackNote = rollbackNote
    if (plan) this.plan = plan
  }
}

async function cfFetch(path, { method = 'GET', token, body } = {}) {
  let res
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': 'application/json',
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(30_000),
    })
  } catch (e) {
    throw new CloudflareError(`Could not reach the Cloudflare API: ${e.message}`, { status: 0 })
  }
  let json = null
  try {
    json = await res.json()
  } catch {
    /* non-json */
  }
  if (!res.ok || (json && json.success === false)) {
    const errs = json?.errors || []
    const msg = errs.map((e) => `${e.code}: ${e.message}`).join(' | ') || `HTTP ${res.status}`
    throw new CloudflareError(msg, { status: res.status, errors: errs })
  }
  return json
}

/** Validate token & return account id + zone for the given domain */
export async function verifyTokenAndZone({ token, domain }) {
  // token permission check
  let tok
  try {
    tok = await cfFetch('/user/tokens/verify', { token })
  } catch (e) {
    if (e.status === 403 || String(e.message).includes('9109')) {
      throw new CloudflareError('Invalid Cloudflare API token (verification failed)')
    }
    throw e
  }
  if (tok.result?.status !== 'active') {
    throw new CloudflareError(`Token status: ${tok.result?.status || 'unknown'}`)
  }

  // account id — token may be account-scoped (user/accounts may 403)
  let accountId = null
  try {
    const acc = await cfFetch('/accounts?per_page=1', { token })
    accountId = acc.result?.[0]?.id || null
  } catch {
    accountId = null
  }

  // zone lookup — exact match first, then suffix match (sub.example.com → example.com)
  const zones = await cfFetch(`/zones?per_page=50&name=${encodeURIComponent(domain)}`, { token })
  let zone = zones.result?.[0] || null
  if (!zone) {
    const parts = domain.split('.')
    for (let i = 1; i < parts.length - 1 && !zone; i++) {
      const candidate = parts.slice(i).join('.')
      const z = await cfFetch(`/zones?per_page=5&name=${encodeURIComponent(candidate)}`, { token })
      zone = z.result?.[0] || null
    }
  }
  if (!zone) {
    throw new CloudflareError(`Domain "${domain}" not found in this Cloudflare account (Zone:Read permission required)`)
  }
  if (!accountId) accountId = zone.account?.id
  if (!accountId) {
    throw new CloudflareError('Cloudflare account id not found')
  }
  return { accountId, zoneId: zone.id, zoneName: zone.name, accountTag: zone.account?.id || accountId }
}

/** Create-or-reuse a named tunnel; returns {id, token, name, created} */
export async function ensureTunnel({ token, accountId, name = 'medpad', existingId }) {
  // reuse by explicit state id
  if (existingId) {
    try {
      const got = await cfFetch(`/accounts/${accountId}/cfd_tunnel/${existingId}`, { token })
      if (got.result?.id) {
        const creds = await tunnelToken(token, accountId, got.result.id)
        return { id: got.result.id, token: creds, name: got.result.name, created: false }
      }
    } catch {
      /* fall through to list/create */
    }
  }
  // reuse by name (prefer cloudflare-managed tunnels)
  const list = await cfFetch(`/accounts/${accountId}/cfd_tunnel?is_deleted=false&per_page=20`, { token })
  const mine = (list.result || []).filter((t) => t.name === name)
  const reusable = mine.find((t) => t.status === 'active' || t.status === 'inactive') || mine[0]
  if (reusable) {
    const creds = await tunnelToken(token, accountId, reusable.id)
    return { id: reusable.id, token: creds, name: reusable.name, created: false }
  }
  const created = await cfFetch(`/accounts/${accountId}/cfd_tunnel`, {
    method: 'POST',
    token,
    body: { name, config_src: 'local' }, // local config file — spec bug fix #5
  })
  const t = created.result
  const creds = await tunnelToken(token, accountId, t.id)
  return { id: t.id, token: creds, name: t.name, created: true }
}

async function tunnelToken(token, accountId, tunnelId) {
  const r = await cfFetch(`/accounts/${accountId}/cfd_tunnel/${tunnelId}/token`, { token })
  return r.result // opaque tunnel token string
}

/** Replace ingress rules of a tunnel (local config source) */
export async function putIngress({ token, accountId, tunnelId, hostname, service }) {
  const config = {
    ingress: [
      { hostname, service, originRequest: { connectTimeout: 30, noTLSVerify: true } },
      { service: 'http_status:404' },
    ],
  }
  await cfFetch(`/accounts/${accountId}/cfd_tunnel/${tunnelId}/configurations`, {
    method: 'PUT',
    token,
    body: config,
  })
  return config
}

// ─── v1.9.12: DNS change planning / backup / confirmation / rollback ───

const DNS_SAFE_TYPES = ['A', 'AAAA', 'CNAME'] // types we consider for replace/delete

/** List the DNS records that exist RIGHT NOW on `hostname` (read-only). */
export async function listHostRecords({ token, zoneId, hostname }) {
  const r = await cfFetch(`/zones/${zoneId}/dns_records?per_page=100&name=${encodeURIComponent(hostname)}`, { token })
  return r.result || []
}

/**
 * Build the DNS change PLAN for pointing `hostname` at the tunnel — without
 * changing anything. The plan lists what EXISTS, what would be CREATED,
 * REPLACED (record id + old content) and DELETED (record id + full record).
 * Records of other types on the same name (TXT/MX/…) are NEVER touched and
 * are listed as `untouched` so the user sees them.
 */
export async function planDnsChanges({ token, zoneId, hostname, tunnelId }) {
  const target = `${tunnelId}.cfargotunnel.com`
  const existing = await listHostRecords({ token, zoneId, hostname })
  const plan = {
    hostname,
    target,
    zoneId,
    existingCount: existing.length,
    correct: [], // already a proxied CNAME to the tunnel — no change needed
    replace: [], // existing CNAME pointing somewhere else → PUT over it
    delete: [], // existing A/AAAA on the name → must go (conflicts)
    create: false, // no CNAME at all → POST one
    untouched: [], // TXT/MX/… on the same name — shown, never modified
  }
  let hasCname = false
  for (const rec of existing) {
    if (rec.type === 'CNAME') {
      hasCname = true
      if (rec.content === target && rec.proxied) plan.correct.push({ id: rec.id, content: rec.content })
      else plan.replace.push({ id: rec.id, content: rec.content, proxied: !!rec.proxied })
    } else if (DNS_SAFE_TYPES.includes(rec.type)) {
      plan.delete.push({ id: rec.id, type: rec.type, content: rec.content })
    } else {
      plan.untouched.push({ id: rec.id, type: rec.type, content: String(rec.content).slice(0, 60) })
    }
  }
  plan.create = !hasCname
  plan.destructive = plan.replace.length > 0 || plan.delete.length > 0
  plan.changes = plan.replace.length + plan.delete.length + (plan.create ? 1 : 0)
  return { plan, existing }
}

/** Human-readable lines for showing a plan in the console / wizard. */
export function formatDnsPlan(plan) {
  const lines = []
  lines.push(`hostname: ${plan.hostname}  ->  CNAME ${plan.target} (proxied)`)
  if (plan.existingCount === 0) lines.push(`  current records on the hostname: none`)
  else lines.push(`  current records on the hostname: ${plan.existingCount}`)
  for (const c of plan.correct) lines.push(`  KEEP     CNAME ${c.id}  (already points at the tunnel)`)
  for (const r of plan.replace) lines.push(`  REPLACE  CNAME ${r.id}  (currently: ${r.content})`)
  for (const d of plan.delete) lines.push(`  DELETE   ${d.type}    ${d.id}  (currently: ${d.content})`)
  if (plan.create) lines.push(`  CREATE   CNAME ${plan.hostname} -> ${plan.target}`)
  for (const u of plan.untouched) lines.push(`  (left untouched: ${u.type} ${u.id} - ${u.content})`)
  if (plan.changes === 0) lines.push(`  -> NOTHING would change; the DNS is already correct.`)
  return lines
}

/**
 * Write a restorable backup of the records currently on the hostname.
 * Returns the backup file path (null when there was nothing to back up).
 */
export function backupRecords(backupDir, hostname, records) {
  if (!backupDir || !records || records.length === 0) return null
  mkdirSync(backupDir, { recursive: true })
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  const safeHost = hostname.replace(/[^a-z0-9.-]/gi, '_')
  const file = `${backupDir}/dns-backup-${safeHost}-${stamp}.json`
  writeFileSync(file, JSON.stringify({
    what: 'MedPad DNS backup — records on the hostname BEFORE the installer changed them',
    hostname,
    createdAt: new Date().toISOString(),
    howToRestore: 'Re-create each record with POST /zones/<zoneId>/dns_records using the saved `record` object (type/name/content/proxied/ttl/priority). Or re-run the MedPad installer and pick a different subdomain.',
    zoneId: records[0]?.zone_id || '',
    records: records.map((r) => ({ id: r.id, type: r.type, name: r.name, content: r.content, proxied: !!r.proxied, ttl: r.ttl || 1, priority: r.priority || undefined, record: r })),
  }, null, 2))
  return file
}

/** Restore a backup written by backupRecords() — used by tests & recovery. */
export async function restoreDnsBackup({ token, zoneId, backupFile }) {
  const data = JSON.parse(readFileSync(backupFile, 'utf8'))
  const restored = []
  for (const r of data.records || []) {
    await cfFetch(`/zones/${zoneId}/dns_records`, {
      method: 'POST',
      token,
      body: { type: r.type, name: r.name, content: r.content, proxied: r.proxied, ttl: r.ttl, priority: r.priority },
    })
    restored.push(`${r.type} ${r.name} -> ${r.content}`)
  }
  return restored
}

/**
 * Apply a plan with rollback-on-failure. Every change that succeeded is
 * reverted (best effort) when a later step fails; the thrown error carries a
 * rollbackNote describing the half-completed state.
 */
export async function applyDnsPlan({ token, plan, existingRecords, backupFile }) {
  const applied = [] // {kind:'replace'|'delete'|'create', ...} — for rollback
  const tryRollback = async (failedStep) => {
    const undone = []
    for (const a of applied.reverse()) {
      try {
        if (a.kind === 'delete' && a.record) {
          await cfFetch(`/zones/${plan.zoneId}/dns_records`, {
            method: 'POST', token,
            body: { type: a.record.type, name: a.record.name, content: a.record.content, proxied: !!a.record.proxied, ttl: a.record.ttl || 1 },
          })
          undone.push(`re-created ${a.record.type} ${a.record.name} -> ${a.record.content}`)
        } else if (a.kind === 'replace' && a.record) {
          await cfFetch(`/zones/${plan.zoneId}/dns_records/${a.record.id}`, {
            method: 'PUT', token,
            body: { type: a.record.type, name: a.record.name, content: a.record.content, proxied: !!a.record.proxied, ttl: a.record.ttl || 1 },
          })
          undone.push(`restored ${a.record.type} ${a.record.name} -> ${a.record.content}`)
        } else if (a.kind === 'create') {
          await cfFetch(`/zones/${plan.zoneId}/dns_records/${a.createdId}`, { method: 'DELETE', token })
          undone.push(`removed the CNAME that was created`)
        }
      } catch { /* rollback is best-effort */ }
    }
    const state = [`step that failed: ${failedStep}`]
    state.push(applied.length === 0
      ? 'no DNS change had been applied yet - the zone is untouched.'
      : `${applied.length} change(s) had been applied and were rolled back (${undone.join('; ') || 'rollback incomplete - see the backup file'}).`)
    if (backupFile) state.push(`a backup of the ORIGINAL records is at: ${backupFile} (restore with the records' JSON inside).`)
    return state.join('\n')
  }

  for (const r of plan.replace) {
    try {
      await cfFetch(`/zones/${plan.zoneId}/dns_records/${r.id}`, {
        method: 'PUT', token,
        body: { type: 'CNAME', name: plan.hostname, content: plan.target, proxied: true, ttl: 1 },
      })
      applied.push({ kind: 'replace', record: (existingRecords || []).find((e) => e.id === r.id) })
    } catch (e) {
      throw new CloudflareError(`DNS replace failed: ${e.message}`, { rollbackNote: await tryRollback(`replace CNAME ${r.id}`) })
    }
  }
  for (const d of plan.delete) {
    try {
      await cfFetch(`/zones/${plan.zoneId}/dns_records/${d.id}`, { method: 'DELETE', token })
      applied.push({ kind: 'delete', record: (existingRecords || []).find((e) => e.id === d.id) })
    } catch (e) {
      throw new CloudflareError(`DNS delete failed: ${e.message}`, { rollbackNote: await tryRollback(`delete ${d.type} ${d.id}`) })
    }
  }
  if (plan.create) {
    try {
      const r = await cfFetch(`/zones/${plan.zoneId}/dns_records`, {
        method: 'POST', token,
        body: { type: 'CNAME', name: plan.hostname, content: plan.target, proxied: true, ttl: 1 },
      })
      applied.push({ kind: 'create', createdId: r.result?.id })
    } catch (e) {
      throw new CloudflareError(`DNS create failed: ${e.message}`, { rollbackNote: await tryRollback(`create CNAME ${plan.hostname}`) })
    }
  }
  return { applied: applied.length }
}

/**
 * Full setup — used by installer & CLI.
 * v1.9.12: pass { backupDir, confirm } — a destructive DNS plan without
 * confirm:true throws MEDPAD_CF_CONFIRM_REQUIRED (with .plan) and no DNS
 * record has been modified at that point. Returns everything the tunnel needs.
 */
export async function setupDomainTunnel({ token, domain, subdomain = 'panel', tunnelName = 'medpad', state = {}, backupDir = null, confirm = false }) {
  const { accountId, zoneId, zoneName, accountTag } = await verifyTokenAndZone({ token, domain })
  const hostname = `${subdomain}.${domain}`
  if (subdomain.includes('.') || domain.includes('/') || !zoneName) {
    throw new CloudflareError('Invalid domain or subdomain')
  }
  if (!hostname.endsWith(zoneName)) {
    throw new CloudflareError(`Subdomain must be under "${zoneName}"`)
  }

  const tunnel = await ensureTunnel({
    token,
    accountId,
    name: tunnelName,
    existingId: state.tunnelId,
  })
  await putIngress({
    token,
    accountId,
    tunnelId: tunnel.id,
    hostname,
    service: state.service || 'http://localhost:3000',
  })

  // ── v1.9.12: plan → confirm → backup → apply (with rollback) ──
  const { plan, existing } = await planDnsChanges({ token, zoneId, hostname, tunnelId: tunnel.id })
  if (plan.destructive && !confirm) {
    const e = new CloudflareError('DNS_CONFIRM_REQUIRED')
    e.code = 'MEDPAD_CF_CONFIRM_REQUIRED'
    e.plan = plan
    throw e
  }
  let backupFile = null
  if (plan.changes > 0) backupFile = backupRecords(backupDir, hostname, existing)
  let dnsApplied = { applied: 0 }
  if (plan.changes > 0) {
    dnsApplied = await applyDnsPlan({ token, plan, existingRecords: existing, backupFile })
  }

  return {
    tunnelId: tunnel.id,
    tunnelToken: tunnel.token,
    tunnelName: tunnel.name,
    tunnelCreated: tunnel.created,
    hostname,
    zoneName,
    accountId,
    accountTag, // credentials file AccountTag (spec bug fix #2)
    dnsCreated: plan.create,
    dnsPlan: formatDnsPlan(plan).join(' | '),
    dnsBackupFile: backupFile,
    dnsApplied: dnsApplied.applied,
  }
}
