import { NextResponse } from 'next/server'
import { createHmac } from 'crypto'
import { isAdmin, socketToken } from '@/lib/auth'

export const dynamic = 'force-dynamic'

/**
 * v1.9.5 — mint a short-lived HMAC token for the dashboard socket handshake.
 * Only authenticated admins get a token; the realtime service (shared
 * SOCKET_TOKEN secret) verifies signature + expiry before accepting the
 * connection. TTL: 10 minutes (watchdog refreshes on reconnect).
 */
export async function GET() {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const payload = { svc: 'medpad-dashboard', exp: Date.now() + 10 * 60 * 1000 }
  const payloadB64 = Buffer.from(JSON.stringify(payload)).toString('base64url')
  const sig = createHmac('sha256', socketToken()).update(payloadB64).digest('base64url')
  return NextResponse.json({ ok: true, token: `${payloadB64}.${sig}`, expiresIn: 600 })
}
