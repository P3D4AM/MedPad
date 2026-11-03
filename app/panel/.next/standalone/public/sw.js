/* MedPad panel — Service Worker (phase 4: browser Push)
 *
 * «اعلان‌های مهم ... در زمان بسته‌بودن مرورگر با Push + Service Worker»
 *
 * Responsibilities (deliberately dependency-free — no Workbox):
 *  - push: decrypt+show the admin's system notification. DEDUP with the
 *    open page: when a visible AND focused panel window exists, the page
 *    already surfaced the realtime toast — the SW stays silent, so the
 *    admin never sees the same event twice («عدم ارسال تکراری»).
 *  - notificationclick: focus an existing panel window (or open one) and
 *    land on the tab the notification is about («کلیک روی اعلان صفحهٔ
 *    مربوط را باز کند») — the payload's url is /#tab=... which the
 *    Dashboard reads on load.
 *
 * The notification payload (JSON from src/lib/push.ts):
 *   { title, body, url, eventKey }   — eventKey doubles as the
 *   notification tag, so a re-delivered push REPLACES its own toast
 *   instead of stacking.
 */
const FALLBACK_TITLE = 'اعلان مدرپد'

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch (e) {
    data = {}
  }
  const d = data || {}
  const title = typeof d.title === 'string' && d.title ? d.title : FALLBACK_TITLE
  const body = typeof d.body === 'string' ? d.body : ''
  const url = typeof d.url === 'string' && d.url ? d.url : '/'
  const tag = typeof d.eventKey === 'string' && d.eventKey ? d.eventKey : 'medpad'
  const options = {
    body: body,
    dir: 'rtl',
    lang: 'fa',
    tag: tag,
    renotify: false,
    data: { url: url },
  }
  event.waitUntil(
    (async () => {
      const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      const hasVisibleFocused = wins.some((w) => w.visibilityState === 'visible' && w.focused)
      if (!hasVisibleFocused) {
        await self.registration.showNotification(title, options)
      }
    })(),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = (event.notification.data && event.notification.data.url) || '/'
  event.waitUntil(
    (async () => {
      const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      for (const w of wins) {
        if ('focus' in w) {
          try {
            await w.focus()
          } catch (e) {
            /* focus can reject for cross-origin leftovers — ignore */
          }
          if (url !== '/') {
            try {
              await w.navigate(url)
            } catch (e) {
              /* navigate can reject while the window is loading — the admin
                 still lands on the panel; acceptable */
            }
          }
          return
        }
      }
      await self.clients.openWindow(url)
    })(),
  )
})
