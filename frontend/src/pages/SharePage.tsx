import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import apiClient from '@/services/api'
import { Shield, Loader2 } from 'lucide-react'

/**
 * PWA share target landing page.
 *
 * When a user shares text from another app on their phone (e.g. an SMS, a link,
 * an email body) and picks Kin-Keeper, the browser sends them here with the
 * shared fields as query params (see share_target config in vite.config.ts).
 * We glue the fields into a single message, create a chat session, fire the
 * message, and navigate to /chat so the agent reply lands on the normal chat
 * surface. Fire-and-forget so the share sheet doesn't sit waiting on the
 * network round-trip.
 *
 * No auth check here — ProtectedRoute wraps this page upstream, so an
 * unauthenticated user gets bounced to /login and comes back after sign-in.
 */
export default function SharePage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  // reason: iOS share sheet occasionally dispatches this page twice in quick
  // succession; guard so we don't create two chat sessions for one share.
  const fired = useRef(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (fired.current) return
    fired.current = true

    const title = (params.get('title') ?? '').trim()
    const text = (params.get('text') ?? '').trim()
    const url = (params.get('url') ?? '').trim()

    const combined = [
      title && title !== text ? title : null,
      text || null,
      url && !text.includes(url) ? url : null,
    ].filter(Boolean).join('\n\n').trim()

    if (!combined) {
      setError('Nothing was shared — try again from the source app.')
      return
    }

    ;(async () => {
      try {
        const session = await apiClient.post<{ id: string }>('/chat/sessions')
        void apiClient.post(`/chat/sessions/${session.data.id}/message`, {
          message: combined,
        })
        navigate('/chat', { replace: true })
      } catch (e: any) {
        setError(e?.response?.data?.error ?? 'Could not hand the shared content to the agent.')
      }
    })()
  }, [params, navigate])

  return (
    <div className="flex min-h-full items-center justify-center px-4">
      <div className="max-w-sm text-center space-y-3">
        <Shield className="w-10 h-10 text-emerald-400 mx-auto" />
        {error ? (
          <>
            <p className="text-sm text-red-300">{error}</p>
            <button
              onClick={() => navigate('/', { replace: true })}
              className="text-xs underline text-neutral-300"
            >
              Back to Home
            </button>
          </>
        ) : (
          <>
            <Loader2 className="w-5 h-5 animate-spin mx-auto text-neutral-300" />
            <p className="text-sm text-neutral-300">
              Handing what you shared to the Kin-Keeper agent…
            </p>
          </>
        )}
      </div>
    </div>
  )
}
