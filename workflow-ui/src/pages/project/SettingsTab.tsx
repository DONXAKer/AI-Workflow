import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { Save, Loader2, AlertCircle, Trash2, Plug, FileCode, FolderOpen } from 'lucide-react'
import { api } from '../../services/api'
import { ProjectInfo, IntegrationConfig } from '../../types'
import PathInput from '../../components/PathInput'

const INTEGRATION_TYPE_LABELS: Record<string, string> = {
  OPENROUTER: 'OpenRouter',
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  YOUTRACK: 'YouTrack',
}

export default function SettingsTab() {
  const { slug } = useParams<{ slug: string }>()
  const navigate = useNavigate()
  const [project, setProject] = useState<ProjectInfo | null>(null)
  const [integrations, setIntegrations] = useState<IntegrationConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [displayName, setDisplayName] = useState('')
  const [configDir, setConfigDir] = useState('')
  const [workingDir, setWorkingDir] = useState('')
  const [description, setDescription] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const load = useCallback(async () => {
    if (!slug) return
    setLoading(true)
    try {
      const [projects, integs] = await Promise.all([
        api.listProjects(),
        api.listIntegrations(),
      ])
      const found = projects.find(p => p.slug === slug)
      if (found) {
        setProject(found)
        setDisplayName(found.displayName)
        setConfigDir(found.configDir ?? '')
        setWorkingDir(found.workingDir ?? '')
        setDescription(found.description ?? '')
      }
      setIntegrations(integs)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ошибка загрузки')
    } finally {
      setLoading(false)
    }
  }, [slug])

  useEffect(() => { load() }, [load])

  const save = async () => {
    if (!slug) return
    setSaving(true)
    setSaveError(null)
    setSaved(false)
    try {
      await api.updateProject(slug, {
        displayName: displayName.trim(),
        configDir: configDir.trim(),
        workingDir: workingDir.trim(),
        description: description.trim(),
      })
      setSaved(true)
      setTimeout(() => setSaved(false), 3000)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!slug || !project) return
    if (!confirm(`Удалить проект «${project.displayName}»? Все данные запусков будут потеряны.`)) return
    try {
      await api.deleteProject(slug)
      navigate('/')
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось удалить')
    }
  }

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-slate-400 p-8">
        <Loader2 className="w-4 h-4 animate-spin" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 text-red-400 p-8">
        <AlertCircle className="w-4 h-4" /> {error}
      </div>
    )
  }

  const isDefault = slug === 'default'

  return (
    <div className="max-w-2xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wide">Настройки проекта</h2>

      {/* Main project fields */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 space-y-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Название
            </label>
            <input
              type="text"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Slug
            </label>
            <p className="text-sm text-slate-500 font-mono px-3 py-2 bg-slate-950/50 rounded-lg border border-slate-800">
              {slug}
            </p>
          </div>
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Описание <span className="text-slate-600 normal-case font-normal">(необязательно)</span>
          </label>
          <textarea
            value={description}
            onChange={e => setDescription(e.target.value)}
            rows={2}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
          />
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            <span className="flex items-center gap-1.5"><FolderOpen className="w-3.5 h-3.5" /> Рабочая директория</span>
          </label>
          <PathInput
            value={workingDir}
            onChange={setWorkingDir}
            placeholder="D:/WarCard"
            disabled={saving}
          />
          <p className="text-xs text-slate-600 mt-1">
            Корень проекта — PathScope для агентов и рабочая папка shell-команд.
          </p>
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            <span className="flex items-center gap-1.5"><FileCode className="w-3.5 h-3.5" /> Pipeline-конфиги</span>
          </label>
          <PathInput
            value={configDir}
            onChange={setConfigDir}
            placeholder="D:/WarCard/.ai-workflow/pipelines"
            disabled={saving}
          />
          <p className="text-xs text-slate-600 mt-1">
            Папка с YAML-файлами пайплайнов. Используется вкладкой «Запуск».
          </p>
        </div>

        {saveError && (
          <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0" /> {saveError}
          </div>
        )}

        <div className="flex justify-end">
          <button
            type="button"
            onClick={save}
            disabled={saving || !displayName.trim()}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {saving
              ? <Loader2 className="w-4 h-4 animate-spin" />
              : <Save className="w-4 h-4" />
            }
            {saved ? 'Сохранено!' : 'Сохранить'}
          </button>
        </div>
      </div>

      {/* Integrations summary */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
        <div className="px-5 py-3 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Plug className="w-4 h-4 text-slate-400" />
            <span className="text-sm font-medium text-slate-300">Интеграции</span>
            {integrations.length > 0 && (
              <span className="text-xs bg-slate-800 text-slate-400 px-1.5 py-0.5 rounded-full">
                {integrations.length}
              </span>
            )}
          </div>
          <Link
            to="../integrations"
            relative="path"
            className="text-xs text-blue-400 hover:text-blue-300 transition-colors"
          >
            Управление →
          </Link>
        </div>

        {integrations.length === 0 ? (
          <div className="px-5 py-6 text-center text-slate-500 text-sm">
            Нет подключённых интеграций.{' '}
            <Link to="../integrations" relative="path" className="text-blue-400 hover:text-blue-300">
              Добавить
            </Link>
          </div>
        ) : (
          <ul className="divide-y divide-slate-800/60">
            {integrations.map(integ => (
              <li key={integ.id} className="px-5 py-3 flex items-center gap-3">
                <span className="text-xs font-medium text-slate-300 w-28 shrink-0">
                  {INTEGRATION_TYPE_LABELS[integ.type] ?? integ.type}
                </span>
                <span className="text-xs text-slate-500 font-mono truncate flex-1">
                  {integ.displayName ?? integ.name}
                </span>
                {integ.baseUrl && (
                  <span className="text-xs text-slate-600 font-mono truncate hidden sm:block max-w-[200px]">
                    {integ.baseUrl}
                  </span>
                )}
                {integ.isDefault && (
                  <span className="text-xs bg-blue-900/40 text-blue-400 border border-blue-800 px-1.5 py-0.5 rounded">
                    default
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Danger zone */}
      {!isDefault && (
        <div className="bg-slate-900 border border-red-900/40 rounded-xl p-5">
          <h3 className="text-sm font-medium text-red-400 mb-1">Опасная зона</h3>
          <p className="text-xs text-slate-500 mb-3">Удаление проекта необратимо.</p>
          <button
            type="button"
            onClick={remove}
            className="flex items-center gap-2 bg-red-900/40 hover:bg-red-900/70 border border-red-800 text-red-300 text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            <Trash2 className="w-4 h-4" /> Удалить проект
          </button>
        </div>
      )}
    </div>
  )
}
