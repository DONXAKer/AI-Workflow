import { createContext, useContext, useState, useCallback, ReactNode, useEffect } from 'react'

export type ToastSeverity = 'info' | 'success' | 'warning' | 'error'

export interface Toast {
  id: string
  severity: ToastSeverity
  title: string
  body?: string
  link?: { href: string; label: string }
  /** Auto-dismiss after N ms. 0 / undefined = sticky until clicked. */
  timeoutMs?: number
}

interface ToastState {
  toasts: Toast[]
  show: (toast: Omit<Toast, 'id'>) => string
  dismiss: (id: string) => void
}

const ToastContext = createContext<ToastState | null>(null)

const DEFAULT_TIMEOUT_MS = 6000

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const dismiss = useCallback((id: string) => {
    setToasts(prev => prev.filter(t => t.id !== id))
  }, [])

  const show = useCallback((toast: Omit<Toast, 'id'>) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    setToasts(prev => [...prev, { ...toast, id }])
    return id
  }, [])

  // Auto-dismiss timer per toast. Timeouts live in a map so each toast has its own clock.
  useEffect(() => {
    const timers = toasts
      .filter(t => (t.timeoutMs ?? DEFAULT_TIMEOUT_MS) > 0)
      .map(t => setTimeout(() => dismiss(t.id), t.timeoutMs ?? DEFAULT_TIMEOUT_MS))
    return () => { for (const id of timers) clearTimeout(id) }
  }, [toasts, dismiss])

  return (
    <ToastContext.Provider value={{ toasts, show, dismiss }}>
      {children}
    </ToastContext.Provider>
  )
}

export function useToast(): ToastState {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
