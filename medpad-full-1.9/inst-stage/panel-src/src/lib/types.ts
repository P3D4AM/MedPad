export interface Employee {
  id: string
  username: string
  name: string
  phone?: string | null
  active: boolean
  createdAt: string
  assignments?: { id: string; customer: { id: string; name: string } }[]
  _count?: { checkins: number }
}

export interface Customer {
  id: string
  name: string
  type: string
  address?: string | null
  phone?: string | null
  lat: number
  lng: number
  radius: number
  updatedAt: string
  createdAt: string
  assignments?: { id: string; employee: { id: string; name: string; username: string } }[]
  _count?: { checkins: number }
}

export interface Checkin {
  id: string
  localId?: string | null
  employeeId: string
  employee: { id: string; name: string; username: string }
  customerId?: string | null
  customer?: { id: string; name: string } | null
  kind: 'checkin' | 'checkout'
  note?: string | null
  voicePath?: string | null
  lat?: number | null
  lng?: number | null
  createdAt: string
}

export interface Assignment {
  id: string
  employee: { id: string; name: string; username: string }
  customer: { id: string; name: string }
}

export interface PanelConfig {
  panelVersion: string
  apkVersion: string
  socketMode: 'gateway' | 'direct'
}

export interface RealtimeEvent {
  id?: string
  employeeName?: string
  customerName?: string
  notePreview?: string
  hasVoice?: boolean
  voiceUrl?: string | null
  at?: string
  [k: string]: unknown
}
