import { Loader2, CheckCircle, XCircle, SkipForward, Clock, AlertCircle, Copy, Check, Bell, ChevronDown, ChevronRight, Hand, Zap, BellRing, RotateCcw } from 'lucide-react'
import { BlockStatus, BlockSnapshot, ApprovalMode, ToolCallEntry } from '../types'
import { effectiveApprovalMode } from '../utils/configSnapshot'
import { blockIdLabel } from '../utils/blockLabels'
import clsx from 'clsx'
import { useState, useCallback, useEffect, Fragment } from 'react'

const APPROVAL_BADGE: Record<ApprovalMode, { label: string; Icon: React.ComponentType<{ className?: string }>; cls: string }> = {
  manual: { label: 'Одобрение', Icon: Hand, cls: 'bg-amber-900/40 border-amber-800/60 text-amber-300' },
  auto: { label: 'Авто', Icon: Zap, cls: 'bg-slate-800 border-slate-700 text-slate-400' },
  auto_notify: { label: 'Авто + уведомление', Icon: BellRing, cls: 'bg-blue-950/40 border-blue-800/60 text-blue-300' },
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
  /** When provided, a relaunch button is shown on complete rows */
  onRelaunchFromBlock?: (blockId: string) => void
  /** Per-block config snapshots (approval_mode, enabled, condition) used for badges */
  snapshots?: Map<string, BlockSnapshot>
  /** Tool call audit entries — when provided, enables per-block iteration expansion */
  toolCalls?: ToolCallEntry[]
}

const TOOL_COLORS: Record<string, string> = {
  Read:  'text-blue-300 bg-blue-950/50 border-blue-800/60',
  Write: 'text-green-300 bg-green-950/50 border-green-800/60',
  Edit:  'text-amber-300 bg-amber-950/50 border-amber-800/60',
  Glob:  'text-purple-300 bg-purple-950/50 border-purple-800/60',
  Grep:  'text-purple-300 bg-purple-950/50 border-purple-800/60',
  Bash:  'text-orange-300 bg-orange-950/50 border-orange-800/60',
}

function summarizeInput(toolName: string, inputJson: string): string {
  try {
    const inp = JSON.parse(inputJson) as Record<string, unknown>
    if (toolName === 'Bash') {
      const cmd = String(inp['command'] ?? inp['cmd'] ?? '').trim()
      const first = cmd.split('\n')[0]
      return first.length > 70 ? first.slice(0, 70) + '…' : first
    }
    if (toolName === 'Grep') {
      const pat = String(inp['pattern'] ?? '')
      const inPath = inp['path'] ? ` @ ${String(inp['path'])}` : ''
      return (pat + inPath).slice(0, 70)
    }
    if (toolName === 'Glob') return String(inp['pattern'] ?? '')
    const fp = inp['file_path'] ?? inp['path'] ?? inp['file'] ?? ''
    if (fp) return String(fp)
    return JSON.stringify(inp).slice(0, 70)
  } catch {
    return inputJson.slice(0, 70)
  }
}

