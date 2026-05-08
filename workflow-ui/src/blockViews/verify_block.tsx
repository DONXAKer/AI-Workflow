import { CheckCircle, XCircle, AlertCircle } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

function VerifyBlockOutput({ out }: { out: Record<string, unknown> }) {
  const passed = out.passed === true
  const issues = Array.isArray(out.issues) ? (out.issues as unknown[]) : []
  const failedItems = Array.isArray(out.failed_items) ? (out.failed_items as unknown[]) : []
  const passedItems = Array.isArray(out.passed_items) ? (out.passed_items as unknown[]) : []
  const llmScore = typeof out.llm_score === 'number' ? out.llm_score : null
  const llmVerdict = typeof out.llm_verdict === 'string' ? out.llm_verdict.trim() : ''
  const llmExplanation = typeof out.llm_explanation === 'string' ? out.llm_explanation.trim() : ''

  const allIssues = [
    ...issues.map(i => (typeof i === 'string' ? i : JSON.stringify(i))),
    ...failedItems.map(i => (typeof i === 'string' ? i : JSON.stringify(i))),
  ]

  return (
    <div className="space-y-3">
      <div className={clsx(
        'flex items-center gap-2 px-3 py-2 rounded-lg border',
        passed ? 'bg-green-950/30 border-green-800/50' : 'bg-red-950/30 border-red-800/50',
      )}>
        {passed
          ? <CheckCircle className="w-4 h-4 text-green-400 flex-shrink-0" />
          : <XCircle className="w-4 h-4 text-red-400 flex-shrink-0" />}
        <span className={clsx('text-sm font-semibold', passed ? 'text-green-300' : 'text-red-300')}>
          {passed ? 'Проверка пройдена' : 'Проверка не пройдена'}
        </span>
        {llmScore !== null && (
          <span className="text-xs text-slate-500 ml-auto font-mono">score: {llmScore}/10</span>
        )}
      </div>

      {allIssues.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium">Проблемы ({allIssues.length})</p>
          {allIssues.map((issue, i) => (
            <div key={i} className="flex items-start gap-2 bg-red-950/20 border border-red-800/40 rounded px-3 py-2">
              <AlertCircle className="w-3 h-3 text-red-400 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-slate-200">{issue}</p>
            </div>
          ))}
        </div>
      )}

      {passedItems.length > 0 && (
        <div className="space-y-1">
          <p className="text-[10px] uppercase tracking-wide text-green-500 font-medium">Пройдено ({passedItems.length})</p>
          {passedItems.map((item, i) => (
            <div key={i} className="flex items-center gap-2 px-3 py-1.5 bg-green-950/10 border border-green-900/30 rounded">
              <CheckCircle className="w-3 h-3 text-green-600/70 flex-shrink-0" />
              <span className="text-xs text-slate-400">{typeof item === 'string' ? item : JSON.stringify(item)}</span>
            </div>
          ))}
        </div>
      )}

      {(llmVerdict || llmExplanation) && (
        <div className="bg-slate-900/50 border border-slate-800 rounded px-3 py-2 space-y-1">
          {llmVerdict && <p className="text-xs font-semibold text-slate-300">{llmVerdict}</p>}
          {llmExplanation && <p className="text-xs text-slate-400 leading-relaxed">{llmExplanation}</p>}
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    if (out.passed === true) return { label: '✓ пройдена', ok: true }
    const issues = Array.isArray(out.issues) ? out.issues.length : 0
    const failed = Array.isArray(out.failed_items) ? out.failed_items.length : 0
    const total = issues + failed
    if (total > 0) return { label: `${total} проблем`, fail: true }
    if (out.passed === false) return { label: 'не пройдена', fail: true }
    return { label: '—' }
  },
  renderOutput: (out) => <VerifyBlockOutput out={out} />,
}
