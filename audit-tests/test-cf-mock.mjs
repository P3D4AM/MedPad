#!/usr/bin/env node
/**
 * test-cf-mock.mjs — Phase 5 verification: the v1.9.12 cloudflare.mjs
 * confirm/backup/rollback flow against a local mock Cloudflare API.
 * Usage: node test-cf-mock.mjs <pkgDir>
 * Prints CF-MOCK-ALL-PASS when every check passes.
 */
import http from 'node:http'
import { mkdirSync, rmSync, readFileSync } from 'node:fs'
import path from 'node:path'

const PKG = process.argv[2] || '/home/z/my-project/MedPad/medpad-full-1.9/medpad-offline-1.9.12'
const cf = await import(path.join(PKG, 'lib', 'cloudflare.mjs'))

let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { console.log(`[PASS] ${name}`); pass++ } else { console.log(`[FAIL] ${name}`); fail++ } }

// ── mock state ─────────────────────────────────────────────────────
const db = {
  records: {
    'panel.example.com': [
      { id: 'rec-A-1', type: 'A', name: 'panel.example.com', content: '203.0.113.9', proxied: false, ttl: 1, zone_id: 'zone-1' },
      { id: 'rec-TXT-1', type: 'TXT', name: 'panel.example.com', content: 'v=spf1 -all', proxied: false, ttl: 1, zone_id: 'zone-1' },
    ],
  },
  tunnels: [],
  ingress: {},
  nextId: 100,
}
let requestLog = []
const requestLogRef = () => requestLog
const json = (res, code, body) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)) }

const srv = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x')
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    const data = body ? JSON.parse(body) : {}
    requestLog.push({ m: req.method, p: u.pathname, data })
    const auth = req.headers.authorization || ''
    if (auth !== 'Bearer tok-123') return json(res, 403, { success: false, errors: [{ code: 9109, message: 'bad token' }] })

    if (u.pathname === '/user/tokens/verify') return json(res, 200, { success: true, result: { status: 'active' } })
    if (u.pathname === '/accounts') return json(res, 200, { success: true, result: [{ id: 'acc-1' }] })
    if (u.pathname === '/zones') return json(res, 200, { success: true, result: [{ id: 'zone-1', name: 'example.com', account: { id: 'acc-1' } }] })
    if (u.pathname.startsWith('/accounts/acc-1/cfd_tunnel') && req.method === 'GET') {
      return json(res, 200, { success: true, result: db.tunnels })
    }
    if (u.pathname === '/accounts/acc-1/cfd_tunnel' && req.method === 'POST') {
      const t = { id: `tun-${db.nextId++}`, name: data.name, status: 'active' }
      db.tunnels.push(t)
      return json(res, 200, { success: true, result: t })
    }
    if (u.pathname.endsWith('/token') && req.method === 'GET') {
      return json(res, 200, { success: true, result: `tunnel-token-for-${u.pathname.split('/')[4]}` })
    }
    if (u.pathname.endsWith('/configurations') && req.method === 'PUT') {
      db.ingress[u.pathname.split('/')[4]] = data
      return json(res, 200, { success: true, result: {} })
    }
    if (u.pathname === '/zones/zone-1/dns_records' && req.method === 'GET') {
      return json(res, 200, { success: true, result: db.records['panel.example.com'] || [] })
    }
    if (u.pathname === '/zones/zone-1/dns_records' && req.method === 'POST') {
      const r = { id: `rec-${db.nextId++}`, ...data, zone_id: 'zone-1' }
      db.records['panel.example.com'].push(r)
      return json(res, 200, { success: true, result: r })
    }
    if (u.pathname.startsWith('/zones/zone-1/dns_records/') && req.method === 'PUT') {
      const id = u.pathname.split('/').pop()
      const r = db.records['panel.example.com'].find((x) => x.id === id)
      if (!r) return json(res, 404, { success: false, errors: [{ code: 0, message: 'not found' }] })
      Object.assign(r, data)
      return json(res, 200, { success: true, result: r })
    }
    if (u.pathname.startsWith('/zones/zone-1/dns_records/') && req.method === 'DELETE') {
      const id = u.pathname.split('/').pop()
      db.records['panel.example.com'] = db.records['panel.example.com'].filter((x) => x.id !== id)
      return json(res, 200, { success: true, result: { id } })
    }
    json(res, 404, { success: false, errors: [{ code: 0, message: 'no route' }] })
  })
})

await new Promise((r) => srv.listen(0, '127.0.0.1', r))
process.env.CF_API_BASE = `http://127.0.0.1:${srv.address().port}`

const backupDir = '/tmp/t1912/cf-backup'
rmSync(backupDir, { recursive: true, force: true })
mkdirSync(backupDir, { recursive: true })

