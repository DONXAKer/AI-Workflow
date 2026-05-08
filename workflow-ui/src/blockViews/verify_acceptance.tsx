import { useState } from 'react'
import { CheckCircle, XCircle, ChevronDown, ChevronRight } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface VItem {
  item_id?: string
  passed?: boolean
  evidence?: string
  fix?: string
  text?: string
  priority?: string
}

function VerifyOutput({ out }: { out: Record<string, unknown> }) {
  const [showPassed, setShowPassed] = useState(false)

  const results: VItem[] = Array.isArray(out.verification_results)
    ? (out.verification_results as VItem[]) : []

  const failedItems = results.filter(r => !r.passed)
  const passedItems = results.filter(r => r.passed)

  // Legacy fallback: passed_items / failed_items
  const passedArr = Array.isArray(out.passed_items) ? (out.passed_items as unknown[]) : []
  const failedArr = Array.isArray(out.failed_items) ? (out.failed_items as unknown[]) : []
  const useResults = results.length > 0

  const totalCount = useResults ? results.length : passedArr.length + failedArr.length
  const passCount = useResults ? passedItems.length : passedArr.length
  const failCount = useResults ? failedItems.length : failedArr.length
  const allPassed = failCount === 0 && passCount > 0

  return (
    <div className="space-y-3">
      <div className={clsx(
        'flex items-center gap-2 px-3 py-2 rounded-lg border',
        allPassed ? 'bg-green-950/30 border-green-800/50' : 'bg-red-950/30 border-red-800/50',
      )}>
        {allPassed
          ? <CheckCircle className="w-4 h-4 text-green-400 flex-shrink-0" />
          : <XCircle className="w-4 h-4 text-red-400 flex-shrink-0" />}
        <span className={clsx('text-sm font-semibold', allPassed ? 'text-green-300' : 'text-red-300')}>
          {passCount}/{totalCount} пунктов пройдено
        </span>
        {out.pass_threshold != null && (
          <span className="text-xs text-slate-500 ml-auto">порог: {String(out.pass_threshold)}</span>
        )}
      </div>

      {/* Failed */}
      {failCount > 0 && (
        <div className="space-y-2">
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium">Не пройдено ({failCount})</p>
          {(useResults ? failedItems : failedArr.map(f => ({ text: typeof f === 'string' ? f : JSON.stringify(f) } as VItem))).map((item, i) => (
            <div key={item.item_id ?? i} className="bg-red-950/20 border border-red-800/40 rounded-lg px-3 py-2 space-y-1">
              <div className="flex items-start gap-2">
                <XCircle className="w-3.5 h-3.5 text-red-400 flex-shrink-0 mt-0.5" />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                    {item.item_id && <span className="font-mono text-[10px] text-slate-500">{item.item_id}</span>}
                    {item.priority && (
                      <span className={clsx(
                        'text-[9px] px-1 py-0.5 rounded border uppercase tracking-wide',
                        item.priority === 'CRITICAL' ? 'bg-red-950/50 border-red-800 text-red-300'
                          : item.priority === 'IMPORTANT' ? 'bg-amber-950/50 border-amber-800 text-amber-300'
                            : 'bg-slate-800 border-slate-700 text-slate-400',
                      )}>
                        {item.priority}
                      </span>
                    )}
                  </div>
                  {item.text && <p className="text-xs text-slate-200">{item.text}</p>}
                  {item.evidence && <p className="text-[10px] text-slate-400 mt-0.5 italic">{item.evidence}</p>}
                  {item.fix && <p className="text-[10px] text-amber-300/80 mt-0.5">Fix: {item.fix}</p>}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Passed — collapsed */}
      {passCount > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setShowPassed(v => !v)}
            className="flex items-center gap-1.5 text-[10px] text-slate-500 hover:text-green-400 transition-colors uppercase tracking-wide"
          >
            {showPassed ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            <CheckCircle className="w-3 h-3 text-green-600/70" />
            Пройдено ({passCount})
          </button>
          {showPassed && (
            <div className="mt-2 space-y-1.5">
              {(useResults ? passedItems : passedArr.map(f => ({ text: typeof f === 'string' ? f : JSON.stringify(f) } as VItem))).map((item, i) => (
                <div key={item.item_id ?? i} className="flex items-start gap-2 px-3 py-1.5 bg-green-950/10 border border-green-900/30 rounded">
                  <CheckCircle className="w-3 h-3 text-green-600/70 flex-shrink-0 mt-0.5" />
                  <div className="flex-1 min-w-0">
                    {item.item_id && <span className="font-mono text-[10px] text-slate-500 mr-2">{item.item_id}</span>}
                    {item.evidence && <span className="text-[10px] text-slate-400 italic">{item.evidence}</span>}
                    {item.text && !item.item_id && <span className="text-xs text-slate-400">{item.text}</span>}
                  </div>
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
    const passed = Array.isArray(out.passed_items) ? (out.passed_items as unknown[]).length : 0
    const failed = Array.isArray(out.failed_items) ? (out.failed_items as unknown[]).length : 0
    const results = Array.isArray(out.verification_results) ? (out.verification_results as Array<{ passed?: boolean }>) : []
    const total = results.length > 0 ? results.length : passed + failed
    const passCount = results.length > 0 ? results.filter(r => r.passed).length : passed
    const failCount = total - passCount
    if (total === 0) return { label: '—' }
    if (failCount === 0) return { label: `${passCount}/${total} pass`, ok: true }
    return { label: `${passCount}/${total} pass`, fail: true }
  },
  renderOutput: (out) => <VerifyOutput out={out} />,
}
