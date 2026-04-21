import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, FolderOpen, AlertCircle, Loader2, X, Save, ChevronRight } from 'lucide-react'
import { api } from '../services/api'
import { ProjectInfo } from '../types'

function slugify(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
}

export default function ProjectsListPage() {
  const navigate = useNavigate()
  const [projects, setProjects] = useState<ProjectInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const [name, setName] = useState('')
  const [path, setPath] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setProjects(await api.listProjects())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить проекты')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const openCreate = () => {
    setShowCreate(true)
    setName('')
    setPath('')
    setSaveError(null)
  }

  const closeCreate = () => setShowCreate(false)

  const create = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      const project = await api.createProject({
        slug: slugify(name),
        displayName: name.trim(),
        configDir: path.trim() || undefined,
      })
      await load()
      closeCreate()
      navigate(`/projects/${project.slug}`)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось создать')
    } finally {
      setSaving(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && name.trim()) create()
    if (e.key === 'Escape') closeCreate()
  }

  return (
    <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-white">Проекты</h1>
          <p className="text-sm text-slate-500 mt-0.5">Выберите проект для работы</p>
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
        >
          <Plus className="w-4 h-4" /> Добавить проект
        </button>
      </div>

      {/* Create modal */}
      {showCreate && (
        <div className="fixed inset-0 bg-slate-950/80 flex items-center justify-center z-50" onKeyDown={handleKeyDown}>
          <div className="bg-slate-900 border border-slate-700 rounded-xl p-6 w-full max-w-md shadow-2xl space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-white">Новый проект</h2>
              <button type="button" onClick={closeCreate} className="text-slate-500 hover:text-white transition-colors">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                  Название
                </label>
                <input
                  type="text"
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="My Project"
                  autoFocus
                  className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                {name.trim() && (
                  <p className="text-xs text-slate-500 mt-1 font-mono">slug: {slugify(name)}</p>
                )}
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                  Путь к проекту
                </label>
                <input
                  type="text"
                  value={path}
                  onChange={e => setPath(e.target.value)}
                  placeholder="/home/user/my-project"
                  className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            </div>

            {saveError && (
              <div className="flex items-center gap-2 text-red-400 text-sm">
                <AlertCircle className="w-4 h-4 flex-shrink-0" /> {saveError}
              </div>
            )}

            <div className="flex justify-end gap-2 pt-1">
              <button
                type="button"
                onClick={closeCreate}
                className="text-sm text-slate-400 hover:text-white px-4 py-2 rounded-lg transition-colors"
              >
                Отмена
              </button>
              <button
                type="button"
                onClick={create}
                disabled={saving || !name.trim()}
                className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
              >
                {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                Создать
              </button>
            </div>
          </div>
        </div>
      )}

      {error && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" /> {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-slate-400">
          <Loader2 className="w-4 h-4 animate-spin" /> Загрузка...
        </div>
      ) : projects.length === 0 ? (
        <div className="text-center py-24">
          <FolderOpen className="w-12 h-12 mx-auto mb-4 text-slate-700" />
          <p className="text-slate-400 font-medium">Проектов пока нет</p>
          <p className="text-slate-600 text-sm mt-1">Нажмите «Добавить проект», чтобы начать</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {projects.map(p => (
            <button
              key={p.slug}
              type="button"
              onClick={() => navigate(`/projects/${p.slug}`)}
              className="text-left bg-slate-900 border border-slate-800 hover:border-blue-700 rounded-xl p-5 transition-all group"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-white text-sm group-hover:text-blue-300 transition-colors">
                    {p.displayName}
                  </p>
                  {p.configDir && (
                    <p className="text-xs text-slate-500 font-mono mt-1.5 truncate">{p.configDir}</p>
                  )}
                  {p.description && (
                    <p className="text-xs text-slate-400 mt-1 truncate">{p.description}</p>
                  )}
                </div>
                <ChevronRight className="w-4 h-4 text-slate-600 group-hover:text-blue-400 flex-shrink-0 mt-0.5 transition-colors" />
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
