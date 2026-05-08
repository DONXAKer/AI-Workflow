import { Loader2, CheckCircle, XCircle, SkipForward, Clock, AlertCircle, Copy, Check, Bell, ChevronDown, ChevronRight, Hand, Zap, BellRing, RotateCcw, Globe, Terminal } from 'lucide-react'
import { BlockStatus, BlockSnapshot, ApprovalMode, ToolCallEntry, LlmCallEntry, LlmProvider } from '../types'
import { effectiveApprovalMode } from '../utils/configSnapshot'
import { blockIdLabel } from '../utils/blockLabels'
import { getBlockView, BlockViewSpec, FieldSpec } from '../blockViews/index'
import clsx from 'clsx'
import { useState, useCallback, useEffect, Fragment, useMemo } from 'react'

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

function fmtDuration(ms: number): string {
  const s = Math.floor(ms / 1000)
  const m = Math.floor(s / 60)
  const h = Math.floor(m / 60)
  if (h > 0) return `${h}h ${m % 60}m`
  if (m > 0) return `${m}m ${s % 60}s`
  return `${s}s`
}

function fmtDurationCompact(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return fmtDuration(ms)
}

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
  onReviewApproval?: (blockId: string) => void
  onRelaunchFromBlock?: (blockId: string) => void
  snapshots?: Map<string, BlockSnapshot>
  toolCalls?: ToolCallEntry[]
  llmCalls?: LlmCallEntry[]
  runId?: string
}

export const TOOL_COLORS: Record<string, string> = {
  Read:  'text-blue-300 bg-blue-950/50 border-blue-800/60',
  Write: 'text-green-300 bg-green-950/50 border-green-800/60',
  Edit:  'text-amber-300 bg-amber-950/50 border-amber-800/60',
  Glob:  'text-purple-300 bg-purple-950/50 border-purple-800/60',
  Grep:  'text-purple-300 bg-purple-950/50 border-purple-800/60',
  Bash:  'text-orange-300 bg-orange-950/50 border-orange-800/60',
}

const PROVIDER_BADGE: Record<LlmProvider, { label: string; Icon: React.ComponentType<{ className?: string }>; cls: string; tooltip: string }> = {
  OPENROUTER: {
    label: 'OpenRouter',
    Icon: Globe,
    cls: 'bg-emerald-950/50 border-emerald-800/50 text-emerald-300',
    tooltip: 'Платный API через OpenRouter',
  },
  CLAUDE_CODE_CLI: {
    label: 'Claude CLI',
    Icon: Terminal,
    cls: 'bg-orange-950/50 border-orange-800/50 text-orange-300',
    tooltip: 'Локальный claude -p, ваша Max-подписка',
  },
}

export function ProviderBadge({ provider }: { provider?: LlmProvider }) {
  if (!provider) return null
  const cfg = PROVIDER_BADGE[provider]
  if (!cfg) return null
  const Icon = cfg.Icon
  return (
    <span
      className={clsx('inline-flex items-center gap-1 text-[10px] font-mono px-1.5 py-0.5 rounded border flex-shrink-0', cfg.cls)}
      title={cfg.tooltip}
    >
      <Icon className="w-2.5 h-2.5" />
      {cfg.label}
    </span>
  )
}

export function ToolCountChips({ calls }: { calls: ToolCallEntry[] }) {
  if (calls.length === 0) return <span className="text-slate-700 text-[10px]">—</span>
  const counts = new Map<string, { total: number; errors: number }>()
  for (const c of calls) {
    const cur = counts.get(c.toolName) ?? { total: 0, errors: 0 }
    cur.total += 1
    if (c.isError) cur.errors += 1
    counts.set(c.toolName, cur)
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1].total - a[1].total).slice(0, 5)
  return (
    <span className="inline-flex items-center gap-1 flex-wrap">
      {sorted.map(([tool, { total, errors }]) => {
        const baseCls = TOOL_COLORS[tool] ?? 'text-slate-400 bg-slate-800/40 border-slate-700'
        return (
          <span
            key={tool}
            className={clsx('px-1.5 py-0.5 rounded border text-[10px] font-mono leading-none', baseCls)}
            title={errors > 0 ? `${tool}: ${total} вызовов, ${errors} ошибок` : `${tool}: ${total} вызовов`}
          >
            {total}×{tool}{errors > 0 && <span className="text-red-400 ml-0.5">!</span>}
          </span>
        )
      })}
    </span>
  )
}

const BAD_FINISH = new Set(['MAX_ITERATIONS', 'BUDGET_EXCEEDED', 'ERROR', 'LENGTH', 'MAX_TOKENS'])

