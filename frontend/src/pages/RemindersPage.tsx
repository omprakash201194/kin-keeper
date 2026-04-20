import { useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Bell, Plus, Check, Trash2 } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'

type LinkType = 'MEMBER' | 'CONTACT' | 'HOME' | 'VEHICLE' | 'APPLIANCE' | 'POLICY' | 'DOCUMENT'
type LinkRef = { type: LinkType; id: string }

type Reminder = {
  id: string
  title: string
  notes?: string
  dueAt?: string
  recurrence?: 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY' | 'ODOMETER'
  recurrenceIntervalKm?: number
  dueOdometerKm?: number
  linkedRefs?: LinkRef[]
  completed: boolean
}

type Member = { id: string; name: string }
type Contact = { id: string; name: string }
type Asset = { id: string; type: LinkType; name: string; odometerKm?: number }
type DocumentRow = { id: string; fileName: string }
type ConversationRow = { id: string; title: string; occurredAt?: string; channel?: string }

type AssetLinkType = 'HOME' | 'VEHICLE' | 'APPLIANCE' | 'POLICY'

const EMPTY_FORM = {
  title: '',
  notes: '',
  dueAt: '',
  recurrence: 'NONE' as Reminder['recurrence'],
  recurrenceIntervalKm: '',
  dueOdometerKm: '',
  linkType: '' as '' | AssetLinkType,
  linkId: '',
}

