import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee } from '@/lib/auth'
import { isValidLatLng } from '@/lib/geo'

/**
 * App edits a customer — Last-Writer-Wins (LWW):
 * client sends its `updatedAt` (ISO); if server row is NEWER (panel edited
 * after app edit started), server version wins and app is told to refresh.
 * No delete from app.
 */
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const emp = await appEmployee(req)
  if (!emp) return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  const { id } = await ctx.params
  const b = await req.json().catch(() => null) as {
    name?: string; type?: string; address?: string; phone?: string
    lat?: number; lng?: number; radius?: number
    updatedAt?: string // client's known version
  } | null

  const current = await db.customer.findUnique({ where: { id } })
  if (!current) return NextResponse.json({ ok: false, error: 'پیدا نشد' }, { status: 404 })

  // LWW: if panel edited after app's known version → reject, app refreshes
  if (b?.updatedAt) {
    const clientTime = new Date(b.updatedAt).getTime()
    if (Number.isFinite(clientTime) && current.updatedAt.getTime() > clientTime + 2000) {
      return NextResponse.json(
        { ok: false, error: 'conflict', customer: current, conflict: true },
        { status: 409 },
      )
    }
  }

  const data: Record<string, unknown> = { updatedAt: new Date() }
  if (b?.name !== undefined && b.name) data.name = b.name
  if (b?.type !== undefined) data.type = b.type
  if (b?.address !== undefined) data.address = b.address || null
  if (b?.phone !== undefined) data.phone = b.phone || null
  if (b?.radius !== undefined) data.radius = Number(b.radius) || 150
  if (b?.lat !== undefined && b?.lng !== undefined && isValidLatLng(Number(b.lat), Number(b.lng))) {
    data.lat = Number(b.lat)
    data.lng = Number(b.lng)
  }
  const row = await db.customer.update({ where: { id }, data })
  return NextResponse.json({ ok: true, customer: row })
}
