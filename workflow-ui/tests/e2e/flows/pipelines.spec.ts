import { test, expect } from '@playwright/test'
import { loginAsAdmin } from '../helpers/auth'
import { enumeratePipelines } from '../helpers/pipelines'
import { prepareTargetRepo } from '../helpers/target-repo'
import { ensureProject, setDefaultProviderAllTokens, visitProjectSettings } from '../helpers/project'
import { ensureAllTokensIntegration, visitIntegrationsTab } from '../helpers/integrations'
import { defaultInputsFor, launchPipeline, resolveBackendConfigPath, visitLaunchTab } from '../helpers/launch'
import { autoApproveUntilTerminal } from '../helpers/approval'
import { shot } from '../helpers/screenshot'
import { entryPointSkipReason, pipelineSkipReason } from '../helpers/skip-rules'

/**
 * Data-driven E2E across every platform pipeline.
 *
 * The test tree is built at module-load time from the filesystem so Playwright
 * can enumerate test names without an async beforeAll round-trip. The
 * integrity.spec.ts asserts that this FS view matches what the backend serves.
 */
const PIPELINES = enumeratePipelines()

for (const p of PIPELINES) {
  const skip = pipelineSkipReason(p)

  test.describe.serial(p.filename, () => {
    test.skip(!!skip, skip ?? '')

    const slug = `e2e-${p.filename.replace(/\.[^.]+$/, '').replace(/[^a-z0-9-]/gi, '-').toLowerCase()}`
    let sharedWorkingDir: string

    test.beforeAll(async ({ browser }) => {
      const page = await browser.newPage()
      try {
        await loginAsAdmin(page)
        await shot(page, p.filename, '_setup', 1, 'login')
        sharedWorkingDir = await prepareTargetRepo(`${p.filename}-setup`)
        await ensureProject(page, {
          slug,
          workingDir: sharedWorkingDir,
          displayName: `E2E ${p.filename}`,
        })
        await ensureAllTokensIntegration(page, slug)
        await visitIntegrationsTab(page, slug)
        await shot(page, p.filename, '_setup', 2, 'integrations')
        await setDefaultProviderAllTokens(page, slug)
        await visitProjectSettings(page, slug)
        await shot(page, p.filename, '_setup', 3, 'settings-provider')
      } finally {
        await page.close()
      }
    })

    for (const ep of p.entryPoints) {
      const epSkip = entryPointSkipReason(p, ep)

      test(ep.id, async ({ page }) => {
        test.skip(!!epSkip, epSkip ?? '')

        await loginAsAdmin(page)
        const workingDir = await prepareTargetRepo(`${p.filename}-${ep.id}`)
        // Re-point the project at this EP's fresh repo so codegen/git_setup
        // operate on a clean tree per-EP rather than the beforeAll's snapshot.
        await ensureProject(page, { slug, workingDir, displayName: `E2E ${p.filename}` })

        await visitLaunchTab(page, slug)
        await shot(page, p.filename, ep.id, 4, 'launch-form')

        const inputs = defaultInputsFor(ep, workingDir)
        const backendConfigPath = await resolveBackendConfigPath(page, slug, p.filename)
        const runId = await launchPipeline(page, {
          projectSlug: slug,
          pipelineConfigPath: backendConfigPath,
          entryPoint: ep,
          inputs,
        })

        await page.goto(`/runs/${runId}`)
        await page.waitForLoadState('domcontentloaded')
        await shot(page, p.filename, ep.id, 5, 'run-started')

        let gateCounter = 0
        const status = await autoApproveUntilTerminal(page, runId, {
          projectSlug: slug,
          maxMinutes: 45,
          onApprovalGate: async (blockId) => {
            gateCounter += 1
            // Let the SPA repaint the open ApprovalDialog before capturing.
            await page.waitForTimeout(2_000)
            await shot(
              page,
              p.filename,
              ep.id,
              6,
              `approval-${String(gateCounter).padStart(2, '0')}-${blockId}`,
            )
          },
        })

        await shot(page, p.filename, ep.id, 7, 'run-completed')
        expect(status, `Run ${runId} ended in ${status}`).toBe('COMPLETED')
      })
    }
  })
}
