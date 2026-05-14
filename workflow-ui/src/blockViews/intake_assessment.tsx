import { CheckCircle, XCircle, HelpCircle, Zap, Workflow, MessageCircleQuestion } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface CriterionRow {
  criterion?: string
  passed?: boolean
  evidence?: string
}

/** English criterion id → Russian label */
const CRITERION_LABELS: Record<string, string> = {
  acceptance_criteria_explicit: 'Acceptance-критерии явно прописаны',
  scope_clear: 'Scope изменений понятен',
  edge_cases_listed: 'Граничные случаи перечислены',
  dod_measurable: 'Definition of Done измерима',
  perf_security_considered: 'Производительность / security учтены',
}

const PATH_META: Record<string, { ru: string; desc: string; icon: typeof Zap; color: string }> = {
  fast: {
    ru: 'Fast — можно сразу кодить',
    desc: 'Задача тривиальна, skip clarification и detailed analysis',
    icon: Zap,
    color: 'bg-green-950/30 border-green-800/50 text-green-300',
  },
  full: {
    ru: 'Full — обычный flow',
    desc: 'Нормальная задача, полный pipeline без сокращений',
    icon: Workflow,
    color: 'bg-blue-950/30 border-blue-800/50 text-blue-300',
  },
  clarify: {
    ru: 'Clarify — нужны уточнения',
    desc: 'Требование туманно, сначала вопросы потом анализ',
    icon: MessageCircleQuestion,
    color: 'bg-amber-950/30 border-amber-800/50 text-amber-300',
  },
}

function IntakeAssessmentView({ out }: { out: Record<string, unknown> }) {
  const pct = typeof out.clarity_pct === 'number' ? out.clarity_pct : 0
  const path = typeof out.recommended_path === 'string' ? out.recommended_path : ''
  const rationale = typeof out.rationale === 'string' ? out.rationale : ''
  const breakdown: CriterionRow[] = Array.isArray(out.clarity_breakdown)
    ? (out.clarity_breakdown as CriterionRow[]) : []
  const meta = PATH_META[path]
  const Icon = meta?.icon ?? HelpCircle

  return (
    <div className="space-y-3">
      {/* Path + clarity_pct */}
      <div className={clsx('flex items-start gap-3 px-3 py-2 rounded-lg border', meta?.color ?? 'bg-slate-900/40 border-slate-700 text-slate-300')}>
        <Icon className="w-5 h-5 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3 mb-0.5">
            <span className="text-sm font-semibold">{meta?.ru ?? path}</span>
            <span className="text-xs text-slate-400">Clarity: <b className="text-slate-200">{pct}%</b></span>
          </div>
          {meta?.desc && <p className="text-[11px] text-slate-400">{meta.desc}</p>}
        </div>
      </div>

      {/* Rationale */}
      {rationale && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1">Обоснование</p>
          <p className="text-xs text-slate-300 px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg whitespace-pre-wrap">
            {rationale}
          </p>
        </div>
      )}

      {/* Breakdown — 5 criteria with Russian labels */}
      {breakdown.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1">
            Критерии ясности ({breakdown.filter(c => c.passed).length}/{breakdown.length})
          </p>
          <div className="space-y-1">
            {breakdown.map((c, i) => {
              const label = c.criterion ? (CRITERION_LABELS[c.criterion] ?? c.criterion) : `Критерий ${i + 1}`
              return (
                <div
                  key={i}
                  className={clsx(
                    'flex items-start gap-2 px-2.5 py-1.5 rounded-lg border',
                    c.passed
                      ? 'bg-green-950/15 border-green-900/30'
                      : 'bg-red-950/20 border-red-900/40',
                  )}
                >
                  {c.passed
                    ? <CheckCircle className="w-3.5 h-3.5 text-green-400 flex-shrink-0 mt-0.5" />
                    : <XCircle className="w-3.5 h-3.5 text-red-400 flex-shrink-0 mt-0.5" />}
                  <div className="flex-1 min-w-0">
                    <p className="text-xs text-slate-200">{label}</p>
                    {c.evidence && <p className="text-[10px] text-slate-400 mt-0.5 italic">{c.evidence}</p>}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const pct = typeof out.clarity_pct === 'number' ? out.clarity_pct : 0
    const path = typeof out.recommended_path === 'string' ? out.recommended_path : ''
    const pathRu = path === 'fast' ? 'fast (сразу кодить)'
      : path === 'full' ? 'full (обычный flow)'
        : path === 'clarify' ? 'clarify (вопросы)'
          : path
    if (path === 'fast') return { label: `${pct}% · ${pathRu}`, ok: true }
    if (path === 'clarify') return { label: `${pct}% · ${pathRu}`, warn: true }
    return { label: `${pct}% · ${pathRu}` }
  },
  renderOutput: (out) => <IntakeAssessmentView out={out} />,
}
