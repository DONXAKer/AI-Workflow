import { useState, FormEvent } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { GitBranch, AlertCircle, Loader2, ArrowLeft } from 'lucide-react'
import { api } from '../services/api'

/**
 * Self-serve "connect a repository" form. Creates an account-scoped project backed by a
 * GitHub/GitLab repo — each run then clones it into an isolated sandbox.
 */
export default function ConnectRepoPage() {
  const navigate = useNavigate()
  const [repoUrl, setRepoUrl] = useState('')
  const [accessToken, setAccessToken] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [defaultBranch, setDefaultBranch] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const project = await api.connectRepo({
        repoUrl: repoUrl.trim(),
        accessToken: accessToken.trim(),
        displayName: displayName.trim() || undefined,
        defaultBranch: defaultBranch.trim() || undefined,
      })
      navigate(`/projects/${project.slug}`, { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось подключить репозиторий')
      setSubmitting(false)
    }
  }

  return (
    <div className="max-w-xl mx-auto px-4 py-8">
      <Link to="/" className="inline-flex items-center gap-1.5 text-sm text-slate-400 hover:text-slate-200 mb-4">
        <ArrowLeft className="w-4 h-4" /> К проектам
      </Link>

      <form
        onSubmit={handleSubmit}
        className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4 shadow-xl"
      >
        <div className="flex items-center gap-2 mb-1">
          <GitBranch className="w-5 h-5 text-blue-400" />
          <h1 className="text-lg font-semibold text-white">Подключить репозиторий</h1>
        </div>
        <p className="text-sm text-slate-400">
          Вставьте HTTPS-ссылку на репозиторий GitHub или GitLab и токен доступа. Каждый
          запуск клонирует репозиторий в изолированную песочницу.
        </p>

        <div>
          <label htmlFor="repoUrl" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            URL репозитория
          </label>
          <input
            id="repoUrl"
            type="url"
            value={repoUrl}
            onChange={e => setRepoUrl(e.target.value)}
            placeholder="https://github.com/owner/repo"
            required
            disabled={submitting}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          />
        </div>

        <div>
          <label htmlFor="accessToken" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
            Токен доступа
          </label>
          <input
            id="accessToken"
            type="password"
            value={accessToken}
            onChange={e => setAccessToken(e.target.value)}
            autoComplete="off"
            required
            disabled={submitting}
            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          />
          <p className="mt-1 text-xs text-slate-500">
            Personal access token с правом чтения и создания MR/PR. Хранится в зашифрованном виде.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="displayName" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Название (опц.)
            </label>
            <input
              id="displayName"
              type="text"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              disabled={submitting}
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
            />
          </div>
          <div>
            <label htmlFor="defaultBranch" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
              Ветка (опц.)
            </label>
            <input
              id="defaultBranch"
              type="text"
              value={defaultBranch}
              onChange={e => setDefaultBranch(e.target.value)}
              placeholder="main"
              disabled={submitting}
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
            />
          </div>
        </div>

        {error && (
          <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={submitting || !repoUrl || !accessToken}
          className="w-full flex items-center justify-center gap-2 bg-blue-700 hover:bg-blue-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
        >
          {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <GitBranch className="w-4 h-4" />}
          Подключить
        </button>
      </form>
    </div>
  )
}
