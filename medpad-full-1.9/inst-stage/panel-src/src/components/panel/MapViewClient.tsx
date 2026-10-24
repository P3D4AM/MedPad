'use client'

/**
 * Client-only wrapper — Leaflet touches `window` at import time,
 * so it must never be evaluated during SSR.
 */
import dynamic from 'next/dynamic'

const MapView = dynamic(() => import('./MapView'), {
  ssr: false,
  loading: () => (
    <div className="flex h-[420px] w-full items-center justify-center rounded-xl border bg-muted/40 text-sm text-muted-foreground">
      در حال بارگذاری نقشه…
    </div>
  ),
})

export default MapView
export type { MapPoint } from './MapView'
