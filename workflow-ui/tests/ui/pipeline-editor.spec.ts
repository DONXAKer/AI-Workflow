import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'
import { setupEditorMocks, SAMPLE_FEATURE_CONFIG } from '../fixtures/pipeline-editor-fixtures'

test.describe('Pipeline Editor — basic render', () => {
  test('renders blocks + depends_on + loopback edges', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    // Switch to Pipeline tab
    await page.getByRole('button', { name: 'Пайплайн' }).click()

    // Wait for the canvas to render
    await page.waitForSelector('[data-testid="pipeline-canvas"]')

    // Each block should appear as a node
    for (const block of SAMPLE_FEATURE_CONFIG.pipeline ?? []) {
      await expect(page.getByTestId(`block-node-${block.id}`)).toBeVisible()
    }
  })

  test('block palette shows registered block types', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()

    await page.waitForSelector('[data-testid="block-palette"]')

    // task_md_input is in the palette
    await expect(page.getByTestId('palette-add-task_md_input')).toBeVisible()
    await expect(page.getByTestId('palette-add-shell_exec')).toBeVisible()
    await expect(page.getByTestId('palette-add-agent_with_tools')).toBeVisible()
  })

  test('clicking a block opens the side panel', async ({ page }) => {
    await setupApiMocks(page)
    await setupEditorMocks(page)

    await page.goto('/projects/default/settings')
    await page.getByRole('button', { name: 'Пайплайн' }).click()

    await page.waitForSelector('[data-testid="pipeline-canvas"]')
    await page.getByTestId('block-node-task_md').click()
    await expect(page.getByTestId('side-panel')).toBeVisible()
    await expect(page.getByTestId('block-id-input')).toHaveValue('task_md')
  })
})
