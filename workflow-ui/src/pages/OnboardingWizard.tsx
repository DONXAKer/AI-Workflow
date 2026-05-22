import { useState, useEffect, FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Wallet, GitBranch, ListChecks, Rocket, Check, Loader2, AlertCircle } from 'lucide-react'
import { api } from '../services/api'
import { useAuth } from '../context/AuthContext'

type StepNum = 1 | 2 | 3 | 4

const STEPS: { n: StepNum; label: string; Icon: typeof Wallet }[] = [
  { n: 1, label: 'Пополнение', Icon: Wallet },
  { n: 2, label: 'Репозиторий', Icon: GitBranch },
  { n: 3, label: 'Трекер', Icon: ListChecks },
  { n: 4, label: 'Первая задача', Icon: Rocket },
]

/**
 * Post-registration onboarding: top-up wallet -> connect a repo -> tracker note ->
 * finish. Each step is skippable. Finishing marks the account onboarded so the
 * post-login redirect (App.tsx) stops sending the user here.
 */
export default function OnboardingWizard() {
  const navigate = useNavigate()
  const { refresh } = useAuth()
  const [step, setStep] = useState<StepNum>(1)
  const [balance, setBalance] = useState<number | null>(null)
  const [connectedSlug, setConnectedSlug] = useState<string | null>(null)

  const [amount, setAmount] = useState('10')
  const [repoUrl, setRepoUrl] = useState('')
  const [accessToken, setAccessToken] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.getWallet().then(w => setBalance(w.balanceUsd)).catch(() => { /* badge only */ })
  }, [])

  const topUp = async () => {
    setBusy(true); setError(null)
    try {
      const { checkoutUrl } = await api.startCheckout(Number(amount))
      window.location.href = checkoutUrl
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось создать оплату')
      setBusy(false)
    }
  }

  const connectRepo = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      const project = await api.connectRepo({ repoUrl: repoUrl.trim(), accessToken: accessToken.trim() })
      setConnectedSlug(project.slug)
      setStep(3)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось подключить репозиторий')
    } finally {
      setBusy(false)
    }
  }

  const finish = async () => {
    setBusy(true)
    try { await api.markOnboarded() } catch { /* non-blocking */ }
    await refresh()
    navigate(connectedSlug ? `/projects/${connectedSlug}` : '/', { replace: true })
  }

  const inputCls = 'w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm ' +
    'text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50'
  const primaryBtn = 'flex items-center justify-center gap-2 bg-blue-700 hover:bg-blue-600 ' +
    'disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors'
  const ghostBtn = 'text-sm text-slate-400 hover:text-slate-200 px-4 py-2 transition-colors'

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-lg space-y-6">
        <div>
          <h1 className="text-xl font-semibold text-white">Добро пожаловать в AI-Workflow</h1>
          <p className="text-sm text-slate-500 mt-0.5">Несколько шагов до первой задачи</p>
        </div>

        {/* Step rail */}
        <div className="flex items-center gap-2">
          {STEPS.map(({ n, label, Icon }) => (
            <div key={n} className="flex-1 flex flex-col items-center gap-1">
              <div className={
                'w-9 h-9 rounded-full flex items-center justify-center border ' +
                (n < step ? 'bg-emerald-900/40 border-emerald-700 text-emerald-300'
                  : n === step ? 'bg-blue-900/50 border-blue-600 text-blue-300'
                  : 'bg-slate-900 border-slate-700 text-slate-600')
              }>
                {n < step ? <Check className="w-4 h-4" /> : <Icon className="w-4 h-4" />}
              </div>
              <span className={'text-[11px] ' + (n === step ? 'text-slate-200' : 'text-slate-600')}>{label}</span>
            </div>
          ))}
        </div>

        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4 shadow-xl">
          {error && (
            <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              {error}
            </div>
          )}

          {/* Step 1 — top-up */}
          {step === 1 && (
            <>
              <h2 className="text-base font-semibold text-white">Пополните кошелёк</h2>
              <p className="text-sm text-slate-400">
                Запуски оплачиваются из кошелька. Текущий баланс:{' '}
                <span className="font-mono text-emerald-400">${(balance ?? 0).toFixed(2)}</span>.
              </p>
              <div className="flex items-end gap-2">
                <div className="flex-1">
                  <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">Сумма, $</label>
                  <input type="number" min={1} max={1000} value={amount}
                    onChange={e => setAmount(e.target.value)} disabled={busy} className={inputCls} />
                </div>
                <button type="button" onClick={topUp} disabled={busy || Number(amount) <= 0} className={primaryBtn}>
                  {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Wallet className="w-4 h-4" />}
                  Пополнить
                </button>
              </div>
              <div className="flex justify-end">
                <button type="button" onClick={() => setStep(2)} disabled={busy} className={ghostBtn}>Пропустить →</button>
              </div>
            </>
          )}

          {/* Step 2 — connect repo */}
          {step === 2 && (
            <form onSubmit={connectRepo} className="space-y-4">
              <h2 className="text-base font-semibold text-white">Подключите репозиторий</h2>
              <p className="text-sm text-slate-400">HTTPS-ссылка на GitHub/GitLab + токен доступа.</p>
              <div>
                <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">URL репозитория</label>
                <input type="url" value={repoUrl} onChange={e => setRepoUrl(e.target.value)}
                  placeholder="https://github.com/owner/repo" required disabled={busy} className={inputCls} />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">Токен доступа</label>
                <input type="password" value={accessToken} onChange={e => setAccessToken(e.target.value)}
                  autoComplete="off" required disabled={busy} className={inputCls} />
              </div>
              <div className="flex justify-between">
                <button type="button" onClick={() => setStep(3)} disabled={busy} className={ghostBtn}>Пропустить →</button>
                <button type="submit" disabled={busy || !repoUrl || !accessToken} className={primaryBtn}>
                  {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <GitBranch className="w-4 h-4" />}
                  Подключить
                </button>
              </div>
            </form>
          )}

          {/* Step 3 — tracker */}
          {step === 3 && (
            <>
              <h2 className="text-base font-semibold text-white">Трекер задач</h2>
              <p className="text-sm text-slate-400">
                Для подключённого репозитория трекер issue (GitHub/GitLab) настраивается автоматически.
                Отдельный трекер (YouTrack и др.) можно добавить позже в настройках проекта.
              </p>
              <div className="flex justify-end">
                <button type="button" onClick={() => setStep(4)} className={primaryBtn}>Далее</button>
              </div>
            </>
          )}

          {/* Step 4 — done */}
          {step === 4 && (
            <>
              <h2 className="text-base font-semibold text-white">Всё готово</h2>
              <p className="text-sm text-slate-400">
                {connectedSlug
                  ? 'Репозиторий подключён. Откройте проект и поставьте первую задачу через Smart Start.'
                  : 'Можно подключить репозиторий позже на странице проектов.'}
              </p>
              <div className="flex justify-end">
                <button type="button" onClick={finish} disabled={busy} className={primaryBtn}>
                  {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Rocket className="w-4 h-4" />}
                  {connectedSlug ? 'Открыть проект' : 'К проектам'}
                </button>
              </div>
            </>
          )}
        </div>

        <div className="text-center">
          <button type="button" onClick={finish} disabled={busy} className={ghostBtn}>
            Пропустить онбординг
          </button>
        </div>
      </div>
    </div>
  )
}
