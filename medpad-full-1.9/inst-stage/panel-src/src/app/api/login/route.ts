import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { ADMIN_COOKIE, getConfig, newToken, setConfig, sha256, verifyPassword, hashPassword } from '@/lib/auth'

export async function POST(req: Request) {
  const { username, password } = await req.json().catch(() => ({ username: '', password: '' }))
  if (!username || !password) {
    return NextResponse.json({ ok: false, error: 'نام کاربری و رمز لازم است' }, { status: 400 })
  }
  const savedUser = await getConfig('adminUser', 'admin')
  const savedPass = await getConfig('adminPassHash', sha256('admin123'))
  // v1.9.12: bcrypt with transparent legacy-SHA-256 migration (re-hash on login)
  const v = await verifyPassword(String(password), savedPass)
  if (username !== savedUser || !v.ok) {
    return NextResponse.json({ ok: false, error: 'نام کاربری یا رمز اشتباه است' }, { status: 401 })
  }
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
