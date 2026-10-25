import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { newToken, verifyPassword, hashPassword } from '@/lib/auth'
import { clientIp, lockoutRemaining, recordFailure, recordSuccess, sweep } from '@/lib/rate-limit'

export const dynamic = 'force-dynamic'

/** Employee app login → issues bearer token (no guest mode).
 *  v1.9.12: bcrypt with transparent legacy-SHA-256 migration (re-hash on login).
 *  v1.9.12 (audit phase 3): rate limiting — 5 failures per IP+username per
 *  15 min locks that pair out for 15 min (brute-force protection for the
 *  4-digit-ish employee passwords used in the field). */
export async function POST(req: Request) {
  sweep()
  const { username, password } = await req.json().catch(() => ({ username: '', password: '' }))
  if (!username || !password) {
    return NextResponse.json({ ok: false, error: 'نام کاربری و رمز لازم است' }, { status: 400 })
  }
  const uname = String(username).trim()
  const rlKey = `app|${clientIp(req)}|${uname.toLowerCase()}`
  const waitMs = lockoutRemaining(rlKey)
  if (waitMs > 0) {
    return NextResponse.json(
      { ok: false, error: 'تلاش‌های ناموفق زیاد — بعداً دوباره امتحان کنید' },
      { status: 429, headers: { 'retry-after': String(Math.ceil(waitMs / 1000)) } },
    )
  }
  const emp = await db.employee.findUnique({ where: { username: uname } })
  if (!emp || !emp.active) {
    recordFailure(rlKey)
    return NextResponse.json({ ok: false, error: 'نام کاربری یا رمز اشتباه است' }, { status: 401 })
  }
  const v = await verifyPassword(String(password), emp.password)
  if (!v.ok) {
    recordFailure(rlKey)
    return NextResponse.json({ ok: false, error: 'نام کاربری یا رمز اشتباه است' }, { status: 401 })
  }
  recordSuccess(rlKey)
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
