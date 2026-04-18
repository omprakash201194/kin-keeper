import { useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Upload, FileText, Download, Trash2 } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'
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
  uploadedAt: string
}

export default function DocumentsPage() {
  const { isAdmin } = useProfile()
  const [loading, setLoading] = useState(true)
  const [members, setMembers] = useState<Member[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [documents, setDocuments] = useState<DocumentRow[]>([])

  const [memberFilter, setMemberFilter] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('')

  const [showUpload, setShowUpload] = useState(false)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadMemberId, setUploadMemberId] = useState('')
  const [uploadCategoryId, setUploadCategoryId] = useState('')
  const [uploadNotes, setUploadNotes] = useState('')
  const [uploading, setUploading] = useState(false)

  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void loadAll()
  }, [])

  useEffect(() => {
    void reloadDocuments()
  }, [memberFilter, categoryFilter])

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
      const res = await apiClient.get<DocumentRow[]>('/documents', { params })
      setDocuments(res.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load documents')
    }
  }

  async function handleUpload(e: React.FormEvent) {
    e.preventDefault()
    if (!uploadFile || !uploadMemberId || !uploadCategoryId) return

    const form = new FormData()
    form.append('file', uploadFile)
    form.append('memberId', uploadMemberId)
    form.append('categoryId', uploadCategoryId)
    if (uploadNotes.trim()) form.append('notes', uploadNotes.trim())

    setUploading(true)
    setError(null)
    try {
      await apiClient.post('/documents/upload', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      setUploadFile(null)
      setUploadMemberId('')
      setUploadCategoryId('')
      setUploadNotes('')
      setShowUpload(false)
      await reloadDocuments()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Upload failed')
    } finally {
      setUploading(false)
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
        <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          Add at least one family member before uploading documents.
        </div>
      )}

      {isAdmin && showUpload && canUpload && (
        <form onSubmit={handleUpload} className="mb-6 max-w-xl space-y-3 border rounded-md p-4">
          <input
            type="file"
            onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)}
            className="w-full text-sm"
            required
          />
          <div className="grid grid-cols-2 gap-3">
            <select
              value={uploadMemberId}
              onChange={(e) => setUploadMemberId(e.target.value)}
              className="rounded-md border px-3 py-2 text-sm"
              required
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
              required
            >
              <option value="">Select category…</option>
              {categoryTree.map((c) => (
                <option key={c.id} value={c.id}>
                  {'\u00A0\u00A0'.repeat(c.depth) + (c.depth > 0 ? '└ ' : '') + c.name}
                </option>
              ))}
            </select>
          </div>
          <textarea
            value={uploadNotes}
            onChange={(e) => setUploadNotes(e.target.value)}
            placeholder="Notes (optional)"
            className="w-full rounded-md border px-3 py-2 text-sm"
            rows={2}
          />
          <Button type="submit" disabled={uploading}>
            {uploading ? 'Uploading…' : 'Upload'}
          </Button>
        </form>
      )}

      <div className="flex gap-2 mb-4">
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
      </div>

      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

      {documents.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
          <FileText className="w-12 h-12 mb-4" />
          <p className="text-lg font-medium mb-1">No documents yet</p>
          <p className="text-sm">Upload your first document or use the chat to get started.</p>
        </div>
      ) : (
        <ul className="divide-y border rounded-md">
          {documents.map((doc) => (
            <li key={doc.id} className="px-4 py-3 flex items-center justify-between gap-3">
              <div className="flex items-center gap-3 min-w-0">
                <FileText className="w-5 h-5 text-muted-foreground shrink-0" />
                <div className="min-w-0">
                  <p className="font-medium truncate">{doc.fileName}</p>
                  <p className="text-xs text-muted-foreground">
                    {memberName(doc.memberId)} · {categoryName(doc.categoryId)} · {formatSize(doc.fileSize)}
                  </p>
                </div>
              </div>
              <div className="flex gap-1 shrink-0">
                <Button variant="ghost" size="icon" onClick={() => handleDownload(doc)} title="Download">
                  <Download className="w-4 h-4" />
                </Button>
                {isAdmin && (
                  <Button variant="ghost" size="icon" onClick={() => handleDelete(doc)} title="Delete">
                    <Trash2 className="w-4 h-4" />
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
