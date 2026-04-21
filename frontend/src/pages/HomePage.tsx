import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import apiClient from '@/services/api'
import {
  Paperclip, Send, Camera, X, MessageSquare, FileText, Plane, Apple,
  Bell, CalendarDays, AlertTriangle, ChevronRight,
} from 'lucide-react'

type QuickMode = { key: string; label: string; to: string; Icon: React.ComponentType<{ className?: string }> }

type ReminderRow = { id: string; title: string; dueAt?: string; completed: boolean; recurrence?: string }
type PlanRow = { id: string; name: string; type?: string; startDate?: string; endDate?: string; destination?: string }
type DocRow = { id: string; fileName: string; uploadedAt?: string; mimeType?: string }

function relativeDate(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  const now = new Date()
  const ms = d.getTime() - now.getTime()
  const days = Math.round(ms / 86_400_000)
  if (days === 0) return 'today'
  if (days === 1) return 'tomorrow'
  if (days === -1) return 'yesterday'
  if (days > 1 && days < 7) return `in ${days}d`
  if (days < -1 && days > -7) return `${-days}d ago`
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' })
}

const MODES: QuickMode[] = [
  { key: 'chat',      label: 'Chat',      to: '/chat',      Icon: MessageSquare },
  { key: 'documents', label: 'Documents', to: '/documents', Icon: FileText },
  { key: 'plans',     label: 'Plans',     to: '/plans',     Icon: Plane },
  { key: 'nutrition', label: 'Nutrition', to: '/nutrition', Icon: Apple },
]

