import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate, useLocation } from 'react-router-dom'
import { AlertCircle, Loader2, RotateCcw, Undo2, FileEdit, FilePlus, Sparkles, Download } from 'lucide-react'
import { useToast } from '../context/ToastContext'
import { api } from '../services/api'
import { connectToRun } from '../services/websocket'
import { PipelineRun, BlockStatus, WsMessage, ApprovalDecision, ToolCallEntry, LlmCallEntry } from '../types'
import BlockProgressTable from '../components/BlockProgressTable'
import ApprovalDialog from '../components/ApprovalDialog'
import ReturnDialog from '../components/ReturnDialog'
import LoopbackTimeline from '../components/LoopbackTimeline'
import AllIterationsTable from '../components/AllIterationsTable'
import LogPanel from '../components/LogPanel'
import { parseConfigSnapshot } from '../utils/configSnapshot'
import { blockIdLabel } from '../utils/blockLabels'
import { useMemo } from 'react'
import RunStatusBadge from '../components/runs/RunStatusBadge'
import RunDuration from '../components/runs/RunDuration'
import CancelButton from '../components/runs/CancelButton'
import PageHeader from '../components/layout/PageHeader'
import clsx from 'clsx'

function ErrorBanner({ error }: { error: string }) {
  const [expanded, setExpanded] = useState(false)
  const hasStack = error.includes('\n')
  const summary = hasStack ? error.split('\n')[0] : error
  const detail = hasStack ? error.slice(summary.length + 1).trimStart() : ''

  return (
    <div className="text-red-300 bg-red-950/50 border border-red-800 rounded-xl px-5 py-4 space-y-2">
      <div className="flex items-start gap-3">
        <AlertCircle className="w-5 h-5 flex-shrink-0 mt-0.5" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-red-200 mb-0.5">Запуск завершился с ошибкой</p>
          <p className="text-sm font-mono break-all">{summary}</p>
        </div>
      </div>
      {detail && (
        <div>
          <button
            type="button"
            onClick={() => setExpanded(v => !v)}
            className="text-xs text-red-400 hover:text-red-300 underline underline-offset-2"
          >
            {expanded ? 'Скрыть стектрейс' : 'Показать стектрейс'}
          </button>
          {expanded && (
            <pre className="mt-2 text-xs text-red-300/80 bg-red-950/60 border border-red-900 rounded-lg p-3 overflow-x-auto whitespace-pre-wrap break-all leading-relaxed">
              {detail}
            </pre>
          )}
        </div>
      )}
    </div>
  )
}

