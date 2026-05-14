import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface TestCase {
  id?: string
  type?: string
  target?: string
  scenario?: string
  boundary_origin?: string | null
  priority?: string
}

const STRATEGY_META: Record<string, { ru: string; desc: string; color: string }> = {
  tdd: {
    ru: 'TDD — тесты сначала',
    desc: 'Behavioural change: тесты пишутся до codegen, должны падать до и проходить после',
    color: 'bg-purple-950/30 border-purple-800/50 text-purple-300',
  },
  adaptive: {
    ru: 'Adaptive — параллельно',
    desc: 'Тесты пишутся параллельно с codegen; нет строгой последовательности',
    color: 'bg-blue-950/30 border-blue-800/50 text-blue-300',
  },
  none: {
    ru: 'Тесты не нужны',
    desc: 'Pure rename / style fix / docs — поведение не меняется',
    color: 'bg-slate-900/40 border-slate-700 text-slate-400',
  },
}

const TYPE_RU: Record<string, string> = {
  unit: 'unit',
  integration: 'integration',
  e2e: 'e2e',
  visual: 'visual',
}

const PRIORITY_RU: Record<string, string> = {
  critical: 'критично',
  important: 'важно',
  nice_to_have: 'желательно',
}

const PRIORITY_COLOR: Record<string, string> = {
  critical: 'bg-red-950/50 border-red-800 text-red-300',
  important: 'bg-amber-950/50 border-amber-800 text-amber-300',
  nice_to_have: 'bg-slate-800 border-slate-700 text-slate-400',
}

function TestPlanningView({ out }: { out: Record<string, unknown> }) {
  const strategy = typeof out.strategy === 'string' ? out.strategy : ''
  const coverage = typeof out.coverage_estimate === 'number' ? out.coverage_estimate : 0
  const notes = typeof out.notes === 'string' ? out.notes : ''
  const cases: TestCase[] = Array.isArray(out.cases) ? (out.cases as TestCase[]) : []
  const meta = STRATEGY_META[strategy]
  const boundaryCount = cases.filter(c => c.boundary_origin).length

  return (
    <div className="space-y-3">
      {/* Strategy header */}
      <div className={clsx('px-3 py-2 rounded-lg border', meta?.color ?? 'bg-slate-900/40 border-slate-700')}>
        <div className="flex items-center gap-3 mb-0.5">
          <span className="text-sm font-semibold">{meta?.ru ?? strategy}</span>
          <span className="text-xs text-slate-400 ml-auto">
            Покрытие: <b className="text-slate-200">{Math.round(coverage * 100)}%</b>
          </span>
        </div>
        {meta?.desc && <p className="text-[11px] text-slate-400">{meta.desc}</p>}
      </div>

      {/* Notes */}
      {notes && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1">Заметки</p>
          <p className="text-xs text-slate-300 px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg whitespace-pre-wrap">{notes}</p>
        </div>
      )}

      {/* Cases */}
      {cases.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1 flex items-center gap-2">
            <span>Test cases · {cases.length}</span>
            {boundaryCount > 0 && (
              <span className="text-slate-500">·</span>
            )}
            {boundaryCount > 0 && (
              <span className="text-purple-400">{boundaryCount} граничных</span>
            )}
          </p>
          <div className="space-y-1.5">
            {cases.map((c, i) => (
              <div
                key={c.id ?? i}
                className={clsx(
                  'px-2.5 py-1.5 rounded-lg border',
                  c.boundary_origin
                    ? 'bg-purple-950/15 border-purple-900/40'
                    : 'bg-slate-900/40 border-slate-800',
                )}
              >
                <div className="flex items-center gap-2 flex-wrap mb-0.5">
                  {c.id && <span className="font-mono text-[10px] text-slate-500">{c.id}</span>}
                  {c.priority && (
                    <span className={clsx(
                      'text-[9px] px-1 py-0.5 rounded border uppercase tracking-wide',
                      PRIORITY_COLOR[c.priority] ?? 'bg-slate-800 border-slate-700 text-slate-400',
                    )}>
                      {PRIORITY_RU[c.priority] ?? c.priority}
                    </span>
                  )}
                  {c.type && (
                    <span className="text-[9px] px-1 py-0.5 rounded bg-slate-800 border border-slate-700 text-slate-400 uppercase tracking-wide">
                      {TYPE_RU[c.type] ?? c.type}
                    </span>
                  )}
                  {c.boundary_origin && (
                    <span className="text-[9px] px-1 py-0.5 rounded bg-purple-950/40 border border-purple-900 text-purple-300">
                      граничное: {c.boundary_origin}
                    </span>
                  )}
                </div>
                {c.target && <p className="text-[11px] text-slate-300 font-mono">{c.target}</p>}
                {c.scenario && <p className="text-xs text-slate-200">{c.scenario}</p>}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const strategy = typeof out.strategy === 'string' ? out.strategy : ''
    const cases = Array.isArray(out.cases) ? (out.cases as unknown[]).length : 0
    const cov = typeof out.coverage_estimate === 'number' ? out.coverage_estimate : 0
    const covPct = Math.round(cov * 100)
    if (strategy === 'none') return { label: 'тесты не нужны', ok: true }
    const stratRu = strategy === 'tdd' ? 'TDD' : 'adaptive'
    return { label: `${stratRu} · ${cases} кейсов · ${covPct}%`, ok: cases > 0 }
  },
  renderOutput: (out) => <TestPlanningView out={out} />,
}
