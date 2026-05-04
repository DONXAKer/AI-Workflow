import { useState, Fragment } from 'react'
import { ChevronDown, ChevronRight, CheckCircle, XCircle } from 'lucide-react'
import clsx from 'clsx'
import { LlmCallEntry, ToolCallEntry } from '../types'
import { blockIdLabel } from '../utils/blockLabels'
import { ProviderBadge, FinishReasonChip, TOOL_COLORS, summarizeInput } from './BlockProgressTable'

interface Props {
  llmCalls: LlmCallEntry[]
  toolCalls: ToolCallEntry[]
}

/**
 * Single comprehensive table with every LLM iteration in the run.
 *
 * <p>Each row collapses to one line (block, iter, provider, model, tokens, cost, duration,
 * tool count, finishReason). Click a row to expand and see the actual tool calls (name,
 * input summary, success/error icon, duration, error output) — the same detail that the
 * per-block IterationsPanel shows, but in one flat list ordered by start time.
 */
export default function AllIterationsTable({ llmCalls, toolCalls }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  if (llmCalls.length === 0) {
    return (
      <div className="text-center text-slate-500 text-sm py-12">
        Итерации появятся когда отработает первый агентный блок.
      </div>
    )
  }

  // Group tool calls by (blockId, iteration) for both the count column and the expanded rows.
  const toolsByKey = new Map<string, ToolCallEntry[]>()
  for (const tc of toolCalls) {
    const key = `${tc.blockId}:${tc.iteration}`
    if (!toolsByKey.has(key)) toolsByKey.set(key, [])
    toolsByKey.get(key)!.push(tc)
  }

  const toggle = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-slate-950/60 border-b border-slate-800">
            <tr className="text-left">
              <Th className="w-8" />
              <Th className="w-10 text-center">#</Th>
              <Th>Block</Th>
              <Th className="w-12 text-center">Iter</Th>
              <Th>Provider</Th>
              <Th>Model</Th>
              <Th className="text-right">Tokens (in/out)</Th>
              <Th className="text-right">Cost</Th>
              <Th className="text-right">Duration</Th>
              <Th className="text-center">Tools</Th>
              <Th>Finish</Th>
            </tr>
          </thead>
          <tbody>
            {llmCalls.map((c, idx) => {
              const key = `${c.blockId}:${c.iteration}`
              const tools = toolsByKey.get(key) ?? []
              const hasErr = tools.some(t => t.isError)
              const isOpen = expanded.has(key)
              const shortModel = c.model.replace(/^[^/]+\//, '')

              return (
                <Fragment key={idx}>
                  <tr
                    className={clsx(
                      'border-b border-slate-800/40 cursor-pointer transition-colors',
                      isOpen ? 'bg-slate-800/30' : 'hover:bg-slate-800/20'
                    )}
                    onClick={() => toggle(key)}
                  >
                    <Td className="text-center">
                      {tools.length > 0
                        ? (isOpen
                            ? <ChevronDown className="w-3 h-3 text-slate-400 inline-block" />
                            : <ChevronRight className="w-3 h-3 text-slate-500 inline-block" />)
                        : null}
                    </Td>
                    <Td className="text-center text-slate-500 font-mono text-xs">{idx + 1}</Td>
                    <Td className="text-slate-300 font-mono text-xs">{blockIdLabel(c.blockId)}</Td>
                    <Td className="text-center text-slate-400 font-mono text-xs">{c.iteration}</Td>
                    <Td><ProviderBadge provider={c.provider} /></Td>
                    <Td>
                      <span className="text-[11px] font-mono px-1.5 py-0.5 rounded bg-violet-950/50 border border-violet-800/50 text-violet-300">
                        {shortModel}
                      </span>
                    </Td>
                    <Td className="text-right text-xs text-slate-400 font-mono">
                      {c.tokensIn > 0
                        ? `${(c.tokensIn / 1000).toFixed(1)}K / ${(c.tokensOut / 1000).toFixed(1)}K`
                        : <span className="text-slate-600">—</span>}
                    </Td>
                    <Td className="text-right text-xs text-slate-400 font-mono">
                      {c.costUsd > 0 ? `$${c.costUsd.toFixed(4)}` : <span className="text-slate-600">—</span>}
                    </Td>
                    <Td className="text-right text-xs text-slate-500 font-mono">{c.durationMs}ms</Td>
                    <Td className="text-center text-xs">
                      {tools.length > 0 ? (
                        <span className={hasErr ? 'text-red-400 font-medium' : 'text-slate-400'}>
                          {tools.length}{hasErr && '!'}
                        </span>
                      ) : (
                        <span className="text-slate-600">—</span>
                      )}
                    </Td>
                    <Td><FinishReasonChip reason={c.finishReason} /></Td>
                  </tr>
                  {isOpen && tools.length > 0 && (
                    <tr className="border-b border-slate-800/40 bg-slate-950/40">
                      <td colSpan={11} className="px-6 py-2">
                        <div className="divide-y divide-slate-800/40">
                          {tools.map((t, i) => {
                            const colorCls = TOOL_COLORS[t.toolName] ?? 'text-slate-400 bg-slate-800/40 border-slate-700'
                            const summary = summarizeInput(t.toolName, t.inputJson)
                            return (
                              <div key={i} className="py-1.5 text-xs">
                                <div className="flex items-center gap-2">
                                  <span className={clsx('px-1.5 py-0.5 rounded border text-[10px] font-mono font-medium flex-shrink-0', colorCls)}>
                                    {t.toolName}
                                  </span>
                                  <span className="font-mono text-slate-400 truncate flex-1 min-w-0">{summary}</span>
                                  {t.isError
                                    ? <XCircle className="w-3 h-3 text-red-400 flex-shrink-0" />
                                    : <CheckCircle className="w-3 h-3 text-green-600/70 flex-shrink-0" />}
                                  <span className="text-slate-600 text-[10px] flex-shrink-0">{t.durationMs}ms</span>
                                </div>
                                {t.isError && t.outputText && (
                                  <pre className="mt-1 ml-7 text-[10px] text-red-300/80 bg-red-950/30 border border-red-900/40 rounded px-2 py-1 whitespace-pre-wrap break-all leading-relaxed max-h-32 overflow-auto">
                                    {t.outputText}
                                  </pre>
                                )}
                              </div>
                            )
                          })}
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="px-4 py-2 text-[11px] text-slate-500 border-t border-slate-800 flex items-center justify-between">
        <span>{llmCalls.length} итерац{llmCalls.length === 1 ? 'ия' : llmCalls.length < 5 ? 'ии' : 'ий'} · {toolCalls.length} tool calls · клик по строке — раскрыть детали</span>
        <span>Сортировка: по времени старта</span>
      </div>
    </div>
  )
}

function Th({ children, className = '' }: { children?: React.ReactNode; className?: string }) {
  return <th className={`px-3 py-2 text-[11px] font-medium text-slate-400 uppercase tracking-wide ${className}`}>{children}</th>
}

function Td({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-3 py-2 ${className}`}>{children}</td>
}
