import { useEffect, useMemo, useState } from 'react'
import { Trash2, X, AlertCircle } from 'lucide-react'
import {
  BlockConfigDto, BlockRegistryEntry, FieldSchemaDto, OnFailureConfigDto,
  PipelineConfigDto, ValidationError, VerifyConfigDto,
} from '../../types'
import GenericBlockForm, { effectiveLevel } from './forms/GenericBlockForm'
import BlockForm from './forms/BlockForm'
import RawJsonFallback from './forms/RawJsonFallback'
import OnFailEditor from './forms/OnFailEditor'
import OutputsRefPicker from './OutputsRefPicker'
import { HelpPopover, ExpressionHelpBody } from './HelpPopover'
import PhaseSelector from './PhaseSelector'
import Section from './Section'
import { pathToSection, SectionKey } from './pathToSection'

interface Props {
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  config: PipelineConfigDto
  /** Block-registry index by `type`; needed for OutputsRefPicker outputs lookup. */
  byType: Record<string, BlockRegistryEntry>
  errors: ValidationError[]
  onClose: () => void
  onPatch: (patch: Partial<BlockConfigDto>) => void
  onRename: (oldId: string, newId: string) => boolean
  onDelete: () => void
}

const CI_BLOCK_TYPES = new Set(['gitlab_ci', 'github_actions'])
const VERIFY_BLOCK_TYPES = new Set(['verify', 'agent_verify'])

export function SidePanel({
  block, registryEntry, config, byType, errors, onClose, onPatch, onRename, onDelete,
}: Props) {
  const meta = registryEntry?.metadata
  const otherBlockIds = (config.pipeline ?? []).map(b => b.id).filter(b => b !== block.id)

  // Compute which section each error maps to. Used both for badges and forceOpen.
  const errorsBySection = useMemo(() => {
    const buckets: Record<SectionKey, ValidationError[]> = {
      essentials: [], conditions: [], advanced: [],
    }
    for (const e of errors) {
      const key = pathToSection(e.location, meta)
      buckets[key].push(e)
    }
    return buckets
    // Recompute when error list or block metadata changes
  }, [errors, meta])

  return (
    <div data-testid="side-panel" className="w-96 bg-slate-900 border-l border-slate-800 flex flex-col">
      <PinnedHeader
        block={block}
        meta={meta}
        errors={errors}
        onPatch={onPatch}
        onRename={onRename}
        onDelete={onDelete}
        onClose={onClose}
      />

      <div className="flex-1 overflow-y-auto px-4">
        <Section
          title="Основное"
          testId="section-essentials"
          defaultOpen
          forceOpen={errorsBySection.essentials.length > 0}
          badge={errorsBySection.essentials.length > 0 ? <ErrorDot /> : null}
        >
          <EssentialsSection
            block={block}
            registryEntry={registryEntry}
            config={config}
            otherBlockIds={otherBlockIds}
            onPatch={onPatch}
          />
        </Section>

        <Section
          title="Условия и retry"
          testId="section-conditions-retry"
          forceOpen={errorsBySection.conditions.length > 0}
          badge={errorsBySection.conditions.length > 0 ? <ErrorDot /> : null}
        >
          <ConditionsAndRetrySection
            block={block}
            config={config}
            byType={byType}
            otherBlockIds={otherBlockIds}
            onPatch={onPatch}
          />
        </Section>

        <Section
          title="Расширенное"
          testId="section-advanced"
          forceOpen={errorsBySection.advanced.length > 0}
          badge={errorsBySection.advanced.length > 0 ? <ErrorDot /> : null}
        >
          <AdvancedSection
            block={block}
            registryEntry={registryEntry}
            config={config}
            onPatch={onPatch}
          />
        </Section>
      </div>
    </div>
  )
}

function ErrorDot() {
  return (
    <span
      aria-hidden
      data-testid="section-error-dot"
      className="inline-block w-2 h-2 rounded-full bg-red-500 ml-1"
    />
  )
}

