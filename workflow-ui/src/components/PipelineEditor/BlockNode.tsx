import { memo } from 'react'
import { Handle, NodeProps, Position } from '@xyflow/react'
import { AlertCircle, Ban, Plus, Sparkles, ShieldCheck, Wrench, Globe, FileInput, Cog } from 'lucide-react'
import clsx from 'clsx'
import { BlockNode as BlockNodeT } from './types'

const CATEGORY_ICON: Record<string, React.ComponentType<{ className?: string }>> = {
  input: FileInput,
  agent: Sparkles,
  verify: ShieldCheck,
  ci: Wrench,
  infra: Cog,
  output: Globe,
  general: Cog,
}

function BlockNodeImpl(props: NodeProps<BlockNodeT>) {
  const { data } = props
  const errors = data.errors ?? []
  const Icon = CATEGORY_ICON[(data as unknown as { category?: string }).category ?? 'general'] ?? Cog

  return (
    <div
      data-testid={`block-node-${data.blockId}`}
      data-block-id={data.blockId}
      className={clsx(
        'group relative bg-slate-900 border rounded-lg px-3 py-2 min-w-[180px] max-w-[260px] text-left shadow-sm transition-colors',
        data.selected ? 'border-blue-500 ring-2 ring-blue-500/40' : 'border-slate-700',
        errors.length > 0 && 'border-red-600 ring-2 ring-red-600/30',
        data.disabled && 'opacity-50',
      )}
    >
      {/* Source / target handles for react-flow drag-edge */}
      <Handle type="target" position={Position.Top} className="!bg-slate-500 !w-2 !h-2" />
      <Handle type="source" position={Position.Bottom} className="!bg-slate-500 !w-2 !h-2" />

      {/* Header row */}
      <div className="flex items-start gap-2">
        <Icon className="w-3.5 h-3.5 text-slate-400 mt-0.5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <div className="text-sm font-semibold text-slate-100 truncate" title={data.blockId}>
            {data.blockId}
          </div>
          <div className="text-[10px] font-mono text-slate-500 truncate" title={data.blockType}>
            {data.blockType}
          </div>
        </div>
      </div>

      {/* Badges row */}
      <div className="flex items-center gap-1 mt-1.5 flex-wrap">
        {data.entryPointId && (
          <span
            className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-900/60 text-emerald-300 font-mono"
            title={`Entry point: ${data.entryPointId}`}
          >
            entry: {data.entryPointId}
          </span>
        )}
        {data.hasCondition && (
          <Ban className="w-3 h-3 text-amber-400" aria-label="condition" />
        )}
        {errors.length > 0 && (
          <span
            data-testid={`block-error-${data.blockId}`}
            className="flex items-center gap-0.5 text-[10px] px-1.5 py-0.5 rounded bg-red-900/60 text-red-200"
            title={errors.map(e => `${e.code}: ${e.message}`).join('\n')}
          >
            <AlertCircle className="w-3 h-3" />
            {errors.length}
          </span>
        )}
      </div>

      {/* Add-after button — visible on hover */}
      <button
        type="button"
        data-testid={`block-add-after-${data.blockId}`}
        onClick={(e) => {
          e.stopPropagation()
          // Canvas listens for "block-add-after" CustomEvent and shows a popover.
          window.dispatchEvent(new CustomEvent('pipeline-editor:add-after', {
            detail: { afterBlockId: data.blockId },
          }))
        }}
        className="absolute -bottom-3 left-1/2 -translate-x-1/2 w-6 h-6 rounded-full bg-blue-600 hover:bg-blue-500 text-white shadow flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity"
        title="Добавить блок после этого"
      >
        <Plus className="w-3.5 h-3.5" />
      </button>
    </div>
  )
}

export const BlockNode = memo(BlockNodeImpl)
export default BlockNode
