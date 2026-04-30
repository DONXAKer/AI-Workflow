import { useEffect, useState } from 'react'
import { Trash2, X, AlertCircle } from 'lucide-react'
import { BlockConfigDto, BlockRegistryEntry, PipelineConfigDto, ValidationError, VerifyConfigDto } from '../../types'
import GenericBlockForm from './forms/GenericBlockForm'
import AgentWithToolsForm from './forms/AgentWithToolsForm'
import VerifyForm from './forms/VerifyForm'
import RawJsonFallback from './forms/RawJsonFallback'
import { HelpPopover, ExpressionHelpBody } from './HelpPopover'

interface Props {
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  config: PipelineConfigDto
  errors: ValidationError[]
  onClose: () => void
  onPatch: (patch: Partial<BlockConfigDto>) => void
  onRename: (oldId: string, newId: string) => boolean
  onDelete: () => void
}

export function SidePanel({
  block, registryEntry, config, errors, onClose, onPatch, onRename, onDelete,
}: Props) {
  const [idDraft, setIdDraft] = useState(block.id)
  const [renameError, setRenameError] = useState<string | null>(null)
  useEffect(() => { setIdDraft(block.id); setRenameError(null) }, [block.id])

  const meta = registryEntry?.metadata
  const otherBlockIds = (config.pipeline ?? []).map(b => b.id).filter(b => b !== block.id)

  const commitId = () => {
    if (idDraft === block.id) return
    if (!idDraft.trim()) {
      setRenameError('ID не может быть пустым')
      return
    }
    if ((config.pipeline ?? []).some(b => b.id === idDraft)) {
      setRenameError('Такой ID уже существует')
      return
    }
    if (!onRename(block.id, idDraft)) {
      setRenameError('Не удалось переименовать')
    } else {
      setRenameError(null)
    }
  }

  return (
    <div data-testid="side-panel" className="w-96 bg-slate-900 border-l border-slate-800 flex flex-col">
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800">
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

      <div className="flex-1 overflow-y-auto p-4 space-y-5">
        {errors.length > 0 && (
          <div className="bg-red-950/40 border border-red-800 rounded-lg p-2.5 space-y-1">
            <div className="flex items-center gap-1.5 text-xs font-medium text-red-200">
              <AlertCircle className="w-3.5 h-3.5" />
              Ошибки валидации ({errors.length})
            </div>
            <ul className="text-[10px] text-red-200 space-y-0.5 pl-4 list-disc">
              {errors.map((e, i) => <li key={i}><span className="font-mono">{e.code}</span>: {e.message}</li>)}
            </ul>
          </div>
        )}

        {/* Common fields */}
        <CommonFields block={block} otherBlockIds={otherBlockIds} onPatch={onPatch} />

        {/* Block-specific form */}
        <div className="border-t border-slate-800 pt-4">
          <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">
            Конфигурация блока
          </h3>
          <BlockForm
            block={block}
            registryEntry={registryEntry}
            config={config}
            onConfigChange={(cfg) => onPatch({ config: cfg })}
            onVerifyChange={(v) => onPatch({ verify: v })}
          />
        </div>

        {/* Agent overrides */}
        <AgentOverrides block={block} onPatch={onPatch} />
      </div>
    </div>
  )
}

function CommonFields({ block, otherBlockIds, onPatch }: {
  block: BlockConfigDto
  otherBlockIds: string[]
  onPatch: (p: Partial<BlockConfigDto>) => void
}) {
  return (
    <div className="space-y-3">
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
      <div>
        <label className="block text-xs font-medium text-slate-300 mb-1">depends_on</label>
        <DependsOnPicker
          value={block.depends_on ?? []}
          options={otherBlockIds}
          onChange={depends_on => onPatch({ depends_on })}
        />
      </div>
      <div>
        <label className="text-xs font-medium text-slate-300 mb-1 flex items-center gap-1.5">
          Condition
          <HelpPopover testId="block-condition" title="Синтаксис condition / on_failure">
            <ExpressionHelpBody />
          </HelpPopover>
        </label>
        <input
          type="text"
          value={block.condition ?? ''}
          onChange={e => onPatch({ condition: e.target.value || null })}
          data-testid="block-condition"
          placeholder="$.analysis.estimated_complexity != 'low'"
          className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 font-mono text-xs focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
        <p className="text-[10px] text-slate-500 mt-0.5">
          Если выражение falsy — блок будет пропущен.
        </p>
      </div>
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
    <div className="border-t border-slate-800 pt-4">
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

function BlockForm({ block, registryEntry, config, onConfigChange, onVerifyChange }: {
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  config: PipelineConfigDto
  onConfigChange: (cfg: Record<string, unknown>) => void
  onVerifyChange: (v: VerifyConfigDto) => void
}) {
  // Custom forms first
  if (block.block === 'agent_with_tools') {
    return <AgentWithToolsForm block={block} config={config} onChange={onConfigChange} />
  }
  if (block.block === 'verify') {
    return <VerifyForm block={block} config={config} onChange={onVerifyChange} />
  }
  // Generic from FieldSchema
  if (registryEntry?.metadata?.configFields?.length) {
    return (
      <GenericBlockForm
        block={block}
        fields={registryEntry.metadata.configFields}
        config={config}
        onChange={onConfigChange}
      />
    )
  }
  // Fallback raw JSON
  return <RawJsonFallback block={block} onChange={onConfigChange} />
}

export default SidePanel
