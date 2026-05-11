import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

const COMPLETED_RUN = makeRun({
  id: 'aaaaaaaa-0000-0000-0000-000000000001',
  status: 'COMPLETED',
  currentBlock: null,
  completedBlocks: ['task_md', 'impl', 'verify'],
  completedAt: '2026-04-15T10:30:00Z',
  requirement: '/project/tasks/active/FEAT-001_my-feature.md',
  pipelineName: 'feature',
})

test.describe('RunHistoryPage', () => {
  test('показывает пустое состояние когда нет запусков в истории', async ({ page }) => {
    await setupApiMocks(page, { runs: [] })
    await page.goto('/runs/history')
    await expect(page.getByText('Запусков пока нет')).toBeVisible()
  })

  test('показывает COMPLETED запуск в таблице', async ({ page }) => {
    await setupApiMocks(page, { runs: [COMPLETED_RUN] })
    await page.goto('/runs/history')
    await expect(page.getByText('FEAT-001')).toBeVisible()
    await expect(page.getByText('feature')).toBeVisible()
  })

  test('глобальная страница /runs/history отправляет allProjects=true', async ({ page }) => {
    let capturedUrl: string | null = null
    await page.route(/\/api\/runs(\?|$)/, async (route) => {
      capturedUrl = route.request().url()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [COMPLETED_RUN],
          totalElements: 1,
          totalPages: 1,
          page: 0,
          size: 25,
        }),
      })
    })
    await page.goto('/runs/history')
    await expect(page.getByText('FEAT-001')).toBeVisible()
    expect(capturedUrl).toContain('allProjects=true')
  })

  test('страница истории проекта не отправляет allProjects=true', async ({ page }) => {
    let capturedUrl: string | null = null
    await page.route(/\/api\/runs(\?|$)/, async (route) => {
      capturedUrl = route.request().url()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [COMPLETED_RUN],
          totalElements: 1,
          totalPages: 1,
          page: 0,
          size: 25,
        }),
      })
    })
    await page.goto('/projects/default/history')
    await expect(page.getByText('FEAT-001')).toBeVisible()
    expect(capturedUrl).not.toContain('allProjects=true')
  })
})
