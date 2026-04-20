import { useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Apple, Camera, Trash2, AlertTriangle, Leaf, X } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'

type NutritionSource = 'PACKAGED' | 'RAW' | 'COOKED' | 'DRINK' | 'OTHER'

type NutritionFacts = {
  servingDescription?: string
  calories?: number | null
  proteinG?: number | null
  carbsG?: number | null
  sugarG?: number | null
  fatG?: number | null
  saturatedFatG?: number | null
  fiberG?: number | null
  sodiumMg?: number | null
}

type NutritionEntry = {
  id: string
  familyId: string
  memberId: string
  consumedAt: string
  foodName: string
  description?: string
  source: NutritionSource
  facts?: NutritionFacts
  ingredients: string[]
  healthBenefits: string[]
  warnings: string[]
}

type FamilyMember = { id: string; name: string; relationship?: string }

const sourceLabel: Record<NutritionSource, string> = {
  PACKAGED: 'Packaged',
  RAW:      'Raw',
  COOKED:   'Cooked',
  DRINK:    'Drink',
  OTHER:    'Other',
}

function fmt(n?: number | null, digits = 0): string {
  if (n == null || Number.isNaN(n)) return '—'
  return n.toFixed(digits)
}

function dayKey(iso: string): string {
  return iso.slice(0, 10)
}

