import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, Link } from 'react-router-dom'
import { Loader2, CheckCircle2, XCircle, Hand, ExternalLink, Settings2, ArrowLeft, Wallet } from 'lucide-react'
import { api } from '../services/api'
import { PipelineRun } from '../types'
import { blockIdLabel } from '../utils/blockLabels'
import RunPage from './RunPage'

const ADVANCED_KEY = 'simpleRun.advanced'

/** A merge-request / pull-request URL hiding in any block output. */
function findMrUrl(run: PipelineRun): string | null {
  const scan = (node: unknown): string | null => {
    if (typeof node === 'string') {
      return /^https?:\/\/\S+(\/pull\/|\/pulls\/|\/-\/merge_requests\/|\/merge_requests\/)/i.test(node)
        ? node : null
    }
    if (Array.isArray(node)) {
      for (const v of node) { const u = scan(v); if (u) return u }
      return null
    }
    if (node && typeof node === 'object') {
      for (const v of Object.values(node)) { const u = scan(v); if (u) return u }
    }
    return null
  }
  for (const out of run.outputs ?? []) {
    let parsed: unknown
    try { parsed = JSON.parse(out.outputJson) } catch { continue }
    const u = scan(parsed)
    if (u) return u
  }
  return null
}

function isWalletError(error: string | null): boolean {
  if (!error) return false
  return /wallet|budget|balance|кошел|баланс/i.test(error)
}

/**
 * Customer-facing run view: plain-language status, the resulting merge request, and the
 * amount billed. An "Advanced" toggle (persisted) swaps in the untouched engineer
 * {@link RunPage} — with a thin bar to switch back.
 */
export default function SimpleRunPage() {
  const { runId } = useParams<{ runId: string }>()
  const [advanced, setAdvanced] = useState<boolean>(() => localStorage.getItem(ADVANCED_KEY) === '1')
  const [run, setRun] = useState<PipelineRun | null>(null)
  const [billedUsd, setBilledUsd] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const timer = useRef<number | null>(null)

  const setAdvancedPersisted = (v: boolean) => {
    localStorage.setItem(ADVANCED_KEY, v ? '1' : '0')
    setAdvanced(v)
  }

  const load = useCallback(async () => {
    if (!runId) return
    try {
      const r = await api.getRun(runId)
      setRun(r)
      try {
        const c = await api.getRunCost(runId)
        setBilledUsd(c.billedCostUsd)
      } catch { /* cost is decoration */ }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить запуск')
    }
  }, [runId])

  useEffect(() => {
    if (advanced) return
    load()
    timer.current = window.setInterval(load, 4000)
    return () => { if (timer.current) window.clearInterval(timer.current) }
  }, [advanced, load])

  // Stop polling once the run reaches a terminal state.
  useEffect(() => {
    if (run && (run.status === 'COMPLETED' || run.status === 'FAILED') && timer.current) {
      window.clearInterval(timer.current)
      timer.current = null
    }
  }, [run])

  if (advanced) {
    return (
      <div>
        <div className="flex items-center justify-between px-4 py-2 bg-slate-900 border-b border-slate-800">
          <span className="text-xs text-slate-500">Подробный (инженерный) вид</span>
          <button
            type="button"
            onClick={() => setAdvancedPersisted(false)}
            className="inline-flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300"
          >
            <ArrowLeft className="w-3.5 h-3.5" /> Простой вид
          </button>
        </div>
        <RunPage />
      </div>
    )
  }

  const mrUrl = run ? findMrUrl(run) : null
  const status = run?.status

  return (
    <div className="max-w-xl mx-auto px-4 py-10 space-y-6">
      <Link to="/" className="inline-flex items-center gap-1.5 text-sm text-slate-400 hover:text-slate-200">
        <ArrowLeft className="w-4 h-4" /> К проектам
      </Link>

      {error && (
        <div className="text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">{error}</div>
      )}

      {!run ? (
        <div className="flex items-center gap-2 text-slate-400 text-sm">
          <Loader2 className="w-4 h-4 animate-spin" /> Загрузка...
        </div>
      ) : (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-5 shadow-xl">
          <div>
            <p className="text-xs text-slate-500 uppercase tracking-wide">Задача</p>
            <p className="text-sm text-slate-200 mt-0.5">{run.requirement || run.pipelineName}</p>
          </div>

          {/* Plain-language status */}
          {status === 'RUNNING' && (
            <div className="flex items-start gap-3">
              <Loader2 className="w-6 h-6 text-blue-400 animate-spin flex-shrink-0" />
              <div>
                <p className="text-base font-medium text-white">Работаю над задачей…</p>
                {run.currentBlock && (
                  <p className="text-sm text-slate-400 mt-0.5">Сейчас: {blockIdLabel(run.currentBlock)}</p>
                )}
              </div>
            </div>
          )}
          {status === 'PAUSED_FOR_APPROVAL' && (
            <div className="flex items-start gap-3">
              <Hand className="w-6 h-6 text-amber-400 flex-shrink-0" />
              <div>
                <p className="text-base font-medium text-white">Жду вашего подтверждения</p>
                <button
                  type="button"
                  onClick={() => setAdvancedPersisted(true)}
                  className="text-sm text-blue-400 hover:text-blue-300 mt-0.5"
                >
                  Открыть подробный вид, чтобы подтвердить →
                </button>
              </div>
            </div>
          )}
          {status === 'COMPLETED' && (
            <div className="flex items-start gap-3">
              <CheckCircle2 className="w-6 h-6 text-emerald-400 flex-shrink-0" />
              <p className="text-base font-medium text-white">Готово</p>
            </div>
          )}
          {status === 'FAILED' && (
            <div className="flex items-start gap-3">
              <XCircle className="w-6 h-6 text-red-400 flex-shrink-0" />
              <div>
                <p className="text-base font-medium text-white">Остановлено</p>
                {isWalletError(run.error) ? (
                  <p className="text-sm text-slate-400 mt-0.5">
                    Закончились средства на кошельке.{' '}
                    <Link to="/billing" className="text-blue-400 hover:text-blue-300">Пополнить →</Link>
                  </p>
                ) : run.error ? (
                  <p className="text-sm text-slate-400 mt-0.5">{run.error}</p>
                ) : null}
              </div>
            </div>
          )}

          {/* Merge request */}
          {mrUrl && (
            <a
              href={mrUrl} target="_blank" rel="noopener noreferrer"
              className="flex items-center justify-center gap-2 bg-blue-700 hover:bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
            >
              <ExternalLink className="w-4 h-4" /> Открыть merge request
            </a>
          )}

          {/* Cost */}
          <div className="flex items-center gap-1.5 text-sm text-slate-400 border-t border-slate-800 pt-4">
            <Wallet className="w-4 h-4 text-slate-500" />
            Списано: <span className="font-mono text-slate-300">${(billedUsd ?? 0).toFixed(2)}</span>
          </div>

          <div className="flex justify-end">
            <button
              type="button"
              onClick={() => setAdvancedPersisted(true)}
              className="inline-flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-300"
            >
              <Settings2 className="w-3.5 h-3.5" /> Подробный вид
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