function IterationRow({ iteration, calls }: { iteration: number; calls: ToolCallEntry[] }) {
  const [open, setOpen] = useState(false)
  const hasError = calls.some(c => c.isError)
  const totalMs = calls.reduce((s, c) => s + c.durationMs, 0)

  return (
    <div className="border border-slate-800/60 rounded-lg overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        className="w-full flex items-center gap-2 px-3 py-1.5 text-left hover:bg-slate-800/40 transition-colors"
      >
        {open
          ? <ChevronDown className="w-3 h-3 text-slate-500 flex-shrink-0" />
          : <ChevronRight className="w-3 h-3 text-slate-500 flex-shrink-0" />}
        <span className="text-xs text-slate-400 font-mono">Итерация {iteration}</span>
        <span className="text-[10px] text-slate-600">
          {calls.length} {calls.length === 1 ? 'вызов' : calls.length < 5 ? 'вызова' : 'вызовов'}
        </span>
        <span className="text-[10px] text-slate-700 ml-auto">{totalMs}ms</span>
        {hasError && <span className="text-[10px] text-red-400">ошибка</span>}
      </button>
      {open && (
        <div className="border-t border-slate-800/60 divide-y divide-slate-800/40">
          {calls.map((c, i) => {
            const colorCls = TOOL_COLORS[c.toolName] ?? 'text-slate-400 bg-slate-800/40 border-slate-700'
            const summary = summarizeInput(c.toolName, c.inputJson)
            return (
              <div key={i} className="px-3 py-1.5 text-xs">
                <div className="flex items-center gap-2">
                  <span className={clsx('px-1.5 py-0.5 rounded border text-[10px] font-mono font-medium flex-shrink-0', colorCls)}>
                    {c.toolName}
                  </span>
                  <span className="font-mono text-slate-400 truncate flex-1 min-w-0">{summary}</span>
                  {c.isError
                    ? <XCircle className="w-3 h-3 text-red-400 flex-shrink-0" />
                    : <CheckCircle className="w-3 h-3 text-green-600/70 flex-shrink-0" />}
                  <span className="text-slate-600 text-[10px] flex-shrink-0">{c.durationMs}ms</span>
                </div>
                {c.isError && c.outputText && (
                  <pre className="mt-1 ml-7 text-[10px] text-red-300/80 bg-red-950/30 border border-red-900/40 rounded px-2 py-1 whitespace-pre-wrap break-all leading-relaxed max-h-32 overflow-auto">
                    {c.outputText}
                  </pre>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function IterationsPanel({ calls }: { calls: ToolCallEntry[] }) {
  const byIteration = new Map<number, ToolCallEntry[]>()
  for (const c of calls) {
    if (!byIteration.has(c.iteration)) byIteration.set(c.iteration, [])
    byIteration.get(c.iteration)!.push(c)
  }
  const iterations = [...byIteration.entries()].sort((a, b) => a[0] - b[0])

  return (
    <div className="space-y-1.5 px-4 py-3 bg-slate-950/60 border-t border-slate-800/60">
      {iterations.map(([iter, iterCalls]) => (
        <IterationRow key={iter} iteration={iter} calls={iterCalls} />
      ))}
    </div>
  )
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

/**
 * Detects the verify-block output shape: {@code { passed: boolean, issues: string[],
 * subject_block?, checks_failed?, recommendation?, iteration? }}. Used to inline a
 * human-readable failure banner so the user doesn't have to expand JSON to see why.
 */
function asVerifyOutput(output: Record<string, unknown> | undefined): {
  passed: boolean
  issues: string[]
  subject?: string
  checksFailed?: number
  checksPassed?: number
  recommendation?: string
  iteration?: number
} | null {
  if (!output) return null
  if (typeof output.passed !== 'boolean') return null
  if (!Array.isArray(output.issues)) return null
  const issues = (output.issues as unknown[]).filter((x): x is string => typeof x === 'string')
  return {
    passed: output.passed,
    issues,
    subject: typeof output.subject_block === 'string' ? output.subject_block : undefined,
    checksFailed: typeof output.checks_failed === 'number' ? output.checks_failed : undefined,
    checksPassed: typeof output.checks_passed === 'number' ? output.checks_passed : undefined,
    recommendation: typeof output.recommendation === 'string' && output.recommendation.trim()
      ? output.recommendation : undefined,
    iteration: typeof output.iteration === 'number' ? output.iteration : undefined,
  }
}

function VerifyOutputBanner({ verify }: { verify: NonNullable<ReturnType<typeof asVerifyOutput>> }) {
  if (verify.passed) {
    return (
      <div className="flex items-center gap-1.5 text-xs text-emerald-300 bg-emerald-950/30 border border-emerald-800/60 rounded-md px-2 py-1 mb-2">
        <CheckCircle className="w-3.5 h-3.5 flex-shrink-0" />
        <span>
          Verify OK
          {verify.subject && <span className="text-emerald-400/70"> · subject: <span className="font-mono">{verify.subject}</span></span>}
          {typeof verify.checksPassed === 'number' && (
            <span className="text-emerald-400/70"> · {verify.checksPassed} check{verify.checksPassed === 1 ? '' : 's'}</span>
          )}
        </span>
      </div>
    )
  }
  return (
    <div className="text-xs bg-red-950/30 border border-red-800/60 rounded-md px-2 py-1.5 mb-2 space-y-1">
      <div className="flex items-center gap-1.5 text-red-300 font-medium">
        <XCircle className="w-3.5 h-3.5 flex-shrink-0" />
        <span>
          Verify не прошёл
          {verify.subject && <span className="text-red-400/70 font-normal"> · subject: <span className="font-mono">{verify.subject}</span></span>}
          {typeof verify.iteration === 'number' && verify.iteration > 1 && (
            <span className="text-red-400/70 font-normal"> · итер. {verify.iteration}</span>
          )}
        </span>
      </div>
      {verify.issues.length > 0 && (
        <ul className="list-disc pl-5 space-y-0.5 text-red-200/90">
          {verify.issues.map((issue, i) => (
            <li key={i} className="break-words">{issue}</li>
          ))}
        </ul>
      )}
      {verify.recommendation && (
        <div className="text-[11px] text-red-300/80 italic mt-1 pl-5">
          {verify.recommendation}
        </div>
      )}
    </div>
  )
}

function OutputCell({ output }: { output?: Record<string, unknown> }) {
  const [expanded, setExpanded] = useState(false)

  if (!output) return <span className="text-slate-600">—</span>

  const keys = Object.keys(output)
  const preview = keys.slice(0, 2).join(', ')
  const jsonStr = JSON.stringify(output, null, 2)
  const ChevronIcon = expanded ? ChevronDown : ChevronRight
  const verify = asVerifyOutput(output)

  return (
    <div>
      {verify && <VerifyOutputBanner verify={verify} />}
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => setExpanded(v => !v)}
          aria-expanded={expanded}
          aria-label={expanded ? 'Свернуть' : 'Развернуть'}
          className="flex items-center gap-1 text-xs text-slate-400 hover:text-blue-400 transition-colors group/toggle"
        >
          <ChevronIcon className="w-3.5 h-3.5 flex-shrink-0 text-slate-500 group-hover/toggle:text-blue-400 transition-colors" />
          <span className="font-mono">{expanded ? 'Свернуть' : `{${preview}${keys.length > 2 ? ', ...' : '}'}}`}</span>
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

export default function BlockProgressTable({ blockStatuses, onReviewApproval, onRelaunchFromBlock, snapshots, toolCalls }: Props) {
  const [expandedBlocks, setExpandedBlocks] = useState<Set<string>>(new Set())

  const toggleBlock = useCallback((blockId: string) => {
    setExpandedBlocks(prev => {
      const next = new Set(prev)
      if (next.has(blockId)) next.delete(blockId)
      else next.add(blockId)
      return next
    })
  }, [])
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
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Вход</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Выход</th>
              {(onReviewApproval || onRelaunchFromBlock) && (
                <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Действия</th>
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-800/60">
            {blockStatuses.map((block, index) => {
              const cfg = STATUS_CONFIG[block.status]
              const Icon = cfg.Icon
              const blockCalls = toolCalls?.filter(tc => tc.blockId === block.blockId) ?? []
              const iterCount = new Set(blockCalls.map(tc => tc.iteration)).size
              const isExpanded = expandedBlocks.has(block.blockId)
              const hasActionsCol = !!(onReviewApproval || onRelaunchFromBlock)
              const colSpan = hasActionsCol ? 7 : 6

              return (
                <Fragment key={block.blockId}>
                  <tr className={clsx('transition-colors', cfg.rowClass)}>
                    <td className="px-5 py-3.5 text-slate-600 text-xs">{index + 1}</td>
                    <td className="px-5 py-3.5">
                      <div className="flex items-start gap-2 flex-wrap">
                        <div>
                          {(() => {
                            const label = blockIdLabel(block.blockId)
                            return (
                              <>
                                <div className="text-sm text-slate-100 font-medium leading-tight">{label}</div>
                                <div className="font-mono text-slate-500 text-xs mt-0.5">{block.blockId}</div>
                              </>
                            )
                          })()}
                        </div>
                        {(() => {
                          const snapshot = snapshots?.get(block.blockId)
                          const mode = effectiveApprovalMode(snapshot)
                          return (
                            <div className="flex items-center gap-1.5 flex-wrap mt-0.5">
                              {mode && <ApprovalBadge mode={mode} />}
                              {snapshot?.enabled === false && (
                                <span
                                  className="text-[10px] uppercase tracking-wide bg-slate-800 border border-slate-700 text-slate-500 px-1.5 py-0.5 rounded"
                                  title="Блок отключён в конфигурации"
                                >
                                  выкл
                                </span>
                              )}
                              {snapshot?.condition && (
                                <span
                                  className="text-[10px] uppercase tracking-wide bg-slate-800/80 border border-slate-700 text-slate-400 px-1.5 py-0.5 rounded"
                                  title={`Условие: ${snapshot.condition}`}
                                >
                                  условие
                                </span>
                              )}
                            </div>
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
                      {block.status === 'running' && block.progressDetail && (
                        <div className="mt-1 text-[11px] text-blue-400/80 font-mono">{block.progressDetail}</div>
                      )}
                      {blockCalls.length > 0 && (
                        <button
                          type="button"
                          onClick={() => toggleBlock(block.blockId)}
                          className="mt-1.5 flex items-center gap-1 text-[11px] text-slate-500 hover:text-slate-300 transition-colors"
                        >
                          {isExpanded
                            ? <ChevronDown className="w-3 h-3" />
                            : <ChevronRight className="w-3 h-3" />}
                          {iterCount} итер. · {blockCalls.length} вызовов
                        </button>
                      )}
                    </td>
                    <td className="px-5 py-3.5 whitespace-nowrap">
                      {block.status === 'running' && block.startedAt ? (
                        <LiveDuration startedAt={block.startedAt} />
                      ) : (
                        <span className="text-xs text-slate-600">—</span>
                      )}
                    </td>
                    <td className="px-5 py-3.5 max-w-xs">
                      <OutputCell output={block.input} />
                    </td>
                    <td className="px-5 py-3.5 max-w-xs">
                      <OutputCell output={block.output} />
                    </td>
                    {hasActionsCol && (
                      <td className="px-5 py-3.5">
                        {block.status === 'awaiting_approval' && onReviewApproval && (
                          <button
                            type="button"
                            onClick={() => onReviewApproval(block.blockId)}
                            className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-amber-900/40 border border-amber-700/50 text-amber-300 hover:bg-amber-900/60 transition-colors"
                          >
                            <Bell className="w-3.5 h-3.5" />
                            Рассмотреть
                          </button>
                        )}
                        {block.status === 'complete' && onRelaunchFromBlock && (
                          <button
                            type="button"
                            onClick={() => onRelaunchFromBlock(block.blockId)}
                            className="p-1.5 rounded-md text-slate-600 hover:text-blue-400 hover:bg-blue-950/40 transition-colors"
                            title="Перезапустить с этого блока"
                          >
                            <RotateCcw className="w-3.5 h-3.5" />
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                  {isExpanded && blockCalls.length > 0 && (
                    <tr>
                      <td colSpan={colSpan} className="p-0">
                        <IterationsPanel calls={blockCalls} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