// ── 1) destructive plan without confirm -> MEDPAD_CF_CONFIRM_REQUIRED, DNS untouched
let confirmErr = null
try {
  await cf.setupDomainTunnel({ token: 'tok-123', domain: 'example.com', subdomain: 'panel', state: {}, backupDir, confirm: false })
  ok('confirm required error thrown', false)
} catch (e) { confirmErr = e }
ok('error code MEDPAD_CF_CONFIRM_REQUIRED', confirmErr?.code === 'MEDPAD_CF_CONFIRM_REQUIRED')
ok('plan lists the A record for DELETE', confirmErr?.plan?.delete?.some((d) => d.id === 'rec-A-1'))
ok('plan lists TXT as untouched', confirmErr?.plan?.untouched?.some((d) => d.id === 'rec-TXT-1'))
ok('no DNS mutation happened before confirm', !requestLog.some((r) => r.p.includes('dns_records') && (r.m === 'PUT' || r.m === 'DELETE' || r.m === 'POST')))
ok('plan formatted for console', typeof cf.formatDnsPlan(confirmErr.plan)[0] === 'string' && cf.formatDnsPlan(confirmErr.plan).length > 3)

// ── 2) confirmed run -> backup written, A deleted, CNAME created
// NOTE: the tunnel was already created during the UNCONFIRMED attempt of
// step 1 — tunnel create/reuse is a reversible non-DNS step and runs before
// the confirm gate BY DESIGN; so this run REUSES it (tunnelCreated=false).
const res = await cf.setupDomainTunnel({ token: 'tok-123', domain: 'example.com', subdomain: 'panel', state: {}, backupDir, confirm: true })
ok('hostname returned', res.hostname === 'panel.example.com')
ok('tunnel id present (reused from the unconfirmed attempt)', !!res.tunnelId)
ok('dns backup file written', !!res.dnsBackupFile)
const recs = db.records['panel.example.com']
ok('A record deleted', !recs.some((r) => r.id === 'rec-A-1'))
ok('TXT record untouched', recs.some((r) => r.id === 'rec-TXT-1'))
ok('proxied CNAME to tunnel created', recs.some((r) => r.type === 'CNAME' && r.content === `${res.tunnelId}.cfargotunnel.com` && r.proxied === true))
ok('backup contains the original A record', JSON.parse(readFileSync(res.dnsBackupFile, 'utf8')).records.some((r) => r.id === 'rec-A-1' && r.type === 'A'))

// ── 3) restoreDnsBackup can replay the backup
const restored = await cf.restoreDnsBackup({ token: 'tok-123', zoneId: 'zone-1', backupFile: res.dnsBackupFile })
ok('restoreDnsBackup re-created records', restored.length >= 1)

// ── 4) idempotent run with correct DNS: nothing left to change
// (remove the A record restored by step 3 so the DNS is already correct;
//  the run must then succeed WITHOUT any confirm and change nothing)
db.records['panel.example.com'] = db.records['panel.example.com'].filter((r) => r.type !== 'A')
requestLog = []
const res2 = await cf.setupDomainTunnel({ token: 'tok-123', domain: 'example.com', subdomain: 'panel', state: { tunnelId: res.tunnelId }, backupDir, confirm: false })
ok('second run reuses the tunnel', res2.tunnelCreated === false && res2.tunnelId === res.tunnelId)
ok('no DNS mutations on an already-correct zone', !requestLog.some((r) => r.p.includes('dns_records') && (r.m === 'PUT' || r.m === 'DELETE' || r.m === 'POST')))
ok('no backup written when nothing changes', res2.dnsBackupFile === null && res2.dnsApplied === 0)

// ── 4b) destructive again? explicit confirm gate re-arms after a restore
db.records['panel.example.com'].push({ id: 'rec-A-2', type: 'A', name: 'panel.example.com', content: '203.0.113.10', proxied: false, ttl: 1, zone_id: 'zone-1' })
let gated = null
try { await cf.setupDomainTunnel({ token: 'tok-123', domain: 'example.com', subdomain: 'panel', state: { tunnelId: res.tunnelId }, backupDir, confirm: false }) } catch (e) { gated = e }
ok('restored A record re-triggers the confirm gate', gated?.code === 'MEDPAD_CF_CONFIRM_REQUIRED')

// ── 5) token never logged/echoed: check request log only saw the Bearer header
ok('mock never received token outside the auth header', !requestLog.some((r) => JSON.stringify(r.data).includes('tok-123')))

srv.close()
console.log(fail === 0 ? 'CF-MOCK-ALL-PASS' : `CF-MOCK-FAILURES:${fail}`)
process.exit(fail === 0 ? 0 : 1)
