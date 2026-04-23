import { useState, useEffect, useCallback } from 'react'
import { Save, Loader2, AlertCircle, ChevronDown, Settings2, X } from 'lucide-react'
import { api } from '../services/api'
import { PipelineConfigSettings, PipelineBlockSetting, PipelineAgentOverride, AgentProfile, SkillInfo } from '../types'
import { blockTypeLabel, BLOCK_TYPE_RECOMMENDED_PRESET, MODEL_TIERS } from '../utils/blockLabels'

const MODEL_PRESETS = [
  { value: '', label: 'Наследовать' },
  ...MODEL_TIERS.map(t => ({ value: t.preset, label: `${t.preset} — ${t.label} (${t.priceHint})` })),
  { value: '__custom__', label: 'Custom...' },
]

interface Props {
  // reserved for future use
}

interface PipelineInfo {
  path: string
  name: string
  pipelineName?: string
}

function ModelSelect({
  value,
  onChange,
}: {
  value: string | null | undefined
  onChange: (v: string | null) => void
}) {
  const isCustom = value != null && value !== '' && !MODEL_PRESETS.some(p => p.value === value && p.value !== '__custom__')
  const selectValue = isCustom ? '__custom__' : (value ?? '')
  const [custom, setCustom] = useState(isCustom ? (value ?? '') : '')

  useEffect(() => {
    if (isCustom) setCustom(value ?? '')
  }, [value, isCustom])

  return (
    <div className="flex gap-1.5 items-center">
      <select
        value={selectValue}
        onChange={e => {
          const v = e.target.value
          if (v === '__custom__') {
            onChange(custom || null)
          } else {
            onChange(v === '' ? null : v)
          }
        }}
        className="bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        {MODEL_PRESETS.map(p => (
          <option key={p.value} value={p.value}>{p.label}</option>
        ))}
      </select>
      {(selectValue === '__custom__') && (
        <input
          type="text"
          value={custom}
          onChange={e => { setCustom(e.target.value); onChange(e.target.value || null) }}
          placeholder="vendor/model-id"
          className="bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5 text-xs text-slate-100 w-44 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      )}
    </div>
  )
}

function BlockEditModal({
  block,
  profiles,
  skills,
  onSave,
  onClose,
}: {
  block: PipelineBlockSetting
  profiles: AgentProfile[]
  skills: SkillInfo[]
  onSave: (updated: PipelineBlockSetting) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState<PipelineBlockSetting>({ ...block, agent: { ...block.agent }, skills: [...block.skills] })

  const updateAgent = (patch: Partial<PipelineAgentOverride>) =>
    setDraft(d => ({ ...d, agent: { ...d.agent, ...patch } }))

  const toggleSkill = (skill: string) => {
    setDraft(d => ({
      ...d,
      skills: d.skills.includes(skill) ? d.skills.filter(s => s !== skill) : [...d.skills, skill],
    }))
  }

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
      <div className="bg-slate-900 border border-slate-700 rounded-xl w-full max-w-lg mx-4 overflow-y-auto max-h-[90vh]">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-800">
          <div>
            <span className="text-sm font-semibold text-slate-100">{draft.id}</span>
            <span className="ml-2 text-xs text-slate-500 font-mono">{draft.block}</span>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-200">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-5">
          {/* Model override */}
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Модель (override)
            </label>
            <ModelSelect value={draft.agent.model} onChange={v => updateAgent({ model: v })} />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Temperature
              </label>
              <input
                type="number"
                min={0} max={2} step={0.1}
                value={draft.agent.temperature ?? ''}
                onChange={e => updateAgent({ temperature: e.target.value === '' ? null : parseFloat(e.target.value) })}
                placeholder="—"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Max Tokens
              </label>
              <input
                type="number"
                min={256}
                value={draft.agent.maxTokens ?? ''}
                onChange={e => updateAgent({ maxTokens: e.target.value === '' ? null : parseInt(e.target.value) })}
                placeholder="—"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
          </div>

          {/* Profile */}
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Профиль агента
            </label>
            <select
              value={draft.profile ?? ''}
              onChange={e => setDraft(d => ({ ...d, profile: e.target.value || null }))}
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              <option value="">— Не задан —</option>
              {profiles.map(p => (
                <option key={p.name} value={p.name}>{p.displayName || p.name}</option>
              ))}
            </select>
          </div>

          {/* Additional skills */}
          {skills.length > 0 && (
            <div>
              <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
                Дополнительные скиллы
              </label>
              <div className="grid grid-cols-2 gap-1.5">
                {skills.map(s => (
                  <label key={s.name} className="flex items-center gap-2 text-xs text-slate-300 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={draft.skills.includes(s.name)}
                      onChange={() => toggleSkill(s.name)}
                      className="accent-blue-500"
                    />
                    {s.name}
                  </label>
                ))}
              </div>
            </div>
          )}

          {/* System prompt append */}
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Дополнение к промпту
            </label>
            <textarea
              value={draft.agent.systemPrompt ?? ''}
              onChange={e => updateAgent({ systemPrompt: e.target.value || null })}
              rows={4}
              placeholder="Добавляется к встроенному промпту блока..."
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 resize-none font-mono"
            />
            <p className="text-xs text-slate-600 mt-1">Только append — не заменяет встроенный промпт блока.</p>
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-slate-800">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-slate-400 hover:text-slate-200 transition-colors"
          >
            Отмена
          </button>
          <button
            onClick={() => { onSave(draft); onClose() }}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            <Save className="w-3.5 h-3.5" /> Применить
          </button>
        </div>
      </div>
    </div>
  )
}

