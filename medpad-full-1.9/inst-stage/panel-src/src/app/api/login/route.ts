import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { ADMIN_COOKIE, getConfig, newToken, setConfig, verifyPassword, hashPassword } from '@/lib/auth'
import { clientIp, lockoutRemaining, recordFailure, recordSuccess, sweep } from '@/lib/rate-limit'

export const dynamic = 'force-dynamic'

export async function POST(req: Request) {
  sweep()
  const { username, password } = await req.json().catch(() => ({ username: '', password: '' }))
  if (!username || !password) {
    return NextResponse.json({ ok: false, error: 'نام کاربری و رمز لازم است' }, { status: 400 })
  }

  // v1.9.12 (audit phase 3): rate limiting — 5 failures per IP per 15 min
  // locks that IP out for 15 minutes. Counted per IP only (the admin user
  // is a single well-known account; per-IP is the meaningful dimension).
  const rlKey = `admin|${clientIp(req)}`
  const waitMs = lockoutRemaining(rlKey)
  if (waitMs > 0) {
    return NextResponse.json(
      { ok: false, error: `تلاش‌های ناموفق زیاد — ${Math.ceil(waitMs / 60000)} دقیقه دیگر دوباره امتحان کنید` },
      { status: 429, headers: { 'retry-after': String(Math.ceil(waitMs / 1000)) } },
    )
  }

  const savedUser = await getConfig('adminUser', 'admin')
  // v1.9.12 (audit phase 3): the admin123 default is GONE. A database
  // without an adminPassHash row is an unseeded/broken install — login is
  // refused with a clear message instead of silently accepting admin123.
  const savedPass = await getConfig('adminPassHash', '')
  if (!savedPass) {
    console.error('[medpad] login refused: no adminPassHash in Config (unseeded database?)')
    return NextResponse.json(
      { ok: false, error: 'حساب مدیر مقداردهی نشده است — نصب را دوباره اجرا کنید یا رمز را با --admin-pass بازنشانی کنید' },
      { status: 503 },
    )
  }
  // v1.9.12: bcrypt with transparent legacy-SHA-256 migration (re-hash on login)
  const v = await verifyPassword(String(password), savedPass)
  if (username !== savedUser || !v.ok) {
    const remain = recordFailure(rlKey)
    return NextResponse.json(
      { ok: false, error: `نام کاربری یا رمز اشتباه است${remain > 0 ? ` — ${Math.ceil(remain / 60000)} دقیقه قفل می‌شوید` : ''}` },
      { status: 401 },
    )
  }
  recordSuccess(rlKey)
  if (v.needsRehash) {
    await setConfig('adminPassHash', hashPassword(String(password)))
  }
  const tok = newToken()
  await setConfig('adminSession', tok)
  const res = NextResponse.json({ ok: true })
  res.cookies.set(ADMIN_COOKIE, tok, {
    httpOnly: true,
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 60 * 24 * 30,
  })
  return res
}
