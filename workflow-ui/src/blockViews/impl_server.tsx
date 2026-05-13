import { useState } from 'react'
import { FilePlus, FileText, Trash2, ChevronDown, ChevronRight } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface FileChange {
  path?: string
  insertions?: number
  deletions?: number
  status?: 'new' | 'modified' | 'deleted' | string
}

function ImplServerOutput({ out }: { out: Record<string, unknown> }) {
  const finalText = typeof out.final_text === 'string' ? out.final_text.trim() : ''
  const cost = typeof out.total_cost_usd === 'number' ? out.total_cost_usd : null
  const iters = typeof out.iterations_used === 'number' ? out.iterations_used : null
  const stopReason = typeof out.stop_reason === 'string' ? out.stop_reason : ''
  const diffSummary = typeof out.diff_summary === 'string' ? out.diff_summary : ''
  const files: FileChange[] = Array.isArray(out.files_changed) ? (out.files_changed as FileChange[]) : []

  const [textOpen, setTextOpen] = useState(false)

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3 px-3 py-2 rounded-lg bg-slate-900/40 border border-slate-700/40 text-xs">
        {iters !== null && <span className="text-slate-300">{iters} итер.</span>}
        {cost !== null && <span className="text-slate-300 font-mono">${cost.toFixed(4)}</span>}
        {stopReason && <span className="text-slate-500 font-mono">{stopReason}</span>}
        {diffSummary && <span className="ml-auto text-green-400 font-medium">{diffSummary}</span>}
      </div>

      {files.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-500 font-medium mb-1.5">Что изменено</p>
          <div className="space-y-0.5">
            {files.map((f, i) => {
              const Icon = f.status === 'new' ? FilePlus : f.status === 'deleted' ? Trash2 : FileText
              const colorByStatus =
                f.status === 'new' ? 'text-green-400'
                : f.status === 'deleted' ? 'text-red-400'
                : 'text-slate-400'
              return (
                <div key={f.path ?? i} className="flex items-center gap-2 text-xs font-mono py-0.5">
                  <Icon className={clsx('w-3 h-3 flex-shrink-0', colorByStatus)} />
                  <span className="text-slate-300 truncate flex-1" title={f.path}>{f.path ?? '?'}</span>
                  {(f.insertions ?? 0) > 0 && <span className="text-green-400">+{f.insertions}</span>}
                  {(f.deletions ?? 0) > 0 && <span className="text-red-400">−{f.deletions}</span>}
                </div>
              )
            })}
          </div>
        </div>
      )}

      {finalText && (
        <div>
          <button type="button" onClick={() => setTextOpen(v => !v)}
            className="flex items-center gap-1.5 text-[10px] text-slate-500 hover:text-slate-300 transition-colors uppercase tracking-wide">
            {textOpen ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            Текст агента
            <span className="text-slate-600 normal-case ml-1">({finalText.length} симв.)</span>
          </button>
          {textOpen && (
            <pre className="mt-1 text-[11px] text-slate-300 bg-slate-950/60 border border-slate-800/60 rounded px-3 py-2 whitespace-pre-wrap break-words leading-relaxed max-h-96 overflow-auto">
              {finalText}
            </pre>
          )}
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const summary = typeof out.diff_summary === 'string' ? out.diff_summary : ''
    if (summary) return { label: summary, ok: true }
    const text = typeof out.final_text === 'string' ? out.final_text : ''
    if (text) {
      const firstLine = text.split('\n').find(l => l.trim()) ?? ''
      const preview = firstLine.length > 60 ? firstLine.slice(0, 60) + '…' : firstLine
      return { label: preview || `${text.length} chars`, ok: true }
    }
    return { label: '—' }
  },
  renderOutput: (out) => <ImplServerOutput out={out} />,
}
