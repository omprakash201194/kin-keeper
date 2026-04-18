import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { Button } from '@/components/ui/button'
import { Send } from 'lucide-react'
import apiClient from '@/services/api'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
}

export default function ChatPage() {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, sending])

  async function handleSend() {
    const text = input.trim()
    if (!text || sending) return

    const userMsg: Message = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: text,
    }
    const nextMessages = [...messages, userMsg]
    setMessages(nextMessages)
    setInput('')
    setError(null)
    setSending(true)

    try {
      const history = nextMessages.slice(0, -1).map((m) => ({ role: m.role, text: m.content }))
      const res = await apiClient.post<{ reply: string }>('/chat/message', {
        message: text,
        history,
      })
      setMessages((prev) => [
        ...prev,
        { id: `a-${Date.now()}`, role: 'assistant', content: res.data.reply },
      ])
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Chat failed. Have you added your Claude API key in Settings?')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="flex flex-col h-full">
      <div className="border-b px-6 py-4">
        <h1 className="text-xl font-semibold">Chat</h1>
        <p className="text-sm text-muted-foreground">
          Ask me to find, summarize, or organize your documents.
        </p>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-6 space-y-4">
        {messages.length === 0 && !sending && (
          <div className="text-center text-muted-foreground mt-20">
            <p className="text-lg font-medium mb-2">No messages yet</p>
            <p className="text-sm">Try: "List my family members" or "Find my Aadhaar"</p>
          </div>
        )}
        {messages.map((msg) => (
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
        ))}
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

      <div className="border-t p-4">
        <div className="flex gap-2">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSend()}
            placeholder="Ask about your documents..."
            disabled={sending}
            className="flex-1 rounded-lg border border-input bg-background px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
          />
          <Button onClick={handleSend} size="icon" disabled={sending || !input.trim()}>
            <Send className="w-4 h-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}
