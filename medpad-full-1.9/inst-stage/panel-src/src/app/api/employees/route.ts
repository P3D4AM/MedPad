import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin, hashPassword } from '@/lib/auth'

export async function GET() {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const rows = await db.employee.findMany({
    include: {
      assignments: { include: { customer: { select: { id: true, name: true } } } },
      _count: { select: { checkins: true } },
    },
    orderBy: { createdAt: 'asc' },
  })
  return NextResponse.json({ ok: true, employees: rows })
}

export async function POST(req: Request) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const body = await req.json().catch(() => null) as {
    username?: string; password?: string; name?: string; phone?: string
  } | null
  if (!body?.username || !body?.password || !body?.name) {
    return NextResponse.json({ ok: false, error: 'اطلاعات ناقص است' }, { status: 400 })
  }
  const exists = await db.employee.findUnique({ where: { username: body.username } })
  if (exists) {
    return NextResponse.json({ ok: false, error: 'این نام کاربری قبلاً ثبت شده' }, { status: 409 })
  }
  const row = await db.employee.create({
    data: {
      username: body.username,
      password: hashPassword(body.password),
      name: body.name,
      phone: body.phone || null,
    },
  })
  return NextResponse.json({ ok: true, employee: row })
}