export default function NutritionPage() {
  const { isAdmin } = useProfile()

  const [loading, setLoading] = useState(true)
  const [entries, setEntries] = useState<NutritionEntry[]>([])
  const [members, setMembers] = useState<FamilyMember[]>([])
  const [memberFilter, setMemberFilter] = useState<string>('all')
  const [error, setError] = useState<string | null>(null)

  const [draft, setDraft] = useState<NutritionEntry | null>(null)
  const [draftMember, setDraftMember] = useState<string>('')
  const [analyzing, setAnalyzing] = useState(false)
  const [saving, setSaving] = useState(false)

  const fileInputRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => { void refresh() }, [])

  async function refresh() {
    setLoading(true)
    setError(null)
    try {
      const [entriesRes, membersRes] = await Promise.all([
        apiClient.get<NutritionEntry[]>('/nutrition'),
        apiClient.get<FamilyMember[]>('/family/members'),
      ])
      setEntries(entriesRes.data)
      setMembers(membersRes.data)
      if (!draftMember && membersRes.data.length > 0) {
        setDraftMember(membersRes.data[0].id)
      }
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load nutrition entries')
    } finally {
      setLoading(false)
    }
  }

  const visible = useMemo(
    () => memberFilter === 'all'
      ? entries
      : entries.filter((e) => e.memberId === memberFilter),
    [entries, memberFilter]
  )

  const grouped = useMemo(() => {
    const byDay = new Map<string, NutritionEntry[]>()
    for (const e of visible) {
      const key = dayKey(e.consumedAt)
      const list = byDay.get(key) ?? []
      list.push(e)
      byDay.set(key, list)
    }
    return [...byDay.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1))
  }, [visible])

  const todaysTotals = useMemo(() => {
    const today = new Date().toISOString().slice(0, 10)
    const todays = visible.filter((e) => dayKey(e.consumedAt) === today)
    return todays.reduce(
      (acc, e) => {
        const f = e.facts ?? {}
        acc.calories += f.calories ?? 0
        acc.proteinG += f.proteinG ?? 0
        acc.carbsG   += f.carbsG ?? 0
        acc.sugarG   += f.sugarG ?? 0
        acc.fatG     += f.fatG ?? 0
        return acc
      },
      { calories: 0, proteinG: 0, carbsG: 0, sugarG: 0, fatG: 0 }
    )
  }, [visible])

  async function handleFilePicked(file: File) {
    setError(null)
    setAnalyzing(true)
    setDraft(null)
    try {
      const form = new FormData()
      form.append('file', file)
      const res = await apiClient.post<NutritionEntry>('/nutrition/analyze', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      setDraft(res.data)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Analysis failed — make sure your Claude API key is set in Settings.')
    } finally {
      setAnalyzing(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  async function handleSave() {
    if (!draft || !draftMember) return
    setSaving(true)
    setError(null)
    try {
      await apiClient.post('/nutrition', {
        memberId: draftMember,
        consumedAt: new Date().toISOString(),
        foodName: draft.foodName,
        description: draft.description,
        source: draft.source,
        facts: draft.facts,
        ingredients: draft.ingredients,
        healthBenefits: draft.healthBenefits,
        warnings: draft.warnings,
      })
      setDraft(null)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to save entry')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(id: string) {
    if (!confirm('Delete this nutrition entry?')) return
    try {
      await apiClient.delete(`/nutrition/${id}`)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to delete entry')
    }
  }

  function memberName(id: string): string {
    return members.find((m) => m.id === id)?.name ?? 'Unknown'
  }

  if (loading) return <div className="p-6 text-sm text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 space-y-8 pl-16 md:pl-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold">Nutrition</h1>
          <p className="text-sm text-muted-foreground">
            Snap a photo of food or a label to log ingredients, calories, sugar, and more.
          </p>
        </div>
        {isAdmin && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              capture="environment"
              className="hidden"
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) void handleFilePicked(f)
              }}
            />
            <Button onClick={() => fileInputRef.current?.click()} disabled={analyzing}>
              <Camera className="w-4 h-4 mr-2" />
              {analyzing ? 'Analyzing…' : 'Scan food'}
            </Button>
          </>
        )}
      </div>

      {error && <p className="text-sm text-red-400">{error}</p>}

      {members.length === 0 && (
        <p className="text-sm text-muted-foreground border rounded-md p-4">
          Add a family member first — every nutrition entry is attributed to a person.
        </p>
      )}

      {/* Today's rollup */}
      <section className="grid grid-cols-2 md:grid-cols-5 gap-3">
        {[
          { label: 'Calories', value: fmt(todaysTotals.calories) },
          { label: 'Protein',  value: `${fmt(todaysTotals.proteinG)} g` },
          { label: 'Carbs',    value: `${fmt(todaysTotals.carbsG)} g` },
          { label: 'Sugar',    value: `${fmt(todaysTotals.sugarG)} g` },
          { label: 'Fat',      value: `${fmt(todaysTotals.fatG)} g` },
        ].map((tile) => (
          <div key={tile.label} className="border rounded-md p-3">
            <p className="text-xs text-muted-foreground uppercase tracking-wide">{tile.label}</p>
            <p className="text-lg font-semibold">{tile.value}</p>
          </div>
        ))}
      </section>

      {/* Member filter */}
      <div className="flex items-center gap-2 text-sm">
        <span className="text-muted-foreground">Member:</span>
        <select
          value={memberFilter}
          onChange={(e) => setMemberFilter(e.target.value)}
          className="rounded-md border px-2 py-1 text-sm"
        >
          <option value="all">Everyone</option>
          {members.map((m) => (
            <option key={m.id} value={m.id}>{m.name}</option>
          ))}
        </select>
      </div>

      {/* Draft review */}
      {draft && (
        <section className="border border-emerald-500/40 rounded-md p-4 space-y-3 bg-emerald-500/10">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold">Review before saving</h2>
            <Button variant="ghost" size="icon" onClick={() => setDraft(null)} title="Discard">
              <X className="w-4 h-4" />
            </Button>
          </div>

          <div className="grid md:grid-cols-2 gap-4">
            <div className="space-y-2">
              <label className="block text-xs font-medium uppercase tracking-wide text-muted-foreground">Food</label>
              <input
                className="w-full rounded-md border px-3 py-2 text-sm"
                value={draft.foodName ?? ''}
                onChange={(e) => setDraft({ ...draft, foodName: e.target.value })}
              />
              <p className="text-xs text-muted-foreground">
                {sourceLabel[draft.source]} · {draft.facts?.servingDescription ?? 'serving'}
              </p>
              {draft.description && (
                <p className="text-sm text-muted-foreground">{draft.description}</p>
              )}
            </div>

            <div className="space-y-2">
              <label className="block text-xs font-medium uppercase tracking-wide text-muted-foreground">For</label>
              <select
                className="w-full rounded-md border px-3 py-2 text-sm"
                value={draftMember}
                onChange={(e) => setDraftMember(e.target.value)}
              >
                {members.map((m) => (
                  <option key={m.id} value={m.id}>{m.name}</option>
                ))}
              </select>
            </div>
          </div>

          {/* Facts */}
          <div className="grid grid-cols-3 md:grid-cols-6 gap-2 text-sm">
            {([
              ['Cal',     draft.facts?.calories,      ''],
              ['Protein', draft.facts?.proteinG,      'g'],
              ['Carbs',   draft.facts?.carbsG,        'g'],
              ['Sugar',   draft.facts?.sugarG,        'g'],
              ['Fat',     draft.facts?.fatG,          'g'],
              ['Sodium',  draft.facts?.sodiumMg,      'mg'],
            ] as const).map(([label, val, unit]) => (
              <div key={label} className="border rounded-md p-2">
                <p className="text-xs text-muted-foreground">{label}</p>
                <p className="font-medium">{fmt(val as any)}{unit && ` ${unit}`}</p>
              </div>
            ))}
          </div>

          {draft.ingredients.length > 0 && (
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-1">Ingredients</p>
              <p className="text-sm">{draft.ingredients.join(', ')}</p>
            </div>
          )}

          {draft.healthBenefits.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {draft.healthBenefits.map((b) => (
                <span key={b} className="text-xs rounded-full bg-emerald-500/15 text-emerald-300 border border-emerald-500/20 px-2 py-0.5 flex items-center gap-1">
                  <Leaf className="w-3 h-3" /> {b}
                </span>
              ))}
            </div>
          )}

          {draft.warnings.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {draft.warnings.map((w) => (
                <span key={w} className="text-xs rounded-full bg-amber-500/15 text-amber-300 border border-amber-500/20 px-2 py-0.5 flex items-center gap-1">
                  <AlertTriangle className="w-3 h-3" /> {w}
                </span>
              ))}
            </div>
          )}

          <div className="flex gap-2">
            <Button onClick={handleSave} disabled={saving || !draftMember}>
              {saving ? 'Saving…' : 'Save entry'}
            </Button>
            <Button variant="ghost" onClick={() => setDraft(null)}>Discard</Button>
          </div>
        </section>
      )}

      {/* History */}
      <section>
        <h2 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">History</h2>
        {grouped.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-muted-foreground border rounded-md">
            <Apple className="w-10 h-10 mb-3" />
            <p className="text-sm">No entries yet. Tap "Scan food" to log your first meal.</p>
          </div>
        ) : (
          <div className="space-y-6">
            {grouped.map(([day, items]) => (
              <div key={day}>
                <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2">{day}</h3>
                <ul className="divide-y border rounded-md">
                  {items.map((e) => (
                    <li key={e.id} className="px-4 py-3 flex gap-4 items-start">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <p className="font-medium truncate">{e.foodName || '(untitled)'}</p>
                          <span className="text-xs rounded-full bg-white/5 border border-white/10 px-2 py-0.5">{sourceLabel[e.source]}</span>
                          {memberFilter === 'all' && (
                            <span className="text-xs text-muted-foreground">· {memberName(e.memberId)}</span>
                          )}
                        </div>
                        {e.facts?.servingDescription && (
                          <p className="text-xs text-muted-foreground">{e.facts.servingDescription}</p>
                        )}
                        <div className="text-xs text-muted-foreground mt-1 flex flex-wrap gap-x-3">
                          <span>{fmt(e.facts?.calories)} cal</span>
                          <span>P {fmt(e.facts?.proteinG)}g</span>
                          <span>C {fmt(e.facts?.carbsG)}g</span>
                          <span>S {fmt(e.facts?.sugarG)}g</span>
                          <span>F {fmt(e.facts?.fatG)}g</span>
                        </div>
                        {e.warnings.length > 0 && (
                          <div className="flex flex-wrap gap-1 mt-1">
                            {e.warnings.map((w) => (
                              <span key={w} className="text-[11px] rounded-full bg-amber-500/15 text-amber-300 border border-amber-500/20 px-1.5 py-0.5">
                                {w}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                      {isAdmin && (
                        <Button variant="ghost" size="icon" onClick={() => handleDelete(e.id)} title="Delete">
                          <Trash2 className="w-4 h-4" />
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
