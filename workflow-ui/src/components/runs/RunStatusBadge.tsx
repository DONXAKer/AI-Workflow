import { Clock, Loader2, AlertCircle, CheckCircle, XCircle } from 'lucide-react'
import clsx from 'clsx'
import { RunStatus } from '../../types'

const STATUS_CONFIG: Record<RunStatus, {
  label: string
  icon: React.ComponentType<{ className?: string }>
  classes: string
  spin?: boolean
  pulse?: boolean
}> = {
  PENDING: { label: 'Ожидание', icon: Clock, classes: 'bg-slate-700/60 text-slate-400' },
  RUNNING: { label: 'Выполняется', icon: Loader2, classes: 'bg-blue-900/50 text-blue-300', spin: true },
  PAUSED_FOR_APPROVAL: { label: 'Ожидает одобрения', icon: AlertCircle, classes: 'bg-amber-900/50 text-amber-300', pulse: true },
  COMPLETED: { label: 'Завершён', icon: CheckCircle, classes: 'bg-green-900/50 text-green-300' },
  FAILED: { label: 'Ошибка', icon: XCircle, classes: 'bg-red-900/50 text-red-300' },
}

export default function RunStatusBadge({ status }: { status: RunStatus }) {
  const cfg = STATUS_CONFIG[status]
  const Icon = cfg.icon
  return (
    <span className={clsx('inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium', cfg.classes)}>
      <Icon className={clsx('w-3.5 h-3.5', cfg.spin && 'animate-spin')} />
      {cfg.label}
      {cfg.pulse && (
        <span className="relative flex h-1.5 w-1.5 ml-0.5">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
          <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-amber-400" />
        </span>
      )}
    </span>
  )
}
