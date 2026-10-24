/**
 * MedPad quick tunnel runner (v1.9.0+) — trycloudflare random URL.
 * Downloads cloudflared if missing, runs `tunnel --url`, prints the URL.
 * NOTE: URL changes on every run — domain mode (v1.9.3) gives a fixed URL.
 */
import { spawn } from 'child_process'
import { existsSync, chmodSync, mkdirSync, createWriteStream } from 'fs'
import path from 'path'
import https from 'https'
import http from 'http'
import url from 'url'

const __dirname = path.dirname(url.fileURLToPath(import.meta.url))
const PORT = process.env.PANEL_PORT || 3000

function binPath() {
  const name = process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared'
  return path.join(__dirname, 'bin', name)
}

function download(urlStr, dest) {
  return new Promise((resolve, reject) => {
    const file = createWriteStream(dest)
    const get = (u, redirects = 0) => {
      const mod = u.startsWith('https') ? https : http
      mod
        .get(u, (res) => {
          if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 5) {
            res.resume()
            return get(res.headers.location, redirects + 1)
          }
          if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`))
          res.pipe(file)
          file.on('finish', () => file.close(resolve))
        })
        .on('error', reject)
    }
    get(urlStr)
  })
}

export async function ensureCloudflared() {
  const bin = binPath()
  if (existsSync(bin)) return bin
  mkdirSync(path.dirname(bin), { recursive: true })
  const arch = process.arch === 'arm64' ? 'arm64' : 'amd64'
  const urlStr =
    process.platform === 'win32'
      ? 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe'
      : `https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${arch}`
  console.log(`[..] Downloading cloudflared...`)
  await download(urlStr, bin)
  if (process.platform !== 'win32') chmodSync(bin, 0o755)
  return bin
}

export async function startQuickTunnel(target = `http://localhost:${PORT}`) {
  const bin = await ensureCloudflared()
  const proc = spawn(bin, ['tunnel', '--url', target, '--protocol', 'http2'], { stdio: 'pipe' })
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout waiting for trycloudflare URL')), 60_000)
    let buf = ''
    const onData = (d) => {
      buf += d.toString()
      const m = buf.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i)
      if (m) {
        clearTimeout(timer)
        resolve({ url: m[0], proc })
      }
    }
    proc.stdout.on('data', onData)
    proc.stderr.on('data', onData)
    proc.on('exit', (code) => reject(new Error(`cloudflared exited (${code})`)))
  })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  startQuickTunnel()
    .then(({ url: u }) => {
      console.log(`\nYour temporary address: ${u}`)
      console.log('WARN: this address changes on every run - use the "my own domain" mode for a fixed address\n')
    })
    .catch((e) => {
      console.error(`[X] Quick tunnel failed: ${e.message}`)
      process.exit(1)
    })
}
