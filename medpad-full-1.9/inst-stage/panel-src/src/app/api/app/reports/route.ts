import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'

/**
 * App "گزارش‌های من" — own check-ins with voice URLs.
 * Optional from/to (ISO date) for the Jalali calendar day filter.
 */
export async function GET(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const url = new URL(req.url)
  const from = url.searchParams.get('from')
  const to = url.searchParams.get('to')

  const where: Record<string, unknown> = { employeeId: emp.id }
  if (from || to) {
    where.createdAt = {
      ...(from ? { gte: new Date(from) } : {}),
      ...(to ? { lte: new Date(to) } : {}),
    }
  }
  const rows = await db.checkin.findMany({
    where,
    include: { customer: { select: { id: true, name: true } } },
    orderBy: { createdAt: 'desc' },
    take: 300,
  })
  const withVoice = rows.map((r) => ({
    ...r,
    voiceUrl: r.voicePath ? `/api/media/${r.voicePath}` : null,
  }))
  return NextResponse.json({ ok: true, reports: withVoice })
}
