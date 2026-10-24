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
import { Link2, Plus, RefreshCw, Unlink, UserPlus } from 'lucide-react'
import type { Assignment, Customer, Employee } from '@/lib/types'

export default function EmployeesTab({ refreshKey }: { refreshKey: number }) {
  const [rows, setRows] = useState<Employee[]>([])
  const [customers, setCustomers] = useState<Customer[]>([])
  const [loading, setLoading] = useState(true)
  const [adding, setAdding] = useState(false)
  const [form, setForm] = useState({ username: '', password: '', name: '', phone: '' })
  const [assignEmp, setAssignEmp] = useState<Employee | null>(null)
  const [assignCus, setAssignCus] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [e, c] = await Promise.all([
        fetch('/api/employees', { cache: 'no-store' }).then((r) => r.json()),
        fetch('/api/customers', { cache: 'no-store' }).then((r) => r.json()),
      ])
      if (e.ok) setRows(e.employees)
      if (c.ok) setCustomers(c.customers)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load, refreshKey])

  async function addEmployee() {
    setSaving(true)
    try {
      const res = await fetch('/api/employees', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(form),
      })
      const data = await res.json()
      if (data.ok) {
        setAdding(false)
        setForm({ username: '', password: '', name: '', phone: '' })
        await load()
      } else alert(data.error || 'خطا')
    } finally {
      setSaving(false)
    }
  }

  async function addAssignment() {
    if (!assignEmp || !assignCus) return
    setSaving(true)
    try {
      const res = await fetch('/api/assignments', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ employeeId: assignEmp.id, customerId: assignCus }),
      })
      const data = await res.json()
      if (!data.ok && res.status !== 409) alert(data.error || 'خطا')
      setAssignEmp(null)
      setAssignCus('')
      await load()
    } finally {
      setSaving(false)
    }
  }

  async function removeAssignment(employeeId: string, customerId: string) {
    await fetch(`/api/assignments?employeeId=${employeeId}&customerId=${customerId}`, {
      method: 'DELETE',
    })
    await load()
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button size="sm" className="bg-teal-600 hover:bg-teal-700" onClick={() => setAdding(true)}>
          <UserPlus className="h-4 w-4" /> کارمند جدید
        </Button>
        <Button variant="outline" size="sm" onClick={load} disabled={loading}>
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        </Button>
        <span className="text-sm text-muted-foreground mr-auto">
          {rows.length.toLocaleString('fa-IR')} کارمند فعال
        </span>
      </div>

      <ScrollArea className="h-[62vh] rounded-xl border">
        <div className="divide-y mp-scroll">
          {rows.map((e) => (
            <div key={e.id} className="p-4">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="font-medium">{e.name}</span>
                <Badge variant="outline" dir="ltr">
                  {e.username}
                </Badge>
                {e.phone && <span className="text-xs text-muted-foreground" dir="ltr">{e.phone}</span>}
                {!e.active && <Badge variant="destructive">غیرفعال</Badge>}
                <span className="text-xs text-muted-foreground">
                  {e._count?.checkins ?? 0} چک‌این
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="mr-auto"
                  onClick={() => {
                    setAssignEmp(e)
                    setAssignCus('')
                  }}
                >
                  <Link2 className="h-4 w-4 text-teal-600" /> انتساب مشتری
                </Button>
              </div>
              {e.assignments && e.assignments.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {e.assignments.map((a) => (
                    <Badge key={a.id} variant="secondary" className="gap-1 py-1">
                      {a.customer.name}
                      <button
                        onClick={() => removeAssignment(e.id, a.customer.id)}
                        title="حذف انتساب"
                        className="text-destructive hover:text-destructive/80"
                      >
                        <Unlink className="h-3 w-3" />
                      </button>
                    </Badge>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </ScrollArea>

      {/* Add employee dialog */}
      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent dir="rtl" className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>افزودن کارمند</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>نام و نام خانوادگی</Label>
                <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label>نام کاربری</Label>
                <Input dir="ltr" className="text-left" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>رمز عبور</Label>
                <Input dir="ltr" className="text-left" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label>تلفن</Label>
                <Input dir="ltr" className="text-left" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAdding(false)}>
              انصراف
            </Button>
            <Button className="bg-teal-600 hover:bg-teal-700" onClick={addEmployee} disabled={saving}>
              ذخیره
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Assign dialog */}
      <Dialog open={assignEmp !== null} onOpenChange={(o) => !o && setAssignEmp(null)}>
        <DialogContent dir="rtl" className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>انتساب مشتری به {assignEmp?.name}</DialogTitle>
          </DialogHeader>
          <div className="space-y-1">
            <Label>مشتری</Label>
            <Select value={assignCus} onValueChange={setAssignCus}>
              <SelectTrigger>
                <SelectValue placeholder="انتخاب کنید…" />
              </SelectTrigger>
              <SelectContent>
                {customers.map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {c.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAssignEmp(null)}>
              انصراف
            </Button>
            <Button className="bg-teal-600 hover:bg-teal-700" onClick={addAssignment} disabled={saving || !assignCus}>
              <Plus className="h-4 w-4" /> انتساب
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
