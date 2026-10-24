import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin } from '@/lib/auth'
import { isValidLatLng } from '@/lib/geo'

export async function GET() {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const rows = await db.customer.findMany({
    include: {
      assignments: { include: { employee: { select: { id: true, name: true, username: true } } } },
      _count: { select: { checkins: true } },
    },
    orderBy: { createdAt: 'asc' },
  })
  return NextResponse.json({ ok: true, customers: rows })
}

export async function POST(req: Request) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const b = await req.json().catch(() => null) as {
    name?: string; type?: string; address?: string; phone?: string
    lat?: number; lng?: number; radius?: number
  } | null
  if (!b?.name || !isValidLatLng(Number(b.lat), Number(b.lng))) {
    return NextResponse.json({ ok: false, error: 'نام و موقعیت روی نقشه لازم است' }, { status: 400 })
  }
  const row = await db.customer.create({
    data: {
      name: b.name,
      type: b.type || 'doctor',
      address: b.address || null,
      phone: b.phone || null,
      lat: Number(b.lat),
      lng: Number(b.lng),
      radius: Number(b.radius) || 150,
    },
  })
  return NextResponse.json({ ok: true, customer: row })
}
