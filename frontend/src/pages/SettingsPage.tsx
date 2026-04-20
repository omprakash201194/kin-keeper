import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/hooks/useAuth'
import { Key, User, Cloud, MessageSquare, LineChart, BellRing } from 'lucide-react'
import apiClient from '@/services/api'
import {
  getPushConfig, isPushSupported, currentSubscription,
  subscribeToPush, unsubscribeFromPush, sendTestNotification,
} from '@/lib/push'

type ModelUsage = {
  model: string
  calls: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  estimatedCostCents: number
}

type MonthSummary = {
  month: string
  totalCalls: number
  totalInputTokens: number
  totalOutputTokens: number
  totalCacheReadTokens: number
  totalCacheWriteTokens: number
  estimatedCostCents: number
  byModel: ModelUsage[]
}

type UsageReport = {
  currentMonth: MonthSummary
  lifetimeCalls: number
  lifetimeInputTokens: number
  lifetimeOutputTokens: number
  lifetimeCacheReadTokens: number
  lifetimeCacheWriteTokens: number
  lifetimeEstimatedCostCents: number
}

function fmtCents(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`
}

function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

export default function SettingsPage() {
  const { user } = useAuth()
  const [apiKey, setApiKey] = useState('')
  const [hasApiKey, setHasApiKey] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const [driveConnected, setDriveConnected] = useState(false)
  const [driveConfigured, setDriveConfigured] = useState(true)
  const [driveWorking, setDriveWorking] = useState(false)

  const [chatRetentionDays, setChatRetentionDays] = useState<number>(7)
  const [chatRetentionInput, setChatRetentionInput] = useState<string>('7')
  const [savingRetention, setSavingRetention] = useState(false)

  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const [usage, setUsage] = useState<UsageReport | null>(null)

  const [pushSupported, setPushSupported] = useState(false)
  const [pushServerEnabled, setPushServerEnabled] = useState(false)
  const [pushSubscribed, setPushSubscribed] = useState(false)
  const [pushPublicKey, setPushPublicKey] = useState('')
  const [pushWorking, setPushWorking] = useState(false)

  useEffect(() => {
    // Handle redirect-back from the Drive OAuth callback
    const params = new URLSearchParams(window.location.search)
    if (params.get('driveConnected') === '1') {
      setStatus('Google Drive connected.')
      window.history.replaceState({}, '', window.location.pathname)
    } else if (params.get('driveError')) {
      setError(`Drive connect failed: ${params.get('driveError')}`)
      window.history.replaceState({}, '', window.location.pathname)
    }
    void refresh()
  }, [])

  async function refresh() {
    try {
      const [settingsRes, driveRes, usageRes] = await Promise.all([
        apiClient.get<{ hasApiKey: boolean; chatRetentionDays: number }>('/settings'),
        apiClient.get<{ connected: boolean; configured: boolean }>('/drive/status'),
        apiClient.get<UsageReport>('/settings/usage').catch(() => null),
      ])
      setHasApiKey(settingsRes.data.hasApiKey)
      setChatRetentionDays(settingsRes.data.chatRetentionDays)
      setChatRetentionInput(String(settingsRes.data.chatRetentionDays))
      setDriveConnected(driveRes.data.connected)
      setDriveConfigured(driveRes.data.configured)
      if (usageRes) setUsage(usageRes.data)
      // Push status — best effort; older browsers / non-PWA contexts may reject.
      try {
        const supported = await isPushSupported()
        setPushSupported(supported)
        if (supported) {
          const cfg = await getPushConfig()
          setPushServerEnabled(cfg.enabled)
          setPushPublicKey(cfg.publicKey)
          const current = await currentSubscription()
          setPushSubscribed(!!current)
        }
      } catch {
        setPushSupported(false)
      }
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load settings')
    } finally {
      setLoading(false)
    }
  }

  async function handlePushToggle(next: boolean) {
    setPushWorking(true)
    setError(null)
    setStatus(null)
    try {
      if (next) {
        if (!pushPublicKey) throw new Error('Server has no VAPID key configured')
        await subscribeToPush(pushPublicKey)
        setPushSubscribed(true)
        setStatus('Push notifications enabled on this device.')
      } else {
        await unsubscribeFromPush()
        setPushSubscribed(false)
        setStatus('Push notifications disabled on this device.')
      }
    } catch (e: any) {
      setError(e?.message ?? 'Failed to change push subscription')
    } finally {
      setPushWorking(false)
    }
  }

  async function handlePushTest() {
    setPushWorking(true)
    setError(null)
    setStatus(null)
    try {
      const sent = await sendTestNotification()
      setStatus(sent > 0
        ? `Sent to ${sent} device${sent === 1 ? '' : 's'} — you should see a notification.`
        : 'No subscribed devices yet. Enable push on this device first.')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Test push failed')
    } finally {
      setPushWorking(false)
    }
  }

  async function handleSaveKey() {
    if (!apiKey.trim()) return
    setSaving(true)
    setError(null)
    setStatus(null)
    try {
      await apiClient.put('/settings/api-key', { apiKey: apiKey.trim() })
      setApiKey('')
      setHasApiKey(true)
      setStatus('API key saved.')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to save key')
    } finally {
      setSaving(false)
    }
  }

  async function handleDeleteKey() {
    if (!confirm('Remove your Claude API key? The AI chat assistant will stop working until you add one again.')) {
      return
    }
    setDeleting(true)
    setError(null)
    setStatus(null)
    try {
      await apiClient.delete('/settings/api-key')
      setHasApiKey(false)
      setStatus('API key removed.')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to remove key')
    } finally {
      setDeleting(false)
    }
  }

  async function handleSaveRetention() {
    const days = Number(chatRetentionInput)
    if (!Number.isInteger(days) || days < 1 || days > 90) {
      setError('Chat retention must be a whole number between 1 and 90.')
      return
    }
    if (days === chatRetentionDays) return
    setSavingRetention(true)
    setError(null)
    setStatus(null)
    try {
      await apiClient.put('/settings/chat-retention', { days })
      setChatRetentionDays(days)
      setStatus(`Chat retention set to ${days} day${days === 1 ? '' : 's'}.`)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to save retention')
    } finally {
      setSavingRetention(false)
    }
  }

  async function handleConnectDrive() {
    setDriveWorking(true)
    setError(null)
    setStatus(null)
    try {
      const res = await apiClient.get<{ url: string }>('/drive/connect')
      window.location.href = res.data.url
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to start Drive connect')
      setDriveWorking(false)
    }
  }

  async function handleDisconnectDrive() {
    if (!confirm('Disconnect Google Drive? Existing documents stay in your Drive but Kin-Keeper can no longer upload or read them.')) {
      return
    }
    setDriveWorking(true)
    setError(null)
    setStatus(null)
    try {
      await apiClient.delete('/drive/connect')
      setDriveConnected(false)
      setStatus('Google Drive disconnected.')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to disconnect Drive')
    } finally {
      setDriveWorking(false)
    }
  }

  return (
    <div className="p-6 max-w-2xl">
      <h1 className="text-xl font-semibold mb-6">Settings</h1>

      {/* Profile */}
      <section className="mb-8">
        <h2 className="flex items-center gap-2 text-lg font-medium mb-4">
          <User className="w-5 h-5" />
          Profile
        </h2>
        <div className="rounded-lg border p-4 space-y-2">
          <div className="flex justify-between text-sm">
            <span className="text-muted-foreground">Name</span>
            <span>{user?.displayName}</span>
          </div>
          <div className="flex justify-between text-sm">
            <span className="text-muted-foreground">Email</span>
            <span>{user?.email}</span>
          </div>
        </div>
      </section>

      {/* Google Drive */}
      <section className="mb-8">
        <h2 className="flex items-center gap-2 text-lg font-medium mb-4">
          <Cloud className="w-5 h-5" />
          Google Drive
        </h2>
        <p className="text-sm text-muted-foreground mb-4">
          Kin-Keeper stores documents in your own Google Drive under a folder called "Kin-Keeper".
          We only access files we create — never anything else.
        </p>

        {!loading && !driveConfigured && (
          <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-300">
            Server-side Google OAuth is not configured yet. Ask the admin to set up credentials.
          </div>
        )}

        {!loading && driveConfigured && (
          driveConnected ? (
            <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-4 py-3 text-sm flex items-center justify-between">
              <span className="text-emerald-300">Connected to Google Drive.</span>
              <Button variant="outline" size="sm" onClick={handleDisconnectDrive} disabled={driveWorking}>
                {driveWorking ? 'Working…' : 'Disconnect'}
              </Button>
            </div>
          ) : (
            <Button onClick={handleConnectDrive} disabled={driveWorking}>
              {driveWorking ? 'Redirecting…' : 'Connect Google Drive'}
            </Button>
          )
        )}
      </section>

      {/* Chat retention */}
      <section className="mb-8">
        <h2 className="flex items-center gap-2 text-lg font-medium mb-4">
          <MessageSquare className="w-5 h-5" />
          Chat History
        </h2>
        <p className="text-sm text-muted-foreground mb-4">
          Chat sessions are kept for this many days after the last message. Older chats are
          automatically deleted. Minimum 1, maximum 90.
        </p>
        <div className="flex items-center gap-2 max-w-xs">
          <input
            type="number"
            min={1}
            max={90}
            value={chatRetentionInput}
            onChange={(e) => setChatRetentionInput(e.target.value)}
            className="w-24 rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <span className="text-sm text-muted-foreground">days</span>
          <Button
            onClick={handleSaveRetention}
            disabled={savingRetention || Number(chatRetentionInput) === chatRetentionDays}
            size="sm"
          >
            {savingRetention ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </section>

      {/* Claude API Key */}
      <section className="mb-8">
        <h2 className="flex items-center gap-2 text-lg font-medium mb-4">
          <Key className="w-5 h-5" />
          Claude API Key
        </h2>
        <p className="text-sm text-muted-foreground mb-4">
          Provide your own Claude API key to enable the AI chat assistant.
          Your key is encrypted and stored securely. Get one at console.anthropic.com.
        </p>

        {!loading && hasApiKey && (
          <div className="mb-4 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-4 py-3 text-sm flex items-center justify-between">
            <span className="text-emerald-300">A key is saved. Enter a new one below to replace it.</span>
            <Button variant="outline" size="sm" onClick={handleDeleteKey} disabled={deleting}>
              {deleting ? 'Removing…' : 'Remove'}
            </Button>
          </div>
        )}

        <div className="flex gap-2">
          <input
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="sk-ant-..."
            className="flex-1 rounded-lg border border-input bg-background px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <Button onClick={handleSaveKey} disabled={saving || !apiKey.trim()}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </section>

      {/* Push notifications */}
      <section className="mb-8">
        <h2 className="flex items-center gap-2 text-lg font-medium mb-4">
          <BellRing className="w-5 h-5" />
          Push notifications
        </h2>
        <p className="text-sm text-muted-foreground mb-4">
          Get a daily nudge on this device for overdue and upcoming reminders.
          Install Kin-Keeper as a PWA first — browser tabs can't receive push.
        </p>
        {!pushSupported ? (
          <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-300">
            This browser doesn't support web push. Try installing Kin-Keeper
            as an app (Add to Home Screen) on mobile Chrome / Safari / Edge.
          </div>
        ) : !pushServerEnabled ? (
          <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-300">
            Server-side VAPID keys aren't configured yet. Ask the admin to set
            <code className="mx-1">WEBPUSH_PUBLIC_KEY</code> and
            <code className="mx-1">WEBPUSH_PRIVATE_KEY</code>.
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex items-center gap-3">
              <Button
                onClick={() => handlePushToggle(!pushSubscribed)}
                disabled={pushWorking}
                variant={pushSubscribed ? 'outline' : 'default'}
              >
                {pushWorking
                  ? 'Working…'
                  : pushSubscribed ? 'Disable on this device' : 'Enable on this device'}
              </Button>
              {pushSubscribed && (
                <Button onClick={handlePushTest} variant="ghost" size="sm" disabled={pushWorking}>
                  Send test notification
                </Button>
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              The daily digest fires at 07:00 UTC (12:30 IST). Each enabled device
              gets one summary per day; no per-reminder spam.
            </p>
          </div>
        )}
      </section>

      {/* API usage */}
      {hasApiKey && usage && (
        <section className="mb-8">
          <h2 className="flex items-center gap-2 text-lg font-medium mb-4">
            <LineChart className="w-5 h-5" />
            Claude API usage
          </h2>
          <p className="text-sm text-muted-foreground mb-4">
            Self-metered from each call's <code>usage</code> block. Cost is an estimate
            using current per-million-token pricing — check your Anthropic console for
            the authoritative bill.
          </p>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
            <UsageTile label={`This month (${usage.currentMonth.month})`}
                       value={fmtCents(usage.currentMonth.estimatedCostCents)}
                       sub={`${usage.currentMonth.totalCalls} call${usage.currentMonth.totalCalls === 1 ? '' : 's'}`} />
            <UsageTile label="Input tokens"
                       value={fmtTokens(usage.currentMonth.totalInputTokens)}
                       sub={usage.currentMonth.totalCacheReadTokens > 0
                         ? `+ ${fmtTokens(usage.currentMonth.totalCacheReadTokens)} cached`
                         : undefined} />
            <UsageTile label="Output tokens"
                       value={fmtTokens(usage.currentMonth.totalOutputTokens)} />
            <UsageTile label="Lifetime"
                       value={fmtCents(usage.lifetimeEstimatedCostCents)}
                       sub={`${usage.lifetimeCalls} call${usage.lifetimeCalls === 1 ? '' : 's'}`} />
          </div>

          {usage.currentMonth.byModel.length > 0 && (
            <div className="border rounded-md overflow-hidden">
              <div className="px-3 py-2 bg-muted/30 text-xs uppercase tracking-wide text-muted-foreground">
                This month by model
              </div>
              <ul className="divide-y text-sm">
                {usage.currentMonth.byModel.map((m) => (
                  <li key={m.model} className="px-3 py-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
                    <code className="text-xs">{m.model}</code>
                    <span className="text-muted-foreground text-xs">
                      in {fmtTokens(m.inputTokens)} · out {fmtTokens(m.outputTokens)}
                      {m.cacheReadTokens > 0 && ` · cached ${fmtTokens(m.cacheReadTokens)}`}
                    </span>
                    <span className="ml-auto font-medium">{fmtCents(m.estimatedCostCents)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      {status && <p className="mt-3 text-sm text-emerald-700">{status}</p>}
      {error && <p className="mt-3 text-sm text-red-400">{error}</p>}
    </div>
  )
}

function UsageTile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="border rounded-md p-3">
      <p className="text-xs text-muted-foreground uppercase tracking-wide">{label}</p>
      <p className="text-lg font-semibold mt-0.5">{value}</p>
      {sub && <p className="text-[11px] text-muted-foreground mt-0.5">{sub}</p>}
    </div>
  )
}
