import { Loader2, CheckCircle, XCircle, SkipForward, Clock, AlertCircle, Copy, Check, Bell, ChevronDown, ChevronRight, Hand, Zap, BellRing } from 'lucide-react'
import { BlockStatus, BlockSnapshot, ApprovalMode } from '../types'
import { effectiveApprovalMode } from '../utils/configSnapshot'
import clsx from 'clsx'
import { useState, useCallback, useEffect } from 'react'

const APPROVAL_BADGE: Record<ApprovalMode, { label: string; Icon: React.ComponentType<{ className?: string }>; cls: string }> = {
  manual: { label: 'manual', Icon: Hand, cls: 'bg-amber-900/40 border-amber-800/60 text-amber-300' },
  auto: { label: 'auto', Icon: Zap, cls: 'bg-slate-800 border-slate-700 text-slate-400' },
  auto_notify: { label: 'auto+notify', Icon: BellRing, cls: 'bg-blue-950/40 border-blue-800/60 text-blue-300' },
}

function ApprovalBadge({ mode }: { mode: ApprovalMode }) {
  const cfg = APPROVAL_BADGE[mode]
  const Icon = cfg.Icon
  return (
    <span
      className={clsx('inline-flex items-center gap-1 text-[10px] font-mono uppercase tracking-wide px-1.5 py-0.5 rounded border', cfg.cls)}
      title={`approval_mode: ${mode}`}
    >
      <Icon className="w-2.5 h-2.5" />
      {cfg.label}
    </span>
  )
}

/** Live elapsed timer for running blocks */
function LiveDuration({ startedAt }: { startedAt: string }) {
  const [elapsed, setElapsed] = useState(() => Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000))

  useEffect(() => {
    const id = setInterval(() => {
      setElapsed(Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000))
    }, 1000)
    return () => clearInterval(id)
  }, [startedAt])

  const m = Math.floor(elapsed / 60)
  const s = elapsed % 60
  return <span className="font-mono text-xs text-blue-400">{m > 0 ? `${m}m ` : ''}{s}s</span>
}

interface Props {
  blockStatuses: BlockStatus[]
  /** When provided, an "Review" button is shown on awaiting_approval rows */
  onReviewApproval?: (blockId: string) => void
  /** Per-block config snapshots (approval_mode, enabled, condition) used for badges */
  snapshots?: Map<string, BlockSnapshot>
}

type StatusConfig = {
  label: string
  badgeClass: string
  rowClass?: string
  Icon: React.ComponentType<{ className?: string }>
  spin?: boolean
  pulse?: boolean
}

const STATUS_CONFIG: Record<BlockStatus['status'], StatusConfig> = {
  pending: {
    label: 'Ожидание',
    badgeClass: 'bg-slate-700/60 text-slate-400',
    Icon: Clock,
  },
  running: {
    label: 'Выполняется',
    badgeClass: 'bg-blue-900/50 text-blue-300',
    rowClass: 'bg-blue-950/20',
    Icon: Loader2,
    spin: true,
  },
  awaiting_approval: {
    label: 'Ожидает одобрения',
    badgeClass: 'bg-amber-900/50 text-amber-300',
    rowClass: 'bg-amber-950/20',
    Icon: AlertCircle,
    pulse: true,
  },
  complete: {
    label: 'Готово',
    badgeClass: 'bg-green-900/50 text-green-300',
    Icon: CheckCircle,
  },
  failed: {
    label: 'Ошибка',
    badgeClass: 'bg-red-900/50 text-red-300',
    rowClass: 'bg-red-950/20',
    Icon: XCircle,
  },
  skipped: {
    label: 'Пропущен',
    badgeClass: 'bg-amber-900/40 text-amber-400',
    Icon: SkipForward,
  },
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // clipboard API unavailable (e.g. non-https) — silently ignore
    }
  }, [text])

  return (
    <button
      type="button"
      onClick={handleCopy}
      title="Копировать JSON"
      className="ml-1.5 text-slate-600 hover:text-slate-400 transition-colors flex-shrink-0"
    >
      {copied ? <Check className="w-3 h-3 text-green-400" /> : <Copy className="w-3 h-3" />}
    </button>
  )
}

