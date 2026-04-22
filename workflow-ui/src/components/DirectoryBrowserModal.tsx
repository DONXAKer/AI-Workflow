import { useState, useEffect, useCallback } from 'react'
import { X, Folder, ChevronRight, Loader2, ArrowUp, AlertCircle } from 'lucide-react'
import { api } from '../services/api'

interface Props {
  initialPath?: string
  onSelect: (path: string) => void
  onClose: () => void
}

interface BrowseResult {
  path: string
  parent: string
  directories: string[]
  root: string
}

export default function DirectoryBrowserModal({ initialPath, onSelect, onClose }: Props) {
  const [result, setResult] = useState<BrowseResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const navigate = useCallback(async (path?: string) => {
    setLoading(true)
    setError(null)
    try {
      setResult(await api.browseFs(path))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ошибка загрузки')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    navigate(initialPath || undefined)
  }, [navigate, initialPath])

  const breadcrumbs = result ? buildBreadcrumbs(result.path, result.root) : []

  return (
    <div className="fixed inset-0 bg-slate-950/80 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-slate-900 border border-slate-700 rounded-xl shadow-2xl w-full max-w-lg flex flex-col"
        style={{ maxHeight: '70vh' }}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-700">
          <span className="text-sm font-semibold text-white">Выбор папки</span>
          <button type="button" onClick={onClose} className="text-slate-500 hover:text-white transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Breadcrumbs */}
        <div className="flex items-center gap-1 px-4 py-2 border-b border-slate-800 flex-wrap min-h-[36px]">
          {breadcrumbs.map((crumb, i) => (
            <span key={crumb.path} className="flex items-center gap-1">
              {i > 0 && <ChevronRight className="w-3 h-3 text-slate-600 flex-shrink-0" />}
              <button
                type="button"
                onClick={() => navigate(crumb.path)}
                className="text-xs text-blue-400 hover:text-blue-300 font-mono transition-colors"
              >
                {crumb.label}
              </button>
            </span>
          ))}
          {loading && <Loader2 className="w-3 h-3 text-slate-500 animate-spin ml-1" />}
        </div>

        {/* Directory list */}
        <div className="overflow-y-auto flex-1 px-2 py-2">
          {error && (
            <div className="flex items-center gap-2 text-red-400 text-sm px-2 py-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0" /> {error}
            </div>
          )}

          {!error && result && (
            <>
              {result.parent && result.path !== result.root && (
                <button
                  type="button"
                  onClick={() => navigate(result.parent)}
                  className="flex items-center gap-2 w-full px-3 py-2 rounded-lg text-sm text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
                >
                  <ArrowUp className="w-4 h-4 flex-shrink-0" />
                  <span className="font-mono">..</span>
                </button>
              )}

              {result.directories.length === 0 && (
                <p className="text-xs text-slate-500 px-3 py-3">Нет вложенных папок</p>
              )}

              {result.directories.map(name => (
                <button
                  key={name}
                  type="button"
                  onClick={() => navigate(`${result.path}/${name}`)}
                  className="flex items-center gap-2 w-full px-3 py-2 rounded-lg text-sm text-slate-300 hover:bg-slate-800 hover:text-white transition-colors"
                >
                  <Folder className="w-4 h-4 flex-shrink-0 text-blue-400" />
                  <span className="font-mono truncate">{name}</span>
                </button>
              ))}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between gap-3 px-4 py-3 border-t border-slate-700">
          <span className="text-xs text-slate-500 font-mono truncate">{result?.path ?? '…'}</span>
          <div className="flex gap-2 flex-shrink-0">
            <button
              type="button"
              onClick={onClose}
              className="text-sm text-slate-400 hover:text-white px-3 py-1.5 rounded-lg transition-colors"
            >
              Отмена
            </button>
            <button
              type="button"
              disabled={!result}
              onClick={() => result && onSelect(result.path)}
              className="text-sm bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg transition-colors"
            >
              Выбрать
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function buildBreadcrumbs(path: string, root: string): { path: string; label: string }[] {
  const parts = path.split('/').filter(Boolean)
  const rootParts = root.split('/').filter(Boolean)

  const crumbs: { path: string; label: string }[] = []
  for (let i = rootParts.length - 1; i < parts.length; i++) {
    const p = '/' + parts.slice(0, i + 1).join('/')
    crumbs.push({ path: p, label: i === rootParts.length - 1 ? '~' : parts[i] })
  }
  return crumbs
}
