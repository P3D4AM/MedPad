import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { isAdmin, hashPassword } from '@/lib/auth'

/** PUT — edit employee (name, phone, password, active) */
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const { id } = await ctx.params
  const body = await req.json().catch(() => null) as {
    name?: string; phone?: string; password?: string; active?: boolean
  } | null
  const data: Record<string, unknown> = {}
  if (body?.name !== undefined) data.name = body.name
  if (body?.phone !== undefined) data.phone = body.phone || null
  if (body?.active !== undefined) data.active = body.active
  if (body?.password) data.password = hashPassword(body.password)
  const row = await db.employee.update({ where: { id }, data })
  return NextResponse.json({ ok: true, employee: row })
}

/** DELETE — remove employee (cascade tokens, assignments) */
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!(await isAdmin())) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }
  const { id } = await ctx.params
  await db.employee.delete({ where: { id } })
  return NextResponse.json({ ok: true })
}
