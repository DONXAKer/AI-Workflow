import type { Page } from '@playwright/test'
import path from 'node:path'
import { apiGet, apiPost } from './api-client'
import type { PipelineEntryPoint } from './pipelines'

export interface LaunchArgs {
  projectSlug: string
  /** Backend-resolved configPath — must come from GET /api/pipelines (NOT a local FS path). */
  pipelineConfigPath: string
  entryPoint: PipelineEntryPoint
  inputs: Record<string, unknown>
}

interface StartRunResponse {
  runId?: string
  id?: string
}

interface ApiPipelineRow {
  name?: string
  path?: string
}

/**
 * Looks up the backend's absolute configPath for the named pipeline. Required
 * because POST /api/runs validates configPath against the backend container's
 * filesystem — our local FS view is unrelated.
 */
export async function resolveBackendConfigPath(
  page: Page,
  projectSlug: string,
  pipelineFilename: string,
): Promise<string> {
  const list = await apiGet<ApiPipelineRow[]>(page, `/api/pipelines?projectSlug=${projectSlug}`, {
    projectSlug,
  })
  const match = Array.isArray(list)
    ? list.find((p) => p?.name === pipelineFilename && typeof p.path === 'string')
    : null
  if (!match?.path) {
    throw new Error(
      `Backend did not return a configPath for ${pipelineFilename}. Got: ${JSON.stringify(list).slice(0, 500)}`,
    )
  }
  return match.path
}

/**
 * Posts POST /api/runs. The UI's LaunchTab does the same with the same payload
 * shape — we go API-direct so the test is deterministic across UI refactors.
 * The launch screenshot is captured separately by navigating to the LaunchTab
 * beforehand.
 */
export async function launchPipeline(page: Page, args: LaunchArgs): Promise<string> {
  const body: Record<string, unknown> = {
    configPath: args.pipelineConfigPath,
    entryPointId: args.entryPoint.id,
    inputs: args.inputs,
    projectSlug: args.projectSlug,
  }
  const res = await apiPost<StartRunResponse>(page, '/api/runs', body, {
    projectSlug: args.projectSlug,
  })
  const runId = res?.runId ?? res?.id
  if (!runId) {
    throw new Error(
      `POST /api/runs returned no runId/id. Response: ${JSON.stringify(res).slice(0, 500)}`,
    )
  }
  return runId
}

/**
 * Default inputs for each requires_input contract documented in
 * RunController.resolveInputFields() (server-side).
 */
export function defaultInputsFor(
  ep: PipelineEntryPoint,
  workingDir: string,
): Record<string, unknown> {
  const ri = ep.requiresInput
  const taskFile = 'WF-E2E-001-goodbye.md'
  // working_dir flows into ${input.working_dir} interpolation used by portable
  // generic templates (feature-generic, bugfix, docs, refactor, write-tests,
  // code-review). The path must be visible to the BACKEND container — the test
  // ensures this by writing the fixture under a shared mount.
  // _autoApproveAll: backend-side bulk approval (PipelineRunner.java:139) so
  // approval gates resolve without round-tripping the helper's polling loop.
  const base = { working_dir: workingDir, _autoApproveAll: true }
  switch (ri) {
    case 'requirement':
      return { ...base, requirement: "Add a goodbye() function in src/hello.ts that returns 'bye'." }
    case 'task_file':
      return { ...base, task_file: path.join(workingDir, taskFile) }
    case 'youtrack_issue':
      return { ...base, youtrackIssue: process.env.E2E_YOUTRACK_ISSUE ?? 'WF-1' }
    case 'youtrack_issue_and_branch':
      return {
        ...base,
        youtrackIssue: process.env.E2E_YOUTRACK_ISSUE ?? 'WF-1',
        branchName: process.env.E2E_BRANCH_NAME ?? 'feature/WF-1-test',
      }
    case 'youtrack_issue_and_mr':
      return {
        ...base,
        youtrackIssue: process.env.E2E_YOUTRACK_ISSUE ?? 'WF-1',
        mrIid: Number(process.env.E2E_MR_IID ?? 1),
      }
    case 'none':
      return { ...base }
    default:
      return { ...base, requirement: "Add a goodbye() function in src/hello.ts that returns 'bye'." }
  }
}

/**
 * Navigates to the LaunchTab so the spec can capture a screenshot of the
 * populated form. Picking pipeline + entry point in the UI is not needed for
 * the actual launch (we POST directly), but is useful evidence in the report.
 */
export async function visitLaunchTab(page: Page, projectSlug: string): Promise<void> {
  await page.goto(`/projects/${projectSlug}/launch`)
  await page.waitForLoadState('domcontentloaded')
}
