import { useState, FormEvent } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { UserPlus, AlertCircle, Loader2 } from 'lucide-react'
import { useAuth } from '../context/AuthContext'

/** Self-serve sign-up. On success the backend auto-logs the user in. */
export default function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await register(email, password, displayName || undefined)
      navigate('/onboarding', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось зарегистрироваться')
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-950 px-4">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4 shadow-xl"
      >
        <div className="flex items-center gap-2 mb-2">
          <UserPlus className="w-5 h-5 text-blue-400" />
          <h1 className="text-lg font-semibold text-white">Регистрация</h1>
        </div>

        <div>
          <label htmlFor="displayName" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Имя или компания
          </label>
          <input
            id="displayName"
            type="text"
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
            autoComplete="name"
            disabled={submitting}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          />
        </div>

        <div>
          <label htmlFor="email" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Email
          </label>
          <input
            id="email"
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            autoComplete="email"
            required
            disabled={submitting}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          />
        </div>

        <div>
          <label htmlFor="password" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Пароль
          </label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            autoComplete="new-password"
            required
            minLength={8}
            disabled={submitting}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          />
          <p className="mt-1 text-xs text-slate-500">Минимум 8 символов.</p>
        </div>

        {error && (
          <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={submitting || !email || password.length < 8}
          className="w-full flex items-center justify-center gap-2 bg-blue-700 hover:bg-blue-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
        >
          {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserPlus className="w-4 h-4" />}
          Создать аккаунт
        </button>

        <p className="text-center text-sm text-slate-400">
          Уже есть аккаунт?{' '}
          <Link to="/login" className="text-blue-400 hover:text-blue-300">Войти</Link>
        </p>
      </form>
    </div>
  )
}
