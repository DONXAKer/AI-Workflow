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

export function pipelineSkipReason(p: EnumeratedPipeline): string | undefined {
  const env = loadEnv()
  if (p.entryPoints.length === 0) {
    return `${p.filename} has no entry_points`
  }
  if (PROJECT_SPECIFIC_PIPELINES.has(p.filename)) {
    return `${p.filename} is project-specific (hardcoded paths) — not portable to mini-target-repo`
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
export function entryPointSkipReason(
  p: EnumeratedPipeline,
  ep: PipelineEntryPoint,
): string | undefined {
  const env = loadEnv()
  const ri = ep.requiresInput
  if (ri === 'youtrack_issue_and_branch' || ri === 'youtrack_issue_and_mr') {
    return `${ep.id}: requires pre-existing branch/MR — not auto-provisioned`
  }
  if (
    (ri === 'youtrack_issue' || ri?.startsWith('youtrack_')) &&
    !env.youtrackToken
  ) {
    return `${ep.id}: requires YouTrack token (set YOUTRACK_TOKEN)`
  }
  return undefined
}
