import { useState, useEffect, useCallback } from 'react'
import {
  Plus, Trash2, Edit3, Loader2, AlertCircle, Save, X, Bot, Wrench,
} from 'lucide-react'
import type { AgentProfile, SkillInfo } from '../types'
import { api } from '../services/api'
import clsx from 'clsx'
import IntegrationSlideOver from './integrations/IntegrationSlideOver'
import PageHeader from './layout/PageHeader'

const EMPTY_FORM: Omit<AgentProfile, 'id'> = {
  name: '',
  displayName: '',
  description: '',
  rolePrompt: '',
  customPrompt: '',
  model: 'claude-sonnet-4-6',
  maxTokens: 8192,
  temperature: 1.0,
  skills: [],
  knowledgeSources: [],
  useExamples: false,
  recommendedPreset: '',
}

const PRESETS = ['', 'fast', 'smart', 'reasoning', 'cheap']

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
  initial?: AgentProfile
  availableSkills: SkillInfo[]
  onSave: (data: Omit<AgentProfile, 'id'>) => Promise<void>
  onCancel: () => void
}

function ProfileForm({ initial, availableSkills, onSave, onCancel }: FormProps) {
  const [form, setForm] = useState<Omit<AgentProfile, 'id'>>({ ...EMPTY_FORM, ...(initial ?? {}) })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const set = useCallback(<K extends keyof typeof form>(key: K, value: (typeof form)[K]) => {
    setForm(prev => ({ ...prev, [key]: value }))
  }, [])

  const toggleSkill = useCallback((skillName: string) => {
    setForm(prev => {
      const skills = prev.skills.includes(skillName)
        ? prev.skills.filter(s => s !== skillName)
        : [...prev.skills, skillName]
      return { ...prev, skills }
    })
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim()) { setError('Имя обязательно.'); return }
    setSaving(true)
    setError(null)
    try {
      await onSave(form)
    } catch {
      setError('Не удалось сохранить профиль агента.')
    } finally {
      setSaving(false)
    }
  }

  const inputCls = 'w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent'
  const labelCls = 'block text-xs font-medium text-slate-400 mb-1.5'
  const textareaCls = clsx(inputCls, 'resize-y min-h-[80px]')

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className={labelCls}>Имя <span className="text-red-400">*</span></label>
          <input type="text" value={form.name} onChange={e => set('name', e.target.value)} placeholder="java-expert" className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Отображаемое имя</label>
          <input type="text" value={form.displayName} onChange={e => set('displayName', e.target.value)} placeholder="Java Expert" className={inputCls} />
        </div>
      </div>

      <div>
        <label className={labelCls}>Описание</label>
        <textarea value={form.description} onChange={e => set('description', e.target.value)} placeholder="Опишите специализацию агента..." className={textareaCls} rows={2} />
      </div>

      <div>
        <label className={labelCls}>Промпт роли</label>
        <textarea value={form.rolePrompt} onChange={e => set('rolePrompt', e.target.value)} placeholder="You are a professional Java developer with 15 years of experience in enterprise systems, Spring Boot, and microservices architecture..." className={textareaCls} rows={3} />
        <p className="text-xs text-slate-500 mt-1">Добавляется перед системным промптом блока для определения экспертизы и роли агента.</p>
      </div>

      <div>
        <label className={labelCls}>Дополнительный промпт</label>
        <textarea value={form.customPrompt} onChange={e => set('customPrompt', e.target.value)} placeholder="Дополнительные инструкции или контекст для агента..." className={textareaCls} rows={3} />
        <p className="text-xs text-slate-500 mt-1">Дополнительный промпт, добавляемый после промпта роли.</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div>
          <label className={labelCls}>Модель</label>
          <input type="text" value={form.model} onChange={e => set('model', e.target.value)} placeholder="claude-sonnet-4-6" className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Макс. токенов</label>
          <input type="number" value={form.maxTokens} onChange={e => set('maxTokens', parseInt(e.target.value) || 8192)} min={1} max={200000} className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Температура</label>
          <div className="flex items-center gap-2">
            <input type="range" min="0" max="2" step="0.1" value={form.temperature} onChange={e => set('temperature', parseFloat(e.target.value))} className="flex-1 accent-blue-500" />
            <span className="text-sm text-slate-300 w-8 text-right">{form.temperature.toFixed(1)}</span>
          </div>
        </div>
      </div>

      {availableSkills.length > 0 && (
        <div>
          <label className={labelCls}>Навыки</label>
          <div className="grid grid-cols-1 gap-2 mt-1">
            {availableSkills.map(skill => (
              <label key={skill.name} className="flex items-start gap-2.5 cursor-pointer select-none group">
                <input
                  type="checkbox"
                  checked={form.skills.includes(skill.name)}
                  onChange={() => toggleSkill(skill.name)}
                  className="mt-0.5 rounded border-slate-600 bg-slate-800 text-blue-500 focus:ring-blue-500 focus:ring-offset-0"
                />
                <div>
                  <span className="text-sm text-slate-200 font-mono group-hover:text-white transition-colors">{skill.name}</span>
                  <p className="text-xs text-slate-500">{skill.description}</p>
                </div>
              </label>
            ))}
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className={labelCls}>Источники знаний (через запятую)</label>
          <input
            type="text"
            value={(form.knowledgeSources ?? []).join(', ')}
            onChange={e => set('knowledgeSources',
              e.target.value.split(',').map(s => s.trim()).filter(s => s.length > 0))}
            placeholder="architecture_docs, coding_standards"
            className={inputCls}
            aria-label="Источники знаний"
          />
          <p className="text-xs text-slate-500 mt-1">Имена knowledge-источников из pipeline-конфига. Агент получит только указанные — остальные скрыты от контекста.</p>
        </div>
        <div>
          <label className={labelCls}>Рекомендованный пресет модели</label>
          <select
            value={form.recommendedPreset ?? ''}
            onChange={e => set('recommendedPreset', e.target.value)}
            className={inputCls}
            aria-label="Рекомендованный пресет"
          >
            {PRESETS.map(p => (
              <option key={p} value={p}>{p || '(не задан)'}</option>
            ))}
          </select>
          <p className="text-xs text-slate-500 mt-1">Резолвится через ModelPresetResolver в конкретный OpenRouter-ID.</p>
        </div>
      </div>

      <label className="flex items-start gap-2.5 text-sm text-slate-300 cursor-pointer select-none">
        <input
          type="checkbox"
          checked={form.useExamples ?? false}
          onChange={e => set('useExamples', e.target.checked)}
          className="mt-0.5 rounded border-slate-600 bg-slate-800 text-blue-500 focus:ring-blue-500"
        />
        <span>
          <span className="font-medium">Использовать few-shot-примеры</span>
          <span className="block text-xs text-slate-500 mt-0.5">
            Перед каждым вызовом агент получает 2-3 успешных примера из истории того же проекта (ExampleRetriever).
          </span>
        </span>
      </label>

      {form.builtin && (
        <div className="flex items-start gap-2 text-xs text-amber-300 bg-amber-950/30 border border-amber-800/50 rounded-lg px-3 py-2">
          <AlertCircle className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" />
          Это встроенный профиль (built-in). Изменения через UI будут проигнорированы — редактируйте YAML в {`resources/skills/`}.
        </div>
      )}

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

type SlideOverState =
  | { mode: 'add' }
  | { mode: 'edit'; profile: AgentProfile }
  | null

export default function AgentProfilesSettings() {
  const [profiles, setProfiles] = useState<AgentProfile[]>([])
  const [availableSkills, setAvailableSkills] = useState<SkillInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [slideOver, setSlideOver] = useState<SlideOverState>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setLoadError(null)
    Promise.all([api.listAgentProfiles(), api.listAvailableSkills()])
      .then(([p, s]) => { setProfiles(p); setAvailableSkills(s) })
      .catch(() => setLoadError('��е удалось загрузить профили агентов'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load])

  const handleAdd = useCallback(async (data: Omit<AgentProfile, 'id'>) => {
    await api.createAgentProfile(data as AgentProfile)
    setSlideOver(null)
    load()
  }, [load])

  const handleEdit = useCallback(async (id: number, data: Omit<AgentProfile, 'id'>) => {
    await api.updateAgentProfile(id, data)
    setSlideOver(null)
    load()
  }, [load])

  const handleDelete = useCallback(async (id: number) => {
    setDeletingId(id)
    setDeleteError(null)
    try {
      await api.deleteAgentProfile(id)
      load()
    } catch {
      setDeleteError(`Не удалось удалить профиль ${id}`)
    } finally {
      setDeletingId(null)
    }
  }, [load])

  const sorted = [...profiles].sort((a, b) => a.name.localeCompare(b.name))

  const slideOverTitle =
    slideOver?.mode === 'add' ? 'Добавить профиль' :
    slideOver?.mode === 'edit' ? `Редактирование — ${slideOver.profile.name}` :
    ''

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Профили агентов"
        description="Переиспользуемые конфигурации агентов с ролями, промптами и навыками"
        actions={
          <button
            type="button"
            onClick={() => setSlideOver({ mode: 'add' })}
            className="flex items-center gap-1.5 text-sm px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium transition-colors"
          >
            <Plus className="w-4 h-4" />
            Добавить
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
          <Loader2 className="w-4 h-4 animate-spin" />Загрузка профилей...
        </div>
      ) : sorted.length === 0 ? (
        <div className="bg-slate-900/50 border border-slate-800 rounded-xl px-5 py-16 flex flex-col items-center gap-4">
          <Bot className="w-12 h-12 text-slate-700" />
          <div className="text-center">
            <p className="text-slate-400 text-sm font-medium">Профили агентов не настроены</p>
            <p className="text-slate-600 text-xs mt-1">Создайте профили для определения ро��ей, экспертизы и навыков агентов.</p>
          </div>
          <button
            type="button"
            onClick={() => setSlideOver({ mode: 'add' })}
            className="flex items-center gap-1.5 text-sm px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium transition-colors"
          >
            <Plus className="w-4 h-4" />
            Добавить
          </button>
        </div>
      ) : (
        <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-800">
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Имя</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Модель</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Токены</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Темп.</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Навыки</th>
                  <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {sorted.map(profile => {
                  const id = profile.id!
                  return (
                    <tr key={id} className="hover:bg-slate-800/30 transition-colors">
                      <td className="px-5 py-3.5">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-slate-200">{profile.name}</span>
                          {profile.builtin && (
                            <span className="text-[10px] bg-slate-800 border border-slate-700 text-slate-400 px-1.5 py-0.5 rounded uppercase tracking-wide">
                              built-in
                            </span>
                          )}
                          {profile.useExamples && (
                            <span
                              className="text-[10px] bg-blue-950/40 border border-blue-800/60 text-blue-300 px-1.5 py-0.5 rounded uppercase tracking-wide"
                              title="Few-shot: агент получает примеры из истории"
                            >
                              few-shot
                            </span>
                          )}
                        </div>
                        {profile.displayName && profile.displayName !== profile.name && (
                          <div className="text-xs text-slate-500">{profile.displayName}</div>
                        )}
                        {profile.description && (
                          <div className="text-xs text-slate-600 mt-0.5 max-w-xs truncate" title={profile.description}>{profile.description}</div>
                        )}
                      </td>
                      <td className="px-5 py-3.5">
                        <span className="inline-flex items-center text-xs font-mono text-slate-400 bg-slate-800 px-2 py-0.5 rounded">
                          {profile.model || 'default'}
                        </span>
                      </td>
                      <td className="px-5 py-3.5 text-slate-400 text-xs font-mono">
                        {profile.maxTokens?.toLocaleString() ?? '8192'}
                      </td>
                      <td className="px-5 py-3.5 text-slate-400 text-xs font-mono">
                        {profile.temperature?.toFixed(1) ?? '1.0'}
                      </td>
                      <td className="px-5 py-3.5">
                        {profile.skills.length > 0 ? (
                          <div className="flex items-center gap-1.5">
                            <Wrench className="w-3.5 h-3.5 text-slate-500" />
                            <span className="text-xs text-slate-400">{profile.skills.length}</span>
                          </div>
                        ) : (
                          <span className="text-slate-600 text-xs">—</span>
                        )}
                      </td>
                      <td className="px-5 py-3.5">
                        <div className="flex items-center gap-2 flex-wrap">
                          <button
                            type="button"
                            onClick={() => setSlideOver({ mode: 'edit', profile })}
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
      >
        {slideOver?.mode === 'add' && (
          <ProfileForm
            availableSkills={availableSkills}
            onSave={handleAdd}
            onCancel={() => setSlideOver(null)}
          />
        )}
        {slideOver?.mode === 'edit' && (
          <ProfileForm
            initial={slideOver.profile}
            availableSkills={availableSkills}
            onSave={data => handleEdit(slideOver.profile.id!, data)}
            onCancel={() => setSlideOver(null)}
          />
        )}
      </IntegrationSlideOver>
    </div>
  )
}
