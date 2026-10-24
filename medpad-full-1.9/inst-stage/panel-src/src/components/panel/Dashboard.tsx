'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useToast } from '@/hooks/use-toast'
import {
  Bell,
  CalendarCheck,
  DoorOpen,
  Download,
  LogOut,
  RadioTower,
} from 'lucide-react'
import OverviewTab from './OverviewTab'
import CheckinsTab from './CheckinsTab'
import CustomersTab from './CustomersTab'
import EmployeesTab from './EmployeesTab'
import type { Checkin, PanelConfigInfo, RealtimeEvent } from '@/lib/types'
import {
  browserNotify,
  faDateTime,
  requestNotifyPermission,
  useRealtimeSocket,
} from '@/lib/realtime'

type TabKey = 'overview' | 'checkins' | 'customers' | 'employees'

const TABS: { key: TabKey; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { key: 'overview', label: 'نمای کلی', icon: RadioTower },
  { key: 'checkins', label: 'چک‌این‌ها', icon: CalendarCheck },
  { key: 'customers', label: 'مشتریان', icon: DoorOpen },
  { key: 'employees', label: 'کارمندان', icon: LogOut },
]

export default function Dashboard({ cfg, onLogout }: { cfg: PanelConfigInfo; onLogout: () => void }) {
  const { toast } = useToast()
  const [tab, setTab] = useState<TabKey>('overview')
  const [refreshKey, setRefreshKey] = useState(0)
  const [connected, setConnected] = useState(false)
  const [live, setLive] = useState<Checkin[]>([]) // realtime events stream
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const bumpRefresh = useCallback(() => {
    if (refreshTimer.current) clearTimeout(refreshTimer.current)
    refreshTimer.current = setTimeout(() => setRefreshKey((k) => k + 1), 400)
  }, [])

  useEffect(() => {
    requestNotifyPermission()
  }, [])

  const onEvent = useCallback(
    (event: string, payload: unknown) => {
      if (event === 'hello') {
        setConnected(true)
        return
      }
      const p = payload as RealtimeEvent
      if (event === 'checkin' || event === 'checkout' || event === 'proximity') {
        const title =
          event === 'checkin'
            ? `چک‌این: ${p.employeeName}`
            : event === 'checkout'
              ? `چک‌اوت: ${p.employeeName}`
              : `حضور در محدوده: ${p.employeeName}`
        const body = `${p.customerName ?? ''}${p.notePreview ? ' — ' + p.notePreview : ''}`.trim()
        toast({ title, description: body || undefined })
        browserNotify(title, body)
        if (event !== 'proximity') bumpRefresh()
      } else if (event === 'notification') {
        toast({ title: String(p.title || 'اعلان جدید'), description: String(p.body || '') })
        browserNotify(String(p.title || 'اعلان'), String(p.body || ''))
        bumpRefresh()
      } else if (event === 'customer-added') {
        toast({ title: `مشتری جدید توسط ${p.by}`, description: String(p.name || '') })
        bumpRefresh()
      }
    },
    [toast, bumpRefresh],
  )

  useRealtimeSocket(cfg, onEvent)

  async function logout() {
    await fetch('/api/logout', { method: 'POST' })
    onLogout()
  }

  return (
    <div className="min-h-screen flex flex-col bg-gradient-to-b from-teal-50/60 to-background">
      {/* Header */}
      <header className="sticky top-0 z-40 border-b bg-background/85 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-teal-600 font-bold text-white">
            M
          </div>
          <div className="flex-1">
            <h1 className="text-base font-bold leading-tight">MedPad — کنترل کارمندها</h1>
            <p className="text-xs text-muted-foreground">
              نسخه پنل {cfg.panelVersion} • نسخه اپ {cfg.apkVersion}
            </p>
          </div>
          <Badge
            variant="secondary"
            className={`gap-1 ${connected ? 'bg-emerald-100 text-emerald-800' : 'bg-gray-100 text-gray-600'}`}
          >
            <span
              className={`h-2 w-2 rounded-full ${connected ? 'bg-emerald-500 animate-pulse' : 'bg-gray-400'}`}
            />
            {connected ? 'Real-time متصل' : 'در حال اتصال…'}
          </Badge>
          <Button variant="ghost" size="icon" title="مرکز دانلود" onClick={() => window.open('/downloads.html', '_blank')}>
            <Download className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="icon" title="اعلان‌های مرورگر" onClick={requestNotifyPermission}>
            <Bell className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={logout}>
            خروج
          </Button>
        </div>
        {/* Tabs */}
        <div className="mx-auto flex max-w-7xl gap-1 overflow-x-auto px-4 pb-2">
          {TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm transition-colors ${
                tab === t.key ? 'bg-teal-600 text-white shadow-sm' : 'hover:bg-muted text-muted-foreground'
              }`}
            >
              <t.icon className="h-4 w-4" />
              {t.label}
            </button>
          ))}
        </div>
      </header>

      {/* Live event ticker */}
      {live.length > 0 && <div className="bg-amber-50 border-b px-4 py-1 text-xs">{faDateTime(live[0].createdAt)}</div>}

      {/* Content */}
      <main className="mx-auto w-full max-w-7xl flex-1 p-4">
        {tab === 'overview' && <OverviewTab refreshKey={refreshKey} />}
        {tab === 'checkins' && <CheckinsTab refreshKey={refreshKey} />}
        {tab === 'customers' && <CustomersTab refreshKey={refreshKey} />}
        {tab === 'employees' && <EmployeesTab refreshKey={refreshKey} />}
      </main>

      <footer className="mt-auto border-t bg-background/60 py-3 text-center text-xs text-muted-foreground">
        MedPad v{cfg.panelVersion} — سیستم کنترل کارمندها با گزارش صوتی و اعلان لحظه‌ای
      </footer>
    </div>
  )
}
