'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { LogIn, ShieldCheck } from 'lucide-react'

export default function LoginScreen({ onLogin }: { onLogin: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })
      const data = await res.json()
      if (data.ok) onLogin()
      else setError(data.error || 'خطا در ورود')
    } catch {
      setError('ارتباط با سرور برقرار نشد')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-teal-50 via-background to-amber-50 p-4">
      <Card className="w-full max-w-sm shadow-lg">
        <CardHeader className="text-center">
          <div className="mx-auto mb-2 flex h-14 w-14 items-center justify-center rounded-2xl bg-teal-600 text-white">
            <ShieldCheck className="h-8 w-8" />
          </div>
          <CardTitle className="text-xl">پنل مدیریت MedPad</CardTitle>
          <CardDescription>سیستم کنترل کارمندها — نسخه ۱.۹.۳</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="u">نام کاربری مدیر</Label>
              <Input
                id="u"
                dir="ltr"
                className="text-left"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="admin"
                autoComplete="username"
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="p">رمز عبور</Label>
              <Input
                id="p"
                dir="ltr"
                className="text-left"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                autoComplete="current-password"
                required
              />
            </div>
            {error && (
              <p className="text-sm text-destructive bg-destructive/10 rounded-md px-3 py-2">{error}</p>
            )}
            <Button type="submit" className="w-full bg-teal-600 hover:bg-teal-700" disabled={busy}>
              <LogIn className="ml-2 h-4 w-4" />
              {busy ? 'در حال ورود…' : 'ورود به پنل'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