export function FinishReasonChip({ reason }: { reason?: string }) {
  if (!reason) return null
  const r = reason.toUpperCase()
  let cls = 'bg-slate-800 border-slate-700 text-slate-400'
  if (r === 'STOP' || r === 'END_TURN') cls = 'bg-green-950/40 border-green-800/50 text-green-400'
  else if (r === 'TOOL_CALLS') cls = 'bg-blue-950/40 border-blue-800/50 text-blue-400'
  else if (r === 'LENGTH' || r === 'MAX_TOKENS') cls = 'bg-amber-950/40 border-amber-800/50 text-amber-400'
  else if (BAD_FINISH.has(r)) cls = 'bg-red-950/40 border-red-800/50 text-red-400'
  return (
    <span
      className={clsx('inline-flex items-center text-[10px] font-mono px-1.5 py-0.5 rounded border flex-shrink-0', cls)}
      title={`finish_reason: ${reason}`}
    >
      {r}
    </span>
  )
}

export function summarizeInput(toolName: string, inputJson: string): string {
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

function IterationTableRow({ iteration, calls, llmCall }: { iteration: number; calls: ToolCallEntry[]; llmCall?: LlmCallEntry }) {
  const [open, setOpen] = useState(false)
  const errorCalls = calls.filter(c => c.isError)
  const okCalls = calls.filter(c => !c.isError)
  const errorCount = errorCalls.length
  const hasError = errorCount > 0
  const totalMs = calls.reduce((s, c) => s + c.durationMs, 0)
  const shortModel = llmCall ? llmCall.model.replace(/^[^/]+\//, '').replace(/^claude-/, '') : null
  const sortedCalls = [...errorCalls, ...okCalls]

  return (
    <Fragment>
      <tr
        className={clsx(
          'border-b border-slate-800/40 cursor-pointer transition-colors',
          open ? 'bg-slate-800/30' : 'hover:bg-slate-800/20',
          hasError && !open && 'bg-red-950/10',
        )}
        onClick={() => setOpen(v => !v)}
      >
        <td className="px-2 py-1.5 text-center w-7">
          {calls.length > 0
            ? (open
              ? <ChevronDown className="w-3 h-3 text-slate-400 inline-block" />
              : <ChevronRight className="w-3 h-3 text-slate-500 inline-block" />)
            : null}
        </td>
        <td className="px-2 py-1.5 text-xs text-slate-400 font-mono w-12 text-center">{iteration}</td>
        <td className="px-2 py-1.5">
          {shortModel && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-violet-950/50 border border-violet-800/50 text-violet-300">
              {shortModel}
            </span>
          )}
        </td>
        <td className="px-2 py-1.5 text-right text-[10px] text-slate-500 font-mono whitespace-nowrap">
          {llmCall && llmCall.tokensIn > 0
            ? <>{(llmCall.tokensIn / 1000).toFixed(0)}K↑ {(llmCall.tokensOut / 1000).toFixed(0)}K↓</>
            : <span className="text-slate-700">—</span>}
        </td>
        <td className="px-2 py-1.5 text-right text-[10px] text-slate-500 font-mono whitespace-nowrap">
          {llmCall && llmCall.costUsd > 0
            ? `$${llmCall.costUsd.toFixed(4)}`
            : <span className="text-slate-700">—</span>}
        </td>
        <td className="px-2 py-1.5">
          <div className="flex items-center gap-1.5 flex-wrap">
            <ToolCountChips calls={calls} />
            {errorCount > 0 && (
              <span className="text-[10px] font-mono text-red-400 bg-red-950/40 border border-red-800/50 rounded px-1.5 py-0.5 leading-none">
                {errorCount} err
              </span>
            )}
          </div>
        </td>
        <td className="px-2 py-1.5 w-32"><FinishReasonChip reason={llmCall?.finishReason} /></td>
        <td className="px-2 py-1.5 text-right text-[10px] text-slate-500 font-mono whitespace-nowrap">{fmtDurationCompact(totalMs)}</td>
      </tr>
      {open && calls.length > 0 && (
        <tr className="border-b border-slate-800/40 bg-slate-950/40">
          <td colSpan={8} className="px-6 py-2">
            <div className="divide-y divide-slate-800/40">
              {sortedCalls.map((c, i) => {
                const colorCls = TOOL_COLORS[c.toolName] ?? 'text-slate-400 bg-slate-800/40 border-slate-700'
                const summary = summarizeInput(c.toolName, c.inputJson)
                return (
                  <div key={i} className={clsx('py-1.5 text-xs', c.isError && 'bg-red-950/10 rounded px-1 -mx-1 my-0.5')}>
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
          </td>
        </tr>
      )}
    </Fragment>
  )
}

function IterationsTable({ calls, llmCalls }: { calls: ToolCallEntry[]; llmCalls?: LlmCallEntry[] }) {
  const byIteration = new Map<number, ToolCallEntry[]>()
  for (const c of calls) {
    if (!byIteration.has(c.iteration)) byIteration.set(c.iteration, [])
    byIteration.get(c.iteration)!.push(c)
  }
  const allIterNumbers = new Set<number>([...byIteration.keys()])
  const llmByIteration = new Map<number, LlmCallEntry>()
  for (const lc of (llmCalls ?? [])) {
    if (!llmByIteration.has(lc.iteration)) llmByIteration.set(lc.iteration, lc)
    allIterNumbers.add(lc.iteration)
  }
  const iterations = [...allIterNumbers].sort((a, b) => a - b)

  return (
    <table className="w-full text-sm">
      <thead className="border-b border-slate-800/60">
        <tr className="text-left">
          <th className="px-2 py-1 w-7" />
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide w-12 text-center">Iter</th>
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide">Модель</th>
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide text-right">Tokens</th>
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide text-right">Cost</th>
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide">Вызовы</th>
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide w-32">Finish</th>
          <th className="px-2 py-1 text-[10px] font-medium text-slate-500 uppercase tracking-wide text-right">Время</th>
        </tr>
      </thead>
      <tbody>
        {iterations.map(iter => (
          <IterationTableRow
            key={iter}
            iteration={iter}
            calls={byIteration.get(iter) ?? []}
            llmCall={llmByIteration.get(iter)}
          />
        ))}
      </tbody>
    </table>
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
  pending: { label: 'Ожидание', badgeClass: 'bg-slate-700/60 text-slate-400', Icon: Clock },
  running: { label: 'Выполняется', badgeClass: 'bg-blue-900/50 text-blue-300', rowClass: 'bg-blue-950/20', Icon: Loader2, spin: true },
  awaiting_approval: { label: 'Ожидает одобрения', badgeClass: 'bg-amber-900/50 text-amber-300', rowClass: 'bg-amber-950/20', Icon: AlertCircle, pulse: true },
  complete: { label: 'Готово', badgeClass: 'bg-green-900/50 text-green-300', Icon: CheckCircle },
  failed: { label: 'Ошибка', badgeClass: 'bg-red-900/50 text-red-300', rowClass: 'bg-red-950/20', Icon: XCircle },
  skipped: { label: 'Пропущен', badgeClass: 'bg-amber-900/40 text-amber-400', Icon: SkipForward },
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch { /* ignore */ }
  }, [text])
  return (
    <button type="button" onClick={handleCopy} title="Копировать JSON"
      className="ml-1.5 text-slate-600 hover:text-slate-400 transition-colors flex-shrink-0">
      {copied ? <Check className="w-3 h-3 text-green-400" /> : <Copy className="w-3 h-3" />}
    </button>
  )
}

// KNOWN_FIELDS: fallback for blocks without a view spec
const KNOWN_FIELDS: FieldSpec[] = [
  { key: 'title', label: 'Title', kind: 'string', emphasis: true },
  { key: 'feat_id', label: 'Feat ID', kind: 'string' },
  { key: 'slug', label: 'Slug', kind: 'string' },
  { key: 'complexity', label: 'Complexity', kind: 'string' },
  { key: 'needs_clarification', label: 'Needs clarification', kind: 'bool' },
  { key: 'as_is', label: 'Как сейчас', kind: 'multiline' },
  { key: 'to_be', label: 'Как надо', kind: 'multiline' },
  { key: 'out_of_scope', label: 'Вне scope', kind: 'multiline' },
  { key: 'acceptance', label: 'Критерии приёмки', kind: 'multiline' },
  { key: 'technical_approach', label: 'Подход', kind: 'multiline' },
  { key: 'affected_components', label: 'Затрагиваемые компоненты', kind: 'list' },
  { key: 'acceptance_checklist', label: 'Acceptance checklist', kind: 'objList', emphasis: true },
  { key: 'goal', label: 'Goal', kind: 'multiline', emphasis: true },
  { key: 'approach', label: 'Approach', kind: 'multiline' },
  { key: 'files_to_touch', label: 'Files to touch', kind: 'list' },
  { key: 'tools_to_use', label: 'Tools to use', kind: 'list' },
  { key: 'definition_of_done', label: 'Definition of Done', kind: 'multiline' },
  { key: 'retry_instruction', label: 'Retry instruction', kind: 'multiline' },
  { key: 'issues', label: 'Issues', kind: 'list' },
  { key: 'passed_items', label: 'Passed', kind: 'list' },
  { key: 'failed_items', label: 'Failed', kind: 'list', emphasis: true },
  { key: 'verification_results', label: 'Verification results', kind: 'objList' },
  { key: 'pass_threshold', label: 'Pass threshold', kind: 'string' },
  { key: 'final_text', label: 'Result', kind: 'multiline', emphasis: true },
  { key: 'success', label: 'Success', kind: 'bool' },
  { key: 'exit_code', label: 'Exit code', kind: 'number' },
  { key: 'duration_ms', label: 'Длительность', kind: 'number' },
  { key: 'stdout', label: 'stdout', kind: 'multiline' },
  { key: 'stderr', label: 'stderr', kind: 'multiline' },
  { key: 'command', label: 'Команда', kind: 'multiline' },
  { key: '_skipped', label: 'Skipped', kind: 'bool' },
  { key: 'reason', label: 'Reason', kind: 'string' },
  { key: 'status', label: 'Status', kind: 'string' },
]
const KNOWN_FIELD_KEYS = new Set(KNOWN_FIELDS.map(f => f.key))

const TRUNCATE_LINE_LIMIT = 200
const TRUNCATE_BYTE_LIMIT = 4096
const HEAD_LINES = 50
const TAIL_LINES = 30

function SmartTruncatedPre({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false)
  const lines = text.split('\n')
  const needsTruncate = lines.length > TRUNCATE_LINE_LIMIT || text.length > TRUNCATE_BYTE_LIMIT

  if (!needsTruncate || expanded) {
    return (
      <div data-full-text={text}>
        <pre className="text-xs text-slate-300 bg-slate-950/60 border border-slate-800/60 rounded p-2 whitespace-pre-wrap break-words leading-relaxed">{text}</pre>
        {needsTruncate && expanded && (
          <button type="button" onClick={() => setExpanded(false)}
            className="mt-1 text-[10px] text-slate-500 hover:text-blue-400 transition-colors">
            Свернуть
          </button>
        )}
      </div>
    )
  }

  const head = lines.slice(0, HEAD_LINES).join('\n')
  const tail = lines.slice(-TAIL_LINES).join('\n')
  const elided = lines.length - HEAD_LINES - TAIL_LINES
  return (
    <div data-full-text={text}>
      <pre className="text-xs text-slate-300 bg-slate-950/60 border border-slate-800/60 rounded p-2 whitespace-pre-wrap break-words leading-relaxed">
        {`${head}\n\n... [${elided} строк скрыто] ...\n\n${tail}`}
      </pre>
      <button type="button" onClick={() => setExpanded(true)}
        className="mt-1 text-[10px] text-blue-400 hover:text-blue-300 transition-colors">
        Показать все {lines.length} строк ({(text.length / 1024).toFixed(1)} KB)
      </button>
    </div>
  )
}

function FieldValue({ value, kind }: { value: unknown; kind: FieldSpec['kind'] }) {
  if (value === null || value === undefined || value === '') return <span className="text-slate-600">—</span>
  if (kind === 'bool') {
    return value
      ? <span className="text-green-400 text-sm">✓</span>
      : <span className="text-slate-500 text-sm">✗</span>
  }
  if (kind === 'number') return <span className="font-mono text-slate-300 text-sm">{String(value)}</span>
  if (kind === 'string') return <span className="text-slate-200 text-sm">{String(value)}</span>
  if (kind === 'multiline') {
    const text = String(value).trim()
    if (!text) return <span className="text-slate-600">—</span>
    return <SmartTruncatedPre text={text} />
  }
  if (kind === 'list') {
    const arr = Array.isArray(value) ? value : []
    if (arr.length === 0) return <span className="text-slate-600">—</span>
    return (
      <ul className="text-sm text-slate-300 space-y-0.5 list-disc list-inside">
        {arr.map((item, i) => (
          <li key={i} className="font-mono text-xs">{typeof item === 'string' ? item : JSON.stringify(item)}</li>
        ))}
      </ul>
    )
  }
  if (kind === 'objList') {
    const arr = Array.isArray(value) ? value : []
    if (arr.length === 0) return <span className="text-slate-600">—</span>
    return (
      <div className="space-y-1">
        {arr.map((item, i) => {
          if (typeof item !== 'object' || item === null) {
            return <div key={i} className="text-xs font-mono text-slate-400">{String(item)}</div>
          }
          const obj = item as Record<string, unknown>
          const status = obj.status as string | undefined
          const statusCls = status === 'pass' ? 'text-green-400' : status === 'fail' ? 'text-red-400' : 'text-slate-400'
          const priority = obj.priority as string | undefined
          const priorityCls = priority === 'CRITICAL' ? 'bg-red-950/50 border-red-800/50 text-red-300'
            : priority === 'IMPORTANT' ? 'bg-amber-950/40 border-amber-800/50 text-amber-300'
              : 'bg-slate-800/50 border-slate-700 text-slate-400'
          const main = obj.item ?? obj.requirement ?? obj.description ?? obj.text ?? Object.values(obj)[0]
          return (
            <div key={i} className="text-xs flex items-start gap-2 py-0.5">
              {priority && (
                <span className={clsx('px-1.5 py-0.5 rounded border text-[10px] font-mono uppercase shrink-0', priorityCls)}>{priority}</span>
              )}
              {status && <span className={clsx('text-sm shrink-0', statusCls)}>{status === 'pass' ? '✓' : status === 'fail' ? '✗' : '·'}</span>}
              <span className="text-slate-300 flex-1 min-w-0 break-words">{typeof main === 'string' ? main : JSON.stringify(main)}</span>
            </div>
          )
        })}
      </div>
    )
  }
  return <span className="font-mono text-xs text-slate-400">{JSON.stringify(value)}</span>
}

/** Renders output using spec fields (if provided) or KNOWN_FIELDS fallback. */
export function StructuredOutput({ output, specFields }: { output: Record<string, unknown>; specFields?: FieldSpec[] }) {
  const [showJson, setShowJson] = useState(false)
  const fieldDefs = specFields ?? KNOWN_FIELDS
  const specKeys = specFields ? new Set(specFields.map(f => f.key)) : KNOWN_FIELD_KEYS

  const presentFields = fieldDefs.filter(f => f.key in output && output[f.key] !== null && output[f.key] !== undefined)
  const unknownKeys = Object.keys(output).filter(k => !specKeys.has(k) && !k.startsWith('_'))
  const jsonStr = JSON.stringify(output, null, 2)

  if (presentFields.length === 0) {
    return <CompactJsonView output={output} />
  }

  return (
    <div className="space-y-2">
      {presentFields.map(f => (
        <div key={f.key} className={clsx('border-l-2 pl-3 py-1', f.emphasis ? 'border-blue-700/60' : 'border-slate-800')}>
          <div className="text-[10px] uppercase tracking-wide text-slate-500 font-medium mb-0.5">{f.label}</div>
          <FieldValue value={output[f.key]} kind={f.kind} />
        </div>
      ))}
      {unknownKeys.length > 0 && (
        <button type="button" onClick={() => setShowJson(v => !v)}
          className="flex items-center gap-1 text-[10px] text-slate-500 hover:text-blue-400 transition-colors">
          {showJson ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
          {showJson ? 'Скрыть' : `Ещё ${unknownKeys.length} ${unknownKeys.length === 1 ? 'поле' : unknownKeys.length < 5 ? 'поля' : 'полей'} (JSON)`}
        </button>
      )}
      {showJson && (
        <pre className="text-[10px] text-slate-400 bg-slate-950 border border-slate-800 rounded p-2 overflow-auto max-h-48 whitespace-pre-wrap">{jsonStr}</pre>
      )}
      <div className="flex justify-end">
        <CopyButton text={jsonStr} />
      </div>
    </div>
  )
}

function CompactJsonView({ output }: { output: Record<string, unknown> }) {
  const [expanded, setExpanded] = useState(false)
  const keys = Object.keys(output)
  const preview = keys.slice(0, 2).join(', ')
  const jsonStr = JSON.stringify(output, null, 2)
  const ChevronIcon = expanded ? ChevronDown : ChevronRight
  return (
    <div>
      <div className="flex items-center gap-1">
        <button type="button" onClick={() => setExpanded(v => !v)}
          className="flex items-center gap-1 text-xs text-slate-400 hover:text-blue-400 transition-colors group/toggle">
          <ChevronIcon className="w-3.5 h-3.5 flex-shrink-0 text-slate-500 group-hover/toggle:text-blue-400 transition-colors" />
          <span className="font-mono">{expanded ? 'Свернуть' : `{${preview}${keys.length > 2 ? ', ...' : '}'}}`}</span>
        </button>
        <CopyButton text={jsonStr} />
      </div>
      {expanded && (
        <pre className="mt-2 text-xs text-slate-300 bg-slate-950 border border-slate-700 rounded-lg p-3 overflow-auto max-h-48 whitespace-pre-wrap">{jsonStr}</pre>
      )}
    </div>
  )
}

function BlockSummaryChip({ block, spec }: { block: BlockStatus; spec?: BlockViewSpec }) {
  // Spec summary overrides generic logic for completed blocks
  if (spec?.summary && block.output && block.status === 'complete') {
    const result = spec.summary(block.output)
    const cls = result.ok
      ? 'bg-green-950/40 border-green-800/50 text-green-300'
      : result.fail
        ? 'bg-red-950/40 border-red-800/50 text-red-300 font-medium'
        : result.warn
          ? 'bg-amber-950/40 border-amber-800/50 text-amber-300'
          : 'bg-slate-800 border-slate-700 text-slate-400'
    return (
      <span className={clsx('inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded border', cls)}>
        {result.label}
      </span>
    )
  }

  if (block.status === 'failed') {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-red-950/40 border border-red-800/50 text-red-300 font-medium">
        <XCircle className="w-3 h-3" /> failed
      </span>
    )
  }
  if (block.status === 'skipped') {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-slate-800 border border-slate-700 text-slate-500">
        <SkipForward className="w-3 h-3" /> skipped
      </span>
    )
  }
  if (block.status === 'awaiting_approval') {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-amber-950/40 border border-amber-700/50 text-amber-300 animate-pulse">
        <Bell className="w-3 h-3" /> approval pending
      </span>
    )
  }
  if (block.status === 'running') {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-blue-950/40 border border-blue-800/50 text-blue-300">
        <Loader2 className="w-3 h-3 animate-spin" /> running…
      </span>
    )
  }

  const out = block.output
  if (!out) return <span className="text-slate-700 text-xs">—</span>

  if (out._skipped === true) {
    const reason = typeof out.reason === 'string' ? out.reason : 'condition'
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-slate-800 border border-slate-700 text-slate-500" title={reason}>
        <SkipForward className="w-3 h-3" /> skipped
      </span>
    )
  }

  if (Array.isArray(out.failed_items) || Array.isArray(out.passed_items)) {
    const passed = Array.isArray(out.passed_items) ? out.passed_items.length : 0
    const failed = Array.isArray(out.failed_items) ? out.failed_items.length : 0
    const total = passed + failed
    const ok = failed === 0 && passed > 0
    const cls = ok ? 'bg-green-950/40 border-green-800/50 text-green-300'
      : failed > 0 ? 'bg-red-950/40 border-red-800/50 text-red-300 font-medium'
        : 'bg-slate-800 border-slate-700 text-slate-400'
    return (
      <span className={clsx('inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded border', cls)}>
        {ok ? <CheckCircle className="w-3 h-3" /> : failed > 0 ? <XCircle className="w-3 h-3" /> : null}
        {passed}/{total} pass
      </span>
    )
  }
  if (Array.isArray(out.acceptance_checklist)) {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-blue-950/40 border border-blue-800/50 text-blue-300">
        checklist · {out.acceptance_checklist.length} items
      </span>
    )
  }
  if (typeof out.retry_instruction === 'string' && out.retry_instruction.trim()) {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-amber-950/40 border border-amber-800/50 text-amber-300">
        <RotateCcw className="w-3 h-3" /> loopback
      </span>
    )
  }
  if (Array.isArray(out.issues) && out.issues.length > 0) {
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-amber-950/40 border border-amber-800/50 text-amber-300">
        <AlertCircle className="w-3 h-3" /> {out.issues.length} issues
      </span>
    )
  }
  if (typeof out.goal === 'string' && out.goal.trim()) {
    const goalPreview = out.goal.length > 60 ? out.goal.slice(0, 60) + '…' : out.goal
    return <span className="text-xs text-slate-400" title={out.goal}><span className="text-slate-500">goal:</span> {goalPreview}</span>
  }
  if (typeof out.success === 'boolean') {
    const exit = typeof out.exit_code === 'number' ? out.exit_code : null
    if (out.success) {
      return (
        <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-green-950/40 border border-green-800/50 text-green-300">
          <CheckCircle className="w-3 h-3" /> ok{exit !== null && exit !== 0 ? ` (exit ${exit})` : ''}
        </span>
      )
    }
    return (
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-red-950/40 border border-red-800/50 text-red-300 font-medium">
        <XCircle className="w-3 h-3" /> {exit !== null ? `exit ${exit}` : 'failed'}
      </span>
    )
  }
  if (typeof out.final_text === 'string' && out.final_text.trim()) {
    return <span className="text-xs text-slate-400"><span className="text-slate-500">result:</span> {out.final_text.length} chars</span>
  }
  if (typeof out.feat_id === 'string' && typeof out.title === 'string') {
    const titlePreview = out.title.length > 40 ? out.title.slice(0, 40) + '…' : out.title
    return (
      <span className="text-xs text-slate-400">
        <span className="font-mono text-slate-500">{out.feat_id}</span> {titlePreview}
      </span>
    )
  }
  const keys = Object.keys(out).filter(k => !k.startsWith('_'))
  return <span className="text-xs text-slate-500 font-mono">{keys.length} {keys.length === 1 ? 'поле' : keys.length < 5 ? 'поля' : 'полей'}</span>
}

