import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'
import { setupEditorMocks } from '../fixtures/pipeline-editor-fixtures'

/**
 * PR-2 (side-panel restructure) Playwright coverage.
 *
 * The new SidePanel layout:
 *   • PinnedHeader  — id input, type/label, enabled/approval, phase, errors banner
 *   • Section: Essentials       (depends_on + essential schema fields)   data-testid="section-essentials"
 *   • Section: Conditions/retry (condition, on_fail, on_failure)         data-testid="section-conditions-retry"
 *   • Section: Advanced         (advanced schema fields, agent overrides) data-testid="section-advanced"
 */
test.describe('Pipeline Editor — side-panel (PR-2)', () => {
  test('opening a block shows Essentials open by default and other sections collapsed', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-task_md').click()
    await expect(page.getByTestId('side-panel')).toBeVisible()

    // Essentials open
    await expect(page.getByTestId('section-essentials')).toHaveAttribute('data-open', 'true')
    // Conditions & retry collapsed
    await expect(page.getByTestId('section-conditions-retry')).toHaveAttribute('data-open', 'false')
    // Advanced collapsed
    await expect(page.getByTestId('section-advanced')).toHaveAttribute('data-open', 'false')
  })

  test('legacy testids preserved (id input, enabled, approval, depends-on, delete)', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-impl').click()

    await expect(page.getByTestId('block-id-input')).toHaveValue('impl')
    await expect(page.getByTestId('block-enabled')).toBeVisible()
    await expect(page.getByTestId('block-approval')).toBeVisible()
    await expect(page.getByTestId('depends-on-picker')).toBeVisible()
    await expect(page.getByTestId('block-delete')).toBeVisible()
    await expect(page.getByTestId('phase-selector')).toBeVisible()
  })

  test('PhaseSelector lives in the pinned header (above sections, not in any section)', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-task_md').click()

    // PhaseSelector should not be a descendant of any section.
    const phaseInsideEssentials = page.getByTestId('section-essentials').getByTestId('phase-selector')
    const phaseInsideConditions = page.getByTestId('section-conditions-retry').getByTestId('phase-selector')
    const phaseInsideAdvanced = page.getByTestId('section-advanced').getByTestId('phase-selector')
    await expect(phaseInsideEssentials).toHaveCount(0)
    await expect(phaseInsideConditions).toHaveCount(0)
    await expect(phaseInsideAdvanced).toHaveCount(0)
    // …but it's visible in the panel.
    await expect(page.getByTestId('phase-selector')).toBeVisible()
  })

  test('clicking the toggle of a collapsed section opens it', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-task_md').click()

    await expect(page.getByTestId('section-conditions-retry')).toHaveAttribute('data-open', 'false')
    await page.getByTestId('section-conditions-retry-toggle').click()
    await expect(page.getByTestId('section-conditions-retry')).toHaveAttribute('data-open', 'true')
  })

  test('validation error in advanced (agent overrides) auto-expands the Advanced section', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page, {
      validateResult: {
        valid: false,
        errors: [
          { code: 'AGENT_INVALID', message: 'bad model', location: 'pipeline[2].agent.model', blockId: 'impl' },
        ],
      },
    })

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('toolbar-validate').click()
    // Wait for the per-node error to surface so we know the validate response is in.
    await expect(page.getByTestId('block-error-impl')).toBeVisible()

    await page.getByTestId('block-node-impl').click()
    await expect(page.getByTestId('section-advanced')).toHaveAttribute('data-open', 'true')
  })

  test('validation error in conditions (verify.on_fail.target) auto-expands Conditions & retry', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page, {
      validateResult: {
        valid: false,
        errors: [
          {
            code: 'LOOPBACK_TARGET_UNKNOWN',
            message: 'unknown loopback target',
            location: 'pipeline[3].verify.on_fail.target',
            blockId: 'review',
          },
        ],
      },
    })

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('toolbar-validate').click()
    await expect(page.getByTestId('block-error-review')).toBeVisible()

    await page.getByTestId('block-node-review').click()
    await expect(page.getByTestId('section-conditions-retry')).toHaveAttribute('data-open', 'true')
  })

  test('verify-block: Conditions & Retry contains the on_fail target selector', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-review').click()

    // Open the section first
    await page.getByTestId('section-conditions-retry-toggle').click()
    await expect(page.getByTestId('section-conditions-retry')).toHaveAttribute('data-open', 'true')

    // The on-fail editor + target are inside this section
    const section = page.getByTestId('section-conditions-retry')
    await expect(section.getByTestId('on-fail-editor')).toBeVisible()
    await expect(section.getByTestId('verify-on-fail-action')).toBeVisible()
    await expect(section.getByTestId('verify-on-fail-target')).toBeVisible()
    await expect(section.getByTestId('verify-on-fail-target')).toHaveValue('impl')
  })

  test('OutputsRefPicker on condition exposes ancestor outputs as $.X.Y options', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    // `impl` depends on `create_branch` → `task_md`. task_md_input declares
    // outputs feat_id/title/body/acceptance.
    await page.getByTestId('block-node-impl').click()
    await page.getByTestId('section-conditions-retry-toggle').click()

    // The picker wrapper exposes `outputs-ref-picker-condition`; its datalist
    // gets `block-condition-options` (suffix on the legacy input testid).
    await expect(page.getByTestId('outputs-ref-picker-condition')).toBeVisible()
    const datalist = page.getByTestId('block-condition-options')
    // Should include ancestor task_md outputs
    await expect(datalist.locator('option[value="$.task_md.feat_id"]')).toHaveCount(1)
    await expect(datalist.locator('option[value="$.task_md.title"]')).toHaveCount(1)
    // Should NOT include impl's own outputs — current block is excluded.
    await expect(datalist.locator('option[value="$.impl.final_text"]')).toHaveCount(0)
  })

  test('typing into the condition picker is preserved (free text allowed)', async ({ page }) => {
    let saveBody: unknown = null
    await setupApiMocks(page)
    await setupEditorMocks(page, { onSaveBody: body => { saveBody = body } })

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-impl').click()
    await page.getByTestId('section-conditions-retry-toggle').click()

    const cond = page.getByTestId('block-condition')
    await cond.fill('$.task_md.feat_id == "FOO-1"')

    await page.getByTestId('toolbar-save').click()
    await expect.poll(() => saveBody).not.toBeNull()
    const body = saveBody as { pipeline: Array<{ id: string; condition?: string | null }> }
    const impl = body.pipeline.find(b => b.id === 'impl')
    expect(impl?.condition).toBe('$.task_md.feat_id == "FOO-1"')
  })

  test('Advanced section renders agent override fields', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-impl').click()
    await page.getByTestId('section-advanced-toggle').click()

    const advanced = page.getByTestId('section-advanced')
    await expect(advanced.getByTestId('agent-overrides')).toBeVisible()
  })

  test('Essentials section renders depends_on + essential schema fields only', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    // create_branch is a shell_exec block; command + working_dir are essential,
    // timeout_sec + allow_nonzero_exit are advanced.
    await page.getByTestId('block-node-create_branch').click()

    const essentials = page.getByTestId('section-essentials')
    await expect(essentials.getByTestId('depends-on-picker')).toBeVisible()
    await expect(essentials.getByTestId('field-command')).toBeVisible()
    await expect(essentials.getByTestId('field-working_dir')).toBeVisible()
    // Advanced fields should NOT appear in Essentials.
    await expect(essentials.getByTestId('field-timeout_sec')).toHaveCount(0)
    await expect(essentials.getByTestId('field-allow_nonzero_exit')).toHaveCount(0)
  })

  test('Advanced section renders the advanced schema fields after expanding', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('block-node-create_branch').click()
    await page.getByTestId('section-advanced-toggle').click()

    const advanced = page.getByTestId('section-advanced')
    await expect(advanced.getByTestId('field-timeout_sec')).toBeVisible()
    await expect(advanced.getByTestId('field-allow_nonzero_exit')).toBeVisible()
  })
})
