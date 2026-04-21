import { NavLink, useNavigate, useMatch } from 'react-router-dom'
import { Workflow, Settings, LogOut, UserCircle } from 'lucide-react'
import clsx from 'clsx'
import { useAuth } from '../../context/AuthContext'

const ROLE_LABEL: Record<string, string> = {
  VIEWER: 'Viewer',
  OPERATOR: 'Operator',
  RELEASE_MANAGER: 'Release Manager',
  ADMIN: 'Admin',
}

export default function Sidebar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'ADMIN'
  const inSystem = useMatch('/system/*')

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <aside className="w-[56px] flex-shrink-0 bg-slate-900 border-r border-slate-800 flex flex-col h-screen sticky top-0 items-center py-4 gap-2">
      {/* Logo → home */}
      <NavLink
        to="/"
        aria-label="На главную"
        className="mb-4 p-2 rounded-md text-blue-400 hover:text-blue-300 hover:bg-slate-800 transition-colors"
      >
        <Workflow className="w-5 h-5" />
      </NavLink>

      <div className="flex-1" />

      {/* System settings — admin only */}
      {isAdmin && (
        <NavLink
          to="/system/users"
          aria-label="Системные настройки"
          title="Системные настройки"
          className={clsx(
            'p-2 rounded-md transition-colors',
            inSystem
              ? 'bg-slate-700 text-white'
              : 'text-slate-500 hover:text-white hover:bg-slate-800'
          )}
        >
          <Settings className="w-4 h-4" />
        </NavLink>
      )}

      {/* User avatar */}
      {user && (
        <div
          title={`${user.displayName || user.username} · ${ROLE_LABEL[user.role] ?? user.role}`}
          className="p-2 text-slate-500 cursor-default"
        >
          <UserCircle className="w-4 h-4" />
        </div>
      )}

      {/* Logout */}
      <button
        type="button"
        onClick={handleLogout}
        aria-label="Выйти"
        title="Выйти"
        className="p-2 rounded-md text-slate-500 hover:text-white hover:bg-slate-800 transition-colors"
      >
        <LogOut className="w-4 h-4" />
      </button>
    </aside>
  )
}
