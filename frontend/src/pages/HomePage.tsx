import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import apiClient from '@/services/api'
import {
  Paperclip, Send, Camera, X, MessageSquare, FileText, Plane, Apple,
} from 'lucide-react'

type QuickMode = { key: string; label: string; to: string; Icon: React.ComponentType<{ className?: string }> }

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

  const fileRef = useRef<HTMLInputElement>(null)
  const cameraRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const urls = attachments.map((f) => f.type.startsWith('image/') ? URL.createObjectURL(f) : null)
    setPreviewUrls(urls)
    return () => { urls.forEach((u) => { if (u) URL.revokeObjectURL(u) }) }
  }, [attachments])

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
                <input ref={fileRef} type="file" multiple className="hidden"
                       onChange={(e) => { appendFiles(e.target.files); if (fileRef.current) fileRef.current.value = '' }} />
                <input ref={cameraRef} type="file" accept="image/*" capture="environment" className="hidden"
                       onChange={(e) => { appendFiles(e.target.files); if (cameraRef.current) cameraRef.current.value = '' }} />
                <button
                  type="button"
                  onClick={() => fileRef.current?.click()}
                  className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 hover:bg-white/10 transition"
                >
                  <Paperclip className="w-3.5 h-3.5" />
                  Add files
                </button>
                <button
                  type="button"
                  onClick={() => cameraRef.current?.click()}
                  className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 hover:bg-white/10 transition"
                >
                  <Camera className="w-3.5 h-3.5" />
                  Scan
                </button>
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
