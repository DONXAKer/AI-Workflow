import { useState, useEffect, useCallback } from 'react'
import { Power, AlertTriangle, Loader2, AlertCircle } from 'lucide-react'
import { api } from '../services/api'
import { KillSwitchState } from '../types'
import PageHeader from '../components/layout/PageHeader'
import ProjectScopeBadge from '../components/ProjectScopeBadge'
import clsx from 'clsx'

export default function KillSwitchPage() {
  const [state, setState] = useState<KillSwitchState | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [cancelActive, setCancelActive] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setState(await api.getKillSwitch())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить статус')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const activate = async () => {
    if (!reason.trim()) {
      setError('Причина обязательна для активации')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      setState(await api.toggleKillSwitch({ active: true, reason: reason.trim(), cancelActive }))
      setReason('')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось активировать')
    } finally {
      setSubmitting(false)
    }
  }

  const deactivate = async () => {
    setSubmitting(true)
    setError(null)
    try {
      setState(await api.toggleKillSwitch({ active: false }))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось деактивировать')
    } finally {
      setSubmitting(false)
    }
  }

  const isActive = state?.active ?? false

  return (
    <div className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Kill Switch"
        breadcrumbs={[{ label: 'Настройки' }, { label: 'Kill Switch' }]}
        actions={<ProjectScopeBadge />}
      />

      <div
        className={clsx(
          'rounded-xl border-2 p-6',
          isActive
            ? 'border-red-500 bg-red-950/60 shadow-lg shadow-red-900/30'
            : 'border-slate-800 bg-slate-900'
        )}
      >
        <div className="flex items-start gap-3 mb-4">
          <Power className={clsx('w-6 h-6 flex-shrink-0', isActive ? 'text-red-300 animate-pulse' : 'text-slate-500')} />
          <div className="flex-1">
            <h2 className={clsx('text-lg font-semibold mb-1', isActive ? 'text-red-200' : 'text-white')}>
              {isActive ? 'Kill switch АКТИВЕН' : 'Kill switch не активен'}
            </h2>
            <p className={clsx('text-sm', isActive ? 'text-red-200/80' : 'text-slate-400')}>
              {isActive
                ? 'Новые pipeline-запуски заблокированы. Снимите kill switch чтобы возобновить работу.'
                : 'Активация блокирует все новые запуски. Используйте в инцидентах — освобождение системы от нагрузки без отключения backend.'}
            </p>
          </div>
        </div>

        {loading && <Loader2 className="w-5 h-5 animate-spin text-slate-500" />}

        {isActive && state && (
          <div className="bg-slate-950/70 border border-red-700/60 rounded-lg p-3 mb-4 space-y-1 text-sm">
            <p><span className="text-red-300/70">Причина:</span> <span className="text-white">{state.reason || '—'}</span></p>
            <p><span className="text-red-300/70">Активирован:</span> <span className="text-white">{state.activatedBy || '—'}</span></p>
            {state.activatedAt && (
              <p><span className="text-red-300/70">Время:</span> <span className="text-white font-mono text-xs">{new Date(state.activatedAt).toLocaleString()}</span></p>
            )}
          </div>
        )}

        {error && (
          <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2 mb-3">
            <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
            {error}
          </div>
        )}

        {!isActive ? (
          <div className="space-y-3">
            <div>
              <label htmlFor="reason" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Причина активации
              </label>
              <textarea
                id="reason"
                value={reason}
                onChange={e => setReason(e.target.value)}
                disabled={submitting}
                rows={3}
                placeholder="Например: инцидент в проде, заморозка на время миграции..."
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-red-500 disabled:opacity-50"
              />
            </div>
            <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
              <input
                type="checkbox"
                checked={cancelActive}
                onChange={e => setCancelActive(e.target.checked)}
                disabled={submitting}
                className="rounded bg-slate-800 border-slate-600"
              />
              Также прервать все активные запуски
            </label>
            <button
              type="button"
              onClick={activate}
              disabled={submitting || !reason.trim()}
              className="flex items-center gap-2 bg-red-700 hover:bg-red-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
            >
              {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <AlertTriangle className="w-4 h-4" />}
              Активировать kill switch
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={deactivate}
            disabled={submitting}
            className="flex items-center gap-2 bg-green-700 hover:bg-green-600 disabled:bg-slate-700 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Power className="w-4 h-4" />}
            Деактивировать
          </button>
        )}
      </div>
    </div>
  )
}
