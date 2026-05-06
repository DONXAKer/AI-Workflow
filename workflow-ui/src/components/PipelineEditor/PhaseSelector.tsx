import { RotateCcw } from 'lucide-react'
import clsx from 'clsx'
import { Phase } from '../../types'
import { CONCRETE_PHASES, PHASE_LABEL, phaseStripeClass } from '../../utils/phaseColors'

interface Props {
  /** Current YAML override on the instance, or null/undefined if inheriting. */
  value: string | null | undefined
  /** Phase declared by the registry for this block type — used for "default" hint. */
  defaultPhase: Phase | undefined
  onChange: (next: string | null) => void
}

const OPTIONS: Array<{ value: string; phase: Phase }> = [
  ...CONCRETE_PHASES.map(p => ({ value: p.toLowerCase(), phase: p })),
  { value: 'any', phase: 'ANY' },
]

/**
 * Top-level "Phase" field shown in SidePanel CommonFields. Maps to the YAML
 * `phase:` override on a block instance. Selecting "По умолчанию" clears the
 * override (sets phase to null), letting the registry default take over.
 */
export function PhaseSelector({ value, defaultPhase, onChange }: Props) {
  const override = value?.trim() || null
  const isOverridden = override !== null
  const display: Phase = (override?.toUpperCase() as Phase) ?? defaultPhase ?? 'ANY'

  return (
    <div data-testid="phase-selector" className="space-y-1">
      <label className="block text-xs font-medium text-slate-300">
        Фаза
      </label>
      <div className="flex items-center gap-2">
        <span
          aria-hidden
          className={clsx('w-2.5 h-2.5 rounded-sm flex-shrink-0', phaseStripeClass(display))}
          title={PHASE_LABEL[display] ?? display}
        />
        <select
          data-testid="phase-selector-input"
          value={override ?? ''}
          onChange={e => onChange(e.target.value || null)}
          className={clsx(
            'flex-1 bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5 text-xs focus:outline-none focus:ring-1 focus:ring-blue-500',
            isOverridden ? 'text-slate-100' : 'text-slate-400 italic',
          )}
        >
          <option value="">
            {defaultPhase ? `По умолчанию (${defaultPhase.toLowerCase()})` : 'По умолчанию'}
          </option>
          {OPTIONS.map(o => (
            <option key={o.value} value={o.value} className="not-italic text-slate-100">
              {o.phase.toLowerCase()} — {PHASE_LABEL[o.phase].split(' — ')[1] ?? o.phase}
            </option>
          ))}
        </select>
        {isOverridden && (
          <button
            type="button"
            data-testid="phase-selector-reset"
            onClick={() => onChange(null)}
            className="p-1 rounded hover:bg-slate-800 text-slate-500 hover:text-slate-100"
            title="Вернуть к default"
          >
            <RotateCcw className="w-3 h-3" />
          </button>
        )}
      </div>
      {!isOverridden && defaultPhase === 'ANY' && (
        <p className="text-[10px] text-amber-400">
          Полиморфный блок без override — валидатор выдаст WARN. Укажи фазу явно.
        </p>
      )}
    </div>
  )
}

export default PhaseSelector
