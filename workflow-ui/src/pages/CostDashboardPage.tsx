import { useState, useEffect, useCallback } from 'react'
import { DollarSign, Activity, TrendingUp, Loader2, AlertCircle } from 'lucide-react'
import { api } from '../services/api'
import { CostSummary } from '../types'
import PageHeader from '../components/layout/PageHeader'
import ProjectScopeBadge from '../components/ProjectScopeBadge'

function formatNumber(n: number): string {
  return n.toLocaleString('ru')
}

function formatUsd(n: number): string {
  return `$${n.toFixed(4)}`
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function daysAgoIso(days: number): string {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() - days)
  return d.toISOString().slice(0, 10)
}

function Card({ icon: Icon, label, value, sub }: {
  icon: React.ComponentType<{ className?: string }>
  label: string
  value: string
  sub?: string
}) {
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
      <div className="flex items-center gap-2 text-slate-400 mb-1">
        <Icon className="w-4 h-4" />
        <span className="text-xs uppercase tracking-wide">{label}</span>
      </div>
      <p className="text-2xl font-semibold text-white tabular-nums">{value}</p>
      {sub && <p className="text-xs text-slate-500 mt-1">{sub}</p>}
    </div>
  )
}

export default function CostDashboardPage() {
  const [from, setFrom] = useState(daysAgoIso(30))
  const [to, setTo] = useState(todayIso())
  const [summary, setSummary] = useState<CostSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setSummary(await api.getCostSummary(from, to))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить статистику')
    } finally {
      setLoading(false)
    }
  }, [from, to])

  useEffect(() => { load() }, [load])

  const maxCost = summary && summary.byModel.length > 0
    ? Math.max(...summary.byModel.map(m => m.costUsd))
    : 0

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Стоимость LLM"
        breadcrumbs={[{ label: 'Cost' }]}
        actions={<ProjectScopeBadge />}
      />

      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-sm text-slate-400">
            <span>От</span>
            <input
              type="date"
              aria-label="От даты"
              value={from}
              onChange={e => setFrom(e.target.value)}
              className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </label>
          <label className="flex items-center gap-2 text-sm text-slate-400">
            <span>До</span>
            <input
              type="date"
              aria-label="До даты"
              value={to}
              onChange={e => setTo(e.target.value)}
              className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </label>
          {loading && <Loader2 className="w-4 h-4 animate-spin text-slate-500 ml-2" />}
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          {error}
        </div>
      )}

      {summary && (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <Card
              icon={DollarSign}
              label="Всего потрачено"
              value={formatUsd(summary.totalCostUsd)}
              sub={`за ${new Date(summary.from).toLocaleDateString()} — ${new Date(summary.to).toLocaleDateString()}`}
            />
            <Card icon={Activity} label="LLM-вызовов" value={formatNumber(summary.totalCalls)} />
            <Card icon={TrendingUp} label="Tokens in" value={formatNumber(summary.totalTokensIn)} />
            <Card icon={TrendingUp} label="Tokens out" value={formatNumber(summary.totalTokensOut)} />
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-800 flex items-center justify-between">
              <h2 className="text-sm font-medium text-slate-300">По моделям</h2>
              <span className="text-xs text-slate-500">{summary.byModel.length} моделей</span>
            </div>
            {summary.byModel.length === 0 ? (
              <div className="text-center py-12 text-slate-500">Нет вызовов за выбранный период</div>
            ) : (
              <table className="w-full text-sm">
                <thead className="bg-slate-950/50 text-xs text-slate-400 uppercase tracking-wide">
                  <tr>
                    <th className="text-left px-4 py-2 font-medium">Модель</th>
                    <th className="text-right px-4 py-2 font-medium">Вызовов</th>
                    <th className="text-right px-4 py-2 font-medium">Tokens in</th>
                    <th className="text-right px-4 py-2 font-medium">Tokens out</th>
                    <th className="text-right px-4 py-2 font-medium">Стоимость</th>
                    <th className="text-left px-4 py-2 font-medium w-1/4">Доля</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.byModel.map(m => {
                    const pct = maxCost > 0 ? (m.costUsd / maxCost) * 100 : 0
                    return (
                      <tr key={m.model} className="border-t border-slate-800/60 hover:bg-slate-800/30">
                        <td className="px-4 py-2 text-slate-200 font-mono text-xs">{m.model}</td>
                        <td className="px-4 py-2 text-slate-300 text-right tabular-nums">{formatNumber(m.calls)}</td>
                        <td className="px-4 py-2 text-slate-300 text-right tabular-nums">{formatNumber(m.tokensIn)}</td>
                        <td className="px-4 py-2 text-slate-300 text-right tabular-nums">{formatNumber(m.tokensOut)}</td>
                        <td className="px-4 py-2 text-white text-right tabular-nums font-medium">{formatUsd(m.costUsd)}</td>
                        <td className="px-4 py-2">
                          <div className="h-2 bg-slate-800 rounded-full overflow-hidden">
                            <div
                              className="h-full bg-blue-500"
                              style={{ width: `${pct}%` }}
                            />
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </div>
  )
}
