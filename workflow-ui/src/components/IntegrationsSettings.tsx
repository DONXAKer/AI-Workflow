import { useState, useEffect, useCallback } from 'react'
import {
  Plus, Trash2, Edit3, Eye, EyeOff, CheckCircle, XCircle,
  Loader2, AlertCircle, Save, X, TicketCheck, GitBranch, Github, Cpu, ChevronRight, Layers, Terminal,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { IntegrationConfig, IntegrationType } from '../types'
import { api } from '../services/api'
import clsx from 'clsx'
import IntegrationSlideOver from './integrations/IntegrationSlideOver'
import PageHeader from './layout/PageHeader'

const INTEGRATION_TYPES: IntegrationType[] = ['YOUTRACK', 'GITLAB', 'GITHUB', 'OPENROUTER', 'AITUNNEL', 'UNREAL', 'CLAUDE_CODE_CLI', 'OLLAMA']

const TYPE_LABELS: Record<IntegrationType, string> = {
  YOUTRACK:        'YouTrack',
  GITLAB:          'GitLab',
  GITHUB:          'GitHub',
  OPENROUTER:      'OpenRouter',
  AITUNNEL:        'AITunnel',
  UNREAL:          'Unreal Engine',
  CLAUDE_CODE_CLI: 'Claude Agent',
  OLLAMA:          'Ollama',
}

const TYPE_BADGE: Record<IntegrationType, string> = {
  YOUTRACK:        'bg-violet-900/50 text-violet-300 border-violet-800/50',
  GITLAB:          'bg-orange-900/50 text-orange-300 border-orange-800/50',
  GITHUB:          'bg-slate-700/60 text-slate-300 border-slate-600/50',
  OPENROUTER:      'bg-blue-900/50 text-blue-300 border-blue-800/50',
  AITUNNEL:        'bg-cyan-900/50 text-cyan-300 border-cyan-800/50',
  UNREAL:          'bg-emerald-900/50 text-emerald-300 border-emerald-800/50',
  CLAUDE_CODE_CLI: 'bg-amber-900/50 text-amber-300 border-amber-800/50',
  OLLAMA:          'bg-purple-900/50 text-purple-300 border-purple-800/50',
}

const TYPE_META: Record<IntegrationType, { description: string; icon: LucideIcon; accent: string }> = {
  YOUTRACK:        { description: 'Трекер задач JetBrains',        icon: TicketCheck, accent: 'text-violet-400' },
  GITLAB:          { description: 'Репозитории и CI/CD',            icon: GitBranch,   accent: 'text-orange-400' },
  GITHUB:          { description: 'Репозитории и PR',               icon: Github,      accent: 'text-slate-300'  },
  OPENROUTER:      { description: 'Маршрутизация AI-моделей',       icon: Cpu,         accent: 'text-blue-400'   },
  AITUNNEL:        { description: 'AITunnel.ru — российский AI-шлюз', icon: Cpu,       accent: 'text-cyan-400'   },
  UNREAL:          { description: 'Unreal MCP + Blueprint роутинг', icon: Layers,      accent: 'text-emerald-400' },
  CLAUDE_CODE_CLI: { description: 'Claude CLI — подписка Max/API',  icon: Terminal,    accent: 'text-amber-400'  },
  OLLAMA:          { description: 'Локальный Ollama — без API-ключа', icon: Cpu,       accent: 'text-purple-400' },
}

const DEFAULT_UNREAL_EXTRA = JSON.stringify({
  mcpServerName: "unreal-mcp",
  heuristicKeywords: {
    needs_bp:              ["blueprint", "umg", "bp_"],
    needs_server:          ["server", "gameplay", "authoritative"],
    needs_client:          ["client", "ui", "widget", "hud"],
    needs_contract_change: ["ustruct", "contract", "proto", "message schema", "payload"]
  }
}, null, 2)

type SlideOverState =
  | { mode: 'choose-type' }
  | { mode: 'add'; type: IntegrationType }
  | { mode: 'edit'; integration: IntegrationConfig }
  | null

const EMPTY_FORM: Omit<IntegrationConfig, 'id'> = {
  name: '',
  type: 'YOUTRACK',
  displayName: '',
  baseUrl: '',
  token: '',
  project: '',
  owner: '',
  repo: '',
  extraConfigJson: '',
  isDefault: false,
}

function getOllamaDisableReasoning(extraConfigJson: string | undefined | null): boolean {
  if (!extraConfigJson || !extraConfigJson.trim()) return false
  try { return Boolean(JSON.parse(extraConfigJson).disableReasoning) } catch { return false }
}

function setOllamaDisableReasoning(extraConfigJson: string | undefined | null, value: boolean): string {
  let obj: Record<string, unknown> = {}
  if (extraConfigJson && extraConfigJson.trim()) {
    try { obj = JSON.parse(extraConfigJson) } catch { obj = {} }
  }
  if (value) obj.disableReasoning = true
  else delete obj.disableReasoning
  return Object.keys(obj).length === 0 ? '' : JSON.stringify(obj)
}

function typeHasField(type: IntegrationType, field: 'baseUrl' | 'project' | 'owner' | 'repo'): boolean {
  switch (field) {
    case 'baseUrl': return type !== 'UNREAL'
    case 'project': return type === 'YOUTRACK' || type === 'GITLAB'
    case 'owner': return type === 'GITHUB'
    case 'repo': return type === 'GITHUB'
  }
}

function typeRequiresToken(type: IntegrationType): boolean {
  return type !== 'UNREAL' && type !== 'CLAUDE_CODE_CLI' && type !== 'OLLAMA'
}

function TokenCell({ token }: { token: string }) {
  const [revealed, setRevealed] = useState(false)
  return (
    <div className="flex items-center gap-2">
      <span className="font-mono text-xs text-slate-400">{revealed ? token : '••••••••••••'}</span>
      <button
        type="button"
        onClick={() => setRevealed(v => !v)}
        className="text-slate-500 hover:text-slate-300 transition-colors"
        title={revealed ? 'Скрыть токен' : 'Показать токен'}
      >
        {revealed ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
      </button>
    </div>
  )
}

function TestButton({ id }: { id: number }) {
  const [state, setState] = useState<'idle' | 'loading' | 'success' | 'failure'>('idle')
  const [message, setMessage] = useState('')

  const handleTest = async () => {
    setState('loading')
    setMessage('')
    try {
      const result = await api.testIntegration(id)
      const nextState = result.success ? 'success' : 'failure'
      setState(nextState)
      setMessage(result.message)
      if (nextState === 'success') {
        setTimeout(() => setState('idle'), 4000)
      }
    } catch {
      setState('failure')
      setMessage('Ошибка запроса')
    }
  }

  if (state === 'loading') return (
    <button disabled className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-slate-700 text-slate-400">
      <Loader2 className="w-3.5 h-3.5 animate-spin" />Проверка...
    </button>
  )
  if (state === 'success') return (
    <span className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-green-900/50 text-green-300 border border-green-800/50">
      <CheckCircle className="w-3.5 h-3.5" />Ок
      {message && <span className="text-green-400/70 ml-1 truncate max-w-[12rem]" title={message}>{message}</span>}
    </span>
  )
  if (state === 'failure') return (
    <span className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-red-900/50 text-red-300 border border-red-800/50">
      <XCircle className="w-3.5 h-3.5 flex-shrink-0" />Ошибка
      {message && <span className="text-red-400/70 ml-1 truncate max-w-[12rem]" title={message}>{message}</span>}
    </span>
  )
  return (
    <button type="button" onClick={handleTest} className="text-xs px-2.5 py-1.5 rounded-md bg-slate-700 hover:bg-slate-600 text-slate-300 transition-colors">
      Тест
    </button>
  )
}

function DeleteButton({ deleting, onDelete }: { deleting: boolean; onDelete: () => void }) {
  const [confirming, setConfirming] = useState(false)

  if (deleting) return (
    <span className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-slate-700 text-slate-400">
      <Loader2 className="w-3.5 h-3.5 animate-spin" />Удаление...
    </span>
  )
  if (confirming) return (
    <div className="flex items-center gap-1.5">
      <span className="text-xs text-red-400">Удалить?</span>
      <button type="button" onClick={() => { setConfirming(false); onDelete() }} className="text-xs px-2.5 py-1.5 rounded-md bg-red-800 hover:bg-red-700 text-white transition-colors">Да</button>
      <button type="button" onClick={() => setConfirming(false)} className="text-xs px-2 py-1.5 rounded-md bg-slate-700 hover:bg-slate-600 text-slate-300 transition-colors">Нет</button>
    </div>
  )
  return (
    <button type="button" onClick={() => setConfirming(true)} className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-red-950/50 hover:bg-red-900/60 border border-red-800/50 text-red-400 transition-colors">
      <Trash2 className="w-3.5 h-3.5" />Удалить
    </button>
  )
}

interface FormProps {
  initial?: IntegrationConfig
  lockedType?: boolean
  onSave: (data: Omit<IntegrationConfig, 'id'>) => Promise<void>
  onCancel: () => void
}

function IntegrationForm({ initial, lockedType = false, onSave, onCancel }: FormProps) {
  const [form, setForm] = useState<Omit<IntegrationConfig, 'id'>>({ ...EMPTY_FORM, ...(initial ?? {}) })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const set = useCallback(<K extends keyof typeof form>(key: K, value: (typeof form)[K]) => {
    setForm(prev => ({ ...prev, [key]: value }))
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const tokenRequired = typeRequiresToken(form.type)
    if (!form.name.trim() || (tokenRequired && !form.token.trim())) {
      setError(tokenRequired ? 'Имя и Токен обязательны.' : 'Имя обязательно.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await onSave(form)
    } catch {
      setError('Не удалось сохранить интеграцию.')
    } finally {
      setSaving(false)
    }
  }

  const inputCls = 'w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent'
  const labelCls = 'block text-xs font-medium text-slate-400 mb-1.5'

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className={labelCls}>Имя <span className="text-red-400">*</span></label>
          <input type="text" value={form.name} onChange={e => set('name', e.target.value)} placeholder="my-youtrack" className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Отображаемое имя</label>
          <input type="text" value={form.displayName} onChange={e => set('displayName', e.target.value)} placeholder="My YouTrack" className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Тип</label>
          {lockedType ? (
            <div className={clsx(inputCls, 'flex items-center gap-2 cursor-default select-none opacity-60')}>
              <span>{TYPE_LABELS[form.type]}</span>
              <span className="ml-auto text-xs text-slate-500">(зафиксировано)</span>
            </div>
          ) : (
            <select value={form.type} onChange={e => set('type', e.target.value as IntegrationType)} className={inputCls}>
              {INTEGRATION_TYPES.map(t => <option key={t} value={t}>{TYPE_LABELS[t]}</option>)}
            </select>
          )}
        </div>
        {form.type !== 'CLAUDE_CODE_CLI' && form.type !== 'OLLAMA' && (
          <div>
            <label className={labelCls}>
              Токен {typeRequiresToken(form.type) && <span className="text-red-400">*</span>}
              {!typeRequiresToken(form.type) && <span className="text-slate-500">(необязательно)</span>}
            </label>
            <input type="password" value={form.token} onChange={e => set('token', e.target.value)} placeholder={form.type === 'UNREAL' ? 'MCP auth token (если нужен)' : 'API-токен или ключ...'} autoComplete="new-password" className={inputCls} />
          </div>
        )}
        {form.type === 'CLAUDE_CODE_CLI' && (
          <div className="sm:col-span-2 rounded-lg bg-amber-950/30 border border-amber-800/40 px-3 py-2.5 text-xs text-amber-300/80">
            Использует локальный Claude CLI (<code>claude -p</code>) с вашей подпиской. Токен API не нужен.
            Убедитесь, что <code>claude</code> доступен в PATH или укажите путь в поле Base URL ниже.
          </div>
        )}
        {form.type === 'OLLAMA' && (
          <>
            <div className="sm:col-span-2 rounded-lg bg-purple-950/30 border border-purple-800/40 px-3 py-2.5 text-xs text-purple-300/80">
              Локальный Ollama-сервер. Токен не нужен.{' '}
              В Docker используйте <code>http://host.docker.internal:11434</code>.
              Модели по умолчанию: <code>smart=qwen3:8b</code>, <code>flash=qwen3:8b</code>.
            </div>
            <label className="sm:col-span-2 flex items-start gap-3 rounded-lg bg-slate-800/40 border border-slate-700/60 px-3 py-2.5 cursor-pointer hover:bg-slate-800/60 transition-colors">
              <input
                type="checkbox"
                checked={getOllamaDisableReasoning(form.extraConfigJson)}
                onChange={e => set('extraConfigJson', setOllamaDisableReasoning(form.extraConfigJson, e.target.checked))}
                className="mt-0.5 w-4 h-4 rounded border-slate-600 bg-slate-900 text-blue-500 focus:ring-blue-500 focus:ring-offset-0"
              />
              <div className="text-xs">
                <div className="text-slate-200 font-medium">Отключить reasoning (qwen3 <code>/no_think</code>)</div>
                <div className="text-slate-400 mt-0.5">
                  Для моделей qwen3-семейства добавляет <code>/no_think</code> в user-промпт — модель пропускает chain-of-thought и сразу возвращает ответ. Без этого reasoning часто съедает весь max_tokens.
                </div>
              </div>
            </label>
          </>
        )}
        {typeHasField(form.type, 'baseUrl') && (
          <div className={clsx((form.type === 'OPENROUTER' || form.type === 'AITUNNEL' || form.type === 'CLAUDE_CODE_CLI' || form.type === 'OLLAMA') ? 'sm:col-span-2' : '')}>
            <label className={labelCls}>
              {form.type === 'CLAUDE_CODE_CLI' ? 'Путь к claude' : 'Base URL'}
              {' '}<span className="text-slate-500">(необязательно)</span>
            </label>
            <input
              type={form.type === 'CLAUDE_CODE_CLI' ? 'text' : 'url'}
              value={form.baseUrl}
              onChange={e => set('baseUrl', e.target.value)}
              placeholder={
                form.type === 'YOUTRACK' ? 'https://youtrack.example.com' :
                form.type === 'GITLAB' ? 'https://gitlab.com' :
                form.type === 'OPENROUTER' ? 'https://openrouter.ai/api/v1' :
                form.type === 'AITUNNEL' ? 'https://api.aitunnel.ru/v1' :
                form.type === 'CLAUDE_CODE_CLI' ? '/usr/local/bin/claude (по умолчанию: claude из PATH)' :
                form.type === 'OLLAMA' ? 'http://host.docker.internal:11434' :
                'https://api.github.com'
              }
              className={inputCls}
            />
          </div>
        )}
        {typeHasField(form.type, 'project') && (
          <div>
            <label className={labelCls}>{form.type === 'GITLAB' ? 'ID проекта' : 'Проект'}</label>
            <input type="text" value={form.project ?? ''} onChange={e => set('project', e.target.value)} placeholder={form.type === 'GITLAB' ? '12345678' : 'MY-PROJECT'} className={inputCls} />
          </div>
        )}
        {typeHasField(form.type, 'owner') && (
          <div>
            <label className={labelCls}>Владелец</label>
            <input type="text" value={form.owner ?? ''} onChange={e => set('owner', e.target.value)} placeholder="octocat" className={inputCls} />
          </div>
        )}
        {typeHasField(form.type, 'repo') && (
          <div>
            <label className={labelCls}>Репозиторий</label>
            <input type="text" value={form.repo ?? ''} onChange={e => set('repo', e.target.value)} placeholder="my-repo" className={inputCls} />
          </div>
        )}
      </div>

      {form.type === 'UNREAL' && (
        <div>
          <div className="flex items-center justify-between mb-1.5">
            <label className={labelCls + ' mb-0'}>Конфигурация (JSON)</label>
            {!form.extraConfigJson?.trim() && (
              <button
                type="button"
                onClick={() => set('extraConfigJson', DEFAULT_UNREAL_EXTRA)}
                className="text-xs text-blue-400 hover:text-blue-300 transition-colors"
              >
                Заполнить по умолчанию
              </button>
            )}
          </div>
          <textarea
            rows={12}
            value={form.extraConfigJson ?? ''}
            onChange={e => set('extraConfigJson', e.target.value)}
            placeholder={DEFAULT_UNREAL_EXTRA}
            className={inputCls + ' font-mono text-xs resize-y'}
          />
          <p className="text-xs text-slate-500 mt-1">
            <code>mcpServerName</code> — имя MCP-сервера; <code>heuristicKeywords</code> — ключевые слова для роутинга в pipeline.
          </p>
        </div>
      )}

      <label className="flex items-center gap-3 cursor-pointer select-none">
        <div
          className={clsx('relative w-9 h-5 rounded-full border transition-colors', form.isDefault ? 'bg-blue-600 border-blue-500' : 'bg-slate-700 border-slate-600')}
          onClick={() => set('isDefault', !form.isDefault)}
        >
          <span className={clsx('absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform', form.isDefault ? 'translate-x-4' : 'translate-x-0.5')} />
        </div>
        <span className="text-sm text-slate-300">Интеграция по умолчанию для этого типа</span>
      </label>

      {error && (
        <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />{error}
        </div>
      )}

      <div className="flex items-center gap-2 pt-1">
        <button type="submit" disabled={saving} className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors">
          {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
          {saving ? 'Сохранение...' : 'Сохранить'}
        </button>
        <button type="button" onClick={onCancel} className="flex items-center gap-2 bg-slate-800 hover:bg-slate-700 text-slate-300 text-sm font-medium px-4 py-2 rounded-lg transition-colors">
          <X className="w-4 h-4" />Отмена
        </button>
      </div>
    </form>
  )
}

function TypePicker({ onSelect }: { onSelect: (type: IntegrationType) => void }) {
  return (
    <div className="space-y-3">
      <p className="text-sm text-slate-400">Выберите тип интеграции:</p>
      <div className="grid grid-cols-2 gap-3">
        {INTEGRATION_TYPES.map(type => {
          const { description, icon: Icon, accent } = TYPE_META[type]
          return (
            <button
              key={type}
              type="button"
              onClick={() => onSelect(type)}
              className="relative flex flex-col gap-2 p-4 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 hover:border-slate-600 text-left transition-colors group"
            >
              <Icon className={clsx('w-6 h-6', accent)} />
              <div>
                <div className="text-sm font-semibold text-white">{TYPE_LABELS[type]}</div>
                <div className="text-xs text-slate-400 mt-0.5">{description}</div>
              </div>
              <ChevronRight className="absolute top-3 right-3 w-3.5 h-3.5 text-slate-600 group-hover:text-slate-400 transition-colors" />
            </button>
          )
        })}
      </div>
    </div>
  )
}

export default function IntegrationsSettings() {
  const [integrations, setIntegrations] = useState<IntegrationConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [slideOver, setSlideOver] = useState<SlideOverState>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setLoadError(null)
    api.listIntegrations()
      .then(setIntegrations)
      .catch(() => setLoadError('Не удалось загрузить интеграции'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load])

  const handleAdd = useCallback(async (data: Omit<IntegrationConfig, 'id'>) => {
    await api.createIntegration(data as IntegrationConfig)
    setSlideOver(null)
    load()
  }, [load])

  const handleEdit = useCallback(async (id: number, data: Omit<IntegrationConfig, 'id'>) => {
    await api.updateIntegration(id, data)
    setSlideOver(null)
    load()
  }, [load])

  const handleDelete = useCallback(async (id: number) => {
    setDeletingId(id)
    setDeleteError(null)
    try {
      await api.deleteIntegration(id)
      load()
    } catch {
      setDeleteError(`Не удалось удалить интеграцию ${id}`)
    } finally {
      setDeletingId(null)
    }
  }, [load])

  const sorted = [...integrations].sort((a, b) =>
    a.type.localeCompare(b.type) || a.name.localeCompare(b.name)
  )

  const slideOverTitle =
    slideOver?.mode === 'choose-type' ? 'Добавить интеграцию' :
    slideOver?.mode === 'add' ? `Добавить ${TYPE_LABELS[slideOver.type]}` :
    slideOver?.mode === 'edit' ? `Редактирование — ${slideOver.integration.name}` :
    ''

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Интеграции"
        description="Управление подключениями к внешним сервисам"
        actions={
          <button
            type="button"
            onClick={() => setSlideOver({ mode: 'choose-type' })}
            className="flex items-center gap-1.5 text-sm px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium transition-colors"
          >
            <Plus className="w-4 h-4" />
            Add Integration
          </button>
        }
      />

      {(loadError || deleteError) && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />{loadError ?? deleteError}
        </div>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-slate-400">
          <Loader2 className="w-4 h-4 animate-spin" />Загрузка интеграций...
        </div>
      ) : sorted.length === 0 ? (
        <div className="bg-slate-900/50 border border-slate-800 rounded-xl px-5 py-16 flex flex-col items-center gap-4">
          <svg className="w-12 h-12 text-slate-700" fill="none" viewBox="0 0 36 36" aria-hidden="true">
            <rect x="14" y="4" width="8" height="6" rx="1.5" stroke="currentColor" strokeWidth="2" opacity="0.5" />
            <path d="M10 10h16v6a8 8 0 01-16 0v-6z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" opacity="0.4" />
            <line x1="18" y1="22" x2="18" y2="32" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.35" />
          </svg>
          <div className="text-center">
            <p className="text-slate-400 text-sm font-medium">Интеграции не настроены</p>
            <p className="text-slate-600 text-xs mt-1">Подключите внешние сервисы для использования в пайплайнах.</p>
          </div>
          <button
            type="button"
            onClick={() => setSlideOver({ mode: 'choose-type' })}
            className="flex items-center gap-1.5 text-sm px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium transition-colors"
          >
            <Plus className="w-4 h-4" />
            Добавить интеграцию
          </button>
        </div>
      ) : (
        <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-800">
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Имя</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Тип</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Base URL</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Токен</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">По умолч.</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {sorted.map(integration => {
                  const id = integration.id!
                  return (
                    <tr key={id} className="hover:bg-slate-800/30 transition-colors">
                      <td className="px-5 py-3.5">
                        <div className="font-medium text-slate-200">{integration.name}</div>
                        {integration.displayName && integration.displayName !== integration.name && (
                          <div className="text-xs text-slate-500">{integration.displayName}</div>
                        )}
                      </td>
                      <td className="px-5 py-3.5">
                        <span className={clsx('inline-flex items-center text-xs font-medium px-2 py-0.5 rounded-full border', TYPE_BADGE[integration.type])}>
                          {TYPE_LABELS[integration.type]}
                        </span>
                      </td>
                      <td className="px-5 py-3.5 text-slate-400 text-xs font-mono max-w-xs truncate">
                        {integration.baseUrl || '—'}
                      </td>
                      <td className="px-5 py-3.5">
                        <TokenCell token={integration.token} />
                      </td>
                      <td className="px-5 py-3.5">
                        {integration.isDefault
                          ? <CheckCircle className="w-4 h-4 text-green-400" />
                          : <span className="text-slate-600 text-xs">—</span>}
                      </td>
                      <td className="px-5 py-3.5">
                        <div className="flex items-center gap-2 flex-wrap">
                          <TestButton id={id} />
                          <button
                            type="button"
                            onClick={() => setSlideOver({ mode: 'edit', integration })}
                            className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-slate-700 hover:bg-slate-600 text-slate-300 transition-colors"
                          >
                            <Edit3 className="w-3.5 h-3.5" />Изменить
                          </button>
                          <DeleteButton deleting={deletingId === id} onDelete={() => handleDelete(id)} />
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Slide-over */}
      <IntegrationSlideOver
        open={slideOver !== null}
        title={slideOverTitle}
        onClose={() => setSlideOver(null)}
        onBack={slideOver?.mode === 'add' ? () => setSlideOver({ mode: 'choose-type' }) : undefined}
      >
        {slideOver?.mode === 'choose-type' && (
          <TypePicker onSelect={type => setSlideOver({ mode: 'add', type })} />
        )}
        {slideOver?.mode === 'add' && (
          <IntegrationForm
            initial={{
              ...EMPTY_FORM,
              type: slideOver.type,
              ...(slideOver.type === 'UNREAL' ? { extraConfigJson: DEFAULT_UNREAL_EXTRA } : {}),
            }}
            lockedType
            onSave={handleAdd}
            onCancel={() => setSlideOver(null)}
          />
        )}
        {slideOver?.mode === 'edit' && (
          <IntegrationForm
            initial={slideOver.integration}
            onSave={data => handleEdit(slideOver.integration.id!, data)}
            onCancel={() => setSlideOver(null)}
          />
        )}
      </IntegrationSlideOver>
    </div>
  )
}
