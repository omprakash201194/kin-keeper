import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/hooks/useAuth'
import { Key, User } from 'lucide-react'

export default function SettingsPage() {
  const { user } = useAuth()
  const [apiKey, setApiKey] = useState('')

  const handleSaveApiKey = () => {
    // TODO: call PUT /api/settings/api-key
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
        <div className="flex gap-2">
          <input
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="sk-ant-..."
            className="flex-1 rounded-lg border border-input bg-background px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <Button onClick={handleSaveApiKey}>Save</Button>
        </div>
      </section>
    </div>
  )
}
