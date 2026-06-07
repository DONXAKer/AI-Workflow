import { FilePlus, FileText, Trash2 } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface Change {
  file_path?: string
  status?: 'new' | 'modified' | 'deleted' | string
}

function CodeGenOutput({ out }: { out: Record<string, unknown> }) {
  const branch = typeof out.branch_name === 'string' ? out.branch_name.trim() : ''
  const commit = typeof out.commit_message === 'string' ? out.commit_message.trim() : ''
  const changes: Change[] = Array.isArray(out.changes) ? (out.changes as Change[]) : []
  const testChanges: Change[] = Array.isArray(out.test_changes) ? (out.test_changes as Change[]) : []
  const allFiles = [...changes, ...testChanges]
  const parseFailures = typeof out.parse_failures === 'number' ? out.parse_failures : 0

  return (
    <div className="space-y-3 min-w-0">
      {/* Branch + commit */}
      <div className="space-y-1">
        {branch && (
          <div className="flex items-center gap-2 text-xs">
            <span className="text-slate-500 flex-shrink-0">ветка</span>
            <code className="font-mono text-blue-300 bg-blue-950/30 px-1.5 py-0.5 rounded text-[11px] truncate">{branch}</code>
          </div>
        )}
        {commit && (
          <div className="flex items-start gap-2 text-xs">
            <span className="text-slate-500 flex-shrink-0 mt-0.5">коммит</span>
            <span className="text-slate-200 whitespace-pre-wrap break-words leading-snug">{commit}</span>
          </div>
        )}
      </div>

      {/* File list */}
      {allFiles.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-500 font-medium mb-1.5">
            Файлы ({allFiles.length})
          </p>
          <div className="space-y-0.5">
            {allFiles.map((f, i) => {
              const Icon = f.status === 'new' ? FilePlus : f.status === 'deleted' ? Trash2 : FileText
              const color = f.status === 'new' ? 'text-green-400' : f.status === 'deleted' ? 'text-red-400' : 'text-slate-400'
              return (
                <div key={f.file_path ?? i} className="flex items-center gap-2 text-xs font-mono py-0.5">
                  <Icon className={clsx('w-3 h-3 flex-shrink-0', color)} />
                  <span className="text-slate-300 truncate flex-1 min-w-0" title={f.file_path}>{f.file_path ?? '?'}</span>
                  {f.status && f.status !== 'modified' && (
                    <span className={clsx('text-[10px] flex-shrink-0', color)}>{f.status}</span>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      )}

      {parseFailures > 0 && (
        <p className="text-[11px] text-amber-400 font-mono">⚠ {parseFailures} задач не распознано</p>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const changes = Array.isArray(out.changes) ? out.changes.length : 0
    const testChanges = Array.isArray(out.test_changes) ? out.test_changes.length : 0
    const total = changes + testChanges
    const branch = typeof out.branch_name === 'string' ? out.branch_name.trim() : ''
    if (total > 0) return { label: `${total} файл${total === 1 ? '' : total < 5 ? 'а' : 'ов'}`, ok: true }
    if (branch) return { label: branch, ok: true }
    return { label: 'codegen' }
  },
  renderOutput: (out) => <CodeGenOutput out={out} />,
}
