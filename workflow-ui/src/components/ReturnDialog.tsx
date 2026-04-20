import { useState } from 'react'
import { Undo2, AlertCircle, X, Loader2 } from 'lucide-react'
import clsx from 'clsx'

interface Props {
  completedBlocks: string[]
  onSubmit: (targetBlock: string, comment: string) => Promise<void>
  onClose: () => void
}

export default function ReturnDialog({ completedBlocks, onSubmit, onClose }: Props) {
  const [targetBlock, setTargetBlock] = useState(completedBlocks[0] ?? '')
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const canSubmit = targetBlock.length > 0 && comment.trim().length > 0 && !submitting

  const handleSubmit = async () => {
    if (!canSubmit) return
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(targetBlock, comment.trim())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось вернуть задачу')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm">
      <div className="bg-slate-900 border border-slate-700 rounded-2xl shadow-2xl w-full max-w-xl flex flex-col">
        <div className="flex items-start justify-between px-6 py-5 border-b border-slate-800">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <Undo2 className="w-5 h-5 text-amber-400" />
              <h2 className="text-base font-semibold text-white">Вернуть на доработку</h2>
            </div>
            <p className="text-sm text-slate-400">
              Комментарий будет преобразован AI в структурированный фидбэк и передан выбранному блоку.
              Все следующие блоки будут переисполнены.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="p-1.5 rounded-lg text-slate-500 hover:text-white hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="px-6 py-5 space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
              Целевой блок
            </label>
            {completedBlocks.length === 0 ? (
              <p className="text-sm text-slate-500">Нет завершённых блоков для возврата.</p>
            ) : (
              <select
                value={targetBlock}
                onChange={e => setTargetBlock(e.target.value)}
                disabled={submitting}
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-amber-500 disabled:opacity-50"
              >
                {completedBlocks.map(b => (
                  <option key={b} value={b}>{b}</option>
                ))}
              </select>
            )}
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-2">
              Комментарий
            </label>
            <textarea
              value={comment}
              onChange={e => setComment(e.target.value)}
              disabled={submitting}
              rows={6}
              placeholder="Что нужно переделать и почему?"
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-amber-500 disabled:opacity-50 resize-y"
            />
          </div>

          {error && (
            <div className="flex items-start gap-2 text-sm text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              {error}
            </div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-800 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="text-sm text-slate-400 hover:text-slate-200 px-4 py-2 rounded-lg transition-colors disabled:opacity-50"
          >
            Отмена
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className={clsx(
              'flex items-center gap-2 text-sm font-medium px-4 py-2 rounded-lg transition-colors',
              canSubmit
                ? 'bg-amber-700 hover:bg-amber-600 text-white'
                : 'bg-slate-700 text-slate-500 cursor-not-allowed'
            )}
          >
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Undo2 className="w-4 h-4" />}
            Вернуть
          </button>
        </div>
      </div>
    </div>
  )
}
