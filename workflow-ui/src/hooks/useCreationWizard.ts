import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react'
import {
  BlockConfigDto, BlockRegistryEntry, EntryPointConfigDto, Phase, PipelineConfigDto,
  ValidationError,
} from '../types'
import { api } from '../services/api'
import { recommendedFor } from '../components/PipelineEditor/Wizard/recommendedFor'

// ── Types ────────────────────────────────────────────────────────────────────

/**
 * Wizard step. Phases (`INTAKE | ANALYZE | IMPLEMENT | VERIFY | PUBLISH | RELEASE`)
 * map 1:1 to their pipeline phase. `RETRY` and `PREVIEW` are wizard-only steps.
 *
 * Note: `Phase` from `types.ts` includes `ANY` which we never surface in the
 * wizard — orchestrator-style power-user blocks are added post-create via the
 * regular palette.
 */
export type WizardStep =
  | 'INTAKE' | 'ANALYZE' | 'IMPLEMENT' | 'VERIFY' | 'RETRY' | 'PUBLISH' | 'RELEASE' | 'PREVIEW'

export type WizardPhase = Exclude<Phase, 'ANY'>

const PHASE_STEPS: WizardPhase[] = ['INTAKE', 'ANALYZE', 'IMPLEMENT', 'VERIFY', 'PUBLISH', 'RELEASE']

export type PhaseStatus = 'upcoming' | 'current' | 'skipped' | 'completed'

export interface PhaseState {
  status: PhaseStatus
  blocks: BlockConfigDto[]
}

export interface WizardState {
  currentStep: WizardStep
  phases: Record<WizardPhase, PhaseState>
  retryEnabled: boolean
  slug: string
  displayName: string
  description: string
  /** Latest validation result errors (from validate-body). */
  validationErrors: ValidationError[]
  /** True while a validate-body request is in flight. */
  validating: boolean
  /** True iff at least one user mutation has occurred (drives beforeunload guard). */
  dirty: boolean
}

type Action =
  | { type: 'SET_NAME'; field: 'slug' | 'displayName' | 'description'; value: string }
  | { type: 'GOTO_STEP'; step: WizardStep }
  | { type: 'NEXT' }
  | { type: 'PREV' }
  | { type: 'SKIP_PHASE'; phase: WizardPhase }
  | { type: 'REPLACE_BLOCK'; phase: WizardPhase; index: number; block: BlockConfigDto }
  | { type: 'ADD_BLOCK'; phase: WizardPhase; block: BlockConfigDto }
  | { type: 'PATCH_BLOCK_CONFIG'; phase: WizardPhase; index: number; config: Record<string, unknown> }
  | { type: 'PATCH_BLOCK'; phase: WizardPhase; index: number; patch: Partial<BlockConfigDto> }
  | { type: 'REMOVE_BLOCK'; phase: WizardPhase; index: number }
  | { type: 'SET_RETRY'; value: boolean }
  | { type: 'SET_VALIDATION'; errors: ValidationError[]; validating: boolean }

// ── Reducer ──────────────────────────────────────────────────────────────────

function initialPhaseStates(): Record<WizardPhase, PhaseState> {
  const out = {} as Record<WizardPhase, PhaseState>
  for (let i = 0; i < PHASE_STEPS.length; i++) {
    const p = PHASE_STEPS[i]
    out[p] = { status: i === 0 ? 'current' : 'upcoming', blocks: [] }
  }
  return out
}

function initialState(): WizardState {
  return {
    currentStep: 'INTAKE',
    phases: initialPhaseStates(),
    retryEnabled: true,
    slug: '',
    displayName: '',
    description: '',
    validationErrors: [],
    validating: false,
    dirty: false,
  }
}

function isPhaseStep(step: WizardStep): step is WizardPhase {
  return (PHASE_STEPS as string[]).includes(step)
}

/**
 * Compute the linear order of wizard steps given current state. RETRY only
 * appears between VERIFY and PUBLISH if the wizard determines retry is
 * applicable (verify or CI block selected).
 */
function stepOrder(state: WizardState): WizardStep[] {
  const out: WizardStep[] = ['INTAKE', 'ANALYZE', 'IMPLEMENT', 'VERIFY']
  if (showRetryStepFor(state)) out.push('RETRY')
  out.push('PUBLISH', 'RELEASE', 'PREVIEW')
  return out
}

