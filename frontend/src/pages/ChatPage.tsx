import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { Button } from '@/components/ui/button'
import { Send, Plus, MessageSquare, Trash2, Paperclip, Camera, X } from 'lucide-react'
import apiClient from '@/services/api'

type ChatSession = {
  id: string
  title: string
  updatedAt?: string
  expiresAt?: string
}

type Message = {
  id: string
  role: 'user' | 'assistant'
  content: string
}

type SessionWithMessages = {
  session: ChatSession
  messages: Array<{ id: string; role: string; content: string }>
}

export default function ChatPage() {
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [attachment, setAttachment] = useState<File | null>(null)
  const [loadingSessions, setLoadingSessions] = useState(true)
  const [loadingMessages, setLoadingMessages] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const cameraInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    void loadSessions(true)
  }, [])

  useEffect(() => {
    if (activeId) void loadMessages(activeId)
    else setMessages([])
  }, [activeId])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, sending])

  async function loadSessions(autoSelect: boolean) {
    setLoadingSessions(true)
    try {
      const res = await apiClient.get<ChatSession[]>('/chat/sessions')
      setSessions(res.data)
      if (autoSelect && res.data.length > 0 && !activeId) {
        setActiveId(res.data[0].id)
      }
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load chats')
    } finally {
      setLoadingSessions(false)
    }
  }

  async function loadMessages(sessionId: string) {
    setLoadingMessages(true)
    setError(null)
    try {
      const res = await apiClient.get<SessionWithMessages>(`/chat/sessions/${sessionId}`)
      setMessages(res.data.messages.map((m) => ({
        id: m.id,
        role: m.role as 'user' | 'assistant',
        content: m.content,
      })))
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load chat')
      setMessages([])
    } finally {
      setLoadingMessages(false)
    }
  }

  async function handleNewChat() {
    setError(null)
    try {
      const res = await apiClient.post<ChatSession>('/chat/sessions')
      setSessions((prev) => [res.data, ...prev])
      setActiveId(res.data.id)
      setMessages([])
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to start new chat')
    }
  }

  async function handleDeleteSession(id: string, e: React.MouseEvent) {
    e.stopPropagation()
    if (!confirm('Delete this chat?')) return
    try {
      await apiClient.delete(`/chat/sessions/${id}`)
      setSessions((prev) => prev.filter((s) => s.id !== id))
      if (activeId === id) {
        setActiveId(null)
        setMessages([])
      }
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete chat')
    }
  }

  async function handleSend() {
    const text = input.trim()
    if ((!text && !attachment) || sending) return

    let sessionId = activeId
    if (!sessionId) {
      try {
        const res = await apiClient.post<ChatSession>('/chat/sessions')
        sessionId = res.data.id
        setSessions((prev) => [res.data, ...prev])
        setActiveId(res.data.id)
      } catch (e: any) {
        setError(e?.response?.data?.error ?? 'Failed to start chat')
        return
      }
    }

    const pending = attachment
    const displayText = pending
      ? (text ? `${text}\n\n[attached: ${pending.name}]` : `[attached: ${pending.name}]`)
      : text
    const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: displayText }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    setAttachment(null)
    setError(null)
    setSending(true)

    try {
      let attachmentId: string | null = null
      if (pending) {
        const form = new FormData()
        form.append('file', pending)
        const up = await apiClient.post<{ attachmentId: string }>('/chat/attachments', form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
        attachmentId = up.data.attachmentId
      }

      const res = await apiClient.post<{ reply: string; messageId: string }>(
        `/chat/sessions/${sessionId}/message`,
        { message: text, attachmentId },
      )
      setMessages((prev) => [
        ...prev,
        { id: res.data.messageId, role: 'assistant', content: res.data.reply },
      ])
      void loadSessions(false)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Chat failed. Have you added your Claude API key in Settings?')
    } finally {
      setSending(false)
    }
  }

  function formatDate(iso?: string) {
    if (!iso) return ''
    const d = new Date(iso)
    const now = new Date()
    const sameDay = d.toDateString() === now.toDateString()
    if (sameDay) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' })
  }

  return (
    <div className="flex h-full">
      {/* Sidebar */}
      <aside className="w-64 border-r flex flex-col bg-muted/30">
        <div className="p-3 border-b">
          <Button onClick={handleNewChat} className="w-full" size="sm">
            <Plus className="w-4 h-4 mr-2" />
            New chat
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto">
          {loadingSessions ? (
            <p className="p-3 text-xs text-muted-foreground">Loading…</p>
          ) : sessions.length === 0 ? (
            <p className="p-3 text-xs text-muted-foreground">No chats yet.</p>
          ) : (
            <ul>
              {sessions.map((s) => (
                <li
                  key={s.id}
                  onClick={() => setActiveId(s.id)}
                  className={`group px-3 py-2 cursor-pointer text-sm flex items-start gap-2 border-l-2 ${
                    activeId === s.id
                      ? 'bg-background border-primary'
                      : 'border-transparent hover:bg-background/60'
                  }`}
                >
                  <MessageSquare className="w-4 h-4 mt-0.5 shrink-0 text-muted-foreground" />
                  <div className="flex-1 min-w-0">
                    <p className="truncate">{s.title || 'New chat'}</p>
                    <p className="text-xs text-muted-foreground">{formatDate(s.updatedAt)}</p>
                  </div>
                  <button
                    onClick={(e) => handleDeleteSession(s.id, e)}
                    className="opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-red-600 transition"
                    title="Delete chat"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>

      {/* Main */}
      <div className="flex-1 flex flex-col">
        <div className="border-b px-6 py-4">
          <h1 className="text-xl font-semibold">
            {sessions.find((s) => s.id === activeId)?.title || 'Chat'}
          </h1>
          <p className="text-sm text-muted-foreground">
            Ask me to find, summarize, or organize your documents.
          </p>
        </div>

        <div ref={scrollRef} className="flex-1 overflow-y-auto p-6 space-y-4">
          {loadingMessages ? (
            <p className="text-sm text-muted-foreground">Loading messages…</p>
          ) : messages.length === 0 && !sending ? (
            <div className="text-center text-muted-foreground mt-20">
              <p className="text-lg font-medium mb-2">No messages yet</p>
              <p className="text-sm">Try: "List my family members" or "Find my Aadhaar"</p>
            </div>
          ) : (
            messages.map((msg) => (
              <div
                key={msg.id}
                className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
              >
                <div
                  className={`max-w-[70%] rounded-lg px-4 py-2 text-sm ${
                    msg.role === 'user'
                      ? 'bg-primary text-primary-foreground whitespace-pre-wrap'
                      : 'bg-muted text-foreground'
                  }`}
                >
                  {msg.role === 'assistant' ? (
                    <ReactMarkdown
                      components={{
                        p: ({ children }) => <p className="my-1 first:mt-0 last:mb-0">{children}</p>,
                        ul: ({ children }) => <ul className="list-disc pl-5 my-1 space-y-0.5">{children}</ul>,
                        ol: ({ children }) => <ol className="list-decimal pl-5 my-1 space-y-0.5">{children}</ol>,
                        li: ({ children }) => <li className="my-0">{children}</li>,
                        h1: ({ children }) => <h1 className="text-base font-semibold mt-2 mb-1">{children}</h1>,
                        h2: ({ children }) => <h2 className="text-sm font-semibold mt-2 mb-1">{children}</h2>,
                        h3: ({ children }) => <h3 className="text-sm font-semibold mt-2 mb-1">{children}</h3>,
                        strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
                        em: ({ children }) => <em className="italic">{children}</em>,
                        code: ({ children }) => (
                          <code className="bg-background px-1 py-0.5 rounded text-xs">{children}</code>
                        ),
                        a: ({ href, children }) => (
                          <a href={href} className="underline" target="_blank" rel="noreferrer">{children}</a>
                        ),
                      }}
                    >
                      {msg.content}
                    </ReactMarkdown>
                  ) : (
                    msg.content
                  )}
                </div>
              </div>
            ))
          )}
          {sending && (
            <div className="flex justify-start">
              <div className="bg-muted text-foreground rounded-lg px-4 py-2 text-sm italic">
                Thinking…
              </div>
            </div>
          )}
          {error && (
            <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700">
              {error}
            </div>
          )}
        </div>

        <div className="border-t p-4 space-y-2">
          {attachment && (
            <div className="flex items-center justify-between bg-muted px-3 py-1.5 rounded-md text-xs">
              <span className="truncate">📎 {attachment.name} ({Math.round(attachment.size/1024)} KB)</span>
              <button
                onClick={() => setAttachment(null)}
                className="text-muted-foreground hover:text-red-600 ml-2"
                title="Remove attachment"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          )}
          <div className="flex gap-2 items-center">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={() => fileInputRef.current?.click()}
              disabled={sending}
              title="Attach file"
            >
              <Paperclip className="w-4 h-4" />
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={() => cameraInputRef.current?.click()}
              disabled={sending}
              title="Scan with camera"
            >
              <Camera className="w-4 h-4" />
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              className="hidden"
              onChange={(e) => setAttachment(e.target.files?.[0] ?? null)}
            />
            <input
              ref={cameraInputRef}
              type="file"
              accept="image/*"
              capture="environment"
              className="hidden"
              onChange={(e) => setAttachment(e.target.files?.[0] ?? null)}
            />
            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSend()}
              placeholder={attachment ? 'Describe the document (optional)…' : 'Ask about your documents...'}
              disabled={sending}
              className="flex-1 rounded-lg border border-input bg-background px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
            />
            <Button onClick={handleSend} size="icon" disabled={sending || (!input.trim() && !attachment)}>
              <Send className="w-4 h-4" />
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
