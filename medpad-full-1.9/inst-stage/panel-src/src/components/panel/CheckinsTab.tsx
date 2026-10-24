'use client'

import { useCallback, useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Mic, Trash2, MapPin, LogIn, LogOut, RefreshCw } from 'lucide-react'
import type { Checkin } from '@/lib/types'
import { faDateTime } from '@/lib/realtime'

export default function CheckinsTab({ refreshKey }: { refreshKey: number }) {
  const [rows, setRows] = useState<Checkin[]>([])
  const [loading, setLoading] = useState(true)
  const [q, setQ] = useState('')
  const [kind, setKind] = useState<'all' | 'checkin' | 'checkout'>('all')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/checkins?limit=300', { cache: 'no-store' })
      const data = await res.json()
      if (data.ok) setRows(data.checkins)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load, refreshKey])

  const filtered = rows.filter((r) => {
    if (kind !== 'all' && r.kind !== kind) return false
    if (!q) return true
    const hay = `${r.employee?.name} ${r.customer?.name || ''} ${r.note || ''}`
    return hay.includes(q)
  })

  async function remove(id: string) {
    if (!confirm('این چک‌این حذف شود؟ (برای پاک‌کردن رکوردهای تکراری)')) return
    const res = await fetch(`/api/checkins/${id}`, { method: 'DELETE' })
    if (res.ok) setRows((rs) => rs.filter((r) => r.id !== id))
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          placeholder="جست‌وجو (نام کارمند/مشتری/متن)…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="max-w-xs"
        />
        <div className="flex rounded-lg border overflow-hidden">
          {(
            [
              ['all', 'همه'],
              ['checkin', 'چک‌این'],
              ['checkout', 'چک‌اوت'],
            ] as const
          ).map(([k, label]) => (
            <button
              key={k}
              onClick={() => setKind(k)}
              className={`px-3 py-1.5 text-sm transition-colors ${
                kind === k ? 'bg-teal-600 text-white' : 'bg-background hover:bg-muted'
              }`}
            >
              {label}
            </button>
          ))}
        </div>
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          به‌روزرسانی
        </Button>
        <span className="text-sm text-muted-foreground mr-auto">
          {filtered.length.toLocaleString('fa-IR')} رکورد
        </span>
      </div>

      <ScrollArea className="h-[62vh] rounded-xl border">
        <div className="divide-y mp-scroll">
          {filtered.length === 0 && !loading && (
            <p className="p-8 text-center text-muted-foreground">رکوردی یافت نشد</p>
          )}
          {filtered.map((r) => (
            <div key={r.id} className="flex flex-col gap-2 p-4 sm:flex-row sm:items-center">
              <div className="flex items-center gap-2 min-w-[150px]">
                {r.kind === 'checkin' ? (
                  <Badge className="bg-teal-600 hover:bg-teal-700 gap-1">
                    <LogIn className="h-3 w-3" /> چک‌این
                  </Badge>
                ) : (
                  <Badge className="bg-amber-600 hover:bg-amber-700 gap-1">
                    <LogOut className="h-3 w-3" /> چک‌اوت
                  </Badge>
                )}
                <span className="font-medium">{r.employee?.name}</span>
              </div>
              <div className="flex items-center gap-1 text-sm text-muted-foreground min-w-[170px]">
                <MapPin className="h-3.5 w-3.5" />
                {r.customer?.name || '—'}
              </div>
              <div className="flex-1 text-sm">
                {r.note ? (
                  <p className="line-clamp-2">{r.note}</p>
                ) : (
                  <p className="text-muted-foreground">بدون متن</p>
                )}
                <span className="text-xs text-muted-foreground">{faDateTime(r.createdAt)}</span>
              </div>
              <div className="flex items-center gap-2">
                {r.voicePath && (
                  <audio controls preload="none" src={`/api/media/${r.voicePath}`} className="h-8 max-w-[180px]" />
                )}
                {r.voicePath && <Mic className="h-4 w-4 text-teal-600 shrink-0" />}
                <Button variant="ghost" size="icon" onClick={() => remove(r.id)} title="حذف رکورد">
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      </ScrollArea>
    </div>
  )
}
