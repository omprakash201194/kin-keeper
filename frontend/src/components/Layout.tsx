import { Outlet, NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { useReminderCount } from '@/hooks/useReminderCount'
import {
  Home,
  MessageSquare,
  MessageCircle,
  FileText,
  FolderTree,
  Users,
  UserCircle2,
  Boxes,
  Apple,
  Plane,
  Bell,
  Settings,
  LogOut,
  Shield,
  Menu,
  X,
} from 'lucide-react'
import { clsx } from 'clsx'
import { useState } from 'react'

const nav = [
  { to: '/',              label: 'Home',          icon: Home,          end: true as const },
  { to: '/chat',          label: 'Chat',          icon: MessageSquare },
  { to: '/documents',     label: 'Documents',     icon: FileText },
  { to: '/categories',    label: 'Categories',    icon: FolderTree },
  { to: '/members',       label: 'Members',       icon: Users },
  { to: '/contacts',      label: 'Contacts',      icon: UserCircle2 },
  { to: '/conversations', label: 'Conversations', icon: MessageCircle },
  { to: '/assets',        label: 'Assets',        icon: Boxes },
  { to: '/reminders',     label: 'Reminders',     icon: Bell, badgeKey: 'reminders' as const },
  { to: '/nutrition',     label: 'Nutrition',     icon: Apple },
  { to: '/plans',         label: 'Plans',         icon: Plane },
  { to: '/settings',      label: 'Settings',      icon: Settings },
]

export default function Layout() {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const reminderCount = useReminderCount()

  const handleLogout = async () => {
    await signOut()
    navigate('/login')
  }

  const sidebar = (
    <>
      {/* Logo */}
      <div className="flex items-center gap-2 px-6 py-5 border-b border-white/10">
        <Shield className="w-6 h-6 text-emerald-400" />
        <span className="text-lg font-semibold tracking-tight font-serif">Kin-Keeper</span>
      </div>

      {/* Nav */}
      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
        {nav.map((item) => {
          const Icon = item.icon
          const showBadge = item.badgeKey === 'reminders' && reminderCount > 0
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={'end' in item ? item.end : false}
              onClick={() => setSidebarOpen(false)}
              className={({ isActive }) =>
                clsx(
                  'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-emerald-500/90 text-white shadow-sm'
                    : 'text-neutral-400 hover:bg-white/5 hover:text-white'
                )
              }
            >
              <Icon className="w-4 h-4" />
              <span className="flex-1">{item.label}</span>
              {showBadge && (
                <span className="text-[11px] bg-red-500 text-white rounded-full px-1.5 py-0.5 min-w-[20px] text-center">
                  {reminderCount > 99 ? '99+' : reminderCount}
                </span>
              )}
            </NavLink>
          )
        })}
      </nav>

      {/* User + Logout */}
      <div className="border-t border-white/10 px-4 py-4">
        <div className="flex items-center gap-3 mb-3">
          {user?.photoURL ? (
            <img src={user.photoURL} alt="" className="w-8 h-8 rounded-full" />
          ) : (
            <div className="w-8 h-8 rounded-full bg-emerald-500 flex items-center justify-center text-sm font-bold">
              {user?.displayName?.[0]?.toUpperCase() ?? 'U'}
            </div>
          )}
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate">{user?.displayName}</p>
            <p className="text-xs text-neutral-400 truncate">{user?.email}</p>
          </div>
        </div>
        <button
          onClick={handleLogout}
          className="flex items-center gap-2 w-full px-3 py-2 text-sm text-neutral-400 hover:text-white hover:bg-white/5 rounded-lg transition-colors"
        >
          <LogOut className="w-4 h-4" />
          Sign out
        </button>
      </div>
    </>
  )

  return (
    <div className="flex h-screen">
      {/* Mobile hamburger */}
      <button
        onClick={() => setSidebarOpen(!sidebarOpen)}
        className="md:hidden fixed top-4 left-4 z-50 p-2 rounded-lg bg-black/40 border border-white/10 backdrop-blur-md text-white"
      >
        {sidebarOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
      </button>

      {/* Mobile overlay */}
      {sidebarOpen && (
        <div
          className="md:hidden fixed inset-0 bg-black/50 z-30"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar — desktop always visible, mobile slide-in. Translucent so the
          aurora gradient on body shows through subtly. */}
      <aside
        className={clsx(
          'w-64 text-white flex flex-col z-40',
          'bg-black/50 backdrop-blur-md border-r border-white/10',
          'fixed md:static inset-y-0 left-0 transition-transform md:translate-x-0',
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        )}
      >
        {sidebar}
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  )
}
