import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin } from '@/lib/auth'
import { emitEvent } from '@/lib/emit'

export async function GET() {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const rows = await db.assignment.findMany({
    include: {
      employee: { select: { id: true, name: true, username: true } },
      customer: { select: { id: true, name: true } },
    },
    orderBy: { createdAt: 'desc' },
  })
  return NextResponse.json({ ok: true, assignments: rows })
}

/** POST — assign customer to employee (+ app notification, panel realtime toast) */
export async function POST(req: Request) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const { employeeId, customerId } = await req.json().catch(() => ({})) as {
    employeeId?: string; customerId?: string
  }
  if (!employeeId || !customerId) {
    return NextResponse.json({ ok: false, error: 'اطلاعات ناقص' }, { status: 400 })
  }
  const [emp, cus] = await Promise.all([
    db.employee.findUnique({ where: { id: employeeId } }),
    db.customer.findUnique({ where: { id: customerId } }),
  ])
  if (!emp || !cus) {
    return NextResponse.json({ ok: false, error: 'کارمند یا مشتری پیدا نشد' }, { status: 404 })
  }
  const exists = await db.assignment.findFirst({ where: { employeeId, customerId } })
  if (exists) {
    return NextResponse.json({ ok: false, error: 'این انتساب از قبل وجود دارد' }, { status: 409 })
  }
  const row = await db.assignment.create({ data: { employeeId, customerId } })

  // in-app notification + realtime panel toast
  const notif = await db.appNotification.create({
    data: {
      employeeId,
      title: 'مشتری جدید اختصاص یافت',
      body: `«${cus.name}» توسط مدیر به شما اختصاص داده شد.`,
      kind: 'assignment',
      customerId,
    },
  })
  await emitEvent('notification', {
    id: notif.id,
    employeeId,
    employeeName: emp.name,
    title: notif.title,
    body: notif.body,
  })
  return NextResponse.json({ ok: true, assignment: row })
}

/** DELETE — remove assignment (?employeeId=&customerId=) */
export async function DELETE(req: Request) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const url = new URL(req.url)
  const employeeId = url.searchParams.get('employeeId')
  const customerId = url.searchParams.get('customerId')
  if (!employeeId || !customerId) {
    return NextResponse.json({ ok: false, error: 'اطلاعات ناقص' }, { status: 400 })
  }
  await db.assignment.deleteMany({ where: { employeeId, customerId } })
  return NextResponse.json({ ok: true })
}
