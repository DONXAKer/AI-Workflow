import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

const configSnapshot = JSON.stringify({
  name: 'example-pipeline',
  pipeline: [
    { id: 'analysis', block: 'analysis', approval_mode: 'manual', enabled: true },
    { id: 'codegen', block: 'code_generation', approval_mode: 'auto_notify', enabled: true },
    { id: 'verify_code', block: 'verify', approval_mode: 'auto', enabled: true },
    { id: 'ai_review', block: 'ai_review', approval_mode: 'auto_notify', enabled: false },
    { id: 'optional_docs', block: 'docs', approval_mode: 'auto', enabled: true, condition: "$.analysis.complexity != 'low'" },
  ],
})

test.describe('Block badges — approval_mode / enabled / condition', () => {
  test('рендерит manual/auto/auto+notify бейджи на блоках', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        configSnapshotJson: configSnapshot,
        completedBlocks: ['analysis', 'codegen', 'verify_code'],
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    const analysisRow = page.locator('tr', { hasText: 'analysis' }).first()
    await expect(analysisRow.getByText('manual', { exact: true })).toBeVisible()
    const codegenRow = page.locator('tr', { hasText: 'codegen' }).first()
    await expect(codegenRow.getByText('auto+notify')).toBeVisible()
    const verifyRow = page.locator('tr', { hasText: 'verify_code' }).first()
    await expect(verifyRow.getByText('auto', { exact: true })).toBeVisible()
  })

  test('disabled-бейдж виден для enabled=false', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        configSnapshotJson: configSnapshot,
        completedBlocks: ['ai_review'],
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    const aiReviewRow = page.locator('tr', { hasText: 'ai_review' }).first()
    await expect(aiReviewRow.getByText('disabled')).toBeVisible()
  })

  test('cond-бейдж виден если указан condition', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        configSnapshotJson: configSnapshot,
        completedBlocks: ['optional_docs'],
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    const row = page.locator('tr', { hasText: 'optional_docs' }).first()
    await expect(row.getByText('cond')).toBeVisible()
  })

  test('битый configSnapshotJson не ломает таблицу', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({ configSnapshotJson: 'invalid{json' }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByText('Implement user authentication')).toBeVisible()
  })
})
