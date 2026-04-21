import { test, expect, Page, Route } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

const RUNNING_RUN = makeRun({
  id: 'aaaaaaaa-0000-0000-0000-000000000001',
  status: 'RUNNING',
  currentBlock: 'impl',
  completedBlocks: ['task_md'],
  completedAt: null,
  requirement: '/project/tasks/active/FEAT-001_my-feature.md',
  pipelineName: 'feature',
})

const PAUSED_RUN = makeRun({
  id: 'bbbbbbbb-0000-0000-0000-000000000002',
  status: 'PAUSED_FOR_APPROVAL',
  currentBlock: 'impl',
  completedBlocks: ['task_md'],
  completedAt: null,
  requirement: '/project/tasks/active/FEAT-002_another.md',
  pipelineName: 'feature',
})

async function mockActiveRuns(page: Page, runs: typeof RUNNING_RUN[]) {
  await setupApiMocks(page)
  await page.route('**/api/runs**', async (route: Route) => {
    // Only intercept list calls (no UUID in path)
    const url = route.request().url()
    if (/\/api\/runs\/[a-f0-9-]{36}/.test(url)) { await route.fallback(); return }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: runs, totalElements: runs.length, totalPages: 1, page: 0, size: 100 }),
    })
  })
}

test.describe('ActiveRunsPage', () => {
  test('показывает пустое состояние когда нет активных запусков', async ({ page }) => {
    await mockActiveRuns(page, [])
    await page.goto('/active')
    await expect(page.getByText('Нет активных запусков')).toBeVisible()
    await expect(page.getByText('Запустите пайплайн')).toBeVisible()
  })

  test('показывает RUNNING запуск в таблице', async ({ page }) => {
    await mockActiveRuns(page, [RUNNING_RUN])
    await page.goto('/active')
    await expect(page.getByText('feature')).toBeVisible()
    await expect(page.getByText(/FEAT-001/)).toBeVisible()
    await expect(page.getByText('impl')).toBeVisible()
    await expect(page.getByText('Подробнее')).toBeVisible()
  })

  test('PAUSED_FOR_APPROVAL показывает кнопку "Рассмотреть"', async ({ page }) => {
    await mockActiveRuns(page, [PAUSED_RUN])
    await page.goto('/active')
    await expect(page.getByText('Рассмотреть')).toBeVisible()
    // Строка должна иметь amber-бордер
    const row = page.locator('tr').filter({ hasText: 'Рассмотреть' })
    await expect(row).toHaveClass(/border-amber-500/)
  })

  test('"Рассмотреть" ведёт на страницу запуска', async ({ page }) => {
    await mockActiveRuns(page, [PAUSED_RUN])
    await page.goto('/active')
    await page.getByText('Рассмотреть').click()
    await expect(page).toHaveURL(/\/runs\/bbbbbbbb/)
  })

  test('фильтр "Ожидают одобрения" скрывает RUNNING запуски', async ({ page }) => {
    await mockActiveRuns(page, [RUNNING_RUN, PAUSED_RUN])
    await page.goto('/active')
    await page.getByRole('button', { name: 'Ожидают одобрения' }).click()
    await expect(page.getByText('Рассмотреть')).toBeVisible()
    await expect(page.getByText('Подробнее')).toHaveCount(0)
  })

  test('фильтр "Выполняются" скрывает PAUSED запуски', async ({ page }) => {
    await mockActiveRuns(page, [RUNNING_RUN, PAUSED_RUN])
    await page.goto('/active')
    await page.getByRole('button', { name: 'Выполняются' }).click()
    await expect(page.getByText('Подробнее')).toBeVisible()
    await expect(page.getByText('Рассмотреть')).toHaveCount(0)
  })

  test('фильтр пайплайна работает через select', async ({ page }) => {
    await mockActiveRuns(page, [RUNNING_RUN, PAUSED_RUN])
    await page.goto('/active')
    await expect(page.locator('tbody tr')).toHaveCount(2)
    const select = page.locator('select')
    await select.selectOption('feature')
    await expect(page.locator('tbody tr')).toHaveCount(2)
  })

  test('кнопка отмены присутствует для активных запусков', async ({ page }) => {
    await mockActiveRuns(page, [RUNNING_RUN])
    await page.goto('/active')
    // CancelButton рендерит кнопку с role button и текстом/иконкой отмены
    const cancelBtn = page.locator('button[title*="Отмен"], button[aria-label*="Отмен"]').first()
    await expect(cancelBtn).toBeVisible()
  })

  test('заголовок показывает количество запусков', async ({ page }) => {
    await mockActiveRuns(page, [RUNNING_RUN, PAUSED_RUN])
    await page.goto('/active')
    await expect(page.getByText(/2 запуск/)).toBeVisible()
  })
})
