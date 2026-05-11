import { useState, useEffect, useRef } from 'react'
import { ChevronLeft, ChevronRight, AlertCircle } from 'lucide-react'
import { api } from '../services/api'
import { PipelineRunSummary, RunStatus } from '../types'
import { useRunsFilter } from '../hooks/useRunsFilter'
import RunsTable from '../components/runs/RunsTable'
import FilterBar from '../components/runs/FilterBar'
import PageHeader from '../components/layout/PageHeader'

const PAGE_SIZE = 25

interface RunHistoryPageProps {
  allProjects?: boolean
}

export default function RunHistoryPage({ allProjects = false }: RunHistoryPageProps) {
  const { filters, setFilter, resetFilters } = useRunsFilter()
  const [runs, setRuns] = useState<PipelineRunSummary[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [pipelines, setPipelines] = useState<string[]>([])

  // Local state for the search input — decoupled from the filter that drives
  // the API call so we can debounce without making the input feel laggy.
  const [searchInput, setSearchInput] = useState(filters.search)
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Stable primitive keys to avoid re-fetching on object identity changes.
  const statusKey = filters.status.join(',')
  const { pipelineName, search, from, to, page } = filters

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setFetchError(null)
    api.listRuns({
      status: statusKey ? (statusKey.split(',') as typeof filters.status) : undefined,
      pipelineName: pipelineName || undefined,
      search: search || undefined,
      from: from || undefined,
      to: to || undefined,
      page,
      size: PAGE_SIZE,
      allProjects,
    }).then(data => {
      if (cancelled) return
      setRuns(data.content)
      setTotalElements(data.totalElements)
      setTotalPages(data.totalPages)
    }).catch(e => {
      if (cancelled) return
      setFetchError(e instanceof Error ? e.message : 'Failed to load runs')
      setRuns([])
    }).finally(() => {
      if (cancelled) return
      setLoading(false)
    })
    return () => { cancelled = true }
  }, [statusKey, pipelineName, search, from, to, page])

  useEffect(() => {
    api.listPipelines()
      .then(data => setPipelines(data.map(p => p.pipelineName || p.name).filter(Boolean) as string[]))
      .catch(() => {})
  }, [])

  function toggleStatus(status: RunStatus) {
    const next = filters.status.includes(status)
      ? filters.status.filter(s => s !== status)
      : [...filters.status, status]
    setFilter('status', next)
  }

  // Update local input immediately (keeps the input responsive) and debounce
  // the filter update that triggers an API call. Clears any pending timer on
  // each keystroke so only the final value after 300 ms of inactivity fires.
  function handleSearchChange(value: string) {
    setSearchInput(value)
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    searchDebounceRef.current = setTimeout(() => {
      setFilter('search', value)
      setFilter('page', 0)
    }, 300)
  }

  // Keep local input in sync when filters are reset externally (e.g. the
  // Reset button in FilterBar calls resetFilters which clears filters.search).
  useEffect(() => {
    setSearchInput(filters.search)
  }, [filters.search])

  const hasFilters = filters.status.length > 0 || filters.pipelineName || filters.search || filters.from || filters.to
  const isEmpty = !loading && runs.length === 0 && !fetchError

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="История запусков"
        description={loading ? 'Загрузка...' : `Всего: ${totalElements.toLocaleString()}`}
      />

      <FilterBar
        filters={{ ...filters, search: searchInput }}
        pipelines={pipelines}
        onStatusToggle={toggleStatus}
        onPipelineChange={p => { setFilter('pipelineName', p); setFilter('page', 0) }}
        onSearchChange={handleSearchChange}
        onFromChange={f => { setFilter('from', f); setFilter('page', 0) }}
        onToChange={t => { setFilter('to', t); setFilter('page', 0) }}
        onReset={resetFilters}
      />

      {fetchError && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3 text-sm">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          {fetchError}
        </div>
      )}

      {isEmpty && !hasFilters && !fetchError ? (
        <div className="bg-slate-900 border border-slate-800 rounded-xl px-6 py-16 text-center">
          <p className="text-slate-500 text-sm">Запусков пока нет.</p>
          <p className="text-slate-600 text-xs mt-1">Запустите первый пайплайн на странице «Пайплайны».</p>
        </div>
      ) : !fetchError ? (
        <>
          <RunsTable
            runs={runs}
            loading={loading}
            skeletonRows={6}
            emptyMessage={hasFilters ? 'Нет запусков по текущим фильтрам' : 'Запуски не найдены'}
            emptySubMessage={hasFilters ? 'Попробуйте изменить или сбросить фильтры.' : undefined}
          />

          {totalPages > 1 && (
            <div className="flex items-center justify-between mt-4">
              <p className="text-xs text-slate-500">
                Страница {filters.page + 1} из {totalPages} · {totalElements.toLocaleString()} всего
              </p>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setFilter('page', filters.page - 1)}
                  disabled={filters.page === 0}
                  className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40 hover:bg-slate-700 transition-colors"
                >
                  <ChevronLeft className="w-3.5 h-3.5" />
                  Назад
                </button>
                <button
                  type="button"
                  onClick={() => setFilter('page', filters.page + 1)}
                  disabled={filters.page >= totalPages - 1}
                  className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40 hover:bg-slate-700 transition-colors"
                >
                  Далее
                  <ChevronRight className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          )}
        </>
      ) : null}
    </div>
  )
}
