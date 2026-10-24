import { NextResponse } from 'next/server'
import { appEmployee, getConfig } from '@/lib/auth'

/** App boot config — includes panelVersion so app can warn on old panel */
export async function GET(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const panelVersion = await getConfig('panelVersion', '1.9.3')
  return NextResponse.json({
    ok: true,
    panelVersion,
    employee: { id: emp.id, name: emp.name, username: emp.username },
    serverTime: new Date().toISOString(),
  })
}
