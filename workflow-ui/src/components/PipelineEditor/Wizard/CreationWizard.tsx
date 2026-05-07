import { useEffect, useState } from 'react'
import { AlertCircle, X } from 'lucide-react'
import { BlockRegistryEntry } from '../../../types'
import { api } from '../../../services/api'
import {
  PHASE_STEPS, useCreationWizard, WizardPhase, WizardStep,
} from '../../../hooks/useCreationWizard'
import WizardSideRail from './WizardSideRail'
import WizardPhaseStep from './WizardPhaseStep'
import WizardRetryStep from './WizardRetryStep'
import WizardPreviewStep from './WizardPreviewStep'

interface Props {
  onCancel: () => void
  onCreated: (info: { path: string; name: string; pipelineName: string }) => void
  registry: BlockRegistryEntry[]
  byType: Record<string, BlockRegistryEntry>
}

/**
 * Root component of the Creation Wizard. Owns:
 *  - The wizard hook (state + reducer + computed previewConfig).
 *  - The pre-step name fields (slug, displayName, description) — rendered as a
 *    sticky banner above the step content.
 *  - Auto-validation effect: every time state.dirty changes (or step ≥ IMPLEMENT),
 *    we trigger {@link UseCreationWizard.runValidation} which is itself debounced.
 *  - Create handler: posts the assembled PipelineConfig via {@link api.createPipeline}.
 *  - {@code beforeunload} guard while there are unsaved changes.
 */
export function CreationWizard({ onCancel, onCreated, registry, byType }: Props) {
  const wizard = useCreationWizard(registry)
  const { state } = wizard
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  // Beforeunload guard while dirty.
  useEffect(() => {
    if (!state.dirty) return
    const handler = (e: BeforeUnloadEvent) => { e.preventDefault(); return '' }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [state.dirty])

  // Live validation: trigger a debounced re-validate on every state change once
  // the user has reached IMPLEMENT (no point validating an empty pipeline).
  const stepIndex = wizard.stepOrder.indexOf(state.currentStep)
  const implementIndex = wizard.stepOrder.indexOf('IMPLEMENT')
  const reachedImpl = stepIndex >= implementIndex
  useEffect(() => {
    if (!reachedImpl) return
    wizard.runValidation()
    // We deliberately depend on previewConfig, not state — buildPreviewConfig
    // is memoised and a stable reference if nothing relevant changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wizard.previewConfig, reachedImpl])

  const handleCreate = async () => {
    setCreateError(null)
    setCreating(true)
    try {
      const result = await api.createPipeline({
        slug: state.slug,
        displayName: state.displayName,
        description: state.description || undefined,
        pipeline: wizard.previewConfig.pipeline,
        entry_points: wizard.previewConfig.entry_points,
      })
      onCreated(result)
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : 'Не удалось создать пайплайн')
    } finally {
      setCreating(false)
    }
  }

  const renderStep = () => {
    if (state.currentStep === 'PREVIEW') {
      return (
        <WizardPreviewStep
          wizard={wizard}
          byType={byType}
          onCreate={handleCreate}
          creating={creating}
          createError={createError}
        />
      )
    }
    if (state.currentStep === 'RETRY') {
      return <WizardRetryStep wizard={wizard} />
    }
    // Phase step
    return (
      <WizardPhaseStep
        phase={state.currentStep as WizardPhase}
        wizard={wizard}
        registry={registry}
        byType={byType}
      />
    )
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-700 rounded-xl w-[1100px] max-w-full h-[80vh] flex flex-col overflow-hidden">
        {/* Header */}
        <header className="px-5 py-3 border-b border-slate-800 flex items-center gap-3">
          <h1 className="text-sm font-semibold text-slate-100 flex-1">Новый пайплайн (мастер)</h1>
          <button
            type="button"
            onClick={onCancel}
            className="p-1.5 rounded hover:bg-slate-800 text-slate-400 hover:text-slate-100"
          >
            <X className="w-4 h-4" />
          </button>
        </header>

        {/* Pre-step name fields */}
        <NameFields
          slug={state.slug}
          displayName={state.displayName}
          description={state.description}
          onSlug={wizard.setSlug}
          onDisplayName={wizard.setDisplayName}
          onDescription={wizard.setDescription}
        />

        {/* Body: side-rail + step */}
        <div className="flex-1 min-h-0 flex">
          <WizardSideRail
            state={state}
            showRetryStep={wizard.showRetryStep}
            onGoto={(step: WizardStep) => wizard.goto(step)}
          />
          <div className="flex-1 min-w-0 flex flex-col">
            {renderStep()}
          </div>
        </div>
      </div>
    </div>
  )
}

function NameFields({
  slug, displayName, description, onSlug, onDisplayName, onDescription,
}: {
  slug: string
  displayName: string
  description: string
  onSlug: (v: string) => void
  onDisplayName: (v: string) => void
  onDescription: (v: string) => void
}) {
  const slugValid = !slug || /^[a-z0-9][a-z0-9-]*$/.test(slug)

  return (
    <div className="px-5 py-3 border-b border-slate-800 bg-slate-900/40 grid grid-cols-3 gap-3">
      <div>
        <label className="block text-[10px] uppercase tracking-wide text-slate-400 mb-1">Slug *</label>
        <input
          type="text"
          data-testid="wizard-slug"
          value={slug}
          onChange={e => onSlug(e.target.value.toLowerCase())}
          placeholder="my-pipeline"
          className={
            'w-full bg-slate-950 border rounded px-2 py-1.5 text-xs text-slate-100 font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 '
            + (slugValid ? 'border-slate-700' : 'border-red-700')
          }
        />
        {!slugValid && (
          <p className="text-[10px] text-red-400 mt-0.5 flex items-center gap-1">
            <AlertCircle className="w-3 h-3" /> kebab-case: a-z, 0-9, -
          </p>
        )}
      </div>
      <div>
        <label className="block text-[10px] uppercase tracking-wide text-slate-400 mb-1">Название *</label>
        <input
          type="text"
          data-testid="wizard-display-name"
          value={displayName}
          onChange={e => onDisplayName(e.target.value)}
          placeholder="My Pipeline"
          className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      </div>
      <div>
        <label className="block text-[10px] uppercase tracking-wide text-slate-400 mb-1">Описание</label>
        <input
          type="text"
          data-testid="wizard-description"
          value={description}
          onChange={e => onDescription(e.target.value)}
          placeholder="Краткое описание"
          className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      </div>
    </div>
  )
}

// Re-export PHASE_STEPS so any external consumer needing the list (e.g. tests)
// can import from one place.
export { PHASE_STEPS }
export default CreationWizard
