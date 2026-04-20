import { useState, useEffect, useRef } from 'react'
import { ChevronDown, Check, FolderKanban } from 'lucide-react'
import { api } from '../../services/api'
import { ProjectInfo } from '../../types'
import { PROJECT_SLUG_KEY as STORAGE_KEY, setCurrentProjectSlug } from '../../services/projectContext'
import clsx from 'clsx'

/**
 * Dropdown that lets the operator switch between projects. Selection is persisted to
 * localStorage; backend scoping is not yet wired (Срез 4.1 scaffold only) so the switch
 * is visual for now — future work will propagate the slug through API calls.
 */
export default function ProjectSwitcher() {
  const [projects, setProjects] = useState<ProjectInfo[]>([])
  const [currentSlug, setCurrentSlug] = useState<string>(() =>
    localStorage.getItem(STORAGE_KEY) ?? 'default'
  )
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.listProjects()
      .then(ps => {
        setProjects(ps)
        // If stored slug no longer exists, fall back to default.
        if (!ps.some(p => p.slug === currentSlug)) {
          const fallback = ps.find(p => p.slug === 'default')?.slug ?? ps[0]?.slug
          if (fallback) setCurrentSlug(fallback)
        }
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load projects'))
  }, [currentSlug])

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (!open) return
      const target = e.target as Node
      if (!menuRef.current?.contains(target) && !buttonRef.current?.contains(target)) {
        setOpen(false)
      }
    }
    function onEsc(e: KeyboardEvent) { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onEsc)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onEsc)
    }
  }, [open])

  const current = projects.find(p => p.slug === currentSlug)

  const select = (slug: string) => {
    setCurrentSlug(slug)
    setCurrentProjectSlug(slug)
    // Notify the rest of the app to refetch. Pages that care (Runs, Cost, etc.) listen
    // for this and reload. For now, a full reload guarantees nothing stale slips through.
    setOpen(false)
    window.location.reload()
  }

  if (error || projects.length === 0) {
    return null  // don't break layout if projects endpoint is unavailable
  }

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen(v => !v)}
        aria-label="Выбрать проект"
        aria-expanded={open}
        className="w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm text-slate-300 hover:bg-slate-800 hover:text-white transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        <FolderKanban className="w-4 h-4 flex-shrink-0 text-slate-500" />
        <span className="flex-1 text-left truncate">{current?.displayName ?? currentSlug}</span>
        <ChevronDown className={clsx('w-3.5 h-3.5 transition-transform flex-shrink-0', open && 'rotate-180')} />
      </button>

      {open && (
        <div
          ref={menuRef}
          role="menu"
          className="absolute left-0 right-0 mt-1 bg-slate-900 border border-slate-700 rounded-lg shadow-xl z-30 overflow-hidden max-h-80 overflow-y-auto"
        >
          {projects.map(p => (
            <button
              key={p.slug}
              type="button"
              role="menuitem"
              onClick={() => select(p.slug)}
              className={clsx(
                'w-full flex items-center gap-2 px-3 py-2 text-sm text-left hover:bg-slate-800 transition-colors',
                p.slug === currentSlug ? 'text-white bg-slate-800/50' : 'text-slate-300'
              )}
            >
              <Check
                className={clsx(
                  'w-3.5 h-3.5 flex-shrink-0',
                  p.slug === currentSlug ? 'text-blue-400' : 'invisible'
                )}
              />
              <div className="flex-1 min-w-0">
                <p className="truncate">{p.displayName}</p>
                <p className="text-xs text-slate-500 truncate font-mono">{p.slug}</p>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
