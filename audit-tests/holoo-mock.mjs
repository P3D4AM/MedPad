#!/usr/bin/env node
// holoo-mock.mjs — an EXPLICIT local mock of the Holoo TncHoloo web service
// for audit-tests/test-holoo.sh. NEVER a substitute for the real service:
// every response is hand-crafted and the suite labels all of this as MOCK.
//
//   usage: node holoo-mock.mjs <port> <state.json> <requests.log>
//
// Wire contract (per the owner's integration instruction, mirroring the
// real TncHoloo API):
//   POST {base}/Login  body {"userinfo":{"username","userpass(base64)","dbname"}}
//        → {"Login":{"State":"True","Token":"Bearer <tok>","ErrorCode":0,"Error":""}}
//   GET  {base}/Customer | /Product | /Invoice          (Authorization: Bearer <tok>)
//   GET  {base}/Report/GetCustPay?customerErpCode=…     (per-customer payments)
//   401 (JSON) when the Authorization header is missing/unknown — exercising
//   the client's ONE auto re-login.
//
// The DATA is re-read from <state.json> on EVERY request so the test can
// mutate the "Holoo side" between syncs (change detection + no-delete).
// Every request line is appended to <requests.log> (auth presence, the
// BASE64 password as received — the test asserts the PLAIN password never
// appears anywhere).

import http from 'node:http'
import { readFileSync, appendFileSync } from 'node:fs'

const [, , portArg, stateFile, logFile] = process.argv
const PORT = Number(portArg) || 8399
if (!stateFile || !logFile) {
  console.error('usage: node holoo-mock.mjs <port> <state.json> <requests.log>')
  process.exit(1)
}

const MOCK_USER = 'holoadmin'
const MOCK_DB = 'HolooTest'
const MOCK_PASS_B64 = Buffer.from('HolooPass123!', 'utf8').toString('base64')

/** issued tokens (any of them is accepted until the process exits) */
const issued = new Set()
let loginCount = 0

const readState = () => JSON.parse(readFileSync(stateFile, 'utf8'))
const log = (line) => appendFileSync(logFile, `${new Date().toISOString()} ${line}\n`)

const server = http.createServer((req, res) => {
  const json = (code, obj) => {
    const body = JSON.stringify(obj)
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(body)
  }
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname.replace(/\/+$/, '')
  const auth = req.headers.authorization || ''

  // POST /Login (also tolerates /TncHoloo/api/Login)
  if (req.method === 'POST' && /\/Login$/.test(path)) {
    let raw = ''
    req.on('data', (c) => (raw += c))
    req.on('end', () => {
      let user = ''
      let pass = ''
      let db = ''
      try {
        const body = JSON.parse(raw)
        const ui = body.userinfo || {}
        user = String(ui.username ?? '')
        pass = String(ui.userpass ?? '')
        db = String(ui.dbname ?? '')
      } catch {
        /* fall through with empties */
      }
      log(`LOGIN user=${user} db=${db} pass=${pass} (base64 as received)`)
      const ok = user === MOCK_USER && db === MOCK_DB && pass === MOCK_PASS_B64
      if (!ok) {
        loginCount++
        json(200, { Login: { State: 'False', Token: '', ErrorCode: 1, Error: 'نام کاربری یا رمز یا پایگاه داده نادرست است' } })
        return
      }
      loginCount++
      const token = `mock-token-${loginCount}`
      issued.add(`Bearer ${token}`)
      json(200, { Login: { State: 'True', Token: `Bearer ${token}`, ErrorCode: 0, Error: '' } })
    })
    return
  }

  // everything else requires a valid token
  log(`${req.method} ${path}${url.search} auth=${auth ? 'yes' : 'NO'}`)
  if (!issued.has(auth)) {
    json(401, { error: 'unauthorized' })
    return
  }

  let state
  try {
    state = readState()
  } catch (e) {
    json(500, { error: `mock state unreadable: ${e.message}` })
    return
  }

  // exact suffix routing (LONGEST first — '/Invoice/Invoice' must not fall
  // into the '/Invoice' handler just because it shares the suffix)
  if (req.method === 'GET' && path.endsWith('/Invoice/Invoice')) return json(404, { error: 'not found (fallback path)' })
  if (req.method === 'GET' && path.endsWith('/Customer')) return json(200, state.customers)
  if (req.method === 'GET' && path.endsWith('/Product')) return json(200, state.products)
  if (req.method === 'GET' && path.endsWith('/Invoice')) return json(200, state.invoices)
  if (req.method === 'GET' && path.endsWith('/Report/GetCustPay')) {
    const code = url.searchParams.get('customerErpCode') ?? ''
    const all = state.payments[code] ?? []
    return json(200, all)
  }
  json(404, { error: `mock has no handler for ${req.method} ${path}` })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`holoo-mock listening on http://127.0.0.1:${PORT} (MOCK — state: ${stateFile})`)
})
