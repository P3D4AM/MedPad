import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'
import { distanceMeters } from '@/lib/geo'
import { emitEvent } from '@/lib/emit'

/**
 * App proximity ping — fired by TrackingService when the rep enters a
 * customer's geofence (radius). Creates a notification (throttled: once per
 * customer / 10 min) and emits a realtime panel event.
 */
const THROTTLE_MS = 10 * 60 * 1000

export async function POST(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const { customerId, lat, lng } = await req.json().catch(() => ({})) as {
    customerId?: string; lat?: number; lng?: number
  }
  if (!customerId) return NextResponse.json({ ok: false, error: 'bad request' }, { status: 400 })
  const cus = await db.customer.findUnique({ where: { id: customerId } })
  if (!cus) return NextResponse.json({ ok: false, error: 'not found' }, { status: 404 })

  const assigned = await db.assignment.findFirst({ where: { employeeId: emp.id, customerId } })
  if (!assigned) return NextResponse.json({ ok: false, error: 'not assigned' }, { status: 403 })

  // throttle: skip if a proximity notif for same customer exists within window
  const recent = await db.appNotification.findFirst({
    where: {
      employeeId: emp.id,
      customerId,
      kind: 'proximity',
      createdAt: { gte: new Date(Date.now() - THROTTLE_MS) },
    },
  })
  if (recent) return NextResponse.json({ ok: true, throttled: true })

  const dist =
    Number.isFinite(lat) && Number.isFinite(lng)
      ? Math.round(distanceMeters(lat, lng, cus.lat, cus.lng))
      : null

  const notif = await db.appNotification.create({
    data: {
      employeeId: emp.id,
      title: 'حضور در محدوده مشتری',
      body: `${emp.name} در شعاع ${cus.radius} متری «${cus.name}» است${dist !== null ? ` (فاصله ${dist} متر)` : ''}.`,
      kind: 'proximity',
      customerId,
    },
  })
  await emitEvent('proximity', {
    id: notif.id,
    employeeName: emp.name,
    customerName: cus.name,
    distance: dist,
    at: notif.createdAt,
  })
  return NextResponse.json({ ok: true, notification: notif.id })
}
