import { useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { FolderTree, Plus, Trash2 } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'
import {
  Category,
  flattenCategoryTree,
  isDefaultCategory,
} from '@/lib/categoryTree'

export default function CategoriesPage() {
  const { isAdmin } = useProfile()

  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const [showAdd, setShowAdd] = useState(false)
  const [newName, setNewName] = useState('')
  const [newParentId, setNewParentId] = useState<string>('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    void load()
  }, [])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const res = await apiClient.get<Category[]>('/categories')
      setCategories(res.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load categories')
    } finally {
      setLoading(false)
    }
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (!newName.trim()) return
    setSubmitting(true)
    setError(null)
    setStatus(null)
    try {
      await apiClient.post('/categories', {
        name: newName.trim(),
        parentId: newParentId || null,
      })
      setNewName('')
      setNewParentId('')
      setShowAdd(false)
      setStatus('Category created.')
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to create category')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleDelete(c: Category) {
    if (!confirm(`Delete "${c.name}"?`)) return
    setError(null)
    setStatus(null)
    try {
      await apiClient.delete(`/categories/${c.id}`)
      setStatus(`Deleted "${c.name}".`)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete category')
    }
  }

  const tree = useMemo(() => flattenCategoryTree(categories), [categories])

  if (loading) {
    return <div className="p-6 text-sm text-muted-foreground">Loading…</div>
  }

  return (
    <div className="p-6 max-w-2xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Categories</h1>
          <p className="text-sm text-muted-foreground">
            Organise your documents by category. You can nest sub-categories under any parent.
          </p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowAdd((v) => !v)}>
            <Plus className="w-4 h-4 mr-2" />
            {showAdd ? 'Cancel' : 'Add Category'}
          </Button>
        )}
      </div>

      {showAdd && isAdmin && (
        <form onSubmit={handleCreate} className="mb-6 space-y-3 border rounded-md p-4">
          <input
            type="text"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="Category name (e.g. Passport)"
            className="w-full rounded-md border px-3 py-2 text-sm"
            required
          />
          <select
            value={newParentId}
            onChange={(e) => setNewParentId(e.target.value)}
            className="w-full rounded-md border px-3 py-2 text-sm"
          >
            <option value="">No parent (top-level)</option>
            {tree.map((c) => (
              <option key={c.id} value={c.id}>
                {'\u00A0\u00A0'.repeat(c.depth) + (c.depth > 0 ? '└ ' : '') + c.name}
              </option>
            ))}
          </select>
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create'}
          </Button>
        </form>
      )}

      {status && <p className="mb-4 text-sm text-emerald-700">{status}</p>}
      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

      {tree.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-12 text-muted-foreground border rounded-md">
          <FolderTree className="w-10 h-10 mb-3" />
          <p className="text-sm">No categories yet.</p>
        </div>
      ) : (
        <ul className="border rounded-md divide-y">
          {tree.map((c) => (
            <li
              key={c.id}
              className="px-4 py-2.5 flex items-center justify-between"
              style={{ paddingLeft: 16 + c.depth * 20 }}
            >
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-sm">{c.name}</span>
                {isDefaultCategory(c) && (
                  <span className="text-xs px-2 py-0.5 rounded bg-muted text-muted-foreground">Default</span>
                )}
              </div>
              {isAdmin && !isDefaultCategory(c) && (
                <button
                  onClick={() => handleDelete(c)}
                  className="text-muted-foreground hover:text-red-600"
                  title="Delete"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
