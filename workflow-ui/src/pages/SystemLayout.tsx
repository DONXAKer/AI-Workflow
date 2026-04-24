import { NavLink, Outlet } from 'react-router-dom'
import { Users, Settings, Bot, FileSearch, Power, DollarSign, FolderKanban } from 'lucide-react'
import clsx from 'clsx'

const TABS = [
  { to: '/system/users', label: 'Пользователи', icon: Users },
  { to: '/system/projects', label: 'Проекты', icon: FolderKanban },
  { to: '/system/integrations', label: 'Интеграции', icon: Settings },
  { to: '/system/agent-profiles', label: 'Агенты', icon: Bot },
  { to: '/system/audit', label: 'Журнал', icon: FileSearch },
  { to: '/system/kill-switch', label: 'Kill Switch', icon: Power },
  { to: '/system/cost', label: 'Стоимость', icon: DollarSign },
]

export default function SystemLayout() {
  return (
    <div className="flex flex-col min-h-screen">
      <div className="bg-slate-900 border-b border-slate-800 px-6 py-3">
        <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-3">Система</p>
        <nav className="flex gap-0 -mb-px">
          {TABS.map(tab => (
            <NavLink
              key={tab.to}
              to={tab.to}
              className={({ isActive }) =>
                clsx(
                  'flex items-center gap-1.5 px-3 py-2.5 text-sm font-medium border-b-2 transition-colors',
                  isActive
                    ? 'border-blue-500 text-white'
                    : 'border-transparent text-slate-400 hover:text-white hover:border-slate-600'
                )
              }
            >
              <tab.icon className="w-3.5 h-3.5" />
              {tab.label}
            </NavLink>
          ))}
        </nav>
      </div>
      <div className="flex-1">
        <Outlet />
      </div>
    </div>
  )
}
