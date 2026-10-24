/**
 * MedPad Cloudflare integration — Named Tunnel automation (v1.9.3)
 *
 * Flow: verify token → resolve account (via /accounts or zone) → find zone
 *       → create-or-reuse named tunnel → ingress (hostname→localhost) →
 *       DNS CNAME (proxied, replaces conflicting records) → credentials file.
 *
 * The API token is NEVER written to disk. Only tunnel credentials are stored.
 */

const CF_API_BASE_DEFAULT = 'https://api.cloudflare.com/client/v4'

function apiBase() {
  // lazy — allows tests to point at a mock server
  return process.env.CF_API_BASE || CF_API_BASE_DEFAULT
}

export class CloudflareError extends Error {
  constructor(message, { status, errors } = {}) {
    super(message)
    this.name = 'CloudflareError'
    this.status = status
    this.errors = errors || []
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

/** Ensure proxied CNAME <subdomain> → <tunnelId>.cfargotunnel.com (delete conflicting A/AAAA) */
export async function ensureDnsCname({ token, zoneId, hostname, tunnelId }) {
  const recName = hostname
  const target = `${tunnelId}.cfargotunnel.com`
  const existing = await cfFetch(`/zones/${zoneId}/dns_records?per_page=100&name=${encodeURIComponent(recName)}`, { token })
  let created = false
  for (const rec of existing.result || []) {
    if (rec.type === 'CNAME') {
      if (rec.content === target && rec.proxied) {
        // already correct
      } else {
        await cfFetch(`/zones/${zoneId}/dns_records/${rec.id}`, { method: 'PUT', token, body: { type: 'CNAME', name: recName, content: target, proxied: true, ttl: 1 } })
      }
      continue
    }
    // conflicting A / AAAA / TXT etc → delete (spec bug fix #6)
    if (['A', 'AAAA'].includes(rec.type)) {
      await cfFetch(`/zones/${zoneId}/dns_records/${rec.id}`, { method: 'DELETE', token })
    }
  }
  if (!existing.result?.some((r) => r.type === 'CNAME')) {
    await cfFetch(`/zones/${zoneId}/dns_records`, {
      method: 'POST',
      token,
      body: { type: 'CNAME', name: recName, content: target, proxied: true, ttl: 1 },
    })
    created = true
  }
  return { created, target }
}

/**
 * Full setup — used by installer & CLI.
 * Returns everything the tunnel service needs to run.
 */
export async function setupDomainTunnel({ token, domain, subdomain = 'panel', tunnelName = 'medpad', state = {} }) {
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
  const dns = await ensureDnsCname({ token, zoneId, hostname, tunnelId: tunnel.id })

  return {
    tunnelId: tunnel.id,
    tunnelToken: tunnel.token,
    tunnelName: tunnel.name,
    tunnelCreated: tunnel.created,
    hostname,
    zoneName,
    accountId,
    accountTag, // credentials file AccountTag (spec bug fix #2)
    dnsCreated: dns.created,
  }
}
