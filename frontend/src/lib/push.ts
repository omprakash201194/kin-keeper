import apiClient from '@/services/api'

/**
 * Push-notification plumbing. Wraps the browser Push API + our backend
 * subscribe/unsubscribe endpoints.
 *
 * Flow:
 *   1. `getPushConfig()` asks the server whether VAPID is configured.
 *   2. If so and the user toggles on:
 *      - request Notification.permission
 *      - navigator.serviceWorker.ready → subscribe with the VAPID public key
 *      - POST the subscription JSON to /api/push/subscribe
 *   3. Toggling off: call `unsubscribe()` on the browser sub + POST /unsubscribe.
 */

export type PushConfig = { enabled: boolean; publicKey: string }

export async function getPushConfig(): Promise<PushConfig> {
  const res = await apiClient.get<PushConfig>('/push/config')
  return res.data
}

export async function isPushSupported(): Promise<boolean> {
  return 'serviceWorker' in navigator
      && 'PushManager' in window
      && 'Notification' in window
}

export async function currentSubscription(): Promise<PushSubscription | null> {
  if (!(await isPushSupported())) return null
  const reg = await navigator.serviceWorker.getRegistration()
  if (!reg) return null
  return reg.pushManager.getSubscription()
}

export async function subscribeToPush(publicKey: string): Promise<PushSubscription> {
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    throw new Error('Notification permission denied')
  }
  const reg = await navigator.serviceWorker.ready
  // reason: an existing subscription may have been made with a stale VAPID key
  // (e.g. server reinstalled). Unsubscribe first so we always register with
  // the currently-advertised key.
  const existing = await reg.pushManager.getSubscription()
  if (existing) {
    const existingKey = extractServerKey(existing)
    if (existingKey && existingKey !== publicKey) {
      await existing.unsubscribe().catch(() => {})
    } else if (existingKey === publicKey) {
      // Already subscribed to the right key — just re-register with backend
      // in case the Firestore doc was lost and return.
      await registerWithBackend(existing)
      return existing
    }
  }
  const sub = await reg.pushManager.subscribe({
    userVisibleOnly: true,
    // TS's DOM lib 5.7+ tightened BufferSource to require ArrayBuffer over
    // SharedArrayBuffer, which the base64 decoder can't promise at type level.
    // The runtime value is always a plain Uint8Array.
    applicationServerKey: urlBase64ToUint8Array(publicKey) as BufferSource,
  })
  await registerWithBackend(sub)
  return sub
}

export async function unsubscribeFromPush(): Promise<void> {
  const sub = await currentSubscription()
  if (!sub) return
  await apiClient.post('/push/unsubscribe', { endpoint: sub.endpoint }).catch(() => {})
  await sub.unsubscribe().catch(() => {})
}

export async function sendTestNotification(): Promise<number> {
  const res = await apiClient.post<{ sent: number }>('/push/test')
  return res.data.sent
}

async function registerWithBackend(sub: PushSubscription): Promise<void> {
  const json = sub.toJSON() as PushSubscriptionJSON
  await apiClient.post('/push/subscribe', {
    endpoint: json.endpoint,
    keys: json.keys,
  })
}

function extractServerKey(sub: PushSubscription): string | null {
  const opts = sub.options
  if (!opts || !opts.applicationServerKey) return null
  const bytes = new Uint8Array(opts.applicationServerKey)
  return uint8ArrayToUrlBase64(bytes)
}

// VAPID public keys are URL-safe base64; the PushManager expects a Uint8Array.
function urlBase64ToUint8Array(base64: string): Uint8Array {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4)
  const standard = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(standard)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

function uint8ArrayToUrlBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i])
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