export default function RunPage() {
  const { runId } = useParams<{ runId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const navState = location.state as { from?: string; backHref?: string } | null
  const fromState = navState?.from
  const fromActive = fromState === 'active'
  const backHref = navState?.backHref ?? (fromActive ? '/runs/active' : '/runs/history')
  const [run, setRun] = useState<PipelineRun | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [blockStatuses, setBlockStatuses] = useState<BlockStatus[]>([])
  const [pendingApproval, setPendingApproval] = useState<WsMessage | null>(null)
  // Controls dialog visibility independently of pendingApproval — dismissing hides the dialog
  // without clearing the approval (so the awaiting_approval row in the table persists).
  const [showApprovalDialog, setShowApprovalDialog] = useState(false)
  const [logs, setLogs] = useState<string[]>([])
  const [wsConnected, setWsConnected] = useState(false)
  const [activeTab, setActiveTab] = useState<'blocks' | 'timeline' | 'iterations' | 'logs' | 'summary'>('blocks')
  const [toolCalls, setToolCalls] = useState<ToolCallEntry[]>([])
  const [llmCalls, setLlmCalls] = useState<LlmCallEntry[]>([])
  const [showReturnDialog, setShowReturnDialog] = useState(false)
  const [requirementExpanded, setRequirementExpanded] = useState(false)
  const [relaunchBlock, setRelaunchBlock] = useState<string | null>(null)
  const [relaunchLoading, setRelaunchLoading] = useState(false)
  const [relaunchError, setRelaunchError] = useState<string | null>(null)
  const [bashApprovalRequest, setBashApprovalRequest] = useState<{ command: string; requestId: string; blockId: string } | null>(null)
  const [bashApprovalLoading, setBashApprovalLoading] = useState(false)
  const [bashApprovalError, setBashApprovalError] = useState<string | null>(null)
  const [restartLoading, setRestartLoading] = useState(false)
  const { show: showToast } = useToast()

  // Keep a stable ref to the latest run so reconnect handler can read current state
  const runRef = useRef<PipelineRun | null>(null)
  runRef.current = run

  const addLog = useCallback((msg: string) => {
    const ts = new Date().toISOString().replace('T', ' ').slice(0, 23)
    setLogs(prev => {
      const next = [...prev, `[${ts}] ${msg}`]
      return next.length > 500 ? next.slice(next.length - 500) : next
    })
  }, [])

  // Load run from API and rebuild block status list from persisted data
  const loadRun = useCallback(async (quiet = false) => {
    if (!runId) return
    if (!quiet) setLoading(true)
    try {
      const data = await api.getRun(runId)
      setRun(data)

      // Build blockId → parsed output/input maps from persisted outputs (if available)
      const outputMap = new Map<string, Record<string, unknown>>()
      const inputMap = new Map<string, Record<string, unknown>>()
      if (data.outputs?.length) {
        for (const stored of data.outputs) {
          try {
            const parsed = JSON.parse(stored.outputJson)
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
              outputMap.set(stored.blockId, parsed as Record<string, unknown>)
            }
          } catch {
            // ignore malformed JSON
          }
          if (stored.inputJson) {
            try {
              const parsed = JSON.parse(stored.inputJson)
              if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                inputMap.set(stored.blockId, parsed as Record<string, unknown>)
              }
            } catch {
              // ignore malformed JSON
            }
          }
        }
      }

      // Hydrate completed blocks — only blocks that have a persisted output are shown.
      // Pre-entry blocks added by runFrom() with no real injection are excluded.
      const completedStatuses: BlockStatus[] = (data.completedBlocks ?? [])
        .filter(blockId => outputMap.has(blockId))
        .map(blockId => {
          const output = outputMap.get(blockId)
          const isSkipped = output?._skipped === true
          return {
            blockId,
            status: (isSkipped ? 'skipped' : 'complete') as BlockStatus['status'],
            output,
            input: inputMap.get(blockId),
          }
        })

      // Add current block if not already in completedBlocks. The status mapping must
      // cover FAILED — without it, a failed run displayed after page refresh would show
      // its failure block as "pending" (since there's no BlockOutput for an exception
      // path), which renders as "Ожидание" instead of "Ошибка" — confusing for the user.
      if (data.currentBlock && !data.completedBlocks?.includes(data.currentBlock)) {
        const currentStatus: BlockStatus['status'] =
          data.status === 'PAUSED_FOR_APPROVAL' ? 'awaiting_approval' :
          data.status === 'RUNNING' ? 'running' :
          data.status === 'FAILED' ? 'failed' :
          'pending'
        completedStatuses.push({ blockId: data.currentBlock, status: currentStatus })
      }

      setBlockStatuses(completedStatuses)

      // If run is PAUSED_FOR_APPROVAL and we don't have a pendingApproval yet (e.g. page
      // was opened after the WS event was sent), synthesize one from the current block.
      if (data.status === 'PAUSED_FOR_APPROVAL' && data.currentBlock) {
        setPendingApproval(prev => {
          if (prev) return prev // don't overwrite a live WS approval
          const blockOutput = outputMap.get(data.currentBlock!)
          const synthetic: WsMessage = {
            type: 'APPROVAL_REQUEST',
            blockId: data.currentBlock!,
            output: blockOutput,
            runId: data.id,
          }
          setShowApprovalDialog(true)
          return synthetic
        })
      }

      if (!quiet) addLog(`Loaded run ${runId} — status: ${data.status}`)
    } catch {
      if (!quiet) setError('Не удалось загрузить запуск')
    } finally {
      if (!quiet) setLoading(false)
    }
  }, [runId, addLog])

  const handleMessage = useCallback((msg: WsMessage) => {
    if (msg.type === 'BLOCK_STARTED') {
      const blockId = msg.blockId ?? 'unknown'
      const startedAt = new Date().toISOString()
      addLog(`Блок запущен: ${blockIdLabel(blockId)}`)
      setBlockStatuses(prev => {
        const exists = prev.find(b => b.blockId === blockId)
        if (exists) return prev.map(b => b.blockId === blockId ? { ...b, status: 'running', startedAt } : b)
        return [...prev, { blockId, status: 'running', startedAt }]
      })
      setRun(prev => prev ? { ...prev, status: 'RUNNING', currentBlock: blockId } : prev)
    } else if (msg.type === 'BLOCK_COMPLETE') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Блок завершён: ${blockIdLabel(blockId)} — ${msg.status ?? 'done'}`)
      const completedAt = new Date().toISOString()
      setBlockStatuses(prev =>
        prev.map(b =>
          b.blockId === blockId
            ? { ...b, status: msg.status === 'SKIPPED' ? 'skipped' : 'complete', output: msg.output, completedAt }
            : b
        )
      )
      // Refresh tool calls immediately so iteration expand buttons appear without waiting for polling
      if (runId) {
        api.getRunToolCalls(runId).then(setToolCalls).catch(() => {})
        api.getRunLlmCalls(runId).then(setLlmCalls).catch(() => {})
      }
    } else if (msg.type === 'APPROVAL_REQUEST') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Требуется одобрение: ${blockIdLabel(blockId)}`)
      setPendingApproval(msg)
      setShowApprovalDialog(true)
      // Transition the running block to awaiting_approval so the table reflects the gate
      setBlockStatuses(prev =>
        prev.map(b =>
          b.blockId === blockId && b.status === 'running'
            ? { ...b, status: 'awaiting_approval', output: msg.output }
            : b
        )
      )
      setRun(prev => prev ? { ...prev, status: 'PAUSED_FOR_APPROVAL' } : prev)
    } else if (msg.type === 'BASH_APPROVAL_REQUEST') {
      const cmd = msg.command ?? ''
      const reqId = msg.requestId ?? ''
      const bId = msg.blockId ?? ''
      addLog(`Запрос разрешения bash: ${cmd}`)
      setBashApprovalRequest({ command: cmd, requestId: reqId, blockId: bId })
      setBashApprovalError(null)
    } else if (msg.type === 'AUTO_NOTIFY') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Авто-уведомление: ${blockIdLabel(blockId)} завершён без подтверждения`)
      setBlockStatuses(prev =>
        prev.map(b =>
          b.blockId === blockId
            ? { ...b, status: 'complete', output: msg.output }
            : b
        )
      )
      showToast({
        severity: 'info',
        title: `Блок «${blockIdLabel(blockId)}» автоматически принят`,
        body: msg.description ?? 'auto_notify mode — pipeline продолжился без блокирующего подтверждения.',
      })
    } else if (msg.type === 'BLOCK_PROGRESS') {
      const blockId = msg.blockId ?? 'unknown'
      setBlockStatuses(prev =>
        prev.map(b =>
          b.blockId === blockId
            ? { ...b, progressDetail: msg.detail }
            : b
        )
      )
    } else if (msg.type === 'BLOCK_SKIPPED') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Блок пропущен: ${blockIdLabel(blockId)}`)
      setBlockStatuses(prev => {
        const exists = prev.find(b => b.blockId === blockId)
        if (exists) return prev.map(b => b.blockId === blockId ? { ...b, status: 'skipped' } : b)
        return [...prev, { blockId, status: 'skipped' }]
      })
    } else if (msg.type === 'RUN_COMPLETE') {
      const status = msg.status === 'FAILED' ? 'FAILED' : 'COMPLETED'
      addLog(status === 'FAILED' ? 'Запуск завершился с ошибкой' : 'Запуск завершён')
      setRun(prev => prev ? { ...prev, status } : prev)
      // When a run fails, any block still marked running should transition to failed
      // so the block table reflects which step caused the failure.
      if (msg.status === 'FAILED') {
        setBlockStatuses(prev =>
          prev.map(b =>
            b.status === 'running'
              ? { ...b, status: 'failed' }
              : b
          )
        )
      }
    }
  }, [addLog, showToast])

  useEffect(() => {
    loadRun(false)
  }, [loadRun])

  useEffect(() => {
    if (!runId) return
    const disconnect = connectToRun(runId, handleMessage, () => {
      setWsConnected(true)
      addLog('WebSocket connected')
      // Re-fetch run state on reconnect to recover any events missed during disconnection
      const currentRun = runRef.current
      const isActive = !currentRun ||
        currentRun.status === 'RUNNING' ||
        currentRun.status === 'PAUSED_FOR_APPROVAL' ||
        currentRun.status === 'PENDING'
      if (isActive) {
        loadRun(true)
        addLog('Refreshed run state after reconnect')
      }
    })
    return () => {
      setWsConnected(false)
      disconnect()
    }
  }, [runId, handleMessage, addLog, loadRun])

  const handleDecision = useCallback(async (decision: ApprovalDecision) => {
    if (!runId) return
    try {
      await api.submitApproval(runId, decision)
      setPendingApproval(null)
      setShowApprovalDialog(false)
      addLog(`Одобрение отправлено: ${decision.decision} для блока ${blockIdLabel(decision.blockId)}`)
      showToast({
        severity: 'info',
        title: `«${blockIdLabel(decision.blockId)}» одобрен`,
        body: 'Пайплайн продолжается...',
      })
      // Optimistically transition block back to running (WS will confirm with BLOCK_COMPLETE)
      if (decision.decision === 'APPROVE' || decision.decision === 'EDIT') {
        setBlockStatuses(prev =>
          prev.map(b =>
            b.blockId === decision.blockId && b.status === 'awaiting_approval'
              ? { ...b, status: 'running' }
              : b
          )
        )
      } else if (decision.decision === 'SKIP') {
        setBlockStatuses(prev =>
          prev.map(b =>
            b.blockId === decision.blockId && b.status === 'awaiting_approval'
              ? { ...b, status: 'skipped' }
              : b
          )
        )
      }
      setRun(prev => prev ? { ...prev, status: 'RUNNING' } : prev)
    } catch {
      addLog(`ОШИБКА: не удалось отправить одобрение для блока ${blockIdLabel(decision.blockId)}`)
      showToast({ severity: 'error', title: 'Ошибка одобрения', body: 'Не удалось отправить решение. Попробуйте снова.' })
    }
  }, [runId, addLog, showToast])

  // Blocks that haven't finished yet — used to populate the Jump target list in ApprovalDialog
  const remainingBlocks = blockStatuses
    .filter(b => b.status === 'pending' || b.status === 'running' || b.status === 'awaiting_approval')
    .map(b => b.blockId)

  const isHistorical = run?.status === 'COMPLETED' || run?.status === 'FAILED'
  const canCancel = run?.status === 'RUNNING' || run?.status === 'PAUSED_FOR_APPROVAL'

  const blockSnapshots = useMemo(
    () => parseConfigSnapshot(run?.configSnapshotJson),
    [run?.configSnapshotJson]
  )

  const runSummary = useMemo(() => {
    if (!run?.outputs?.length) return null
    const outputMap = new Map<string, Record<string, unknown>>()
    for (const stored of run.outputs) {
      try { outputMap.set(stored.blockId, JSON.parse(stored.outputJson)) } catch { /* skip */ }
    }
    const taskMd = outputMap.get('task_md') as Record<string, unknown> | undefined
    const impl = outputMap.get('impl') as Record<string, unknown> | undefined
    const commit = outputMap.get('commit') as Record<string, unknown> | undefined
    const analysis = outputMap.get('analysis') as Record<string, unknown> | undefined

    const writtenFiles = new Set<string>()
    const editedFiles = new Set<string>()
    for (const tc of toolCalls) {
      if (tc.isError) continue
      try {
        const inp = JSON.parse(tc.inputJson) as Record<string, unknown>
        const fp = inp['file_path'] as string | undefined
        if (fp) {
          if (tc.toolName === 'Write') writtenFiles.add(fp)
          else if (tc.toolName === 'Edit') editedFiles.add(fp)
        }
      } catch { /* skip */ }
    }

    return { taskMd, impl, commit, analysis, writtenFiles: [...writtenFiles], editedFiles: [...editedFiles] }
  }, [run?.outputs, toolCalls])

  // Redirect global /runs/:runId to project-scoped URL when possible
  useEffect(() => {
    if (!run || !runId) return
    const inProjectRoute = location.pathname.includes('/projects/')
    if (!inProjectRoute && run.projectSlug && run.projectSlug !== 'default') {
      navigate(`/projects/${run.projectSlug}/runs/${runId}`, { replace: true })
    }
  }, [run?.projectSlug, runId, location.pathname, navigate])

  const relaunchInjectedBlocks = useMemo(() => {
    if (!relaunchBlock || !run) return []
    const completed = run.completedBlocks ?? []
    const idx = completed.indexOf(relaunchBlock)
    return idx >= 0 ? completed.slice(0, idx) : completed
  }, [relaunchBlock, run])

  const handleRelaunchSubmit = useCallback(async () => {
    if (!run || !relaunchBlock) return
    setRelaunchLoading(true)
    setRelaunchError(null)
    try {
      const pipelines = await api.listPipelines()
      const match = pipelines.find(p => p.pipelineName === run.pipelineName || p.name === run.pipelineName)
      if (!match) throw new Error(`Не найден конфиг для "${run.pipelineName}"`)

      const injectedOutputs: Record<string, Record<string, unknown>> = {}
      for (const stored of (run.outputs ?? [])) {
        if (relaunchInjectedBlocks.includes(stored.blockId)) {
          try { injectedOutputs[stored.blockId] = JSON.parse(stored.outputJson) } catch { /* skip */ }
        }
      }

      const result = await api.startRun({
        configPath: match.path,
        requirement: run.requirement,
        fromBlock: relaunchBlock,
        injectedOutputs,
      })
      setRelaunchBlock(null)
      navigate(`/runs/${result.id ?? result.runId}`)
    } catch (e) {
      setRelaunchError(e instanceof Error ? e.message : String(e))
    } finally {
      setRelaunchLoading(false)
    }
  }, [run, relaunchBlock, relaunchInjectedBlocks, navigate])

  const handleRestart = useCallback(async () => {
    if (!run) return
    setRestartLoading(true)
    try {
      const pipelines = await api.listPipelines()
      const match = pipelines.find(p => p.pipelineName === run.pipelineName || p.name === run.pipelineName)
      if (!match) throw new Error(`Не найден конфиг для "${run.pipelineName}"`)
      const body: Parameters<typeof api.startRun>[0] = {
        configPath: match.path,
        requirement: run.requirement,
      }
      if (run.entryPointId) body.entryPointId = run.entryPointId
      if (run.runInputsJson) {
        try { body.inputs = JSON.parse(run.runInputsJson) } catch { /* ignore */ }
      }
      const result = await api.startRun(body)
      navigate(`/runs/${result.id ?? result.runId}`)
    } catch (e) {
      addLog(`Ошибка перезапуска: ${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setRestartLoading(false)
    }
  }, [run, navigate, addLog])

  const handleReturn = useCallback(async (targetBlock: string, comment: string) => {
    if (!runId || !run) return
    const pipelines = await api.listPipelines()
    const match = pipelines.find(p => p.pipelineName === run.pipelineName || p.name === run.pipelineName)
    if (!match) throw new Error(`Не удалось найти путь к конфигу для "${run.pipelineName}"`)
    await api.returnRun(runId, { targetBlock, comment, configPath: match.path })
    setShowReturnDialog(false)
    addLog(`Возврат отправлен: к блоку «${blockIdLabel(targetBlock)}»`)
    loadRun(true)
  }, [runId, run, addLog, loadRun])

  const handleBashApproval = useCallback(async (approved: boolean, allowAll = false) => {
    if (!runId || !bashApprovalRequest) return
    setBashApprovalLoading(true)
    setBashApprovalError(null)
    try {
      await api.resolveBashApproval(runId, bashApprovalRequest.requestId, approved, allowAll, bashApprovalRequest.blockId)
      if (allowAll) {
        addLog(`Разрешены все команды для блока «${blockIdLabel(bashApprovalRequest.blockId)}»`)
      } else {
        addLog(approved
          ? `Команда разрешена: ${bashApprovalRequest.command}`
          : `Команда отклонена: ${bashApprovalRequest.command}`)
      }
      setBashApprovalRequest(null)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      addLog(`Ошибка ответа на запрос bash: ${msg}`)
      setBashApprovalError(msg)
    } finally {
      setBashApprovalLoading(false)
    }
  }, [runId, bashApprovalRequest, addLog])

  // Load tool calls for all runs; refresh when a block completes
  useEffect(() => {
    if (!runId) return
    api.getRunToolCalls(runId).then(setToolCalls).catch(() => {/* ignore */})
    api.getRunLlmCalls(runId).then(setLlmCalls).catch(() => {/* ignore */})
  }, [runId])

  // Poll tool calls every 5s while run is active so in-progress iterations are visible
  useEffect(() => {
    if (isHistorical || !runId) return
    const id = setInterval(() => {
      api.getRunToolCalls(runId).then(setToolCalls).catch(() => {/* ignore */})
      api.getRunLlmCalls(runId).then(setLlmCalls).catch(() => {/* ignore */})
    }, 5000)
    return () => clearInterval(id)
  }, [isHistorical, runId])

  // When run is historical, event log is unavailable — switch to blocks tab if logs was active
  useEffect(() => {
    if (isHistorical && activeTab === 'logs') {
      setActiveTab('summary')
    }
  }, [isHistorical, activeTab])

  if (!runId) return null

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title={run?.pipelineName ?? 'Запуск'}
        breadcrumbs={[
          { label: fromActive ? 'Активные' : 'История', href: backHref },
          {
            label: run?.pipelineName ?? '…',
            href: run?.pipelineName
              ? `/runs/history?pipeline=${encodeURIComponent(run.pipelineName)}`
              : undefined,
          },
          { label: runId.slice(0, 8) },
        ]}
        actions={
          <div className="flex items-center gap-2">
            {/* Live indicator — only shown while run is active */}
            {!isHistorical && (
              <span className={clsx(
                'inline-flex items-center gap-1.5 px-2 py-1 rounded-full text-xs',
                wsConnected ? 'text-green-400' : 'text-slate-500'
              )}>
                <span className={clsx('w-1.5 h-1.5 rounded-full', wsConnected ? 'bg-green-400 animate-pulse' : 'bg-slate-500')} />
                {wsConnected ? 'Онлайн' : 'Подключение...'}
              </span>
            )}

            {/* Cancel button */}
            {canCancel && runId && (
              <CancelButton
                runId={runId}
                onCancelled={() => loadRun(true)}
              />
            )}

            {/* Return-to-block (for historical runs) */}
            {isHistorical && run && (run.completedBlocks?.length ?? 0) > 0 && (
              <button
                type="button"
                onClick={() => setShowReturnDialog(true)}
                className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md bg-amber-900/40 hover:bg-amber-900/60 border border-amber-800 text-amber-300 transition-colors"
                title="Вернуть задачу на предыдущий этап с комментарием"
              >
                <Undo2 className="w-3.5 h-3.5" />
                Вернуть на доработку
              </button>
            )}

            {/* Re-run button */}
            {isHistorical && run && (
              <button
                type="button"
                onClick={handleRestart}
                disabled={restartLoading}
                className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md bg-slate-700 hover:bg-slate-600 text-slate-300 transition-colors disabled:opacity-50"
              >
                <RotateCcw className={`w-3.5 h-3.5 ${restartLoading ? 'animate-spin' : ''}`} />
                {restartLoading ? 'Запуск...' : 'Перезапуск'}
              </button>
            )}

            {/* Download report */}
            {runId && (
              <a
                href={isHistorical ? `/api/runs/${runId}/report` : undefined}
                download
                onClick={!isHistorical ? e => e.preventDefault() : undefined}
                className={clsx(
                  'flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md border transition-colors',
                  isHistorical
                    ? 'bg-slate-800 border-slate-700 text-slate-300 hover:bg-slate-700 hover:text-white'
                    : 'bg-slate-900 border-slate-800 text-slate-600 cursor-not-allowed'
                )}
                title={isHistorical ? 'Скачать HTML-отчёт о запуске' : 'Отчёт будет доступен после завершения'}
              >
                <Download className="w-3.5 h-3.5" />
                Отчёт
              </a>
            )}
          </div>
        }
      />

      {/* Amber banner when WS disconnects during an active run */}
      {!wsConnected && (run?.status === 'RUNNING' || run?.status === 'PAUSED_FOR_APPROVAL') && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-amber-700/50 bg-amber-900/30 px-4 py-2 text-sm text-amber-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          Обновления приостановлены — переподключение…
        </div>
      )}

      {/* Status + run info */}
      {!loading && run && (
        <>
          {/* FAILED banner */}
          {run.status === 'FAILED' && run.error && (
            <ErrorBanner error={run.error} />
          )}

          <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
            <div className="flex flex-wrap items-center gap-4 mb-3">
              <RunStatusBadge status={run.status} />
              <span className="text-xs text-slate-500 font-mono">{runId}</span>
              <span className="text-xs text-slate-500">
                Длительность: <RunDuration startedAt={run.startedAt} completedAt={run.completedAt} live={!isHistorical} />
              </span>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
              <div>
                <p className="text-slate-500 text-xs uppercase tracking-wide mb-1">Требование</p>
                <p className={clsx('text-slate-200 whitespace-pre-wrap', !requirementExpanded && 'line-clamp-3')}>
                  {run.requirement || '—'}
                </p>
                {(run.requirement?.length ?? 0) > 120 && (
                  <button
                    type="button"
                    onClick={() => setRequirementExpanded(v => !v)}
                    className="mt-1 text-xs text-slate-500 hover:text-slate-300 transition-colors"
                  >
                    {requirementExpanded ? 'Свернуть' : 'Показать полностью'}
                  </button>
                )}
              </div>
              <div>
                <p className="text-slate-500 text-xs uppercase tracking-wide mb-1">Текущий блок</p>
                <p className="text-slate-200" title={run.currentBlock ?? undefined}>{blockIdLabel(run.currentBlock)}</p>
              </div>
              <div>
                <p className="text-slate-500 text-xs uppercase tracking-wide mb-1">Начало</p>
                <p className="text-slate-200">{run.startedAt ? new Date(run.startedAt).toLocaleString() : '—'}</p>
              </div>
            </div>
          </div>
        </>
      )}

      {/* Loading state */}
      {loading && (
        <div className="flex items-center gap-2 text-slate-400">
          <Loader2 className="w-4 h-4 animate-spin" />
          Загрузка деталей запуска...
        </div>
      )}

      {/* Page load error */}
      {error && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          {error}
        </div>
      )}

      {/* Approval dialog */}
      {pendingApproval && showApprovalDialog && (
        <ApprovalDialog
          approval={pendingApproval}
          remainingBlocks={remainingBlocks}
          onDecision={handleDecision}
          onDismiss={() => setShowApprovalDialog(false)}
        />
      )}

      {/* Bash command approval dialog */}
      {bashApprovalRequest && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm px-4">
          <div className="bg-slate-900 border border-amber-700/60 rounded-2xl shadow-2xl w-full max-w-lg p-6 space-y-4">
            <div className="flex items-start gap-3">
              <div className="w-9 h-9 rounded-full bg-amber-900/50 border border-amber-700/60 flex items-center justify-center flex-shrink-0">
                <span className="text-amber-400 text-sm font-bold">$</span>
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-amber-200">Команда вне списка разрешённых</p>
                <p className="text-xs text-slate-400 mt-0.5">
                  Блок <span className="font-mono text-slate-300">{blockIdLabel(bashApprovalRequest.blockId)}</span> хочет выполнить:
                </p>
              </div>
            </div>
            <pre className="bg-slate-950 border border-slate-700 rounded-lg px-4 py-3 text-sm text-amber-100 font-mono whitespace-pre-wrap break-all max-h-40 overflow-auto">
              {bashApprovalRequest.command}
            </pre>
            {bashApprovalError && (
              <div className="flex items-start gap-2 rounded-lg bg-red-950/60 border border-red-800 px-3 py-2 text-xs text-red-300">
                <AlertCircle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                <span>Ошибка: {bashApprovalError}</span>
              </div>
            )}
            <p className="text-xs text-slate-500">
              Нажмите «Разрешить всё для блока» чтобы не подтверждать каждую команду отдельно.
            </p>
            <div className="flex gap-2 justify-end pt-1 flex-wrap">
              <button
                type="button"
                onClick={() => handleBashApproval(false)}
                disabled={bashApprovalLoading}
                className="px-3 py-2 rounded-lg text-sm font-medium bg-slate-800 border border-slate-700 text-slate-300 hover:bg-slate-700 disabled:opacity-50 transition-colors"
              >
                Запретить
              </button>
              <button
                type="button"
                onClick={() => handleBashApproval(true)}
                disabled={bashApprovalLoading}
                className="px-3 py-2 rounded-lg text-sm font-medium bg-amber-600 hover:bg-amber-500 disabled:opacity-50 text-white transition-colors"
              >
                {bashApprovalLoading ? 'Отправка...' : 'Разрешить'}
              </button>
              <button
                type="button"
                onClick={() => handleBashApproval(true, true)}
                disabled={bashApprovalLoading}
                className="px-3 py-2 rounded-lg text-sm font-medium bg-green-700 hover:bg-green-600 disabled:opacity-50 text-white transition-colors"
                title="Разрешить эту и все следующие команды в текущем блоке"
              >
                {bashApprovalLoading ? 'Отправка...' : 'Разрешить всё для блока'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Return-to-block dialog */}
      {showReturnDialog && run && (
        <ReturnDialog
          completedBlocks={run.completedBlocks ?? []}
          onSubmit={handleReturn}
          onClose={() => setShowReturnDialog(false)}
        />
      )}

      {/* Relaunch from block dialog */}
      {relaunchBlock && run && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm px-4">
          <div className="bg-slate-900 border border-slate-700 rounded-2xl shadow-2xl w-full max-w-md p-6 space-y-4">
            <h3 className="text-base font-semibold text-slate-100">Новый запуск с блока</h3>
            <div className="space-y-2 text-sm">
              <div>
                <span className="text-slate-400">Старт с блока:</span>{' '}
                <span className="text-blue-300">{blockIdLabel(relaunchBlock)}</span>
              </div>
              <div>
                <span className="text-slate-400">Передаётся выходов:</span>{' '}
                <span className="text-slate-200">{relaunchInjectedBlocks.length} блок{relaunchInjectedBlocks.length === 1 ? '' : relaunchInjectedBlocks.length < 5 ? 'а' : 'ов'}</span>
              </div>
              {relaunchInjectedBlocks.length > 0 && (
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {relaunchInjectedBlocks.map(b => (
                    <span key={b} className="px-2 py-0.5 text-xs rounded bg-slate-800 text-slate-300 border border-slate-700">
                      {blockIdLabel(b)}
                    </span>
                  ))}
                </div>
              )}
            </div>
            {relaunchError && (
              <div className="flex items-start gap-2 rounded-lg bg-red-950/60 border border-red-800 px-3 py-2 text-xs text-red-300">
                <AlertCircle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                <span>{relaunchError}</span>
              </div>
            )}
            <div className="flex gap-2 justify-end pt-1">
              <button
                type="button"
                onClick={() => { setRelaunchBlock(null); setRelaunchError(null) }}
                disabled={relaunchLoading}
                className="px-3 py-2 rounded-lg text-sm font-medium bg-slate-800 border border-slate-700 text-slate-300 hover:bg-slate-700 disabled:opacity-50 transition-colors"
              >
                Отмена
              </button>
              <button
                type="button"
                onClick={handleRelaunchSubmit}
                disabled={relaunchLoading}
                className="px-3 py-2 rounded-lg text-sm font-medium bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white transition-colors"
              >
                {relaunchLoading ? 'Запуск...' : 'Запустить'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Tabs — Event Log hidden for historical runs (backend does not persist events) */}
      <div className="flex items-center gap-1 border-b border-slate-800">
        {isHistorical && runSummary && (
          <button
            type="button"
            onClick={() => setActiveTab('summary')}
            aria-selected={activeTab === 'summary'}
            role="tab"
            className={clsx(
              'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950',
              activeTab === 'summary'
                ? 'border-blue-500 text-blue-400'
                : 'border-transparent text-slate-500 hover:text-slate-300'
            )}
          >
            Саммари
          </button>
        )}
        <button
          type="button"
          onClick={() => setActiveTab('blocks')}
          aria-selected={activeTab === 'blocks'}
          role="tab"
          className={clsx(
            'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950',
            activeTab === 'blocks'
              ? 'border-blue-500 text-blue-400'
              : 'border-transparent text-slate-500 hover:text-slate-300'
          )}
        >
          Выходы блоков
        </button>
        <button
          type="button"
          onClick={() => setActiveTab('timeline')}
          aria-selected={activeTab === 'timeline'}
          role="tab"
          className={clsx(
            'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950',
            activeTab === 'timeline'
              ? 'border-blue-500 text-blue-400'
              : 'border-transparent text-slate-500 hover:text-slate-300'
          )}
        >
          История итераций
        </button>
        <button
          type="button"
          onClick={() => setActiveTab('iterations')}
          aria-selected={activeTab === 'iterations'}
          role="tab"
          className={clsx(
            'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950',
            activeTab === 'iterations'
              ? 'border-blue-500 text-blue-400'
              : 'border-transparent text-slate-500 hover:text-slate-300'
          )}
        >
          Итерации (таблица)
          {llmCalls.length > 0 && (
            <span className="ml-2 text-[10px] text-slate-500 font-mono">{llmCalls.length}</span>
          )}
        </button>
        {/* Event Log tab only available for active runs */}
        {!isHistorical && (
          <button
            type="button"
            onClick={() => setActiveTab('logs')}
            aria-selected={activeTab === 'logs'}
            role="tab"
            className={clsx(
              'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950',
              activeTab === 'logs'
                ? 'border-blue-500 text-blue-400'
                : 'border-transparent text-slate-500 hover:text-slate-300'
            )}
          >
            Журнал событий
          </button>
        )}
        {/* Approval notification badge on the blocks tab */}
        {pendingApproval && activeTab === 'logs' && (
          <button
            type="button"
            onClick={() => setActiveTab('blocks')}
            className="ml-2 flex items-center gap-1 text-xs text-amber-400 px-2 py-0.5 rounded-full bg-amber-900/30 border border-amber-700/40 animate-pulse"
          >
            <AlertCircle className="w-3 h-3" />
            Требуется одобрение
          </button>
        )}
      </div>
      {isHistorical && (
        <p className="text-xs text-slate-500 mt-1">Журнал событий доступен только для активных запусков</p>
      )}

      {/* Summary tab */}
      {isHistorical && runSummary && (
        <div className={activeTab === 'summary' ? 'space-y-4' : 'hidden'}>
          {/* Task info */}
          {runSummary.taskMd && (
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
              <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">Задача</h3>
              <div className="space-y-1">
                {!!runSummary.taskMd['feat_id'] && (
                  <p className="text-sm font-mono text-blue-400">{String(runSummary.taskMd['feat_id'])}</p>
                )}
                {!!runSummary.taskMd['title'] && (
                  <p className="text-sm text-slate-200">{String(runSummary.taskMd['title'])}</p>
                )}
              </div>
            </div>
          )}

          {/* Analysis */}
          {runSummary.analysis && (
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide">Анализ требования</h3>
                {(() => {
                  const complexity = runSummary.analysis!['estimated_complexity'] as string | undefined
                  if (!complexity) return null
                  return (
                    <span className={clsx(
                      'text-xs px-2 py-0.5 rounded-full font-medium border',
                      complexity === 'low'
                        ? 'bg-green-900/40 border-green-800/60 text-green-300'
                        : complexity === 'high'
                          ? 'bg-red-900/40 border-red-800/60 text-red-300'
                          : 'bg-amber-900/40 border-amber-800/60 text-amber-300'
                    )}>
                      {complexity === 'low' ? 'Низкая сложность' : complexity === 'high' ? 'Высокая сложность' : 'Средняя сложность'}
                    </span>
                  )
                })()}
              </div>
              {(() => {
                const approach = runSummary.analysis!['technical_approach'] as string | undefined
                return approach ? (
                  <p className="text-sm text-slate-300 whitespace-pre-wrap leading-relaxed">{approach}</p>
                ) : null
              })()}
              {Array.isArray(runSummary.analysis['affected_components']) && (runSummary.analysis['affected_components'] as string[]).length > 0 && (
                <div>
                  <p className="text-xs text-slate-500 uppercase tracking-wide mb-1.5">Затронутые компоненты</p>
                  <div className="flex flex-wrap gap-1.5">
                    {(runSummary.analysis['affected_components'] as string[]).map((c, i) => (
                      <span key={i} className="px-2 py-0.5 text-xs font-mono rounded bg-slate-800 text-slate-300 border border-slate-700">{c}</span>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Agent summary */}
          {runSummary.impl && (
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide flex items-center gap-2">
                  <Sparkles className="w-3.5 h-3.5 text-purple-400" />
                  Агент
                </h3>
                <div className="flex gap-3 text-xs text-slate-500">
                  {runSummary.impl['iterations_used'] != null && (
                    <span>{String(runSummary.impl['iterations_used'])} итераций</span>
                  )}
                  {runSummary.impl['tool_calls_made'] != null && (
                    <span>{Array.isArray(runSummary.impl['tool_calls_made']) ? runSummary.impl['tool_calls_made'].length : String(runSummary.impl['tool_calls_made'])} вызовов</span>
                  )}
                  {runSummary.impl['total_cost_usd'] != null && (
                    <span>${Number(runSummary.impl['total_cost_usd']).toFixed(4)}</span>
                  )}
                </div>
              </div>
              {!!runSummary.impl['final_text'] && (
                <p className="text-sm text-slate-300 whitespace-pre-wrap leading-relaxed">
                  {String(runSummary.impl['final_text'])}
                </p>
              )}
            </div>
          )}

          {/* Files changed */}
          {(runSummary.writtenFiles.length > 0 || runSummary.editedFiles.length > 0) && (
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
              <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">
                Изменённые файлы ({runSummary.writtenFiles.length + runSummary.editedFiles.length})
              </h3>
              <div className="space-y-1">
                {runSummary.editedFiles.map(f => (
                  <div key={f} className="flex items-center gap-2 text-sm">
                    <FileEdit className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                    <span className="font-mono text-slate-300 truncate">{f}</span>
                  </div>
                ))}
                {runSummary.writtenFiles.map(f => (
                  <div key={f} className="flex items-center gap-2 text-sm">
                    <FilePlus className="w-3.5 h-3.5 text-green-400 shrink-0" />
                    <span className="font-mono text-slate-300 truncate">{f}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Completed blocks */}
          <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
            <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">
              Блоки ({run?.completedBlocks?.length ?? 0})
            </h3>
            <div className="flex flex-wrap gap-2">
              {run?.completedBlocks?.map(b => (
                <span key={b} className="px-2 py-0.5 text-xs font-mono rounded bg-slate-800 text-slate-300 border border-slate-700">
                  {b}
                </span>
              ))}
            </div>
          </div>
        </div>
      )}

      {/*
        Main content — both panels stay mounted to preserve scroll position and
        expanded output state when switching tabs. CSS `hidden` hides the
        inactive panel without unmounting it.
      */}
      <div className={activeTab === 'blocks' ? undefined : 'hidden'}>
        <BlockProgressTable
          blockStatuses={blockStatuses}
          snapshots={blockSnapshots}
          toolCalls={toolCalls}
          llmCalls={llmCalls}
          onReviewApproval={!isHistorical && pendingApproval ? (blockId) => {
            if (pendingApproval.blockId === blockId) setShowApprovalDialog(true)
          } : undefined}
          onRelaunchFromBlock={isHistorical ? (blockId) => setRelaunchBlock(blockId) : undefined}
        />
      </div>
      <div className={activeTab === 'timeline' ? undefined : 'hidden'}>
        <LoopbackTimeline loopHistoryJson={run?.loopHistoryJson} />
      </div>
      <div className={activeTab === 'iterations' ? undefined : 'hidden'}>
        <AllIterationsTable llmCalls={llmCalls} toolCalls={toolCalls} />
      </div>
      {!isHistorical && (
        <div className={activeTab === 'logs' ? undefined : 'hidden'}>
          <LogPanel logs={logs} onClear={() => setLogs([])} visible={activeTab === 'logs'} />
        </div>
      )}
    </div>
  )
}
