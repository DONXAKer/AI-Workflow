import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'
import { setupEditorMocks } from '../fixtures/pipeline-editor-fixtures'

/**
 * PR-3 — recommendedFor() pure function coverage via observable wizard UI.
 *
 * SAMPLE_REGISTRY (from pipeline-editor-fixtures) contains:
 *   INTAKE:     task_md_input   (rank 100)
 *   ANY:        shell_exec      (excluded from per-phase wizard)
 *   IMPLEMENT:  agent_with_tools (rank 100), orchestrator (rank 0)
 *   VERIFY:     verify           (rank 0 → alphabetical-first)
 *   ANALYZE/PUBLISH/RELEASE: no candidates
 */

async function openWizard(page: import('@playwright/test').Page) {
  await setupApiMocks(page)
  await setupEditorMocks(page)
  await page.route('**/api/pipelines/validate-body', async route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ valid: true, errors: [] }),
    }),
  )

  await page.goto('/projects/default/settings')
  await page.getByRole('button', { name: 'Пайплайн' }).click()
  await page.waitForSelector('[data-testid="pipeline-canvas"]')

  await page.getByTestId('pipeline-new').click()
  await page.waitForSelector('[data-testid="new-pipeline-template-custom"]')
  await page.getByTestId('new-pipeline-template-custom').click()
  await page.getByTestId('new-pipeline-submit').click()
  await page.waitForSelector('[data-testid="wizard-slug"]')
}

test.describe('recommendedFor() — wizard auto-seed behavior', () => {
  test('INTAKE phase auto-seeds task_md_input (rank=100, only INTAKE candidate)', async ({ page }) => {
    await openWizard(page)

    await expect(page.getByTestId('wizard-phase-step')).toBeVisible()
    // slot 0 should be auto-seeded with task_md_input
    await expect(page.getByTestId('wizard-block-slot-0')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).toContainText('task_md_input')
  })

  test('ANALYZE phase shows empty state — no ANALYZE candidates in fixture registry', async ({ page }) => {
    await openWizard(page)

    await page.getByTestId('wizard-next').click() // INTAKE → ANALYZE
    await expect(page.getByTestId('wizard-phase-step')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).not.toBeVisible()
    await expect(page.getByText('В этой фазе ещё нет блоков')).toBeVisible()
  })

  test('IMPLEMENT phase auto-seeds agent_with_tools (rank=100 beats orchestrator rank=0)', async ({ page }) => {
    await openWizard(page)

    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-next').click() // → IMPLEMENT
    await expect(page.getByTestId('wizard-phase-step')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).toContainText('agent_with_tools')
  })

  test('IMPLEMENT replace dropdown lists agent_with_tools before orchestrator (rank desc)', async ({ page }) => {
    await openWizard(page)

    await page.getByTestId('wizard-next').click() // → ANALYZE
    await page.getByTestId('wizard-next').click() // → IMPLEMENT

    // Open the replace dropdown on slot 0
    await page.getByTestId('wizard-replace-0').click()

    // agent_with_tools (★ rank=100) should be the first item
    const firstOption = page.locator('[data-testid="wizard-replace-0"] + div button').first()
    await expect(firstOption).toContainText('agent_with_tools')
  })

  test('VERIFY phase auto-seeds verify (only VERIFY candidate, rank=0 → alphabetical-first)', async ({ page }) => {
    await openWizard(page)

    // Navigate to VERIFY: INTAKE → ANALYZE → IMPLEMENT → VERIFY
    for (let i = 0; i < 3; i++) {
      await page.getByTestId('wizard-next').click()
      await expect(page.getByTestId('wizard-phase-step')).toBeVisible()
    }

    await expect(page.getByTestId('wizard-block-slot-0')).toBeVisible()
    await expect(page.getByTestId('wizard-block-slot-0')).toContainText('verify')
  })
})