function PinnedHeader({
  block, meta, errors, onPatch, onRename, onDelete, onClose,
}: {
  block: BlockConfigDto
  meta: BlockRegistryEntry['metadata'] | undefined
  errors: ValidationError[]
  onPatch: (p: Partial<BlockConfigDto>) => void
  onRename: (oldId: string, newId: string) => boolean
  onDelete: () => void
  onClose: () => void
}) {
  const [idDraft, setIdDraft] = useState(block.id)
  const [renameError, setRenameError] = useState<string | null>(null)
  useEffect(() => { setIdDraft(block.id); setRenameError(null) }, [block.id])

  const commitId = () => {
    if (idDraft === block.id) return
    if (!idDraft.trim()) {
      setRenameError('ID не может быть пустым')
      return
    }
    if (!onRename(block.id, idDraft)) {
      setRenameError('Не удалось переименовать (возможно, такой ID уже существует)')
    } else {
      setRenameError(null)
    }
  }

  return (
    <div className="border-b border-slate-800">
      <div className="flex items-center justify-between px-4 py-3">
        <div className="flex-1 min-w-0">
          <input
            data-testid="block-id-input"
            type="text"
            value={idDraft}
            onChange={e => setIdDraft(e.target.value)}
            onBlur={commitId}
            onKeyDown={e => { if (e.key === 'Enter') commitId() }}
            className="w-full bg-transparent text-sm font-semibold text-slate-100 focus:outline-none focus:bg-slate-950 px-1 py-0.5 rounded"
          />
          <div className="text-[10px] font-mono text-slate-500 mt-0.5">
            {block.block} {meta?.label ? `· ${meta.label}` : ''}
          </div>
          {renameError && <div className="text-[10px] text-red-400 mt-0.5">{renameError}</div>}
        </div>
        <div className="flex items-center gap-1 ml-2">
          <button
            type="button"
            onClick={onDelete}
            data-testid="block-delete"
            className="p-1.5 rounded hover:bg-red-900/40 text-slate-500 hover:text-red-300"
            title="Удалить блок"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 rounded hover:bg-slate-800 text-slate-400 hover:text-slate-100"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Toggles + phase row, pinned under header */}
      <div className="px-4 pb-3 space-y-2.5">
        <div className="grid grid-cols-2 gap-3">
          <label className="flex items-center gap-2 text-xs text-slate-300">
            <input
              type="checkbox"
              checked={block.enabled !== false}
              onChange={e => onPatch({ enabled: e.target.checked })}
              data-testid="block-enabled"
              className="accent-blue-500"
            />
            Включён
          </label>
          <label className="flex items-center gap-2 text-xs text-slate-300">
            <input
              type="checkbox"
              checked={block.approval ?? false}
              onChange={e => onPatch({ approval: e.target.checked })}
              data-testid="block-approval"
              className="accent-amber-500"
            />
            Approval
          </label>
        </div>
        <PhaseSelector
          value={block.phase}
          defaultPhase={meta?.phase}
          onChange={(next) => onPatch({ phase: next })}
        />
      </div>

      {/* Validation errors banner — pinned, never inside the scroll area */}
      {errors.length > 0 && (
        <div className="mx-4 mb-3 bg-red-950/40 border border-red-800 rounded-lg p-2.5 space-y-1">
          <div className="flex items-center gap-1.5 text-xs font-medium text-red-200">
            <AlertCircle className="w-3.5 h-3.5" />
            Ошибки валидации ({errors.length})
          </div>
          <ul className="text-[10px] text-red-200 space-y-0.5 pl-4 list-disc">
            {errors.map((e, i) => <li key={i}><span className="font-mono">{e.code}</span>: {e.message}</li>)}
          </ul>
        </div>
      )}
    </div>
  )
}

function EssentialsSection({
  block, registryEntry, config, otherBlockIds, onPatch,
}: {
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  config: PipelineConfigDto
  otherBlockIds: string[]
  onPatch: (p: Partial<BlockConfigDto>) => void
}) {
  return (
    <div className="space-y-3">
      <div>
        <label className="block text-xs font-medium text-slate-300 mb-1">depends_on</label>
        <DependsOnPicker
          value={block.depends_on ?? []}
          options={otherBlockIds}
          onChange={depends_on => onPatch({ depends_on })}
        />
      </div>

      <div className="pt-2">
        <BlockForm
          block={block}
          registryEntry={registryEntry}
          config={config}
          levelFilter="essential"
          onConfigChange={(cfg) => onPatch({ config: cfg })}
          onVerifyChange={(v) => onPatch({ verify: v })}
        />
      </div>
    </div>
  )
}

