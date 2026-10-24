import { NextResponse } from 'next/server'
import { writeFile } from 'fs/promises'
import path from 'path'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'
import { VOICE_DIR, ensureDirs } from '@/lib/media'
import { emitEvent } from '@/lib/emit'

const MIN_NOTE = 10 // chars — "متن چک‌اوت ستاره‌دار"

/**
 * App check-out (multipart/form-data):
 *   token, note (REQUIRED ≥10 chars), localId, voice (REQUIRED), lat?, lng?
 * Closes ALL open visits of the employee (fixes duplicated-visit leak).
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

  const note = String(form.get('note') || '').trim()
  const localId = String(form.get('localId') || '') || null
  const lat = parseFloat(String(form.get('lat') || ''))
  const lng = parseFloat(String(form.get('lng') || ''))

  if (note.length < MIN_NOTE) {
    return NextResponse.json(
      { ok: false, error: `متن چک‌اوت باید حداقل ${MIN_NOTE} نویسه باشد` },
      { status: 400 },
    )
  }
  if (localId) {
    const dup = await db.checkin.findUnique({ where: { localId } })
    if (dup) return NextResponse.json({ ok: true, checkout: dup, duplicate: true })
  }

  const openVisits = await db.visit.findMany({ where: { employeeId: emp.id, open: true } })
  if (openVisits.length === 0) {
    return NextResponse.json({ ok: false, error: 'ویزیت بازی وجود ندارد' }, { status: 409 })
  }

  // ── Voice REQUIRED on checkout (per spec) ───────────────
  let voicePath: string | null = null
  const voice = form.get('voice')
  if (voice instanceof File && voice.size > 0) {
    await ensureDirs()
    const ext = (voice.name.split('.').pop() || 'm4a').toLowerCase().replace(/[^a-z0-9]/g, '')
    const fname = `${emp.id}_out_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.${ext || 'm4a'}`
    const buf = Buffer.from(await voice.arrayBuffer())
    await writeFile(path.join(VOICE_DIR, fname), buf)
    voicePath = `voice/${fname}`
  }
  if (!voicePath) {
    return NextResponse.json(
      { ok: false, error: 'برای چک‌اوت، ضبط صدا الزامی است' },
      { status: 400 },
    )
  }

  const co = await db.checkin.create({
    data: {
      localId,
      employeeId: emp.id,
      customerId: openVisits[0].customerId,
      kind: 'checkout',
      note,
      voicePath,
      lat: Number.isFinite(lat) ? lat : null,
      lng: Number.isFinite(lng) ? lng : null,
    },
  })

  const now = new Date()
  for (const v of openVisits) {
    await db.visit.update({
      where: { id: v.id },
      data: { open: false, endAt: now, checkoutId: co.id },
    })
  }

  await emitEvent('checkout', {
    id: co.id,
    employeeName: emp.name,
    employeeId: emp.id,
    closedVisits: openVisits.length,
    notePreview: note.slice(0, 120),
    hasVoice: true,
    voiceUrl: `/api/media/${voicePath}`,
    at: co.createdAt,
  })

  return NextResponse.json({ ok: true, checkout: co, closedVisits: openVisits.length })
}

/** GET — active visit info for the app (open visits count) */
export async function GET(req: Request) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const open = await db.visit.findMany({
    where: { employeeId: emp.id, open: true },
    include: { customer: { select: { id: true, name: true } } },
    orderBy: { startAt: 'desc' },
  })
  return NextResponse.json({ ok: true, openVisits: open })
}
