import { RotateCcw, AlertCircle, UserCircle, ShieldAlert, History, Repeat, Check, Plus, Minus } from 'lucide-react'
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
  no_progress: {
    icon: Repeat,
    label: 'Зацикливание (no-progress)',
    color: 'text-fuchsia-400',
    bg: 'bg-fuchsia-950/30 border-fuchsia-800/60',
  },
} as const

function metaFor(source: string | undefined) {
  if (
    source === 'operator_return' ||
    source === 'ci_failure' ||
    source === 'verify' ||
    source === 'no_progress'
  ) {
    return SOURCE_META[source]
  }
  return SOURCE_META.verify
}

/** Adjacent entries sharing the same to_block form a logical loopback cycle
 *  (impl → verify → impl → verify → …). Grouping them makes the timeline
 *  readable when there are 5+ iterations against one target. */
interface Cycle {
  toBlock: string
  entries: LoopbackEntry[]
}

function groupIntoCycles(entries: LoopbackEntry[]): Cycle[] {
  const cycles: Cycle[] = []
  for (const entry of entries) {
    const last = cycles[cycles.length - 1]
    if (last && last.toBlock === entry.to_block) {
      last.entries.push(entry)
    } else {
      cycles.push({ toBlock: entry.to_block, entries: [entry] })
    }
  }
  return cycles
}

interface IssueDelta {
  resolved: string[]   // present prev, gone now
  persisted: string[]  // in both
  added: string[]      // new this iter
}

function diffIssues(prev: string[] | undefined, curr: string[] | undefined): IssueDelta {
  const p = new Set(prev ?? [])
  const c = new Set(curr ?? [])
  const resolved: string[] = []
  const persisted: string[] = []
  const added: string[] = []
  for (const it of p) (c.has(it) ? persisted : resolved).push(it)
  for (const it of c) if (!p.has(it)) added.push(it)
  return { resolved, persisted, added }
}

export default function LoopbackTimeline({ loopHistoryJson }: Props) {
  const entries = parseHistory(loopHistoryJson)
  const cycles = groupIntoCycles(entries)

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
          История итераций · {entries.length} {entries.length === 1 ? 'возврат' : 'возвратов'} в {cycles.length} {cycles.length === 1 ? 'цикле' : 'циклах'}
        </h2>
      </div>
      <div className="divide-y divide-slate-800/60">
        {cycles.map((cycle, ci) => (
          <section key={ci} className="px-4 py-3">
            <header className="flex items-center gap-2 mb-2">
              <Repeat className="w-3.5 h-3.5 text-slate-500" />
              <span className="text-xs uppercase tracking-wide text-slate-500">Цикл</span>
              <span className="text-sm font-mono text-slate-300">→ {cycle.toBlock}</span>
              <span className="text-xs text-slate-600">·</span>
              <span className="text-xs text-slate-500">{cycle.entries.length} {cycle.entries.length === 1 ? 'итерация' : 'итерации'}</span>
            </header>
            <ol className="space-y-2 ml-5 border-l border-slate-800/80 pl-4">
              {cycle.entries.map((entry, ei) => {
                const meta = metaFor(entry.source)
                const Icon = meta.icon
                const prev = ei > 0 ? cycle.entries[ei - 1] : undefined
                const delta = diffIssues(prev?.issues, entry.issues)
                return (
                  <li key={ei} className="relative">
                    <div className={clsx('absolute -left-[26px] top-0 w-6 h-6 rounded-full border flex items-center justify-center', meta.bg)}>
                      <Icon className={clsx('w-3 h-3', meta.color)} />
                    </div>
                    <div className="flex flex-wrap items-center gap-2 mb-1">
                      <span className={clsx('text-xs font-medium', meta.color)}>{meta.label}</span>
                      <span className="text-xs text-slate-500">•</span>
                      <span className="text-xs text-slate-400 font-mono">
                        {entry.from_block ? `${entry.from_block} → ` : ''}{entry.to_block}
                      </span>
                      <span className="text-xs text-slate-500">•</span>
                      <span className="text-xs text-slate-500 font-mono">итер. #{entry.iteration}</span>
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
                      <ul className="mt-1.5 space-y-0.5 text-sm">
                        {prev && delta.resolved.map((iss, i) => (
                          <li key={`r${i}`} className="flex items-start gap-1.5 text-emerald-400/80 line-through decoration-emerald-700/50" title="Issue из предыдущей итерации больше не упоминается">
                            <Check className="w-3 h-3 mt-0.5 flex-shrink-0" />
                            <span className="line-clamp-2">{iss}</span>
                          </li>
                        ))}
                        {delta.persisted.map((iss, i) => (
                          <li key={`p${i}`} className="flex items-start gap-1.5 text-amber-300" title="Issue повторяется (потенциальное зацикливание)">
                            <Minus className="w-3 h-3 mt-0.5 flex-shrink-0" />
                            <span>{iss}</span>
                          </li>
                        ))}
                        {delta.added.map((iss, i) => (
                          <li key={`a${i}`} className={clsx('flex items-start gap-1.5', prev ? 'text-rose-300' : 'text-slate-300')} title={prev ? 'Новая проблема в этой итерации' : undefined}>
                            {prev
                              ? <Plus className="w-3 h-3 mt-0.5 flex-shrink-0" />
                              : <span className="text-slate-600 mt-0.5">•</span>}
                            <span>{iss}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </li>
                )
              })}
            </ol>
          </section>
        ))}
      </div>
    </div>
  )
}
