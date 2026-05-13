import { useState } from 'react'
import { CheckCircle, XCircle, RotateCcw, AlertCircle, AlertTriangle, ChevronDown, ChevronRight } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface CItem {
  id?: string
  passed?: boolean
  evidence?: string
  fix?: string
}

function ReviewOutput({ out }: { out: Record<string, unknown> }) {
  const passed = out.passed === true
  const retryInstruction = typeof out.retry_instruction === 'string' ? out.retry_instruction.trim() : ''
  const issues = Array.isArray(out.issues) ? (out.issues as unknown[]) : []
  const checklist: CItem[] = Array.isArray(out.checklist_status) ? (out.checklist_status as CItem[]) : []
  const regressions = Array.isArray(out.regressions) ? (out.regressions as unknown[]) : []
  const carryForward = typeof out.carry_forward === 'string' ? out.carry_forward.trim() : ''

  const failedItems = checklist.filter(c => !c.passed)
  const passedItems = checklist.filter(c => c.passed)
  const hasChecklist = checklist.length > 0
  // When nothing failed, surface the passed checklist by default — otherwise
  // a "passed" review shows only a chip with no detail of what was checked.
  const [showPassed, setShowPassed] = useState(failedItems.length === 0)
  const degraded = out.degraded === true
  const degradedReason = typeof out.degraded_reason === 'string' ? out.degraded_reason : ''

  return (
    <div className="space-y-3">
      <div className={clsx(
        'flex items-center gap-2 px-3 py-2 rounded-lg border',
        passed ? 'bg-green-950/30 border-green-800/50' : 'bg-amber-950/30 border-amber-800/50',
      )}>
        {passed
          ? <CheckCircle className="w-4 h-4 text-green-400 flex-shrink-0" />
          : <XCircle className="w-4 h-4 text-amber-400 flex-shrink-0" />}
        <span className={clsx('text-sm font-semibold', passed ? 'text-green-300' : 'text-amber-300')}>
          {passed ? 'Ревью пройдено' : 'Требует доработки'}
        </span>
        {hasChecklist && (
          <span className="text-xs text-slate-500 ml-auto">
            {passedItems.length}/{checklist.length}
          </span>
        )}
      </div>

      {degraded && (
        <div className="flex items-start gap-2 bg-yellow-950/20 border border-yellow-700/40 rounded-lg px-3 py-2">
          <AlertTriangle className="w-3.5 h-3.5 text-yellow-400 flex-shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="text-xs text-yellow-300 font-medium">Degraded output (prose-fallback)</p>
            <p className="text-[11px] text-yellow-200/80 mt-0.5">
              {degradedReason || 'Модель не выдала JSON — output синтезирован из текста. Verdict оптимистичный, реальные баги ловят build/tests/verify_acceptance.'}
            </p>
          </div>
        </div>
      )}

      {carryForward && (
        <div className="bg-slate-900/40 border border-slate-700/40 rounded-lg px-3 py-2">
          <p className="text-[10px] uppercase tracking-wide text-slate-500 font-medium mb-1">Что сделано</p>
          <p className="text-xs text-slate-300 leading-relaxed whitespace-pre-wrap">{carryForward}</p>
        </div>
      )}

      {retryInstruction && (
        <div className="flex items-start gap-2 bg-amber-950/20 border border-amber-800/40 rounded-lg px-3 py-2">
          <RotateCcw className="w-3.5 h-3.5 text-amber-400 flex-shrink-0 mt-0.5" />
          <p className="text-xs text-amber-200 leading-relaxed whitespace-pre-wrap">{retryInstruction}</p>
        </div>
      )}

      {regressions.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium">Регрессии ({regressions.length})</p>
          {regressions.map((r, i) => (
            <div key={i} className="flex items-start gap-2 bg-red-950/30 border border-red-700/50 rounded-lg px-3 py-2">
              <AlertTriangle className="w-3.5 h-3.5 text-red-400 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-red-200">{typeof r === 'string' ? r : JSON.stringify(r)}</p>
            </div>
          ))}
        </div>
      )}

      {failedItems.length > 0 && (
        <div className="space-y-2">
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium">Не пройдено ({failedItems.length})</p>
          {failedItems.map((item, i) => (
            <div key={item.id ?? i} className="bg-red-950/20 border border-red-800/40 rounded-lg px-3 py-2 space-y-0.5">
              <div className="flex items-start gap-2">
                <XCircle className="w-3 h-3 text-red-400 flex-shrink-0 mt-0.5" />
                <div className="flex-1 min-w-0">
                  {item.id && <span className="font-mono text-[10px] text-slate-500 block mb-0.5">{item.id}</span>}
                  {item.evidence && <p className="text-xs text-slate-300">{item.evidence}</p>}
                  {item.fix && <p className="text-[10px] text-amber-300/80 mt-0.5">Fix: {item.fix}</p>}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {issues.length > 0 && !hasChecklist && (
        <div className="space-y-1.5">
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium">Проблемы ({issues.length})</p>
          {issues.map((issue, i) => (
            <div key={i} className="flex items-start gap-2 bg-red-950/20 border border-red-800/40 rounded-lg px-3 py-2">
              <AlertCircle className="w-3 h-3 text-red-400 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-slate-200">{typeof issue === 'string' ? issue : JSON.stringify(issue)}</p>
            </div>
          ))}
        </div>
      )}

      {passedItems.length > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setShowPassed(v => !v)}
            className="flex items-center gap-1.5 text-[10px] text-slate-500 hover:text-green-400 transition-colors uppercase tracking-wide"
          >
            {showPassed ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            <CheckCircle className="w-3 h-3 text-green-600/70" />
            Пройдено ({passedItems.length})
          </button>
          {showPassed && (
            <div className="mt-2 space-y-1">
              {passedItems.map((item, i) => (
                <div key={item.id ?? i} className="flex items-center gap-2 px-3 py-1.5 bg-green-950/10 border border-green-900/30 rounded text-xs">
                  <CheckCircle className="w-3 h-3 text-green-600/70 flex-shrink-0" />
                  <span className="font-mono text-slate-500 mr-1">{item.id}</span>
                  {item.evidence && <span className="text-slate-400 italic truncate">{item.evidence}</span>}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    if (out.passed === true) return { label: '✓ пройдено', ok: true }
    const retryInstruction = typeof out.retry_instruction === 'string' && out.retry_instruction.trim()
    if (retryInstruction) return { label: 'loopback', warn: true }
    const issues = Array.isArray(out.issues) ? (out.issues as unknown[]).length : 0
    if (issues > 0) return { label: `${issues} issues`, warn: true }
    const failedChecklist = Array.isArray(out.checklist_status)
      ? (out.checklist_status as Array<{ passed?: boolean }>).filter(c => !c.passed).length
      : 0
    if (failedChecklist > 0) return { label: `${failedChecklist} не пройдено`, warn: true }
    return { label: '—' }
  },
  renderOutput: (out) => <ReviewOutput out={out} />,
}
