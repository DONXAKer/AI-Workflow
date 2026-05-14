import { Code, FileText, Lightbulb } from 'lucide-react'
import type { BlockViewSpec } from './index'

interface BestPractice {
  rule?: string
  source?: string
  confidence?: number
}

const LANG_LABEL: Record<string, string> = {
  java: 'Java',
  python: 'Python',
  typescript: 'TypeScript',
  javascript: 'JavaScript',
  go: 'Go',
  rust: 'Rust',
  cpp: 'C++',
  unknown: 'не определён',
}

function ContextScanView({ out }: { out: Record<string, unknown> }) {
  const language = typeof out.language === 'string' ? out.language : 'unknown'
  const stack = (out.tech_stack as Record<string, unknown> | undefined) ?? {}
  const conventions = Array.isArray(out.code_conventions) ? (out.code_conventions as string[]) : []
  const bestPractices = Array.isArray(out.applicable_best_practices) ? (out.applicable_best_practices as BestPractice[]) : []
  const suggestions = Array.isArray(out.suggestions_for_codegen) ? (out.suggestions_for_codegen as string[]) : []
  const sampled = typeof out.source_files_sampled === 'number' ? out.source_files_sampled : 0

  const langRu = LANG_LABEL[language] ?? language
  const framework = typeof stack.framework === 'string' ? stack.framework : ''
  const buildTool = typeof stack.build_tool === 'string' ? stack.build_tool : ''
  const keyDeps = Array.isArray(stack.key_deps) ? (stack.key_deps as string[]) : []

  return (
    <div className="space-y-3">
      {/* Tech stack header */}
      <div className="px-3 py-2 bg-slate-900/40 border border-slate-700 rounded-lg">
        <div className="flex items-center gap-3 flex-wrap">
          <Code className="w-5 h-5 text-slate-400" />
          <span className="text-sm font-semibold text-slate-200">{langRu}</span>
          {framework && (
            <>
              <span className="text-slate-600">·</span>
              <span className="text-xs text-slate-400">framework: <span className="text-slate-200">{framework}</span></span>
            </>
          )}
          {buildTool && (
            <>
              <span className="text-slate-600">·</span>
              <span className="text-xs text-slate-400">build: <span className="text-slate-200">{buildTool}</span></span>
            </>
          )}
          <span className="text-[11px] text-slate-500 ml-auto">проанализировано файлов: {sampled}</span>
        </div>
        {keyDeps.length > 0 && (
          <div className="mt-1.5 flex flex-wrap gap-1">
            {keyDeps.map((d, i) => (
              <span key={i} className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 border border-slate-700 text-slate-300 font-mono">
                {d}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* Conventions */}
      {conventions.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1 flex items-center gap-1">
            <FileText className="w-3 h-3" />Conventions проекта ({conventions.length})
          </p>
          <ul className="space-y-1">
            {conventions.map((c, i) => (
              <li key={i} className="text-xs text-slate-300 px-2.5 py-1 bg-slate-900/40 border border-slate-800 rounded">
                {c}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Applicable best practices */}
      {bestPractices.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-slate-400 font-medium mb-1">
            Best practices применимые здесь ({bestPractices.length})
          </p>
          <div className="space-y-1">
            {bestPractices.map((bp, i) => (
              <div key={i} className="px-2.5 py-1.5 bg-slate-900/40 border border-slate-800 rounded-lg">
                <div className="flex items-start gap-2">
                  <p className="text-xs text-slate-200 flex-1">{bp.rule}</p>
                  {typeof bp.confidence === 'number' && (
                    <span className="text-[10px] text-slate-500 font-mono whitespace-nowrap">
                      доверие: {Math.round(bp.confidence * 100)}%
                    </span>
                  )}
                </div>
                {bp.source && <p className="text-[10px] text-slate-500 mt-0.5">источник: {bp.source}</p>}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Suggestions for codegen */}
      {suggestions.length > 0 && (
        <div>
          <p className="text-[10px] uppercase tracking-wide text-blue-400 font-medium mb-1 flex items-center gap-1">
            <Lightbulb className="w-3 h-3" />Подсказки для codegen ({suggestions.length})
          </p>
          <ul className="space-y-1">
            {suggestions.map((s, i) => (
              <li key={i} className="text-xs text-blue-200 px-2.5 py-1 bg-blue-950/20 border border-blue-900/40 rounded">
                {s}
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
    const lang = typeof out.language === 'string' ? out.language : '?'
    const conv = Array.isArray(out.code_conventions) ? (out.code_conventions as unknown[]).length : 0
    const bp = Array.isArray(out.applicable_best_practices) ? (out.applicable_best_practices as unknown[]).length : 0
    const langRu = LANG_LABEL[lang] ?? lang
    return { label: `${langRu} · ${conv} conv · ${bp} bp`, ok: lang !== 'unknown' }
  },
  renderOutput: (out) => <ContextScanView out={out} />,
}
