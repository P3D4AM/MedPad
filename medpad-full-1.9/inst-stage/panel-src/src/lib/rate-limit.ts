/**
 * v1.9.12 (audit phase 3): simple in-memory login rate limiter.
 *
 * Policy: MAX failures per KEY within WINDOW ms -> the key is locked out
 * for LOCKOUT ms. Successful logins clear the key's failure history.
 * In-memory by design: the panel runs as a single standalone Node process
 * (offline bundle); no external store exists. A restart resets counters,
 * which is acceptable for this deployment model.
 *
 * Keys are caller-defined: `${ip}` for the admin login, `${ip}|${username}`
 * for the employee app login. Keys and counters NEVER contain passwords.
 */

const MAX_FAILURES = 5
const WINDOW_MS = 15 * 60 * 1000 // 15 minutes
const LOCKOUT_MS = 15 * 60 * 1000 // 15 minutes

type Bucket = { fails: number[]; lockedUntil: number }

// survive module re-imports in the same process (Next.js route bundling)
const g = globalThis as unknown as { __medpadRateBuckets?: Map<string, Bucket> }
const buckets: Map<string, Bucket> = (g.__medpadRateBuckets ??= new Map())

/** Client IP for a Request (standalone Next.js puts it in x-forwarded-for /
 *  x-real-ip when behind the tunnel; direct LAN hits have no header). */
export function clientIp(req: Request): string {
  const xf = req.headers.get('x-forwarded-for')
  if (xf) return xf.split(',')[0].trim()
  return req.headers.get('x-real-ip') || 'local'
}

/** ms remaining in an active lockout, 0 when the key may try again. */
export function lockoutRemaining(key: string): number {
  const b = buckets.get(key)
  if (!b) return 0
  const now = Date.now()
  if (b.lockedUntil > now) return b.lockedUntil - now
  if (b.lockedUntil && b.lockedUntil <= now) buckets.delete(key)
  return 0
}

/** Record a FAILED attempt. Returns the remaining lockout (may start one). */
export function recordFailure(key: string): number {
  const now = Date.now()
  const b = buckets.get(key) ?? { fails: [], lockedUntil: 0 }
  b.fails = b.fails.filter((t) => now - t < WINDOW_MS)
  b.fails.push(now)
  if (b.fails.length >= MAX_FAILURES) {
    b.lockedUntil = now + LOCKOUT_MS
    b.fails = []
  }
  buckets.set(key, b)
  return Math.max(0, b.lockedUntil - now)
}

/** Record a SUCCESS — clears the failure history for the key. */
export function recordSuccess(key: string): void {
  buckets.delete(key)
}

/** Housekeeping so the map cannot grow unbounded (run per login request). */
export function sweep(): void {
  if (buckets.size < 5000) return
  const now = Date.now()
  for (const [k, b] of buckets) {
    if (b.lockedUntil <= now && b.fails.every((t) => now - t >= WINDOW_MS)) buckets.delete(k)
  }
}
