import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import {
  MessageCircle, Plus, Trash2, X, Phone, Users, Home, Car, Cpu, Shield, FileText, Bell,
  Upload, Camera, FileUp,
} from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'
import { flattenCategoryTree, Category as TreeCategory } from '@/lib/categoryTree'

type LinkType = 'MEMBER' | 'CONTACT' | 'HOME' | 'VEHICLE' | 'APPLIANCE' | 'POLICY' | 'DOCUMENT' | 'CONVERSATION'
type LinkRef = { type: LinkType; id: string }
type Format = 'ENCOUNTER' | 'THREAD'
type Channel = 'CALL' | 'VISIT' | 'MESSAGE' | 'EMAIL' | 'MEETING' | 'OTHER'

type ConversationMessage = { id?: string; from: string; content: string; at?: string }

type Conversation = {
  id: string
  title: string
  format: Format
  channel?: Channel
  occurredAt: string
  summary?: string
  outcome?: string
  followUp?: string
  messages?: ConversationMessage[]
  links: LinkRef[]
}

type Member = { id: string; name: string }
type Contact = { id: string; name: string; relationship?: string }
type AssetRow = { id: string; type: Exclude<LinkType, 'MEMBER' | 'CONTACT' | 'DOCUMENT' | 'CONVERSATION'>; name: string }
type DocumentRow = { id: string; fileName: string }
type Category = TreeCategory

const ASSET_ICON: Record<string, React.ComponentType<{ className?: string }>> = {
  HOME: Home, VEHICLE: Car, APPLIANCE: Cpu, POLICY: Shield,
  CONTACT: Phone, MEMBER: Users, DOCUMENT: FileText, CONVERSATION: MessageCircle,
}

const EMPTY_FORM = {
  format: 'ENCOUNTER' as Format,
  channel: 'CALL' as Channel,
  occurredAt: toLocalInput(new Date()),
  title: '',
  summary: '',
  outcome: '',
  followUp: '',
  messages: [] as ConversationMessage[],
  links: [] as LinkRef[],
}

