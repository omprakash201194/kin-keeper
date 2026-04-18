import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { UserPlus, Users } from 'lucide-react'
import apiClient from '@/services/api'

type Family = {
  id: string
  name: string
  adminUid: string
  createdAt?: string
}

type FamilyMember = {
  id: string
  familyId: string
  name: string
  relationship?: string
  createdAt?: string
}

export default function MembersPage() {
  const [loading, setLoading] = useState(true)
  const [family, setFamily] = useState<Family | null>(null)
  const [members, setMembers] = useState<FamilyMember[]>([])
  const [error, setError] = useState<string | null>(null)

  const [familyName, setFamilyName] = useState('')
  const [submittingFamily, setSubmittingFamily] = useState(false)

  const [showAddMember, setShowAddMember] = useState(false)
  const [memberName, setMemberName] = useState('')
  const [memberRelationship, setMemberRelationship] = useState('')
  const [submittingMember, setSubmittingMember] = useState(false)

  useEffect(() => {
    void refresh()
  }, [])

  async function refresh() {
    setLoading(true)
    setError(null)
    try {
      const familyRes = await apiClient.get('/family')
      const fam = familyRes.data?.id ? (familyRes.data as Family) : null
      setFamily(fam)
      if (fam) {
        const membersRes = await apiClient.get<FamilyMember[]>('/family/members')
        setMembers(membersRes.data)
      } else {
        setMembers([])
      }
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to load family')
    } finally {
      setLoading(false)
    }
  }

  async function handleCreateFamily(e: React.FormEvent) {
    e.preventDefault()
    if (!familyName.trim()) return
    setSubmittingFamily(true)
    setError(null)
    try {
      await apiClient.post('/family', { name: familyName.trim() })
      setFamilyName('')
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to create family')
    } finally {
      setSubmittingFamily(false)
    }
  }

  async function handleAddMember(e: React.FormEvent) {
    e.preventDefault()
    if (!memberName.trim()) return
    setSubmittingMember(true)
    setError(null)
    try {
      await apiClient.post('/family/members', {
        name: memberName.trim(),
        relationship: memberRelationship.trim(),
      })
      setMemberName('')
      setMemberRelationship('')
      setShowAddMember(false)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to add member')
    } finally {
      setSubmittingMember(false)
    }
  }

  if (loading) {
    return <div className="p-6 text-sm text-muted-foreground">Loading…</div>
  }

  if (!family) {
    return (
      <div className="p-6 max-w-md">
        <h1 className="text-xl font-semibold mb-1">Create your family</h1>
        <p className="text-sm text-muted-foreground mb-6">
          Start by naming your family vault. You'll become the admin.
        </p>
        <form onSubmit={handleCreateFamily} className="space-y-3">
          <input
            type="text"
            value={familyName}
            onChange={(e) => setFamilyName(e.target.value)}
            placeholder="e.g. The Gautams"
            className="w-full rounded-md border px-3 py-2 text-sm"
            required
          />
          <Button type="submit" disabled={submittingFamily}>
            {submittingFamily ? 'Creating…' : 'Create Family'}
          </Button>
        </form>
        {error && <p className="mt-4 text-sm text-red-600">{error}</p>}
      </div>
    )
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold">{family.name}</h1>
          <p className="text-sm text-muted-foreground">Manage people in your family vault.</p>
        </div>
        <Button onClick={() => setShowAddMember((v) => !v)}>
          <UserPlus className="w-4 h-4 mr-2" />
          {showAddMember ? 'Cancel' : 'Add Member'}
        </Button>
      </div>

      {showAddMember && (
        <form onSubmit={handleAddMember} className="mb-6 max-w-md space-y-3 border rounded-md p-4">
          <input
            type="text"
            value={memberName}
            onChange={(e) => setMemberName(e.target.value)}
            placeholder="Name"
            className="w-full rounded-md border px-3 py-2 text-sm"
            required
          />
          <input
            type="text"
            value={memberRelationship}
            onChange={(e) => setMemberRelationship(e.target.value)}
            placeholder="Relationship (e.g. Spouse, Child)"
            className="w-full rounded-md border px-3 py-2 text-sm"
          />
          <Button type="submit" disabled={submittingMember}>
            {submittingMember ? 'Adding…' : 'Add'}
          </Button>
        </form>
      )}

      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

      {members.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
          <Users className="w-12 h-12 mb-4" />
          <p className="text-lg font-medium mb-1">No members yet</p>
          <p className="text-sm">Add family members to start organizing their documents.</p>
        </div>
      ) : (
        <ul className="divide-y border rounded-md">
          {members.map((m) => (
            <li key={m.id} className="px-4 py-3 flex items-center justify-between">
              <div>
                <p className="font-medium">{m.name}</p>
                {m.relationship && (
                  <p className="text-sm text-muted-foreground">{m.relationship}</p>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
