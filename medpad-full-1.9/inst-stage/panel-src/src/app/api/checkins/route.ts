import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin } from '@/lib/auth'

export async function GET(req: Request) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const url = new URL(req.url)
  const kind = url.searchParams.get('kind') // checkin | checkout | all
  const employeeId = url.searchParams.get('employeeId')
  const limit = Math.min(parseInt(url.searchParams.get('limit') || '200', 10) || 200, 500)

  const where: Record<string, unknown> = {}
  if (kind === 'checkin' || kind === 'checkout') where.kind = kind
  if (employeeId) where.employeeId = employeeId

  const rows = await db.checkin.findMany({
    where,
    include: {
      employee: { select: { id: true, name: true, username: true } },
      customer: { select: { id: true, name: true } },
    },
    orderBy: { createdAt: 'desc' },
    take: limit,
  })
  return NextResponse.json({ ok: true, checkins: rows })
}
