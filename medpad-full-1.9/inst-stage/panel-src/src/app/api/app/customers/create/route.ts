import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'
import { isValidLatLng } from '@/lib/geo'
import { emitEvent } from '@/lib/emit'

/** App creates a new customer (sales rep adds doctor/pharmacy) — no delete allowed */
export async function POST(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const b = await req.json().catch(() => null) as {
    name?: string; type?: string; address?: string; phone?: string
    lat?: number; lng?: number; radius?: number; clientId?: string
  } | null
  if (!b?.name || !isValidLatLng(Number(b.lat), Number(b.lng))) {
    return NextResponse.json({ ok: false, error: 'نام و موقعیت لازم است' }, { status: 400 })
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
  // creator automatically assigned
  await db.assignment.create({ data: { employeeId: emp.id, customerId: row.id } }).catch(() => {})

  await emitEvent('customer-added', {
    id: row.id,
    name: row.name,
    by: emp.name,
    lat: row.lat,
    lng: row.lng,
  })
  return NextResponse.json({ ok: true, customer: row })
}
