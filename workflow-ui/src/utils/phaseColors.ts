import { BlockConfigDto, BlockRegistryEntry, Phase } from '../types'

/**
 * Russian display label for each phase. Used in side panel dropdown,
 * palette section headers, and missing-phase indicator tooltips.
 */
export const PHASE_LABEL: Record<Phase, string> = {
  INTAKE: 'Intake — входные данные',
  ANALYZE: 'Analyze — анализ',
  IMPLEMENT: 'Implement — имплементация',
  VERIFY: 'Verify — верификация',
  PUBLISH: 'Publish — публикация',
  RELEASE: 'Release — релиз',
  ANY: 'Any — универсальный',
}

/** Linear order in which phases appear in the palette and Toolbar indicator. */
export const PHASE_ORDER: Phase[] = [
  'INTAKE', 'ANALYZE', 'IMPLEMENT', 'VERIFY', 'PUBLISH', 'RELEASE', 'ANY',
]

/** Concrete phases (no ANY) — used by the missing-phase indicator. */
export const CONCRETE_PHASES: Phase[] = [
  'INTAKE', 'ANALYZE', 'IMPLEMENT', 'VERIFY', 'PUBLISH', 'RELEASE',
]

/**
 * Tailwind background-class for the phase top-stripe on a block node.
 * Chosen to be readable on the dark editor background but distinguishable.
 * ANY uses neutral slate so polymorphic blocks don't visually claim a phase.
 */
const STRIPE_CLASS: Record<Phase, string> = {
  INTAKE:    'bg-sky-500',
  ANALYZE:   'bg-violet-500',
  IMPLEMENT: 'bg-emerald-500',
  VERIFY:    'bg-amber-500',
  PUBLISH:   'bg-cyan-500',
  RELEASE:   'bg-rose-500',
  ANY:       'bg-slate-600',
}

export function phaseStripeClass(phase: Phase | undefined): string {
  return STRIPE_CLASS[phase ?? 'ANY']
}

/**
 * Resolve the effective phase of a block instance:
 * 1. block.phase YAML override (case-insensitive), if set
 * 2. else metadata.phase from registry
 * 3. else ANY (registry not loaded yet / unknown type)
 */
export function effectivePhase(
  block: Pick<BlockConfigDto, 'block' | 'phase'>,
  registryEntry: BlockRegistryEntry | undefined,
): Phase {
  const override = block.phase?.trim().toUpperCase()
  if (override) {
    if ((PHASE_ORDER as string[]).includes(override)) return override as Phase
    // unknown override: validator will emit INVALID_PHASE; for UI fallback to default
  }
  return registryEntry?.metadata?.phase ?? 'ANY'
}

/**
 * Numeric position in the linear order (0..5) for concrete phases, or -1 for ANY.
 * Use this for monotonicity comparisons (matches Java Phase.order()).
 */
export function phaseOrder(phase: Phase): number {
  return phase === 'ANY' ? -1 : CONCRETE_PHASES.indexOf(phase)
}

/** True iff successor's phase violates monotonicity vs predecessor. ANY is transparent. */
export function violatesMonotonic(predecessor: Phase, successor: Phase): boolean {
  if (predecessor === 'ANY' || successor === 'ANY') return false
  return phaseOrder(successor) < phaseOrder(predecessor)
}
