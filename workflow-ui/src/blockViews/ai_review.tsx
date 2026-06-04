import { CheckCircle, XCircle, AlertCircle } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

const VERDICT_LABELS: Record<string, { label: string; ok?: boolean; warn?: boolean }> = {
  approve: { label: '✓ approve', ok: true },
  request_changes: { label: 'request changes', warn: true },
  reject: { label: 'reject', warn: true },
}

function AiReviewOutput({ out }: { out: Record<string, unknown> }) {
  const passed = out.passed === true
  const verdict = typeof out.verdict === 'string' ? out.verdict.trim() : ''
  const summary = typeof out.summary === 'string' ? out.summary.trim() : ''
  const issues = Array.isArray(out.issues) ? (out.issues as unknown[]) : []
  const blockers = Array.isArray(out.blockers) ? (out.blockers as unknown[]) : []
  const suggestions = Array.isArray(out.suggestions) ? (out.suggestions as unknown[]) : []
  const allIssues = issues.length > 0 ? issues : blockers

  return (
    <div className="space-y-3">
      {/* Verdict header */}
      <div className={clsx(
        'flex items-center gap-2 px-3 py-2 rounded-lg border',
        passed ? 'bg-green-950/30 border-green-800/50' : 'bg-amber-950/30 border-amber-800/50',
      )}>
        {passed
          ? <CheckCircle className="w-4 h-4 text-green-400 flex-shrink-0" />
          : <XCircle className="w-4 h-4 text-amber-400 flex-shrink-0" />}
        <span className={clsx('text-sm font-semibold', passed ? 'text-green-300' : 'text-amber-300')}>
          {passed ? 'Approved' : 'Changes requested'}
        </span>
        {verdict && (
          <span className="ml-auto font-mono text-[10px] text-slate-500">{verdict}</span>
        )}
      </div>

      {/* Summary */}
      {summary && (
        <div className="bg-slate-900/40 border border-slate-700/40 rounded-lg px-3 py-2">
          <p className="text-xs text-slate-300 leading-relaxed whitespace-pre-wrap">{summary}</p>
        </div>
      )}

      {/* Blockers / issues */}
      {allIssues.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium">
            Blockers ({allIssues.length})
          </p>
          {allIssues.map((issue, i) => (
            <div key={i} className="flex items-start gap-2 bg-red-950/20 border border-red-800/40 rounded-lg px-3 py-2">
              <AlertCircle className="w-3 h-3 text-red-400 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-slate-200">{typeof issue === 'string' ? issue : JSON.stringify(issue)}</p>
            </div>
          ))}
        </div>
      )}

      {/* Suggestions */}
      {suggestions.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-[10px] uppercase tracking-wide text-slate-500 font-medium">
            Suggestions ({suggestions.length})
          </p>
          {suggestions.map((s, i) => (
            <div key={i} className="flex items-start gap-2 bg-slate-900/30 border border-slate-700/30 rounded-lg px-3 py-2">
              <p className="text-xs text-slate-400">{typeof s === 'string' ? s : JSON.stringify(s)}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const verdict = typeof out.verdict === 'string' ? out.verdict : ''
    const v = VERDICT_LABELS[verdict]
    if (v) return v
    if (out.passed === true) return { label: '✓ approve', ok: true }
    const issues = Array.isArray(out.issues) ? out.issues.length
      : Array.isArray(out.blockers) ? (out.blockers as unknown[]).length : 0
    if (issues > 0) return { label: `${issues} blockers`, warn: true }
    return { label: 'ревью' }
  },
  renderOutput: (out) => <AiReviewOutput out={out} />,
}
