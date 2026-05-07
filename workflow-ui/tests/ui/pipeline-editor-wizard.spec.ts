import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'
import { setupEditorMocks } from '../fixtures/pipeline-editor-fixtures'

/**
 * PR-3 — Creation Wizard end-to-end Playwright coverage.
 *
 * Wizard flow: NewPipelineModal (custom) → CreationWizard → per-phase steps →
 * RETRY step → PREVIEW (validate-body banner + create button).
 */

async function openWizard(page: import('@playwright/test').Page, {
  validateBodyResult = { valid: true, errors: [] },
  onCreateBody = (_b: unknown) => {},
}: {
  validateBodyResult?: { valid: boolean; errors: unknown[] }
  onCreateBody?: (body: unknown) => void
} = {}) {
  await setupApiMocks(page)
  await setupEditorMocks(page)

  await page.route('**/api/pipelines/validate-body', async route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(validateBodyResult),
    }),
  )

  await page.route('**/api/pipelines/new', async route => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      onCreateBody(body)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          path: '/project/.ai-workflow/pipelines/my-pipe.yaml',
          name: 'my-pipe.yaml',
          pipelineName: 'My Pipeline',
        }),
      })
    }
  })

  await page.goto('/projects/default/settings')
  await page.getByRole('button', { name: 'Пайплайн' }).click()
  await page.waitForSelector('[data-testid="pipeline-canvas"]')

  await page.getByTestId('pipeline-new').click()
  await page.waitForSelector('[data-testid="new-pipeline-template-custom"]')
  await page.getByTestId('new-pipeline-template-custom').click()
  await page.getByTestId('new-pipeline-submit').click()
  await page.waitForSelector('[data-testid="wizard-slug"]')
}

