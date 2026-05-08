import { useState, useCallback, useMemo } from 'react'
import { CheckCircle, Edit3, XCircle, SkipForward, ArrowRight, AlertCircle, X, ShieldCheck } from 'lucide-react'
import { WsMessage, ApprovalDecision, ApprovalDecisionType } from '../types'
import clsx from 'clsx'
import BlockOutputViewer from './BlockOutputViewer'
import { StructuredOutput } from './BlockProgressTable'
import { getBlockView } from '../blockViews/index'
import { blockIdLabel } from '../utils/blockLabels'

interface VerifyFailedItem {
  item_id: string
  text?: string
  priority?: string
  evidence?: string
}

/**
 * Detects the agent_verify "manual override" approval — raised by the backend
 * when verify exhausted max_iterations and the operator must decide whether
 * to override failing items as PASS or hard-fail the run.
 */
function detectVerifyOverride(output: Record<string, unknown> | undefined): VerifyFailedItem[] | null {
  if (!output) return null
  const failed = output.failed_items
  if (!Array.isArray(failed) || failed.length === 0) return null
  const passed = output.passed
  if (passed === true) return null
  const items: VerifyFailedItem[] = []
  for (const f of failed) {
    if (typeof f !== 'object' || f === null) continue
    const obj = f as Record<string, unknown>
    const id = obj.item_id
    if (typeof id !== 'string' || !id) continue
    items.push({
      item_id: id,
      text: typeof obj.text === 'string' ? obj.text : undefined,
      priority: typeof obj.priority === 'string' ? obj.priority : undefined,
      evidence: typeof obj.evidence === 'string' ? obj.evidence : undefined,
    })
  }
  return items.length > 0 ? items : null
}

const PRIORITY_STYLES: Record<string, string> = {
  critical: 'bg-red-900/40 border-red-700 text-red-200',
  important: 'bg-amber-900/40 border-amber-700 text-amber-200',
  nice_to_have: 'bg-slate-800 border-slate-700 text-slate-400',
}

interface Props {
  approval: WsMessage
  remainingBlocks?: string[]
  onDecision: (decision: ApprovalDecision) => void
  /** Called when the user closes the dialog without making a decision. The run stays paused. */
  onDismiss: () => void
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function parseJsonSafe(str: string): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  try {
    const parsed = JSON.parse(str)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { ok: false, error: 'Значение должно быть JSON-объектом' }
    }
    return { ok: true, value: parsed as Record<string, unknown> }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : 'Невалидный JSON' }
  }
}

function extractBranchName(output: Record<string, unknown> | undefined): string | null {
  const stdout = typeof output?.stdout === 'string' ? output.stdout : ''
  const m = stdout.match(/(?:Создаём ветку|Создана ветка|переключаемся на)\s*:?\s*(feat\/\S+)/i)
    ?? stdout.match(/(feat\/[^\s\n]+)/)
  return m ? m[1].trim() : null
}

