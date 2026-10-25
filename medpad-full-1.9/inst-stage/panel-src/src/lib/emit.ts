import { socketToken } from '@/lib/auth'

/** Fire-and-forget event emit to the realtime service (port 3003).
 *  v1.9.12 (audit phase 3): no dev-token fallback — a missing SOCKET_TOKEN
 *  is logged once and the emit is skipped (the panel keeps working via
 *  polling; the misconfiguration is visible in the server log). */
let warnedNoToken = false
export async function emitEvent(event: string, payload: unknown): Promise<void> {
  let token: string
  try {
    token = socketToken()
  } catch (e) {
    if (!warnedNoToken) {
      warnedNoToken = true
      console.error(`[medpad] emit skipped (${(e as Error).message})`)
    }
    return
  }
  try {
    const base = process.env.REALTIME_URL || 'http://127.0.0.1:3003'
    await fetch(`${base}/emit`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        token,
        event,
        payload,
      }),
      signal: AbortSignal.timeout(1500),
    })
  } catch {
    // realtime service down — panel still works via polling
  }
}
