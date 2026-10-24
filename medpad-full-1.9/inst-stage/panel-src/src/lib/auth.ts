import { createHash, randomBytes } from 'crypto'
import bcrypt from 'bcryptjs'
import { cookies } from 'next/headers'
import { db } from '@/lib/db'

export const ADMIN_COOKIE = 'medpad_admin'
export const APP_TOKEN_HEADER = 'x-medpad-token'

export const sha256 = (s: string) => createHash('sha256').update(s).digest('hex')

/**
 * v1.9.12 password hashing — MIGRATING AWAY FROM PLAIN SHA-256.
 *
 * New hashes are bcrypt (bcryptjs, pure JS — no native deps, offline-bundle
 * safe) with cost 10, stored with the prefix `bcrypt$` so legacy and new
 * formats are distinguishable. Legacy rows contain a bare 64-char hex
 * SHA-256; they keep working and are transparently upgraded to bcrypt on
 * the next successful login (no user action, no lockout, db stays valid).
 */
export const BCRYPT_PREFIX = 'bcrypt$'
export const BCRYPT_COST = 10

export function hashPassword(plain: string): string {
  return BCRYPT_PREFIX + bcrypt.hashSync(plain, BCRYPT_COST)
}

export function isBcrypt(stored: string): boolean {
  return typeof stored === 'string' && stored.startsWith(BCRYPT_PREFIX)
}

/** Verify a password against a stored hash (bcrypt OR legacy sha256).
 *  Returns { ok, needsRehash } — needsRehash=true means the login worked via
 *  the legacy path and the caller should re-store it with hashPassword(). */
export async function verifyPassword(plain: string, stored: string): Promise<{ ok: boolean; needsRehash: boolean }> {
  if (!stored) return { ok: false, needsRehash: false }
  if (isBcrypt(stored)) {
    const ok = await bcrypt.compare(plain, stored.slice(BCRYPT_PREFIX.length))
    return { ok, needsRehash: false }
  }
  // legacy plain SHA-256 (installs made before v1.9.12)
  if (sha256(plain) === stored) return { ok: true, needsRehash: true }
  return { ok: false, needsRehash: false }
}

export function newToken(): string {
  return randomBytes(24).toString('hex')
}

/** Admin auth via cookie — returns true when session valid */
export async function isAdmin(): Promise<boolean> {
  const jar = await cookies()
  const tok = jar.get(ADMIN_COOKIE)?.value
  if (!tok) return false
  const row = await db.config.findUnique({ where: { key: 'adminSession' } })
  return !!row && row.value === tok
}

/** Employee (app) auth via header or ?token= — returns employee or null */
export async function appEmployee(req: Request) {
  const url = new URL(req.url)
  let tok = req.headers.get(APP_TOKEN_HEADER) || ''
  if (!tok) tok = url.searchParams.get('token') || ''
  if (!tok) return null
  const row = await db.appToken.findUnique({ where: { token: tok }, include: { employee: true } })
  if (!row || !row.employee.active) return null
  // refresh lastUsed (fire & forget)
  db.appToken.update({ where: { token: tok }, data: { lastUsedAt: new Date() } }).catch(() => {})
  return row.employee
}

export async function getConfig(key: string, def = ''): Promise<string> {
  const row = await db.config.findUnique({ where: { key } })
  return row?.value ?? def
}

export async function setConfig(key: string, value: string) {
  await db.config.upsert({ where: { key }, update: { value }, create: { key, value } })
}

/** Socket token shared between panel & realtime service (from env or config) */
export function socketToken(): string {
  return process.env.SOCKET_TOKEN || 'medpad-dev-token'
}
