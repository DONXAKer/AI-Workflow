import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { Save, Loader2, AlertCircle, Trash2, Plug, FileCode, FolderOpen, Brain, Globe, Terminal } from 'lucide-react'
import { api } from '../../services/api'
import { ProjectInfo, IntegrationConfig, LlmProvider } from '../../types'
import PathInput from '../../components/PathInput'
import { PipelineEditor } from '../../components/PipelineEditor'

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
  const [orchEnabled, setOrchEnabled] = useState(true)
  const [orchModel, setOrchModel] = useState('')
  const [orchExtra, setOrchExtra] = useState('')
  const [defaultProvider, setDefaultProvider] = useState<LlmProvider>('OPENROUTER')
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [activeTab, setActiveTab] = useState<'project' | 'pipeline'>('project')

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
        setOrchEnabled(found.orchestratorEnabled ?? true)
        setOrchModel(found.orchestratorModel ?? '')
        setOrchExtra(found.orchestratorSystemPromptExtra ?? '')
        setDefaultProvider(found.defaultProvider ?? 'OPENROUTER')
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
        orchestratorEnabled: orchEnabled,
        orchestratorModel: orchModel.trim() || null,
        orchestratorSystemPromptExtra: orchExtra.trim() || null,
        defaultProvider,
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

  return (
    <div className={activeTab === 'pipeline' ? '' : 'max-w-2xl mx-auto px-4 sm:px-6 py-8 space-y-6'}>
      {/* Tab bar */}
      <div className={`flex gap-1 border-b border-slate-800 pb-0 ${activeTab === 'pipeline' ? 'px-4 pt-4' : ''}`}>
        {([['project', 'Проект'], ['pipeline', 'Пайплайн']] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-medium rounded-t-lg transition-colors -mb-px border-b-2 ${
              activeTab === tab
                ? 'text-slate-100 border-blue-500'
                : 'text-slate-500 border-transparent hover:text-slate-300'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {activeTab === 'pipeline' && <PipelineEditor />}

      {activeTab === 'project' && <>
      <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wide sr-only">Настройки проекта</h2>

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

        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            LLM-провайдер по умолчанию
          </label>
          <div className="grid grid-cols-2 gap-2">
            {([
              ['OPENROUTER', 'OpenRouter', 'Платный API, оплачивается по токенам', Globe, 'emerald'],
              ['CLAUDE_CODE_CLI', 'Claude Code CLI', 'Локальный claude -p, ваша Max-подписка', Terminal, 'orange'],
            ] as const).map(([value, title, hint, Icon, accent]) => {
              const active = defaultProvider === value
              const accentBorder = accent === 'emerald'
                ? (active ? 'border-emerald-600 bg-emerald-950/40' : 'border-slate-700 hover:border-emerald-800/60')
                : (active ? 'border-orange-600 bg-orange-950/40' : 'border-slate-700 hover:border-orange-800/60')
              const iconColor = accent === 'emerald' ? 'text-emerald-400' : 'text-orange-400'
              return (
                <button
                  key={value}
                  type="button"
                  onClick={() => setDefaultProvider(value)}
                  disabled={saving}
                  className={`text-left rounded-lg border px-3 py-2.5 transition-colors ${accentBorder}`}
                >
                  <div className="flex items-center gap-2">
                    <Icon className={`w-4 h-4 ${iconColor}`} />
                    <span className="text-sm font-medium text-slate-100">{title}</span>
                  </div>
                  <p className="text-xs text-slate-500 mt-1">{hint}</p>
                </button>
              )
            })}
          </div>
          <p className="text-xs text-slate-600 mt-1">
            Используется когда run стартует без явного <code className="font-mono bg-slate-800 px-1 rounded">inputs.provider</code>.
            Pipeline блоки могут гейтиться на <code className="font-mono bg-slate-800 px-1 rounded">$.input.provider == 'CLAUDE_CODE_CLI'</code>.
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

      {/* Orchestrator settings */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Brain className="w-4 h-4 text-purple-400" />
            <span className="text-sm font-medium text-slate-300">Оркестратор</span>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={orchEnabled}
            onClick={() => setOrchEnabled(v => !v)}
            className={`relative w-9 h-5 rounded-full border transition-colors focus:outline-none flex-shrink-0 ${orchEnabled ? 'bg-purple-600 border-purple-500' : 'bg-slate-700 border-slate-600'}`}
          >
            <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${orchEnabled ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
        </div>
        <p className="text-xs text-slate-500">
          Блок <code className="font-mono bg-slate-800 px-1 rounded">orchestrator</code> — план перед и ревью после агентных блоков.
        </p>

        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Модель по умолчанию
          </label>
          <input
            type="text"
            value={orchModel}
            onChange={e => setOrchModel(e.target.value)}
            placeholder="anthropic/claude-sonnet-4-5"
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 font-mono focus:outline-none focus:ring-2 focus:ring-purple-500"
          />
          <p className="text-xs text-slate-600 mt-1">Пусто — используется модель из YAML-блока.</p>
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Контекст проекта для оркестратора
          </label>
          <textarea
            value={orchExtra}
            onChange={e => setOrchExtra(e.target.value)}
            rows={4}
            placeholder={"Язык: Java 21 + Spring Boot 3.\nПакеты: api/, core/, blocks/.\nСтиль: без комментариев, snake_case в JSON."}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-purple-500 resize-y"
          />
          <p className="text-xs text-slate-600 mt-1">Добавляется в системный промпт каждого orchestrator-блока этого проекта.</p>
        </div>

        <div className="flex justify-end">
          <button
            type="button"
            onClick={save}
            disabled={saving}
            className="flex items-center gap-2 bg-purple-700 hover:bg-purple-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
            Сохранить
          </button>
        </div>
      </div>

      {/* Danger zone */}
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
      </>}
    </div>
  )
}