function ConditionsAndRetrySection({
  block, config, byType, otherBlockIds, onPatch,
}: {
  block: BlockConfigDto
  config: PipelineConfigDto
  byType: Record<string, BlockRegistryEntry>
  otherBlockIds: string[]
  onPatch: (p: Partial<BlockConfigDto>) => void
}) {
  const isVerifyBlock = VERIFY_BLOCK_TYPES.has(block.block)
  const isCiBlock = CI_BLOCK_TYPES.has(block.block)

  return (
    <div className="space-y-4">
      {/* Always: condition */}
      <div>
        <label className="text-xs font-medium text-slate-300 mb-1 flex items-center gap-1.5">
          Condition
          <HelpPopover testId="block-condition" title="Синтаксис condition / on_failure">
            <ExpressionHelpBody />
          </HelpPopover>
        </label>
        <div data-testid="outputs-ref-picker-condition">
          <OutputsRefPicker
            value={block.condition ?? ''}
            onChange={v => onPatch({ condition: v || null })}
            currentBlockId={block.id}
            config={config}
            byType={byType}
            placeholder="$.analysis.estimated_complexity != 'low'"
            testId="block-condition"
          />
        </div>
        <p className="text-[10px] text-slate-500 mt-0.5">
          Если выражение falsy — блок будет пропущен.
        </p>
      </div>

      {/* Verify-block: on_fail */}
      {isVerifyBlock && (
        <div className="border-t border-slate-800 pt-3">
          <OnFailEditor
            onFail={block.verify?.on_fail}
            blockIds={otherBlockIds}
            currentBlockId={block.id}
            config={config}
            byType={byType}
            onChange={onFail => {
              const verify: VerifyConfigDto = { ...(block.verify ?? {}), on_fail: onFail }
              onPatch({ verify })
            }}
          />
        </div>
      )}

      {/* CI-block: on_failure */}
      {isCiBlock && (
        <div className="border-t border-slate-800 pt-3">
          <OnFailureEditor
            onFailure={block.on_failure}
            blockIds={otherBlockIds}
            currentBlockId={block.id}
            config={config}
            byType={byType}
            onChange={(next) => onPatch({ on_failure: next })}
          />
        </div>
      )}
    </div>
  )
}

function OnFailureEditor({
  onFailure, blockIds, currentBlockId, config, byType, onChange,
}: {
  onFailure: OnFailureConfigDto | null | undefined
  blockIds: string[]
  currentBlockId: string
  config: PipelineConfigDto
  byType: Record<string, BlockRegistryEntry>
  onChange: (next: OnFailureConfigDto | null) => void
}) {
  const action = onFailure?.action ?? 'fail'
  const update = (patch: Partial<OnFailureConfigDto>) =>
    onChange({ ...onFailure, ...patch })

  const failedStatuses = onFailure?.failed_statuses ?? []
  const injectEntries = Object.entries(onFailure?.inject_context ?? {})

  return (
    <div data-testid="on-failure-editor">
      <label className="block text-xs font-medium text-slate-300 mb-1">При провале CI</label>
      <select
        data-testid="on-failure-action"
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
              data-testid="on-failure-target"
              value={onFailure?.target ?? ''}
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
              data-testid="on-failure-max-iterations"
              value={onFailure?.max_iterations ?? ''}
              placeholder="2"
              onChange={e => update({
                max_iterations: e.target.value === '' ? undefined : Number(e.target.value),
              })}
              className="w-20 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-[10px] text-slate-400 mb-0.5">Failed statuses</label>
            <input
              type="text"
              data-testid="on-failure-failed-statuses"
              value={failedStatuses.join(', ')}
              placeholder="failure, failed, timeout"
              onChange={e => {
                const parts = e.target.value
                  .split(',')
                  .map(s => s.trim())
                  .filter(Boolean)
                update({ failed_statuses: parts.length === 0 ? undefined : parts })
              }}
              className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
            />
          </div>

          <div>
            <label className="block text-[10px] text-slate-400 mb-0.5">Inject context</label>
            <div className="space-y-1.5">
              {injectEntries.map(([k, v], idx) => (
                <div key={idx} className="flex items-center gap-1.5">
                  <input
                    type="text"
                    defaultValue={k}
                    onBlur={e => {
                      const nextKey = e.target.value
                      if (nextKey === k) return
                      const map = { ...(onFailure?.inject_context ?? {}) }
                      delete map[k]
                      map[nextKey] = v
                      update({ inject_context: map })
                    }}
                    placeholder="key"
                    className="w-24 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
                  />
                  <div className="flex-1">
                    <OutputsRefPicker
                      value={v}
                      onChange={(nextVal) => {
                        const map = { ...(onFailure?.inject_context ?? {}) }
                        map[k] = nextVal
                        update({ inject_context: map })
                      }}
                      currentBlockId={currentBlockId}
                      config={config}
                      byType={byType}
                      placeholder="$.block.field"
                      testId={`outputs-ref-picker-on-failure-feedback-${idx}`}
                      className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
                    />
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      const map = { ...(onFailure?.inject_context ?? {}) }
                      delete map[k]
                      update({ inject_context: Object.keys(map).length === 0 ? undefined : map })
                    }}
                    className="p-1 text-slate-500 hover:text-red-400"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ))}
              <button
                type="button"
                onClick={() => {
                  const map = { ...(onFailure?.inject_context ?? {}) }
                  let key = 'feedback'
                  let n = 1
                  while (key in map) key = `feedback_${++n}`
                  map[key] = ''
                  update({ inject_context: map })
                }}
                className="text-[10px] text-blue-400 hover:text-blue-300"
              >
                + Добавить
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function AdvancedSection({
  block, registryEntry, config, onPatch,
}: {
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  config: PipelineConfigDto
  onPatch: (p: Partial<BlockConfigDto>) => void
}) {
  const meta = registryEntry?.metadata
  // Compute whether the block has any advanced fields to render
  const advancedConfigFields = useMemo(() => {
    const fields: FieldSchemaDto[] = meta?.configFields ?? []
    return fields.filter(f => effectiveLevel(f) === 'advanced')
  }, [meta])

  // For agent_with_tools / verify the per-block form decides what's advanced.
  const hasCustomAdvancedForm =
    block.block === 'agent_with_tools' || block.block === 'verify' || block.block === 'agent_verify'

  const showRawJsonFallback = !meta || (
    (meta.configFields?.length ?? 0) === 0
    && (meta.outputs?.length ?? 0) === 0
    && !hasCustomAdvancedForm
  )

  return (
    <div className="space-y-5">
      {/* Block-specific advanced form (filtered to level=advanced) */}
      {hasCustomAdvancedForm
        ? (
          <BlockForm
            block={block}
            registryEntry={registryEntry}
            config={config}
            levelFilter="advanced"
            onConfigChange={(cfg) => onPatch({ config: cfg })}
            onVerifyChange={(v) => onPatch({ verify: v })}
          />
        )
        : advancedConfigFields.length > 0 && (
          <GenericBlockForm
            block={block}
            fields={advancedConfigFields}
            config={config}
            onChange={(cfg) => onPatch({ config: cfg })}
          />
        )}

      {/* Raw JSON fallback */}
      {showRawJsonFallback && (
        <RawJsonFallback block={block} onChange={cfg => onPatch({ config: cfg })} />
      )}

      {/* Agent overrides */}
      <AgentOverrides block={block} onPatch={onPatch} />
    </div>
  )
}

