/// <reference lib="webworker" />
/* eslint-disable @typescript-eslint/no-explicit-any */
import { precacheAndRoute, cleanupOutdatedCaches } from 'workbox-precaching'
import { clientsClaim } from 'workbox-core'

declare let self: ServiceWorkerGlobalScope

// ---- App-shell precache (same behaviour as the old generateSW config) ----
precacheAndRoute(self.__WB_MANIFEST)
cleanupOutdatedCaches()

// reason: when a new SW ships, skipWaiting activates it immediately instead of
// waiting for every tab to close, and clientsClaim has it take over existing
// pages. Without these, installed users stay stuck on the previous SW.
self.skipWaiting()
clientsClaim()

// ---- Push notifications ----
//
// The backend sends a JSON payload of { title, body, link }. We render a
// notification with those fields; clicking it focuses an existing tab or
// opens the app at `link`. No data is persisted in the SW — the payload is
// read once and discarded.

self.addEventListener('push', (event: PushEvent) => {
  let data: { title?: string; body?: string; link?: string } = {}
  try {
    data = event.data ? (event.data.json() as any) : {}
  } catch {
    // Some push services deliver a bare keepalive with no body; treat as no-op.
    return
  }
  const title = data.title || 'Kin-Keeper'
  const options: NotificationOptions = {
    body: data.body || '',
    icon: '/pwa-192x192.png',
    badge: '/pwa-192x192.png',
    // reason: stash the link so notificationclick can route without reparsing.
    data: { link: data.link || '/' },
    // Collapse multiple pushes from the same tag into one visible banner.
    tag: 'kin-keeper-digest',
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event: NotificationEvent) => {
  event.notification.close()
  const link = (event.notification.data && event.notification.data.link) || '/'
  event.waitUntil((async () => {
    const allClients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    // Prefer focusing an existing Kin-Keeper tab so we don't spawn a second
    // instance every time a notification is tapped.
    for (const client of allClients) {
      const url = new URL(client.url)
      if (url.origin === self.location.origin) {
        await (client as WindowClient).focus()
        if (client.url !== self.location.origin + link) {
          await (client as WindowClient).navigate(link).catch(() => {})
        }
        return
      }
    }
    await self.clients.openWindow(link)
  })())
})
