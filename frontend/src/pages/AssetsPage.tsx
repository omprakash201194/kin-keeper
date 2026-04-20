import { useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Plus, Trash2, Home, Car, Cpu, Shield, Pencil, Receipt, ChevronDown, ChevronRight } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'

type AssetType = 'HOME' | 'VEHICLE' | 'APPLIANCE' | 'POLICY'

type Asset = {
  id: string
  type: AssetType
  name: string
  make?: string
  model?: string
  identifier?: string
  address?: string
  provider?: string
  purchaseDate?: string
  expiryDate?: string
  frequency?: string
  amount?: number
  odometerKm?: number
  notes?: string
}

const TYPE_LABEL: Record<AssetType, string> = {
  HOME: 'Homes',
  VEHICLE: 'Vehicles',
  APPLIANCE: 'Appliances',
  POLICY: 'Policies & Subscriptions',
}

const TYPE_ICON: Record<AssetType, React.ComponentType<{ className?: string }>> = {
  HOME: Home,
  VEHICLE: Car,
  APPLIANCE: Cpu,
  POLICY: Shield,
}

const EMPTY_FORM = {
  type: 'HOME' as AssetType,
  name: '',
  make: '',
  model: '',
  identifier: '',
  address: '',
  provider: '',
  purchaseDate: '',
  expiryDate: '',
  frequency: '',
  amount: '',
  odometerKm: '',
  notes: '',
}

type Bill = {
  id: string
  assetId: string
  dueAt?: string
  paidAt?: string
  amount?: number
  currency?: string
  source?: 'MANUAL' | 'SMS' | 'EMAIL' | 'CHAT'
  notes?: string
}

