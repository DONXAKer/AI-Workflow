import { RotateCcw } from 'lucide-react'
import { UseCreationWizard } from '../../../hooks/useCreationWizard'

interface Props {
  wizard: UseCreationWizard
}

/**
 * Single-checkbox step that toggles retry-on-failure across the pipeline.
 * When enabled (default), {@link buildPreviewConfig} wires:
 *  - {@code verify.on_fail = loopback → last IMPLEMENT block} on every verify-type block;
 *  - {@code on_failure = loopback → first IMPLEMENT block} on every CI block.
 */
export function WizardRetryStep({ wizard }: Props) {
  return (
    <div className="flex-1 overflow-y-auto px-6 py-6 max-w-2xl">
      <header className="mb-4 flex items-center gap-2">
        <RotateCcw className="w-5 h-5 text-amber-400" />
        <h2 className="text-base font-semibold text-slate-100">Retry policy</h2>
      </header>

      <label
        className="flex items-start gap-3 bg-slate-900/40 border border-slate-800 rounded-lg p-4 cursor-pointer hover:border-slate-700"
      >
        <input
          type="checkbox"
          data-testid="wizard-retry-checkbox"
          checked={wizard.state.retryEnabled}
          onChange={(e) => wizard.setRetry(e.target.checked)}
          className="mt-0.5 accent-blue-500 w-4 h-4"
        />
        <div className="flex-1">
          <div className="text-sm text-slate-100 font-medium">
            Retry on failure (max 2 attempts)
          </div>
          <p className="text-xs text-slate-400 mt-1">
            При провале верификации или CI пайплайн вернётся в фазу
            «implement» и попытается исправить ошибку до 2 раз.
          </p>
        </div>
      </label>

      <div className="mt-5 text-xs text-slate-500 space-y-1.5">
        <p className="font-medium text-slate-400">Что это включает:</p>
        <ul className="list-disc pl-5 space-y-1">
          <li>
            <code className="font-mono text-slate-300">verify.on_fail</code>: блок верификации не прошёл → loopback на последний implement-блок.
          </li>
          <li>
            <code className="font-mono text-slate-300">on_failure</code>: CI упал
            (<code className="font-mono">failure</code> / <code className="font-mono">failed</code> / <code className="font-mono">timeout</code>) → loopback на первый implement-блок.
          </li>
        </ul>
      </div>

      {/* Action buttons */}
      <div className="mt-6 flex items-center gap-2 pt-4 border-t border-slate-800">
        <div className="flex-1" />
        <button
          type="button"
          data-testid="wizard-prev"
          onClick={wizard.prev}
          className="text-xs px-3 py-1.5 rounded border border-slate-700 text-slate-300 hover:text-slate-100 hover:border-slate-600"
        >
          ← Назад
        </button>
        <button
          type="button"
          data-testid="wizard-next"
          onClick={wizard.next}
          className="text-xs px-3 py-1.5 rounded bg-blue-600 hover:bg-blue-500 text-white"
        >
          Далее →
        </button>
      </div>
    </div>
  )
}

export default WizardRetryStep
