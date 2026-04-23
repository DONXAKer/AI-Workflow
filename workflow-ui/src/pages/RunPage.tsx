import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate, useLocation } from 'react-router-dom'
import { AlertCircle, Loader2, RotateCcw, Undo2, FileEdit, FilePlus, Sparkles } from 'lucide-react'
import { useToast } from '../context/ToastContext'
import { api } from '../services/api'
import { connectToRun } from '../services/websocket'
import { PipelineRun, BlockStatus, WsMessage, ApprovalDecision, ToolCallEntry } from '../types'
import BlockProgressTable from '../components/BlockProgressTable'
import ApprovalDialog from '../components/ApprovalDialog'
import ReturnDialog from '../components/ReturnDialog'
import LoopbackTimeline from '../components/LoopbackTimeline'
import LogPanel from '../components/LogPanel'
import { parseConfigSnapshot } from '../utils/configSnapshot'
import { useMemo } from 'react'
import RunStatusBadge from '../components/runs/RunStatusBadge'
import RunDuration from '../components/runs/RunDuration'
import CancelButton from '../components/runs/CancelButton'
import PageHeader from '../components/layout/PageHeader'
import clsx from 'clsx'

export default function RunPage() {
  const { runId } = useParams<{ runId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const fromActive = (location.state as { from?: string } | null)?.from === 'active'
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
  const [activeTab, setActiveTab] = useState<'blocks' | 'timeline' | 'logs' | 'summary'>('blocks')
  const [toolCalls, setToolCalls] = useState<ToolCallEntry[]>([])
  const [showReturnDialog, setShowReturnDialog] = useState(false)
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

      // Build a blockId → parsed output map from persisted outputs (if available)
      const outputMap = new Map<string, Record<string, unknown>>()
      if (data.outputs?.length) {
        for (const stored of data.outputs) {
          try {
            const parsed = JSON.parse(stored.outputJson)
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
              outputMap.set(stored.blockId, parsed as Record<string, unknown>)
            }
          } catch {
            // ignore malformed JSON — block still shows as complete without output
          }
        }
      }

      // Hydrate completed blocks — merge in persisted output when available
      const completedStatuses: BlockStatus[] = (data.completedBlocks ?? []).map(blockId => ({
        blockId,
        status: 'complete' as const,
        output: outputMap.get(blockId),
      }))

      // Add current block if not already in completedBlocks
      if (data.currentBlock && !data.completedBlocks?.includes(data.currentBlock)) {
        const currentStatus: BlockStatus['status'] =
          data.status === 'PAUSED_FOR_APPROVAL' ? 'awaiting_approval' :
          data.status === 'RUNNING' ? 'running' :
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
      addLog(`Block started: ${blockId}`)
      setBlockStatuses(prev => {
        const exists = prev.find(b => b.blockId === blockId)
        if (exists) return prev.map(b => b.blockId === blockId ? { ...b, status: 'running', startedAt } : b)
        return [...prev, { blockId, status: 'running', startedAt }]
      })
      setRun(prev => prev ? { ...prev, status: 'RUNNING', currentBlock: blockId } : prev)
    } else if (msg.type === 'BLOCK_COMPLETE') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Block complete: ${blockId} — ${msg.status ?? 'done'}`)
      setBlockStatuses(prev =>
        prev.map(b =>
          b.blockId === blockId
            ? { ...b, status: msg.status === 'SKIPPED' ? 'skipped' : 'complete', output: msg.output }
            : b
        )
      )
    } else if (msg.type === 'APPROVAL_REQUEST') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Approval requested for block: ${blockId}`)
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
    } else if (msg.type === 'AUTO_NOTIFY') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Auto-notify: ${blockId} completed without blocking approval`)
      setBlockStatuses(prev =>
        prev.map(b =>
          b.blockId === blockId
            ? { ...b, status: 'complete', output: msg.output }
            : b
        )
      )
      showToast({
        severity: 'info',
        title: `Блок «${blockId}» автоматически принят`,
        body: msg.description ?? 'auto_notify mode — pipeline продолжился без блокирующего подтверждения.',
      })
    } else if (msg.type === 'BLOCK_SKIPPED') {
      const blockId = msg.blockId ?? 'unknown'
      addLog(`Block skipped: ${blockId}`)
      setBlockStatuses(prev => {
        const exists = prev.find(b => b.blockId === blockId)
        if (exists) return prev.map(b => b.blockId === blockId ? { ...b, status: 'skipped' } : b)
        return [...prev, { blockId, status: 'skipped' }]
      })
    } else if (msg.type === 'RUN_COMPLETE') {
      const status = msg.status === 'FAILED' ? 'FAILED' : 'COMPLETED'
      addLog(`Run ${status.toLowerCase()}`)
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
      addLog(`Approval submitted: ${decision.decision} for block ${decision.blockId}`)
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
      addLog(`ERROR: Failed to submit approval for block ${decision.blockId}`)
    }
  }, [runId, addLog])

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

    return { taskMd, impl, commit, writtenFiles: [...writtenFiles], editedFiles: [...editedFiles] }
  }, [run?.outputs, toolCalls])

  const handleReturn = useCallback(async (targetBlock: string, comment: string) => {
    if (!runId || !run) return
    const pipelines = await api.listPipelines()
    const match = pipelines.find(p => p.pipelineName === run.pipelineName || p.name === run.pipelineName)
    if (!match) throw new Error(`Не удалось найти путь к конфигу для "${run.pipelineName}"`)
    await api.returnRun(runId, { targetBlock, comment, configPath: match.path })
    setShowReturnDialog(false)
    addLog(`Return submitted: back to ${targetBlock}`)
    loadRun(true)
  }, [runId, run, addLog, loadRun])

  // Load tool calls for completed/failed runs to show summary
  useEffect(() => {
    if (isHistorical && runId) {
      api.getRunToolCalls(runId).then(setToolCalls).catch(() => {/* ignore */})
    }
  }, [isHistorical, runId])

  // When run is historical, event log is unavailable — switch to blocks tab if logs was active
  useEffect(() => {
    if (isHistorical && activeTab === 'logs') {
      setActiveTab('blocks')
    }
  }, [isHistorical, activeTab])

  if (!runId) return null

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title={run?.pipelineName ?? 'Запуск'}
        breadcrumbs={[
          fromActive
            ? { label: 'Активные', href: '/runs/active' }
            : { label: 'История', href: '/runs/history' },
          {
            label: run?.pipelineName ?? '…',
            href: run?.pipelineName
              ? `/pipelines?pipeline=${encodeURIComponent(run.pipelineName)}`
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
                onClick={() => navigate('/pipelines', { state: { pipeline: run.pipelineName, requirement: run.requirement } })}
                className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md bg-slate-700 hover:bg-slate-600 text-slate-300 transition-colors"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                Перезапуск
              </button>
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
            <div className="flex items-start gap-3 text-red-300 bg-red-950/50 border border-red-800 rounded-xl px-5 py-4">
              <AlertCircle className="w-5 h-5 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-medium text-red-200 mb-0.5">Запуск завершился с ошибкой</p>
                <p className="text-sm">{run.error}</p>
              </div>
            </div>
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
                <p className="text-slate-200 line-clamp-3">{run.requirement || '—'}</p>
              </div>
              <div>
                <p className="text-slate-500 text-xs uppercase tracking-wide mb-1">Текущий блок</p>
                <p className="text-slate-200 font-mono">{run.currentBlock || '—'}</p>
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

      {/* Return-to-block dialog */}
      {showReturnDialog && run && (
        <ReturnDialog
          completedBlocks={run.completedBlocks ?? []}
          onSubmit={handleReturn}
          onClose={() => setShowReturnDialog(false)}
        />
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
                    <span>{String(runSummary.impl['tool_calls_made'])} вызовов</span>
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
          // Only live runs with a pending approval expose the Review action
          onReviewApproval={!isHistorical && pendingApproval ? (blockId) => {
            if (pendingApproval.blockId === blockId) setShowApprovalDialog(true)
          } : undefined}
        />
      </div>
      <div className={activeTab === 'timeline' ? undefined : 'hidden'}>
        <LoopbackTimeline loopHistoryJson={run?.loopHistoryJson} />
      </div>
      {!isHistorical && (
        <div className={activeTab === 'logs' ? undefined : 'hidden'}>
          <LogPanel logs={logs} onClear={() => setLogs([])} visible={activeTab === 'logs'} />
        </div>
      )}
    </div>
  )
}
