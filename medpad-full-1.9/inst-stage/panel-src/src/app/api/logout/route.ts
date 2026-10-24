import { NextResponse } from 'next/server'
import { ADMIN_COOKIE, setConfig } from '@/lib/auth'

export async function POST() {
  await setConfig('adminSession', '')
  const res = NextResponse.json({ ok: true })
  res.cookies.set(ADMIN_COOKIE, '', { httpOnly: true, path: '/', maxAge: 0 })
  return res
}
