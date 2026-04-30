import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'
import { setupEditorMocks, SAMPLE_FEATURE_CONFIG } from '../fixtures/pipeline-editor-fixtures'
import { PipelineConfigDto } from '../../src/types'

test.describe('Pipeline Editor — save', () => {
  test('editing a field shows dirty indicator and save submits the full config', async ({ page }) => {
    let saveBody: PipelineConfigDto | null = null
    await setupApiMocks(page)
    await setupEditorMocks(page, {
      onSaveBody: body => { saveBody = body },
    })

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    // Initially not dirty
    await expect(page.getByTestId('dirty-indicator')).toHaveCount(0)

    // Edit pipeline name
    const nameInput = page.getByTestId('pipeline-name')
    await nameInput.fill('feature-renamed')

    // Dirty indicator appears
    await expect(page.getByTestId('dirty-indicator')).toBeVisible()

    // Save
    await page.getByTestId('toolbar-save').click()

    // Wait for save to complete
    await expect.poll(() => saveBody).not.toBeNull()
    expect(saveBody!.name).toBe('feature-renamed')
    expect(saveBody!.pipeline?.length).toBe(SAMPLE_FEATURE_CONFIG.pipeline?.length)

    // Dirty cleared
    await expect(page.getByTestId('dirty-indicator')).toHaveCount(0)
    await expect(page.getByTestId('validated-clean')).toBeVisible()
  })

  test('save 400 with errors renders the same per-node decorations as Validate', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page, {
      saveValidationErrors: {
        valid: false,
        errors: [
          { code: 'MISSING_FIELD', message: 'block missing field', location: 'pipeline[0]', blockId: 'task_md' },
        ],
      },
    })

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    // Edit so save button becomes enabled
    await page.getByTestId('pipeline-name').fill('renamed')
    await page.getByTestId('toolbar-save').click()

    await expect(page.getByTestId('block-error-task_md')).toBeVisible()
    // Save button still shows dirty (unsuccessful save)
    await expect(page.getByTestId('dirty-indicator')).toBeVisible()
  })
})
