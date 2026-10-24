import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin } from '@/lib/auth'

export async function GET() {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const [employees, customers, openVisits] = await Promise.all([
    db.employee.count({ where: { active: true } }),
    db.customer.count(),
    db.visit.count({ where: { open: true } }),
  ])
  const startOfDay = new Date()
  startOfDay.setHours(0, 0, 0, 0)
  const todayCheckins = await db.checkin.count({
    where: { kind: 'checkin', createdAt: { gte: startOfDay } },
  })
  const last7 = new Date(Date.now() - 7 * 24 * 3600 * 1000)
  const weekCheckins = await db.checkin.count({
    where: { kind: 'checkin', createdAt: { gte: last7 } },
  })
  return NextResponse.json({
    ok: true,
    employees,
    customers,
    openVisits,
    todayCheckins,
    weekCheckins,
  })
}
