import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('Sidebar — роль-зависимый UI', () => {
  test('ADMIN видит настройки и user-footer', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 1, username: 'admin', displayName: 'Admin', email: null, role: 'ADMIN' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    // Settings секция видна только для ADMIN
    await expect(page.getByRole('link', { name: 'Интеграции' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Профили агентов' })).toBeVisible()
    // User-footer с логином, ролью и кнопкой logout
    await expect(page.getByText('Admin', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Выйти' })).toBeVisible()
  })

  test('OPERATOR не видит секцию "Настройки"', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 2, username: 'op', displayName: 'Operator', email: null, role: 'OPERATOR' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Интеграции' })).toHaveCount(0)
    await expect(page.getByRole('link', { name: 'Профили агентов' })).toHaveCount(0)
    // Но user-footer + logout остаются
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
