import { useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import {
  Plane, PartyPopper, CalendarDays, Sparkles, Plus, Trash2, ChevronDown, ChevronRight,
  Paperclip, MapPin, Hotel, Music, Utensils, Ticket, Bus, X,
} from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'

type PlanType = 'TRIP' | 'EVENT' | 'CELEBRATION' | 'OTHER'
type SegmentKind =
  | 'FLIGHT' | 'HOTEL' | 'ACTIVITY' | 'CONCERT' | 'MEAL' | 'TRANSPORT' | 'OTHER'

type LinkType =
  | 'MEMBER' | 'CONTACT' | 'HOME' | 'VEHICLE' | 'APPLIANCE' | 'POLICY'
  | 'DOCUMENT' | 'CONVERSATION' | 'PLAN'

type LinkRef = { type: LinkType; id: string }

type PlanSegment = {
  id?: string
  kind: SegmentKind
  title: string
  location?: string
  confirmation?: string
  notes?: string
  startAt?: string
  endAt?: string
  documentId?: string
}

type Plan = {
  id: string
  familyId: string
  name: string
  type: PlanType
  startDate?: string  // YYYY-MM-DD
  endDate?: string
  destination?: string
  notes?: string
  segments?: PlanSegment[]
  links?: LinkRef[]
}

type Member = { id: string; name: string }
type Contact = { id: string; name: string }
type DocumentRow = { id: string; fileName: string }

const planTypeMeta: Record<PlanType, { label: string; Icon: React.ComponentType<{ className?: string }> }> = {
  TRIP:        { label: 'Trip',        Icon: Plane },
  EVENT:       { label: 'Event',       Icon: Ticket },
  CELEBRATION: { label: 'Celebration', Icon: PartyPopper },
  OTHER:       { label: 'Other',       Icon: CalendarDays },
}

const segmentIcon: Record<SegmentKind, React.ComponentType<{ className?: string }>> = {
  FLIGHT:    Plane,
  HOTEL:     Hotel,
  ACTIVITY:  Sparkles,
  CONCERT:   Music,
  MEAL:      Utensils,
  TRANSPORT: Bus,
  OTHER:     CalendarDays,
}

function fmtDate(iso?: string): string {
  if (!iso) return ''
  return iso.slice(0, 10)
}

function fmtDateTime(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export default function PlansPage() {
  const { isAdmin } = useProfile()

  const [loading, setLoading] = useState(true)
  const [plans, setPlans] = useState<Plan[]>([])
  const [members, setMembers] = useState<Member[]>([])
  const [contacts, setContacts] = useState<Contact[]>([])
  const [documents, setDocuments] = useState<DocumentRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState<Plan>(emptyForm())
  const [saving, setSaving] = useState(false)

  useEffect(() => { void refresh() }, [])

  async function refresh() {
    setLoading(true)
    setError(null)
    try {
      const [plansRes, membersRes, contactsRes, docsRes] = await Promise.all([
        apiClient.get<Plan[]>('/plans'),
        apiClient.get<Member[]>('/family/members'),
        apiClient.get<Contact[]>('/contacts'),
        apiClient.get<DocumentRow[]>('/documents'),
      ])
      setPlans(plansRes.data)
      setMembers(membersRes.data)
      setContacts(contactsRes.data)
      setDocuments(docsRes.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load plans')
    } finally {
      setLoading(false)
    }
  }

  function emptyForm(): Plan {
    return {
      id: '',
      familyId: '',
      name: '',
      type: 'TRIP',
      segments: [],
      links: [],
    }
  }

  function linkLabel(ref: LinkRef): string {
    switch (ref.type) {
      case 'MEMBER':   return members.find((m) => m.id === ref.id)?.name ?? `Member ${ref.id.slice(0, 6)}`
      case 'CONTACT':  return contacts.find((c) => c.id === ref.id)?.name ?? `Contact ${ref.id.slice(0, 6)}`
      case 'DOCUMENT': return documents.find((d) => d.id === ref.id)?.fileName ?? `Doc ${ref.id.slice(0, 6)}`
      default:         return `${ref.type} ${ref.id.slice(0, 6)}`
    }
  }

  async function handleCreate() {
    if (!form.name.trim()) {
      setError('Plan name is required')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const body: Record<string, unknown> = {
        name: form.name.trim(),
        type: form.type,
        startDate: form.startDate || null,
        endDate: form.endDate || null,
        destination: form.destination ?? null,
        notes: form.notes ?? null,
        links: form.links ?? [],
      }
      await apiClient.post<Plan>('/plans', body)
      setForm(emptyForm())
      setShowCreate(false)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to create plan')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(plan: Plan) {
    if (!confirm(`Delete "${plan.name}"? This does not delete attached documents.`)) return
    try {
      await apiClient.delete(`/plans/${plan.id}`)
      if (expandedId === plan.id) setExpandedId(null)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete plan')
    }
  }

  async function handleAddSegment(planId: string, segment: PlanSegment) {
    try {
      await apiClient.post(`/plans/${planId}/segments`, {
        kind: segment.kind,
        title: segment.title,
        location: segment.location ?? null,
        confirmation: segment.confirmation ?? null,
        notes: segment.notes ?? null,
        startAt: segment.startAt ? new Date(segment.startAt).toISOString() : null,
        endAt: segment.endAt ? new Date(segment.endAt).toISOString() : null,
        documentId: segment.documentId || null,
      })
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to add segment')
    }
  }

  async function handleAttachDocument(planId: string, documentId: string) {
    try {
      await apiClient.post(`/plans/${planId}/attach-document/${documentId}`)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to attach document')
    }
  }

  const sortedPlans = useMemo(() => plans, [plans])

  if (loading) return <div className="p-6 text-sm text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 space-y-6 pl-16 md:pl-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Plans</h1>
          <p className="text-sm text-muted-foreground">
            Trips, concerts, weddings, conferences — anything time-bounded worth organizing.
          </p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowCreate((v) => !v)}>
            <Plus className="w-4 h-4 mr-2" />
            {showCreate ? 'Cancel' : 'New plan'}
          </Button>
        )}
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {showCreate && (
        <div className="border rounded-md p-4 space-y-3 max-w-2xl">
          <div className="grid grid-cols-1 md:grid-cols-[1fr_auto] gap-3">
            <input
              type="text"
              className="rounded-md border px-3 py-2 text-sm"
              placeholder="Plan name (e.g. Goa Dec 2026)"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
            <select
              className="rounded-md border px-3 py-2 text-sm"
              value={form.type}
              onChange={(e) => setForm({ ...form, type: e.target.value as PlanType })}
            >
              {(Object.keys(planTypeMeta) as PlanType[]).map((t) => (
                <option key={t} value={t}>{planTypeMeta[t].label}</option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <label className="text-xs text-muted-foreground">
              Starts
              <input
                type="date"
                className="mt-1 w-full rounded-md border px-3 py-2 text-sm"
                value={form.startDate ?? ''}
                onChange={(e) => setForm({ ...form, startDate: e.target.value })}
              />
            </label>
            <label className="text-xs text-muted-foreground">
              Ends
              <input
                type="date"
                className="mt-1 w-full rounded-md border px-3 py-2 text-sm"
                value={form.endDate ?? ''}
                onChange={(e) => setForm({ ...form, endDate: e.target.value })}
              />
            </label>
          </div>
          <input
            type="text"
            className="w-full rounded-md border px-3 py-2 text-sm"
            placeholder="Destination (e.g. Goa, Wembley Stadium)"
            value={form.destination ?? ''}
            onChange={(e) => setForm({ ...form, destination: e.target.value })}
          />
          <textarea
            className="w-full rounded-md border px-3 py-2 text-sm"
            rows={2}
            placeholder="Notes (optional)"
            value={form.notes ?? ''}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
          />

          <LinkPicker
            links={form.links ?? []}
            members={members}
            contacts={contacts}
            onChange={(links) => setForm({ ...form, links })}
          />

          <div className="flex gap-2 pt-1">
            <Button onClick={handleCreate} disabled={saving}>
              {saving ? 'Creating…' : 'Create plan'}
            </Button>
            <Button variant="ghost" onClick={() => { setShowCreate(false); setForm(emptyForm()) }}>
              Cancel
            </Button>
          </div>
        </div>
      )}

      {sortedPlans.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-muted-foreground border rounded-md">
          <Plane className="w-10 h-10 mb-3" />
          <p className="text-sm">No plans yet. Start one for your next trip, concert, or event.</p>
        </div>
      ) : (
        <ul className="space-y-3">
          {sortedPlans.map((p) => {
            const Icon = planTypeMeta[p.type]?.Icon ?? CalendarDays
            const expanded = expandedId === p.id
            return (
              <li key={p.id} className="border rounded-md">
                <div
                  className="px-4 py-3 flex items-center gap-3 cursor-pointer hover:bg-muted/30"
                  onClick={() => setExpandedId(expanded ? null : p.id)}
                >
                  {expanded ? <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />
                            : <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />}
                  <Icon className="w-5 h-5 text-muted-foreground shrink-0" />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline gap-2 flex-wrap">
                      <p className="font-medium truncate">{p.name}</p>
                      <span className="text-xs text-muted-foreground">{planTypeMeta[p.type]?.label}</span>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {[p.destination, formatDateRange(p.startDate, p.endDate)]
                        .filter(Boolean).join(' · ') || 'No dates yet'}
                      {p.segments && p.segments.length > 0 && ` · ${p.segments.length} segment${p.segments.length > 1 ? 's' : ''}`}
                    </p>
                  </div>
                  {isAdmin && (
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Delete plan"
                      onClick={(e) => { e.stopPropagation(); void handleDelete(p) }}
                    >
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  )}
                </div>

                {expanded && (
                  <PlanDetail
                    plan={p}
                    members={members}
                    contacts={contacts}
                    documents={documents}
                    linkLabel={linkLabel}
                    onAddSegment={(seg) => handleAddSegment(p.id, seg)}
                    onAttachDocument={(docId) => handleAttachDocument(p.id, docId)}
                  />
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function formatDateRange(start?: string, end?: string): string {
  if (!start && !end) return ''
  if (start && end && start !== end) return `${fmtDate(start)} → ${fmtDate(end)}`
  return fmtDate(start || end)
}

function LinkPicker({
  links, members, contacts, onChange,
}: {
  links: LinkRef[]
  members: Member[]
  contacts: Contact[]
  onChange: (next: LinkRef[]) => void
}) {
  const [type, setType] = useState<'MEMBER' | 'CONTACT'>('MEMBER')
  const [id, setId] = useState('')
  const options = type === 'MEMBER' ? members : contacts

  function add() {
    if (!id) return
    if (links.some((l) => l.type === type && l.id === id)) return
    onChange([...links, { type, id }])
    setId('')
  }

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Attendees</p>
      <div className="flex flex-wrap gap-2">
        {links.filter((l) => l.type === 'MEMBER' || l.type === 'CONTACT').map((l, i) => (
          <span key={`${l.type}-${l.id}-${i}`} className="inline-flex items-center gap-1 text-xs rounded-full bg-muted px-2 py-1">
            {l.type === 'MEMBER'
              ? (members.find((m) => m.id === l.id)?.name ?? l.id)
              : (contacts.find((c) => c.id === l.id)?.name ?? l.id)}
            <button
              type="button"
              onClick={() => onChange(links.filter((_, idx) => idx !== i))}
              className="text-muted-foreground hover:text-red-600"
            >
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}
      </div>
      <div className="flex gap-2">
        <select
          className="rounded-md border px-2 py-1 text-sm"
          value={type}
          onChange={(e) => { setType(e.target.value as 'MEMBER' | 'CONTACT'); setId('') }}
        >
          <option value="MEMBER">Member</option>
          <option value="CONTACT">Contact</option>
        </select>
        <select
          className="flex-1 rounded-md border px-2 py-1 text-sm"
          value={id}
          onChange={(e) => setId(e.target.value)}
        >
          <option value="">Pick…</option>
          {options.map((o) => (
            <option key={o.id} value={o.id}>{o.name}</option>
          ))}
        </select>
        <Button type="button" size="sm" variant="outline" onClick={add} disabled={!id}>Add</Button>
      </div>
    </div>
  )
}

function PlanDetail({
  plan, documents, linkLabel, onAddSegment, onAttachDocument,
}: {
  plan: Plan
  members: Member[]
  contacts: Contact[]
  documents: DocumentRow[]
  linkLabel: (ref: LinkRef) => string
  onAddSegment: (seg: PlanSegment) => void | Promise<void>
  onAttachDocument: (documentId: string) => void | Promise<void>
}) {
  const [showSegmentForm, setShowSegmentForm] = useState(false)
  const [seg, setSeg] = useState<PlanSegment>({ kind: 'ACTIVITY', title: '' })
  const [docToAttach, setDocToAttach] = useState('')

  async function submitSegment() {
    if (!seg.title.trim()) return
    await onAddSegment({
      kind: seg.kind,
      title: seg.title.trim(),
      location: seg.location || undefined,
      confirmation: seg.confirmation || undefined,
      notes: seg.notes || undefined,
      startAt: seg.startAt || undefined,
      endAt: seg.endAt || undefined,
      documentId: seg.documentId || undefined,
    })
    setSeg({ kind: 'ACTIVITY', title: '' })
    setShowSegmentForm(false)
  }

  async function submitAttach() {
    if (!docToAttach) return
    await onAttachDocument(docToAttach)
    setDocToAttach('')
  }

  const attendees = (plan.links ?? []).filter(
    (l) => l.type === 'MEMBER' || l.type === 'CONTACT')
  const attachedDocIds = new Set((plan.links ?? []).filter((l) => l.type === 'DOCUMENT').map((l) => l.id))
  const attachedDocs = documents.filter((d) => attachedDocIds.has(d.id))
  const availableDocs = documents.filter((d) => !attachedDocIds.has(d.id))

  return (
    <div className="border-t px-4 py-4 space-y-5 bg-muted/10">
      {plan.notes && (
        <p className="text-sm text-muted-foreground italic">{plan.notes}</p>
      )}

      {attendees.length > 0 && (
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-1">Attendees</p>
          <div className="flex flex-wrap gap-2">
            {attendees.map((l, i) => (
              <span key={`${l.type}-${l.id}-${i}`} className="text-xs rounded-full bg-background border px-2 py-0.5">
                {linkLabel(l)}
              </span>
            ))}
          </div>
        </div>
      )}

      <div>
        <div className="flex items-center justify-between mb-2">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Itinerary</p>
          <Button size="sm" variant="outline" onClick={() => setShowSegmentForm((v) => !v)}>
            <Plus className="w-3 h-3 mr-1" /> Add segment
          </Button>
        </div>

        {plan.segments && plan.segments.length > 0 ? (
          <ul className="divide-y border rounded-md bg-background">
            {plan.segments.map((s) => {
              const Icon = segmentIcon[s.kind] ?? CalendarDays
              return (
                <li key={s.id} className="px-3 py-2 flex items-start gap-3 text-sm">
                  <Icon className="w-4 h-4 text-muted-foreground mt-0.5 shrink-0" />
                  <div className="flex-1 min-w-0">
                    <p className="font-medium truncate">{s.title}</p>
                    <p className="text-xs text-muted-foreground">
                      {[s.kind, s.location, fmtDateTime(s.startAt), fmtDateTime(s.endAt)].filter(Boolean).join(' · ')}
                    </p>
                    {s.confirmation && (
                      <p className="text-xs text-muted-foreground">Confirmation: {s.confirmation}</p>
                    )}
                    {s.documentId && (
                      <p className="text-xs text-muted-foreground flex items-center gap-1 mt-0.5">
                        <Paperclip className="w-3 h-3" />
                        {documents.find((d) => d.id === s.documentId)?.fileName ?? 'Attached document'}
                      </p>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        ) : (
          <p className="text-xs text-muted-foreground italic">No segments yet.</p>
        )}

        {showSegmentForm && (
          <div className="mt-3 space-y-2 border rounded-md p-3 bg-background">
            <div className="grid grid-cols-[auto_1fr] gap-2">
              <select className="rounded-md border px-2 py-1 text-sm" value={seg.kind}
                      onChange={(e) => setSeg({ ...seg, kind: e.target.value as SegmentKind })}>
                {(Object.keys(segmentIcon) as SegmentKind[]).map((k) => (
                  <option key={k} value={k}>{k}</option>
                ))}
              </select>
              <input type="text" placeholder="Title (e.g. AI-812 to Goa)"
                     className="rounded-md border px-2 py-1 text-sm"
                     value={seg.title}
                     onChange={(e) => setSeg({ ...seg, title: e.target.value })} />
            </div>
            <div className="grid grid-cols-2 gap-2">
              <label className="text-xs text-muted-foreground">
                Starts
                <input type="datetime-local" className="mt-1 w-full rounded-md border px-2 py-1 text-sm"
                       value={seg.startAt ? seg.startAt.slice(0, 16) : ''}
                       onChange={(e) => setSeg({ ...seg, startAt: e.target.value })} />
              </label>
              <label className="text-xs text-muted-foreground">
                Ends
                <input type="datetime-local" className="mt-1 w-full rounded-md border px-2 py-1 text-sm"
                       value={seg.endAt ? seg.endAt.slice(0, 16) : ''}
                       onChange={(e) => setSeg({ ...seg, endAt: e.target.value })} />
              </label>
            </div>
            <input type="text" placeholder="Location" className="w-full rounded-md border px-2 py-1 text-sm"
                   value={seg.location ?? ''}
                   onChange={(e) => setSeg({ ...seg, location: e.target.value })} />
            <input type="text" placeholder="Confirmation / PNR"
                   className="w-full rounded-md border px-2 py-1 text-sm"
                   value={seg.confirmation ?? ''}
                   onChange={(e) => setSeg({ ...seg, confirmation: e.target.value })} />
            <select className="w-full rounded-md border px-2 py-1 text-sm" value={seg.documentId ?? ''}
                    onChange={(e) => setSeg({ ...seg, documentId: e.target.value })}>
              <option value="">Link a document (optional)…</option>
              {documents.map((d) => (
                <option key={d.id} value={d.id}>{d.fileName}</option>
              ))}
            </select>
            <textarea className="w-full rounded-md border px-2 py-1 text-sm" rows={2}
                      placeholder="Notes" value={seg.notes ?? ''}
                      onChange={(e) => setSeg({ ...seg, notes: e.target.value })} />
            <div className="flex gap-2">
              <Button size="sm" onClick={submitSegment} disabled={!seg.title.trim()}>Add</Button>
              <Button size="sm" variant="ghost" onClick={() => setShowSegmentForm(false)}>Cancel</Button>
            </div>
          </div>
        )}
      </div>

      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-2">Attached documents</p>
        {attachedDocs.length > 0 ? (
          <ul className="divide-y border rounded-md bg-background text-sm">
            {attachedDocs.map((d) => (
              <li key={d.id} className="px-3 py-2 flex items-center gap-2">
                <Paperclip className="w-4 h-4 text-muted-foreground shrink-0" />
                <span className="truncate">{d.fileName}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-xs text-muted-foreground italic">No documents attached.</p>
        )}
        <div className="mt-2 flex gap-2">
          <select className="flex-1 rounded-md border px-2 py-1 text-sm"
                  value={docToAttach}
                  onChange={(e) => setDocToAttach(e.target.value)}>
            <option value="">Attach an existing document…</option>
            {availableDocs.map((d) => (
              <option key={d.id} value={d.id}>{d.fileName}</option>
            ))}
          </select>
          <Button type="button" size="sm" variant="outline" onClick={submitAttach} disabled={!docToAttach}>
            Attach
          </Button>
        </div>
        <p className="text-[11px] text-muted-foreground mt-1 flex items-center gap-1">
          <MapPin className="w-3 h-3" />
          Map view is on the roadmap — itinerary is list-only for now.
        </p>
      </div>
    </div>
  )
}
