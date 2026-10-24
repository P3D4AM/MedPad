import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'

/** POST — mark notifications as read (all or ids[]) */
export async function POST(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const { ids } = await req.json().catch(() => ({ ids: null })) as { ids?: string[] | null }
  const data = { readAt: new Date() }
  if (Array.isArray(ids) && ids.length > 0) {
    await db.appNotification.updateMany({ where: { id: { in: ids }, employeeId: emp.id }, data })
  } else {
    await db.appNotification.updateMany({ where: { employeeId: emp.id, readAt: null }, data })
  }
  return NextResponse.json({ ok: true })
}
