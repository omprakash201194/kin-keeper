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
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-react'
import { clsx } from 'clsx'
import { useEffect, useState } from 'react'

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

const COLLAPSED_KEY = 'kk.sidebar.collapsed'

export default function Layout() {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  const [sidebarOpen, setSidebarOpen] = useState(false) // mobile slide-in state
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false
    return window.localStorage.getItem(COLLAPSED_KEY) === '1'
  })
  const reminderCount = useReminderCount()

  useEffect(() => {
    if (typeof window === 'undefined') return
    window.localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0')
  }, [collapsed])

  const handleLogout = async () => {
    await signOut()
    navigate('/login')
  }

  const sidebar = (
    <>
      {/* Logo + desktop collapse toggle */}
      <div className={clsx(
        'flex items-center border-b border-white/10',
        collapsed ? 'justify-center px-3 py-5' : 'gap-2 px-6 py-5',
      )}>
        <Shield className="w-6 h-6 text-emerald-400 shrink-0" />
        {!collapsed && (
          <span className="text-lg font-semibold tracking-tight font-serif flex-1 truncate">
            Kin-Keeper
          </span>
        )}
        {!collapsed && (
          <button
            onClick={() => setCollapsed(true)}
            className="hidden md:inline-flex p-1.5 rounded text-neutral-400 hover:text-white hover:bg-white/5"
            title="Collapse sidebar"
          >
            <PanelLeftClose className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* Desktop expand button (only visible when collapsed) */}
      {collapsed && (
        <button
          onClick={() => setCollapsed(false)}
          className="hidden md:inline-flex mx-auto mt-2 p-1.5 rounded text-neutral-400 hover:text-white hover:bg-white/5"
          title="Expand sidebar"
        >
          <PanelLeftOpen className="w-4 h-4" />
        </button>
      )}

      {/* Nav */}
      <nav className={clsx('flex-1 py-4 space-y-1 overflow-y-auto', collapsed ? 'px-2' : 'px-3')}>
        {nav.map((item) => {
          const Icon = item.icon
          const showBadge = item.badgeKey === 'reminders' && reminderCount > 0
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={'end' in item ? item.end : false}
              onClick={() => setSidebarOpen(false)}
              title={collapsed ? item.label : undefined}
              className={({ isActive }) =>
                clsx(
                  'flex items-center rounded-lg text-sm font-medium transition-colors relative',
                  collapsed ? 'justify-center px-2 py-2.5' : 'gap-3 px-3 py-2.5',
                  isActive
                    ? 'bg-primary text-primary-foreground shadow-sm'
                    : 'text-neutral-400 hover:bg-white/5 hover:text-white'
                )
              }
            >
              <Icon className="w-4 h-4 shrink-0" />
              {!collapsed && <span className="flex-1 truncate">{item.label}</span>}
              {showBadge && (
                collapsed ? (
                  <span className="absolute top-1 right-1 w-2 h-2 rounded-full bg-red-500" />
                ) : (
                  <span className="text-[11px] bg-red-500 text-white rounded-full px-1.5 py-0.5 min-w-[20px] text-center">
                    {reminderCount > 99 ? '99+' : reminderCount}
                  </span>
                )
              )}
            </NavLink>
          )
        })}
      </nav>

      {/* User + Logout */}
      <div className={clsx('border-t border-white/10 py-4', collapsed ? 'px-2' : 'px-4')}>
        <div className={clsx('flex items-center mb-3', collapsed ? 'justify-center' : 'gap-3')}>
          {user?.photoURL ? (
            <img src={user.photoURL} alt="" className="w-8 h-8 rounded-full shrink-0" />
          ) : (
            <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-sm font-bold shrink-0">
              {user?.displayName?.[0]?.toUpperCase() ?? 'U'}
            </div>
          )}
          {!collapsed && (
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">{user?.displayName}</p>
              <p className="text-xs text-neutral-400 truncate">{user?.email}</p>
            </div>
          )}
        </div>
        <button
          onClick={handleLogout}
          title={collapsed ? 'Sign out' : undefined}
          className={clsx(
            'flex items-center w-full text-sm text-neutral-400 hover:text-white hover:bg-white/5 rounded-lg transition-colors',
            collapsed ? 'justify-center px-2 py-2' : 'gap-2 px-3 py-2',
          )}
        >
          <LogOut className="w-4 h-4 shrink-0" />
          {!collapsed && <span>Sign out</span>}
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

      {/* Sidebar — desktop static (width flips with collapsed), mobile slide-in
          always full-width since collapse only applies to md+. */}
      <aside
        className={clsx(
          'text-white flex flex-col z-40 transition-[width] duration-200',
          'bg-black/50 backdrop-blur-md border-r border-white/10',
          'fixed md:static inset-y-0 left-0 md:translate-x-0',
          sidebarOpen ? 'translate-x-0 w-64' : '-translate-x-full w-64',
          collapsed ? 'md:w-16' : 'md:w-64',
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
