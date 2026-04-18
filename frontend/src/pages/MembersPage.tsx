import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { UserPlus, Users, Mail, X } from 'lucide-react'
import apiClient from '@/services/api'
import { useProfile } from '@/hooks/useProfile'

type Family = {
  id: string
  name: string
  adminUid: string
}

type FamilyMember = {
  id: string
  familyId: string
  name: string
  relationship?: string
}

type Invite = {
  id: string
  familyId: string
  email: string
  role: string
  status: string
}

export default function MembersPage() {
  const { isAdmin, refresh: refreshProfile } = useProfile()

  const [loading, setLoading] = useState(true)
  const [family, setFamily] = useState<Family | null>(null)
  const [members, setMembers] = useState<FamilyMember[]>([])
  const [invites, setInvites] = useState<Invite[]>([])
  const [error, setError] = useState<string | null>(null)

  const [familyName, setFamilyName] = useState('')
  const [submittingFamily, setSubmittingFamily] = useState(false)

  const [showAddMember, setShowAddMember] = useState(false)
  const [memberName, setMemberName] = useState('')
  const [memberRelationship, setMemberRelationship] = useState('')
  const [submittingMember, setSubmittingMember] = useState(false)

  const [inviteEmail, setInviteEmail] = useState('')
  const [submittingInvite, setSubmittingInvite] = useState(false)

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
        try {
          const invitesRes = await apiClient.get<Invite[]>('/family/invites')
          setInvites(invitesRes.data)
        } catch {
          setInvites([])
        }
      } else {
        setMembers([])
        setInvites([])
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
      await refreshProfile()
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

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault()
    if (!inviteEmail.trim()) return
    setSubmittingInvite(true)
    setError(null)
    try {
      await apiClient.post('/family/invite', { email: inviteEmail.trim(), role: 'viewer' })
      setInviteEmail('')
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to send invite')
    } finally {
      setSubmittingInvite(false)
    }
  }

  async function handleCancelInvite(inviteId: string) {
    try {
      await apiClient.delete(`/family/invites/${inviteId}`)
      await refresh()
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Failed to cancel invite')
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
    <div className="p-6 space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">{family.name}</h1>
          <p className="text-sm text-muted-foreground">Manage people in your family vault.</p>
        </div>
        {isAdmin && (
          <Button onClick={() => setShowAddMember((v) => !v)}>
            <UserPlus className="w-4 h-4 mr-2" />
            {showAddMember ? 'Cancel' : 'Add Member'}
          </Button>
        )}
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {showAddMember && isAdmin && (
        <form onSubmit={handleAddMember} className="max-w-md space-y-3 border rounded-md p-4">
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

      <section>
        <h2 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Members</h2>
        {members.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-muted-foreground border rounded-md">
            <Users className="w-10 h-10 mb-3" />
            <p className="text-sm">No members yet.</p>
          </div>
        ) : (
          <ul className="divide-y border rounded-md">
            {members.map((m) => (
              <li key={m.id} className="px-4 py-3">
                <p className="font-medium">{m.name}</p>
                {m.relationship && (
                  <p className="text-sm text-muted-foreground">{m.relationship}</p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {isAdmin && (
        <section>
          <h2 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide flex items-center gap-2">
            <Mail className="w-4 h-4" />
            Invite Google users
          </h2>
          <form onSubmit={handleInvite} className="max-w-md flex gap-2 mb-4">
            <input
              type="email"
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              placeholder="email@example.com"
              className="flex-1 rounded-md border px-3 py-2 text-sm"
              required
            />
            <Button type="submit" disabled={submittingInvite}>
              {submittingInvite ? 'Inviting…' : 'Invite'}
            </Button>
          </form>
          {invites.length > 0 && (
            <ul className="divide-y border rounded-md max-w-md">
              {invites.map((inv) => (
                <li key={inv.id} className="px-4 py-3 flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium">{inv.email}</p>
                    <p className="text-xs text-muted-foreground">Pending · {inv.role}</p>
                  </div>
                  <Button variant="ghost" size="icon" onClick={() => handleCancelInvite(inv.id)} title="Cancel invite">
                    <X className="w-4 h-4" />
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <p className="text-xs text-muted-foreground mt-3">
            Tell the invitee to sign in at {window.location.host} with the Google account you invited.
            They'll automatically join your family.
          </p>
        </section>
      )}
    </div>
  )
}
