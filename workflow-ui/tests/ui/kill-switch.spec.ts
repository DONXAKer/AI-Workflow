import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('KillSwitchPage', () => {
  test('показывает "не активен" при inactive state', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/settings/kill-switch')
    await expect(page.getByText('Kill switch не активен')).toBeVisible()
    await expect(page.getByRole('button', { name: /Активировать/ })).toBeVisible()
  })

  test('кнопка активации disabled без причины', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/settings/kill-switch')
    await expect(page.getByRole('button', { name: /Активировать/ })).toBeDisabled()
    await page.getByPlaceholder(/инцидент в проде/i).fill('test reason')
    await expect(page.getByRole('button', { name: /Активировать/ })).toBeEnabled()
  })

  test('активация шлёт POST с причиной', async ({ page }) => {
    let captured: Record<string, unknown> | null = null
    await setupApiMocks(page)
    await page.route('**/api/admin/kill-switch', async route => {
      const method = route.request().method()
      if (method === 'POST') {
        captured = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ active: true, reason: captured.reason, activatedBy: 'admin', activatedAt: '2026-04-16T12:00:00Z' }),
        })
      } else {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ active: false, reason: null, activatedBy: null, activatedAt: null }),
        })
      }
    })
    await page.goto('/settings/kill-switch')
    await page.getByPlaceholder(/инцидент в проде/i).fill('Prod incident #42')
    await page.getByRole('button', { name: /Активировать kill switch/ }).click()
    await expect.poll(() => captured).not.toBeNull()
    expect(captured).toMatchObject({ active: true, reason: 'Prod incident #42' })
    await expect(page.getByText('Kill switch АКТИВЕН')).toBeVisible()
  })

  test('активное состояние показывает причину + кнопку деактивации', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/admin/kill-switch', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          active: true,
          reason: 'Migration in progress',
          activatedBy: 'admin',
          activatedAt: '2026-04-16T12:00:00Z',
        }),
      })
    })
    await page.goto('/settings/kill-switch')
    await expect(page.getByText('Kill switch АКТИВЕН')).toBeVisible()
    await expect(page.getByText('Migration in progress')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Деактивировать' })).toBeVisible()
  })
})
