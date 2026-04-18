import { useEffect, useState } from 'react'
import apiClient from '@/services/api'
import { useAuth } from '@/hooks/useAuth'

/**
 * Polls /api/reminders/count so the sidebar badge reflects reminders that are
 * due within 7 days (including overdue). Polling is coarse (every 60s) — fine
 * for a personal app.
 */
export function useReminderCount() {
  const { user, loading } = useAuth()
  const [count, setCount] = useState(0)

  useEffect(() => {
    if (loading || !user) {
      setCount(0)
      return
    }
    let cancelled = false
    async function fetchOnce() {
      try {
        const res = await apiClient.get<{ dueSoon: number }>('/reminders/count')
        if (!cancelled) setCount(res.data.dueSoon ?? 0)
      } catch {
        /* swallow; badge just stays stale */
      }
    }
    void fetchOnce()
    const iv = setInterval(fetchOnce, 60_000)
    return () => { cancelled = true; clearInterval(iv) }
  }, [user, loading])

  return count
}
