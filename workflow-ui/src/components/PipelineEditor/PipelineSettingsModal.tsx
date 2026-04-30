import { useState } from 'react'
import { X, Plus, Trash2 } from 'lucide-react'
import { EntryPointConfigDto, KnowledgeSourceConfigDto, PipelineConfigDto, TriggerConfigDto } from '../../types'
import { Link } from 'react-router-dom'

interface Props {
  config: PipelineConfigDto
  onChange: (patch: Partial<PipelineConfigDto>) => void
  onClose: () => void
}

type Tab = 'defaults' | 'entryPoints' | 'knowledge' | 'triggers' | 'integrations'

const TABS: { id: Tab; label: string }[] = [
  { id: 'defaults', label: 'Defaults' },
  { id: 'entryPoints', label: 'Entry Points' },
  { id: 'knowledge', label: 'Knowledge' },
  { id: 'triggers', label: 'Triggers' },
  { id: 'integrations', label: 'Integrations' },
]

export function PipelineSettingsModal({ config, onChange, onClose }: Props) {
  const [tab, setTab] = useState<Tab>('defaults')

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center" onClick={onClose}>
      <div
        data-testid="pipeline-settings-modal"
        className="bg-slate-900 border border-slate-700 rounded-xl w-[720px] max-h-[80vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-3 border-b border-slate-800">
          <h2 className="text-sm font-semibold text-slate-100">Настройки пайплайна</h2>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-100"><X className="w-4 h-4" /></button>
        </div>
        <div className="flex border-b border-slate-800">
          {TABS.map(t => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              data-testid={`pipeline-settings-tab-${t.id}`}
              className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                tab === t.id ? 'border-blue-500 text-white' : 'border-transparent text-slate-500 hover:text-slate-200'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="flex-1 overflow-y-auto p-4">
          {tab === 'defaults' && <DefaultsTab config={config} onChange={onChange} />}
          {tab === 'entryPoints' && <EntryPointsTab config={config} onChange={onChange} />}
          {tab === 'knowledge' && <KnowledgeTab config={config} onChange={onChange} />}
          {tab === 'triggers' && <TriggersTab config={config} onChange={onChange} />}
          {tab === 'integrations' && <IntegrationsTab config={config} />}
        </div>
      </div>
    </div>
  )
}

function DefaultsTab({ config, onChange }: { config: PipelineConfigDto; onChange: (p: Partial<PipelineConfigDto>) => void }) {
  const agent = config.defaults?.agent ?? {}
  const update = (k: string, v: unknown) => {
    const next = { ...agent }
    if (v === '' || v == null) {
      delete (next as Record<string, unknown>)[k]
    } else {
      ;(next as Record<string, unknown>)[k] = v
    }
    const empty = !next.model && next.temperature == null && !next.maxTokens && !next.systemPrompt
    onChange({ defaults: { agent: empty ? null : next } })
  }
  return (
    <div className="space-y-3 max-w-md">
      <div>
        <label className="block text-xs font-medium text-slate-300 mb-1">Default model</label>
        <input
          type="text"
          value={agent.model ?? ''}
          onChange={e => update('model', e.target.value)}
          placeholder="vendor/model-id"
          className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">Temperature</label>
          <input
            type="number" step={0.1}
            value={agent.temperature ?? ''}
            onChange={e => update('temperature', e.target.value === '' ? null : Number(e.target.value))}
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">Max tokens</label>
          <input
            type="number"
            value={agent.maxTokens ?? ''}
            onChange={e => update('maxTokens', e.target.value === '' ? null : Number(e.target.value))}
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
      </div>
    </div>
  )
}

function EntryPointsTab({ config, onChange }: { config: PipelineConfigDto; onChange: (p: Partial<PipelineConfigDto>) => void }) {
  const eps = config.entry_points ?? []
  const blockIds = (config.pipeline ?? []).map(b => b.id).filter(Boolean)

  const update = (idx: number, patch: Partial<EntryPointConfigDto>) => {
    const next = [...eps]
    next[idx] = { ...next[idx], ...patch }
    onChange({ entry_points: next })
  }
  const remove = (idx: number) => onChange({ entry_points: eps.filter((_, i) => i !== idx) })
  const add = () => onChange({
    entry_points: [...eps, { id: `entry_${eps.length + 1}`, name: 'New entry point', from_block: blockIds[0] ?? '', requires_input: 'requirement' }],
  })

  return (
    <div className="space-y-3">
      <button
        type="button"
        onClick={add}
        data-testid="entrypoint-add"
        className="text-xs text-blue-400 hover:text-blue-300 flex items-center gap-1"
      >
        <Plus className="w-3 h-3" /> Добавить точку входа
      </button>
      <div className="space-y-3">
        {eps.map((ep, idx) => (
          <div key={idx} className="bg-slate-950/50 border border-slate-800 rounded-lg p-3 space-y-2">
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="block text-[10px] text-slate-500 mb-0.5">ID</label>
                <input
                  type="text"
                  value={ep.id ?? ''}
                  onChange={e => update(idx, { id: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-[10px] text-slate-500 mb-0.5">Name</label>
                <input
                  type="text"
                  value={ep.name ?? ''}
                  onChange={e => update(idx, { name: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="block text-[10px] text-slate-500 mb-0.5">from_block</label>
                <select
                  value={ep.from_block ?? ''}
                  onChange={e => update(idx, { from_block: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
                >
                  <option value="">— Не задано —</option>
                  {blockIds.map(b => <option key={b} value={b}>{b}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-[10px] text-slate-500 mb-0.5">requires_input</label>
                <select
                  value={ep.requires_input ?? 'requirement'}
                  onChange={e => update(idx, { requires_input: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
                >
                  <option value="requirement">requirement</option>
                  <option value="youtrack_issue">youtrack_issue</option>
                  <option value="youtrack_issue_and_branch">youtrack_issue_and_branch</option>
                  <option value="youtrack_issue_and_mr">youtrack_issue_and_mr</option>
                  <option value="task_file">task_file</option>
                  <option value="none">none</option>
                </select>
              </div>
            </div>
            <div>
              <label className="block text-[10px] text-slate-500 mb-0.5">auto_detect</label>
              <input
                type="text"
                value={ep.auto_detect ?? ''}
                onChange={e => update(idx, { auto_detect: e.target.value || null })}
                placeholder="youtrack_subtasks | gitlab_branch | …"
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => remove(idx)}
                className="text-xs text-slate-500 hover:text-red-300 flex items-center gap-1"
              >
                <Trash2 className="w-3 h-3" /> Удалить
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function KnowledgeTab({ config, onChange }: { config: PipelineConfigDto; onChange: (p: Partial<PipelineConfigDto>) => void }) {
  const sources = config.knowledgeBase?.sources ?? []
  const update = (idx: number, patch: Partial<KnowledgeSourceConfigDto>) => {
    const next = [...sources]
    next[idx] = { ...next[idx], ...patch }
    onChange({ knowledgeBase: { sources: next } })
  }
  const add = () => onChange({
    knowledgeBase: { sources: [...sources, { type: 'files', path: '' }] },
  })
  const remove = (idx: number) => onChange({
    knowledgeBase: { sources: sources.filter((_, i) => i !== idx) },
  })
  return (
    <div className="space-y-3">
      <button onClick={add} className="text-xs text-blue-400 hover:text-blue-300 flex items-center gap-1">
        <Plus className="w-3 h-3" /> Добавить источник
      </button>
      <div className="space-y-2">
        {sources.map((s, idx) => (
          <div key={idx} className="bg-slate-950/50 border border-slate-800 rounded p-2 grid grid-cols-12 gap-2 items-end">
            <div className="col-span-2">
              <label className="block text-[10px] text-slate-500">type</label>
              <select
                value={s.type ?? 'files'}
                onChange={e => update(idx, { type: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="files">files</option>
                <option value="git">git</option>
              </select>
            </div>
            <div className="col-span-3">
              <label className="block text-[10px] text-slate-500">url</label>
              <input
                type="text" value={s.url ?? ''}
                onChange={e => update(idx, { url: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div className="col-span-3">
              <label className="block text-[10px] text-slate-500">path</label>
              <input
                type="text" value={s.path ?? ''}
                onChange={e => update(idx, { path: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div className="col-span-3">
              <label className="block text-[10px] text-slate-500">branch</label>
              <input
                type="text" value={s.branch ?? ''}
                onChange={e => update(idx, { branch: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div className="col-span-1 flex justify-end">
              <button onClick={() => remove(idx)} className="text-slate-500 hover:text-red-400 p-1">
                <Trash2 className="w-3 h-3" />
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function TriggersTab({ config, onChange }: { config: PipelineConfigDto; onChange: (p: Partial<PipelineConfigDto>) => void }) {
  const triggers = config.triggers ?? []
  const update = (idx: number, patch: Partial<TriggerConfigDto>) => {
    const next = [...triggers]
    next[idx] = { ...next[idx], ...patch }
    onChange({ triggers: next })
  }
  const add = () => onChange({
    triggers: [...triggers, { id: `trigger_${triggers.length + 1}`, type: 'webhook', enabled: true }],
  })
  const remove = (idx: number) => onChange({ triggers: triggers.filter((_, i) => i !== idx) })

  return (
    <div className="space-y-3">
      <button onClick={add} className="text-xs text-blue-400 hover:text-blue-300 flex items-center gap-1">
        <Plus className="w-3 h-3" /> Добавить триггер
      </button>
      <div className="space-y-2">
        {triggers.map((t, idx) => (
          <div key={idx} className="bg-slate-950/50 border border-slate-800 rounded p-3 grid grid-cols-2 gap-2">
            <div>
              <label className="block text-[10px] text-slate-500">id</label>
              <input value={t.id ?? ''} onChange={e => update(idx, { id: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500" />
            </div>
            <div>
              <label className="block text-[10px] text-slate-500">provider</label>
              <input value={t.provider ?? ''} onChange={e => update(idx, { provider: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500" />
            </div>
            <div>
              <label className="block text-[10px] text-slate-500">event</label>
              <input value={t.event ?? ''} onChange={e => update(idx, { event: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500" />
            </div>
            <div>
              <label className="block text-[10px] text-slate-500">entry_point_id</label>
              <input value={t.entry_point_id ?? ''} onChange={e => update(idx, { entry_point_id: e.target.value })}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500" />
            </div>
            <div className="col-span-2 flex items-center justify-between">
              <label className="flex items-center gap-1.5 text-xs text-slate-300">
                <input type="checkbox" checked={t.enabled !== false} onChange={e => update(idx, { enabled: e.target.checked })} className="accent-blue-500" />
                Включён
              </label>
              <button onClick={() => remove(idx)} className="text-xs text-slate-500 hover:text-red-300 flex items-center gap-1">
                <Trash2 className="w-3 h-3" /> Удалить
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function IntegrationsTab({ config }: { config: PipelineConfigDto }) {
  const integ = config.integrations ?? {}
  const rows: { key: string; value: string | null | undefined }[] = [
    { key: 'youtrack', value: integ.youtrack },
    { key: 'gitlab', value: integ.gitlab },
    { key: 'github', value: integ.github },
    { key: 'openrouter', value: integ.openrouter },
  ]
  return (
    <div className="space-y-3">
      <p className="text-xs text-slate-500">
        Интеграции конфигурируются глобально. Здесь — только ссылка из YAML на их name.
      </p>
      <div className="bg-slate-950/40 border border-slate-800 rounded-lg divide-y divide-slate-800">
        {rows.map(r => (
          <div key={r.key} className="flex items-center justify-between px-3 py-2">
            <span className="text-xs font-mono text-slate-300">{r.key}</span>
            <span className="text-xs font-mono text-slate-500">{r.value ?? '— не задано —'}</span>
          </div>
        ))}
      </div>
      <Link to="/system/integrations" className="text-xs text-blue-400 hover:text-blue-300">
        Управление интеграциями →
      </Link>
    </div>
  )
}

export default PipelineSettingsModal