export default function ApprovalDialog({ approval, remainingBlocks = [], onDecision, onDismiss }: Props) {
  const blockId = approval.blockId ?? 'unknown'
  const initialJson = formatJson(approval.output ?? {})
  const branchName = blockId === 'create_branch' ? extractBranchName(approval.output as Record<string, unknown> | undefined) : null

  const [editMode, setEditMode] = useState(false)
  const [editedOutput, setEditedOutput] = useState(initialJson)
  const [jsonError, setJsonError] = useState<string | null>(null)
  const [jumpMode, setJumpMode] = useState(false)
  const [jumpTarget, setJumpTarget] = useState(remainingBlocks[0] ?? '')
  const [skipFuture, setSkipFuture] = useState(false)
  // Require a second click to confirm Reject (irreversible — stops the run)
  const [confirmReject, setConfirmReject] = useState(false)

  // agent_verify override mode: present when backend asks the operator
  // to override failing items as PASS after max_iterations was exhausted.
  const verifyFailedItems = useMemo(
    () => detectVerifyOverride(approval.output as Record<string, unknown> | undefined),
    [approval.output],
  )
  const [overrideIds, setOverrideIds] = useState<Set<string>>(new Set())
  const [overrideReason, setOverrideReason] = useState('')

  const toggleOverride = useCallback((id: string) => {
    setOverrideIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }, [])
  const overrideAll = useCallback(() => {
    if (!verifyFailedItems) return
    setOverrideIds(new Set(verifyFailedItems.map(i => i.item_id)))
  }, [verifyFailedItems])
  const overrideNone = useCallback(() => setOverrideIds(new Set()), [])

  const allOverridesCovered = verifyFailedItems !== null
    && verifyFailedItems.length > 0
    && verifyFailedItems.every(i => overrideIds.has(i.item_id))

  const handleJsonChange = useCallback((value: string) => {
    setEditedOutput(value)
    const result = parseJsonSafe(value)
    setJsonError(result.ok ? null : result.error)
  }, [])

  const handleDecision = useCallback((type: ApprovalDecisionType) => {
    if (type === 'EDIT') {
      const result = parseJsonSafe(editedOutput)
      if (!result.ok) {
        setJsonError(result.error)
        return
      }
      onDecision({ blockId, decision: 'EDIT', output: result.value })
      return
    }

    if (type === 'APPROVE') {
      const decision: ApprovalDecision = { blockId, decision: 'APPROVE' }
      if (skipFuture) decision.skipFuture = true
      // If in edit mode with valid JSON, include the edited output
      if (editMode) {
        const result = parseJsonSafe(editedOutput)
        if (result.ok) decision.output = result.value
      }
      // agent_verify override: encode operator-selected per-item overrides
      // so the backend records them in manual_overrides for audit.
      if (verifyFailedItems && overrideIds.size > 0) {
        decision.output = {
          ...(decision.output ?? {}),
          manual_overrides: Array.from(overrideIds),
          reason: overrideReason.trim() || null,
        }
      }
      onDecision(decision)
      return
    }

    if (type === 'JUMP') {
      if (!jumpTarget) return
      onDecision({ blockId, decision: 'JUMP', targetBlockId: jumpTarget })
      return
    }

    onDecision({ blockId, decision: type })
  }, [blockId, editedOutput, editMode, skipFuture, jumpTarget, onDecision])

  const toggleEdit = useCallback(() => {
    setEditMode(prev => {
      if (!prev) {
        // Entering edit mode: reset to current output
        setEditedOutput(initialJson)
        setJsonError(null)
      }
      return !prev
    })
    setJumpMode(false)
    setConfirmReject(false)
  }, [initialJson])

  const toggleJump = useCallback(() => {
    setJumpMode(prev => !prev)
    setEditMode(false)
    setConfirmReject(false)
  }, [])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm">
      <div className="bg-slate-900 border border-slate-700 rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-start justify-between px-6 py-5 border-b border-slate-800">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <AlertCircle className="w-5 h-5 text-amber-400" />
              <h2 className="text-base font-semibold text-white">Требуется одобрение</h2>
            </div>
            <p className="text-sm text-amber-200 font-medium">{blockIdLabel(blockId)}</p>
            <p className="text-xs text-slate-500 font-mono mt-0.5">{blockId}</p>
            {branchName && (
              <div className="flex items-center gap-2 mt-2 px-2.5 py-1.5 rounded-lg bg-slate-800 border border-slate-700 w-fit">
                <span className="text-[10px] text-slate-500 uppercase tracking-wide">Ветка</span>
                <span className="font-mono text-sm text-blue-300">{branchName}</span>
              </div>
            )}
            {!branchName && (approval.description ? (
              <p className="text-sm text-slate-400 mt-1">{approval.description}</p>
            ) : (
              <p className="text-sm text-slate-500 mt-1">Проверьте выход блока и примите решение о продолжении пайплайна.</p>
            ))}
          </div>
          <button
            type="button"
            onClick={onDismiss}
            className="p-1.5 rounded-lg text-slate-500 hover:text-white hover:bg-slate-800 transition-colors"
            title="Dismiss (keep run paused)"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body: scrollable */}
        <div className="flex-1 overflow-y-auto px-6 py-4 space-y-3">
          {/* Output display / edit */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="text-xs font-medium text-slate-500 uppercase tracking-wide">Вывод блока</label>
              <button
                type="button"
                onClick={toggleEdit}
                className={clsx(
                  'flex items-center gap-1.5 text-xs px-2 py-1 rounded-md border transition-colors',
                  editMode
                    ? 'bg-blue-900/50 border-blue-700 text-blue-300'
                    : 'bg-slate-800 border-slate-700 text-slate-500 hover:text-white hover:border-slate-600'
                )}
              >
                <Edit3 className="w-3 h-3" />
                {editMode ? 'Отменить' : 'Изменить'}
              </button>
            </div>

            {editMode ? (
              <div>
                <textarea
                  value={editedOutput}
                  onChange={e => handleJsonChange(e.target.value)}
                  rows={10}
                  spellCheck={false}
                  className={clsx(
                    'w-full bg-slate-950 border rounded-lg px-4 py-3 text-sm font-mono text-slate-200 focus:outline-none focus:ring-2 resize-y max-h-96 overflow-y-auto',
                    jsonError
                      ? 'border-red-600 focus:ring-red-500'
                      : 'border-slate-700 focus:ring-blue-500 focus:border-transparent'
                  )}
                />
                {jsonError && (
                  <p className="mt-1.5 text-xs text-red-400 flex items-center gap-1.5">
                    <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
                    {jsonError}
                  </p>
                )}
              </div>
            ) : (
              <div className="bg-slate-950 border border-slate-700/60 rounded-lg px-4 py-3 overflow-auto max-h-80">
                {(() => {
                  const out = (approval.output ?? {}) as Record<string, unknown>
                  const spec = getBlockView(blockId)
                  if (spec?.renderOutput) return spec.renderOutput(out)
                  if (spec?.fields) return <StructuredOutput output={out} specFields={spec.fields} />
                  return <BlockOutputViewer output={out} />
                })()}
              </div>
            )}
          </div>

          {/* agent_verify override panel — only when verify exhausted iterations */}
          {verifyFailedItems && (
            <div className="bg-amber-950/30 border border-amber-800/60 rounded-xl p-4 space-y-3">
              <div className="flex items-start gap-2">
                <ShieldCheck className="w-5 h-5 text-amber-400 flex-shrink-0 mt-0.5" />
                <div className="flex-1">
                  <h3 className="text-sm font-semibold text-amber-100">Override недопройденных пунктов</h3>
                  <p className="text-xs text-amber-300/80 mt-0.5">
                    Verify исчерпал лимит итераций. Отметьте пункты, которые считаете false-positive —
                    они будут засчитаны как PASS, остальные останутся FAIL и run упадёт.
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2 text-xs">
                <span className="text-slate-500">Выбрано: {overrideIds.size} из {verifyFailedItems.length}</span>
                <button
                  type="button"
                  onClick={overrideAll}
                  className="ml-auto px-2 py-1 rounded-md bg-slate-800 border border-slate-700 text-slate-300 hover:text-white hover:border-slate-600"
                >
                  Все
                </button>
                <button
                  type="button"
                  onClick={overrideNone}
                  className="px-2 py-1 rounded-md bg-slate-800 border border-slate-700 text-slate-300 hover:text-white hover:border-slate-600"
                >
                  Сбросить
                </button>
              </div>

              <div className="space-y-2 max-h-64 overflow-y-auto">
                {verifyFailedItems.map(item => {
                  const checked = overrideIds.has(item.item_id)
                  const priorityClass = PRIORITY_STYLES[item.priority ?? 'important'] ?? PRIORITY_STYLES.important
                  return (
                    <label
                      key={item.item_id}
                      className={clsx(
                        'flex items-start gap-3 px-3 py-2 rounded-lg border cursor-pointer transition-colors',
                        checked
                          ? 'bg-emerald-950/30 border-emerald-800/60'
                          : 'bg-slate-900/40 border-slate-800 hover:border-slate-700'
                      )}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleOverride(item.item_id)}
                        className="mt-1 w-4 h-4 rounded border-slate-600 bg-slate-900 text-emerald-500 focus:ring-emerald-500 focus:ring-offset-0"
                      />
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-mono text-xs text-slate-500">{item.item_id}</span>
                          {item.priority && (
                            <span className={clsx('text-[10px] px-1.5 py-0.5 rounded border uppercase tracking-wide', priorityClass)}>
                              {item.priority}
                            </span>
                          )}
                        </div>
                        {item.text && (
                          <p className="text-sm text-slate-200">{item.text}</p>
                        )}
                        {item.evidence && (
                          <p className="text-xs text-slate-500 mt-1 italic">Evidence: {item.evidence}</p>
                        )}
                      </div>
                    </label>
                  )
                })}
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-500 uppercase tracking-wide mb-1">
                  Причина (опционально)
                </label>
                <input
                  type="text"
                  value={overrideReason}
                  onChange={e => setOverrideReason(e.target.value)}
                  placeholder="Например: verify не понял что fixture лежит в другой папке"
                  className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-200 focus:outline-none focus:ring-2 focus:ring-amber-500"
                />
              </div>

              {!allOverridesCovered && overrideIds.size > 0 && (
                <p className="text-xs text-amber-300 flex items-center gap-1.5">
                  <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
                  Оставшиеся {verifyFailedItems.length - overrideIds.size} пункта останутся FAIL — run упадёт.
                  Если хотите override всё, нажмите «Все».
                </p>
              )}
            </div>
          )}

          {/* Jump panel */}
          {jumpMode && (
            <div className="bg-slate-800/50 border border-slate-700 rounded-xl p-3">
              <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">Перейти к блоку</label>
              {remainingBlocks.length === 0 ? (
                <p className="text-sm text-slate-500">Нет доступных блоков.</p>
              ) : (
                <div className="flex gap-2">
                  <select
                    value={jumpTarget}
                    onChange={e => setJumpTarget(e.target.value)}
                    className="flex-1 bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  >
                    {remainingBlocks.map(b => <option key={b} value={b}>{blockIdLabel(b)}</option>)}
                  </select>
                  <button
                    type="button"
                    onClick={() => handleDecision('JUMP')}
                    disabled={!jumpTarget}
                    className="flex items-center gap-1.5 bg-purple-700 hover:bg-purple-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-3 py-2 rounded-lg transition-colors"
                  >
                    <ArrowRight className="w-4 h-4" />
                    Перейти
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-slate-800 space-y-3">
          {/* Primary actions */}
          <div className="flex items-center gap-2 flex-wrap">
            <button
              type="button"
              onClick={() => handleDecision('APPROVE')}
              disabled={verifyFailedItems !== null && overrideIds.size === 0}
              className="flex items-center gap-2 bg-green-700 hover:bg-green-600 disabled:bg-slate-700 disabled:text-slate-500 disabled:cursor-not-allowed text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
              title={verifyFailedItems !== null && overrideIds.size === 0
                ? 'Отметьте хотя бы один пункт для override (или нажмите «Все»), либо отклоните'
                : undefined}
            >
              <CheckCircle className="w-4 h-4" />
              {verifyFailedItems
                ? (allOverridesCovered ? 'Применить override и продолжить' : `Override (${overrideIds.size}) и продолжить`)
                : 'Одобрить'}
            </button>

            {editMode && (
              <button
                type="button"
                onClick={() => handleDecision('EDIT')}
                disabled={!!jsonError}
                className="flex items-center gap-2 bg-blue-700 hover:bg-blue-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
              >
                <Edit3 className="w-4 h-4" />
                Сохранить
              </button>
            )}

            <button
              type="button"
              onClick={() => handleDecision('SKIP')}
              className="flex items-center gap-2 bg-slate-700 hover:bg-slate-600 text-slate-200 text-sm font-medium px-4 py-2 rounded-lg transition-colors"
              title="Пропустить блок, пайплайн продолжит без его выхода"
            >
              <SkipForward className="w-4 h-4" />
              Пропустить
            </button>

            {confirmReject ? (
              <div className="ml-auto flex items-center gap-2">
                <span className="text-xs text-red-400">Остановит запуск. Точно?</span>
                <button
                  type="button"
                  onClick={() => handleDecision('REJECT')}
                  className="flex items-center gap-1.5 bg-red-700 hover:bg-red-600 text-white text-sm font-medium px-3 py-2 rounded-lg transition-colors"
                >
                  <XCircle className="w-4 h-4" />
                  Да, отклонить
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmReject(false)}
                  className="text-sm text-slate-400 hover:text-slate-200 px-2 py-2 rounded-lg transition-colors"
                >
                  Нет
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmReject(true)}
                className="flex items-center gap-1.5 text-red-400 hover:text-red-300 text-sm font-medium px-3 py-2 rounded-lg border border-transparent hover:border-red-800/60 transition-colors ml-auto"
              >
                <XCircle className="w-4 h-4" />
                Отклонить
              </button>
            )}
          </div>

          {/* Advanced */}
          <div className="border-t border-slate-800/60 pt-2 flex items-center gap-3 flex-wrap">
            {/* Skip future toggle */}
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <button
                type="button"
                role="switch"
                aria-checked={skipFuture}
                onClick={() => setSkipFuture(v => !v)}
                className={clsx(
                  'relative w-8 h-4 rounded-full border transition-colors focus:outline-none flex-shrink-0',
                  skipFuture ? 'bg-blue-600 border-blue-500' : 'bg-slate-700 border-slate-600'
                )}
              >
                <span className={clsx('absolute top-0.5 w-3 h-3 bg-white rounded-full shadow transition-transform', skipFuture ? 'translate-x-4' : 'translate-x-0.5')} />
              </button>
              <span className="text-xs text-slate-500">Авто-одобрение в будущих запусках</span>
            </label>

            <button
              type="button"
              onClick={toggleJump}
              className={clsx(
                'flex items-center gap-1.5 text-xs font-medium px-3 py-1.5 rounded-lg border transition-colors',
                jumpMode
                  ? 'bg-purple-900/50 border-purple-700 text-purple-300'
                  : 'bg-slate-800 border-slate-700 text-slate-500 hover:text-white hover:border-slate-600'
              )}
            >
              <ArrowRight className="w-3.5 h-3.5" />
              Перейти к блоку
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
