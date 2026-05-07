import { Plus, Trash2 } from 'lucide-react'
import { BlockRegistryEntry, OnFailConfigDto, PipelineConfigDto } from '../../../types'
import OutputsRefPicker from '../OutputsRefPicker'

interface Props {
  /** Current `verify.on_fail` value. `null`/`undefined` is treated as `action: fail`. */
  onFail: OnFailConfigDto | null | undefined
  /** Block IDs available as loopback targets (caller should pre-filter out the current block). */
  blockIds: string[]
  onChange: (next: OnFailConfigDto | null) => void
  /** Block currently being edited — used for `OutputsRefPicker` ancestor lookup. */
  currentBlockId: string
  /** Full pipeline config — required for `OutputsRefPicker` BFS over `depends_on`. */
  config: PipelineConfigDto
  /** Block registry indexed by `type` — required for `OutputsRefPicker` outputs lookup. */
  byType: Record<string, BlockRegistryEntry>
}

/**
 * Free-standing editor for `verify.on_fail.*` (action / target / max_iterations
 * / inject_context). Extracted from {@code VerifyForm} so the new section-based
 * SidePanel can render it inside `Conditions & Retry` while the rest of the
 * verify form (subject / checks / llm_check) stays in Essentials.
 */
export function OnFailEditor({
  onFail, blockIds, onChange, currentBlockId, config, byType,
}: Props) {
  const action = onFail?.action ?? 'fail'
  const update = (patch: Partial<OnFailConfigDto>) => onChange({ ...onFail, ...patch })

  const injectEntries: Array<[string, string]> = Object.entries(onFail?.inject_context ?? {})

  return (
    <div data-testid="on-fail-editor">
      <label className="block text-xs font-medium text-slate-300 mb-1">При провале</label>
      <select
        data-testid="verify-on-fail-action"
        value={action}
        onChange={e => update({ action: e.target.value })}
        className="bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        <option value="fail">fail</option>
        <option value="warn">warn</option>
        <option value="loopback">loopback</option>
      </select>
      {action === 'loopback' && (
        <div className="mt-2 pl-4 space-y-2 border-l border-slate-800">
          <div>
            <label className="block text-[10px] text-slate-400 mb-0.5">Target</label>
            <select
              data-testid="verify-on-fail-target"
              value={onFail?.target ?? ''}
              onChange={e => update({ target: e.target.value })}
              className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              <option value="">— Не задано —</option>
              {blockIds.map(i => <option key={i} value={i}>{i}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-[10px] text-slate-400 mb-0.5">Max iterations</label>
            <input
              type="number"
              value={onFail?.max_iterations ?? ''}
              placeholder="2"
              onChange={e => update({
                max_iterations: e.target.value === '' ? undefined : Number(e.target.value),
              })}
              className="w-20 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>

          {/* inject_context — key/value pairs, value is an OutputsRefPicker */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="block text-[10px] text-slate-400">Inject context</label>
              <button
                type="button"
                data-testid="verify-on-fail-inject-add"
                onClick={() => {
                  const next = { ...(onFail?.inject_context ?? {}) }
                  // Pick a unique placeholder key
                  let key = 'feedback'
                  let n = 1
                  while (key in next) key = `feedback_${++n}`
                  next[key] = ''
                  update({ inject_context: next })
                }}
                className="text-[10px] text-blue-400 hover:text-blue-300 flex items-center gap-1"
              >
                <Plus className="w-3 h-3" /> Добавить
              </button>
            </div>
            <div className="space-y-1.5">
              {injectEntries.map(([k, v], idx) => (
                <InjectRow
                  key={idx}
                  k={k}
                  v={v}
                  currentBlockId={currentBlockId}
                  config={config}
                  byType={byType}
                  testIdSuffix={String(idx)}
                  onChangeKey={(nextKey) => {
                    if (nextKey === k) return
                    const map = { ...(onFail?.inject_context ?? {}) }
                    delete map[k]
                    map[nextKey] = v
                    update({ inject_context: map })
                  }}
                  onChangeValue={(nextVal) => {
                    const map = { ...(onFail?.inject_context ?? {}) }
                    map[k] = nextVal
                    update({ inject_context: map })
                  }}
                  onRemove={() => {
                    const map = { ...(onFail?.inject_context ?? {}) }
                    delete map[k]
                    update({ inject_context: Object.keys(map).length === 0 ? undefined : map })
                  }}
                />
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function InjectRow({
  k, v, currentBlockId, config, byType, testIdSuffix,
  onChangeKey, onChangeValue, onRemove,
}: {
  k: string
  v: string
  currentBlockId: string
  config: PipelineConfigDto
  byType: Record<string, BlockRegistryEntry>
  testIdSuffix: string
  onChangeKey: (next: string) => void
  onChangeValue: (next: string) => void
  onRemove: () => void
}) {
  return (
    <div className="flex items-center gap-1.5">
      <input
        type="text"
        defaultValue={k}
        onBlur={e => onChangeKey(e.target.value)}
        placeholder="key"
        data-testid={`verify-on-fail-inject-key-${testIdSuffix}`}
        className="w-24 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
      />
      <div className="flex-1">
        <OutputsRefPicker
          value={v}
          onChange={onChangeValue}
          currentBlockId={currentBlockId}
          config={config}
          byType={byType}
          placeholder="$.block.field"
          testId={`outputs-ref-picker-feedback-${testIdSuffix}`}
          className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
        />
      </div>
      <button
        type="button"
        onClick={onRemove}
        className="p-1 text-slate-500 hover:text-red-400"
        data-testid={`verify-on-fail-inject-remove-${testIdSuffix}`}
      >
        <Trash2 className="w-3 h-3" />
      </button>
    </div>
  )
}

export default OnFailEditor
