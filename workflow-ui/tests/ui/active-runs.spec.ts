import { test, expect } from '@playwright/test'
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

test.describe('ActiveRunsPage', () => {
  test('показывает пустое состояние когда нет активных запусков', async ({ page }) => {
    await setupApiMocks(page, { runs: [] })
    await page.goto('/runs/active')
    await expect(page.getByText('Нет активных запусков')).toBeVisible()
    await expect(page.getByText('Запустите пайплайн')).toBeVisible()
  })

  test('показывает RUNNING запуск в таблице', async ({ page }) => {
    await setupApiMocks(page, { runs: [RUNNING_RUN] })
    await page.goto('/runs/active')
    await expect(page.getByText('FEAT-001').first()).toBeVisible()
    await expect(page.getByText('impl').first()).toBeVisible()
    await expect(page.getByText('Подробнее')).toBeVisible()
  })

  test('PAUSED_FOR_APPROVAL показывает кнопку "Рассмотреть"', async ({ page }) => {
    await setupApiMocks(page, { runs: [PAUSED_RUN] })
    await page.goto('/runs/active')
    await expect(page.getByText('Рассмотреть')).toBeVisible()
    const row = page.locator('tr').filter({ hasText: 'Рассмотреть' })
    await expect(row).toHaveClass(/border-amber-500/)
  })

  test('"Рассмотреть" ведёт на страницу запуска', async ({ page }) => {
    await setupApiMocks(page, { runs: [PAUSED_RUN] })
    await page.goto('/runs/active')
    await page.getByText('Рассмотреть').click()
    await expect(page).toHaveURL(/\/runs\/bbbbbbbb/)
  })

  test('фильтр "Ожидают одобрения" скрывает RUNNING запуски', async ({ page }) => {
    await setupApiMocks(page, { runs: [RUNNING_RUN, PAUSED_RUN] })
    await page.goto('/runs/active')
    await page.getByRole('button', { name: 'Ожидают одобрения' }).click()
    await expect(page.getByText('Рассмотреть')).toBeVisible()
    await expect(page.getByText('Подробнее')).toHaveCount(0)
  })

  test('фильтр "Выполняются" скрывает PAUSED запуски', async ({ page }) => {
    await setupApiMocks(page, { runs: [RUNNING_RUN, PAUSED_RUN] })
    await page.goto('/runs/active')
    await page.getByRole('button', { name: 'Выполняются' }).click()
    await expect(page.getByText('Подробнее')).toBeVisible()
    await expect(page.getByText('Рассмотреть')).toHaveCount(0)
  })

  test('заголовок показывает количество запусков', async ({ page }) => {
    await setupApiMocks(page, { runs: [RUNNING_RUN, PAUSED_RUN] })
    await page.goto('/runs/active')
    await expect(page.getByText(/2 запуск/)).toBeVisible()
  })

  test('кнопка отмены присутствует для активных запусков', async ({ page }) => {
    await setupApiMocks(page, { runs: [RUNNING_RUN] })
    await page.goto('/runs/active')
    await expect(page.getByRole('button', { name: 'Отменить' })).toBeVisible()
  })
})
