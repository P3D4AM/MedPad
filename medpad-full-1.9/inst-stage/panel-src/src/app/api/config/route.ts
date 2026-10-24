import { NextResponse } from 'next/server'
import { getConfig } from '@/lib/auth'

export async function GET() {
  const panelVersion = await getConfig('panelVersion', '1.9.3')
  const apkVersion = await getConfig('apkVersion', '1.9.1')
  // socket connection mode:
  //  - "gateway": same-origin path with XTransformPort (sandbox/Caddy)
  //  - "direct":  ws(s)://host:3003 (installer / production)
  const socketMode = process.env.MEDPAD_SOCKET_MODE === 'direct' ? 'direct' : 'gateway'
  return NextResponse.json({ ok: true, panelVersion, apkVersion, socketMode })
}
