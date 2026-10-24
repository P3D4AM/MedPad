'use client'

import { Fragment, useEffect, useMemo, useRef } from 'react'
import { MapContainer, TileLayer, CircleMarker, Circle, Popup, useMapEvents } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'

export interface MapPoint {
  id: string
  name: string
  lat: number
  lng: number
  radius: number
  color?: string
}

interface MapViewProps {
  points: MapPoint[]
  center?: [number, number]
  zoom?: number
  /** when set: map click moves this customer's pin (panel edit mode) */
  pickMode?: { customerId: string; name: string } | null
  onPick?: (lat: number, lng: number) => void
  className?: string
}

function ClickHandler({
  pickMode,
  onPick,
}: {
  pickMode: MapViewProps['pickMode']
  onPick?: (lat: number, lng: number) => void
}) {
  useMapEvents({
    click(e) {
      if (pickMode && onPick) onPick(e.latlng.lat, e.latlng.lng)
    },
  })
  return null
}

/** Leaflet map — OSM tiles, RTL-friendly, geofence circles in meters */
export default function MapView({
  points,
  center,
  zoom = 12,
  pickMode = null,
  onPick,
  className = 'h-[420px] w-full',
}: MapViewProps) {
  const mapRef = useRef<L.Map | null>(null)

  const c = useMemo<[number, number]>(() => {
    if (center) return center
    if (points.length > 0) return [points[0].lat, points[0].lng]
    return [35.72, 51.4]
  }, [points, center])

  useEffect(() => {
    if (mapRef.current && pickMode) {
      mapRef.current.getContainer().style.cursor = 'crosshair'
    } else if (mapRef.current) {
      mapRef.current.getContainer().style.cursor = ''
    }
  }, [pickMode])

  return (
    <div className={className}>
      <MapContainer
        center={c}
        zoom={zoom}
        scrollWheelZoom
        style={{ height: '100%', width: '100%', borderRadius: 12 }}
        ref={(m) => {
          if (m) mapRef.current = m
        }}
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <ClickHandler pickMode={pickMode} onPick={onPick} />
        {points.map((p) => (
          <Fragment key={p.id}>
            <Circle
              center={[p.lat, p.lng]}
              radius={p.radius}
              pathOptions={{
                color: p.color ?? '#d97706',
                weight: 1,
                fillColor: p.color ?? '#f59e0b',
                fillOpacity: 0.08,
                dashArray: '4 4',
              }}
            />
            <CircleMarker
              center={[p.lat, p.lng]}
              radius={7}
              pathOptions={{
                color: p.color ?? '#0f766e',
                fillColor: p.color ?? '#0f766e',
                fillOpacity: 0.9,
              }}
            >
              <Popup>
                <div dir="rtl" className="text-sm">
                  <b>{p.name}</b>
                  <br />
                  شعاع: {p.radius} متر
                </div>
              </Popup>
            </CircleMarker>
          </Fragment>
        ))}
      </MapContainer>
    </div>
  )
}
