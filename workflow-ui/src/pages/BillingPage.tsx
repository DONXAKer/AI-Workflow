import { useState, useEffect, useCallback } from 'react'
import { useSearchParams, Link } from 'react-router-dom'
import { Wallet, Loader2, ArrowLeft, Plus, AlertCircle, CheckCircle2 } from 'lucide-react'
import { api, LedgerEntry } from '../services/api'

const TYPE_LABEL: Record<LedgerEntry['type'], string> = {
  TOPUP: 'Пополнение',
  DEBIT: 'Списание',
  REFUND: 'Возврат',
  ADJUSTMENT: 'Корректировка',
}

/** Customer wallet page — balance, top-up, and the append-only ledger history. */
export default function BillingPage() {
  const [params] = useSearchParams()
  const [balance, setBalance] = useState<number | null>(null)
  const [entries, setEntries] = useState<LedgerEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [amount, setAmount] = useState('10')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [w, l] = await Promise.all([api.getWallet(), api.getLedger(100)])
      setBalance(w.balanceUsd)
      setEntries(l)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить кошелёк')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const topUp = async () => {
    setBusy(true)
    setError(null)
    try {
      const { checkoutUrl } = await api.startCheckout(Number(amount))
      window.location.href = checkoutUrl
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось создать оплату')
      setBusy(false)
    }
  }

  const topup = params.get('topup')

  return (
    <div className="max-w-3xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <Link to="/" className="inline-flex items-center gap-1.5 text-sm text-slate-400 hover:text-slate-200">
        <ArrowLeft className="w-4 h-4" /> К проектам
      </Link>

      {topup === 'success' && (
        <div className="flex items-center gap-2 text-sm text-emerald-300 bg-emerald-950/40 border border-emerald-800 rounded-lg px-3 py-2">
          <CheckCircle2 className="w-4 h-4" />
          Платёж принят. Баланс обновится после подтверждения провайдером.
        </div>
      )}
      {topup === 'cancelled' && (
        <div className="text-sm text-slate-400 bg-slate-900 border border-slate-800 rounded-lg px-3 py-2">
          Пополнение отменено.
        </div>
      )}

      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <div className="flex items-center gap-2 text-slate-400 text-sm">
          <Wallet className="w-4 h-4 text-emerald-400" /> Баланс кошелька
        </div>
        <div className="mt-1 text-3xl font-semibold text-white font-mono">
          ${(balance ?? 0).toFixed(2)}
        </div>
        <div className="mt-4 flex items-end gap-2">
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">Сумма, $</label>
            <input
              type="number" min={1} max={1000} value={amount}
              onChange={e => setAmount(e.target.value)} disabled={busy}
              className="w-32 bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
            />
          </div>
          <button
            type="button" onClick={topUp} disabled={busy || Number(amount) <= 0}
            className="flex items-center gap-2 bg-blue-700 hover:bg-blue-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
            Пополнить
          </button>
        </div>
      </div>

      {error && (
        <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          {error}
        </div>
      )}

      <div>
        <h2 className="text-sm font-semibold text-white mb-2">История операций</h2>
        {loading ? (
          <div className="flex items-center gap-2 text-slate-400 text-sm">
            <Loader2 className="w-4 h-4 animate-spin" /> Загрузка...
          </div>
        ) : entries.length === 0 ? (
          <p className="text-sm text-slate-600">Операций пока нет.</p>
        ) : (
          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="text-xs text-slate-500 uppercase tracking-wide">
                <tr className="border-b border-slate-800">
                  <th className="text-left font-medium px-4 py-2">Тип</th>
                  <th className="text-right font-medium px-4 py-2">Сумма</th>
                  <th className="text-right font-medium px-4 py-2">Баланс после</th>
                  <th className="text-left font-medium px-4 py-2">Описание</th>
                  <th className="text-left font-medium px-4 py-2">Дата</th>
                </tr>
              </thead>
              <tbody>
                {entries.map(e => (
                  <tr key={e.id} className="border-b border-slate-800/60 last:border-0">
                    <td className="px-4 py-2 text-slate-300">{TYPE_LABEL[e.type] ?? e.type}</td>
                    <td className={'px-4 py-2 text-right font-mono ' +
                      (e.amountUsd < 0 ? 'text-red-400' : 'text-emerald-400')}>
                      {e.amountUsd < 0 ? '' : '+'}{e.amountUsd.toFixed(4)}
                    </td>
                    <td className="px-4 py-2 text-right font-mono text-slate-400">
                      {e.balanceAfterUsd.toFixed(2)}
                    </td>
                    <td className="px-4 py-2 text-slate-400 truncate max-w-[16rem]">{e.description ?? ''}</td>
                    <td className="px-4 py-2 text-slate-500 whitespace-nowrap">
                      {new Date(e.createdAt).toLocaleString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
