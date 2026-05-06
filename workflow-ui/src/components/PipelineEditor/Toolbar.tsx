import { ArrowLeft, Save, ShieldCheck, Loader2, Settings, AlertCircle, Undo2, AlertTriangle } from 'lucide-react'
import { Link } from 'react-router-dom'
import clsx from 'clsx'
import { Phase } from '../../types'
import { CONCRETE_PHASES, PHASE_LABEL, phaseStripeClass } from '../../utils/phaseColors'

interface Props {
  projectSlug: string
  pipelineName: string
  onPipelineName: (v: string) => void
  description: string
  onDescription: (v: string) => void
  dirty: boolean
  saving: boolean
  validating: boolean
  validatedClean: boolean
  errorCount: number
  canUndo: boolean
  /** Concrete phases (no ANY) currently present in the pipeline. Empty array hides the indicator. */
  presentPhases?: Set<Phase>
  /** Pipeline-level phase_check flag — hides indicator when off. */
  phaseCheckEnabled?: boolean
  onUndo: () => void
  onValidate: () => void
  onSave: () => void
  onOpenSettings: () => void
}

export function Toolbar(props: Props) {
  return (
    <div className="bg-slate-900 border-b border-slate-800 px-4 py-2.5 flex items-center gap-3">
      <Link
        to={`/projects/${props.projectSlug}/settings`}
        className="text-slate-500 hover:text-slate-200"
        title="Назад в настройки проекта"
      >
        <ArrowLeft className="w-4 h-4" />
      </Link>

      <div className="flex-1 flex items-center gap-3 min-w-0">
        <input
          data-testid="pipeline-name"
          type="text"
          value={props.pipelineName}
          onChange={e => props.onPipelineName(e.target.value)}
          placeholder="Pipeline name"
          className="bg-transparent text-sm font-semibold text-slate-100 focus:outline-none focus:bg-slate-950 px-2 py-1 rounded border border-transparent focus:border-slate-700 min-w-[160px] max-w-[320px]"
        />
        <input
          data-testid="pipeline-description"
          type="text"
          value={props.description}
          onChange={e => props.onDescription(e.target.value)}
          placeholder="Описание (необязательно)"
          className="flex-1 min-w-0 bg-transparent text-xs text-slate-400 focus:outline-none focus:bg-slate-950 px-2 py-1 rounded border border-transparent focus:border-slate-700"
        />
      </div>

      {props.errorCount > 0 && (
        <span className="text-xs text-red-300 bg-red-950/40 border border-red-800 rounded px-2 py-1 flex items-center gap-1">
          <AlertCircle className="w-3.5 h-3.5" />
          {props.errorCount} {props.errorCount === 1 ? 'ошибка' : 'ошибок'}
        </span>
      )}
      {props.validatedClean && props.errorCount === 0 && (
        <span data-testid="validated-clean" className="text-xs text-emerald-300 bg-emerald-950/40 border border-emerald-800 rounded px-2 py-1">
          Валидно
        </span>
      )}
      <MissingPhasesIndicator
        presentPhases={props.presentPhases}
        enabled={props.phaseCheckEnabled !== false}
      />

      <button
        type="button"
        onClick={props.onUndo}
        data-testid="toolbar-undo"
        disabled={!props.canUndo}
        title="Отменить последнюю правку (Ctrl+Z)"
        className="flex items-center gap-1.5 bg-slate-800 hover:bg-slate-700 disabled:bg-slate-900 disabled:text-slate-600 text-slate-200 text-xs font-medium px-3 py-1.5 rounded-lg border border-slate-700 transition-colors"
      >
        <Undo2 className="w-3.5 h-3.5" />
        Отменить
      </button>

      <button
        type="button"
        onClick={props.onValidate}
        data-testid="toolbar-validate"
        disabled={props.validating}
        className="flex items-center gap-1.5 bg-slate-800 hover:bg-slate-700 disabled:bg-slate-900 disabled:text-slate-600 text-slate-200 text-xs font-medium px-3 py-1.5 rounded-lg border border-slate-700 transition-colors"
      >
        {props.validating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
        Проверить
      </button>

      <button
        type="button"
        onClick={props.onSave}
        data-testid="toolbar-save"
        disabled={props.saving || !props.dirty}
        className={clsx(
          'relative flex items-center gap-1.5 text-white text-xs font-medium px-3 py-1.5 rounded-lg transition-colors',
          props.saving || !props.dirty
            ? 'bg-slate-700 text-slate-500'
            : 'bg-blue-600 hover:bg-blue-500'
        )}
      >
        {props.saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
        Сохранить
        {props.dirty && (
          <span data-testid="dirty-indicator" className="absolute -top-1 -right-1 w-2.5 h-2.5 bg-red-500 rounded-full ring-2 ring-slate-900" />
        )}
      </button>

      <button
        type="button"
        onClick={props.onOpenSettings}
        data-testid="toolbar-settings"
        className="p-1.5 rounded hover:bg-slate-800 text-slate-400 hover:text-slate-100"
        title="Настройки пайплайна"
      >
        <Settings className="w-4 h-4" />
      </button>
    </div>
  )
}

function MissingPhasesIndicator({ presentPhases, enabled }: {
  presentPhases?: Set<Phase>
  enabled: boolean
}) {
  if (!enabled) return null
  if (!presentPhases || presentPhases.size === 0) return null
  const missing = CONCRETE_PHASES.filter(p => !presentPhases.has(p))
  if (missing.length === 0) return null
  const tooltip = missing.map(p => PHASE_LABEL[p]).join(', ')
  return (
    <span
      data-testid="missing-phases-indicator"
      title={`Не представлены фазы: ${tooltip}`}
      className="text-xs text-amber-200 bg-amber-950/40 border border-amber-900/60 rounded px-2 py-1 flex items-center gap-1.5"
    >
      <AlertTriangle className="w-3.5 h-3.5" />
      <span className="hidden md:inline">Нет фаз:</span>
      <span className="flex items-center gap-0.5">
        {missing.map(p => (
          <span
            key={p}
            aria-label={PHASE_LABEL[p]}
            className={clsx('w-1.5 h-3 rounded-sm', phaseStripeClass(p))}
            title={PHASE_LABEL[p]}
          />
        ))}
      </span>
    </span>
  )
}

export default Toolbar