export default function PipelineConfigTab(_props: Props) {
  const [pipelines, setPipelines] = useState<PipelineInfo[]>([])
  const [selectedPath, setSelectedPath] = useState<string>('')
  const [settings, setSettings] = useState<PipelineConfigSettings | null>(null)
  const [profiles, setProfiles] = useState<AgentProfile[]>([])
  const [skills, setSkills] = useState<SkillInfo[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [editingBlock, setEditingBlock] = useState<PipelineBlockSetting | null>(null)

  useEffect(() => {
    Promise.all([api.listPipelines(), api.listAgentProfiles(), api.listAvailableSkills()])
      .then(([pipes, profs, skillList]) => {
        setPipelines(pipes)
        setProfiles(profs)
        setSkills(skillList)
        if (pipes.length > 0) setSelectedPath(pipes[0].path)
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))
  }, [])

  const loadConfig = useCallback(async (path: string) => {
    if (!path) return
    setLoading(true)
    setError(null)
    setDirty(false)
    try {
      const cfg = await api.getPipelineConfig(path)
      setSettings(cfg)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ошибка загрузки конфига')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (selectedPath) loadConfig(selectedPath)
  }, [selectedPath, loadConfig])

  const updateBlock = (updated: PipelineBlockSetting) => {
    setSettings(s => s ? ({
      ...s,
      blocks: s.blocks.map(b => b.id === updated.id ? updated : b),
    }) : s)
    setDirty(true)
  }

  const toggleBlock = (id: string, field: 'enabled' | 'approval', val: boolean) => {
    setSettings(s => s ? ({
      ...s,
      blocks: s.blocks.map(b => b.id === id ? { ...b, [field]: val } : b),
    }) : s)
    setDirty(true)
  }

  const updateDefaultModel = (model: string | null) => {
    setSettings(s => s ? ({
      ...s,
      defaults: { agent: { ...(s.defaults?.agent ?? {}), model } },
    }) : s)
    setDirty(true)
  }

  const save = async () => {
    if (!selectedPath || !settings) return
    setSaving(true)
    setSaveError(null)
    setSaved(false)
    try {
      await api.savePipelineConfig(selectedPath, settings)
      setSaved(true)
      setDirty(false)
      setTimeout(() => setSaved(false), 3000)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить')
    } finally {
      setSaving(false)
    }
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 text-red-400 p-6 text-sm">
        <AlertCircle className="w-4 h-4 flex-shrink-0" /> {error}
      </div>
    )
  }

  return (
    <div className="space-y-5">
      {/* Config selector */}
      <div className="flex items-center gap-3">
        <label className="text-xs font-medium text-slate-400 uppercase tracking-wide whitespace-nowrap">
          Конфиг
        </label>
        <div className="relative flex-1 max-w-sm">
          <select
            value={selectedPath}
            onChange={e => {
              if (dirty && !confirm('Есть несохранённые изменения. Переключить конфиг?')) return
              setSelectedPath(e.target.value)
            }}
            className="w-full appearance-none bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 pr-8 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            {pipelines.map(p => (
              <option key={p.path} value={p.path}>
                {p.pipelineName || p.name}
              </option>
            ))}
          </select>
          <ChevronDown className="absolute right-2.5 top-2.5 w-4 h-4 text-slate-500 pointer-events-none" />
        </div>
      </div>

      {loading && (
        <div className="flex items-center gap-2 text-slate-400 text-sm py-4">
          <Loader2 className="w-4 h-4 animate-spin" /> Загрузка...
        </div>
      )}

      {settings && !loading && (
        <>
          {/* Defaults */}
          <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
            <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-4">
              Настройки по умолчанию
            </h3>
            <div className="flex items-center gap-3">
              <label className="text-xs text-slate-400 w-24 shrink-0">Модель</label>
              <ModelSelect
                value={settings.defaults?.agent?.model}
                onChange={updateDefaultModel}
              />
            </div>
          </div>

          {/* Blocks table */}
          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
            <div className="px-5 py-3 border-b border-slate-800">
              <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide">Блоки</h3>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-800">
                    <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-40">Блок</th>
                    <th className="text-center px-3 py-2.5 text-xs font-medium text-slate-500 w-16">Вкл.</th>
                    <th className="text-center px-3 py-2.5 text-xs font-medium text-slate-500 w-20">Approval</th>
                    <th className="text-left px-3 py-2.5 text-xs font-medium text-slate-500">Модель override</th>
                    <th className="text-left px-3 py-2.5 text-xs font-medium text-slate-500 w-28">Профиль</th>
                    <th className="px-3 py-2.5 w-10"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60">
                  {settings.blocks.map(block => {
                    const hasOverride = block.agent.model || block.agent.temperature != null || block.agent.maxTokens != null || block.agent.systemPrompt
                    const ruLabel = blockTypeLabel(block.block)
                    const recommended = BLOCK_TYPE_RECOMMENDED_PRESET[block.block]
                    return (
                      <tr key={block.id} className={!block.enabled ? 'opacity-50' : ''}>
                        <td className="px-4 py-2.5">
                          {ruLabel
                            ? <div className="text-sm text-slate-100 font-medium leading-tight">{ruLabel}</div>
                            : <div className="font-mono text-xs text-slate-100">{block.id}</div>
                          }
                          <div className="font-mono text-xs text-slate-600 mt-0.5">{block.id}</div>
                          {recommended && !block.agent.model && (
                            <div className="text-[10px] text-slate-600 mt-0.5">
                              рек: <span className="text-slate-500">{recommended}</span>
                            </div>
                          )}
                        </td>
                        <td className="px-3 py-2.5 text-center">
                          <input
                            type="checkbox"
                            checked={block.enabled}
                            onChange={e => toggleBlock(block.id, 'enabled', e.target.checked)}
                            className="accent-blue-500 w-4 h-4"
                          />
                        </td>
                        <td className="px-3 py-2.5 text-center">
                          <input
                            type="checkbox"
                            checked={block.approval}
                            onChange={e => toggleBlock(block.id, 'approval', e.target.checked)}
                            className="accent-amber-500 w-4 h-4"
                          />
                        </td>
                        <td className="px-3 py-2.5">
                          <ModelSelect
                            value={block.agent.model}
                            onChange={v => updateBlock({ ...block, agent: { ...block.agent, model: v } })}
                          />
                        </td>
                        <td className="px-3 py-2.5 text-xs text-slate-400 font-mono">
                          {block.profile || <span className="text-slate-700">—</span>}
                        </td>
                        <td className="px-3 py-2.5">
                          <button
                            onClick={() => setEditingBlock(block)}
                            title="Настроить промпт и скиллы"
                            className={`p-1.5 rounded-lg transition-colors ${hasOverride ? 'text-blue-400 hover:text-blue-300 bg-blue-950/40' : 'text-slate-600 hover:text-slate-300 hover:bg-slate-800'}`}
                          >
                            <Settings2 className="w-3.5 h-3.5" />
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* Model reference */}
          <div className="bg-slate-900/50 border border-slate-800 rounded-xl p-4">
            <h3 className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">Справка по моделям</h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
              {MODEL_TIERS.map(t => (
                <div key={t.preset} className="flex items-start gap-2.5">
                  <span className={`text-xs font-mono px-1.5 py-0.5 rounded shrink-0 mt-0.5 ${
                    t.tier === 'premium' ? 'bg-purple-900/50 text-purple-300' :
                    t.tier === 'balanced' ? 'bg-blue-900/50 text-blue-300' :
                    t.tier === 'fast' ? 'bg-green-900/50 text-green-300' :
                    t.tier === 'cheap' ? 'bg-slate-700 text-slate-300' :
                    'bg-teal-900/50 text-teal-300'
                  }`}>{t.preset}</span>
                  <div>
                    <div className="text-xs text-slate-300">{t.label}</div>
                    <div className="text-xs text-slate-600">{t.description}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {saveError && (
            <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0" /> {saveError}
            </div>
          )}

          <div className="flex justify-end">
            <button
              onClick={save}
              disabled={saving || !dirty}
              className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              {saved ? 'Сохранено!' : 'Сохранить'}
            </button>
          </div>
        </>
      )}

      {editingBlock && (
        <BlockEditModal
          block={editingBlock}
          profiles={profiles}
          skills={skills}
          onSave={updateBlock}
          onClose={() => setEditingBlock(null)}
        />
      )}
    </div>
  )
}