export default function HomePage() {
  const { user } = useAuth()
  const navigate = useNavigate()

  const [input, setInput] = useState('')
  const [attachments, setAttachments] = useState<File[]>([])
  const [previewUrls, setPreviewUrls] = useState<Array<string | null>>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [reminders, setReminders] = useState<ReminderRow[]>([])
  const [plans, setPlans] = useState<PlanRow[]>([])
  const [docs, setDocs] = useState<DocRow[]>([])

  useEffect(() => {
    const urls = attachments.map((f) => f.type.startsWith('image/') ? URL.createObjectURL(f) : null)
    setPreviewUrls(urls)
    return () => { urls.forEach((u) => { if (u) URL.revokeObjectURL(u) }) }
  }, [attachments])

  useEffect(() => {
    // Fire-and-forget — the hero is usable even before these resolve.
    apiClient.get<ReminderRow[]>('/reminders').then((r) => setReminders(r.data)).catch(() => {})
    apiClient.get<PlanRow[]>('/plans').then((r) => setPlans(r.data)).catch(() => {})
    apiClient.get<DocRow[]>('/documents').then((r) => setDocs(r.data)).catch(() => {})
  }, [])

  const now = Date.now()
  const weekFromNow = now + 7 * 86_400_000
  const thirtyFromNow = now + 30 * 86_400_000
  const overdue = reminders
    .filter((r) => !r.completed && r.dueAt && new Date(r.dueAt).getTime() < now)
    .sort((a, b) => new Date(a.dueAt!).getTime() - new Date(b.dueAt!).getTime())
    .slice(0, 4)
  const dueThisWeek = reminders
    .filter((r) => !r.completed && r.dueAt
      && new Date(r.dueAt).getTime() >= now
      && new Date(r.dueAt).getTime() <= weekFromNow)
    .sort((a, b) => new Date(a.dueAt!).getTime() - new Date(b.dueAt!).getTime())
    .slice(0, 4)
  const upcomingPlans = plans
    .filter((p) => p.startDate && new Date(p.startDate).getTime() <= thirtyFromNow
      && (!p.endDate || new Date(p.endDate).getTime() >= now))
    .sort((a, b) => new Date(a.startDate!).getTime() - new Date(b.startDate!).getTime())
    .slice(0, 4)
  const recentDocs = [...docs]
    .sort((a, b) => new Date(b.uploadedAt ?? 0).getTime() - new Date(a.uploadedAt ?? 0).getTime())
    .slice(0, 4)
  const hasAnything = overdue.length + dueThisWeek.length + upcomingPlans.length + recentDocs.length > 0

  const firstName = (user?.displayName ?? '').split(' ')[0] || 'there'

  function appendFiles(picked: FileList | null) {
    if (!picked || picked.length === 0) return
    setAttachments((prev) => [...prev, ...Array.from(picked)])
  }

  function removeAt(i: number) {
    setAttachments((prev) => prev.filter((_, idx) => idx !== i))
  }

  async function handleSubmit() {
    const text = input.trim()
    if ((!text && attachments.length === 0) || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const attachmentIds: string[] = []
      for (const f of attachments) {
        const fd = new FormData()
        fd.append('file', f)
        const up = await apiClient.post<{ attachmentId: string }>('/chat/attachments', fd, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
        attachmentIds.push(up.data.attachmentId)
      }
      const session = await apiClient.post<{ id: string }>('/chat/sessions')
      // reason: fire-and-forget so navigation is instant. ChatPage's own loader will
      // replace the optimistic view with the server-authoritative message list.
      void apiClient.post(`/chat/sessions/${session.data.id}/message`, {
        message: text,
        attachmentIds,
      })
      navigate('/chat')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Something went wrong. Is your Claude API key set?')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="relative min-h-full w-full overflow-hidden bg-neutral-950 text-neutral-100">
      {/* Aurora gradient background */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          background:
            'radial-gradient(ellipse 80% 50% at 20% 40%, rgba(236,72,153,0.35), transparent 60%),' +
            'radial-gradient(ellipse 60% 50% at 70% 30%, rgba(249,115,22,0.30), transparent 60%),' +
            'radial-gradient(ellipse 70% 60% at 50% 80%, rgba(16,185,129,0.18), transparent 60%)',
        }}
      />
      {/* Subtle vertical grain */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 mix-blend-overlay opacity-25"
        style={{
          backgroundImage:
            'repeating-linear-gradient(90deg, rgba(255,255,255,0.04) 0 1px, transparent 1px 3px)',
        }}
      />

      <div className="relative z-10 flex min-h-screen flex-col">
        <main className="flex-1 flex flex-col items-center justify-center px-4 pt-20 pb-10 md:pt-24">
          <div className="w-full max-w-2xl text-center">
            <h1 className="font-serif text-4xl md:text-5xl leading-tight text-neutral-50">
              Hello, {firstName}!
              <br />
              What are you working on today?
            </h1>

            <div className="mt-8 flex flex-wrap items-center justify-center gap-2 text-sm">
              {MODES.map((m) => {
                const Icon = m.Icon
                return (
                  <button
                    key={m.key}
                    type="button"
                    onClick={() => navigate(m.to)}
                    className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-neutral-200 hover:bg-white/10 hover:text-white transition"
                  >
                    <Icon className="w-3.5 h-3.5" />
                    {m.label}
                  </button>
                )
              })}
            </div>

            {/* Attachment chips */}
            {attachments.length > 0 && (
              <div className="mt-6 rounded-2xl border border-white/10 bg-black/40 backdrop-blur-md p-2">
                <div className="flex flex-wrap gap-2">
                  {attachments.map((f, i) => (
                    <div
                      key={`${f.name}-${i}`}
                      className="inline-flex items-center gap-2 rounded-xl bg-white/5 border border-white/10 pl-2 pr-1 py-1.5 text-xs text-neutral-200 max-w-[220px]"
                    >
                      {previewUrls[i] ? (
                        <img src={previewUrls[i] ?? undefined} alt="" className="w-6 h-6 rounded object-cover shrink-0" />
                      ) : (
                        <FileText className="w-4 h-4 text-neutral-400 shrink-0" />
                      )}
                      <div className="min-w-0">
                        <p className="truncate font-medium">{f.name}</p>
                        <p className="text-[10px] text-neutral-400">
                          {(f.type?.split('/')[1]?.toUpperCase() ?? 'FILE')} · {(f.size / (1024 * 1024)).toFixed(1)} MB
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={() => removeAt(i)}
                        className="ml-1 p-1 text-neutral-400 hover:text-red-400"
                        title="Remove"
                      >
                        <X className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Composer */}
            <div className="mt-4 rounded-2xl border border-white/10 bg-black/40 backdrop-blur-md p-3 text-left">
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') void handleSubmit() }}
                  placeholder={attachments.length > 0
                    ? `Describe ${attachments.length} file${attachments.length > 1 ? 's' : ''} or ask Claude to classify them…`
                    : 'Ask Kin-Keeper anything, or attach a document to file it…'}
                  disabled={submitting}
                  className="flex-1 bg-transparent text-sm text-neutral-50 placeholder:text-neutral-400 focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => void handleSubmit()}
                  disabled={submitting || (!input.trim() && attachments.length === 0)}
                  className="shrink-0 inline-flex items-center justify-center w-9 h-9 rounded-full bg-white text-neutral-900 hover:bg-neutral-200 disabled:opacity-50 disabled:cursor-not-allowed transition"
                  title={submitting ? 'Sending…' : 'Send'}
                >
                  <Send className="w-4 h-4" />
                </button>
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-neutral-300">
                {/* reason: <label>-wrapped inputs open the picker natively, which
                    works on every browser. Refs + programmatic .click() fail
                    silently on some mobile browsers when the input is
                    display:none — use labels instead and skip the hidden-click
                    dance entirely. */}
                <label className="cursor-pointer inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 hover:bg-white/10 transition">
                  <Paperclip className="w-3.5 h-3.5" />
                  Add files
                  <input
                    type="file"
                    multiple
                    className="sr-only"
                    onChange={(e) => {
                      const files = e.target.files
                      console.info('[HomePage] picked', files?.length ?? 0, 'file(s)')
                      appendFiles(files)
                      e.target.value = ''
                    }}
                  />
                </label>
                <label className="cursor-pointer inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 hover:bg-white/10 transition">
                  <Camera className="w-3.5 h-3.5" />
                  Scan
                  <input
                    type="file"
                    accept="image/*"
                    capture="environment"
                    className="sr-only"
                    onChange={(e) => {
                      const files = e.target.files
                      console.info('[HomePage] scanned', files?.length ?? 0, 'file(s)')
                      appendFiles(files)
                      e.target.value = ''
                    }}
                  />
                </label>
                <span className="ml-auto text-[11px] text-neutral-400">
                  Claude Sonnet 4.6 · BYOK
                </span>
              </div>
            </div>

            {error && <p className="mt-3 text-sm text-red-400">{error}</p>}

            <p className="mt-6 text-[11px] text-neutral-500">
              Tip: attach several files and ask Claude to classify and file each one.
            </p>
          </div>

          {hasAnything && (
            <div className="mt-10 w-full max-w-5xl">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {overdue.length > 0 && (
                  <NextUpCard
                    icon={AlertTriangle}
                    title="Overdue"
                    tint="red"
                    count={overdue.length}
                    onHeaderClick={() => navigate('/reminders')}
                  >
                    {overdue.map((r) => (
                      <NextUpRow key={r.id} title={r.title} sub={relativeDate(r.dueAt)}
                                 onClick={() => navigate('/reminders')} />
                    ))}
                  </NextUpCard>
                )}
                {dueThisWeek.length > 0 && (
                  <NextUpCard
                    icon={Bell}
                    title="Due this week"
                    tint="amber"
                    count={dueThisWeek.length}
                    onHeaderClick={() => navigate('/reminders')}
                  >
                    {dueThisWeek.map((r) => (
                      <NextUpRow key={r.id} title={r.title} sub={relativeDate(r.dueAt)}
                                 onClick={() => navigate('/reminders')} />
                    ))}
                  </NextUpCard>
                )}
                {upcomingPlans.length > 0 && (
                  <NextUpCard
                    icon={CalendarDays}
                    title="Upcoming plans"
                    tint="emerald"
                    count={upcomingPlans.length}
                    onHeaderClick={() => navigate('/plans')}
                  >
                    {upcomingPlans.map((p) => (
                      <NextUpRow key={p.id} title={p.name}
                                 sub={[p.destination, relativeDate(p.startDate)].filter(Boolean).join(' · ')}
                                 onClick={() => navigate('/plans')} />
                    ))}
                  </NextUpCard>
                )}
                {recentDocs.length > 0 && (
                  <NextUpCard
                    icon={FileText}
                    title="Recent documents"
                    tint="neutral"
                    count={recentDocs.length}
                    onHeaderClick={() => navigate('/documents')}
                  >
                    {recentDocs.map((d) => (
                      <NextUpRow key={d.id} title={d.fileName} sub={relativeDate(d.uploadedAt)}
                                 onClick={() => navigate('/documents')} />
                    ))}
                  </NextUpCard>
                )}
              </div>
            </div>
          )}
        </main>

        <footer className="relative z-10 flex items-center justify-between px-6 py-3 text-[11px] text-neutral-500">
          <span>Kin-Keeper © {new Date().getFullYear()}</span>
          <span className="flex items-center gap-1">
            AI can make mistakes
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-neutral-500" />
          </span>
        </footer>
      </div>
    </div>
  )
}

const TINT: Record<'red' | 'amber' | 'emerald' | 'neutral', string> = {
  red:     'text-red-300',
  amber:   'text-amber-300',
  emerald: 'text-emerald-300',
  neutral: 'text-neutral-300',
}

function NextUpCard({
  icon: Icon, title, tint, count, onHeaderClick, children,
}: {
  icon: React.ComponentType<{ className?: string }>
  title: string
  tint: keyof typeof TINT
  count: number
  onHeaderClick: () => void
  children: React.ReactNode
}) {
  return (
    <section className="rounded-2xl border border-white/10 bg-black/40 backdrop-blur-md overflow-hidden">
      <button
        type="button"
        onClick={onHeaderClick}
        className="w-full flex items-center gap-2 px-4 py-3 border-b border-white/10 text-left hover:bg-white/5 transition"
      >
        <Icon className={`w-4 h-4 ${TINT[tint]}`} />
        <span className="text-sm font-medium">{title}</span>
        <span className="text-xs text-neutral-400">({count})</span>
        <ChevronRight className="w-4 h-4 text-neutral-500 ml-auto" />
      </button>
      <ul className="divide-y divide-white/5">{children}</ul>
    </section>
  )
}

function NextUpRow({ title, sub, onClick }: { title: string; sub?: string; onClick?: () => void }) {
  return (
    <li>
      <button
        type="button"
        onClick={onClick}
        className="w-full text-left px-4 py-2.5 hover:bg-white/5 transition flex items-baseline gap-2"
      >
        <span className="text-sm truncate flex-1 min-w-0">{title}</span>
        {sub && <span className="text-[11px] text-neutral-400 shrink-0">{sub}</span>}
      </button>
    </li>
  )
}
