import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Upload, FileText, Download, Trash2, Camera, FileUp, Sparkles, X, ScanText } from 'lucide-react'
import { useRef } from 'react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'
import DocumentThumbnail from '@/components/DocumentThumbnail'
import {
  Category as TreeCategory,
  flattenCategoryTree,
  categoryLabelWithAncestors,
} from '@/lib/categoryTree'

type Member = { id: string; name: string; relationship?: string }
type Category = TreeCategory
type DocumentRow = {
  id: string
  fileName: string
  mimeType: string
  fileSize: number
  memberId: string
  categoryId: string
  tags?: string[]
  uploadedAt: string
  extractedText?: string
  extractedAt?: string
}

export default function DocumentsPage() {
  const { isAdmin } = useProfile()
  const [loading, setLoading] = useState(true)
  const [members, setMembers] = useState<Member[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [documents, setDocuments] = useState<DocumentRow[]>([])

  const [memberFilter, setMemberFilter] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [labelFilter, setLabelFilter] = useState('')
  const [groupBy, setGroupBy] = useState<'none' | 'member' | 'category'>('none')

  const [showUpload, setShowUpload] = useState(false)
  const [uploadFiles, setUploadFiles] = useState<File[]>([])
  const [uploadMemberId, setUploadMemberId] = useState('')
  const [uploadCategoryId, setUploadCategoryId] = useState('')
  const [uploadNotes, setUploadNotes] = useState('')
  const [uploadLabels, setUploadLabels] = useState('')
  const [uploading, setUploading] = useState(false)
  const [aiSorting, setAiSorting] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const cameraInputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()

  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void loadAll()
  }, [])

  useEffect(() => {
    void reloadDocuments()
  }, [memberFilter, categoryFilter, labelFilter])

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [membersRes, categoriesRes] = await Promise.all([
        apiClient.get<Member[]>('/family/members'),
        apiClient.get<Category[]>('/categories'),
      ])
      setMembers(membersRes.data)
      setCategories(categoriesRes.data)
      await reloadDocuments()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load documents')
    } finally {
      setLoading(false)
    }
  }

  async function reloadDocuments() {
    try {
      const params: Record<string, string> = {}
      if (memberFilter) params.memberId = memberFilter
      if (categoryFilter) params.categoryId = categoryFilter
      if (labelFilter) params.label = labelFilter
      const res = await apiClient.get<DocumentRow[]>('/documents', { params })
      setDocuments(res.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load documents')
    }
  }

  function appendFiles(picked: FileList | null) {
    if (!picked || picked.length === 0) return
    setUploadFiles((prev) => [...prev, ...Array.from(picked)])
  }

  function removeFileAt(i: number) {
    setUploadFiles((prev) => prev.filter((_, idx) => idx !== i))
  }

  function resetUploadForm() {
    setUploadFiles([])
    setUploadMemberId('')
    setUploadCategoryId('')
    setUploadNotes('')
    setUploadLabels('')
    setShowUpload(false)
  }

  async function handleUpload(e: React.FormEvent) {
    e.preventDefault()
    if (uploadFiles.length === 0) {
      setError('Pick at least one file before uploading.')
      return
    }
    if (!uploadMemberId) {
      setError('Pick a member for these files. "Let AI sort them" if you want different members per file.')
      return
    }
    if (!uploadCategoryId) {
      setError('Pick a category for these files. "Let AI sort them" if you want different categories per file.')
      return
    }

    setUploading(true)
    setError(null)
    try {
      if (uploadFiles.length === 1) {
        const form = new FormData()
        form.append('file', uploadFiles[0])
        form.append('memberId', uploadMemberId)
        form.append('categoryId', uploadCategoryId)
        if (uploadNotes.trim()) form.append('notes', uploadNotes.trim())
        if (uploadLabels.trim()) form.append('labels', uploadLabels.trim())
        await apiClient.post('/documents/upload', form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
      } else {
        const form = new FormData()
        for (const f of uploadFiles) form.append('files', f)
        form.append('memberId', uploadMemberId)
        form.append('categoryId', uploadCategoryId)
        if (uploadNotes.trim()) form.append('notes', uploadNotes.trim())
        if (uploadLabels.trim()) form.append('labels', uploadLabels.trim())
        const res = await apiClient.post<{ documents: unknown[]; failed: Array<{ fileName: string; error: string }> }>(
          '/documents/bulk-upload', form, { headers: { 'Content-Type': 'multipart/form-data' } }
        )
        if (res.data.failed?.length) {
          setError(`Uploaded ${res.data.documents.length}/${uploadFiles.length}. Failed: ${res.data.failed.map((f) => f.fileName).join(', ')}`)
        }
      }
      resetUploadForm()
      await reloadDocuments()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  /**
   * Stage every selected file as a chat attachment, start a new chat session,
   * send a "please classify these" prompt, and navigate to the chat. The agent
   * handles the rest via save_attachment per file.
   */
  async function handleAiSort() {
    if (uploadFiles.length === 0) return
    setAiSorting(true)
    setError(null)
    try {
      const attachmentIds: string[] = []
      for (const f of uploadFiles) {
        const form = new FormData()
        form.append('file', f)
        const up = await apiClient.post<{ attachmentId: string }>('/chat/attachments', form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
        attachmentIds.push(up.data.attachmentId)
      }
      const session = await apiClient.post<{ id: string }>('/chat/sessions')
      const n = uploadFiles.length
      const prompt = `I'm uploading ${n} document${n === 1 ? '' : 's'}. Please inspect each one, pick the right category (create a new one if needed), decide which family member or asset it belongs to, and save each with save_attachment. Report what you did.`
      void apiClient.post(`/chat/sessions/${session.data.id}/message`, {
        message: prompt,
        attachmentIds,
      })
      navigate('/chat')
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'AI sort failed. Is your Claude API key set?')
    } finally {
      setAiSorting(false)
    }
  }

  async function handleDownload(doc: DocumentRow) {
    try {
      const res = await apiClient.get(`/documents/${doc.id}/download`, { responseType: 'blob' })
      const url = URL.createObjectURL(res.data as Blob)
      const a = document.createElement('a')
      a.href = url
      a.download = doc.fileName
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e: any) {
      setError('Download failed')
    }
  }

  async function handleReindex(doc: DocumentRow) {
    try {
      setError(null)
      await apiClient.post(`/documents/${doc.id}/reindex`)
      await reloadDocuments()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Reindex failed')
    }
  }

  async function handleDelete(doc: DocumentRow) {
    if (!confirm(`Delete "${doc.fileName}"? This removes it from Google Drive too.`)) return
    try {
      await apiClient.delete(`/documents/${doc.id}`)
      await reloadDocuments()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Delete failed')
    }
  }

  const categoryTree = useMemo(() => flattenCategoryTree(categories), [categories])

  const allLabels = useMemo(() => {
    const s = new Set<string>()
    for (const d of documents) for (const t of d.tags ?? []) if (t) s.add(t)
    return Array.from(s).sort((a, b) => a.localeCompare(b))
  }, [documents])

  const groups = useMemo(() => {
    if (groupBy === 'none') return null
    const buckets = new Map<string, DocumentRow[]>()
    for (const d of documents) {
      const key = groupBy === 'member' ? d.memberId : d.categoryId
      const effectiveKey = key ?? ''
      const list = buckets.get(effectiveKey) ?? []
      list.push(d)
      buckets.set(effectiveKey, list)
    }
    const unknownLabel = groupBy === 'member' ? 'Unassigned' : 'Uncategorized'
    const labeled = Array.from(buckets.entries()).map(([key, items]) => ({
      key,
      label: key
        ? (groupBy === 'member' ? memberName(key) : categoryName(key))
        : unknownLabel,
      items,
    }))
    labeled.sort((a, b) => a.label.localeCompare(b.label))
    return labeled
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documents, groupBy, members, categories])

  function memberName(id: string) {
    return members.find((m) => m.id === id)?.name ?? '—'
  }
  function categoryName(id: string) {
    return categoryLabelWithAncestors(categories, id) || '—'
  }
  function formatSize(bytes: number) {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }

  if (loading) {
    return <div className="p-6 text-sm text-muted-foreground">Loading…</div>
  }

  const canUpload = members.length > 0 && categories.length > 0

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Documents</h1>
          <p className="text-sm text-muted-foreground">Browse and manage family documents.</p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowUpload((v) => !v)} disabled={!canUpload}>
            <Upload className="w-4 h-4 mr-2" />
            {showUpload ? 'Cancel' : 'Upload'}
          </Button>
        )}
      </div>

      {isAdmin && !canUpload && (
        <div className="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-300">
          Add at least one family member before uploading documents.
        </div>
      )}

      {isAdmin && showUpload && canUpload && (
        <form onSubmit={handleUpload} className="mb-6 max-w-xl space-y-3 border rounded-md p-4">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" size="sm" onClick={() => fileInputRef.current?.click()}>
              <FileUp className="w-4 h-4 mr-2" />
              Choose files
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={() => cameraInputRef.current?.click()}>
              <Camera className="w-4 h-4 mr-2" />
              Scan document
            </Button>
            <span className="text-sm text-muted-foreground self-center truncate">
              {uploadFiles.length === 0
                ? 'No files selected'
                : `${uploadFiles.length} file${uploadFiles.length > 1 ? 's' : ''} selected`}
            </span>
          </div>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            onChange={(e) => { appendFiles(e.target.files); if (fileInputRef.current) fileInputRef.current.value = '' }}
            className="hidden"
          />
          <input
            ref={cameraInputRef}
            type="file"
            accept="image/*"
            capture="environment"
            onChange={(e) => { appendFiles(e.target.files); if (cameraInputRef.current) cameraInputRef.current.value = '' }}
            className="hidden"
          />

          {uploadFiles.length > 0 && (
            <ul className="divide-y rounded-md border bg-muted/30 text-sm max-h-48 overflow-y-auto">
              {uploadFiles.map((f, i) => (
                <li key={`${f.name}-${i}`} className="px-3 py-2 flex items-center gap-2">
                  <FileText className="w-4 h-4 text-muted-foreground shrink-0" />
                  <span className="flex-1 min-w-0 truncate">{f.name}</span>
                  <span className="text-[11px] text-muted-foreground shrink-0">
                    {Math.round(f.size / 1024)} KB
                  </span>
                  <button
                    type="button"
                    onClick={() => removeFileAt(i)}
                    className="text-muted-foreground hover:text-red-400"
                    title="Remove"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="grid grid-cols-2 gap-3">
            <select
              value={uploadMemberId}
              onChange={(e) => setUploadMemberId(e.target.value)}
              className="rounded-md border px-3 py-2 text-sm"
            >
              <option value="">Select member…</option>
              {members.map((m) => (
                <option key={m.id} value={m.id}>{m.name}</option>
              ))}
            </select>
            <select
              value={uploadCategoryId}
              onChange={(e) => setUploadCategoryId(e.target.value)}
              className="rounded-md border px-3 py-2 text-sm"
            >
              <option value="">Select category…</option>
              {categoryTree.map((c) => (
                <option key={c.id} value={c.id}>
                  {'\u00A0\u00A0'.repeat(c.depth) + (c.depth > 0 ? '└ ' : '') + c.name}
                </option>
              ))}
            </select>
          </div>
          <input
            type="text"
            value={uploadLabels}
            onChange={(e) => setUploadLabels(e.target.value)}
            placeholder="Labels (comma-separated, e.g. Mumbai, Home)"
            className="w-full rounded-md border px-3 py-2 text-sm"
          />
          <textarea
            value={uploadNotes}
            onChange={(e) => setUploadNotes(e.target.value)}
            placeholder="Notes (optional — applies to every file in this upload)"
            className="w-full rounded-md border px-3 py-2 text-sm"
            rows={2}
          />

          <div className="flex flex-wrap gap-2 pt-1">
            <Button
              type="submit"
              disabled={uploading || aiSorting || uploadFiles.length === 0}
            >
              {uploading
                ? 'Uploading…'
                : uploadFiles.length > 1
                  ? `Upload all ${uploadFiles.length} to same category`
                  : 'Upload'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={handleAiSort}
              disabled={uploading || aiSorting || uploadFiles.length === 0}
              title="Stage files to a new chat and let Claude pick the right category/member per file"
            >
              <Sparkles className="w-4 h-4 mr-2" />
              {aiSorting ? 'Opening chat…' : 'Let AI sort them'}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            "Upload all to same category" needs member + category picked above. "Let AI sort them"
            ignores those and hands every file to Claude to classify individually.
          </p>
          {error && <p className="text-sm text-red-300">{error}</p>}
        </form>
      )}

      <div className="flex flex-wrap gap-2 mb-4 items-center">
        <select
          value={memberFilter}
          onChange={(e) => setMemberFilter(e.target.value)}
          className="rounded-md border px-3 py-2 text-sm"
        >
          <option value="">All members</option>
          {members.map((m) => (
            <option key={m.id} value={m.id}>{m.name}</option>
          ))}
        </select>
        <select
          value={categoryFilter}
          onChange={(e) => setCategoryFilter(e.target.value)}
          className="rounded-md border px-3 py-2 text-sm"
        >
          <option value="">All categories</option>
          {categoryTree.map((c) => (
            <option key={c.id} value={c.id}>
              {'\u00A0\u00A0'.repeat(c.depth) + (c.depth > 0 ? '└ ' : '') + c.name}
            </option>
          ))}
        </select>

        <input
          type="text"
          list="label-suggestions"
          value={labelFilter}
          onChange={(e) => setLabelFilter(e.target.value)}
          placeholder="Filter label…"
          className="rounded-md border px-3 py-2 text-sm w-36"
        />
        <datalist id="label-suggestions">
          {allLabels.map((l) => (
            <option key={l} value={l} />
          ))}
        </datalist>

        <div className="ml-auto flex items-center gap-1 text-xs">
          <span className="text-muted-foreground mr-1">Group by:</span>
          {(['none', 'member', 'category'] as const).map((opt) => (
            <button
              key={opt}
              onClick={() => setGroupBy(opt)}
              className={`px-2.5 py-1 rounded border transition ${
                groupBy === opt
                  ? 'bg-primary text-primary-foreground border-primary'
                  : 'bg-background hover:bg-muted'
              }`}
            >
              {opt === 'none' ? 'Flat' : opt === 'member' ? 'Member' : 'Category'}
            </button>
          ))}
        </div>
      </div>

      {error && <p className="mb-4 text-sm text-red-400">{error}</p>}

      {documents.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
          <FileText className="w-12 h-12 mb-4" />
          <p className="text-lg font-medium mb-1">No documents yet</p>
          <p className="text-sm">Upload your first document or use the chat to get started.</p>
        </div>
      ) : groups === null ? (
        <ul className="divide-y border rounded-md">
          {documents.map((doc) => renderRow(doc, 'none'))}
        </ul>
      ) : (
        <div className="space-y-4">
          {groups.map((g) => (
            <div key={g.key || '_unknown'} className="border rounded-md">
              <div className="px-4 py-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground bg-muted/40 border-b">
                {g.label} <span className="text-[11px] font-normal normal-case">({g.items.length})</span>
              </div>
              <ul className="divide-y">
                {g.items.map((doc) => renderRow(doc, groupBy))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  )

  function renderRow(doc: DocumentRow, currentGroup: 'none' | 'member' | 'category') {
    const meta: string[] = []
    if (currentGroup !== 'member') meta.push(memberName(doc.memberId))
    if (currentGroup !== 'category') meta.push(categoryName(doc.categoryId))
    meta.push(formatSize(doc.fileSize))

    return (
      <li key={doc.id} className="px-4 py-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <DocumentThumbnail documentId={doc.id} mimeType={doc.mimeType} size="md" />
          <div className="min-w-0">
            <p className="font-medium truncate">{doc.fileName}</p>
            <p className="text-xs text-muted-foreground">{meta.join(' · ')}</p>
            {doc.tags && doc.tags.length > 0 && (
              <div className="mt-1 flex flex-wrap gap-1">
                {doc.tags.map((t) => (
                  <span
                    key={t}
                    onClick={() => setLabelFilter(t)}
                    className="text-[11px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground cursor-pointer hover:bg-primary hover:text-primary-foreground transition"
                    title={`Filter by label "${t}"`}
                  >
                    {t}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
        <div className="flex gap-1 shrink-0">
          <Button variant="ghost" size="icon" onClick={() => handleDownload(doc)} title="Download">
            <Download className="w-4 h-4" />
          </Button>
          {isAdmin && (
            <Button
              variant="ghost"
              size="icon"
              onClick={() => handleReindex(doc)}
              title={doc.extractedText ? 'Text indexed — re-extract' : 'Extract text for search'}
            >
              <ScanText className={`w-4 h-4 ${doc.extractedText ? 'text-emerald-400' : ''}`} />
            </Button>
          )}
          {isAdmin && (
            <Button variant="ghost" size="icon" onClick={() => handleDelete(doc)} title="Delete">
              <Trash2 className="w-4 h-4" />
            </Button>
          )}
        </div>
      </li>
    )
  }
}
