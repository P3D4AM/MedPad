import { mkdir } from 'fs/promises'
import path from 'path'

export const UPLOAD_ROOT = path.join(process.cwd(), 'uploads')
export const VOICE_DIR = path.join(UPLOAD_ROOT, 'voice')

export async function ensureDirs() {
  await mkdir(VOICE_DIR, { recursive: true })
}

/** Only allow safe filenames (no traversal) */
export function safeName(name: string): string | null {
  if (!/^[a-zA-Z0-9._-]+$/.test(name)) return null
  if (name.includes('..')) return null
  return name
}

export function contentTypeFor(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() || ''
  if (ext === 'm4a') return 'audio/mp4'
  if (ext === 'mp3') return 'audio/mpeg'
  if (ext === 'aac') return 'audio/aac'
  if (ext === 'ogg') return 'audio/ogg'
  if (ext === 'wav') return 'audio/wav'
  if (ext === '3gp') return 'audio/3gpp'
  return 'application/octet-stream'
}