export default function RemindersPage() {
  const { isAdmin } = useProfile()
  const [reminders, setReminders] = useState<Reminder[]>([])
  const [members, setMembers] = useState<Member[]>([])
  const [contacts, setContacts] = useState<Contact[]>([])
  const [assets, setAssets] = useState<Asset[]>([])
  const [documents, setDocuments] = useState<DocumentRow[]>([])
  const [convos, setConvos] = useState<ConversationRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [showAdd, setShowAdd] = useState(false)
  const [form, setForm] = useState(EMPTY_FORM)
  const [saving, setSaving] = useState(false)

  useEffect(() => { void load() }, [])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [r, m, c, a, d, cv] = await Promise.all([
        apiClient.get<Reminder[]>('/reminders'),
        apiClient.get<Member[]>('/family/members'),
        apiClient.get<Contact[]>('/contacts'),
        apiClient.get<Asset[]>('/assets'),
        apiClient.get<DocumentRow[]>('/documents'),
        apiClient.get<ConversationRow[]>('/conversations'),
      ])
      setReminders(r.data)
      setMembers(m.data)
      setContacts(c.data)
      setAssets(a.data)
      setDocuments(d.data)
      setConvos(cv.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load reminders')
    } finally { setLoading(false) }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!form.title.trim()) return
    if (!form.linkType || !form.linkId) {
      setError('Reminders must be linked to an asset. Pick a home, vehicle, appliance, or policy.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const body: Record<string, unknown> = {
        title: form.title.trim(),
        recurrence: form.recurrence,
        linkedRefs: [{ type: form.linkType, id: form.linkId }],
      }
      if (form.notes.trim()) body.notes = form.notes.trim()
      if (form.recurrence === 'ODOMETER') {
        body.dueOdometerKm = parseInt(form.dueOdometerKm || '0', 10)
        if (form.recurrenceIntervalKm) body.recurrenceIntervalKm = parseInt(form.recurrenceIntervalKm, 10)
      } else {
        if (!form.dueAt) throw new Error('Due date is required')
        body.dueAt = new Date(form.dueAt).toISOString()
      }
      await apiClient.post('/reminders', body)
      setForm(EMPTY_FORM)
      setShowAdd(false)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? e?.message ?? 'Failed to save reminder')
    } finally { setSaving(false) }
  }

  async function handleComplete(r: Reminder) {
    try {
      await apiClient.post(`/reminders/${r.id}/complete`)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to complete')
    }
  }

  async function handleDelete(r: Reminder) {
    if (!confirm(`Delete reminder "${r.title}"?`)) return
    try {
      await apiClient.delete(`/reminders/${r.id}`)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete')
    }
  }

  // Build a lookup so we can render LinkRef as name-with-type
  const linkLookup = useMemo(() => {
    const map = new Map<string, string>()
    for (const m of members) map.set(`MEMBER:${m.id}`, m.name)
    for (const c of contacts) map.set(`CONTACT:${c.id}`, c.name)
    for (const a of assets) map.set(`${a.type}:${a.id}`, a.name)
    for (const d of documents) map.set(`DOCUMENT:${d.id}`, d.fileName)
    for (const c of convos) map.set(`CONVERSATION:${c.id}`, c.title || 'Conversation')
    return map
  }, [members, contacts, assets, documents, convos])

  function linkLabel(ref: LinkRef): string {
    const name = linkLookup.get(`${ref.type}:${ref.id}`)
    if (name) return `${ref.type.toLowerCase()}: ${name}`
    // Fall back to a short id suffix instead of the full random string.
    const short = ref.id.length > 6 ? ref.id.slice(0, 6) + '…' : ref.id
    return `${ref.type.toLowerCase()}: ${short}`
  }

  const sortedReminders = useMemo(() => {
    const arr = [...reminders]
    arr.sort((a, b) => {
      // open ones first, then by dueAt asc
      if (a.completed !== b.completed) return a.completed ? 1 : -1
      const ad = a.dueAt ? new Date(a.dueAt).getTime() : Infinity
      const bd = b.dueAt ? new Date(b.dueAt).getTime() : Infinity
      return ad - bd
    })
    return arr
  }, [reminders])

  if (loading) return <div className="p-6 text-sm text-muted-foreground">Loading…</div>

  const now = new Date()
  const soon = new Date(now.getTime() + 7 * 24 * 3600 * 1000)

  return (
    <div className="p-6 max-w-2xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Reminders</h1>
          <p className="text-sm text-muted-foreground">
            Stay on top of renewals, warranty expiries, and service schedules.
          </p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowAdd((v) => !v)}>
            <Plus className="w-4 h-4 mr-2" />
            {showAdd ? 'Cancel' : 'Add Reminder'}
          </Button>
        )}
      </div>

      {showAdd && isAdmin && (
        <form onSubmit={handleSubmit} className="mb-6 space-y-3 border rounded-md p-4">
          <input className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Title" required
                 value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          <textarea className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Notes" rows={2}
                    value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />

          <select className="w-full rounded-md border px-3 py-2 text-sm"
                  value={form.recurrence}
                  onChange={(e) => setForm({ ...form, recurrence: e.target.value as any })}>
            <option value="NONE">One-off (no recurrence)</option>
            <option value="DAILY">Daily</option>
            <option value="WEEKLY">Weekly</option>
            <option value="MONTHLY">Monthly</option>
            <option value="QUARTERLY">Quarterly</option>
            <option value="HALF_YEARLY">Every 6 months</option>
            <option value="YEARLY">Yearly</option>
            <option value="ODOMETER">By vehicle odometer</option>
          </select>

          {form.recurrence === 'ODOMETER' ? (
            <div className="grid grid-cols-2 gap-2">
              <input className="rounded-md border px-3 py-2 text-sm" type="number"
                     placeholder="Due at odometer (km)" required
                     value={form.dueOdometerKm} onChange={(e) => setForm({ ...form, dueOdometerKm: e.target.value })} />
              <input className="rounded-md border px-3 py-2 text-sm" type="number"
                     placeholder="Interval (km) e.g. 5000"
                     value={form.recurrenceIntervalKm} onChange={(e) => setForm({ ...form, recurrenceIntervalKm: e.target.value })} />
            </div>
          ) : (
            <input className="w-full rounded-md border px-3 py-2 text-sm" type="datetime-local" required
                   value={form.dueAt} onChange={(e) => setForm({ ...form, dueAt: e.target.value })} />
          )}

          <div className="grid grid-cols-2 gap-2">
            <select className="rounded-md border px-3 py-2 text-sm" required
                    value={form.linkType}
                    onChange={(e) => setForm({ ...form, linkType: e.target.value as AssetLinkType, linkId: '' })}>
              <option value="">Asset type…</option>
              <option value="HOME">Home</option>
              <option value="VEHICLE">Vehicle</option>
              <option value="APPLIANCE">Appliance</option>
              <option value="POLICY">Policy</option>
            </select>
            <select className="rounded-md border px-3 py-2 text-sm" required
                    value={form.linkId}
                    onChange={(e) => setForm({ ...form, linkId: e.target.value })}
                    disabled={!form.linkType}>
              <option value="">Select asset…</option>
              {form.linkType && assets.filter((a) => a.type === form.linkType).map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          </div>
          {form.linkType && assets.filter((a) => a.type === form.linkType).length === 0 && (
            <p className="text-xs text-muted-foreground">
              No {form.linkType.toLowerCase()} assets yet. Create one on the Assets page first.
            </p>
          )}

          <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save'}</Button>
        </form>
      )}

      {error && <p className="mb-4 text-sm text-red-400">{error}</p>}

      {sortedReminders.length === 0 ? (
        <div className="py-12 text-center text-muted-foreground border rounded-md">
          <Bell className="w-10 h-10 mx-auto mb-3" />
          <p className="text-sm">No reminders yet.</p>
        </div>
      ) : (
        <ul className="divide-y border rounded-md">
          {sortedReminders.map((r) => {
            const due = r.dueAt ? new Date(r.dueAt) : null
            const overdue = due && due < now && !r.completed
            const dueSoon = due && due >= now && due <= soon && !r.completed
            return (
              <li key={r.id} className={`px-4 py-3 flex items-start justify-between gap-3 ${
                overdue ? 'bg-red-500/10' : dueSoon ? 'bg-amber-500/10' : ''
              }`}>
                <div className="min-w-0 flex-1">
                  <p className={`font-medium ${r.completed ? 'line-through text-muted-foreground' : ''}`}>
                    {r.title}
                  </p>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {r.recurrence === 'ODOMETER'
                      ? `Odometer: ${r.dueOdometerKm} km`
                      : (due ? due.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—')}
                    {r.recurrence && r.recurrence !== 'NONE' && ` · ${r.recurrence.toLowerCase().replace('_', ' ')}`}
                  </p>
                  {r.linkedRefs && r.linkedRefs.length > 0 && (
                    <div className="mt-1 flex flex-wrap gap-1">
                      {r.linkedRefs.map((ref, i) => (
                        <span key={i} className="text-[11px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                          {linkLabel(ref)}
                        </span>
                      ))}
                    </div>
                  )}
                  {r.notes && <p className="mt-1 text-xs text-muted-foreground">{r.notes}</p>}
                </div>
                {isAdmin && (
                  <div className="flex gap-1 shrink-0">
                    {!r.completed && (
                      <Button variant="ghost" size="icon" onClick={() => handleComplete(r)} title="Complete">
                        <Check className="w-4 h-4" />
                      </Button>
                    )}
                    <Button variant="ghost" size="icon" onClick={() => handleDelete(r)} title="Delete">
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