function showRetryStepFor(state: WizardState): boolean {
  // RETRY visible iff either a verify block (VERIFY phase chosen) or a CI block
  // (PUBLISH phase contains gitlab_ci/github_actions) exists.
  const verifyBlocks = state.phases.VERIFY.blocks
  const publishBlocks = state.phases.PUBLISH.blocks
  const hasVerify = verifyBlocks.some(b => b.block === 'verify' || b.block === 'agent_verify')
  const hasCi = publishBlocks.some(b => b.block === 'gitlab_ci' || b.block === 'github_actions')
  return hasVerify || hasCi
}

/**
 * When transitioning OFF of a phase step, mark its status. Skipped blocks
 * collection means user explicitly skipped; otherwise completed.
 */
function commitPhaseStatus(state: WizardState, phase: WizardPhase, status: PhaseStatus): WizardState {
  return {
    ...state,
    phases: {
      ...state.phases,
      [phase]: { ...state.phases[phase], status },
    },
  }
}

function setStep(state: WizardState, next: WizardStep): WizardState {
  // Mark current phase 'completed' (or leave 'skipped'); set incoming phase 'current'.
  let mut: WizardState = state
  if (isPhaseStep(state.currentStep) && state.currentStep !== next) {
    const prev = state.phases[state.currentStep]
    if (prev.status !== 'skipped') {
      mut = commitPhaseStatus(mut, state.currentStep, 'completed')
    }
  }
  if (isPhaseStep(next)) {
    const incoming = mut.phases[next]
    if (incoming.status === 'upcoming') {
      mut = commitPhaseStatus(mut, next, 'current')
    } else if (incoming.status === 'completed') {
      mut = commitPhaseStatus(mut, next, 'current')
    }
    // 'skipped' stays 'skipped' so the side-rail still shows the user explicitly skipped it.
  }
  return { ...mut, currentStep: next }
}

function reducer(state: WizardState, action: Action): WizardState {
  switch (action.type) {
    case 'SET_NAME':
      return { ...state, [action.field]: action.value, dirty: true }

    case 'GOTO_STEP':
      return setStep(state, action.step)

    case 'NEXT': {
      const order = stepOrder(state)
      const idx = order.indexOf(state.currentStep)
      if (idx < 0 || idx >= order.length - 1) return state
      return setStep(state, order[idx + 1])
    }

    case 'PREV': {
      const order = stepOrder(state)
      const idx = order.indexOf(state.currentStep)
      if (idx <= 0) return state
      return setStep(state, order[idx - 1])
    }

    case 'SKIP_PHASE': {
      const next: WizardState = {
        ...state,
        phases: {
          ...state.phases,
          [action.phase]: { status: 'skipped', blocks: [] },
        },
        dirty: true,
      }
      // After skipping, advance to the next step.
      const order = stepOrder(next)
      const idx = order.indexOf(action.phase)
      if (idx >= 0 && idx < order.length - 1) {
        return setStep(next, order[idx + 1])
      }
      return next
    }

    case 'REPLACE_BLOCK': {
      const phaseState = state.phases[action.phase]
      const blocks = [...phaseState.blocks]
      blocks[action.index] = action.block
      return {
        ...state,
        phases: { ...state.phases, [action.phase]: { ...phaseState, blocks } },
        dirty: true,
      }
    }

    case 'ADD_BLOCK': {
      const phaseState = state.phases[action.phase]
      return {
        ...state,
        phases: {
          ...state.phases,
          [action.phase]: { ...phaseState, blocks: [...phaseState.blocks, action.block] },
        },
        dirty: true,
      }
    }

    case 'PATCH_BLOCK_CONFIG': {
      const phaseState = state.phases[action.phase]
      const blocks = [...phaseState.blocks]
      const target = blocks[action.index]
      if (!target) return state
      blocks[action.index] = { ...target, config: action.config }
      return {
        ...state,
        phases: { ...state.phases, [action.phase]: { ...phaseState, blocks } },
        dirty: true,
      }
    }

    case 'PATCH_BLOCK': {
      const phaseState = state.phases[action.phase]
      const blocks = [...phaseState.blocks]
      const target = blocks[action.index]
      if (!target) return state
      blocks[action.index] = { ...target, ...action.patch }
      return {
        ...state,
        phases: { ...state.phases, [action.phase]: { ...phaseState, blocks } },
        dirty: true,
      }
    }

    case 'REMOVE_BLOCK': {
      const phaseState = state.phases[action.phase]
      const blocks = phaseState.blocks.filter((_, i) => i !== action.index)
      return {
        ...state,
        phases: { ...state.phases, [action.phase]: { ...phaseState, blocks } },
        dirty: true,
      }
    }

    case 'SET_RETRY':
      return { ...state, retryEnabled: action.value, dirty: true }

    case 'SET_VALIDATION':
      return { ...state, validationErrors: action.errors, validating: action.validating }

    default:
      return state
  }
}

