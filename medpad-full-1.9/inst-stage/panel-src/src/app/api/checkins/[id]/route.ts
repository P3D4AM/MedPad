import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin } from '@/lib/auth'
import { unlink } from 'fs/promises'
import path from 'path'
import { VOICE_DIR, safeName } from '@/lib/media'

/** DELETE a (duplicate) check-in row — panel ✕ button */
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const { id } = await ctx.params
  const row = await db.checkin.findUnique({ where: { id } })
  if (!row) return NextResponse.json({ ok: false, error: 'not found' }, { status: 404 })

  // detach from visit (keep visit, just clear reference)
  await db.visit.updateMany({ where: { checkinId: id }, data: { checkinId: null } })
  await db.visit.updateMany({ where: { checkoutId: id }, data: { checkoutId: null } })
  await db.checkin.delete({ where: { id } })

  if (row.voicePath) {
    const name = safeName(path.basename(row.voicePath))
    if (name) unlink(path.join(VOICE_DIR, name)).catch(() => {})
  }
  return NextResponse.json({ ok: true })
}
