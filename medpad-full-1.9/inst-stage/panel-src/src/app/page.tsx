'use client'

import { useEffect, useState } from 'react'
import LoginScreen from '@/components/panel/LoginScreen'
import Dashboard from '@/components/panel/Dashboard'
import type { PanelConfigInfo } from '@/lib/types'

export default function Page() {
  const [authed, setAuthed] = useState<boolean | null>(null)
  const [cfg, setCfg] = useState<PanelConfigInfo | null>(null)

  const loadConfig = async () => {
    const res = await fetch('/api/config', { cache: 'no-store' })
    const data = await res.json()
    if (data.ok) setCfg({ panelVersion: data.panelVersion, apkVersion: data.apkVersion, socketMode: data.socketMode })
  }

  useEffect(() => {
    ;(async () => {
      // session check via a cheap authenticated endpoint
      const res = await fetch('/api/stats', { cache: 'no-store' })
      setAuthed(res.ok)
      await loadConfig()
    })()
  }, [])

  if (authed === null || !cfg) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-teal-600 border-t-transparent" />
      </div>
    )
  }

  if (!authed) return <LoginScreen onLogin={() => setAuthed(true)} />
  return <Dashboard cfg={cfg} onLogout={() => setAuthed(false)} />
}
