/** Fire-and-forget event emit to the realtime service (port 3003) */
export async function emitEvent(event: string, payload: unknown): Promise<void> {
  try {
    const base = process.env.REALTIME_URL || 'http://127.0.0.1:3003'
    await fetch(`${base}/emit`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        token: process.env.SOCKET_TOKEN || 'medpad-dev-token',
        event,
        payload,
      }),
      signal: AbortSignal.timeout(1500),
    })
  } catch {
    // realtime service down — panel still works via polling
  }
}
