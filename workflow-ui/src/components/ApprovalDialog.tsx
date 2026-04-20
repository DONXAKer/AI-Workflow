import { useState, useCallback } from 'react'
import { CheckCircle, Edit3, XCircle, SkipForward, ArrowRight, AlertCircle, X } from 'lucide-react'
import { WsMessage, ApprovalDecision, ApprovalDecisionType } from '../types'
import clsx from 'clsx'

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

export default function ApprovalDialog({ approval, remainingBlocks = [], onDecision, onDismiss }: Props) {
  const blockId = approval.blockId ?? 'unknown'
  const initialJson = formatJson(approval.output ?? {})

  const [editMode, setEditMode] = useState(false)
  const [editedOutput, setEditedOutput] = useState(initialJson)
  const [jsonError, setJsonError] = useState<string | null>(null)
  const [jumpMode, setJumpMode] = useState(false)
  const [jumpTarget, setJumpTarget] = useState(remainingBlocks[0] ?? '')
  const [skipFuture, setSkipFuture] = useState(false)
  // Require a second click to confirm Reject (irreversible — stops the run)
  const [confirmReject, setConfirmReject] = useState(false)

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
            <p className="text-sm text-slate-400 font-mono">Блок: <span className="text-amber-300">{blockId}</span></p>
            {approval.description ? (
              <p className="text-sm text-slate-400 mt-1">{approval.description}</p>
            ) : (
              <p className="text-sm text-slate-500 mt-1">Проверьте выход блока и примите решение о продолжении пайплайна.</p>
            )}
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
        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-4">
          {/* Output display / edit */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs font-medium text-slate-400 uppercase tracking-wide">
                Выход блока
              </label>
              <button
                type="button"
                onClick={toggleEdit}
                className={clsx(
                  'flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-md border transition-colors',
                  editMode
                    ? 'bg-blue-900/50 border-blue-700 text-blue-300'
                    : 'bg-slate-800 border-slate-700 text-slate-400 hover:text-white hover:border-slate-600'
                )}
              >
                <Edit3 className="w-3.5 h-3.5" />
                {editMode ? 'Отменить' : 'Редактировать'}
              </button>
            </div>

            {editMode ? (
              <div>
                <textarea
                  value={editedOutput}
                  onChange={e => handleJsonChange(e.target.value)}
                  rows={12}
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
              <pre className="bg-slate-950 border border-slate-700/60 rounded-lg px-4 py-3 text-sm font-mono text-slate-300 overflow-auto max-h-64 whitespace-pre-wrap">
                {initialJson}
              </pre>
            )}
          </div>

          {/* Skip future toggle — uses button role="switch" for keyboard accessibility */}
          <div className="flex items-center gap-3">
            <button
              type="button"
              role="switch"
              aria-checked={skipFuture}
              onClick={() => setSkipFuture(v => !v)}
              className={clsx(
                'relative w-9 h-5 rounded-full border transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:ring-offset-slate-900 flex-shrink-0',
                skipFuture ? 'bg-blue-600 border-blue-500' : 'bg-slate-700 border-slate-600'
              )}
            >
              <span
                className={clsx(
                  'absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform',
                  skipFuture ? 'translate-x-4' : 'translate-x-0.5'
                )}
              />
            </button>
            {/* Clicking the label text also toggles the switch */}
            <span
              className="text-sm text-slate-300 cursor-pointer select-none"
              onClick={() => setSkipFuture(v => !v)}
              title="When enabled, this block will be automatically approved in all future runs without prompting"
            >
              Авто-одобрение этого блока в будущих запусках
            </span>
          </div>

          {/* Jump mode */}
          {jumpMode && (
            <div className="bg-slate-800/50 border border-slate-700 rounded-xl p-4">
              <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
                Перейти к блоку
              </label>
              {remainingBlocks.length === 0 ? (
                <p className="text-sm text-slate-500">Нет доступных блоков.</p>
              ) : (
                <div className="flex gap-3">
                  <select
                    value={jumpTarget}
                    onChange={e => setJumpTarget(e.target.value)}
                    className="flex-1 bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  >
                    {remainingBlocks.map(b => (
                      <option key={b} value={b}>{b}</option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={() => handleDecision('JUMP')}
                    disabled={!jumpTarget}
                    className="flex items-center gap-2 bg-purple-700 hover:bg-purple-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
                  >
                    <ArrowRight className="w-4 h-4" />
                    Перейти
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer actions */}
        <div className="px-6 py-4 border-t border-slate-800 space-y-3">
          {/* Primary actions row */}
          <div className="flex flex-wrap items-start gap-2">
            {/* Approve */}
            <div className="flex flex-col items-start">
              <button
                type="button"
                onClick={() => handleDecision('APPROVE')}
                className="flex items-center gap-2 bg-green-700 hover:bg-green-600 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
              >
                <CheckCircle className="w-4 h-4" />
                Одобрить
              </button>
              <span className="text-xs text-green-600/70 block mt-0.5 pl-1">Продолжить с этим выходом</span>
            </div>

            {/* Edit & submit */}
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

            {/* Skip */}
            <div className="flex flex-col items-start">
              <button
                type="button"
                onClick={() => handleDecision('SKIP')}
                className="flex items-center gap-2 bg-slate-700 hover:bg-slate-600 text-slate-200 text-sm font-medium px-4 py-2 rounded-lg transition-colors"
              >
                <SkipForward className="w-4 h-4" />
                Пропустить
              </button>
              <span className="text-xs text-slate-500 block mt-0.5 pl-1">Пропустить блок, пайплайн продолжит без его выхода</span>
            </div>

            {/* Reject — two-step to prevent accidental run termination */}
            {confirmReject ? (
              <div className="ml-auto flex items-center gap-2">
                <span className="text-xs text-red-400">Это остановит запуск. Подтвердить?</span>
                <button
                  type="button"
                  onClick={() => handleDecision('REJECT')}
                  className="flex items-center gap-2 bg-red-700 hover:bg-red-600 text-white text-sm font-medium px-3 py-2 rounded-lg transition-colors"
                >
                  <XCircle className="w-4 h-4" />
                  Подтвердить отказ
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmReject(false)}
                  className="text-sm text-slate-400 hover:text-slate-200 px-3 py-2 rounded-lg transition-colors"
                >
                  Отмена
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmReject(true)}
                className="flex items-center gap-2 bg-red-900/50 hover:bg-red-800/60 border border-red-800 text-red-300 text-sm font-medium px-4 py-2 rounded-lg transition-colors ml-auto"
              >
                <XCircle className="w-4 h-4" />
                Отклонить
              </button>
            )}
          </div>

          {/* Advanced section separator */}
          <div className="border-t border-slate-800 pt-2">
            <p className="text-xs text-slate-600 uppercase tracking-wide font-medium mb-2">Дополнительно</p>
            {/* Jump toggle */}
            <button
              type="button"
              onClick={toggleJump}
              title="Skip ahead to a specific block in the pipeline, bypassing all intermediate blocks"
              className={clsx(
                'flex items-center gap-2 text-sm font-medium px-4 py-2 rounded-lg border transition-colors',
                jumpMode
                  ? 'bg-purple-900/50 border-purple-700 text-purple-300'
                  : 'bg-slate-800 border-slate-700 text-slate-300 hover:text-white hover:border-slate-600'
              )}
            >
              <ArrowRight className="w-4 h-4" />
              Перейти к блоку
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
