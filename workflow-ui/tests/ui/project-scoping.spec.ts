import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

/**
 * Проверяет что API-клиент автоматически шлёт X-Project-Slug на scope-sensitive
 * endpoints и опускает на cross-project (auth, projects).
 */
test.describe('Project scoping — X-Project-Slug header', () => {
  test('по умолчанию шлёт default', async ({ page }) => {
    await setupApiMocks(page)
    const capturedSlugs: string[] = []
    await page.route('**/api/runs*', async route => {
      const slug = route.request().headers()['x-project-slug']
      if (slug) capturedSlugs.push(slug)
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 25 }),
      })
    })
    await page.goto('/runs/history')
    await expect.poll(() => capturedSlugs.length).toBeGreaterThan(0)
    expect(capturedSlugs[0]).toBe('default')
  })

  test('после выбора проекта шлёт соответствующий slug', async ({ page }) => {
    // Seed localStorage с новым slug до навигации.
    await page.addInitScript(() => {
      localStorage.setItem('workflow:currentProjectSlug', 'mobile-app')
    })
    await setupApiMocks(page)
    const capturedSlugs: string[] = []
    await page.route('**/api/cost/summary**', async route => {
      const slug = route.request().headers()['x-project-slug']
      if (slug) capturedSlugs.push(slug)
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          from: '2026-03-17T00:00:00Z', to: '2026-04-17T00:00:00Z',
          totalCostUsd: 0, totalCalls: 0, totalTokensIn: 0, totalTokensOut: 0, byModel: [],
        }),
      })
    })
    await page.goto('/cost')
    await expect.poll(() => capturedSlugs.length).toBeGreaterThan(0)
    expect(capturedSlugs[0]).toBe('mobile-app')
  })

  test('cross-project endpoints НЕ получают X-Project-Slug', async ({ page }) => {
    await setupApiMocks(page)
    const authHeaders: (string | undefined)[] = []
    const projectsHeaders: (string | undefined)[] = []
    await page.route('**/api/auth/me', async route => {
      authHeaders.push(route.request().headers()['x-project-slug'])
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 1, username: 'admin', displayName: 'Admin', email: null, role: 'ADMIN' }),
      })
    })
    await page.route('**/api/projects', async route => {
      projectsHeaders.push(route.request().headers()['x-project-slug'])
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify([{ id: 1, slug: 'default', displayName: 'Default', description: null,
          configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }]),
      })
    })
    await page.goto('/runs/history')
    // /api/auth/me всегда шлётся, /api/projects — в ProjectSwitcher
    await expect.poll(() => authHeaders.length).toBeGreaterThan(0)
    expect(authHeaders[0]).toBeUndefined()
    // projectsHeaders may or may not be captured depending on timing; if captured, must be undefined
    for (const h of projectsHeaders) expect(h).toBeUndefined()
  })
})
