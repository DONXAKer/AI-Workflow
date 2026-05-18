import { loadEnv } from './env'
import type { EnumeratedPipeline, PipelineEntryPoint } from './pipelines'

/**
 * Decides whether a whole pipeline should be skipped for the current test run.
 * Returns a non-empty reason string when skipping, undefined otherwise.
 */
/**
 * Pipelines that bake operator-specific paths into the YAML (knowledge_base
 * sources, working_dir, shell commands rooted at a real on-disk project tree).
 * These are not portable templates and cannot run against the mini-target-repo
 * fixture without rewriting the YAML — out of scope for E2E.
 */
const PROJECT_SPECIFIC_PIPELINES = new Set([
  'ai_workflow.yaml',
  'personal_assistant.yaml',
  'skill_marketplace.yaml',
])

/**
 * Pipelines whose YAML still hardcodes per-block config that the test can't
 * synthesize from a fresh mini-target-repo. Tracked as a follow-up — these
 * need a config-field default (e.g. {@code branch: main} in git_branch_input)
 * before they can run portably.
 */
const PIPELINES_WITH_HARDCODED_BLOCK_DEPS = new Set([
  'refactor.yaml', // git_branch_input requires `branch` config, no default
])

export function pipelineSkipReason(p: EnumeratedPipeline): string | undefined {
  const env = loadEnv()
  if (p.entryPoints.length === 0) {
    return `${p.filename} has no entry_points`
  }
  if (PROJECT_SPECIFIC_PIPELINES.has(p.filename)) {
    return `${p.filename} is project-specific (hardcoded paths) — not portable to mini-target-repo`
  }
  if (PIPELINES_WITH_HARDCODED_BLOCK_DEPS.has(p.filename)) {
    return `${p.filename} needs block-config defaults the test does not synthesize yet`
  }
  if (p.filename === 'pipeline.example.yaml' && !env.pipelineExampleEnabled) {
    return 'pipeline.example.yaml is env-gated (set E2E_PIPELINE_EXAMPLE=1)'
  }
  return undefined
}

/**
 * Some entry-points require external state that an automated test can't easily
 * provision (existing YouTrack issue / GitLab branch / GitHub PR). Skip those
 * for now — they remain reachable manually via the UI.
 */
/**
 * Entry-points that require pre-existing state we don't provision in the fixture:
 * a real YouTrack issue ID, an existing git branch on a remote, an open MR.
 * The mini-target-repo is brand-new per spec, so these EPs always fail unless an
 * operator wires real state — keep them out of the default matrix.
 */
const EXTERNAL_STATE_INPUTS = new Set([
  'youtrack_issue',
  'youtrack_issue_and_branch',
  'youtrack_issue_and_mr',
  'branch_name',
  'mr_url',
])

export function entryPointSkipReason(
  p: EnumeratedPipeline,
  ep: PipelineEntryPoint,
): string | undefined {
  loadEnv()
  const ri = ep.requiresInput ?? ''
  if (EXTERNAL_STATE_INPUTS.has(ri)) {
    return `${ep.id}: requires pre-existing external state (${ri}) — not auto-provisioned`
  }
  return undefined
}
