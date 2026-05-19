import { Check, Loader2, Hand, XCircle, Circle, SkipForward, Cloud, Shield, AlertTriangle } from 'lucide-react'
import clsx from 'clsx'
import { BlockSnapshot, BlockStatus, RunStatus } from '../types'
import { blockIdLabel } from '../utils/blockLabels'

interface Props {
  blockSnapshots: Map<string, BlockSnapshot>
  blockStatuses: BlockStatus[]
  currentBlock: string | null
  runStatus: RunStatus
  pipelineName?: string | null
}

/**
 * Heuristic: warn when the selected pipeline doesn't match the task.md flags.
 * task_md_input emits `heuristics: string[]` and a few boolean fields (is_docs,
 * is_greenfield, is_bugfix-ish). Pipeline filename hints at expected category.
 * Returns a short user-facing reason or null when no mismatch detected.
 */
function detectTaskPipelineMismatch(
  pipelineName: string | null | undefined,
  blockStatuses: BlockStatus[],
): string | null {
  if (!pipelineName) return null
  const taskMd = blockStatuses.find(b => b.blockId === 'task_md')
  const out = taskMd?.output as Record<string, unknown> | undefined
  if (!out) return null
  const heuristics = new Set<string>(Array.isArray(out.heuristics) ? out.heuristics as string[] : [])
  const isDocs = out.is_docs === true || heuristics.has('is_docs')
  const isGreenfield = heuristics.has('is_greenfield')
  const lower = pipelineName.toLowerCase()

  if (lower.includes('docs') && !isDocs) {
    return 'Pipeline для документации, но task.md не помечен как docs — `code-only` задача проедет docs-флоу и может выдать только markdown без правки кода.'
  }
  if (lower.includes('bugfix') && isGreenfield) {
    return 'Pipeline для багфикса, но task.md помечен как greenfield (новая функциональность) — bugfix-аналитик намеренно ограничивает scope и может отвергнуть задачу.'
  }
  if ((lower.includes('refactor') || lower === 'refactor.yaml') && isGreenfield) {
    return 'Refactor-pipeline на greenfield task — refactor ожидает существующий код для улучшения, не новой реализации.'
  }
  if ((lower.includes('write-tests') || lower.includes('write_tests')) && isDocs) {
    return 'Pipeline для генерации тестов, но task.md помечен как docs.'
  }
  return null
}

type Visual =
  | 'done'      // block finished successfully (latest iter complete/reused)
  | 'running'   // run is RUNNING and this is currentBlock
  | 'awaiting'  // run is PAUSED_FOR_APPROVAL and this is currentBlock
  | 'failed'    // latest iter failed
  | 'skipped'   // skipped by condition / approval skip
  | 'pending'   // not started yet

interface BlockState {
  visual: Visual
  iterations: number
  escalationStep?: 'cloud' | 'cloud-tier' | 'human' | undefined
}

function deriveBlockState(
  blockId: string,
  blockStatuses: BlockStatus[],
  currentBlock: string | null,
  runStatus: RunStatus,
): BlockState {
  const rows = blockStatuses.filter(b => b.blockId === blockId)
  const latest = rows[rows.length - 1]
  const isCurrent = currentBlock === blockId
  const out = latest?.output as Record<string, unknown> | undefined
  const escalationStep = (out?.escalation_step as BlockState['escalationStep']) ?? undefined

  let visual: Visual = 'pending'
  if (latest?.status === 'failed') visual = 'failed'
  else if (isCurrent && runStatus === 'PAUSED_FOR_APPROVAL') visual = 'awaiting'
  else if (isCurrent && runStatus === 'RUNNING') visual = 'running'
  else if (latest?.status === 'complete' || latest?.status === 'reused') visual = 'done'
  else if (latest?.status === 'skipped' || (out && out._skipped === true)) visual = 'skipped'
  else if (latest?.status === 'running') visual = 'running'
  else if (latest?.status === 'awaiting_approval') visual = 'awaiting'

  return { visual, iterations: rows.length, escalationStep }
}

