'use client'

import { useCallback, useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { MapPin, Pencil, Plus, RefreshCw, StickyNote } from 'lucide-react'
import MapViewClient, { type MapPoint } from './MapViewClient'
import type { Customer } from '@/lib/types'
import { faDateTime } from '@/lib/realtime'

const emptyForm = {
  name: '',
  type: 'doctor',
  address: '',
  phone: '',
  lat: 0,
  lng: 0,
  radius: 150,
}

export default function CustomersTab({ refreshKey }: { refreshKey: number }) {
  const [rows, setRows] = useState<Customer[]>([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<Customer | null>(null)
  const [editing, setEditing] = useState<Customer | null>(null)
  const [creating, setCreating] = useState(false)
  const [form, setForm] = useState(emptyForm)
  const [pickMode, setPickMode] = useState<{ customerId: string; name: string } | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/customers', { cache: 'no-store' })
      const data = await res.json()
      if (data.ok) setRows(data.customers)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load, refreshKey])

  const points: MapPoint[] = rows.map((c) => ({
    id: c.id,
    name: c.name,
    lat: c.lat,
    lng: c.lng,
    radius: c.radius,
  }))

  function openEdit(c: Customer) {
    setEditing(c)
    setForm({
      name: c.name,
      type: c.type,
      address: c.address || '',
      phone: c.phone || '',
      lat: c.lat,
      lng: c.lng,
      radius: c.radius,
    })
  }

  function openCreate() {
    setCreating(true)
    const p = selected ?? rows[0]
    setForm({
      ...emptyForm,
      lat: p ? p.lat + 0.002 : 35.72,
      lng: p ? p.lng + 0.002 : 51.4,
    })
  }

  async function save() {
    setSaving(true)
    try {
      const url = creating ? '/api/customers' : `/api/customers/${editing!.id}`
      const res = await fetch(url, {
        method: creating ? 'POST' : 'PUT',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(form),
      })
      const data = await res.json()
      if (data.ok) {
        setEditing(null)
        setCreating(false)
        await load()
      } else {
        alert(data.error || 'خطا در ذخیره')
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_420px]">
      {/* Map + pick hint */}
      <div className="space-y-2">
        {pickMode && (
          <div className="rounded-lg bg-amber-100 text-amber-900 px-3 py-2 text-sm">
            روی نقشه کلیک کنید تا موقعیت «{pickMode.name}» جابه‌جا شود
            <Button variant="ghost" size="sm" className="mr-2 h-7" onClick={() => setPickMode(null)}>
              لغو
            </Button>
          </div>
        )}
        <MapViewClient
          points={points}
          pickMode={pickMode}
          center={selected ? [selected.lat, selected.lng] : undefined}
          zoom={selected ? 15 : 12}
          onPick={async (lat, lng) => {
            if (!pickMode) return
            // creating new customer → just update form coordinates
            if (pickMode.customerId === '__new__') {
              setForm((f) => ({ ...f, lat, lng }))
              setPickMode(null)
              return
            }
            setSaving(true)
            try {
              const res = await fetch(`/api/customers/${pickMode.customerId}`, {
                method: 'PUT',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({ lat, lng }),
              })
              if (res.ok) {
                setPickMode(null)
                await load()
              }
            } finally {
              setSaving(false)
            }
          }}
        />
      </div>

      {/* List */}
      <div className="space-y-2">
        <div className="flex items-center gap-2">
          <Button size="sm" className="bg-teal-600 hover:bg-teal-700" onClick={openCreate}>
            <Plus className="h-4 w-4" /> مشتری جدید
          </Button>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          </Button>
          <span className="text-sm text-muted-foreground mr-auto">
            {rows.length.toLocaleString('fa-IR')} مشتری
          </span>
        </div>
        <ScrollArea className="h-[62vh] rounded-xl border">
          <div className="divide-y mp-scroll">
            {rows.map((c) => (
              <div
                key={c.id}
                onClick={() => setSelected(c)}
                className={`p-3 cursor-pointer transition-colors hover:bg-muted/60 ${
                  selected?.id === c.id ? 'bg-teal-50' : ''
                }`}
              >
                <div className="flex items-center gap-2">
                  <Badge variant="secondary" className="shrink-0">
                    {c.type === 'doctor' ? 'پزشک' : c.type === 'pharmacy' ? 'داروخانه' : 'سایر'}
                  </Badge>
                  <span className="font-medium flex-1">{c.name}</span>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8"
                    onClick={(e) => {
                      e.stopPropagation()
                      setPickMode({ customerId: c.id, name: c.name })
                    }}
                    title="جابه‌جایی روی نقشه"
                  >
                    <MapPin className="h-4 w-4 text-amber-600" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8"
                    onClick={(e) => {
                      e.stopPropagation()
                      openEdit(c)
                    }}
                    title="ویرایش"
                  >
                    <Pencil className="h-4 w-4 text-teal-600" />
                  </Button>
                </div>
                {c.address && <p className="mt-1 text-xs text-muted-foreground">{c.address}</p>}
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-muted-foreground">
                  <span>شعاع: {c.radius} متر</span>
                  {c.assignments && c.assignments.length > 0 && (
                    <span>
                      کارمندان: {c.assignments.map((a) => a.employee.name).join('، ')}
                    </span>
                  )}
                  {c._count && <span>چک‌این‌ها: {c._count.checkins}</span>}
                </div>
              </div>
            ))}
          </div>
        </ScrollArea>
        {selected && (
          <p className="text-xs text-muted-foreground flex items-center gap-1">
            <StickyNote className="h-3 w-3" />
            آخرین به‌روزرسانی: {faDateTime(selected.updatedAt)} — همین مقدار مبنای سینک LWW با اپ است
          </p>
        )}
      </div>

      {/* Edit / Create dialog */}
      <Dialog open={editing !== null || creating} onOpenChange={(o) => !o && (setEditing(null), setCreating(false))}>
        <DialogContent dir="rtl" className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{creating ? 'افزودن مشتری' : 'ویرایش مشتری'}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="space-y-1">
              <Label>نام</Label>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>نوع</Label>
                <Select value={form.type} onValueChange={(v) => setForm({ ...form, type: v })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="doctor">پزشک</SelectItem>
                    <SelectItem value="pharmacy">داروخانه</SelectItem>
                    <SelectItem value="clinic">کلینیک</SelectItem>
                    <SelectItem value="other">سایر</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>تلفن</Label>
                <Input dir="ltr" className="text-left" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
              </div>
            </div>
            <div className="space-y-1">
              <Label>آدرس</Label>
              <Input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div className="space-y-1">
                <Label>عرض (lat)</Label>
                <Input dir="ltr" className="text-left" type="number" step="any" value={form.lat} onChange={(e) => setForm({ ...form, lat: parseFloat(e.target.value) })} />
              </div>
              <div className="space-y-1">
                <Label>طول (lng)</Label>
                <Input dir="ltr" className="text-left" type="number" step="any" value={form.lng} onChange={(e) => setForm({ ...form, lng: parseFloat(e.target.value) })} />
              </div>
              <div className="space-y-1">
                <Label>شعاع (متر)</Label>
                <Input dir="ltr" className="text-left" type="number" value={form.radius} onChange={(e) => setForm({ ...form, radius: parseInt(e.target.value || '150', 10) })} />
              </div>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setPickMode({ customerId: creating ? '__new__' : editing!.id, name: form.name || 'مشتری جدید' })
              }}
            >
              <MapPin className="h-4 w-4 text-amber-600" />
              انتخاب موقعیت با کلیک روی نقشه
            </Button>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => (setEditing(null), setCreating(false))}>
              انصراف
            </Button>
            <Button className="bg-teal-600 hover:bg-teal-700" onClick={save} disabled={saving}>
              {saving ? 'در حال ذخیره…' : 'ذخیره'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
