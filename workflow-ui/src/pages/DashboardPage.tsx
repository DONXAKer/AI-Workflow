import { useState, useEffect, useRef, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { AlertCircle } from 'lucide-react'
import { api } from '../services/api'
import { PipelineRunSummary, RunStats } from '../types'
import StatCard from '../components/dashboard/StatCard'
import RunsTable from '../components/runs/RunsTable'
import PageHeader from '../components/layout/PageHeader'

export default function DashboardPage() {
  const [stats, setStats] = useState<RunStats | null>(null)
  const [activeRuns, setActiveRuns] = useState<PipelineRunSummary[]>([])
  const [recentRuns, setRecentRuns] = useState<PipelineRunSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [backendError, setBackendError] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  const fetchData = useCallback(async (showLoading = false) => {
    abortRef.current?.abort()
    const ctrl = new AbortController()
    abortRef.current = ctrl

    if (showLoading) setLoading(true)

    try {
      const [statsData, activeData, recentData] = await Promise.all([
        api.getRunStats(),
        api.listRuns({ status: ['RUNNING', 'PAUSED_FOR_APPROVAL'], size: 5, page: 0 }),
        api.listRuns({ size: 10, page: 0 }),
      ])
      if (ctrl.signal.aborted) return
      setStats(statsData)
      setActiveRuns(activeData.content)
      setRecentRuns(recentData.content)
      setBackendError(false)
    } catch {
      if (!ctrl.signal.aborted) setBackendError(true)
    } finally {
      if (!ctrl.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData(true)
    const interval = setInterval(() => fetchData(false), 15_000)
    return () => {
      clearInterval(interval)
      abortRef.current?.abort()
    }
  }, [fetchData])

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-8">
      <PageHeader title="Главная" description="Обзор запусков пайплайнов" />

      {backendError && (
        <div className="flex items-center gap-2 text-amber-400 bg-amber-950/30 border border-amber-800/50 rounded-lg px-4 py-3 text-sm">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          Не удалось подключиться к серверу. Данные могут быть устаревшими. Повтор каждые 15 сек.
        </div>
      )}

      {/* Stat strip */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          label="Активные"
          value={stats?.activeRuns ?? 0}
          color="blue"
          pulse={(stats?.activeRuns ?? 0) > 0}
          isLoading={loading}
          href="/runs/active"
        />
        <StatCard
          label="Ожидают одобрения"
          value={stats?.awaitingApproval ?? 0}
          color="amber"
          isLoading={loading}
          href="/runs/active?status=PAUSED_FOR_APPROVAL"
        />
        <StatCard
          label="Завершено сегодня"
          value={stats?.completedToday ?? 0}
          color="green"
          isLoading={loading}
          href="/runs/history?status=COMPLETED"
        />
        <StatCard
          label="Ошибки сегодня"
          value={stats?.failedToday ?? 0}
          color="red"
          isLoading={loading}
          href="/runs/history?status=FAILED"
        />
      </div>

      {/* Active runs */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-white">Активные запуски</h2>
          {activeRuns.length > 0 && (
            <Link to="/runs/active" className="text-xs text-blue-400 hover:text-blue-300 transition-colors">
              Все →
            </Link>
          )}
        </div>
        <RunsTable
          runs={activeRuns}
          loading={loading}
          skeletonRows={3}
          liveStatuses
          from="active"
          emptyMessage="Нет активных запусков"
        />
      </div>

      {/* Recent runs */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-white">Последние запуски</h2>
          <Link to="/runs/history" className="text-xs text-blue-400 hover:text-blue-300 transition-colors">
            Вся история →
          </Link>
        </div>
        <RunsTable
          runs={recentRuns}
          loading={loading}
          skeletonRows={5}
          liveStatuses
          emptyMessage="Запусков пока нет. Запустите первый пайплайн."
        />
      </div>
    </div>
  )
}
