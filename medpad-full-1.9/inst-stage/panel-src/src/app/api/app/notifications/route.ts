import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'

/** App notification poll — returns unread + recent (last 50) */
export async function GET(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const rows = await db.appNotification.findMany({
    where: { employeeId: emp.id },
    orderBy: { createdAt: 'desc' },
    take: 50,
  })
  const unread = rows.filter((r) => !r.readAt).length
  return NextResponse.json({ ok: true, notifications: rows, unread })
}

/** POST — create notification (admin broadcast or system) */
export async function POST(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const { title, body } = await req.json().catch(() => ({ title: '', body: '' }))
  if (!title) return NextResponse.json({ ok: false, error: 'عنوان لازم است' }, { status: 400 })
  const row = await db.appNotification.create({
    data: { employeeId: emp.id, title, body: body || '', kind: 'info' },
  })
  return NextResponse.json({ ok: true, notification: row })
}