// ── previewConfig builder ────────────────────────────────────────────────────

/**
 * Generate a unique block id from a base type, given an existing-id set. Mirrors
 * the convention used elsewhere in the editor (`<type>`, then `<type>_2`, …).
 */
function uniqueId(base: string, existing: Set<string>): string {
  let id = base
  let n = 1
  while (existing.has(id)) id = `${base}_${++n}`
  return id
}

/**
 * Builds a deterministic PipelineConfigDto from wizard state. Used both for
 * live validation and as the {@code pipeline} body sent to {@code POST
 * /api/pipelines/new}.
 *
 *  - IDs are auto-numbered for duplicate types (analysis, analysis_2, …).
 *  - depends_on chains: last block of previous non-skipped phase →
 *    first block of current phase. Multiple blocks within a phase
 *    chain sequentially in their declared order.
 *  - Retry edges (only when {@code retryEnabled}):
 *      * Any verify-type block in VERIFY gets {@code verify.on_fail =
 *        loopback → last IMPLEMENT block, max_iterations: 2}.
 *      * Any CI block in PUBLISH gets {@code on_failure = loopback → first
 *        IMPLEMENT block, max_iterations: 2, failed_statuses}.
 */
export function buildPreviewConfig(state: WizardState): PipelineConfigDto {
  const out: BlockConfigDto[] = []
  const existing = new Set<string>()

  // First pass: assign unique IDs deterministically per phase order.
  // Track the resolved block list per phase so we can build depends_on chains
  // and locate retry targets.
  const resolvedByPhase: Record<WizardPhase, BlockConfigDto[]> = {
    INTAKE: [], ANALYZE: [], IMPLEMENT: [], VERIFY: [], PUBLISH: [], RELEASE: [],
  }

  for (const phase of PHASE_STEPS) {
    const phaseState = state.phases[phase]
    if (phaseState.status === 'skipped') continue
    for (const b of phaseState.blocks) {
      const id = uniqueId(b.id || b.block, existing)
      existing.add(id)
      const cloned: BlockConfigDto = { ...b, id }
      resolvedByPhase[phase].push(cloned)
    }
  }

  // Second pass: depends_on chain across phases + within-phase sequencing.
  let lastInPriorPhase: string | null = null
  for (const phase of PHASE_STEPS) {
    const blocks = resolvedByPhase[phase]
    if (blocks.length === 0) continue
    for (let i = 0; i < blocks.length; i++) {
      const prevId = i === 0 ? lastInPriorPhase : blocks[i - 1].id
      if (prevId) {
        const existingDeps = blocks[i].depends_on ?? []
        if (!existingDeps.includes(prevId)) {
          blocks[i] = { ...blocks[i], depends_on: [...existingDeps, prevId] }
        }
      }
    }
    lastInPriorPhase = blocks[blocks.length - 1].id
  }

  // Third pass: retry edges.
  if (state.retryEnabled) {
    const implBlocks = resolvedByPhase.IMPLEMENT
    if (implBlocks.length > 0) {
      const firstImpl = implBlocks[0].id
      const lastImpl = implBlocks[implBlocks.length - 1].id

      // VERIFY → loopback → lastImpl on verify-type blocks.
      resolvedByPhase.VERIFY = resolvedByPhase.VERIFY.map(b => {
        if (b.block !== 'verify' && b.block !== 'agent_verify') return b
        const verify = { ...(b.verify ?? {}) }
        verify.on_fail = {
          ...(verify.on_fail ?? {}),
          action: 'loopback',
          target: lastImpl,
          max_iterations: 2,
        }
        return { ...b, verify }
      })

      // PUBLISH → on_failure → firstImpl on CI blocks.
      resolvedByPhase.PUBLISH = resolvedByPhase.PUBLISH.map(b => {
        if (b.block !== 'gitlab_ci' && b.block !== 'github_actions') return b
        return {
          ...b,
          on_failure: {
            ...(b.on_failure ?? {}),
            action: 'loopback',
            target: firstImpl,
            max_iterations: 2,
            failed_statuses: ['failure', 'failed', 'timeout'],
          },
        }
      })
    }
  }

  // Flatten in canonical phase order.
  for (const phase of PHASE_STEPS) {
    out.push(...resolvedByPhase[phase])
  }

  // Default entry_points: a single "from_scratch" pointing at the first block.
  let entry_points: EntryPointConfigDto[] | undefined
  if (out.length > 0) {
    entry_points = [
      {
        id: 'from_scratch',
        name: 'Новый запуск',
        from_block: out[0].id,
      },
    ]
  }

  return {
    name: state.displayName || state.slug || 'untitled',
    description: state.description || undefined,
    pipeline: out,
    entry_points,
  }
}

