import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { appEmployee, isAdmin } from '@/lib/auth'
import { VOICE_DIR, contentTypeFor, safeName } from '@/lib/media'
import { readFile } from 'fs/promises'
import path from 'path'

/**
 * Serve uploaded voice files.
 * Access: admin cookie OR app token (?token=) — employee can read own files;
 * admin cookie reads anything.
 */
export async function GET(req: Request, ctx: { params: Promise<{ path: string[] }> }) {
  const { path: parts } = await ctx.params
  const [dir, name] = parts
  if (dir !== 'voice' || !name) {
    return NextResponse.json({ ok: false, error: 'not found' }, { status: 404 })
  }
  const safe = safeName(name)
  if (!safe) return NextResponse.json({ ok: false, error: 'bad request' }, { status: 400 })

  const admin = await isAdmin()
  if (!admin) {
    const emp = await appEmployee(req)
    if (!emp) {
      return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
    }
    // employees may only fetch their own voice files
    const row = await db.checkin.findFirst({ where: { voicePath: `voice/${safe}` } })
    if (row && row.employeeId !== emp.id) {
      return NextResponse.json({ ok: false, error: 'forbidden' }, { status: 403 })
    }
  }

  const file = path.join(VOICE_DIR, safe)
  try {
    const data = await readFile(file)
    return new NextResponse(new Uint8Array(data), {
      headers: {
        'content-type': contentTypeFor(safe),
        'cache-control': 'private, max-age=3600',
        'accept-ranges': 'bytes',
      },
    })
  } catch {
    return NextResponse.json({ ok: false, error: 'not found' }, { status: 404 })
  }
}
