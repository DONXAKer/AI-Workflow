import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('Sidebar — роль-зависимый UI', () => {
  test('ADMIN видит настройки и кнопку выхода', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 1, username: 'admin', displayName: 'Admin', email: null, role: 'ADMIN' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    // Settings icon visible only for ADMIN
    await expect(page.getByRole('link', { name: 'Системные настройки' })).toBeVisible()
    // Logout button always visible
    await expect(page.getByRole('button', { name: 'Выйти' })).toBeVisible()
  })

  test('OPERATOR не видит ссылку настроек', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 2, username: 'op', displayName: 'Operator', email: null, role: 'OPERATOR' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Системные настройки' })).toHaveCount(0)
    // Logout button still visible
    await expect(page.getByRole('button', { name: 'Выйти' })).toBeVisible()
  })

  test('logout зовёт API и переводит на /login', async ({ page }) => {
    await setupApiMocks(page)
    // Playwright matches the LAST-registered route → register override after the setup.
    let logoutCalled = false
    await page.route('**/api/auth/logout', async route => {
      logoutCalled = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"success":true}' })
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Выйти' }).click()
    await expect(page).toHaveURL(/\/login/)
    expect(logoutCalled).toBe(true)
  })
})
