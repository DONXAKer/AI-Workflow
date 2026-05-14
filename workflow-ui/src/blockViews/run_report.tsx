import { CheckCircle, XCircle, AlertTriangle, HelpCircle, DollarSign, Clock, Flag } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

interface ChecklistItem {
  id?: string
  criterion?: string
  priority?: string
  status?: string  // pass | fail | unknown | skip
  evidence?: string
}

function statusIcon(status: string | undefined) {
  if (status === 'pass') return <CheckCircle className="w-3.5 h-3.5 text-green-400 flex-shrink-0" />
  if (status === 'fail') return <XCircle className="w-3.5 h-3.5 text-red-400 flex-shrink-0" />
  if (status === 'skip') return <AlertTriangle className="w-3.5 h-3.5 text-amber-400 flex-shrink-0" />
  return <HelpCircle className="w-3.5 h-3.5 text-slate-500 flex-shrink-0" />
}

function statusBg(status: string | undefined) {
  if (status === 'pass') return 'bg-green-950/20 border-green-900/40'
  if (status === 'fail') return 'bg-red-950/30 border-red-800/50'
  if (status === 'skip') return 'bg-amber-950/20 border-amber-900/40'
  return 'bg-slate-900/40 border-slate-800/40'
}

function fmtDuration(ms: unknown): string {
  if (typeof ms !== 'number' || ms <= 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rs = s % 60
  if (m < 60) return `${m}m ${rs}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

function fmtCost(usd: unknown): string {
  if (typeof usd !== 'number') return '—'
  if (usd === 0) return '$0 (локально)'
  if (usd < 0.01) return `$${usd.toFixed(4)}`
  return `$${usd.toFixed(2)}`
}

function RunReport({ out }: { out: Record<string, unknown> }) {
  const finalStatus = typeof out.final_status === 'string' ? out.final_status : ''
  const flags = Array.isArray(out.flags) ? (out.flags as string[]) : []
  const checklist = Array.isArray(out.acceptance_checklist) ? (out.acceptance_checklist as ChecklistItem[]) : []
  const cost = (out.cost as Record<string, unknown> | undefined)?.total_usd
  const testCov = out.test_coverage as Record<string, unknown> | undefined
  const codegen = out.codegen as Record<string, unknown> | undefined
  const ci = out.ci as Record<string, unknown> | undefined
  const deploy = out.deploy as Record<string, unknown> | undefined

  const passed = checklist.filter(c => c.status === 'pass').length
  const failed = checklist.filter(c => c.status === 'fail').length
  const unknown = checklist.filter(c => c.status === 'unknown').length

  const headerColor = finalStatus === 'success'
    ? 'bg-green-950/30 border-green-800/50 text-green-300'
    : finalStatus === 'with_warnings'
      ? 'bg-amber-950/30 border-amber-800/50 text-amber-300'
      : finalStatus === 'failed_upstream'
        ? 'bg-red-950/30 border-red-800/50 text-red-300'
        : 'bg-slate-900/40 border-slate-700 text-slate-300'

  const headerLabel = finalStatus === 'success' ? 'Успешно'
    : finalStatus === 'with_warnings' ? 'С предупреждениями'
      : finalStatus === 'failed_upstream' ? 'Падение upstream'
        : finalStatus || '—'

  return (
    <div className="space-y-3">
      {/* Header */}
      <div className={clsx('flex items-center gap-3 px-3 py-2 rounded-lg border', headerColor)}>
        <span className="text-sm font-semibold uppercase tracking-wide">{headerLabel}</span>
        <span className="text-xs ml-auto flex items-center gap-3 text-slate-400">
          <span className="flex items-center gap-1"><Clock className="w-3 h-3" />{fmtDuration(out.duration_ms)}</span>
          <span className="flex items-center gap-1"><DollarSign className="w-3 h-3" />{fmtCost(cost)}</span>
        </span>
      </div>

      {/* Acceptance checklist */}
      {checklist.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium flex items-center gap-2">
            <span>Acceptance checklist</span>
            <span className="text-slate-500">·</span>
            {passed > 0 && <span className="text-green-400">✓ {passed}</span>}
            {failed > 0 && <span className="text-red-400">✗ {failed}</span>}
            {unknown > 0 && <span className="text-slate-500">? {unknown}</span>}
          </p>
          {checklist.map((item, i) => (
            <div key={item.id ?? i} className={clsx('rounded-lg border px-2.5 py-1.5 flex items-start gap-2', statusBg(item.status))}>
              {statusIcon(item.status)}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  {item.id && <span className="font-mono text-[10px] text-slate-500">{item.id}</span>}
                  {item.priority && (
                    <span className={clsx(
                      'text-[9px] px-1 py-0.5 rounded border uppercase tracking-wide',
                      item.priority === 'critical' ? 'bg-red-950/50 border-red-800 text-red-300'
                        : item.priority === 'important' ? 'bg-amber-950/50 border-amber-800 text-amber-300'
                          : 'bg-slate-800 border-slate-700 text-slate-400',
                    )}>
                      {item.priority}
                    </span>
                  )}
                </div>
                {item.criterion && <p className="text-xs text-slate-200">{item.criterion}</p>}
                {item.evidence && <p className="text-[10px] text-slate-400 mt-0.5 italic">{item.evidence}</p>}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Test coverage */}
      {testCov && Object.keys(testCov).length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1">Test coverage</p>
          <div className="text-xs text-slate-300 px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg space-x-3">
            {testCov.planned != null && <span>planned: <b>{String(testCov.planned)}</b></span>}
            {testCov.generated != null && <span>generated: <b>{String(testCov.generated)}</b></span>}
            {testCov.passing != null && <span>passing: <b>{String(testCov.passing)}</b></span>}
          </div>
        </div>
      )}

      {/* Codegen / CI / Deploy summary */}
      {(codegen && Object.keys(codegen).length > 0) || (ci && Object.keys(ci).length > 0) || (deploy && Object.keys(deploy).length > 0) ? (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
          {codegen && Object.keys(codegen).length > 0 && (
            <div className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg">
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Codegen</p>
              {codegen.branch != null && <p className="text-xs text-slate-300 truncate">branch: <span className="font-mono">{String(codegen.branch)}</span></p>}
              {codegen.files_changed != null && <p className="text-xs text-slate-300">files: <b>{String(codegen.files_changed)}</b></p>}
              {codegen.diff_summary != null && <p className="text-[10px] text-slate-400 truncate">{String(codegen.diff_summary)}</p>}
            </div>
          )}
          {ci && Object.keys(ci).length > 0 && (
            <div className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg">
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">CI</p>
              {ci.status != null && <p className="text-xs text-slate-300">status: <b>{String(ci.status)}</b></p>}
              {ci.duration_ms != null && <p className="text-[10px] text-slate-400">{fmtDuration(ci.duration_ms)}</p>}
            </div>
          )}
          {deploy && Object.keys(deploy).length > 0 && (
            <div className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg">
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Deploy</p>
              {Object.entries(deploy).map(([env, status]) => (
                <p key={env} className="text-xs text-slate-300">{env}: <b>{String(status)}</b></p>
              ))}
            </div>
          )}
        </div>
      ) : null}

      {/* Flags */}
      {flags.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-amber-400 font-medium mb-1 flex items-center gap-1">
            <Flag className="w-3 h-3" />Flags
          </p>
          <ul className="space-y-1">
            {flags.map((f, i) => (
              <li key={i} className="text-xs text-amber-300 px-2.5 py-1 bg-amber-950/20 border border-amber-900/40 rounded">
                {f}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const status = typeof out.final_status === 'string' ? out.final_status : ''
    const flags = Array.isArray(out.flags) ? (out.flags as unknown[]).length : 0
    if (status === 'success') return { label: 'Успешно', ok: true }
    if (status === 'with_warnings') return { label: `С предупреждениями (${flags})`, warn: true }
    if (status === 'failed_upstream') return { label: 'Падение upstream', fail: true }
    return { label: status || '—' }
  },
  renderOutput: (out) => <RunReport out={out} />,
}
