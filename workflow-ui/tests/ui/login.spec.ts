import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('LoginPage + auth redirect', () => {
  test('неаутентифицированный пользователь попадает на /login', async ({ page }) => {
    await setupApiMocks(page, { user: null })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible()
  })

  test('кнопка входа disabled при пустых полях', async ({ page }) => {
    await setupApiMocks(page, { user: null })
    await page.goto('/login')
    const submit = page.getByRole('button', { name: 'Войти' })
    await expect(submit).toBeDisabled()
    await page.getByLabel('Логин').fill('admin')
    await expect(submit).toBeDisabled()
    await page.getByLabel('Пароль').fill('secret')
    await expect(submit).toBeEnabled()
  })

  test('успешный логин переводит на исходный URL', async ({ page }) => {
    let authState: 'anonymous' | 'authenticated' = 'anonymous'
    // Register non-auth mocks first; auth mocks override them to drive state.
    await setupApiMocks(page, { user: null })
    await page.route('**/api/auth/me', async route => {
      if (authState === 'anonymous') {
        await route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"Not authenticated"}' })
      } else {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ id: 1, username: 'admin', displayName: 'Admin', email: null, role: 'ADMIN' }),
        })
      }
    })
    await page.route('**/api/auth/login', async route => {
      authState = 'authenticated'
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 1, username: 'admin', displayName: 'Admin', email: null, role: 'ADMIN' }),
      })
    })

    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page).toHaveURL(/\/login/)
    await page.getByLabel('Логин').fill('admin')
    await page.getByLabel('Пароль').fill('secret')
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page).toHaveURL(/\/runs\//)
  })

  test('401 от backend показывает ошибку', async ({ page }) => {
    await page.route('**/api/auth/me', async route => {
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"Not authenticated"}' })
    })
    await page.route('**/api/auth/login', async route => {
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"Invalid credentials"}' })
    })
    await page.goto('/login')
    await page.getByLabel('Логин').fill('admin')
    await page.getByLabel('Пароль').fill('wrong')
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page.getByText('Invalid credentials')).toBeVisible()
  })
})
