import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { newToken, verifyPassword, hashPassword } from '@/lib/auth'

/** Employee app login → issues bearer token (no guest mode).
 *  v1.9.12: bcrypt with transparent legacy-SHA-256 migration (re-hash on login). */
export async function POST(req: Request) {
  const { username, password } = await req.json().catch(() => ({ username: '', password: '' }))
  if (!username || !password) {
    return NextResponse.json({ ok: false, error: 'نام کاربری و رمز لازم است' }, { status: 400 })
  }
  const emp = await db.employee.findUnique({ where: { username: String(username).trim() } })
  if (!emp || !emp.active) {
    return NextResponse.json({ ok: false, error: 'نام کاربری یا رمز اشتباه است' }, { status: 401 })
  }
  const v = await verifyPassword(String(password), emp.password)
  if (!v.ok) {
    return NextResponse.json({ ok: false, error: 'نام کاربری یا رمز اشتباه است' }, { status: 401 })
  }
  if (v.needsRehash) {
    await db.employee.update({ where: { id: emp.id }, data: { password: hashPassword(String(password)) } })
  }
  const token = newToken()
  await db.appToken.create({ data: { token, employeeId: emp.id } })
  return NextResponse.json({
    ok: true,
    token,
    employee: { id: emp.id, name: emp.name, username: emp.username },
  })
}
