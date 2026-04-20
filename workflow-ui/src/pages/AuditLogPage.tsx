import { useState, useEffect, useCallback } from 'react'
import { FileSearch, Filter, Loader2, AlertCircle, ChevronLeft, ChevronRight } from 'lucide-react'
import { api } from '../services/api'
import { AuditEntry, AuditFilters } from '../types'
import PageHeader from '../components/layout/PageHeader'
import ProjectScopeBadge from '../components/ProjectScopeBadge'
import clsx from 'clsx'

const PAGE_SIZE = 50

function OutcomeBadge({ outcome }: { outcome: string }) {
  return (
    <span
      className={clsx(
        'inline-flex items-center px-2 py-0.5 rounded text-xs font-medium',
        outcome === 'SUCCESS'
          ? 'bg-green-900/40 text-green-300 border border-green-800/60'
          : 'bg-red-900/40 text-red-300 border border-red-800/60'
      )}
    >
      {outcome}
    </span>
  )
}

export default function AuditLogPage() {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filters, setFilters] = useState<AuditFilters>({ page: 0, size: PAGE_SIZE })
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)

  const load = useCallback(async (f: AuditFilters) => {
    setLoading(true)
    setError(null)
    try {
      const page = await api.listAudit(f)
      setEntries(page.content)
      setTotalPages(page.totalPages)
      setTotalElements(page.totalElements)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить журнал')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load(filters) }, [load, filters])

  const updateFilter = (patch: Partial<AuditFilters>) => {
    setFilters(prev => ({ ...prev, ...patch, page: 0 }))
  }

  const goToPage = (page: number) => setFilters(prev => ({ ...prev, page }))

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Журнал действий"
        breadcrumbs={[{ label: 'Настройки' }, { label: 'Audit' }]}
        actions={<ProjectScopeBadge />}
      />

      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
        <div className="flex items-center gap-2 mb-3">
          <Filter className="w-4 h-4 text-slate-400" />
          <span className="text-xs font-medium text-slate-400 uppercase tracking-wide">Фильтры</span>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <input
            placeholder="Actor"
            aria-label="Фильтр: актор"
            value={filters.actor ?? ''}
            onChange={e => updateFilter({ actor: e.target.value || undefined })}
            className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            placeholder="Action"
            aria-label="Фильтр: действие"
            value={filters.action ?? ''}
            onChange={e => updateFilter({ action: e.target.value || undefined })}
            className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            type="date"
            aria-label="Фильтр: от даты"
            value={filters.from ?? ''}
            onChange={e => updateFilter({ from: e.target.value || undefined })}
            className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            type="date"
            aria-label="Фильтр: до даты"
            value={filters.to ?? ''}
            onChange={e => updateFilter({ to: e.target.value || undefined })}
            className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          {error}
        </div>
      )}

      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <FileSearch className="w-4 h-4 text-slate-400" />
            <span className="text-sm font-medium text-slate-300">
              {totalElements.toLocaleString('ru')} записей
            </span>
          </div>
          {loading && <Loader2 className="w-4 h-4 animate-spin text-slate-500" />}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-slate-950/50 text-xs text-slate-400 uppercase tracking-wide">
              <tr>
                <th className="text-left px-4 py-2 font-medium">Timestamp</th>
                <th className="text-left px-4 py-2 font-medium">Actor</th>
                <th className="text-left px-4 py-2 font-medium">Action</th>
                <th className="text-left px-4 py-2 font-medium">Target</th>
                <th className="text-left px-4 py-2 font-medium">Outcome</th>
                <th className="text-left px-4 py-2 font-medium">IP</th>
              </tr>
            </thead>
            <tbody>
              {entries.length === 0 && !loading && (
                <tr>
                  <td colSpan={6} className="text-center py-8 text-slate-500">
                    Нет записей, соответствующих фильтрам
                  </td>
                </tr>
              )}
              {entries.map(e => (
                <tr key={e.id} className="border-t border-slate-800/60 hover:bg-slate-800/30">
                  <td className="px-4 py-2 text-slate-400 font-mono text-xs">
                    {new Date(e.timestamp).toLocaleString()}
                  </td>
                  <td className="px-4 py-2 text-slate-200">{e.actor}</td>
                  <td className="px-4 py-2 text-blue-300 font-mono text-xs">{e.action}</td>
                  <td className="px-4 py-2 text-slate-400 font-mono text-xs">
                    {e.targetType ? `${e.targetType}:${e.targetId ?? '—'}` : '—'}
                  </td>
                  <td className="px-4 py-2">
                    <OutcomeBadge outcome={e.outcome} />
                  </td>
                  <td className="px-4 py-2 text-slate-500 font-mono text-xs">{e.remoteAddr ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {totalPages > 1 && (
          <div className="px-4 py-3 border-t border-slate-800 flex items-center justify-between">
            <span className="text-xs text-slate-500">
              Страница {(filters.page ?? 0) + 1} из {totalPages}
            </span>
            <div className="flex gap-1">
              <button
                type="button"
                onClick={() => goToPage(Math.max(0, (filters.page ?? 0) - 1))}
                disabled={(filters.page ?? 0) === 0}
                className="p-1.5 rounded-md text-slate-400 hover:text-white hover:bg-slate-800 disabled:opacity-40"
                aria-label="Предыдущая страница"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button
                type="button"
                onClick={() => goToPage(Math.min(totalPages - 1, (filters.page ?? 0) + 1))}
                disabled={(filters.page ?? 0) >= totalPages - 1}
                className="p-1.5 rounded-md text-slate-400 hover:text-white hover:bg-slate-800 disabled:opacity-40"
                aria-label="Следующая страница"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
