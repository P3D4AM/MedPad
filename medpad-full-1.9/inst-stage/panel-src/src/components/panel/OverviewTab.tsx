'use client'

import { useEffect, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Briefcase,
  CalendarCheck,
  DoorOpen,
  MapPin,
  Radar,
  Users,
} from 'lucide-react'
import { faDateTime } from '@/lib/realtime'

interface Stats {
  employees: number
  customers: number
  openVisits: number
  todayCheckins: number
  weekCheckins: number
}

interface Notif {
  id: string
  title: string
  body: string
  kind: string
  createdAt: string
  employee?: { name: string }
}

export default function OverviewTab({ refreshKey }: { refreshKey: number }) {
  const [stats, setStats] = useState<Stats | null>(null)
  const [notifs, setNotifs] = useState<Notif[]>([])

  useEffect(() => {
    fetch('/api/stats', { cache: 'no-store' })
      .then((r) => r.json())
      .then((d) => d.ok && setStats(d))
      .catch(() => {})
    fetch('/api/notifications', { cache: 'no-store' })
      .then((r) => r.json())
      .then((d) => d.ok && setNotifs(d.notifications))
      .catch(() => {})
  }, [refreshKey])

  const cards = [
    { label: 'کارمندان فعال', value: stats?.employees, icon: Users, color: 'text-teal-600 bg-teal-50' },
    { label: 'مشتریان', value: stats?.customers, icon: Briefcase, color: 'text-sky-600 bg-sky-50' },
    { label: 'ویزیت‌های در جریان', value: stats?.openVisits, icon: DoorOpen, color: 'text-amber-600 bg-amber-50' },
    { label: 'چک‌این امروز', value: stats?.todayCheckins, icon: CalendarCheck, color: 'text-emerald-600 bg-emerald-50' },
    { label: 'چک‌این ۷ روز اخیر', value: stats?.weekCheckins, icon: Radar, color: 'text-violet-600 bg-violet-50' },
  ]

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
        {cards.map((c) => (
          <Card key={c.label} className="border/60">
            <CardContent className="p-4">
              <div className={`mx-auto mb-2 flex h-9 w-9 items-center justify-center rounded-xl ${c.color}`}>
                <c.icon className="h-5 w-5" />
              </div>
              <p className="text-center text-2xl font-bold">
                {c.value === undefined ? '…' : c.value.toLocaleString('fa-IR')}
              </p>
              <p className="text-center text-xs text-muted-foreground">{c.label}</p>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base flex items-center gap-2">
            <Radar className="h-4 w-4 text-amber-600" />
            آخرین رویدادهای اپ (نوتیفیکیشن‌ها)
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ScrollArea className="h-[40vh] rounded-lg">
            <div className="divide-y mp-scroll">
              {notifs.length === 0 && (
                <p className="p-6 text-center text-sm text-muted-foreground">
                  هنوز رویدادی ثبت نشده — وقتی بازاریاب وارد محدوده مشتری شود یا مشتری به او اختصاص یابد، اینجا نمایش داده می‌شود
                </p>
              )}
              {notifs.map((n) => (
                <div key={n.id} className="flex items-start gap-2 p-3">
                  <Badge
                    variant="secondary"
                    className={`shrink-0 ${
                      n.kind === 'proximity' ? 'bg-amber-100 text-amber-800' : 'bg-teal-100 text-teal-800'
                    }`}
                  >
                    {n.kind === 'proximity' ? 'حضور' : 'اختصاص'}
                  </Badge>
                  <div className="flex-1 text-sm">
                    <p>
                      {n.employee?.name ? `${n.employee.name}: ` : ''}
                      {n.body}
                    </p>
                    <p className="text-xs text-muted-foreground">{faDateTime(n.createdAt)}</p>
                  </div>
                </div>
              ))}
            </div>
          </ScrollArea>
        </CardContent>
      </Card>
    </div>
  )
}
