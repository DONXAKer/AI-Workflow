import { useState } from 'react'
import { AlertCircle, CheckCircle2, ChevronDown, ChevronRight, Loader2 } from 'lucide-react'
import { BlockRegistryEntry } from '../../../types'
import Canvas from '../Canvas'
import { UseCreationWizard } from '../../../hooks/useCreationWizard'

interface Props {
  wizard: UseCreationWizard
  byType: Record<string, BlockRegistryEntry>
  onCreate: () => void | Promise<void>
  creating: boolean
  createError: string | null
}

/**
 * Final wizard step. Shows a read-only preview of the assembled pipeline as a
 * react-flow Canvas + a validation banner. The Create button is gated on:
 *  - at least one IMPLEMENT block;
 *  - no ERROR-severity validation errors;
 *  - slug + displayName valid (handled by parent's {@link UseCreationWizard.canCreate}).
 */
export function WizardPreviewStep({ wizard, byType, onCreate, creating, createError }: Props) {
  const { previewConfig, state } = wizard
  const errors = state.validationErrors
  const errorCount = errors.filter(e => (e.severity ?? 'ERROR') === 'ERROR').length
  const warnCount = errors.filter(e => e.severity === 'WARN').length

  const hasImplBlock = state.phases.IMPLEMENT.blocks.length > 0
  const [errorsOpen, setErrorsOpen] = useState(true)

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <header className="px-6 py-3 border-b border-slate-800">
        <h2 className="text-base font-semibold text-slate-100">Превью</h2>
        <p className="text-xs text-slate-400 mt-1">
          Граф пайплайна, как он будет сохранён.
        </p>
      </header>

      {/* Canvas */}
      <div data-testid="wizard-preview-canvas" className="flex-1 min-h-0 relative">
        <Canvas
          config={previewConfig}
          selectedBlockId={null}
          errors={errors}
          byType={byType}
          onSelectBlock={() => {}}
          onConnectDependsOn={() => {}}
          onDeleteEdge={() => {}}
        />
      </div>

      {/* Banners + actions */}
      <div className="border-t border-slate-800 px-6 py-3 space-y-3 max-h-[40%] overflow-y-auto">
        {/* IMPLEMENT-block sanity check */}
        {!hasImplBlock && (
          <div className="bg-red-950/40 border border-red-800 rounded-lg p-3 flex items-start gap-2">
            <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
            <div>
              <div className="text-xs font-medium text-red-200">Нужен хотя бы один Implement-блок</div>
              <p className="text-[11px] text-red-300/80 mt-0.5">
                Вернитесь к шагу Implement и выберите блок.
              </p>
            </div>
          </div>
        )}

        {/* Validation banner */}
        <div data-testid="wizard-validation-banner">
          {state.validating ? (
            <div className="flex items-center gap-2 text-xs text-slate-400">
              <Loader2 className="w-3.5 h-3.5 animate-spin" /> Проверяем конфиг…
            </div>
          ) : errorCount === 0 ? (
            <div className="flex items-center gap-2 text-xs text-emerald-300">
              <CheckCircle2 className="w-4 h-4" /> Готово — конфиг валиден.
              {warnCount > 0 && (
                <span className="text-amber-300">({warnCount} предупреждений)</span>
              )}
            </div>
          ) : (
            <div className="bg-red-950/40 border border-red-800 rounded-lg p-3">
              <button
                type="button"
                onClick={() => setErrorsOpen(o => !o)}
                className="w-full flex items-center gap-2 text-xs font-medium text-red-200"
              >
                {errorsOpen ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                <AlertCircle className="w-3.5 h-3.5" />
                Найдено {errorCount} {errorCount === 1 ? 'ошибка' : 'ошибок'} валидации
                {warnCount > 0 && <span className="text-amber-300">+ {warnCount} предупреждений</span>}
              </button>
              {errorsOpen && (
                <ul className="mt-2 text-[11px] text-red-200 space-y-0.5 pl-5 list-disc">
                  {errors.map((e, i) => (
                    <li key={i}>
                      <span className="font-mono">{e.code}</span>: {e.message}
                      {e.blockId && <span className="text-red-300/70"> · {e.blockId}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>

        {createError && (
          <div className="bg-red-950/40 border border-red-800 rounded-lg p-3 flex items-start gap-2">
            <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
            <div className="text-xs text-red-200">{createError}</div>
          </div>
        )}

        {/* Buttons */}
        <div className="flex items-center gap-2 pt-2">
          <button
            type="button"
            data-testid="wizard-prev"
            onClick={wizard.prev}
            className="text-xs px-3 py-1.5 rounded border border-slate-700 text-slate-300 hover:text-slate-100 hover:border-slate-600"
          >
            ← Назад
          </button>
          <div className="flex-1" />
          <button
            type="button"
            data-testid="wizard-create-button"
            onClick={onCreate}
            disabled={!wizard.canCreate || creating}
            className="text-xs px-4 py-1.5 rounded bg-emerald-600 hover:bg-emerald-500 disabled:bg-slate-700 disabled:text-slate-400 text-white flex items-center gap-1.5"
          >
            {creating && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            Создать пайплайн
          </button>
        </div>
      </div>
    </div>
  )
}

export default WizardPreviewStep
