import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/hooks/useAuth'
import { Key, User } from 'lucide-react'
import apiClient from '@/services/api'

export default function SettingsPage() {
  const { user } = useAuth()
  const [apiKey, setApiKey] = useState('')
  const [hasApiKey, setHasApiKey] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    void refresh()
  }, [])

  async function refresh() {
    try {
      const res = await apiClient.get<{ hasApiKey: boolean }>('/settings')
      setHasApiKey(res.data.hasApiKey)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load settings')
    } finally {
      setLoading(false)
    }
  }

  async function handleSave() {
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

  async function handleDelete() {
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
            <Button variant="outline" size="sm" onClick={handleDelete} disabled={deleting}>
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
          <Button onClick={handleSave} disabled={saving || !apiKey.trim()}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>

        {status && <p className="mt-3 text-sm text-emerald-700">{status}</p>}
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      </section>
    </div>
  )
}
