import { X } from 'lucide-react'
import clsx from 'clsx'
import { RunStatus } from '../../types'
import { FilterState } from '../../hooks/useRunsFilter'

const ALL_STATUSES: RunStatus[] = ['PENDING', 'RUNNING', 'PAUSED_FOR_APPROVAL', 'COMPLETED', 'FAILED']
const STATUS_LABELS: Record<RunStatus, string> = {
  PENDING: 'Ожидание',
  RUNNING: 'Выполняется',
  PAUSED_FOR_APPROVAL: 'Одобрение',
  COMPLETED: 'Завершён',
  FAILED: 'Ошибка',
}

interface Props {
  filters: FilterState
  pipelines: string[]
  onStatusToggle: (status: RunStatus) => void
  onPipelineChange: (pipeline: string) => void
  onSearchChange: (search: string) => void
  onFromChange: (from: string) => void
  onToChange: (to: string) => void
  onReset: () => void
}

export default function FilterBar({
  filters, pipelines,
  onStatusToggle, onPipelineChange, onSearchChange,
  onFromChange, onToChange, onReset,
}: Props) {
  const hasFilters = filters.status.length > 0 || filters.pipelineName || filters.search || filters.from || filters.to

  return (
    <div className="flex flex-wrap items-center gap-2.5 bg-slate-900 border border-slate-800 rounded-xl px-4 py-3 mb-4">
      {/* Status pills */}
      <div className="flex items-center gap-1.5 flex-wrap">
        {ALL_STATUSES.map(s => (
          <button
            key={s}
            type="button"
            onClick={() => onStatusToggle(s)}
            className={clsx(
              'text-xs px-2.5 py-1 rounded-full border transition-colors',
              filters.status.includes(s)
                ? 'bg-blue-600 border-blue-500 text-white'
                : 'bg-slate-800 border-slate-700 text-slate-400 hover:text-white hover:border-slate-600'
            )}
          >
            {STATUS_LABELS[s]}
          </button>
        ))}
      </div>

      <div className="w-px h-5 bg-slate-700 hidden sm:block" />

      {/* Pipeline */}
      <select
        value={filters.pipelineName}
        onChange={e => onPipelineChange(e.target.value)}
        className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1 text-xs text-slate-300 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        <option value="">Все пайплайны</option>
        {pipelines.map(p => <option key={p} value={p}>{p}</option>)}
      </select>

      {/* Search */}
      <input
        type="text"
        value={filters.search}
        onChange={e => onSearchChange(e.target.value)}
        placeholder="Поиск по требованию..."
        aria-label="Поиск по тексту требования"
        className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1 text-xs text-slate-300 placeholder-slate-600 focus:outline-none focus:ring-1 focus:ring-blue-500 w-44"
      />

      {/* Date range — color-scheme:dark makes the native calendar picker use a dark theme */}
      <span className="text-xs text-slate-400">С</span>
      <input
        type="date"
        value={filters.from}
        onChange={e => onFromChange(e.target.value)}
        aria-label="From date"
        style={{ colorScheme: 'dark' }}
        className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1 text-xs text-slate-300 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
      <span className="text-xs text-slate-400">По</span>
      <input
        type="date"
        value={filters.to}
        onChange={e => onToChange(e.target.value)}
        aria-label="To date"
        style={{ colorScheme: 'dark' }}
        className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1 text-xs text-slate-300 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />

      {hasFilters && (
        <button
          type="button"
          onClick={onReset}
          className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300 transition-colors ml-1"
        >
          <X className="w-3 h-3" />
          Сбросить
        </button>
      )}
    </div>
  )
}