function DependsOnPicker({ value, options, onChange }: {
  value: string[]
  options: string[]
  onChange: (v: string[]) => void
}) {
  const set = new Set(value)
  return (
    <div data-testid="depends-on-picker" className="bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5 max-h-32 overflow-y-auto space-y-0.5">
      {options.length === 0 ? (
        <div className="text-[10px] text-slate-600 italic">Нет других блоков</div>
      ) : options.map(id => (
        <label key={id} className="flex items-center gap-2 text-xs text-slate-300 cursor-pointer hover:text-slate-100">
          <input
            type="checkbox"
            checked={set.has(id)}
            onChange={e => {
              if (e.target.checked) onChange([...value, id])
              else onChange(value.filter(x => x !== id))
            }}
            className="accent-blue-500"
          />
          <span className="font-mono">{id}</span>
        </label>
      ))}
    </div>
  )
}

function AgentOverrides({ block, onPatch }: {
  block: BlockConfigDto
  onPatch: (p: Partial<BlockConfigDto>) => void
}) {
  const agent = block.agent ?? {}
  const update = (k: string, v: unknown) => {
    const next = { ...agent }
    if (v === '' || v === undefined || v === null) {
      delete (next as Record<string, unknown>)[k]
    } else {
      ;(next as Record<string, unknown>)[k] = v
    }
    const empty = !next.model && next.temperature == null && !next.maxTokens && !next.systemPrompt
    onPatch({ agent: empty ? null : next })
  }
  return (
    <div data-testid="agent-overrides" className="border-t border-slate-800 pt-4">
      <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">
        Agent override
      </h3>
      <div className="space-y-2.5">
        <div>
          <label className="block text-[10px] text-slate-500 mb-0.5">Model</label>
          <input
            type="text"
            value={agent.model ?? ''}
            onChange={e => update('model', e.target.value)}
            placeholder="vendor/model-id"
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="block text-[10px] text-slate-500 mb-0.5">Temperature</label>
            <input
              type="number"
              step={0.1}
              value={agent.temperature ?? ''}
              onChange={e => update('temperature', e.target.value === '' ? null : Number(e.target.value))}
              className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-[10px] text-slate-500 mb-0.5">Max tokens</label>
            <input
              type="number"
              value={agent.maxTokens ?? ''}
              onChange={e => update('maxTokens', e.target.value === '' ? null : Number(e.target.value))}
              className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
        </div>
        <div>
          <label className="block text-[10px] text-slate-500 mb-0.5">System prompt</label>
          <textarea
            value={agent.systemPrompt ?? ''}
            onChange={e => update('systemPrompt', e.target.value)}
            rows={3}
            className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 resize-y"
          />
        </div>
      </div>
    </div>
  )
}

export default SidePanel
