import { NextResponse } from 'next/server'
import { writeFile } from 'fs/promises'
import path from 'path'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'
import { VOICE_DIR, ensureDirs } from '@/lib/media'
import { isValidLatLng } from '@/lib/geo'
import { emitEvent } from '@/lib/emit'

const DEDUP_WINDOW_MS = 90_000 // same employee+customer within 90s = duplicate

/**
 * App check-in (multipart/form-data):
 *   token, customerId, note, lat, lng, localId, voice? (audio file)
 * Idempotency:
 *   1) localId unique — replay returns the original row (201→200)
 *   2) 90s dedup window per employee+customer
 * Closes any previous open visit for the same employee.
 */
export async function POST(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })

  let form: FormData
  try {
    form = await req.formData()
  } catch {
    return NextResponse.json({ ok: false, error: 'فرم نامعتبر است' }, { status: 400 })
  }

  const customerId = String(form.get('customerId') || '')
  const note = String(form.get('note') || '').trim()
  const lat = parseFloat(String(form.get('lat') || ''))
  const lng = parseFloat(String(form.get('lng') || ''))
  const localId = String(form.get('localId') || '') || null

  if (!customerId) {
    return NextResponse.json({ ok: false, error: 'مشتری انتخاب نشده است' }, { status: 400 })
  }
  const customer = await db.customer.findUnique({ where: { id: customerId } })
  if (!customer) {
    return NextResponse.json({ ok: false, error: 'مشتری پیدا نشد' }, { status: 404 })
  }

  // ── Idempotency layer 1: localId replay ─────────────────
  if (localId) {
    const dup = await db.checkin.findUnique({ where: { localId } })
    if (dup) {
      return NextResponse.json({ ok: true, checkin: dup, duplicate: true })
    }
  }

  // ── Idempotency layer 2: 90s dedup window ───────────────
  const recent = await db.checkin.findFirst({
    where: {
      employeeId: emp.id,
      kind: 'checkin',
      createdAt: { gte: new Date(Date.now() - DEDUP_WINDOW_MS) },
    },
    orderBy: { createdAt: 'desc' },
  })
  if (recent && recent.customerId === customerId) {
    return NextResponse.json({ ok: true, checkin: recent, duplicate: true, dedupWindow: true })
  }

  // ── Save voice (optional on checkin) ────────────────────
  let voicePath: string | null = null
  const voice = form.get('voice')
  if (voice instanceof File && voice.size > 0) {
    await ensureDirs()
    const ext = (voice.name.split('.').pop() || 'm4a').toLowerCase().replace(/[^a-z0-9]/g, '')
    const fname = `${emp.id}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.${ext || 'm4a'}`
    const buf = Buffer.from(await voice.arrayBuffer())
    await writeFile(path.join(VOICE_DIR, fname), buf)
    voicePath = `voice/${fname}`
  }

  const ci = await db.checkin.create({
    data: {
      localId,
      employeeId: emp.id,
      customerId,
      kind: 'checkin',
      note: note || null,
      voicePath,
      lat: isValidLatLng(lat, lng) ? lat : customer.lat,
      lng: isValidLatLng(lat, lng) ? lng : customer.lng,
    },
  })

  // ── Close previous open visits of this employee ─────────
  await db.visit.updateMany({
    where: { employeeId: emp.id, open: true },
    data: { open: false, endAt: new Date() },
  })

  // ── New visit ────────────────────────────────────────────
  await db.visit.create({
    data: {
      employeeId: emp.id,
      customerId,
      checkinId: ci.id,
      startAt: new Date(),
      open: true,
    },
  })

  await emitEvent('checkin', {
    id: ci.id,
    employeeName: emp.name,
    employeeId: emp.id,
    customerName: customer.name,
    notePreview: (note || '').slice(0, 120),
    hasVoice: !!voicePath,
    voiceUrl: voicePath ? `/api/media/${voicePath}` : null,
    at: ci.createdAt,
  })

  return NextResponse.json({ ok: true, checkin: ci })
}
