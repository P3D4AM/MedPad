import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin } from '@/lib/auth'
import { isValidLatLng } from '@/lib/geo'

/** PUT — edit customer (panel side; bumps updatedAt for LWW sync to app) */
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const { id } = await ctx.params
  const b = await req.json().catch(() => null) as {
    name?: string; type?: string; address?: string; phone?: string
    lat?: number; lng?: number; radius?: number
  } | null
  const data: Record<string, unknown> = { updatedAt: new Date() } // LWW bump
  if (b?.name !== undefined) data.name = b.name
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

export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const { id } = await ctx.params
  await db.customer.delete({ where: { id } })
  return NextResponse.json({ ok: true })
}
