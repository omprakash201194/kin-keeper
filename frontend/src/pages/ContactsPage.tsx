import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { UserPlus, Trash2, Phone, Mail } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'

type Contact = {
  id: string
  name: string
  relationship?: string
  phone?: string
  email?: string
  notes?: string
}

export default function ContactsPage() {
  const { isAdmin } = useProfile()
  const [contacts, setContacts] = useState<Contact[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [showAdd, setShowAdd] = useState(false)
  const [form, setForm] = useState({ name: '', relationship: '', phone: '', email: '', notes: '' })
  const [saving, setSaving] = useState(false)

  useEffect(() => { void load() }, [])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const res = await apiClient.get<Contact[]>('/contacts')
      setContacts(res.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load contacts')
    } finally { setLoading(false) }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!form.name.trim()) return
    setSaving(true)
    setError(null)
    try {
      await apiClient.post('/contacts', form)
      setForm({ name: '', relationship: '', phone: '', email: '', notes: '' })
      setShowAdd(false)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to save contact')
    } finally { setSaving(false) }
  }

  async function handleDelete(c: Contact) {
    if (!confirm(`Delete ${c.name}?`)) return
    try {
      await apiClient.delete(`/contacts/${c.id}`)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete')
    }
  }

  if (loading) return <div className="p-6 text-sm text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 max-w-2xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Contacts</h1>
          <p className="text-sm text-muted-foreground">
            External people whose documents you keep — lawyer, doctor, landlord, etc. They don't log in.
          </p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowAdd((v) => !v)}>
            <UserPlus className="w-4 h-4 mr-2" />
            {showAdd ? 'Cancel' : 'Add Contact'}
          </Button>
        )}
      </div>

      {showAdd && isAdmin && (
        <form onSubmit={handleSubmit} className="mb-6 space-y-3 border rounded-md p-4">
          <input className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Name"
                 value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          <input className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Relationship (e.g. Lawyer, Doctor)"
                 value={form.relationship} onChange={(e) => setForm({ ...form, relationship: e.target.value })} />
          <div className="grid grid-cols-2 gap-2">
            <input className="rounded-md border px-3 py-2 text-sm" placeholder="Phone"
                   value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
            <input className="rounded-md border px-3 py-2 text-sm" placeholder="Email" type="email"
                   value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          </div>
          <textarea className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Notes" rows={2}
                    value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
          <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save'}</Button>
        </form>
      )}

      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

      {contacts.length === 0 ? (
        <div className="py-12 text-center text-muted-foreground border rounded-md">No contacts yet.</div>
      ) : (
        <ul className="divide-y border rounded-md">
          {contacts.map((c) => (
            <li key={c.id} className="px-4 py-3 flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-medium">{c.name}
                  {c.relationship && <span className="text-muted-foreground font-normal"> · {c.relationship}</span>}
                </p>
                <div className="mt-0.5 flex gap-4 text-xs text-muted-foreground">
                  {c.phone && <span className="flex items-center gap-1"><Phone className="w-3 h-3" />{c.phone}</span>}
                  {c.email && <span className="flex items-center gap-1"><Mail className="w-3 h-3" />{c.email}</span>}
                </div>
                {c.notes && <p className="mt-1 text-xs text-muted-foreground">{c.notes}</p>}
              </div>
              {isAdmin && (
                <Button variant="ghost" size="icon" onClick={() => handleDelete(c)} title="Delete">
                  <Trash2 className="w-4 h-4" />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
