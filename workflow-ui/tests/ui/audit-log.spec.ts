import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

const sampleEntries = [
  {
    id: 1, timestamp: '2026-04-16T10:00:00Z', actor: 'admin', action: 'LOGIN',
    targetType: 'user', targetId: 'admin', outcome: 'SUCCESS', detailsJson: null, remoteAddr: '127.0.0.1',
  },
  {
    id: 2, timestamp: '2026-04-16T10:05:00Z', actor: 'op', action: 'RUN_START',
    targetType: 'run', targetId: 'abc', outcome: 'SUCCESS', detailsJson: '{"configPath":"x"}', remoteAddr: '10.0.0.1',
  },
  {
    id: 3, timestamp: '2026-04-16T10:10:00Z', actor: 'alice', action: 'LOGIN',
    targetType: 'user', targetId: 'alice', outcome: 'FAILURE', detailsJson: '{"reason":"BadCredentials"}', remoteAddr: '10.0.0.2',
  },
]

test.describe('AuditLogPage', () => {
  test('ADMIN видит ссылку на журнал в сайдбаре', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/system/users')
    await expect(page.getByRole('link', { name: 'Журнал' })).toBeVisible()
  })

  test('OPERATOR не видит ссылку на журнал', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 2, username: 'op', displayName: 'Op', email: null, role: 'OPERATOR' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Журнал' })).toHaveCount(0)
  })

  test('рендерит записи из API', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/audit**', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ content: sampleEntries, totalElements: 3, totalPages: 1, page: 0, size: 50 }),
      })
    })
    await page.goto('/system/audit')
    await expect(page.getByRole('heading', { name: 'Журнал действий' })).toBeVisible()
    await expect(page.locator('body')).toContainText('admin')
    await expect(page.locator('body')).toContainText('RUN_START')
    await expect(page.locator('body')).toContainText('FAILURE')
  })

  test('фильтр actor шлёт параметр в API', async ({ page }) => {
    await setupApiMocks(page)
    const actorParams: string[] = []
    await page.route('**/api/audit**', async route => {
      const url = new URL(route.request().url())
      const actor = url.searchParams.get('actor')
      if (actor) actorParams.push(actor)
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 50 }),
      })
    })
    await page.goto('/system/audit')
    await page.getByLabel('Фильтр: актор').fill('alice')
    await expect.poll(() => actorParams.length).toBeGreaterThan(0)
    expect(actorParams).toContain('alice')
  })

  test('пустой список показывает placeholder', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/system/audit')
    await expect(page.getByText('Нет записей, соответствующих фильтрам')).toBeVisible()
  })
})