function toLocalInput(d: Date): string {
  // datetime-local input wants YYYY-MM-DDTHH:mm without timezone
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function ConversationsPage() {
  const { isAdmin } = useProfile()
  const [params, setParams] = useSearchParams()

  const [conversations, setConversations] = useState<Conversation[]>([])
  const [members, setMembers] = useState<Member[]>([])
  const [contacts, setContacts] = useState<Contact[]>([])
  const [assets, setAssets] = useState<AssetRow[]>([])
  const [documents, setDocuments] = useState<DocumentRow[]>([])
  const [categories, setCategories] = useState<Category[]>([])

  const [showUpload, setShowUpload] = useState(false)
  const [uploadFiles, setUploadFiles] = useState<File[]>([])
  const [uploadCategoryId, setUploadCategoryId] = useState('')
  const [uploadLabels, setUploadLabels] = useState('')
  const [uploading, setUploading] = useState(false)
  const uploadFileRef = useRef<HTMLInputElement>(null)
  const uploadCameraRef = useRef<HTMLInputElement>(null)

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const [showAdd, setShowAdd] = useState(false)
  const [form, setForm] = useState(EMPTY_FORM)
  const [saving, setSaving] = useState(false)

  const [filterType, setFilterType] = useState<'' | LinkType>('')
  const [filterId, setFilterId] = useState('')
  const [filterQuery, setFilterQuery] = useState('')

  // Initialise filter from URL (?linkType=CONTACT&linkId=... deep-links from ContactsPage)
  useEffect(() => {
    const lt = params.get('linkType') as LinkType | null
    const lid = params.get('linkId')
    if (lt) setFilterType(lt)
    if (lid) setFilterId(lid)
  }, [])

  useEffect(() => { void loadAll() }, [])
  useEffect(() => { void reload() }, [filterType, filterId, filterQuery])

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [m, c, a, d, cat] = await Promise.all([
        apiClient.get<Member[]>('/family/members'),
        apiClient.get<Contact[]>('/contacts'),
        apiClient.get<AssetRow[]>('/assets'),
        apiClient.get<DocumentRow[]>('/documents'),
        apiClient.get<Category[]>('/categories'),
      ])
      setMembers(m.data)
      setContacts(c.data)
      setAssets(a.data)
      setDocuments(d.data)
      setCategories(cat.data)
      await reload()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load')
    } finally {
      setLoading(false)
    }
  }

  async function reload() {
    try {
      const q: Record<string, string> = {}
      if (filterType) q.linkType = filterType
      if (filterId) q.linkId = filterId
      if (filterQuery.trim()) q.query = filterQuery.trim()
      const res = await apiClient.get<Conversation[]>('/conversations', { params: q })
      setConversations(res.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load conversations')
    }
  }

  function clearFilter() {
    setFilterType(''); setFilterId(''); setFilterQuery('')
    setParams({})
  }

  function addLink(type: LinkType, id: string) {
    if (!id) return
    if (form.links.some((l) => l.type === type && l.id === id)) return
    setForm({ ...form, links: [...form.links, { type, id }] })
  }

  function removeLink(type: LinkType, id: string) {
    setForm({ ...form, links: form.links.filter((l) => !(l.type === type && l.id === id)) })
  }

  function addMessage() {
    setForm({
      ...form,
      messages: [...form.messages, { from: 'Me', content: '', at: toLocalInput(new Date()) }],
    })
  }

  function updateMessage(i: number, patch: Partial<ConversationMessage>) {
    const msgs = [...form.messages]
    msgs[i] = { ...msgs[i], ...patch }
    setForm({ ...form, messages: msgs })
  }

  function removeMessage(i: number) {
    setForm({ ...form, messages: form.messages.filter((_, idx) => idx !== i) })
  }

  async function handleUploadAndAttach() {
    if (uploadFiles.length === 0 || !uploadCategoryId) {
      setError('Pick at least one file and a category before uploading.')
      return
    }
    setUploading(true)
    setError(null)
    try {
      const memberLink = form.links.find((l) => l.type === 'MEMBER')
      const preLinks = form.links.filter((l) => l.type !== 'DOCUMENT')

      const uploaded: { id: string; fileName: string }[] = []
      if (uploadFiles.length === 1) {
        const fd = new FormData()
        fd.append('file', uploadFiles[0])
        fd.append('categoryId', uploadCategoryId)
        if (memberLink) fd.append('memberId', memberLink.id)
        if (uploadLabels.trim()) fd.append('labels', uploadLabels.trim())
        if (preLinks.length > 0) fd.append('links', JSON.stringify(preLinks))
        const res = await apiClient.post<{ id: string; fileName: string }>('/documents/upload', fd, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
        uploaded.push({ id: res.data.id, fileName: res.data.fileName })
      } else {
        const fd = new FormData()
        for (const f of uploadFiles) fd.append('files', f)
        fd.append('categoryId', uploadCategoryId)
        if (memberLink) fd.append('memberId', memberLink.id)
        if (uploadLabels.trim()) fd.append('labels', uploadLabels.trim())
        if (preLinks.length > 0) fd.append('links', JSON.stringify(preLinks))
        const res = await apiClient.post<{
          documents: { id: string; fileName: string }[]
          failed: Array<{ fileName: string; error: string }>
        }>('/documents/bulk-upload', fd, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
        for (const d of res.data.documents) uploaded.push({ id: d.id, fileName: d.fileName })
        if (res.data.failed?.length) {
          setError(`Uploaded ${uploaded.length}/${uploadFiles.length}. Failed: ${res.data.failed.map((f) => f.fileName).join(', ')}`)
        }
      }

      setDocuments((prev) => [
        ...uploaded.map((u) => ({ id: u.id, fileName: u.fileName })),
        ...prev,
      ])
      setForm({
        ...form,
        links: [
          ...form.links,
          ...uploaded.map((u) => ({ type: 'DOCUMENT' as const, id: u.id })),
        ],
      })
      setUploadFiles([])
      setUploadCategoryId('')
      setUploadLabels('')
      setShowUpload(false)
      setStatus(`Uploaded ${uploaded.length} file${uploaded.length === 1 ? '' : 's'} and attached.`)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  const categoryTree = useMemo(() => flattenCategoryTree(categories), [categories])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (form.links.length === 0) {
      setError('Link at least one subject (contact, member, asset, or document).')
      return
    }
    setSaving(true)
    setError(null)
    setStatus(null)
    try {
      const body: Record<string, unknown> = {
        format: form.format,
        channel: form.channel,
        occurredAt: new Date(form.occurredAt).toISOString(),
        links: form.links,
      }
      if (form.title.trim()) body.title = form.title.trim()
      if (form.format === 'ENCOUNTER') {
        if (!form.summary.trim()) throw new Error('Summary is required for an encounter.')
        body.summary = form.summary.trim()
        if (form.outcome.trim()) body.outcome = form.outcome.trim()
        if (form.followUp.trim()) body.followUp = form.followUp.trim()
      } else {
        const msgs = form.messages
          .filter((m) => m.content.trim())
          .map((m) => ({
            from: m.from || 'Me',
            content: m.content.trim(),
            at: m.at ? new Date(m.at).toISOString() : new Date().toISOString(),
          }))
        if (msgs.length === 0) throw new Error('Add at least one message.')
        body.messages = msgs
      }
      await apiClient.post('/conversations', body)
      setForm(EMPTY_FORM)
      setShowAdd(false)
      setStatus('Conversation logged.')
      await reload()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? e?.message ?? 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(c: Conversation) {
    if (!confirm(`Delete this conversation?`)) return
    try {
      await apiClient.delete(`/conversations/${c.id}`)
      await reload()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete')
    }
  }

  async function handleCreateReminderFromFollowUp(c: Conversation) {
    if (!c.followUp || !c.followUp.trim()) return
    const dueAtPrompt = prompt(
      'When should this reminder trigger? (ISO-8601 or YYYY-MM-DD HH:mm; blank = 7 days from now)',
      toLocalInput(new Date(Date.now() + 7 * 24 * 3600 * 1000))
    )
    if (dueAtPrompt === null) return
    const dueAt = dueAtPrompt.trim()
      ? new Date(dueAtPrompt).toISOString()
      : new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString()
    try {
      await apiClient.post('/reminders', {
        title: c.followUp.slice(0, 120),
        notes: `From conversation on ${new Date(c.occurredAt).toLocaleString()}`,
        recurrence: 'NONE',
        dueAt,
        linkedRefs: [{ type: 'CONVERSATION', id: c.id }],
      })
      setStatus('Reminder created.')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to create reminder')
    }
  }

  const subjectLookup = useMemo(() => {
    const m = new Map<string, string>()
    for (const x of members) m.set(`MEMBER:${x.id}`, x.name)
    for (const x of contacts) m.set(`CONTACT:${x.id}`, x.name)
    for (const x of assets) m.set(`${x.type}:${x.id}`, x.name)
    for (const x of documents) m.set(`DOCUMENT:${x.id}`, x.fileName)
    return m
  }, [members, contacts, assets, documents])

  if (loading) return <div className="p-6 text-sm text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 max-w-3xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Conversations</h1>
          <p className="text-sm text-muted-foreground">
            A ledger of calls, visits, and messages. Attach them to contacts, family, assets, or documents.
          </p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowAdd((v) => !v)}>
            <Plus className="w-4 h-4 mr-2" />
            {showAdd ? 'Cancel' : 'Log Conversation'}
          </Button>
        )}
      </div>

      {/* Filters */}
      <div className="mb-4 flex flex-wrap gap-2 items-center">
        <input
          type="text"
          value={filterQuery}
          onChange={(e) => setFilterQuery(e.target.value)}
          placeholder="Search…"
          className="rounded-md border px-3 py-2 text-sm flex-1 min-w-[180px]"
        />
        <select
          value={filterType}
          onChange={(e) => setFilterType(e.target.value as LinkType | '')}
          className="rounded-md border px-3 py-2 text-sm"
        >
          <option value="">All subject types</option>
          <option value="CONTACT">Contact</option>
          <option value="MEMBER">Member</option>
          <option value="HOME">Home</option>
          <option value="VEHICLE">Vehicle</option>
          <option value="APPLIANCE">Appliance</option>
          <option value="POLICY">Policy</option>
          <option value="DOCUMENT">Document</option>
        </select>
        {filterType && (
          <select
            value={filterId}
            onChange={(e) => setFilterId(e.target.value)}
            className="rounded-md border px-3 py-2 text-sm"
          >
            <option value="">All</option>
            {filterType === 'MEMBER' && members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            {filterType === 'CONTACT' && contacts.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            {(filterType === 'HOME' || filterType === 'VEHICLE' || filterType === 'APPLIANCE' || filterType === 'POLICY')
              && assets.filter((a) => a.type === filterType).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            {filterType === 'DOCUMENT' && documents.map((d) => <option key={d.id} value={d.id}>{d.fileName}</option>)}
          </select>
        )}
        {(filterType || filterQuery) && (
          <Button variant="outline" size="sm" onClick={clearFilter}>Clear</Button>
        )}
      </div>

      {/* Add form */}
      {showAdd && isAdmin && (
        <form onSubmit={handleSubmit} className="mb-6 space-y-3 border rounded-md p-4">
          <div className="grid grid-cols-3 gap-2">
            <select className="rounded-md border px-3 py-2 text-sm"
                    value={form.format}
                    onChange={(e) => setForm({ ...form, format: e.target.value as Format })}>
              <option value="ENCOUNTER">Encounter (recap)</option>
              <option value="THREAD">Thread (messages)</option>
            </select>
            <select className="rounded-md border px-3 py-2 text-sm"
                    value={form.channel}
                    onChange={(e) => setForm({ ...form, channel: e.target.value as Channel })}>
              <option value="CALL">Call</option>
              <option value="VISIT">Visit</option>
              <option value="MEETING">Meeting</option>
              <option value="MESSAGE">Message</option>
              <option value="EMAIL">Email</option>
              <option value="OTHER">Other</option>
            </select>
            <input type="datetime-local" className="rounded-md border px-3 py-2 text-sm"
                   value={form.occurredAt}
                   onChange={(e) => setForm({ ...form, occurredAt: e.target.value })} />
          </div>
          <input className="w-full rounded-md border px-3 py-2 text-sm"
                 placeholder="Title (optional — auto-derived from summary/first message)"
                 value={form.title}
                 onChange={(e) => setForm({ ...form, title: e.target.value })} />

          {/* Subjects */}
          <div className="border rounded-md p-3 space-y-2">
            <div className="text-xs font-semibold text-muted-foreground">Subjects (at least one required)</div>
            <div className="flex flex-wrap gap-1.5">
              {form.links.map((l) => {
                const label = subjectLookup.get(`${l.type}:${l.id}`) ?? l.id
                const Icon = ASSET_ICON[l.type] ?? Users
                return (
                  <span key={`${l.type}:${l.id}`}
                        className="inline-flex items-center gap-1 text-xs bg-muted px-2 py-0.5 rounded">
                    <Icon className="w-3 h-3" />
                    {label}
                    <button type="button" onClick={() => removeLink(l.type, l.id)}
                            className="ml-1 text-muted-foreground hover:text-red-400">
                      <X className="w-3 h-3" />
                    </button>
                  </span>
                )
              })}
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <SubjectAdder label="+ Member" onPick={(id) => addLink('MEMBER', id)}
                            options={members.map((m) => ({ id: m.id, label: m.name }))} />
              <SubjectAdder label="+ Contact" onPick={(id) => addLink('CONTACT', id)}
                            options={contacts.map((c) => ({ id: c.id, label: c.name }))} />
              <SubjectAdder label="+ Asset" onPick={(compound) => {
                const [type, id] = compound.split(':')
                if (type && id) addLink(type as LinkType, id)
              }}
                            options={assets.map((a) => ({ id: `${a.type}:${a.id}`, label: `${a.type.toLowerCase()}: ${a.name}` }))} />
              <SubjectAdder label="+ Link existing document" onPick={(id) => addLink('DOCUMENT', id)}
                            options={documents.map((d) => ({ id: d.id, label: d.fileName }))} />
            </div>

            <div className="pt-2 border-t">
              <Button type="button" variant="outline" size="sm"
                      onClick={() => setShowUpload((v) => !v)}>
                <Upload className="w-3 h-3 mr-1" />
                {showUpload ? 'Cancel upload' : 'Upload new document'}
              </Button>

              {showUpload && (
                <div className="mt-2 space-y-2 border rounded-md p-3 bg-muted/30">
                  <div className="flex flex-wrap gap-2 items-center">
                    <Button type="button" variant="outline" size="sm"
                            onClick={() => uploadFileRef.current?.click()}>
                      <FileUp className="w-3.5 h-3.5 mr-1" />
                      Choose files
                    </Button>
                    <Button type="button" variant="outline" size="sm"
                            onClick={() => uploadCameraRef.current?.click()}>
                      <Camera className="w-3.5 h-3.5 mr-1" />
                      Scan
                    </Button>
                    <span className="text-xs text-muted-foreground truncate">
                      {uploadFiles.length === 0
                        ? 'No files selected'
                        : `${uploadFiles.length} file${uploadFiles.length > 1 ? 's' : ''} selected`}
                    </span>
                  </div>
                  <input ref={uploadFileRef} type="file" multiple className="hidden"
                         onChange={(e) => {
                           const picked = e.target.files
                           if (picked && picked.length > 0) setUploadFiles((prev) => [...prev, ...Array.from(picked)])
                           if (uploadFileRef.current) uploadFileRef.current.value = ''
                         }} />
                  <input ref={uploadCameraRef} type="file" accept="image/*" capture="environment"
                         className="hidden"
                         onChange={(e) => {
                           const picked = e.target.files
                           if (picked && picked.length > 0) setUploadFiles((prev) => [...prev, ...Array.from(picked)])
                           if (uploadCameraRef.current) uploadCameraRef.current.value = ''
                         }} />
                  {uploadFiles.length > 0 && (
                    <ul className="divide-y rounded-md border bg-background text-xs">
                      {uploadFiles.map((f, i) => (
                        <li key={`${f.name}-${i}`} className="px-2 py-1 flex items-center gap-2">
                          <span className="flex-1 min-w-0 truncate">{f.name}</span>
                          <span className="text-[10px] text-muted-foreground">{Math.round(f.size / 1024)} KB</span>
                          <button type="button" className="text-muted-foreground hover:text-red-400"
                                  onClick={() => setUploadFiles((prev) => prev.filter((_, idx) => idx !== i))}
                                  title="Remove">×</button>
                        </li>
                      ))}
                    </ul>
                  )}
                  <select className="w-full rounded-md border px-3 py-2 text-sm"
                          value={uploadCategoryId}
                          onChange={(e) => setUploadCategoryId(e.target.value)}>
                    <option value="">Pick a category…</option>
                    {categoryTree.map((c) => (
                      <option key={c.id} value={c.id}>
                        {'\u00A0\u00A0'.repeat(c.depth) + (c.depth > 0 ? '└ ' : '') + c.name}
                      </option>
                    ))}
                  </select>
                  <input className="w-full rounded-md border px-3 py-2 text-sm"
                         placeholder="Labels (comma-separated, optional)"
                         value={uploadLabels}
                         onChange={(e) => setUploadLabels(e.target.value)} />
                  <Button type="button" size="sm" onClick={handleUploadAndAttach}
                          disabled={uploading || uploadFiles.length === 0 || !uploadCategoryId}>
                    {uploading
                      ? 'Uploading…'
                      : uploadFiles.length > 1
                        ? `Upload & attach ${uploadFiles.length} files`
                        : 'Upload & attach'}
                  </Button>
                  <p className="text-[11px] text-muted-foreground">
                    The uploaded document inherits the conversation's member/contact/asset links
                    so it shows up on the Documents page under those subjects too.
                  </p>
                </div>
              )}
            </div>
          </div>

          {/* Format-specific */}
          {form.format === 'ENCOUNTER' ? (
            <>
              <textarea className="w-full rounded-md border px-3 py-2 text-sm"
                        placeholder="Summary — what was discussed" rows={3}
                        required
                        value={form.summary}
                        onChange={(e) => setForm({ ...form, summary: e.target.value })} />
              <textarea className="w-full rounded-md border px-3 py-2 text-sm"
                        placeholder="Outcome (optional) — what was decided" rows={2}
                        value={form.outcome}
                        onChange={(e) => setForm({ ...form, outcome: e.target.value })} />
              <textarea className="w-full rounded-md border px-3 py-2 text-sm"
                        placeholder="Follow-up (optional) — next action or callback" rows={2}
                        value={form.followUp}
                        onChange={(e) => setForm({ ...form, followUp: e.target.value })} />
            </>
          ) : (
            <div className="space-y-2">
              <div className="text-xs font-semibold text-muted-foreground">Messages</div>
              {form.messages.map((m, i) => (
                <div key={i} className="border rounded-md p-2 space-y-2">
                  <div className="flex gap-2">
                    <input className="rounded-md border px-2 py-1 text-sm w-32"
                           placeholder="From"
                           value={m.from}
                           onChange={(e) => updateMessage(i, { from: e.target.value })} />
                    <input type="datetime-local" className="rounded-md border px-2 py-1 text-sm flex-1"
                           value={m.at}
                           onChange={(e) => updateMessage(i, { at: e.target.value })} />
                    <button type="button" onClick={() => removeMessage(i)}
                            className="text-muted-foreground hover:text-red-400">
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                  <textarea className="w-full rounded-md border px-2 py-1 text-sm" rows={2}
                            placeholder="Message content"
                            value={m.content}
                            onChange={(e) => updateMessage(i, { content: e.target.value })} />
                </div>
              ))}
              <Button type="button" variant="outline" size="sm" onClick={addMessage}>
                <Plus className="w-3 h-3 mr-1" /> Add message
              </Button>
            </div>
          )}

          <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save'}</Button>
        </form>
      )}

      {status && <p className="mb-4 text-sm text-emerald-700">{status}</p>}
      {error && <p className="mb-4 text-sm text-red-400">{error}</p>}

      {/* List */}
      {conversations.length === 0 ? (
        <div className="py-12 text-center text-muted-foreground border rounded-md">
          <MessageCircle className="w-10 h-10 mx-auto mb-3" />
          <p className="text-sm">No conversations logged yet.</p>
        </div>
      ) : (
        <ul className="space-y-3">
          {conversations.map((c) => (
            <li key={c.id} className="border rounded-md p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span>{c.channel?.toLowerCase() ?? 'interaction'}</span>
                    <span>·</span>
                    <span>{new Date(c.occurredAt).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}</span>
                    <span>·</span>
                    <span>{c.format === 'THREAD' ? `${c.messages?.length ?? 0} msgs` : 'recap'}</span>
                  </div>
                  <p className="font-medium mt-0.5">{c.title}</p>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {c.links.map((l, i) => {
                      const Icon = ASSET_ICON[l.type] ?? Users
                      return (
                        <span key={i} className="inline-flex items-center gap-1 text-[11px] bg-muted px-1.5 py-0.5 rounded">
                          <Icon className="w-3 h-3" />
                          {subjectLookup.get(`${l.type}:${l.id}`) ?? `${l.type.toLowerCase()}: ${l.id}`}
                        </span>
                      )
                    })}
                  </div>
                  {c.format === 'ENCOUNTER' ? (
                    <div className="mt-2 text-sm space-y-1">
                      {c.summary && <p>{c.summary}</p>}
                      {c.outcome && <p className="text-muted-foreground"><span className="font-semibold text-foreground">Outcome:</span> {c.outcome}</p>}
                      {c.followUp && (
                        <p className="text-muted-foreground">
                          <span className="font-semibold text-foreground">Follow-up:</span> {c.followUp}
                        </p>
                      )}
                    </div>
                  ) : (
                    <div className="mt-2 space-y-1">
                      {c.messages?.map((m) => (
                        <div key={m.id} className="text-sm">
                          <span className="font-semibold">{m.from}:</span> {m.content}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <div className="flex gap-1 shrink-0">
                  {isAdmin && c.followUp && (
                    <Button variant="ghost" size="icon" title="Create reminder from follow-up"
                            onClick={() => handleCreateReminderFromFollowUp(c)}>
                      <Bell className="w-4 h-4" />
                    </Button>
                  )}
                  {isAdmin && (
                    <Button variant="ghost" size="icon" onClick={() => handleDelete(c)} title="Delete">
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function SubjectAdder({ label, options, onPick }: {
  label: string
  options: { id: string; label: string }[]
  onPick: (id: string) => void
}) {
  return (
    <select
      onChange={(e) => { if (e.target.value) { onPick(e.target.value); e.target.value = '' } }}
      className="rounded-md border px-3 py-2 text-sm"
      defaultValue=""
    >
      <option value="">{label}</option>
      {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
    </select>
  )
}