// ── isStepValid / canCreate ─────────────────────────────────────────────────

function hasImplementBlock(state: WizardState): boolean {
  return state.phases.IMPLEMENT.blocks.length > 0
}

function isStepValidFor(state: WizardState, step: WizardStep): boolean {
  if (step === 'PREVIEW') {
    if (!hasImplementBlock(state)) return false
    if (state.validating) return true // optimistic — preview is reachable while in flight
    return state.validationErrors.filter(e => (e.severity ?? 'ERROR') === 'ERROR').length === 0
  }
  if (step === 'RETRY') return true
  if (isPhaseStep(step)) {
    // Phase steps are always "valid" — user can skip/leave empty (caught at PREVIEW).
    return true
  }
  return true
}

// ── Hook ────────────────────────────────────────────────────────────────────

export interface UseCreationWizard {
  state: WizardState
  /** Step navigation. */
  goto: (step: WizardStep) => void
  next: () => void
  prev: () => void
  /** Pre-step name fields. */
  setSlug: (v: string) => void
  setDisplayName: (v: string) => void
  setDescription: (v: string) => void
  /** Phase-level. */
  skipPhase: (phase: WizardPhase) => void
  replaceBlock: (phase: WizardPhase, index: number, block: BlockConfigDto) => void
  addBlock: (phase: WizardPhase, block: BlockConfigDto) => void
  patchBlockConfig: (phase: WizardPhase, index: number, config: Record<string, unknown>) => void
  patchBlock: (phase: WizardPhase, index: number, patch: Partial<BlockConfigDto>) => void
  removeBlock: (phase: WizardPhase, index: number) => void
  setRetry: (v: boolean) => void
  /** Computed. */
  recommendedFor: (phase: WizardPhase) => BlockRegistryEntry | null
  previewConfig: PipelineConfigDto
  isStepValid: (step: WizardStep) => boolean
  canCreate: boolean
  showRetryStep: boolean
  stepOrder: WizardStep[]
  /** Trigger debounced live validation. Caller is the auto-effect in CreationWizard. */
  runValidation: () => void
}

/**
 * Custom hook backing the Creation Wizard. Owns wizard state via useReducer and
 * exposes high-level actions + computed views (`previewConfig`, `recommendedFor`,
 * `isStepValid`, `canCreate`).
 *
 * Live validation is debounced 300ms with AbortController-based cancellation —
 * each call to {@link runValidation} schedules a timer; the previous timer (if
 * any) is cleared, and any in-flight fetch is aborted. The hook is otherwise
 * passive (it does not auto-trigger validation; the caller wires up the effect).
 */
