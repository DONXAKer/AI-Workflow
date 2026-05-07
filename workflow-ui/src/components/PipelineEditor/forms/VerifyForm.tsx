import { Plus, Trash2, ChevronDown, ChevronUp } from 'lucide-react'
import { useState } from 'react'
import { BlockConfigDto, FieldCheckConfigDto, PipelineConfigDto, VerifyConfigDto } from '../../../types'

interface Props {
  block: BlockConfigDto
  config: PipelineConfigDto
  onChange: (verify: VerifyConfigDto) => void
}

const RULES = ['min_length', 'max_length', 'min_items', 'max_items', 'one_of', 'not_empty', 'regex', 'gt', 'lt', 'equals']

/**
 * Custom form for the {@code verify} block. Edits {@code block.verify} (NOT
 * {@code block.config}) — that's where subject, checks, llm_check live.
 *
 * `on_fail` is no longer rendered here — it has been pulled into a freestanding
 * {@link OnFailEditor} so the section-aware SidePanel can place it inside
 * `Conditions & Retry` while keeping subject/checks/llm_check in Essentials.
 */
export function VerifyForm({ block, config, onChange }: Props) {
  const verify: VerifyConfigDto = block.verify ?? {}
  const [showLlm, setShowLlm] = useState(!!verify.llm_check)

  const blockIds = (config.pipeline ?? []).map(b => b.id).filter(Boolean)

  const update = (patch: Partial<VerifyConfigDto>) => onChange({ ...verify, ...patch })

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-xs font-medium text-slate-300 mb-1">Subject</label>
        <select
          data-testid="verify-subject"
          value={verify.subject ?? ''}
          onChange={e => update({ subject: e.target.value || undefined })}
          className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">— Не задано —</option>
          {blockIds.filter(i => i !== block.id).map(i => (
            <option key={i} value={i}>{i}</option>
          ))}
        </select>
        <p className="text-[10px] text-slate-500 mt-1">ID блока, чей output проверяется.</p>
      </div>

      {/* Checks repeater */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="text-xs font-medium text-slate-300">Структурные проверки</label>
          <button
            type="button"
            data-testid="verify-add-check"
            onClick={() => update({
              checks: [...(verify.checks ?? []), { field: '', rule: 'not_empty', value: '' }],
            })}
            className="text-xs text-blue-400 hover:text-blue-300 flex items-center gap-1"
          >
            <Plus className="w-3 h-3" /> Добавить
          </button>
        </div>
        <div className="space-y-2">
          {(verify.checks ?? []).map((check, idx) => (
            <CheckRow
              key={idx}
              check={check}
              onChange={(c) => {
                const next = [...(verify.checks ?? [])]
                next[idx] = c
                update({ checks: next })
              }}
              onRemove={() => {
                update({ checks: (verify.checks ?? []).filter((_, i) => i !== idx) })
              }}
            />
          ))}
        </div>
      </div>

      {/* LLM check (collapsible) */}
      <div>
        <button
          type="button"
          onClick={() => setShowLlm(s => !s)}
          className="flex items-center gap-1.5 text-xs font-medium text-slate-300 hover:text-slate-100"
        >
          {showLlm ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
          LLM-проверка
        </button>
        {showLlm && (
          <div className="mt-2 pl-4 space-y-2 border-l border-slate-800">
            <label className="flex items-center gap-2 text-xs text-slate-300">
              <input
                type="checkbox"
                checked={verify.llm_check?.enabled ?? false}
                onChange={e => update({
                  llm_check: { ...(verify.llm_check ?? {}), enabled: e.target.checked },
                })}
                className="accent-blue-500"
              />
              Включить
            </label>
            <div>
              <label className="block text-[10px] text-slate-400 mb-0.5">Промпт</label>
              <textarea
                value={verify.llm_check?.prompt ?? ''}
                onChange={e => update({
                  llm_check: { ...(verify.llm_check ?? {}), prompt: e.target.value },
                })}
                rows={3}
                className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
              />
            </div>
            <div>
              <label className="block text-[10px] text-slate-400 mb-0.5">Min score</label>
              <input
                type="number"
                step={0.5}
                value={verify.llm_check?.minScore ?? ''}
                placeholder="7"
                onChange={e => update({
                  llm_check: {
                    ...(verify.llm_check ?? {}),
                    minScore: e.target.value === '' ? undefined : Number(e.target.value),
                  },
                })}
                className="w-24 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function CheckRow({ check, onChange, onRemove }: {
  check: FieldCheckConfigDto
  onChange: (c: FieldCheckConfigDto) => void
  onRemove: () => void
}) {
  return (
    <div data-testid="verify-check-row" className="flex items-center gap-1.5 bg-slate-950/40 border border-slate-800 rounded px-2 py-1.5">
      <input
        type="text"
        placeholder="field"
        value={check.field ?? ''}
        onChange={e => onChange({ ...check, field: e.target.value })}
        className="flex-1 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
      />
      <select
        value={check.rule ?? ''}
        onChange={e => onChange({ ...check, rule: e.target.value })}
        className="bg-slate-950 border border-slate-700 rounded px-1.5 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        {RULES.map(r => <option key={r} value={r}>{r}</option>)}
      </select>
      <input
        type="text"
        placeholder="value"
        value={check.value === undefined ? '' : String(check.value)}
        onChange={e => {
          const raw = e.target.value
          let v: unknown = raw
          if (raw === 'true') v = true
          else if (raw === 'false') v = false
          else if (raw !== '' && !isNaN(Number(raw))) v = Number(raw)
          onChange({ ...check, value: v })
        }}
        className="w-24 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
      />
      <button type="button" onClick={onRemove} className="p-1 text-slate-500 hover:text-red-400">
        <Trash2 className="w-3 h-3" />
      </button>
    </div>
  )
}

export default VerifyForm