function OutputCell({ output }: { output?: Record<string, unknown> }) {
  const [expanded, setExpanded] = useState(false)

  if (!output) return <span className="text-slate-600">—</span>

  const keys = Object.keys(output)
  const preview = keys.slice(0, 2).join(', ')
  const jsonStr = JSON.stringify(output, null, 2)
  const ChevronIcon = expanded ? ChevronDown : ChevronRight

  return (
    <div>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => setExpanded(v => !v)}
          aria-expanded={expanded}
          aria-label={expanded ? 'Свернуть' : 'Развернуть'}
          className="flex items-center gap-1 text-xs text-slate-400 hover:text-blue-400 transition-colors group/toggle"
        >
          <ChevronIcon className="w-3.5 h-3.5 flex-shrink-0 text-slate-500 group-hover/toggle:text-blue-400 transition-colors" />
          <span className="font-mono">{expanded ? 'Collapse' : `{${preview}${keys.length > 2 ? ', ...' : '}'}}`}</span>
        </button>
        {/* Copy button always visible alongside the toggle */}
        <CopyButton text={jsonStr} />
      </div>
      {expanded && (
        <div className="mt-2 relative group/output">
          <pre className="text-xs text-slate-300 bg-slate-950 border border-slate-700 rounded-lg p-3 overflow-auto max-h-48 whitespace-pre-wrap pr-8">
            {jsonStr}
          </pre>
          {/* Reuse CopyButton for the expanded overlay — gives "copied" tick feedback */}
          <div className="absolute top-2 right-2 p-0.5 rounded bg-slate-800 opacity-0 group-hover/output:opacity-100 transition-opacity">
            <CopyButton text={jsonStr} />
          </div>
        </div>
      )}
    </div>
  )
}

export default function BlockProgressTable({ blockStatuses, onReviewApproval, snapshots }: Props) {
  if (blockStatuses.length === 0) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl">
        <div className="px-5 py-4 border-b border-slate-800">
          <h2 className="text-sm font-semibold text-slate-200">Прогресс блоков</h2>
        </div>
        <div className="px-5 py-10 text-center text-slate-600 text-sm">
          Блоки ещё не запущены.
        </div>
      </div>
    )
  }

  const completedCount = blockStatuses.filter(b => b.status === 'complete' || b.status === 'skipped').length
  const hasApprovalPending = blockStatuses.some(b => b.status === 'awaiting_approval')

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
      <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-200">Прогресс блоков</h2>
        <div className="flex items-center gap-3">
          {hasApprovalPending && onReviewApproval && (
            <span className="flex items-center gap-1 text-xs text-amber-400 animate-pulse">
              <Bell className="w-3.5 h-3.5" />
              Требуется одобрение
            </span>
          )}
          <span className="text-xs text-slate-500">
            {completedCount}/{blockStatuses.length} готово
          </span>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-800">
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide w-8">#</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Блок</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Статус</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Длительность</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Выход</th>
              {/* Actions column only rendered when there's an onReviewApproval handler */}
              {onReviewApproval && (
                <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Действия</th>
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-800/60">
            {blockStatuses.map((block, index) => {
              const cfg = STATUS_CONFIG[block.status]
              const Icon = cfg.Icon
              return (
                <tr
                  key={block.blockId}
                  className={clsx('transition-colors', cfg.rowClass)}
                >
                  <td className="px-5 py-3.5 text-slate-600 text-xs">{index + 1}</td>
                  <td className="px-5 py-3.5">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-mono text-slate-200 text-sm">{block.blockId}</span>
                      {(() => {
                        const snapshot = snapshots?.get(block.blockId)
                        const mode = effectiveApprovalMode(snapshot)
                        return (
                          <>
                            {mode && <ApprovalBadge mode={mode} />}
                            {snapshot?.enabled === false && (
                              <span
                                className="text-[10px] font-mono uppercase tracking-wide bg-slate-800 border border-slate-700 text-slate-500 px-1.5 py-0.5 rounded"
                                title="Блок отключён в конфигурации"
                              >
                                disabled
                              </span>
                            )}
                            {snapshot?.condition && (
                              <span
                                className="text-[10px] font-mono uppercase tracking-wide bg-slate-800/80 border border-slate-700 text-slate-400 px-1.5 py-0.5 rounded"
                                title={`condition: ${snapshot.condition}`}
                              >
                                cond
                              </span>
                            )}
                          </>
                        )
                      })()}
                    </div>
                  </td>
                  <td className="px-5 py-3.5">
                    <span className={clsx('inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium', cfg.badgeClass)}>
                      <Icon className={clsx('w-3.5 h-3.5', cfg.spin && 'animate-spin')} />
                      {cfg.label}
                      {cfg.pulse && (
                        <span className="relative flex h-1.5 w-1.5 ml-0.5">
                          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
                          <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-amber-400" />
                        </span>
                      )}
                    </span>
                  </td>
                  <td className="px-5 py-3.5 whitespace-nowrap">
                    {block.status === 'running' && block.startedAt ? (
                      <LiveDuration startedAt={block.startedAt} />
                    ) : (
                      <span className="text-xs text-slate-600">—</span>
                    )}
                  </td>
                  <td className="px-5 py-3.5 max-w-xs">
                    <OutputCell output={block.output} />
                  </td>
                  {onReviewApproval && (
                    <td className="px-5 py-3.5">
                      {block.status === 'awaiting_approval' && (
                        <button
                          type="button"
                          onClick={() => onReviewApproval(block.blockId)}
                          className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-amber-900/40 border border-amber-700/50 text-amber-300 hover:bg-amber-900/60 transition-colors"
                        >
                          <Bell className="w-3.5 h-3.5" />
                          Рассмотреть
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