export function useCreationWizard(registry: BlockRegistryEntry[]): UseCreationWizard {
  const [state, dispatch] = useReducer(reducer, undefined, initialState)

  // Memo deps deliberately exclude validationErrors / validating / currentStep —
  // those don't affect the produced PipelineConfig, and including them would
  // cause an infinite validation loop (validate → setError → new state → new
  // previewConfig ref → effect re-fires → validate again).
  const previewConfig = useMemo(
    () => buildPreviewConfig(state),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [state.phases, state.retryEnabled, state.slug, state.displayName, state.description],
  )

  const validationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const validationAbortRef = useRef<AbortController | null>(null)

  const runValidation = useCallback(() => {
    if (validationTimerRef.current) {
      clearTimeout(validationTimerRef.current)
      validationTimerRef.current = null
    }
    if (validationAbortRef.current) {
      validationAbortRef.current.abort()
      validationAbortRef.current = null
    }

    // Debounce 300ms.
    validationTimerRef.current = setTimeout(async () => {
      const ctrl = new AbortController()
      validationAbortRef.current = ctrl
      // We deliberately don't pass through prior errors during the in-flight
      // window — empty list + validating=true is the cleanest "spinner" signal.
      dispatch({ type: 'SET_VALIDATION', errors: [], validating: true })
      try {
        const result = await api.validatePipelineBody(previewConfig)
        if (ctrl.signal.aborted) return
        dispatch({ type: 'SET_VALIDATION', errors: result.errors, validating: false })
      } catch (e) {
        if (ctrl.signal.aborted) return
        // Surface as a synthetic error so the banner shows something actionable.
        const msg = e instanceof Error ? e.message : 'Ошибка валидации'
        dispatch({
          type: 'SET_VALIDATION',
          errors: [{ code: 'NETWORK_ERROR', message: msg, location: null, blockId: null, severity: 'ERROR' }],
          validating: false,
        })
      } finally {
        if (validationAbortRef.current === ctrl) validationAbortRef.current = null
      }
    }, 300)
  }, [previewConfig])

  // Cleanup on unmount.
  useEffect(() => () => {
    if (validationTimerRef.current) clearTimeout(validationTimerRef.current)
    if (validationAbortRef.current) validationAbortRef.current.abort()
  }, [])

  const recommendedForPhase = useCallback((phase: WizardPhase): BlockRegistryEntry | null => {
    return recommendedFor(phase, registry, state.phases[phase].blocks)
  }, [registry, state.phases])

  const showRetryStep = showRetryStepFor(state)

  const isStepValid = useCallback((step: WizardStep) => isStepValidFor(state, step), [state])

  const canCreate = useMemo(() => {
    if (!state.slug || !/^[a-z0-9][a-z0-9-]*$/.test(state.slug)) return false
    if (!state.displayName.trim()) return false
    if (!hasImplementBlock(state)) return false
    if (state.validating) return false
    return state.validationErrors.filter(e => (e.severity ?? 'ERROR') === 'ERROR').length === 0
  }, [state])

  const order = useMemo(() => stepOrder(state), [state])

  return {
    state,
    goto: (step) => dispatch({ type: 'GOTO_STEP', step }),
    next: () => dispatch({ type: 'NEXT' }),
    prev: () => dispatch({ type: 'PREV' }),
    setSlug: (v) => dispatch({ type: 'SET_NAME', field: 'slug', value: v }),
    setDisplayName: (v) => dispatch({ type: 'SET_NAME', field: 'displayName', value: v }),
    setDescription: (v) => dispatch({ type: 'SET_NAME', field: 'description', value: v }),
    skipPhase: (phase) => dispatch({ type: 'SKIP_PHASE', phase }),
    replaceBlock: (phase, index, block) => dispatch({ type: 'REPLACE_BLOCK', phase, index, block }),
    addBlock: (phase, block) => dispatch({ type: 'ADD_BLOCK', phase, block }),
    patchBlockConfig: (phase, index, config) =>
      dispatch({ type: 'PATCH_BLOCK_CONFIG', phase, index, config }),
    patchBlock: (phase, index, patch) => dispatch({ type: 'PATCH_BLOCK', phase, index, patch }),
    removeBlock: (phase, index) => dispatch({ type: 'REMOVE_BLOCK', phase, index }),
    setRetry: (v) => dispatch({ type: 'SET_RETRY', value: v }),
    recommendedFor: recommendedForPhase,
    previewConfig,
    isStepValid,
    canCreate,
    showRetryStep,
    stepOrder: order,
    runValidation,
  }
}

export { PHASE_STEPS, showRetryStepFor }
