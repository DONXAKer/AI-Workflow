import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('ProjectSwitcher', () => {
  test('показывает текущий проект в сайдбаре', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('button', { name: 'Выбрать проект' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Выбрать проект' })).toContainText('Default Project')
  })

  test('открывает выпадающее меню с проектами', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify([
          { id: 1, slug: 'default', displayName: 'Default Project', description: null,
            configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
          { id: 2, slug: 'mobile-app', displayName: 'Mobile App', description: null,
            configDir: './config-mobile', createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
          { id: 3, slug: 'backend-platform', displayName: 'Backend Platform', description: null,
            configDir: './config-backend', createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
        ]),
      })
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Выбрать проект' }).click()
    await expect(page.getByRole('menu')).toBeVisible()
    await expect(page.locator('[role="menu"]')).toContainText('Default Project')
    await expect(page.locator('[role="menu"]')).toContainText('Mobile App')
    await expect(page.locator('[role="menu"]')).toContainText('Backend Platform')
  })

  test('выбор проекта сохраняется в localStorage', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify([
          { id: 1, slug: 'default', displayName: 'Default Project', description: null,
            configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
          { id: 2, slug: 'mobile-app', displayName: 'Mobile App', description: null,
            configDir: './config-mobile', createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
        ]),
      })
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Выбрать проект' }).click()
    await page.getByRole('menuitem', { name: /Mobile App/ }).click()
    const stored = await page.evaluate(() => localStorage.getItem('workflow:currentProjectSlug'))
    expect(stored).toBe('mobile-app')
    await expect(page.getByRole('button', { name: 'Выбрать проект' })).toContainText('Mobile App')
  })
})
