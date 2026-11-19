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
//   GET  {base}/Report/GetCustPay?customerErpCode=…&code=… (per-customer
//        payments — BOTH documented parameter spellings are accepted; the
//        owner spec §4 names `code`, public TncHoloo clients send
//        `customerErpCode`; same value either way)
//   state.failPay=true → 500 on GetCustPay (exercises the spec-§9 PARTIAL
//        run: healthy sections keep their data, the run is labeled ناقص)
//   401 (JSON) when the Authorization header is missing/unknown — exercising
//        the client's ONE auto re-login.
//
// Task 12 (Nov 2026): by default every list is served inside the EXACT
// business wrapper the LIVE service was observed using (owner field test):
//     Customer -> {"Customer": [ ... ]}    Product -> {"product": [ ... ]}
//     Invoice  -> {"Invoice": [ ... ]}     GetCustPay -> {"report": [ ... ]}
// (the pre-Task-12 mock returned bare arrays - which is exactly why all
// mock tests passed while the live sync imported ZERO records.)
//
// Per-endpoint "modes" in state.json drive the extraction edge cases
// (test-holoo.sh H10 + the Phase 14 sections H11-H16):
//   "modes": { "Customer": "real|bare|raw|nonjson|fail|paginated|capped", ... }
//   real      - the observed wrapped shape (default)
//   bare      - a direct JSON array (legacy tolerance)
//   raw       - serve state.rawCustomer / rawProduct / rawGetCustPay AS-IS
//               (arbitrary envelopes: junk, nested, single-record, empty,
//                duplicates, poison records — fully hand-crafted)
//   nonjson   - an HTML body (invalid-response classification)
//   fail      - HTTP 500 (Phase 14 section isolation: one source fails,
//               the others must still run)
//   paginated - the wrapped shape PLUS pagination metadata
//               ({"<wrapper>":[...], "TotalCount": state.pagTotal (default
//               3x the list), "Page":1, "PageCount":3}) — strong evidence
//               of a possibly-incomplete response (totalCount > received)
//   capped    - the list TRUNCATED to state.cap records (default 2) with
//               "TotalCount" = the FULL list length — the response hit a
//               record-count cap with metadata proving more exists
//
// Phase 14 concurrency fixtures (all in state.json, re-read EVERY request):
//   "slow":  { "Customer": 3000, "GetCustPay": 400, ... }  — artificial
//             per-endpoint delay in ms (makes a manual run last long
//             enough for the scheduler-tick race tests)
//   "failPayFor": ["<erpCode>", ...] — 500 for THOSE customers' GetCustPay
//             only (per-customer failure isolation)
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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

  // Phase 14 — artificial per-endpoint delay (concurrency fixtures)
  const slowFor = (ep) => {
    const ms = Number(state.slow && state.slow[ep])
    return Number.isFinite(ms) && ms > 0 ? ms : 0
  }

  // exact suffix routing (LONGEST first — '/Invoice/Invoice' must not fall
  // into the '/Invoice' handler just because it shares the suffix)
  if (req.method === 'GET' && path.endsWith('/Invoice/Invoice')) return json(404, { error: 'not found (fallback path)' })

  // Task 12 - per-endpoint envelope modes (default: the OBSERVED live
  // wrappers; 'bare'/'raw'/'nonjson'/'fail'/'paginated'/'capped' drive
  // the H10 extraction edge cases + the Phase 14 isolation/pagination ones)
  const serve = (ep, wrappedKey, data) => {
    const m = (state.modes && state.modes[ep]) || 'real'
    if (m === 'nonjson') {
      res.writeHead(200, { 'Content-Type': 'text/html' })
      res.end('<html><body>not json (MOCK)</body></html>')
      return
    }
    if (m === 'raw') return json(200, state['raw' + ep])
    if (m === 'bare') return json(200, data)
    if (m === 'paginated') {
      const total = Number(state.pagTotal) > 0 ? Number(state.pagTotal) : data.length * 3
      return json(200, { [wrappedKey]: data, TotalCount: total, Page: 1, PageCount: 3 })
    }
    if (m === 'capped') {
      const cap = Number(state.cap) > 0 ? Number(state.cap) : Math.min(2, data.length)
      return json(200, { [wrappedKey]: data.slice(0, cap), TotalCount: data.length })
    }
    return json(200, { [wrappedKey]: data }) // 'real' - the observed live shape
  }

  const handle = async () => {
    if (req.method === 'GET' && path.endsWith('/Customer')) {
      await sleep(slowFor('Customer'))
      const m = (state.modes && state.modes.Customer) || 'real'
      if (m === 'fail') return json(500, { error: 'simulated Customer outage (MOCK)' })
      return serve('Customer', 'Customer', state.customers)
    }
    if (req.method === 'GET' && path.endsWith('/Product')) {
      await sleep(slowFor('Product'))
      const m = (state.modes && state.modes.Product) || 'real'
      if (m === 'fail') return json(500, { error: 'simulated Product outage (MOCK)' })
      return serve('Product', 'product', state.products)
    }
    if (req.method === 'GET' && path.endsWith('/Invoice')) {
      await sleep(slowFor('Invoice'))
      const m = (state.modes && state.modes.Invoice) || 'real'
      if (m === 'fail') return json(500, { error: 'simulated Invoice outage (MOCK)' })
      return serve('Invoice', 'Invoice', state.invoices)
    }
    if (req.method === 'GET' && path.endsWith('/Report/GetCustPay')) {
      await sleep(slowFor('GetCustPay'))
      if (state.failPay) return json(500, { error: 'simulated GetCustPay outage (MOCK)' })
      const code = url.searchParams.get('customerErpCode') ?? url.searchParams.get('code') ?? ''
      // Phase 14 — per-customer failure isolation fixture
      if (Array.isArray(state.failPayFor) && state.failPayFor.includes(code)) {
        return json(500, { error: `simulated per-customer outage (MOCK) for ${code}` })
      }
      const all = state.payments[code] ?? []
      return serve('GetCustPay', 'report', all)
    }
    return json(404, { error: `mock has no handler for ${req.method} ${path}` })
  }
  handle().catch(() => json(500, { error: 'mock internal error' }))
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`holoo-mock listening on http://127.0.0.1:${PORT} (MOCK — state: ${stateFile})`)
})
