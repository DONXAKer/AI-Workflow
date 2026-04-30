import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'
import { setupEditorMocks } from '../fixtures/pipeline-editor-fixtures'

test.describe('Pipeline Editor — validation', () => {
  test('Validate button surfaces errors per node + count badge', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page, {
      validateResult: {
        valid: false,
        errors: [
          { code: 'DEPENDS_ON_UNKNOWN', message: 'unknown dep', location: 'pipeline[1].depends_on[0]', blockId: 'create_branch' },
          { code: 'REF_UNKNOWN_BLOCK', message: 'unknown ref ${ghost.x}', location: 'pipeline[2].config', blockId: 'impl' },
        ],
      },
    })

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    await page.getByTestId('toolbar-validate').click()
    // Per-node error badge
    await expect(page.getByTestId('block-error-create_branch')).toBeVisible()
    await expect(page.getByTestId('block-error-impl')).toBeVisible()
    // Count in toolbar
    await expect(page.locator('text=2 ошибок')).toBeVisible()
  })
})
