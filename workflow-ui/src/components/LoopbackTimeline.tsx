import { RotateCcw, AlertCircle, UserCircle, ShieldAlert, History } from 'lucide-react'
import { LoopbackEntry } from '../types'
import clsx from 'clsx'

interface Props {
  loopHistoryJson: string | null | undefined
}

function parseHistory(json: string | null | undefined): LoopbackEntry[] {
  if (!json || json === '[]') return []
  try {
    const parsed = JSON.parse(json)
    return Array.isArray(parsed) ? (parsed as LoopbackEntry[]) : []
  } catch {
    return []
  }
}

const SOURCE_META = {
  operator_return: {
    icon: UserCircle,
    label: 'Оператор',
    color: 'text-amber-400',
    bg: 'bg-amber-950/30 border-amber-800/60',
  },
  ci_failure: {
    icon: ShieldAlert,
    label: 'CI упал',
    color: 'text-red-400',
    bg: 'bg-red-950/30 border-red-800/60',
  },
  verify: {
    icon: AlertCircle,
    label: 'Verify не прошёл',
    color: 'text-blue-400',
    bg: 'bg-blue-950/30 border-blue-800/60',
  },
} as const

function metaFor(source: string | undefined) {
  if (source === 'operator_return' || source === 'ci_failure' || source === 'verify') {
    return SOURCE_META[source]
  }
  // Default (verify loopback without explicit source, legacy entries)
  return SOURCE_META.verify
}

export default function LoopbackTimeline({ loopHistoryJson }: Props) {
  const entries = parseHistory(loopHistoryJson)

  if (entries.length === 0) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-8 text-center text-slate-500 text-sm">
        <History className="w-6 h-6 mx-auto mb-2 opacity-50" />
        Возвратов и loopback-итераций не было — pipeline прошёл линейно.
      </div>
    )
  }

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-800 flex items-center gap-2">
        <RotateCcw className="w-4 h-4 text-slate-400" />
        <h2 className="text-sm font-medium text-slate-300">
          История итераций ({entries.length})
        </h2>
      </div>
      <ol className="divide-y divide-slate-800/60">
        {entries.map((entry, idx) => {
          const meta = metaFor(entry.source)
          const Icon = meta.icon
          return (
            <li key={idx} className="px-4 py-3">
              <div className="flex items-start gap-3">
                <div className={clsx('flex-shrink-0 w-8 h-8 rounded-full border flex items-center justify-center', meta.bg)}>
                  <Icon className={clsx('w-4 h-4', meta.color)} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex flex-wrap items-center gap-2 mb-1">
                    <span className={clsx('text-xs font-medium', meta.color)}>{meta.label}</span>
                    <span className="text-xs text-slate-500">•</span>
                    <span className="text-xs text-slate-400 font-mono">
                      {entry.from_block ? `${entry.from_block} → ` : ''}{entry.to_block}
                    </span>
                    <span className="text-xs text-slate-500">•</span>
                    <span className="text-xs text-slate-500 font-mono">итерация #{entry.iteration}</span>
                    <span className="ml-auto text-xs text-slate-600 font-mono">
                      {new Date(entry.timestamp).toLocaleString()}
                    </span>
                  </div>
                  {entry.comment && (
                    <div className="mt-1.5 text-sm text-slate-200 bg-slate-950/50 rounded px-3 py-2 border border-slate-800/80">
                      <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Комментарий оператора</p>
                      {entry.comment}
                    </div>
                  )}
                  {entry.issues && entry.issues.length > 0 && (
                    <ul className="mt-1.5 space-y-0.5 text-sm text-slate-300">
                      {entry.issues.map((iss, i) => (
                        <li key={i} className="flex items-start gap-1.5">
                          <span className="text-slate-600 mt-0.5">•</span>
                          <span>{iss}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            </li>
          )
        })}
      </ol>
    </div>
  )
}