const VISUAL_STYLES: Record<Visual, { cls: string; Icon: typeof Check }> = {
  done:     { cls: 'bg-emerald-900/30 border-emerald-700/50 text-emerald-300',  Icon: Check },
  running:  { cls: 'bg-blue-900/40 border-blue-600/70 text-blue-200',          Icon: Loader2 },
  awaiting: { cls: 'bg-amber-900/40 border-amber-700/60 text-amber-300',       Icon: Hand },
  failed:   { cls: 'bg-red-900/40 border-red-700/60 text-red-300',             Icon: XCircle },
  skipped:  { cls: 'bg-slate-800/40 border-slate-700/40 text-slate-500 line-through decoration-slate-600', Icon: SkipForward },
  pending:  { cls: 'bg-slate-900/30 border-slate-800/60 text-slate-500',       Icon: Circle },
}

function scrollToBlock(blockId: string): void {
  const el = document.getElementById(`block-row-${blockId}`)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

export default function PipelineOverview({ blockSnapshots, blockStatuses, currentBlock, runStatus, pipelineName }: Props) {
  const all = Array.from(blockSnapshots.values())
  if (all.length === 0) return null
  const states = all.map(s => deriveBlockState(s.id, blockStatuses, currentBlock, runStatus))
  const done = states.filter(s => s.visual === 'done').length
  const total = all.length
  const mismatch = detectTaskPipelineMismatch(pipelineName, blockStatuses)

  return (
    <div className="space-y-1.5">
      {mismatch && (
        <div className="flex items-start gap-1.5 px-2 py-1 rounded-md bg-amber-950/40 border border-amber-800/60 text-amber-200 text-xs leading-snug">
          <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0 text-amber-400" />
          <span><span className="font-semibold uppercase tracking-wide text-[10px] mr-1.5">Mismatch</span>{mismatch}</span>
        </div>
      )}
      <div className="flex items-start gap-3 flex-wrap py-1.5">
        <div className="flex items-center gap-1.5 text-xs text-slate-400 shrink-0 font-medium">
          <span className="uppercase tracking-wide text-[10px] text-slate-500">Пайплайн</span>
          <span className="text-slate-200 font-mono">{done}/{total}</span>
          <span className="text-slate-600">блоков</span>
        </div>
        <div className="flex items-center gap-1.5 flex-wrap min-w-0">
          {all.map((snap, idx) => {
          const state = states[idx]
          const { cls, Icon } = VISUAL_STYLES[state.visual]
          const isCurrent = currentBlock === snap.id && (runStatus === 'RUNNING' || runStatus === 'PAUSED_FOR_APPROVAL')
          const Escalation = state.escalationStep === 'human' ? Shield
            : (state.escalationStep === 'cloud' || state.escalationStep === 'cloud-tier') ? Cloud
            : null
          return (
            <button
              type="button"
              key={snap.id}
              onClick={() => scrollToBlock(snap.id)}
              title={`${snap.id} (${snap.block})${state.iterations > 1 ? ` ×${state.iterations}` : ''}`}
              className={clsx(
                'inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-xs leading-none transition-colors',
                'hover:brightness-125 cursor-pointer',
                cls,
                isCurrent && 'ring-2 ring-blue-500/70 shadow-sm shadow-blue-500/30',
                state.visual === 'running' && 'animate-pulse',
              )}
            >
              <Icon className={clsx('w-3 h-3 shrink-0', state.visual === 'running' && 'animate-spin')} />
              <span className="font-medium">{blockIdLabel(snap.id)}</span>
              {state.iterations > 1 && (
                <span className="font-mono text-[10px] opacity-80">×{state.iterations}</span>
              )}
              {Escalation && (
                <Escalation className="w-3 h-3 ml-0.5 text-fuchsia-300/90 shrink-0" />
              )}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
