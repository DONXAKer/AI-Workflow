import { CheckCircle, XCircle, AlertTriangle, FolderOpen, HardDrive } from 'lucide-react'
import clsx from 'clsx'
import type { BlockViewSpec } from './index'

const STATUS_LABEL: Record<string, string> = {
  PASSED: 'Зелёный baseline',
  WARNING: 'С предупреждениями',
  RED_BLOCKED: 'Красный baseline (заблокировано)',
}

const PHASE_STATUS_LABEL: Record<string, string> = {
  ok: 'успешно',
  failed: 'упало',
  skipped: 'пропущено',
}

const SOURCE_LABEL: Record<string, string> = {
  claude_md: 'CLAUDE.md ## Preflight',
  auto_detect: 'auto-detect',
  fallback: 'не найдено (skip)',
}

function PreflightView({ out }: { out: Record<string, unknown> }) {
  const status = typeof out.status === 'string' ? out.status : ''
  const buildStatus = typeof out.build_status === 'string' ? out.build_status : ''
  const testStatus = typeof out.test_status === 'string' ? out.test_status : ''
  const source = typeof out.preflight_source === 'string' ? out.preflight_source : ''
  const detected = typeof out.preflight_detected === 'string' ? out.preflight_detected : ''
  const cached = out.cached === true
  const cacheSha = typeof out.cache_source_sha === 'string' ? out.cache_source_sha : ''
  const durationMs = typeof out.duration_ms === 'number' ? out.duration_ms : 0
  const failures: string[] = Array.isArray(out.baseline_failures) ? (out.baseline_failures as string[]) : []
  const commands = out.commands as Record<string, unknown> | undefined
  const logExcerpt = typeof out.log_excerpt === 'string' ? out.log_excerpt : ''

  const headerColor = status === 'PASSED'
    ? 'bg-green-950/30 border-green-800/50 text-green-300'
    : status === 'WARNING'
      ? 'bg-amber-950/30 border-amber-800/50 text-amber-300'
      : status === 'RED_BLOCKED'
        ? 'bg-red-950/30 border-red-800/50 text-red-300'
        : 'bg-slate-900/40 border-slate-700 text-slate-300'

  const StatusIcon = status === 'PASSED' ? CheckCircle : status === 'RED_BLOCKED' ? XCircle : AlertTriangle

  return (
    <div className="space-y-3">
      {/* Status header */}
      <div className={clsx('flex items-center gap-3 px-3 py-2 rounded-lg border', headerColor)}>
        <StatusIcon className="w-5 h-5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <span className="text-sm font-semibold">{STATUS_LABEL[status] ?? status}</span>
        </div>
        <span className="text-[11px] text-slate-400 flex items-center gap-3">
          {cached && (
            <span className="flex items-center gap-1"><HardDrive className="w-3 h-3" />из кэша</span>
          )}
          {durationMs > 0 && !cached && (
            <span>{Math.round(durationMs / 1000)}s</span>
          )}
        </span>
      </div>

      {/* Build + test status */}
      <div className="grid grid-cols-2 gap-2">
        <div className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg">
          <p className="text-[10px] uppercase tracking-wide text-slate-400">Сборка</p>
          <p className={clsx('text-sm font-medium',
            buildStatus === 'ok' ? 'text-green-400' :
            buildStatus === 'failed' ? 'text-red-400' :
            'text-slate-500')}>
            {PHASE_STATUS_LABEL[buildStatus] ?? buildStatus ?? '—'}
          </p>
        </div>
        <div className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg">
          <p className="text-[10px] uppercase tracking-wide text-slate-400">Тесты</p>
          <p className={clsx('text-sm font-medium',
            testStatus === 'ok' ? 'text-green-400' :
            testStatus === 'failed' ? 'text-red-400' :
            'text-slate-500')}>
            {PHASE_STATUS_LABEL[testStatus] ?? testStatus ?? '—'}
          </p>
        </div>
      </div>

      {/* Baseline failures */}
      {failures.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-red-400 font-medium mb-1">
            Падающие тесты на baseline ({failures.length})
          </p>
          <div className="px-2.5 py-1.5 bg-red-950/20 border border-red-900/40 rounded-lg max-h-64 overflow-y-auto">
            <ul className="space-y-0.5">
              {failures.map((f, i) => (
                <li key={i} className="text-[11px] font-mono text-red-300">• {f}</li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {/* Source + commands */}
      <div>
        <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1 flex items-center gap-1">
          <FolderOpen className="w-3 h-3" />Источник команд
        </p>
        <div className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg space-y-1 text-xs text-slate-300">
          <p>Откуда: <span className="text-slate-200">{SOURCE_LABEL[source] ?? source}</span>{detected && ` (${detected})`}</p>
          {commands && (
            <>
              {commands.build != null && <p className="font-mono text-[11px]">build: <span className="text-slate-100">{String(commands.build) || '—'}</span></p>}
              {commands.test != null && <p className="font-mono text-[11px]">test: <span className="text-slate-100">{String(commands.test) || '—'}</span></p>}
              {commands.fqn_format != null && <p className="text-[11px]">формат FQN: <span className="text-slate-200">{String(commands.fqn_format)}</span></p>}
            </>
          )}
          {cacheSha && <p className="text-[10px] text-slate-500 font-mono">SHA: {cacheSha.substring(0, 12)}...</p>}
        </div>
      </div>

      {/* Log tail */}
      {logExcerpt && (
        <details className="group">
          <summary className="text-[10px] uppercase tracking-wide text-slate-400 cursor-pointer hover:text-slate-200">
            Лог (хвост · {logExcerpt.length} chars)
          </summary>
          <pre className="text-[10px] font-mono text-slate-400 bg-slate-950/60 border border-slate-800 rounded p-2 mt-1 max-h-96 overflow-auto whitespace-pre-wrap">{logExcerpt}</pre>
        </details>
      )}
    </div>
  )
}

export const spec: BlockViewSpec = {
  summary: (out) => {
    const status = typeof out.status === 'string' ? out.status : ''
    const cached = out.cached === true
    const fails = Array.isArray(out.baseline_failures) ? (out.baseline_failures as unknown[]).length : 0
    const buildStatus = typeof out.build_status === 'string' ? out.build_status : ''
    const testStatus = typeof out.test_status === 'string' ? out.test_status : ''
    const source = typeof out.preflight_source === 'string' ? out.preflight_source : ''
    const suffix = cached ? ' · из кэша' : ''

    if (status === 'PASSED') return { label: `✓ baseline зелёный${suffix}`, ok: true }

    if (status === 'WARNING') {
      if (source === 'fallback') return { label: `⚠ manifests не найдены${suffix}`, warn: true }
      if (buildStatus === 'failed') return { label: `⚠ сборка упала${suffix}`, warn: true }
      if (testStatus === 'failed') {
        return { label: fails > 0 ? `⚠ ${fails} тестов упало${suffix}` : `⚠ тесты упали (FQN не извлечены)${suffix}`, warn: true }
      }
      return { label: `⚠ baseline с предупреждениями${suffix}`, warn: true }
    }

    if (status === 'RED_BLOCKED') {
      if (buildStatus === 'failed') return { label: `✗ сборка упала${suffix}`, fail: true }
      if (testStatus === 'failed') {
        return { label: fails > 0 ? `✗ ${fails} тестов упало${suffix}` : `✗ тесты упали${suffix}`, fail: true }
      }
      return { label: `✗ baseline красный${suffix}`, fail: true }
    }

    return { label: status || '—' }
  },
  renderOutput: (out) => <PreflightView out={out} />,
}
