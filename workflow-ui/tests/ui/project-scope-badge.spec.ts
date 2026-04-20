import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('ProjectScopeBadge', () => {
  test('виден на Cost dashboard с default scope', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/cost')
    const badge = page.getByTestId('project-scope-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('scope:')
    await expect(badge).toContainText('default')
  })

  test('показывает выбранный slug из localStorage', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('workflow:currentProjectSlug', 'mobile-app')
    })
    await setupApiMocks(page)
    await page.goto('/settings/audit')
    const badge = page.getByTestId('project-scope-badge')
    await expect(badge).toContainText('mobile-app')
  })

  test('виден на Audit, Cost и Kill Switch страницах', async ({ page }) => {
    await setupApiMocks(page)
    for (const url of ['/cost', '/settings/audit', '/settings/kill-switch']) {
      await page.goto(url)
      await expect(page.getByTestId('project-scope-badge')).toBeVisible()
    }
  })
})
