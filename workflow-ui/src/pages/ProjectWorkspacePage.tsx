import { useEffect, useState } from 'react'
import { NavLink, Outlet, useParams, useNavigate } from 'react-router-dom'
import { ArrowLeft, Play, Activity, History, Settings, Plug, Sparkles, Loader2, AlertCircle } from 'lucide-react'
import { api } from '../services/api'
import { ProjectInfo } from '../types'
import { setCurrentProjectSlug } from '../services/projectContext'
import { useRunsContext } from '../context/RunsContext'
import clsx from 'clsx'

const TABS = [
  { to: 'launch',       label: 'Запуск',     icon: Play },
  { to: 'smart-start',  label: 'Smart Start', icon: Sparkles },
  { to: 'active',       label: 'Активные',   icon: Activity },
  { to: 'history',      label: 'История',    icon: History },
  { to: 'integrations', label: 'Интеграции', icon: Plug },
  { to: 'settings',     label: 'Настройки',  icon: Settings },
]

export default function ProjectWorkspacePage() {
  const { slug } = useParams<{ slug: string }>()
  const navigate = useNavigate()
  const { activeCount, pendingApprovalCount } = useRunsContext()
  const [project, setProject] = useState<ProjectInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!slug) return
    setCurrentProjectSlug(slug)
    setLoading(true)
    setError(null)
    api.listProjects()
      .then(ps => {
        const found = ps.find(p => p.slug === slug)
        if (!found) setError('Проект не найден')
        else setProject(found)
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))
      .finally(() => setLoading(false))
  }, [slug])

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-slate-400">
        <Loader2 className="w-5 h-5 animate-spin" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto px-6 py-10">
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" /> {error}
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col min-h-screen">
      {/* Workspace header */}
      <div className="bg-slate-900 border-b border-slate-800 px-6 pt-4 pb-0">
        <button
          type="button"
          onClick={() => navigate('/')}
          className="flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-300 mb-2 transition-colors"
        >
          <ArrowLeft className="w-3 h-3" /> Все проекты
        </button>
        <h1 className="text-base font-semibold text-white">{project?.displayName}</h1>
        {project?.configDir && (
          <p className="text-xs text-slate-500 font-mono mt-0.5 mb-3">{project.configDir}</p>
        )}

        {/* Tab bar */}
        <nav className="flex gap-0 -mb-px mt-3">
          {TABS.map(tab => (
            <NavLink
              key={tab.to}
              to={tab.to}
              className={({ isActive }) =>
                clsx(
                  'flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors relative',
                  isActive
                    ? 'border-blue-500 text-white'
                    : 'border-transparent text-slate-400 hover:text-white hover:border-slate-600'
                )
              }
            >
              <tab.icon className="w-3.5 h-3.5" />
              {tab.label}
              {tab.to === 'active' && activeCount > 0 && (
                <span className="ml-1 text-xs bg-blue-600 text-white px-1.5 py-0.5 rounded-full leading-none">
                  {activeCount > 99 ? '99+' : activeCount}
                </span>
              )}
              {tab.to === 'active' && pendingApprovalCount > 0 && activeCount === 0 && (
                <span className="ml-1 w-2 h-2 rounded-full bg-amber-400 inline-block" />
              )}
            </NavLink>
          ))}
        </nav>
      </div>

      {/* Tab content */}
      <div className="flex-1">
        <Outlet />
      </div>
    </div>
  )
}