test.describe('Creation Wizard (PR-3)', () => {
  test('wizard opens when custom template is selected', async ({ page }) => {
    await openWizard(page)

    // Header
    await expect(page.getByText('Новый пайплайн (мастер)')).toBeVisible()
    // Name fields
    await expect(page.getByTestId('wizard-slug')).toBeVisible()
    await expect(page.getByTestId('wizard-display-name')).toBeVisible()
    await expect(page.getByTestId('wizard-description')).toBeVisible()
    // First phase step rendered
    await expect(page.getByTestId('wizard-phase-step')).toBeVisible()
  })

  test('slug validation rejects invalid characters', async ({ page }) => {
    await openWizard(page)

    await page.getByTestId('wizard-slug').fill('My Pipeline!')
    // Error hint appears
    await expect(page.getByText('kebab-case: a-z, 0-9, -')).toBeVisible()
  })

  test('Next navigates from INTAKE to ANALYZE', async ({ page }) => {
    await openWizard(page)

    // INTAKE: slot 0 visible
    await expect(page.getByTestId('wizard-block-slot-0')).toBeVisible()

    await page.getByTestId('wizard-next').click()

    // ANALYZE: empty state (no candidates in fixture registry)
    await expect(page.getByText('В этой фазе ещё нет блоков')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).not.toBeVisible()
  })

  test('Prev navigates back to INTAKE from ANALYZE', async ({ page }) => {
    await openWizard(page)

    await page.getByTestId('wizard-next').click() // → ANALYZE
    await expect(page.getByText('В этой фазе ещё нет блоков')).toBeVisible()

    await page.getByTestId('wizard-prev').click() // → INTAKE
    await expect(page.getByTestId('wizard-block-slot-0')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).toContainText('task_md_input')
  })

  test('Skip phase marks phase as skipped and advances', async ({ page }) => {
    await openWizard(page)

    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-skip-phase').click() // skip ANALYZE → IMPLEMENT

    // Now at IMPLEMENT, agent_with_tools auto-seeded
    await expect(page.getByTestId('wizard-block-slot-0')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).toContainText('agent_with_tools')
  })

  test('Add Another block creates a second slot in the phase', async ({ page }) => {
    await openWizard(page)

    // INTAKE already has slot-0 (task_md_input)
    await page.getByTestId('wizard-add-another').click()
    // Dropdown shows task_md_input as the only INTAKE option
    await page.locator('[data-testid="wizard-add-another"] + div button').first().click()

    await expect(page.getByTestId('wizard-block-slot-1')).toBeVisible()
  })

  test('RETRY step shows retry checkbox (VERIFY phase has verify block)', async ({ page }) => {
    await openWizard(page)

    // Navigate: INTAKE → ANALYZE → IMPLEMENT → VERIFY
    // (verify block auto-seeded at VERIFY → showRetryStep = true)
    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-next').click() // → IMPLEMENT
    await page.getByTestId('wizard-next').click() // → VERIFY
    await page.getByTestId('wizard-next').click() // → RETRY (verify block triggers it)

    await expect(page.getByTestId('wizard-retry-checkbox')).toBeVisible()
  })

  test('PREVIEW step shows canvas, validation banner and create button', async ({ page }) => {
    await openWizard(page)

    // Navigate all the way to PREVIEW via skip-phase on empty phases
    // (faster: skip ANALYZE, skip PUBLISH, skip RELEASE from later steps)
    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-skip-phase').click() // skip → IMPLEMENT
    await page.getByTestId('wizard-next').click() // → VERIFY
    await page.getByTestId('wizard-next').click() // → RETRY (verify auto-seeded)
    await page.getByTestId('wizard-next').click() // → PUBLISH
    await page.getByTestId('wizard-skip-phase').click() // skip → RELEASE
    await page.getByTestId('wizard-skip-phase').click() // skip → PREVIEW

    await expect(page.getByTestId('wizard-preview-canvas')).toBeVisible()
    await expect(page.getByTestId('wizard-validation-banner')).toBeVisible()
    await expect(page.getByTestId('wizard-create-button')).toBeVisible()
  })

  test('Create button disabled when slug or displayName empty', async ({ page }) => {
    await openWizard(page)

    // Navigate to PREVIEW quickly (skip everything except IMPLEMENT which is needed)
    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-skip-phase').click() // → IMPLEMENT
    await page.getByTestId('wizard-next').click() // → VERIFY
    await page.getByTestId('wizard-next').click() // → RETRY
    await page.getByTestId('wizard-next').click() // → PUBLISH
    await page.getByTestId('wizard-skip-phase').click() // → RELEASE
    await page.getByTestId('wizard-skip-phase').click() // → PREVIEW

    // slug and displayName are empty → canCreate = false
    await expect(page.getByTestId('wizard-create-button')).toBeDisabled()
  })

  test('Create button enabled and submits when slug+displayName filled and config valid', async ({ page }) => {
    let capturedBody: unknown = null
    await openWizard(page, {
      onCreateBody: b => { capturedBody = b },
    })

    // Fill name fields first
    await page.getByTestId('wizard-slug').fill('my-pipe')
    await page.getByTestId('wizard-display-name').fill('My Pipeline')

    // Navigate to PREVIEW (skip ANALYZE/PUBLISH/RELEASE)
    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-skip-phase').click() // → IMPLEMENT
    await page.getByTestId('wizard-next').click() // → VERIFY
    await page.getByTestId('wizard-next').click() // → RETRY
    await page.getByTestId('wizard-next').click() // → PUBLISH
    await page.getByTestId('wizard-skip-phase').click() // → RELEASE
    await page.getByTestId('wizard-skip-phase').click() // → PREVIEW

    // Wait for validation to complete (banner shows green)
    await expect(page.getByText('Готово — конфиг валиден.')).toBeVisible({ timeout: 5000 })

    await expect(page.getByTestId('wizard-create-button')).toBeEnabled()
    await page.getByTestId('wizard-create-button').click()

    // Wizard closes (canvas visible again in background)
    await expect(page.getByTestId('wizard-slug')).not.toBeVisible({ timeout: 5000 })
    expect(capturedBody).toBeTruthy()
  })

  test('Closing wizard with X button hides wizard without navigating', async ({ page }) => {
    await openWizard(page)

    await page.getByRole('button', { name: '' }).filter({ hasText: '' }).first().click()
    // Fallback: find close button by its position (X icon in header)
    const closeBtn = page.locator('header button').filter({ has: page.locator('svg') })
    await closeBtn.click()

    await expect(page.getByTestId('wizard-slug')).not.toBeVisible()
  })
})
