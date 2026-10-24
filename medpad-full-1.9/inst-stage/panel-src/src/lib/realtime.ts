'use client'

import { useEffect, useRef } from 'react'
import { io, type Socket } from 'socket.io-client'

export interface PanelConfigInfo {
  panelVersion: string
  apkVersion: string
  socketMode: 'gateway' | 'direct'
}

/**
 * Fetch a short-lived socket token from the panel API (admin session required).
 * Returns null when not logged in or the API is unreachable.
 */
export async function fetchSocketToken(): Promise<string | null> {
  try {
    const res = await fetch('/api/admin/socket-auth', { cache: 'no-store' })
    if (!res.ok) return null
    const data = await res.json()
    return data?.ok ? (data.token as string) : null
  } catch {
    return null
  }
}

/**
 * Connect to the MedPad realtime service (v1.9.5: authenticated handshake).
 *  - gateway mode (sandbox/preview/tunnel): same-origin /socket.io — Next.js
 *    rewrite proxies it to the realtime service (works everywhere, one port)
 *  - direct mode (installer LAN): ws(s)://host:3003
 * The HMAC token from /api/admin/socket-auth is sent in handshake.auth —
 * the realtime service rejects anonymous connections.
 */
export function connectRealtime(cfg: PanelConfigInfo, token: string | null): Socket {
  const auth = { token: token || '' }
  if (cfg.socketMode === 'direct') {
    const proto = location.protocol === 'https:' ? 'https' : 'http'
    return io(`${proto}://${location.hostname}:3003`, { path: '/socket.io', transports: ['polling', 'websocket'], auth })
  }
  return io('/', { path: '/socket.io', transports: ['polling', 'websocket'], auth })
}

/** Ask once for browser notification permission (no-op on insecure origins) */
export function requestNotifyPermission(): void {
  try {
    if (typeof window !== 'undefined' && 'Notification' in window && location.protocol === 'https:') {
      if (Notification.permission === 'default') Notification.requestPermission()
    }
  } catch { /* ignore */ }
}

export function browserNotify(title: string, body: string): void {
  try {
    if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'granted') {
      new Notification(title, { body, dir: 'rtl', lang: 'fa' })
    }
  } catch { /* ignore */ }
}

/** Keeps a socket alive and dispatches events to a handler */
export function useRealtimeSocket(
  cfg: PanelConfigInfo | null,
  onEvent: (event: string, payload: unknown) => void,
): void {
  const handlerRef = useRef(onEvent)

  useEffect(() => {
    handlerRef.current = onEvent
  }, [onEvent])

  useEffect(() => {
    if (!cfg) return
    let sock: Socket | null = null
    let cancelled = false
    let connecting = false

    const connect = async () => {
      if (cancelled || connecting) return
      connecting = true
      try {
        try { sock?.disconnect() } catch { /* noop */ }
        // fresh token per attempt (TTL 10 min — keeps long sessions alive)
        const token = await fetchSocketToken()
        if (cancelled) return
        if (!token) {
          // not logged in / API unreachable — retry on next watchdog tick
          return
        }
        sock = connectRealtime(cfg, token)
        const forward = (evt: string) => (payload: unknown) => handlerRef.current(evt, payload)
        const events = ['hello', 'checkin', 'checkout', 'notification', 'proximity', 'customer-added']
        sock.on('connect', () => console.log('[rt] connected (authenticated)'))
        sock.on('disconnect', () => console.log('[rt] disconnected'))
        sock.on('connect_error', (e) => {
          // v1.9.5: server rejected the handshake (expired/invalid token)
          console.warn('[rt] connect_error:', e.message)
        })
        for (const e of events) sock.on(e, forward(e))
      } finally {
        connecting = false
      }
    }
    connect()

    // watchdog: reconnect with a fresh token if dropped
    const iv = setInterval(() => {
      if (cancelled) return
      if (!sock || !sock.connected) connect()
    }, 10_000)

    return () => {
      cancelled = true
      clearInterval(iv)
      try { sock?.disconnect() } catch { /* noop */ }
    }
  }, [cfg])
}

/** Persian date/time formatting */
export function faDateTime(iso: string | Date): string {
  const d = new Date(iso)
  return d.toLocaleString('fa-IR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

export function faTime(iso: string | Date): string {
  return new Date(iso).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' })
}
