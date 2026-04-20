import { NavLink, useNavigate } from 'react-router-dom'
import { Workflow, LayoutDashboard, GitBranch, Activity, History, Settings, Bot, LogOut, UserCircle, FileSearch, Power, DollarSign, FolderKanban, Users } from 'lucide-react'
import clsx from 'clsx'
import { useRunsContext } from '../../context/RunsContext'
import { useAuth } from '../../context/AuthContext'
import ProjectSwitcher from './ProjectSwitcher'

const ROLE_LABEL: Record<string, string> = {
  VIEWER: 'Viewer',
  OPERATOR: 'Operator',
  RELEASE_MANAGER: 'Release Manager',
  ADMIN: 'Admin',
}

function SidebarLink({ to, end, icon: Icon, children, badge }: {
  to: string
  end?: boolean
  icon: React.ComponentType<{ className?: string }>
  children: React.ReactNode
  badge?: number
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        clsx(
          'flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-900',
          isActive
            ? 'bg-slate-700 text-white'
            : 'text-slate-400 hover:bg-slate-800 hover:text-white'
        )
      }
    >
      <div className="flex items-center gap-2.5">
        <Icon className="w-4 h-4 flex-shrink-0" />
        {children}
      </div>
      {badge !== undefined && badge > 0 && (
        <span className="text-xs font-medium bg-blue-600 text-white px-1.5 py-0.5 rounded-full min-w-[20px] text-center leading-none">
          {badge > 99 ? '99+' : badge}
        </span>
      )}
    </NavLink>
  )
}

export default function Sidebar() {
  const { activeCount, pendingApprovalCount } = useRunsContext()
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'ADMIN'

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <aside className="w-[220px] flex-shrink-0 bg-slate-900 border-r border-slate-800 flex flex-col h-screen sticky top-0">
      {/* Logo */}
      <div className="flex items-center gap-2.5 px-4 py-4 border-b border-slate-800">
        <Workflow className="w-5 h-5 text-blue-400" />
        <span className="font-semibold text-white tracking-tight">Workflow</span>
      </div>

      {/* Project switcher */}
      <div className="px-3 pt-3">
        <ProjectSwitcher />
      </div>

      {/* Nav */}
      <nav className="flex-1 px-3 py-3 space-y-0.5">
        <SidebarLink to="/" end icon={LayoutDashboard}>Главная</SidebarLink>
        <SidebarLink to="/pipelines" icon={GitBranch}>Пайплайны</SidebarLink>
        <SidebarLink to="/runs/active" icon={Activity} badge={activeCount}>Активные</SidebarLink>
        <SidebarLink to="/runs/history" icon={History}>История</SidebarLink>
        <SidebarLink to="/cost" icon={DollarSign}>Стоимость</SidebarLink>
      </nav>

      {/* Awaiting approval callout — always reserve space to avoid layout shift */}
      <div className="px-3 pb-2 h-[44px] flex items-center">
        <div className={pendingApprovalCount > 0 ? 'visible w-full' : 'invisible w-full'}>
          <NavLink
            to="/runs/active?status=PAUSED_FOR_APPROVAL"
            className="flex items-center gap-2 px-3 py-2 rounded-md bg-amber-900/30 border border-amber-800/50 text-amber-300 text-xs font-medium hover:bg-amber-900/50 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-900"
          >
            <span className="relative flex h-2 w-2 flex-shrink-0">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-amber-400" />
            </span>
            {pendingApprovalCount} ожидает одобрения
          </NavLink>
        </div>
      </div>

      {/* Settings — admin-only links hidden for non-admins */}
      {isAdmin && (
        <div className="px-3 py-3 border-t border-slate-800">
          <p className="px-3 py-1 text-xs font-medium text-slate-600 uppercase tracking-wide mb-1">Настройки</p>
          <SidebarLink to="/settings/projects" icon={FolderKanban}>Проекты</SidebarLink>
          <SidebarLink to="/settings/users" icon={Users}>Пользователи</SidebarLink>
          <SidebarLink to="/settings/integrations" icon={Settings}>Интеграции</SidebarLink>
          <SidebarLink to="/settings/agent-profiles" icon={Bot}>Профили агентов</SidebarLink>
          <SidebarLink to="/settings/audit" icon={FileSearch}>Журнал действий</SidebarLink>
          <SidebarLink to="/settings/kill-switch" icon={Power}>Kill Switch</SidebarLink>
        </div>
      )}

      {/* User footer */}
      {user && (
        <div className="px-3 py-3 border-t border-slate-800">
          <div className="flex items-center gap-2.5 px-3 py-1.5 mb-1">
            <UserCircle className="w-4 h-4 text-slate-500 flex-shrink-0" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-slate-200 truncate">
                {user.displayName || user.username}
              </p>
              <p className="text-[10px] text-slate-500 uppercase tracking-wide truncate">
                {ROLE_LABEL[user.role] ?? user.role}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={handleLogout}
            className="w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium text-slate-400 hover:bg-slate-800 hover:text-white transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            <LogOut className="w-4 h-4 flex-shrink-0" />
            Выйти
          </button>
        </div>
      )}
    </aside>
  )
}
