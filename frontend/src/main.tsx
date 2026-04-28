import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { registerSW } from 'virtual:pwa-register'
import './index.css'
import App from './App'

// reason: when a new build ships, force-reload open tabs instead of leaving
// them running the cached JS bundle. Without this, users keep seeing the
// old UI until they manually hard-refresh — the "upload does nothing" bug
// reported 2026-04-20 was caused by exactly this mismatch (old bundle
// running against a newer SW / backend).
registerSW({
  immediate: true,
  onNeedRefresh() {
    // A new SW is waiting. skipWaiting() in the SW already told it to take
    // over; a reload swaps the JS out from under us so we stop running stale
    // code.
    window.location.reload()
  },
})

// reason: belt-and-braces — the new SW also posts KIN_KEEPER_NEW_VERSION
// to every client when it activates, so even bundles that registered the
// SW through the legacy registerSW.js (and therefore don't get
// onNeedRefresh fired) still get a chance to reload. Once we hear it,
// reload exactly once per page lifetime.
if ('serviceWorker' in navigator) {
  let reloadedFromMessage = false
  navigator.serviceWorker.addEventListener('message', (event) => {
    if (event.data?.type === 'KIN_KEEPER_NEW_VERSION' && !reloadedFromMessage) {
      reloadedFromMessage = true
      window.location.reload()
    }
  })
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, staleTime: 30_000 },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>
)
