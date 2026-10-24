/**
 * MedPad Realtime Service (installer version) v1.9.5 — Node.js, plain ESM.
 * socket.io on :3003 + /emit endpoint.
 *
 * v1.9.5 SECURITY FIX — dashboard socket handshake is now authenticated:
 *   The panel mints a short-lived HMAC token via /api/admin/socket-auth
 *   (admin session required) and sends it in socket.handshake.auth.token.
 *   Anonymous / forged connections are rejected before any event is delivered.
 *   (Previously anyone on the LAN could listen to live locations.)
 */
import { createServer } from 'node:http'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { Server } from 'socket.io'

const PORT = Number(process.env.REALTIME_PORT || 3003)
const TOKEN = process.env.SOCKET_TOKEN || 'medpad-dev-token'

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
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      try {
        const { token, event, payload } = JSON.parse(body || '{}')
        if (token !== TOKEN) {
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
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout: 60000,
  pingInterval: 25000,
})

// ─── v1.9.5: authenticated handshake (mandatory) ───────────────
io.use((socket, next) => {
  const tok = socket.handshake.auth?.token
  if (verifySocketToken(tok)) return next()
  console.warn(`[rt] rejected unauthenticated socket from ${socket.handshake.address || 'unknown'}`)
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
