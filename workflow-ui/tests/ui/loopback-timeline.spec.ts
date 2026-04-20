import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

const sampleHistory = [
  {
    timestamp: '2026-04-16T10:05:00Z',
    from_block: 'verify_code',
    to_block: 'codegen',
    iteration: 1,
    issues: ['Missing error handling in API call', 'No unit tests for edge cases'],
  },
  {
    timestamp: '2026-04-16T10:30:00Z',
    source: 'operator_return',
    to_block: 'analysis',
    iteration: 1,
    comment: 'Нужно учесть интеграцию с legacy-системой — она влияет на схему БД',
    issues: ['Учесть интеграцию с legacy-системой'],
  },
  {
    timestamp: '2026-04-16T11:00:00Z',
    from_block: 'ci',
    to_block: 'codegen',
    iteration: 2,
    issues: ['CI job failed: lint errors in 3 files'],
  },
]

test.describe('LoopbackTimeline', () => {
  test('вкладка "История итераций" видна на RunPage', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('tab', { name: 'История итераций' })).toBeVisible()
  })

  test('placeholder если итераций не было', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('tab', { name: 'История итераций' }).click()
    await expect(page.getByText(/прошёл линейно/i)).toBeVisible()
  })

  test('рендерит все записи истории', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({ loopHistoryJson: JSON.stringify(sampleHistory) }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('tab', { name: 'История итераций' }).click()
    await expect(page.getByText('История итераций (3)')).toBeVisible()
    await expect(page.locator('body')).toContainText('Missing error handling in API call')
    await expect(page.locator('body')).toContainText('Оператор')
    await expect(page.locator('body')).toContainText('Нужно учесть интеграцию с legacy-системой')
    await expect(page.locator('body')).toContainText('CI job failed: lint errors in 3 files')
  })

  test('различает источники по цвету/иконке', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({ loopHistoryJson: JSON.stringify(sampleHistory) }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('tab', { name: 'История итераций' }).click()
    // Badges для каждого source-типа
    await expect(page.getByText('Verify не прошёл').first()).toBeVisible()
    await expect(page.getByText('Оператор').first()).toBeVisible()
  })

  test('битый JSON не ломает страницу', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({ loopHistoryJson: 'not-valid-json{' }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('tab', { name: 'История итераций' }).click()
    await expect(page.getByText(/прошёл линейно/i)).toBeVisible()
  })
})
