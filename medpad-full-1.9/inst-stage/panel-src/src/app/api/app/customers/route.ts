import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'

/** App customers sync — returns ALL active customers (app filters by assignment locally)
 *  plus the employee's assignments. LWW client compares updatedAt. */
export async function GET(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const url = new URL(req.url)
  const since = url.searchParams.get('since') // incremental sync

  const where: Record<string, unknown> = {}
  if (since) where.updatedAt = { gt: new Date(since) }

  const [customers, assignments] = await Promise.all([
    db.customer.findMany({ where }),
    db.assignment.findMany({
      where: { employeeId: emp.id },
      select: { customerId: true },
    }),
  ])
  return NextResponse.json({
    ok: true,
    customers,
    assignedIds: assignments.map((a) => a.customerId),
    serverTime: new Date().toISOString(),
  })
}