function BlockExpandedDetail({ block, calls, llmCalls, runId, spec }: {
  block: BlockStatus
  calls: ToolCallEntry[]
  llmCalls?: LlmCallEntry[]
  runId?: string
  spec?: BlockViewSpec
}) {
  const [inputOpen, setInputOpen] = useState(false)
  const [copied, setCopied] = useState(false)

  const hasIterations = calls.length > 0 || (llmCalls && llmCalls.length > 0)
  const hasOutput = !!block.output && Object.keys(block.output).length > 0
  const hasInput = !!block.input && Object.keys(block.input).length > 0

  const hasErrors = calls.some(c => c.isError) ||
    block.status === 'failed' ||
    (llmCalls?.some(lc => BAD_FINISH.has((lc.finishReason ?? '').toUpperCase())) ?? false)
  const [iterOpen, setIterOpen] = useState(false)
  // Auto-open when errors are detected — even if llmCalls loaded after initial render
  useEffect(() => {
    if (hasErrors) setIterOpen(true)
  }, [hasErrors])

  const iterCount = new Set(calls.map(tc => tc.iteration)).size

  const copyAsMd = useCallback(async () => {
    if (!runId) return
    try {
      const resp = await fetch(`/api/runs/${runId}/report?format=md&block=${encodeURIComponent(block.blockId)}`, { credentials: 'include' })
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      await navigator.clipboard.writeText(await resp.text())
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch (e) { console.error('Copy block as MD failed:', e) }
  }, [block.blockId, runId])

  return (
    <div className="border-t border-slate-800 bg-slate-950/40">
      {/* 1. Output — first */}
      {hasOutput && (
        <section className="px-5 py-4">
          <h4 className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-2">Результат</h4>
          {spec?.renderOutput
            ? spec.renderOutput(block.output!)
            : <StructuredOutput output={block.output!} specFields={spec?.fields} />}
        </section>
      )}

      {/* 2. Input — second, collapsed */}
      {hasInput && (
        <section className="px-5 py-3 border-t border-slate-800/60">
          <button type="button" onClick={() => setInputOpen(v => !v)}
            className="flex items-center gap-1.5 text-[10px] uppercase tracking-wide text-slate-500 hover:text-slate-300 font-medium transition-colors">
            {inputOpen ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            Входные данные
            <span className="text-slate-600 normal-case font-normal">({Object.keys(block.input!).length} {Object.keys(block.input!).length === 1 ? 'поле' : 'полей'})</span>
          </button>
          {inputOpen && (
            <div className="mt-2">
              <StructuredOutput output={block.input!} specFields={spec?.inputFields} />
            </div>
          )}
        </section>
      )}

      {/* 3. Iterations — third, collapsible */}
      {hasIterations && (
        <section className="border-t border-slate-800/60">
          <button type="button" onClick={() => setIterOpen(v => !v)}
            className="w-full flex items-center gap-2 px-5 py-3 text-left hover:bg-slate-800/20 transition-colors">
            {iterOpen ? <ChevronDown className="w-3 h-3 text-slate-500" /> : <ChevronRight className="w-3 h-3 text-slate-500" />}
            <span className="text-[10px] uppercase tracking-wide text-slate-500 font-medium">
              Детали выполнения
            </span>
            <span className="text-[10px] text-slate-600 normal-case">
              {iterCount > 0 ? `${iterCount} итер.` : ''}{calls.length > 0 ? ` · ${calls.length} вызовов` : ''}
            </span>
            {hasErrors && (
              <span className="ml-auto text-[10px] font-mono text-red-400 bg-red-950/40 border border-red-800/50 rounded px-1.5 py-0.5">
                ошибки
              </span>
            )}
          </button>
          {iterOpen && (
            <div className="px-4 pb-3 bg-slate-950/60">
              <IterationsTable calls={calls} llmCalls={llmCalls} />
            </div>
          )}
        </section>
      )}

      {/* 4. Copy as MD — bottom */}
      {runId && (
        <div className="flex justify-end px-5 py-2 border-t border-slate-800/40">
          <button type="button" onClick={copyAsMd}
            className={clsx(
              'flex items-center gap-1.5 text-[10px] uppercase tracking-wide px-2 py-1 rounded border transition-colors',
              copied
                ? 'bg-green-950/40 border-green-800/50 text-green-300'
                : 'bg-slate-800 border-slate-700 text-slate-400 hover:text-slate-200 hover:bg-slate-700',
            )}
            title="Скопировать блок как Markdown">
            {copied ? <><Check className="w-3 h-3" /> Скопировано</> : <><Copy className="w-3 h-3" /> Copy as MD</>}
          </button>
        </div>
      )}
    </div>
  )
}

export default function BlockProgressTable({ blockStatuses, onReviewApproval, onRelaunchFromBlock, snapshots, toolCalls, llmCalls, runId }: Props) {
  const [expandedBlocks, setExpandedBlocks] = useState<Set<string>>(new Set())

  const toggleBlock = useCallback((key: string) => {
    setExpandedBlocks(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  // Build blockId → last LLM call map (used for model chip on main row)
  const lastLlmByBlockIteration = useMemo(() => {
    const map = new Map<string, LlmCallEntry>()
    for (const lc of (llmCalls ?? [])) {
      map.set(`${lc.blockId}:${lc.iteration}`, lc)
    }
    return map
  }, [llmCalls])

  if (blockStatuses.length === 0) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl">
        <div className="px-5 py-4 border-b border-slate-800">
          <h2 className="text-sm font-semibold text-slate-200">Прогресс блоков</h2>
        </div>
        <div className="px-5 py-10 text-center text-slate-600 text-sm">Блоки ещё не запущены.</div>
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
              <Bell className="w-3.5 h-3.5" /> Требуется одобрение
            </span>
          )}
          <span className="text-xs text-slate-500">{completedCount}/{blockStatuses.length} готово</span>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-800">
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide w-8">#</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Блок</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Статус</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Время</th>
              <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Результат</th>
              {(onReviewApproval || onRelaunchFromBlock) && (
                <th className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Действия</th>
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-800/60">
            {blockStatuses.map((block, index) => {
              const cfg = STATUS_CONFIG[block.status]
              const Icon = cfg.Icon
              const blockIteration = block.iteration ?? 0
              const rowKey = `${block.blockId}:${blockIteration}`
              const blockCalls = toolCalls?.filter(tc => tc.blockId === block.blockId && tc.iteration >= blockIteration * 0) ?? []
              // For per-iteration blocks, filter calls to this iteration range
              const iterCalls = (block.iteration != null)
                ? toolCalls?.filter(tc => tc.blockId === block.blockId) ?? []
                : blockCalls
              const blockLlmCalls = (block.iteration != null)
                ? llmCalls?.filter(lc => lc.blockId === block.blockId) ?? []
                : llmCalls?.filter(lc => lc.blockId === block.blockId) ?? []
              const lastLlmCall = lastLlmByBlockIteration.get(`${block.blockId}:${blockIteration}`)
                ?? blockLlmCalls[blockLlmCalls.length - 1]
              const shortModel = lastLlmCall?.model.replace(/^[^/]+\//, '').replace(/^claude-/, '')
              const badFinishReason = lastLlmCall?.finishReason && BAD_FINISH.has(lastLlmCall.finishReason.toUpperCase())
                ? lastLlmCall.finishReason.toUpperCase() : null
              const isExpanded = expandedBlocks.has(rowKey)
              const hasActionsCol = !!(onReviewApproval || onRelaunchFromBlock)
              const colSpan = hasActionsCol ? 6 : 5
              const spec = getBlockView(block.blockId)
              const hasDetails = iterCalls.length > 0 || !!block.output || !!block.input

              return (
                <Fragment key={rowKey}>
                  <tr className={clsx('transition-colors', cfg.rowClass)}>
                    <td className="px-5 py-3.5 text-slate-600 text-xs">{index + 1}</td>
                    <td className="px-5 py-3.5">
                      <div className="flex items-start gap-2 flex-wrap">
                        <div>
                          <div className="flex items-center gap-1.5">
                            <span className="text-sm text-slate-100 font-medium leading-tight">{blockIdLabel(block.blockId)}</span>
                            {block.iteration != null && block.iteration > 0 && (
                              <span className="text-[10px] font-mono bg-amber-900/40 text-amber-400 border border-amber-700/50 rounded px-1 py-0.5 leading-none">
                                #{block.iteration + 1}
                              </span>
                            )}
                          </div>
                          {/* Bad finish reason warning */}
                          {badFinishReason && (
                            <div className="flex items-center gap-1 mt-0.5">
                              <AlertCircle className="w-3 h-3 text-amber-400 flex-shrink-0" />
                              <span className="text-[10px] font-mono text-amber-400">{badFinishReason}</span>
                            </div>
                          )}
                        </div>
                        {(() => {
                          const snapshot = snapshots?.get(block.blockId)
                          const mode = effectiveApprovalMode(snapshot)
                          return (
                            <div className="flex items-center gap-1.5 flex-wrap mt-0.5">
                              {mode && <ApprovalBadge mode={mode} />}
                              {snapshot?.enabled === false && (
                                <span className="text-[10px] uppercase tracking-wide bg-slate-800 border border-slate-700 text-slate-500 px-1.5 py-0.5 rounded" title="Блок отключён">выкл</span>
                              )}
                              {snapshot?.condition && (
                                <span className="text-[10px] uppercase tracking-wide bg-slate-800/80 border border-slate-700 text-slate-400 px-1.5 py-0.5 rounded" title={`Условие: ${snapshot.condition}`}>условие</span>
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
                      {hasDetails && (
                        <button type="button" onClick={() => toggleBlock(rowKey)}
                          className="mt-1.5 flex items-center gap-1 text-[11px] text-slate-500 hover:text-slate-300 transition-colors">
                          {isExpanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                          детали
                        </button>
                      )}
                    </td>
                    <td className="px-5 py-3.5 whitespace-nowrap">
                      {block.status === 'running' && block.startedAt ? (
                        <LiveDuration startedAt={block.startedAt} />
                      ) : block.durationMs != null ? (
                        <span className="font-mono text-xs text-slate-400">{fmtDurationCompact(block.durationMs)}</span>
                      ) : block.startedAt && block.completedAt ? (
                        <span className="font-mono text-xs text-slate-400">{fmtDuration(new Date(block.completedAt).getTime() - new Date(block.startedAt).getTime())}</span>
                      ) : (
                        <span className="text-xs text-slate-600">—</span>
                      )}
                    </td>
                    <td className="px-5 py-3.5 max-w-xs">
                      <div className="flex flex-col gap-1">
                        <BlockSummaryChip block={block} spec={spec} />
                        {shortModel && (
                          <span className="inline-flex items-center gap-1 text-[10px] font-mono px-1.5 py-0.5 rounded bg-violet-950/50 border border-violet-800/50 text-violet-300 w-fit"
                            title={lastLlmCall?.provider ?? ''}>
                            {shortModel}
                          </span>
                        )}
                      </div>
                    </td>
                    {hasActionsCol && (
                      <td className="px-5 py-3.5">
                        {block.status === 'awaiting_approval' && onReviewApproval && (
                          <button type="button" onClick={() => onReviewApproval(block.blockId)}
                            className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-amber-900/40 border border-amber-700/50 text-amber-300 hover:bg-amber-900/60 transition-colors">
                            <Bell className="w-3.5 h-3.5" /> Рассмотреть
                          </button>
                        )}
                        {block.status === 'complete' && onRelaunchFromBlock && (
                          <button type="button" onClick={() => onRelaunchFromBlock(block.blockId)}
                            className="p-1.5 rounded-md text-slate-600 hover:text-blue-400 hover:bg-blue-950/40 transition-colors"
                            title="Перезапустить с этого блока">
                            <RotateCcw className="w-3.5 h-3.5" />
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                  {isExpanded && (
                    <tr>
                      <td colSpan={colSpan} className="p-0">
                        <BlockExpandedDetail
                          block={block}
                          calls={iterCalls}
                          llmCalls={blockLlmCalls}
                          runId={runId}
                          spec={spec}
                        />
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
