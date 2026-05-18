import { test, expect } from '@playwright/test'
import { loginAsAdmin } from '../helpers/auth'
import { apiGet } from '../helpers/api-client'
import { enumeratePipelines } from '../helpers/pipelines'
import { prepareTargetRepo } from '../helpers/target-repo'
import { ensureProject } from '../helpers/project'

interface ApiPipeline {
  name?: string
  path?: string
  source?: string
}

/**
 * Sanity check: enumeration via filesystem (which test-discovery uses to
 * generate the matrix) and the backend's view via /api/pipelines must agree
 * on the set of pipeline filenames. Detects drift between PipelineConfigLoader
 * (server-side) and helpers/pipelines.ts (test-side).
 */
test('FS-enumeration matches GET /api/pipelines', async ({ page }) => {
  await loginAsAdmin(page)
  const workingDir = await prepareTargetRepo('integrity')
  const slug = 'e2e-integrity'
  await ensureProject(page, { slug, workingDir, displayName: 'E2E integrity check' })

  const fsSet = new Set(
    enumeratePipelines({ projectWorkingDir: workingDir }).map((p) => p.filename),
  )
  const apiList = await apiGet<ApiPipeline[]>(page, `/api/pipelines?projectSlug=${slug}`, {
    projectSlug: slug,
  })
  const apiSet = new Set(
    (Array.isArray(apiList) ? apiList : [])
      .map((p) => p.name)
      .filter((n): n is string => typeof n === 'string'),
  )

  expect(apiSet.size, 'GET /api/pipelines returned no pipelines').toBeGreaterThan(0)
  expect([...apiSet].sort()).toEqual([...fsSet].sort())
})
