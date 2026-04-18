import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/hooks/useAuth'
import { Key, User, Cloud, MessageSquare } from 'lucide-react'
import apiClient from '@/services/api'

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
      const [settingsRes, driveRes] = await Promise.all([
        apiClient.get<{ hasApiKey: boolean; chatRetentionDays: number }>('/settings'),
        apiClient.get<{ connected: boolean; configured: boolean }>('/drive/status'),
      ])
      setHasApiKey(settingsRes.data.hasApiKey)
      setChatRetentionDays(settingsRes.data.chatRetentionDays)
      setChatRetentionInput(String(settingsRes.data.chatRetentionDays))
      setDriveConnected(driveRes.data.connected)
      setDriveConfigured(driveRes.data.configured)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load settings')
    } finally {
      setLoading(false)
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
          <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
            Server-side Google OAuth is not configured yet. Ask the admin to set up credentials.
          </div>
        )}

        {!loading && driveConfigured && (
          driveConnected ? (
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm flex items-center justify-between">
              <span className="text-emerald-900">Connected to Google Drive.</span>
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
          <div className="mb-4 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm flex items-center justify-between">
            <span className="text-emerald-900">A key is saved. Enter a new one below to replace it.</span>
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

      {status && <p className="mt-3 text-sm text-emerald-700">{status}</p>}
      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
    </div>
  )
}
