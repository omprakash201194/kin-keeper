import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { Button } from '@/components/ui/button'
import { Shield } from 'lucide-react'
import { useEffect } from 'react'

export default function LoginPage() {
  const { user, loading, signIn } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (!loading && user) {
      navigate('/', { replace: true })
    }
  }, [user, loading, navigate])

  const handleSignIn = async () => {
    try {
      await signIn()
    } catch (err) {
      console.error('Sign-in failed:', err)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-2xl bg-card/70 border border-white/10 backdrop-blur-md p-8 shadow-2xl text-center">
        <Shield className="w-12 h-12 text-emerald-400 mx-auto mb-4" />
        <h1 className="text-3xl font-serif mb-2 text-foreground">Kin-Keeper</h1>
        <p className="text-muted-foreground mb-8">
          Your family's document vault, powered by AI.
        </p>
        <Button onClick={handleSignIn} className="w-full" size="lg">
          Sign in with Google
        </Button>
      </div>
    </div>
  )
}
