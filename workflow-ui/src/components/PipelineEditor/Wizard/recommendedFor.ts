import { BlockConfigDto, BlockRegistryEntry, Phase } from '../../../types'

/**
 * Hardcoded preference per phase used as tie-breaker when multiple registry
 * entries share the maximum {@code recommendedRank}. Order matters — the first
 * matching type in the list wins. Phases not listed fall through to the
 * alphabetical fallback.
 *
 * Decisions (grill 2026-05-07):
 *  - INTAKE: task_md_input over youtrack_input (file-based intake is the v1
 *    happy path — youtrack requires integration setup).
 *  - IMPLEMENT: agent_with_tools over code_generation (agentic loop is the
 *    canonical "smart" implementer; code_generation is fallback for rigid
 *    single-shot pipelines).
 *  - PUBLISH: github_pr over gitlab_mr (statistically more common; GitLab is
 *    a one-radio-click swap).
 */
const PREFERRED: Partial<Record<Phase, string[]>> = {
  INTAKE: ['task_md_input', 'youtrack_input'],
  IMPLEMENT: ['agent_with_tools', 'code_generation'],
  PUBLISH: ['github_pr', 'gitlab_mr'],
}

/**
 * Returns the registry entry that should be pre-selected for a given pipeline
 * phase, or {@code null} if the phase has no candidate blocks.
 *
 * Algorithm (deterministic):
 *  1. Filter registry → entries whose metadata.phase matches.
 *  2. If 0 candidates → null.
 *  3. Find max recommendedRank among candidates.
 *  4. If max === 0 → no rank declared; return alphabetically-first entry.
 *  5. If exactly one entry holds the max → return it.
 *  6. Tie-break via PREFERRED[phase] (first match wins).
 *  7. Fall back to alphabetical-first among the tied entries.
 *
 * Pure — no React hooks; safe for unit tests.
 *
 * @param phase           Target pipeline phase. ANY-phase blocks are never
 *                        recommended (orchestrator etc. — power-user feature).
 * @param registry        Full block registry from {@code GET /api/blocks/registry}.
 * @param chosenSoFar     Reserved for future use (e.g. avoiding duplicates).
 *                        Currently unused — kept in signature so callers don't
 *                        change when we add it.
 */
export function recommendedFor(
  phase: Phase,
  registry: BlockRegistryEntry[],
  _chosenSoFar: BlockConfigDto[] = [],
): BlockRegistryEntry | null {
  const candidates = registry.filter(e => e.metadata.phase === phase)
  if (candidates.length === 0) return null

  const ranks = candidates.map(e => e.metadata.recommendedRank ?? 0)
  const maxRank = Math.max(...ranks)

  if (maxRank === 0) {
    // No preferences declared — return alphabetical-first by type id.
    return [...candidates].sort((a, b) => a.type.localeCompare(b.type))[0]
  }

  const top = candidates.filter(e => (e.metadata.recommendedRank ?? 0) === maxRank)
  if (top.length === 1) return top[0]

  // Tie-break via PREFERRED.
  const preferred = PREFERRED[phase] ?? []
  for (const preferredType of preferred) {
    const hit = top.find(e => e.type === preferredType)
    if (hit) return hit
  }

  // Fallback: alphabetical-first.
  return [...top].sort((a, b) => a.type.localeCompare(b.type))[0]
}

export { PREFERRED as PREFERRED_PER_PHASE }
