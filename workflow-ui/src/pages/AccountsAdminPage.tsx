import { useState, useEffect, useCallback } from 'react'
import { Loader2, AlertCircle, Ban, CheckCircle2 } from 'lucide-react'
import { api, AdminAccount } from '../services/api'
import clsx from 'clsx'

const STATUS_CLS: Record<AdminAccount['status'], string> = {
  ACTIVE: 'bg-emerald-900/40 border-emerald-700/50 text-emerald-300',
  SUSPENDED: 'bg-amber-900/40 border-amber-700/50 text-amber-300',
  CLOSED: 'bg-slate-800 border-slate-700 text-slate-400',
}

/** Platform-staff account console — list every tenant, suspend / re-activate. */
export default function AccountsAdminPage() {
  const [accounts, setAccounts] = useState<AdminAccount[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setAccounts(await api.getAdminAccounts())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить аккаунты')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const toggle = async (a: AdminAccount) => {
    setBusyId(a.id)
    setError(null)
    try {
      if (a.status === 'SUSPENDED') await api.activateAccount(a.id)
      else await api.suspendAccount(a.id)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось изменить статус')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="max-w-5xl mx-auto px-6 py-8 space-y-5">
      <div>
        <h1 className="text-xl font-semibold text-white">Аккаунты</h1>
        <p className="text-sm text-slate-500 mt-0.5">Все клиентские аккаунты платформы</p>
      </div>

      {error && (
        <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-slate-400 text-sm">
          <Loader2 className="w-4 h-4 animate-spin" /> Загрузка...
        </div>
      ) : accounts.length === 0 ? (
        <p className="text-sm text-slate-600">Аккаунтов нет.</p>
      ) : (
        <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="text-xs text-slate-500 uppercase tracking-wide">
              <tr className="border-b border-slate-800">
                <th className="text-left font-medium px-4 py-2">Аккаунт</th>
                <th className="text-left font-medium px-4 py-2">Тариф</th>
                <th className="text-left font-medium px-4 py-2">Статус</th>
                <th className="text-right font-medium px-4 py-2">Баланс</th>
                <th className="text-right font-medium px-4 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {accounts.map(a => (
                <tr key={a.id} className="border-b border-slate-800/60 last:border-0">
                  <td className="px-4 py-2">
                    <div className="text-slate-200">{a.name}</div>
                    <div className="text-xs text-slate-500 font-mono">{a.slug}</div>
                  </td>
                  <td className="px-4 py-2 text-slate-400">{a.tier}</td>
                  <td className="px-4 py-2">
                    <span className={clsx('inline-block px-2 py-0.5 rounded-full border text-[11px]', STATUS_CLS[a.status])}>
                      {a.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-right font-mono text-slate-300">
                    ${a.balanceUsd.toFixed(2)}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {a.status !== 'CLOSED' && (
                      <button
                        type="button"
                        onClick={() => toggle(a)}
                        disabled={busyId === a.id}
                        className={clsx(
                          'inline-flex items-center gap-1.5 text-xs font-medium px-3 py-1.5 rounded-lg border transition-colors disabled:opacity-50',
                          a.status === 'SUSPENDED'
                            ? 'border-emerald-700 text-emerald-300 hover:bg-emerald-900/30'
                            : 'border-amber-700 text-amber-300 hover:bg-amber-900/30'
                        )}
                      >
                        {busyId === a.id
                          ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                          : a.status === 'SUSPENDED'
                            ? <CheckCircle2 className="w-3.5 h-3.5" />
                            : <Ban className="w-3.5 h-3.5" />}
                        {a.status === 'SUSPENDED' ? 'Активировать' : 'Приостановить'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
