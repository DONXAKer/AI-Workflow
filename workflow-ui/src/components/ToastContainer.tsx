import { CheckCircle, AlertCircle, AlertTriangle, Info, X, ExternalLink } from 'lucide-react'
import { useToast, ToastSeverity } from '../context/ToastContext'
import clsx from 'clsx'

const ICONS: Record<ToastSeverity, React.ComponentType<{ className?: string }>> = {
  info: Info,
  success: CheckCircle,
  warning: AlertTriangle,
  error: AlertCircle,
}

const PALETTE: Record<ToastSeverity, { border: string; icon: string; bg: string }> = {
  info: { border: 'border-blue-700', icon: 'text-blue-400', bg: 'bg-blue-950/70' },
  success: { border: 'border-green-700', icon: 'text-green-400', bg: 'bg-green-950/70' },
  warning: { border: 'border-amber-700', icon: 'text-amber-400', bg: 'bg-amber-950/70' },
  error: { border: 'border-red-700', icon: 'text-red-400', bg: 'bg-red-950/70' },
}

/**
 * Stacked toasts in the bottom-right corner. Each toast is role="status" so assistive
 * tech announces them. Container sits above the rest of the UI via fixed positioning.
 */
export default function ToastContainer() {
  const { toasts, dismiss } = useToast()
  if (toasts.length === 0) return null

  return (
    <div
      data-testid="toast-container"
      className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 max-w-sm w-full pointer-events-none"
    >
      {toasts.map(t => {
        const Icon = ICONS[t.severity]
        const palette = PALETTE[t.severity]
        return (
          <div
            key={t.id}
            role="status"
            aria-live="polite"
            className={clsx(
              'pointer-events-auto flex items-start gap-2.5 rounded-xl border px-4 py-3 shadow-lg backdrop-blur-sm',
              palette.border, palette.bg
            )}
          >
            <Icon className={clsx('w-4 h-4 flex-shrink-0 mt-0.5', palette.icon)} />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-white">{t.title}</p>
              {t.body && <p className="text-xs text-slate-300 mt-1 break-words">{t.body}</p>}
              {t.link && (
                <a
                  href={t.link.href}
                  className="inline-flex items-center gap-1 text-xs text-blue-300 hover:text-blue-200 mt-1"
                >
                  {t.link.label}
                  <ExternalLink className="w-3 h-3" />
                </a>
              )}
            </div>
            <button
              type="button"
              onClick={() => dismiss(t.id)}
              aria-label="Скрыть уведомление"
              className="p-1 rounded-md text-slate-400 hover:text-white hover:bg-slate-800 flex-shrink-0"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )
      })}
    </div>
  )
}
