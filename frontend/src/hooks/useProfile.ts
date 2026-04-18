import { useEffect, useState } from 'react'
import apiClient from '@/services/api'
import { useAuth } from '@/hooks/useAuth'

export type UserProfile = {
  uid: string
  email: string
  displayName?: string
  photoUrl?: string
  familyId?: string
  role?: 'admin' | 'viewer'
}

export function useProfile() {
  const { user, loading: authLoading } = useAuth()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (authLoading) return
    if (!user) {
      setProfile(null)
      setLoading(false)
      return
    }
    void load()
  }, [user, authLoading])

  async function load() {
    setLoading(true)
    try {
      const res = await apiClient.get<UserProfile>('/auth/me')
      setProfile(res.data)
    } catch {
      setProfile(null)
    } finally {
      setLoading(false)
    }
  }

  return { profile, loading, refresh: load, isAdmin: profile?.role === 'admin' }
}
