import { useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { Copy, Check } from 'lucide-react'
import clsx from 'clsx'
import { PipelineRunSummary } from '../../types'
import RunStatusBadge from './RunStatusBadge'
import RunDuration from './RunDuration'
import { useRelativeTime } from '../../hooks/useRelativeTime'
import { runHref } from '../../utils/runHref'

function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(id)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="group flex items-center gap-1 font-mono text-xs text-slate-400 hover:text-slate-200 transition-colors"
      title={id}
    >
      {id.slice(0, 8)}
      {copied
        ? <Check className="w-3 h-3 text-green-400" />
        : <Copy className="w-3 h-3 opacity-0 group-hover:opacity-100 transition-opacity" />
      }
    </button>
  )
}

function RelativeTime({ dateStr }: { dateStr: string }) {
  const rel = useRelativeTime(dateStr)
  return <span title={new Date(dateStr).toLocaleString()}>{rel}</span>
}

function SkeletonRow({ cols }: { cols: number }) {
  const widths = [60, 80, 140, 80, 60, 50, 40]
  return (
    <tr>
      {Array.from({ length: cols }).map((_, i) => (
        <td key={i} className="px-4 py-3.5">
          <div
            className="h-4 bg-slate-800 rounded animate-pulse"
            style={{ width: `${widths[i] ?? 60}px` }}
          />
        </td>
      ))}
    </tr>
  )
}

interface Props {
  runs: PipelineRunSummary[]
  loading?: boolean
  skeletonRows?: number
  showPipeline?: boolean
  liveStatuses?: boolean
  emptyMessage?: string
  emptySubMessage?: string
  from?: string
}

export default function RunsTable({
  runs,
  loading,
  skeletonRows = 6,
  showPipeline = true,
  liveStatuses = false,
  emptyMessage = 'Запуски не найдены',
  emptySubMessage = 'Запуски появятся здесь после запуска пайплайна.',
  from = 'history',
}: Props) {
  const colCount = showPipeline ? 8 : 7
  const navigate = useNavigate()
  const location = useLocation()

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-800">
              <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">ID</th>
              {showPipeline && (
                <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Пайплайн</th>
              )}
              <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Требование</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Статус</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Начало</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Длительность</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">Блоки</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-800/60">
            {loading ? (
              Array.from({ length: skeletonRows }).map((_, i) => (
                <SkeletonRow key={i} cols={colCount} />
              ))
            ) : runs.length === 0 ? (
              <tr>
                <td colSpan={colCount} className="px-4 py-14 text-center">
                  <div className="flex flex-col items-center gap-3">
                    <svg
                      className="w-10 h-10 text-slate-700"
                      fill="none"
                      viewBox="0 0 40 40"
                      aria-hidden="true"
                    >
                      {/* Stack of three horizontal bars representing an empty list */}
                      <rect x="6" y="8" width="28" height="5" rx="2.5" fill="currentColor" opacity="0.5" />
                      <rect x="6" y="17" width="20" height="5" rx="2.5" fill="currentColor" opacity="0.3" />
                      <rect x="6" y="26" width="24" height="5" rx="2.5" fill="currentColor" opacity="0.2" />
                    </svg>
                    <p className="text-slate-500 text-sm font-medium">{emptyMessage}</p>
                    <p className="text-slate-600 text-xs">{emptySubMessage}</p>
                  </div>
                </td>
              </tr>
            ) : (
              runs.map(run => (
                <tr
                  key={run.id}
                  onClick={() => navigate(runHref(run.id, location.pathname), { state: { from, backHref: location.pathname + location.search } })}
                  className={clsx(
                    'transition-colors hover:bg-slate-800/30 cursor-pointer',
                    run.status === 'PAUSED_FOR_APPROVAL' && 'border-l-2 border-amber-500'
                  )}
                >
                  <td className="px-4 py-3.5">
                    <CopyId id={run.id} />
                  </td>
                  {showPipeline && (
                    <td className="px-4 py-3.5">
                      <span className="text-slate-300 text-xs font-medium">{run.pipelineName}</span>
                    </td>
                  )}
                  <td className="px-4 py-3.5 max-w-xs overflow-hidden">
                    <span className="text-xs text-slate-400 line-clamp-2 break-all" title={run.requirement}>
                      {run.requirement
                        ? run.requirement.length > 80
                          ? run.requirement.slice(0, 80) + '…'
                          : run.requirement
                        : '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3.5">
                    <RunStatusBadge status={run.status} />
                  </td>
                  <td className="px-4 py-3.5 text-xs text-slate-500 whitespace-nowrap">
                    <RelativeTime dateStr={run.startedAt} />
                  </td>
                  <td className="px-4 py-3.5 text-xs text-slate-500 font-mono whitespace-nowrap">
                    <RunDuration
                      startedAt={run.startedAt}
                      completedAt={run.completedAt}
                      live={liveStatuses && (run.status === 'RUNNING' || run.status === 'PAUSED_FOR_APPROVAL')}
                    />
                  </td>
                  <td className="px-4 py-3.5 text-xs text-slate-500 font-mono whitespace-nowrap">
                    {run.blockCount ?? '—'}
                  </td>
                  <td className="px-4 py-3.5">
                    <Link
                      to={runHref(run.id, location.pathname)}
                      state={{ from }}
                      onClick={e => e.stopPropagation()}
                      className="text-xs text-blue-400 hover:text-blue-300 transition-colors whitespace-nowrap"
                    >
                      →
                    </Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
