import { useState, useEffect, useCallback } from 'react'
import { Link, useSearchParams, useLocation } from 'react-router-dom'
import { Bell, CheckCircle, Loader2 } from 'lucide-react'
import { api } from '../services/api'
import { PipelineRunSummary } from '../types'
import { connectToGlobalRuns } from '../services/websocket'
import RunStatusBadge from '../components/runs/RunStatusBadge'
import RunDuration from '../components/runs/RunDuration'
import CancelButton from '../components/runs/CancelButton'
import PageHeader from '../components/layout/PageHeader'
import { blockIdLabel } from '../utils/blockLabels'
import { runHref } from '../utils/runHref'
import clsx from 'clsx'

type ActiveFilter = 'all' | 'RUNNING' | 'PAUSED_FOR_APPROVAL'

interface ActiveRunsPageProps {
  allProjects?: boolean
}

const FILTERS: { key: ActiveFilter; label: string }[] = [
  { key: 'all', label: 'Все' },
  { key: 'RUNNING', label: 'Выполняются' },
  { key: 'PAUSED_FOR_APPROVAL', label: 'Ожидают одобрения' },
]

export default function ActiveRunsPage({ allProjects = false }: ActiveRunsPageProps) {
  const { pathname } = useLocation()
  const [searchParams] = useSearchParams()
  const [runs, setRuns] = useState<PipelineRunSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState<ActiveFilter>(() => {
    const param = searchParams.get('status')
    if (param === 'RUNNING' || param === 'PAUSED_FOR_APPROVAL') return param
    return 'all'
  })
  const [pipelineFilter, setPipelineFilter] = useState('')
  const [pipelines, setPipelines] = useState<string[]>([])
  const [fadingOut, setFadingOut] = useState<Set<string>>(new Set())
  const [approvingIds, setApprovingIds] = useState<Set<string>>(new Set())

  const load = useCallback(async () => {
    try {
      const data = await api.listRuns({ status: ['RUNNING', 'PAUSED_FOR_APPROVAL'], size: 100, page: 0, allProjects })
      setRuns(data.content)
      const names = [...new Set(data.content.map(r => r.pipelineName))]
      setPipelines(names)
    } catch {
      // ignore
    } finally {
      setLoading(false)
    }
  }, [allProjects])

  useEffect(() => {
    load()

    const disconnect = connectToGlobalRuns(msg => {
      const runId = msg.runId
      if (!runId) return
      if (msg.type === 'RUN_COMPLETE') {
        if (msg.status === 'FAILED') {
          setRuns(prev => prev.map(r => r.id === runId ? { ...r, status: 'FAILED' as const } : r))
        } else {
          setFadingOut(prev => new Set([...prev, runId]))
          setTimeout(() => {
            setRuns(prev => prev.filter(r => r.id !== runId))
            setFadingOut(prev => { const s = new Set(prev); s.delete(runId); return s })
          }, 3000)
        }
      } else if (msg.type === 'BLOCK_STARTED' && msg.blockId) {
        setRuns(prev => prev.map(r =>
          r.id === runId ? { ...r, status: 'RUNNING' as const, currentBlock: msg.blockId ?? r.currentBlock } : r
        ))
      } else if (msg.type === 'APPROVAL_REQUEST' && msg.blockId) {
        setRuns(prev => prev.map(r =>
          r.id === runId ? { ...r, status: 'PAUSED_FOR_APPROVAL' as const, currentBlock: msg.blockId ?? r.currentBlock } : r
        ))
      } else {
        load()
      }
    })

    // The backend only broadcasts to /topic/runs on RUN_COMPLETE, so newly
    // started runs won't arrive via WebSocket. Poll every 10 s as a safety net
    // so new runs surface within a few seconds even if no WS event fires first.
    const pollInterval = setInterval(load, 10_000)

    return () => {
      disconnect()
      clearInterval(pollInterval)
    }
  }, [load])

  const filtered = runs.filter(r => {
    if (filter === 'RUNNING' && r.status !== 'RUNNING') return false
    if (filter === 'PAUSED_FOR_APPROVAL' && r.status !== 'PAUSED_FOR_APPROVAL') return false
    if (pipelineFilter && r.pipelineName !== pipelineFilter) return false
    return true
  })

  // FAILED rows remain until page refresh — they are not fading out
  const isFailed = (id: string) => runs.find(r => r.id === id)?.status === 'FAILED'

  const handleQuickApprove = useCallback(async (runId: string, blockId: string | null) => {
    if (!blockId) return
    setApprovingIds(prev => new Set([...prev, runId]))
    try {
      await api.submitApproval(runId, { blockId, decision: 'APPROVE' })
      setRuns(prev => prev.map(r => r.id === runId ? { ...r, status: 'RUNNING' as const } : r))
    } catch {
      // ignore — user can navigate to run page for full approval
    } finally {
      setApprovingIds(prev => { const s = new Set(prev); s.delete(runId); return s })
    }
  }, [])

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Активные запуски"
        description={loading ? 'Загрузка...' : `${runs.length} запуск${runs.length !== 1 ? 'ов' : ''} в процессе`}
      />

      {/* Filters */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5">
          {FILTERS.map(f => (
            <button
              key={f.key}
              type="button"
              onClick={() => setFilter(f.key)}
              className={clsx(
                'text-xs px-3 py-1.5 rounded-md border transition-colors',
                filter === f.key
                  ? 'bg-blue-600 border-blue-500 text-white'
                  : 'bg-slate-800 border-slate-700 text-slate-400 hover:text-white'
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
        <select
          value={pipelineFilter}
          onChange={e => setPipelineFilter(e.target.value)}
          className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-slate-300 focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">Все пайплайны</option>
          {pipelines.map(p => <option key={p} value={p}>{p}</option>)}
        </select>
      </div>

      {/* Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-800">
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Пайплайн</th>
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Требование</th>
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Статус</th>
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Текущий блок</th>
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Длительность</th>
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Действия</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {loading ? (
                Array.from({ length: 3 }).map((_, i) => (
                  <tr key={i}>
                    {Array.from({ length: 6 }).map((_, j) => (
                      <td key={j} className="px-4 py-3.5">
                        <div className="h-4 bg-slate-800 rounded animate-pulse w-20" />
                      </td>
                    ))}
                  </tr>
                ))
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-14 text-center">
                    {runs.length === 0 ? (
                      <div className="flex flex-col items-center gap-3">
                        <svg
                          className="w-10 h-10 text-slate-700"
                          fill="none"
                          viewBox="0 0 40 40"
                          aria-hidden="true"
                        >
                          {/* Circle with a play-triangle inside — represents "no active run" */}
                          <circle cx="20" cy="20" r="14" stroke="currentColor" strokeWidth="2.5" opacity="0.5" />
                          <path d="M16 14l10 6-10 6V14z" fill="currentColor" opacity="0.3" />
                        </svg>
                        <p className="text-slate-500 text-sm font-medium">Нет активных запусков</p>
                        <p className="text-slate-600 text-xs">Запустите пайплайн, чтобы увидеть запуски здесь в реальном времени.</p>
                      </div>
                    ) : (
                      <div className="flex flex-col items-center gap-2">
                        <svg
                          className="w-8 h-8 text-slate-700"
                          fill="none"
                          viewBox="0 0 32 32"
                          aria-hidden="true"
                        >
                          {/* Funnel / filter icon */}
                          <path d="M4 7h24l-9 10v8l-6-3V17L4 7z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" opacity="0.5" />
                        </svg>
                        <p className="text-slate-500 text-sm font-medium">Нет запусков по текущему фильтру</p>
                        <p className="text-slate-600 text-xs">Сбросьте фильтр, чтобы увидеть все активные запуски.</p>
                      </div>
                    )}
                  </td>
                </tr>
              ) : (
                filtered.map(run => (
                  <tr
                    key={run.id}
                    className={clsx(
                      'transition-all duration-1000',
                      run.status === 'PAUSED_FOR_APPROVAL' && 'border-l-2 border-amber-500',
                      // Only fade non-FAILED completing rows
                      fadingOut.has(run.id) && !isFailed(run.id) && 'opacity-20'
                    )}
                  >
                    <td className="px-4 py-3.5 text-slate-300 text-sm font-medium">{run.pipelineName}</td>
                    <td className="px-4 py-3.5 max-w-xs">
                      <span className="text-xs text-slate-400 line-clamp-2" title={run.requirement}>
                        {run.requirement
                          ? run.requirement.length > 60 ? run.requirement.slice(0, 60) + '…' : run.requirement
                          : '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3.5">
                      <RunStatusBadge status={run.status} />
                    </td>
                    <td className="px-4 py-3.5">
                      <span className="text-xs text-slate-400" title={run.currentBlock ?? undefined}>{blockIdLabel(run.currentBlock)}</span>
                    </td>
                    <td className="px-4 py-3.5 text-xs text-slate-500 font-mono">
                      <RunDuration startedAt={run.startedAt} completedAt={run.completedAt} live={run.status === 'RUNNING' || run.status === 'PAUSED_FOR_APPROVAL'} />
                    </td>
                    <td className="px-4 py-3.5">
                      <div className="flex items-center gap-2">
                        {run.status === 'FAILED' ? (
                          <Link
                            to={runHref(run.id, pathname)}
                            state={{ from: 'active', backHref: pathname }}
                            className="text-xs text-red-400 hover:text-red-300 transition-colors font-medium"
                          >
                            Ошибка
                          </Link>
                        ) : run.status === 'PAUSED_FOR_APPROVAL' ? (
                          <div className="flex items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => handleQuickApprove(run.id, run.currentBlock)}
                              disabled={approvingIds.has(run.id)}
                              className="flex items-center gap-1 text-xs px-2 py-1 rounded bg-green-800/50 border border-green-700/60 text-green-300 hover:bg-green-800 disabled:opacity-50 transition-colors"
                              title={`Одобрить блок «${blockIdLabel(run.currentBlock)}»`}
                            >
                              {approvingIds.has(run.id)
                                ? <Loader2 className="w-3 h-3 animate-spin" />
                                : <CheckCircle className="w-3 h-3" />}
                              Одобрить
                            </button>
                            <Link
                              to={runHref(run.id, pathname)}
                              state={{ from: 'active', backHref: pathname }}
                              className="flex items-center gap-1 text-xs px-2 py-1 rounded bg-amber-900/40 border border-amber-700/50 text-amber-300 hover:bg-amber-900/60 transition-colors"
                              title="Открыть для полного рассмотрения"
                            >
                              <Bell className="w-3 h-3" />
                              Рассмотреть
                            </Link>
                          </div>
                        ) : (
                          <Link
                            to={runHref(run.id, pathname)}
                            state={{ from: 'active', backHref: pathname }}
                            className="text-xs text-blue-400 hover:text-blue-300 transition-colors"
                          >
                            Подробнее
                          </Link>
                        )}
                        {run.status !== 'FAILED' && (
                          <CancelButton runId={run.id} onCancelled={load} />
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
