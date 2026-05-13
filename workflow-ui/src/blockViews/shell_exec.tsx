import { useState } from 'react'
import { CheckCircle, XCircle } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

function ShellOutput({ out }: { out: Record<string, unknown> }) {
  const success = out.success === true
  const exitCode = typeof out.exit_code === 'number' ? out.exit_code : null
  const stdout = typeof out.stdout === 'string' ? out.stdout.trim() : ''
  const stderr = typeof out.stderr === 'string' ? out.stderr.trim() : ''

  return (
    <div className="space-y-3">
      <div className={clsx(
        'flex items-center gap-2 px-3 py-2 rounded-lg border',
        success ? 'bg-green-950/30 border-green-800/50' : 'bg-red-950/30 border-red-800/50',
      )}>
        {success
          ? <CheckCircle className="w-4 h-4 text-green-400 flex-shrink-0" />
          : <XCircle className="w-4 h-4 text-red-400 flex-shrink-0" />}
        <span className={clsx('text-sm font-semibold', success ? 'text-green-300' : 'text-red-300')}>
          {success ? 'Успешно' : `Ошибка${exitCode !== null ? ` (exit ${exitCode})` : ''}`}
        </span>
        {typeof out.duration_ms === 'number' && (
          <span className="text-xs text-slate-500 ml-auto font-mono">{(out.duration_ms / 1000).toFixed(1)}s</span>
        )}
      </div>

      {stdout && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-500 font-medium mb-1">stdout</p>
          <OutputPre text={stdout} />
        </div>
      )}

      {stderr && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium mb-1">stderr</p>
          <OutputPre text={stderr} error />
        </div>
      )}
    </div>
  )
}

function OutputPre({ text, error }: { text: string; error?: boolean }) {
  const [expanded, setExpanded] = useState(false)
  const lines = text.split('\n')
  const limit = 30
  const needsTruncate = lines.length > limit

  const displayed = needsTruncate && !expanded ? lines.slice(-limit).join('\n') : text
  return (
    <div>
      {needsTruncate && !expanded && (
        <button type="button" onClick={() => setExpanded(true)}
          className="mb-1 text-[10px] text-blue-400 hover:text-blue-300 transition-colors">
          ... показать все {lines.length} строк
        </button>
      )}
      <pre className={clsx(
        'text-[10px] font-mono rounded px-2 py-1.5 whitespace-pre-wrap break-all overflow-auto max-h-64 leading-relaxed',
        error
          ? 'text-red-300/80 bg-red-950/20 border border-red-900/40'
          : 'text-slate-300 bg-slate-950/60 border border-slate-800/60',
      )}>
        {displayed}
      </pre>
      {needsTruncate && expanded && (
        <button type="button" onClick={() => setExpanded(false)}
          className="mt-1 text-[10px] text-slate-500 hover:text-slate-300 transition-colors">
          Свернуть
        </button>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    if (out.success === true) return { label: 'exit 0', ok: true }
    const code = typeof out.exit_code === 'number' ? out.exit_code : null
    if (out.success === false) return { label: code !== null ? `exit ${code}` : 'failed', fail: true }
    return { label: '—' }
  },
  renderOutput: (out) => <ShellOutput out={out} />,
}
