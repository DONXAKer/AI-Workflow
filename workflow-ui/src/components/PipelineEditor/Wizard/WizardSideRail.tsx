import { Check, Circle, Eye, RotateCcw } from 'lucide-react'
import { phaseStripeClass, PHASE_LABEL } from '../../../utils/phaseColors'
import { WizardState, WizardStep, WizardPhase, PHASE_STEPS } from '../../../hooks/useCreationWizard'

interface Props {
  state: WizardState
  showRetryStep: boolean
  onGoto: (step: WizardStep) => void
}

/**
 * Vertical stepper rail (≈180px) listing wizard steps. Phase steps show their
 * phase color stripe. Status icons:
 *  - completed → check
 *  - current   → filled dot
 *  - upcoming  → empty circle (disabled)
 *  - skipped   → empty circle, italic, slate text
 *
 * Click navigates to the step iff its status is NOT 'upcoming'. Phase steps
 * become navigable after the user has visited them once (status flips to
 * `current → completed/skipped`).
 */
export function WizardSideRail({ state, showRetryStep, onGoto }: Props) {
  const items: { step: WizardStep; label: string }[] = []
  for (const p of PHASE_STEPS) items.push({ step: p, label: PHASE_LABEL[p] })
  if (showRetryStep) items.push({ step: 'RETRY', label: 'Retry policy' })
  items.push({ step: 'PREVIEW', label: 'Preview' })

  return (
    <nav
      data-testid="wizard-side-rail"
      className="w-[180px] flex-shrink-0 border-r border-slate-800 bg-slate-900/60 py-4 px-2 space-y-1 overflow-y-auto"
    >
      {items.map(item => (
        <RailItem
          key={item.step}
          step={item.step}
          label={item.label}
          state={state}
          onGoto={onGoto}
        />
      ))}
    </nav>
  )
}

function RailItem({
  step, label, state, onGoto,
}: {
  step: WizardStep
  label: string
  state: WizardState
  onGoto: (step: WizardStep) => void
}) {
  const isPhase = (PHASE_STEPS as string[]).includes(step)
  const phaseStatus = isPhase ? state.phases[step as WizardPhase].status : null
  const isCurrent = state.currentStep === step

  let status: 'completed' | 'current' | 'upcoming' | 'skipped'
  if (isCurrent) status = 'current'
  else if (isPhase) status = (phaseStatus as 'completed' | 'upcoming' | 'skipped') ?? 'upcoming'
  else if (step === 'PREVIEW' || step === 'RETRY') {
    // Non-phase steps: navigable iff we've passed VERIFY at least once.
    const reached =
      state.phases.VERIFY.status === 'completed'
      || state.phases.VERIFY.status === 'skipped'
      || state.phases.PUBLISH.status !== 'upcoming'
      || state.phases.RELEASE.status !== 'upcoming'
    status = reached ? 'completed' : 'upcoming'
  } else {
    status = 'upcoming'
  }

  const enabled = status !== 'upcoming'
  const stripeClass = isPhase ? phaseStripeClass(step as WizardPhase) : ''

  return (
    <button
      type="button"
      data-testid={`wizard-rail-${step}`}
      data-status={status}
      onClick={() => { if (enabled) onGoto(step) }}
      disabled={!enabled}
      className={
        'w-full flex items-center gap-2 px-2 py-1.5 rounded text-xs text-left transition-colors '
        + (isCurrent
          ? 'bg-blue-950/40 text-blue-100 border border-blue-800'
          : enabled
            ? 'text-slate-200 hover:bg-slate-800 border border-transparent'
            : status === 'skipped'
              ? 'text-slate-500 italic border border-transparent cursor-default'
              : 'text-slate-600 border border-transparent cursor-not-allowed')
      }
    >
      {/* phase color stripe */}
      {isPhase && (
        <span
          aria-hidden
          className={`w-1 h-5 rounded-sm flex-shrink-0 ${stripeClass}`}
        />
      )}
      {!isPhase && step === 'RETRY' && <RotateCcw className="w-3 h-3 flex-shrink-0" />}
      {!isPhase && step === 'PREVIEW' && <Eye className="w-3 h-3 flex-shrink-0" />}

      <span className="flex-1 min-w-0 truncate">{label}</span>

      {/* status icon */}
      {status === 'completed' && (
        <Check className="w-3.5 h-3.5 text-emerald-400 flex-shrink-0" />
      )}
      {status === 'current' && (
        <span className="w-2 h-2 rounded-full bg-blue-400 flex-shrink-0" />
      )}
      {status === 'upcoming' && (
        <Circle className="w-3 h-3 text-slate-600 flex-shrink-0" />
      )}
      {status === 'skipped' && (
        <span className="text-[10px] text-slate-500 flex-shrink-0">skipped</span>
      )}
    </button>
  )
}

export default WizardSideRail
