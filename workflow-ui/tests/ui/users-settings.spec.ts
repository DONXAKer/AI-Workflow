import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

const sampleUsers = [
  { id: 1, username: 'admin', displayName: 'Admin', email: 'admin@example.com',
    role: 'ADMIN', enabled: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { id: 2, username: 'alice', displayName: 'Alice Johnson', email: 'alice@example.com',
    role: 'RELEASE_MANAGER', enabled: true, createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
  { id: 3, username: 'bob', displayName: 'Bob Smith', email: 'bob@example.com',
    role: 'OPERATOR', enabled: true, createdAt: '2026-02-15T00:00:00Z', updatedAt: '2026-02-15T00:00:00Z' },
  { id: 4, username: 'carol', displayName: 'Carol', email: null,
    role: 'VIEWER', enabled: false, createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
]

test.describe('UsersSettingsPage', () => {
  test('ADMIN видит ссылку "Пользователи" в сайдбаре', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Пользователи' })).toBeVisible()
  })

  test('OPERATOR не видит ссылку "Пользователи"', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 2, username: 'op', displayName: 'Op', email: null, role: 'OPERATOR' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Пользователи' })).toHaveCount(0)
  })

  test('рендерит список пользователей с role-бейджами', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/users', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleUsers) })
    })
    await page.goto('/settings/users')
    const list = page.getByRole('list').first()
    await expect(list.getByText('Alice Johnson')).toBeVisible()
    await expect(list.getByText('Bob Smith')).toBeVisible()
    await expect(list.getByText('Release Manager')).toBeVisible()
    await expect(list.getByText('Operator')).toBeVisible()
    await expect(list.getByText('Viewer')).toBeVisible()
    await expect(list.getByText('disabled')).toBeVisible()
  })

  test('форма создания: submit disabled пока нет username и password ≥ 8', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/settings/users')
    await page.getByRole('button', { name: 'Новый пользователь' }).click()
    await expect(page.getByRole('button', { name: 'Сохранить' })).toBeDisabled()
    await page.getByLabel('Логин').fill('alice')
    await expect(page.getByRole('button', { name: 'Сохранить' })).toBeDisabled()
    await page.getByLabel('Пароль', { exact: false }).fill('short')
    await expect(page.getByRole('button', { name: 'Сохранить' })).toBeDisabled()
    await page.getByLabel('Пароль', { exact: false }).fill('longenough123')
    await expect(page.getByRole('button', { name: 'Сохранить' })).toBeEnabled()
  })

  test('POST с корректным body и ролью', async ({ page }) => {
    await setupApiMocks(page)
    let captured: Record<string, unknown> | null = null
    await page.route('**/api/users', async route => {
      if (route.request().method() === 'POST') {
        captured = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ id: 99, ...captured, enabled: true,
            createdAt: '2026-04-17T00:00:00Z', updatedAt: '2026-04-17T00:00:00Z' }),
        })
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleUsers) })
      }
    })
    await page.goto('/settings/users')
    await page.getByRole('button', { name: 'Новый пользователь' }).click()
    await page.getByLabel('Логин').fill('diana')
    await page.getByLabel('Имя').fill('Diana King')
    await page.getByLabel('Пароль', { exact: false }).fill('strongPassword!123')
    await page.getByLabel('Роль', { exact: true }).selectOption('OPERATOR')
    await page.getByRole('button', { name: 'Сохранить' }).click()
    await expect.poll(() => captured).not.toBeNull()
    expect(captured).toMatchObject({
      username: 'diana',
      displayName: 'Diana King',
      password: 'strongPassword!123',
      role: 'OPERATOR',
    })
  })

  test('редактирование: username disabled, active-checkbox виден', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/users', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleUsers) })
    })
    await page.goto('/settings/users')
    await page.getByRole('button', { name: 'Редактировать alice' }).click()
    await expect(page.getByRole('heading', { name: /Редактировать: alice/ })).toBeVisible()
    await expect(page.getByLabel('Логин')).toBeDisabled()
    await expect(page.getByLabel('Имя')).toHaveValue('Alice Johnson')
    await expect(page.getByLabel('Пользователь активен')).toBeChecked()
  })
})