export default function AssetsPage() {
  const { isAdmin } = useProfile()
  const [assets, setAssets] = useState<Asset[]>([])
  const [bills, setBills] = useState<Bill[]>([])
  const [openBillsFor, setOpenBillsFor] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [showAdd, setShowAdd] = useState(false)
  const [form, setForm] = useState(EMPTY_FORM)
  const [saving, setSaving] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)

  useEffect(() => { void load() }, [])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [assetsRes, billsRes] = await Promise.all([
        apiClient.get<Asset[]>('/assets'),
        apiClient.get<Bill[]>('/bills').catch(() => ({ data: [] as Bill[] })),
      ])
      setAssets(assetsRes.data)
      setBills(billsRes.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load assets')
    } finally { setLoading(false) }
  }

  function toggleBills(assetId: string) {
    setOpenBillsFor((prev) => {
      const next = new Set(prev)
      if (next.has(assetId)) next.delete(assetId)
      else next.add(assetId)
      return next
    })
  }

  async function handleDeleteBill(id: string) {
    if (!confirm('Delete this bill entry?')) return
    try {
      await apiClient.delete(`/bills/${id}`)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete bill')
    }
  }

  function formatMoney(amount?: number, currency?: string): string {
    if (amount == null) return '—'
    const cur = currency || 'INR'
    const symbol = cur === 'INR' ? '₹' : cur === 'USD' ? '$' : cur === 'EUR' ? '€' : ''
    return symbol ? `${symbol}${amount.toLocaleString()}` : `${amount.toLocaleString()} ${cur}`
  }

  function formatDate(iso?: string): string {
    if (!iso) return ''
    return new Date(iso).toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' })
  }

  function billsFor(assetId: string): Bill[] {
    return bills
      .filter((b) => b.assetId === assetId)
      .sort((a, b) => new Date(b.dueAt ?? 0).getTime() - new Date(a.dueAt ?? 0).getTime())
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!form.name.trim()) return
    setSaving(true)
    setError(null)
    try {
      const body: Record<string, unknown> = { type: form.type, name: form.name.trim() }
      for (const k of ['make', 'model', 'identifier', 'address', 'provider', 'purchaseDate',
                        'expiryDate', 'frequency', 'notes'] as const) {
        const v = form[k]
        if (v && v.trim()) body[k] = v.trim()
      }
      if (form.amount && form.amount.trim()) body.amount = Number(form.amount)
      if (form.odometerKm && form.odometerKm.trim()) body.odometerKm = parseInt(form.odometerKm, 10)
      if (editingId) {
        await apiClient.put(`/assets/${editingId}`, body)
      } else {
        await apiClient.post('/assets', body)
      }
      resetForm()
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to save asset')
    } finally { setSaving(false) }
  }

  function handleEdit(a: Asset) {
    setForm({
      type: a.type,
      name: a.name ?? '',
      make: a.make ?? '',
      model: a.model ?? '',
      identifier: a.identifier ?? '',
      address: a.address ?? '',
      provider: a.provider ?? '',
      purchaseDate: a.purchaseDate ?? '',
      expiryDate: a.expiryDate ?? '',
      frequency: a.frequency ?? '',
      amount: a.amount != null ? String(a.amount) : '',
      odometerKm: a.odometerKm != null ? String(a.odometerKm) : '',
      notes: a.notes ?? '',
    })
    setEditingId(a.id)
    setShowAdd(true)
    if (typeof window !== 'undefined') window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  function resetForm() {
    setForm(EMPTY_FORM)
    setShowAdd(false)
    setEditingId(null)
  }

  async function handleDelete(a: Asset) {
    if (!confirm(`Delete "${a.name}"?`)) return
    try {
      await apiClient.delete(`/assets/${a.id}`)
      await load()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete')
    }
  }

  const grouped = useMemo(() => {
    const g: Record<AssetType, Asset[]> = { HOME: [], VEHICLE: [], APPLIANCE: [], POLICY: [] }
    for (const a of assets) g[a.type]?.push(a)
    return g
  }, [assets])

  if (loading) return <div className="p-6 text-sm text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 max-w-3xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">Assets</h1>
          <p className="text-sm text-muted-foreground">
            Things you own that have documents: homes, vehicles, appliances, policies.
          </p>
        </div>
        {isAdmin && (
          <Button onClick={() => (showAdd ? resetForm() : setShowAdd(true))}>
            <Plus className="w-4 h-4 mr-2" />
            {showAdd ? 'Cancel' : 'Add Asset'}
          </Button>
        )}
      </div>

      {showAdd && isAdmin && (
        <form onSubmit={handleSubmit} className="mb-6 space-y-3 border rounded-md p-4">
          {editingId && (
            <p className="text-xs text-muted-foreground -mb-1">Editing existing asset.</p>
          )}
          <div className="grid grid-cols-2 gap-2">
            <select className="rounded-md border px-3 py-2 text-sm" disabled={!!editingId}
                    value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as AssetType })}>
              <option value="HOME">Home</option>
              <option value="VEHICLE">Vehicle</option>
              <option value="APPLIANCE">Appliance</option>
              <option value="POLICY">Policy / Subscription</option>
            </select>
            <input className="rounded-md border px-3 py-2 text-sm" placeholder="Name" required
                   value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </div>

          {form.type === 'HOME' && (
            <textarea className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Address" rows={2}
                      value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
          )}

          {(form.type === 'VEHICLE' || form.type === 'APPLIANCE') && (
            <div className="grid grid-cols-3 gap-2">
              <input className="rounded-md border px-3 py-2 text-sm" placeholder="Make"
                     value={form.make} onChange={(e) => setForm({ ...form, make: e.target.value })} />
              <input className="rounded-md border px-3 py-2 text-sm" placeholder="Model"
                     value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
              <input className="rounded-md border px-3 py-2 text-sm"
                     placeholder={form.type === 'VEHICLE' ? 'Reg / VIN' : 'Serial #'}
                     value={form.identifier} onChange={(e) => setForm({ ...form, identifier: e.target.value })} />
            </div>
          )}

          {form.type === 'VEHICLE' && (
            <input className="w-full rounded-md border px-3 py-2 text-sm" type="number"
                   placeholder="Odometer (km)"
                   value={form.odometerKm} onChange={(e) => setForm({ ...form, odometerKm: e.target.value })} />
          )}

          {form.type === 'POLICY' && (
            <>
              <div className="grid grid-cols-2 gap-2">
                <input className="rounded-md border px-3 py-2 text-sm" placeholder="Provider (insurer / ISP / utility / bank)"
                       value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })} />
                <input className="rounded-md border px-3 py-2 text-sm" placeholder="Policy / account / customer #"
                       value={form.identifier} onChange={(e) => setForm({ ...form, identifier: e.target.value })} />
              </div>
              <div className="grid grid-cols-2 gap-2">
                <select className="rounded-md border px-3 py-2 text-sm"
                        value={form.frequency} onChange={(e) => setForm({ ...form, frequency: e.target.value })}>
                  <option value="">Frequency…</option>
                  <option value="MONTHLY">Monthly</option>
                  <option value="QUARTERLY">Quarterly</option>
                  <option value="YEARLY">Yearly</option>
                </select>
                <input className="rounded-md border px-3 py-2 text-sm" type="number"
                       placeholder="Premium amount"
                       value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
              </div>
            </>
          )}

          <div className="grid grid-cols-2 gap-2">
            <input className="rounded-md border px-3 py-2 text-sm" type="date"
                   placeholder="Purchase / start date"
                   value={form.purchaseDate} onChange={(e) => setForm({ ...form, purchaseDate: e.target.value })} />
            <input className="rounded-md border px-3 py-2 text-sm" type="date"
                   placeholder="Warranty / policy end"
                   value={form.expiryDate} onChange={(e) => setForm({ ...form, expiryDate: e.target.value })} />
          </div>

          <textarea className="w-full rounded-md border px-3 py-2 text-sm" placeholder="Notes" rows={2}
                    value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />

          <div className="flex gap-2">
            <Button type="submit" disabled={saving}>
              {saving ? 'Saving…' : editingId ? 'Save changes' : 'Save'}
            </Button>
            {editingId && (
              <Button type="button" variant="ghost" onClick={resetForm}>Cancel</Button>
            )}
          </div>
        </form>
      )}

      {error && <p className="mb-4 text-sm text-red-400">{error}</p>}

      {assets.length === 0 ? (
        <div className="py-12 text-center text-muted-foreground border rounded-md">No assets yet.</div>
      ) : (
        (['HOME', 'VEHICLE', 'APPLIANCE', 'POLICY'] as AssetType[]).map((t) => {
          const list = grouped[t]
          if (!list.length) return null
          const Icon = TYPE_ICON[t]
          return (
            <section key={t} className="mb-6">
              <h2 className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-muted-foreground mb-2">
                <Icon className="w-4 h-4" />
                {TYPE_LABEL[t]} <span className="font-normal normal-case">({list.length})</span>
              </h2>
              <ul className="divide-y border rounded-md">
                {list.map((a) => {
                  const assetBills = t === 'POLICY' ? billsFor(a.id) : []
                  const lastBill = assetBills[0]
                  const total = assetBills.reduce((s, b) => s + (b.amount ?? 0), 0)
                  const isOpen = openBillsFor.has(a.id)
                  return (
                    <li key={a.id}>
                      <div className="px-4 py-3 flex items-center justify-between gap-3">
                        <div className="min-w-0">
                          <p className="font-medium">{a.name}</p>
                          <p className="text-xs text-muted-foreground">
                            {[a.make, a.model, a.identifier, a.provider, a.address,
                              a.odometerKm ? `${a.odometerKm} km` : null,
                              a.frequency, a.expiryDate ? `next ${a.expiryDate}` : null]
                              .filter(Boolean).join(' · ')}
                          </p>
                          {t === 'POLICY' && (
                            <button
                              type="button"
                              onClick={() => toggleBills(a.id)}
                              className="mt-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition"
                            >
                              {isOpen ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                              <Receipt className="w-3 h-3" />
                              {assetBills.length === 0
                                ? 'No bills logged'
                                : `${assetBills.length} bill${assetBills.length === 1 ? '' : 's'}` +
                                  (lastBill
                                    ? ` · last ${formatMoney(lastBill.amount, lastBill.currency)} on ${formatDate(lastBill.dueAt)}`
                                    : '')}
                            </button>
                          )}
                        </div>
                        {isAdmin && (
                          <div className="flex gap-1 shrink-0">
                            <Button variant="ghost" size="icon" onClick={() => handleEdit(a)} title="Edit">
                              <Pencil className="w-4 h-4" />
                            </Button>
                            <Button variant="ghost" size="icon" onClick={() => handleDelete(a)} title="Delete">
                              <Trash2 className="w-4 h-4" />
                            </Button>
                          </div>
                        )}
                      </div>
                      {t === 'POLICY' && isOpen && (
                        <div className="px-4 pb-3 pt-1">
                          {assetBills.length === 0 ? (
                            <p className="text-xs text-muted-foreground italic">
                              No bills logged yet. Share an SMS or bill into the app to start the history.
                            </p>
                          ) : (
                            <>
                              <ul className="divide-y border rounded-md text-sm bg-background/40">
                                {assetBills.map((b) => (
                                  <li key={b.id} className="px-3 py-2 flex items-center gap-3">
                                    <span className="text-xs text-muted-foreground w-28 shrink-0">
                                      {formatDate(b.dueAt)}
                                    </span>
                                    <span className="font-medium flex-1">
                                      {formatMoney(b.amount, b.currency)}
                                    </span>
                                    {b.source && (
                                      <span className="text-[10px] uppercase tracking-wide text-muted-foreground">
                                        {b.source}
                                      </span>
                                    )}
                                    {isAdmin && (
                                      <button
                                        type="button"
                                        onClick={() => handleDeleteBill(b.id)}
                                        className="text-muted-foreground hover:text-red-400"
                                        title="Delete bill entry"
                                      >
                                        <Trash2 className="w-3.5 h-3.5" />
                                      </button>
                                    )}
                                  </li>
                                ))}
                              </ul>
                              <p className="mt-2 text-xs text-muted-foreground">
                                Total across {assetBills.length} bill{assetBills.length === 1 ? '' : 's'}:{' '}
                                <span className="font-medium text-foreground">
                                  {formatMoney(total, lastBill?.currency)}
                                </span>
                              </p>
                            </>
                          )}
                        </div>
                      )}
                    </li>
                  )
                })}
              </ul>
            </section>
          )
        })
      )}
    </div>
  )
}
