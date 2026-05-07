import { useEffect, useMemo, useState } from 'react'
import { ChevronDown, Plus, SkipForward, Trash2, Lightbulb } from 'lucide-react'
import {
  BlockConfigDto, BlockRegistryEntry, PipelineConfigDto, VerifyConfigDto,
} from '../../../types'
import { blockTypeLabelWithCode } from '../../../utils/blockLabels'
import { PHASE_LABEL, phaseStripeClass } from '../../../utils/phaseColors'
import { UseCreationWizard, WizardPhase } from '../../../hooks/useCreationWizard'
import BlockForm from '../forms/BlockForm'

interface Props {
  phase: WizardPhase
  wizard: UseCreationWizard
  registry: BlockRegistryEntry[]
  byType: Record<string, BlockRegistryEntry>
}

/**
 * Per-phase wizard step. Renders block slots (each = a row with type label,
 * Replace dropdown, delete button + the block-essentials form). New users
 * land here with the recommended-default block already pre-filled in slot 0.
 */
export function WizardPhaseStep({ phase, wizard, registry, byType }: Props) {
  const phaseState = wizard.state.phases[phase]
  const recommended = wizard.recommendedFor(phase)

  // Phase-eligible block entries, sorted by recommendedRank desc then alpha.
  const phaseEntries = useMemo(() => {
    return registry
      .filter(e => e.metadata.phase === phase)
      .sort((a, b) => {
        const ra = a.metadata.recommendedRank ?? 0
        const rb = b.metadata.recommendedRank ?? 0
        if (ra !== rb) return rb - ra
        return a.type.localeCompare(b.type)
      })
  }, [registry, phase])

  // Auto-seed slot 0 with the recommended block on first visit, IF the user
  // hasn't picked / skipped yet. One-shot per phase mount.
  const [seeded, setSeeded] = useState(false)
  useEffect(() => {
    if (seeded) return
    if (phaseState.blocks.length === 0 && phaseState.status === 'current' && recommended) {
      const seedBlock: BlockConfigDto = {
        id: recommended.type,
        block: recommended.type,
        enabled: true,
        depends_on: [],
        config: {},
      }
      wizard.addBlock(phase, seedBlock)
    }
    setSeeded(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Faux config used by BlockForm (it expects PipelineConfigDto for cross-block
  // refs). Provide the wizard's preview so picker dropdowns see other phase blocks.
  const fauxConfig: PipelineConfigDto = wizard.previewConfig

  const replaceWith = (index: number, entry: BlockRegistryEntry) => {
    const old = phaseState.blocks[index]
    const next: BlockConfigDto = {
      id: entry.type,
      block: entry.type,
      enabled: true,
      depends_on: old?.depends_on ?? [],
      config: {},
    }
    wizard.replaceBlock(phase, index, next)
  }

  return (
    <div data-testid="wizard-phase-step" className="flex-1 overflow-y-auto px-6 py-4">
      <header className="mb-4">
        <div className="flex items-center gap-2">
          <span aria-hidden className={`w-1.5 h-6 rounded-sm ${phaseStripeClass(phase)}`} />
          <h2 className="text-base font-semibold text-slate-100">
            Фаза: {PHASE_LABEL[phase]}
          </h2>
        </div>
        {recommended && (
          <p className="text-xs text-slate-400 mt-1.5 flex items-center gap-1.5">
            <Lightbulb className="w-3.5 h-3.5 text-amber-400" />
            Рекомендуется: <span className="font-mono text-slate-200">{blockTypeLabelWithCode(recommended.type)}</span>
          </p>
        )}
      </header>

      <div className="space-y-4">
        {phaseState.blocks.length === 0 && (
          <div className="bg-slate-900/40 border border-slate-800 rounded-lg p-6 text-center">
            <p className="text-sm text-slate-400">
              {phaseState.status === 'skipped'
                ? 'Фаза пропущена.'
                : 'В этой фазе ещё нет блоков.'}
            </p>
            {phaseEntries.length > 0 && (
              <button
                type="button"
                onClick={() => {
                  const first = phaseEntries[0]
                  wizard.addBlock(phase, {
                    id: first.type, block: first.type, enabled: true, depends_on: [], config: {},
                  })
                }}
                className="mt-3 text-xs text-blue-400 hover:text-blue-300"
              >
                Выбрать блок
              </button>
            )}
          </div>
        )}

        {phaseState.blocks.map((block, i) => (
          <BlockSlotEditor
            key={i}
            index={i}
            block={block}
            registryEntry={byType[block.block]}
            phaseEntries={phaseEntries}
            config={fauxConfig}
            onReplace={(entry) => replaceWith(i, entry)}
            onDelete={() => wizard.removeBlock(phase, i)}
            onConfigChange={(cfg) => wizard.patchBlockConfig(phase, i, cfg)}
            onVerifyChange={(v) => wizard.patchBlock(phase, i, { verify: v })}
          />
        ))}
      </div>

      {/* Action buttons */}
      <div className="mt-6 flex flex-wrap items-center gap-2 pt-4 border-t border-slate-800">
        <AddAnotherButton phaseEntries={phaseEntries} onPick={(entry) => {
          wizard.addBlock(phase, {
            id: entry.type, block: entry.type, enabled: true, depends_on: [], config: {},
          })
        }} />

        <button
          type="button"
          data-testid="wizard-skip-phase"
          onClick={() => wizard.skipPhase(phase)}
          className="text-xs px-3 py-1.5 rounded border border-slate-700 text-slate-300 hover:text-slate-100 hover:border-slate-600 flex items-center gap-1"
        >
          <SkipForward className="w-3.5 h-3.5" /> Пропустить фазу
        </button>

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

function BlockSlotEditor({
  index, block, registryEntry, phaseEntries, config,
  onReplace, onDelete, onConfigChange, onVerifyChange,
}: {
  index: number
  block: BlockConfigDto
  registryEntry: BlockRegistryEntry | undefined
  phaseEntries: BlockRegistryEntry[]
  config: PipelineConfigDto
  onReplace: (entry: BlockRegistryEntry) => void
  onDelete: () => void
  onConfigChange: (cfg: Record<string, unknown>) => void
  onVerifyChange: (v: VerifyConfigDto) => void
}) {
  const [showReplace, setShowReplace] = useState(false)

  return (
    <div
      data-testid={`wizard-block-slot-${index}`}
      className="border border-slate-800 rounded-lg bg-slate-900/40"
    >
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-slate-800">
        <div className="flex-1 min-w-0">
          <div className="text-sm text-slate-100 font-medium truncate">
            {blockTypeLabelWithCode(block.block)}
          </div>
          {registryEntry && (
            <div className="text-[10px] text-slate-500 truncate">
              {registryEntry.metadata.label}
            </div>
          )}
        </div>

        <div className="relative">
          <button
            type="button"
            data-testid={`wizard-replace-${index}`}
            onClick={() => setShowReplace(s => !s)}
            className="text-xs px-2 py-1 rounded border border-slate-700 text-slate-300 hover:text-slate-100 hover:border-slate-600 flex items-center gap-1"
          >
            Заменить <ChevronDown className="w-3 h-3" />
          </button>
          {showReplace && (
            <div className="absolute right-0 top-full mt-1 z-20 bg-slate-900 border border-slate-700 rounded-lg shadow-xl p-1 w-64 max-h-72 overflow-y-auto">
              {phaseEntries.map(entry => (
                <button
                  key={entry.type}
                  type="button"
                  onClick={() => { onReplace(entry); setShowReplace(false) }}
                  className={
                    'w-full text-left px-2 py-1.5 rounded text-xs flex items-center gap-2 '
                    + (entry.type === block.block
                      ? 'bg-blue-950/40 text-blue-100'
                      : 'text-slate-200 hover:bg-slate-800')
                  }
                >
                  <span className="flex-1">
                    <div className="font-medium">{blockTypeLabelWithCode(entry.type)}</div>
                    <div className="text-[10px] text-slate-500 font-mono">{entry.metadata.label}</div>
                  </span>
                  {(entry.metadata.recommendedRank ?? 0) > 0 && (
                    <span className="text-[10px] text-amber-400">★</span>
                  )}
                </button>
              ))}
              {phaseEntries.length === 0 && (
                <div className="text-[11px] text-slate-500 px-2 py-2">
                  Нет блоков в этой фазе.
                </div>
              )}
            </div>
          )}
        </div>

        <button
          type="button"
          onClick={onDelete}
          className="p-1.5 rounded hover:bg-red-900/40 text-slate-500 hover:text-red-300"
          title="Удалить"
        >
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Form (essentials only) */}
      <div className="px-3 py-3">
        <BlockForm
          block={block}
          registryEntry={registryEntry}
          config={config}
          levelFilter="essential"
          onConfigChange={onConfigChange}
          onVerifyChange={onVerifyChange}
        />
        {!registryEntry && (
          <p className="text-[10px] text-slate-500 italic">
            Метаданные блока недоступны.
          </p>
        )}
      </div>
    </div>
  )
}

function AddAnotherButton({ phaseEntries, onPick }: {
  phaseEntries: BlockRegistryEntry[]
  onPick: (entry: BlockRegistryEntry) => void
}) {
  const [open, setOpen] = useState(false)

  return (
    <div className="relative">
      <button
        type="button"
        data-testid="wizard-add-another"
        onClick={() => setOpen(o => !o)}
        className="text-xs px-3 py-1.5 rounded border border-slate-700 text-slate-300 hover:text-slate-100 hover:border-slate-600 flex items-center gap-1"
        disabled={phaseEntries.length === 0}
      >
        <Plus className="w-3.5 h-3.5" /> Ещё блок <ChevronDown className="w-3 h-3" />
      </button>
      {open && (
        <div className="absolute left-0 bottom-full mb-1 z-20 bg-slate-900 border border-slate-700 rounded-lg shadow-xl p-1 w-64 max-h-72 overflow-y-auto">
          {phaseEntries.map(entry => (
            <button
              key={entry.type}
              type="button"
              onClick={() => { onPick(entry); setOpen(false) }}
              className="w-full text-left px-2 py-1.5 rounded text-xs text-slate-200 hover:bg-slate-800 flex items-center gap-2"
            >
              <span className="flex-1">
                <div className="font-medium">{blockTypeLabelWithCode(entry.type)}</div>
                <div className="text-[10px] text-slate-500 font-mono">{entry.metadata.label}</div>
              </span>
              {(entry.metadata.recommendedRank ?? 0) > 0 && (
                <span className="text-[10px] text-amber-400">★</span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export default WizardPhaseStep
