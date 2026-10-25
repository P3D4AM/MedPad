/**
 * MedPad Realtime Service (installer version) v1.9.5 → v1.9.12 — Node.js, plain ESM.
 * socket.io on :3003 + /emit endpoint.
 *
 * v1.9.5 SECURITY FIX — dashboard socket handshake is now authenticated:
 *   The panel mints a short-lived HMAC token via /api/admin/socket-auth
 *   (admin session required) and sends it in socket.handshake.auth.token.
 *   Anonymous / forged connections are rejected before any event is delivered.
 *   (Previously anyone on the LAN could listen to live locations.)
 *
 * v1.9.12: when SOCKET_TOKEN is not set in the environment, the token is
 *   read from ../panel/.env (owner-only file) — so generated launcher
 *   scripts never embed secrets.
 *
 * v1.9.12 (phase 0 hardening):
 *   - /emit body is BOUNDED (64 KB) — an unauthenticated client must not be
 *     able to stream an unbounded request into memory.
 *   - the /emit shared-secret comparison is CONSTANT-TIME (both sides are
 *     SHA-256-hashed first so lengths always match and no early-exit leaks).
 *   - CORS stays permissive ON PURPOSE and is documented here: the dashboard
 *     is reached from dynamic origins (LAN IPs, localhost, or a Cloudflare
 *     tunnel hostname), so origin allow-listing would break real deployments
 *     while adding nothing — ACCESS CONTROL IS TOKEN-BASED, not origin-based:
 *     the socket.io handshake requires a panel-minted short-lived HMAC token,
 *     and /emit requires the shared secret. A wildcard CORS header only
 *     allows the ATTEMPT, which is then rejected by the auth layer.
 */
import { createServer } from 'node:http'
import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import url from 'node:url'
import { Server } from 'socket.io'

const __dirname = path.dirname(url.fileURLToPath(import.meta.url))
const PORT = Number(process.env.REALTIME_PORT || 3003)

/** v1.9.12: shared socket token — env first, then ../panel/.env.
 *  v1.9.12 (audit phase 3): NO production fallback. The public dev token
 *  is only honored with an explicit MEDPAD_DEV=1 opt-in; without any token
 *  the service exits with a clear error (fail secure) instead of silently
 *  accepting a well-known constant that anyone could forge. */
function resolveSocketToken() {
  if (process.env.SOCKET_TOKEN && process.env.SOCKET_TOKEN.trim()) return process.env.SOCKET_TOKEN.trim()
  try {
    const env = readFileSync(path.join(__dirname, '..', 'panel', '.env'), 'utf8')
    const m = env.match(/^SOCKET_TOKEN=\s*([^\r\n]+)/m)
    if (m && m[1].trim()) return m[1].trim()
  } catch { /* no .env — fall through */ }
  if (process.env.MEDPAD_DEV === '1') {
    console.warn('[rt] DEV MODE: using the public dev socket token — NEVER use this in production')
    return 'medpad-dev-token'
  }
  console.error('[rt] FATAL: SOCKET_TOKEN is not set and ../panel/.env has no token.')
  console.error('[rt]        Run the MedPad installer (it generates panel/.env), or start with SOCKET_TOKEN=<secret>.')
  console.error('[rt]        MEDPAD_DEV=1 allows the public dev token for local development only.')
  process.exit(1)
}
const TOKEN = resolveSocketToken()

/** v1.9.12 (phase 0): constant-time comparison for the /emit shared secret.
 *  Both sides are hashed first so the lengths always match and the comparison
 *  never leaks position information. */
function tokenEq(a, b) {
  try {
    const ha = createHash('sha256').update(String(a)).digest()
    const hb = createHash('sha256').update(String(b)).digest()
    return timingSafeEqual(ha, hb)
  } catch {
    return false
  }
}

/** Verify a panel-minted socket token: `<payloadB64url>.<hmacB64url>` (TTL ≤ 15 min) */
function verifySocketToken(tok) {
  try {
    const s = String(tok || '')
    const dot = s.indexOf('.')
    if (dot <= 0 || dot === s.length - 1) return false
    const payloadB64 = s.slice(0, dot)
    const sig = s.slice(dot + 1)
    const expected = createHmac('sha256', TOKEN).update(payloadB64).digest('base64url')
    const a = Buffer.from(sig)
    const b = Buffer.from(expected)
    if (a.length !== b.length || !timingSafeEqual(a, b)) return false
    const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'))
    if (!payload || payload.svc !== 'medpad-dashboard') return false
    if (typeof payload.exp !== 'number' || Date.now() > payload.exp) return false
    return true
  } catch {
    return false
  }
}

const httpServer = createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'content-type')
  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    res.end()
    return
  }

  const url = new URL(req.url || '/', `http://localhost:${PORT}`)

  if (req.method === 'GET' && url.pathname === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, service: 'medpad-realtime', clients: io.engine.clientsCount }))
    return
  }

  if (req.method === 'POST' && url.pathname === '/emit') {
    // v1.9.12 (phase 0): bounded body — reject anything above 64 KB before
    // parsing. A location update is a few hundred bytes; 64 KB is generous.
    // The request is drained (not destroyed) after the 413 so the client
    // receives the clean HTTP response instead of a connection reset.
    const EMIT_BODY_LIMIT = 64 * 1024
    let body = ''
    let oversized = false
    req.on('data', (c) => {
      if (oversized) return // drain after rejection — never buffer
      body += c
      if (body.length > EMIT_BODY_LIMIT) {
        oversized = true
        body = ''
        res.writeHead(413, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: false, error: 'body too large' }))
      }
    })
    req.on('end', () => {
      if (oversized) return
      try {
        const { token, event, payload } = JSON.parse(body || '{}')
        if (!tokenEq(token, TOKEN)) {
          res.writeHead(401, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: 'bad token' }))
          return
        }
        if (!event || typeof event !== 'string') {
          res.writeHead(400, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: 'missing event' }))
          return
        }
        io.emit(event, payload ?? {})
        console.log(`[emit] ${event}`)
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: true }))
      } catch {
        res.writeHead(400, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: false, error: 'bad json' }))
      }
    })
    return
  }

  res.writeHead(404, { 'content-type': 'application/json' })
  res.end(JSON.stringify({ ok: false, error: 'not found' }))
})

const io = new Server(httpServer, {
  path: '/socket.io',
  // Wildcard CORS is INTENTIONAL — see the header comment: origins are
  // dynamic (LAN IP / localhost / tunnel hostname) and every connection
  // must still pass the mandatory token handshake below.
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout: 60000,
  pingInterval: 25000,
})

// ─── v1.9.5: authenticated handshake (mandatory) ───────────────
io.use((socket, next) => {
  const tok = socket.handshake.auth?.token
  if (verifySocketToken(tok)) return next()
  // v1.9.12: distinguish a FORGED/EXPIRED token from no token at all
  if (tok) console.warn(`[rt] rejected FORGED/EXPIRED socket token from ${socket.handshake.address || 'unknown'}`)
  else console.warn(`[rt] rejected unauthenticated socket from ${socket.handshake.address || 'unknown'}`)
  next(new Error('unauthorized'))
})

io.on('connection', (socket) => {
  console.log(`[rt] authenticated panel connected: ${socket.id}`)
  socket.emit('hello', { ok: true, service: 'medpad-realtime', at: new Date().toISOString() })
  socket.on('disconnect', () => console.log(`[rt] disconnected: ${socket.id}`))
})

httpServer.listen(PORT, () => {
  console.log(`✔ MedPad realtime listening on :${PORT} (socket auth: ON)`)
})
