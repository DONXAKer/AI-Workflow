import { useState, useRef, useEffect, useId } from 'react'
import { X, Loader2, AlertTriangle } from 'lucide-react'
import { api } from '../../services/api'

interface Props {
  runId: string
  onCancelled?: () => void
}

export default function CancelButton({ runId, onCancelled }: Props) {
  const [open, setOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ref = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)
  const popoverId = useId()

  // Close on outside click
  useEffect(() => {
    if (!open) return
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [open])

  // Close on Escape and move focus back to trigger
  useEffect(() => {
    if (!open) return
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [open])

  // When the popover opens, move focus to the Confirm button
  useEffect(() => {
    if (open) {
      // Small delay so the element is rendered before we attempt focus
      requestAnimationFrame(() => confirmRef.current?.focus())
    }
  }, [open])

  const handleCancel = async () => {
    setCancelling(true)
    try {
      await api.cancelRun(runId)
      setOpen(false)
      onCancelled?.()
    } catch {
      setCancelling(false)
      setError('Не удалось отменить. Попробуйте ещё раз.')
    }
  }

  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => { setError(null); setOpen(v => !v) }}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={popoverId}
        className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-red-950/50 hover:bg-red-900/60 border border-red-800/50 text-red-400 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950"
      >
        <X className="w-3.5 h-3.5" />
        Отменить
      </button>

      {open && (
        <div
          id={popoverId}
          role="dialog"
          aria-label="Подтверждение отмены запуска"
          className="absolute right-0 top-full mt-1.5 w-60 bg-slate-800 border border-slate-700 rounded-xl shadow-xl p-4 z-50"
        >
          <div className="flex items-start gap-2 mb-3">
            <AlertTriangle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
            <p className="text-sm text-slate-200">Отменить этот запуск? Действие необратимо.</p>
          </div>
          <div className="flex items-center gap-2">
            <button
              ref={confirmRef}
              type="button"
              onClick={handleCancel}
              disabled={cancelling}
              className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md bg-red-700 hover:bg-red-600 text-white transition-colors disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400"
            >
              {cancelling ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <X className="w-3.5 h-3.5" />}
              {cancelling ? 'Отмена...' : 'Подтвердить'}
            </button>
            <button
              type="button"
              onClick={() => { setOpen(false); triggerRef.current?.focus() }}
              className="text-xs px-3 py-1.5 rounded-md bg-slate-700 hover:bg-slate-600 text-slate-300 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400"
            >
              Продолжить
            </button>
          </div>
          {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
        </div>
      )}
    </div>
  )
}
