import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

const threeProjects = [
  { id: 1, slug: 'default', displayName: 'Default Project', description: 'Auto-created',
    configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { id: 2, slug: 'mobile-app', displayName: 'Mobile App', description: 'iOS/Android',
    configDir: './config-mobile', createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
  { id: 3, slug: 'backend', displayName: 'Backend Platform', description: null,
    configDir: './config-backend', createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
]

test.describe('ProjectsSettingsPage', () => {
  test('ADMIN видит ссылку "Проекты" в системных настройках', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/system/users')
    await expect(page.getByRole('link', { name: 'Проекты' })).toBeVisible()
  })

  test('OPERATOR не видит ссылку системных настроек', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 2, username: 'op', displayName: 'Op', email: null, role: 'OPERATOR' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Системные настройки' })).toHaveCount(0)
  })

  test('рендерит список проектов с default-бейджем', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(threeProjects) })
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
      }
    })
    await page.goto('/system/projects')
    // "Default Project" также появляется в кнопке проект-свитчера — ограничиваемся list.
    const list = page.getByRole('list').first()
    await expect(list.getByText('Default Project')).toBeVisible()
    await expect(list.getByText('Mobile App')).toBeVisible()
    await expect(list.getByText('Backend Platform')).toBeVisible()
    await expect(list.getByText('default', { exact: true }).first()).toBeVisible()
  })

  test('удаление default-проекта заблокировано', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(threeProjects) })
    })
    await page.goto('/system/projects')
    const deleteDefault = page.getByRole('button', { name: 'Удалить default' })
    await expect(deleteDefault).toBeDisabled()
    const deleteMobile = page.getByRole('button', { name: 'Удалить mobile-app' })
    await expect(deleteMobile).toBeEnabled()
  })

  test('кнопка "Новый проект" открывает форму', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(threeProjects) })
    })
    await page.goto('/system/projects')
    await page.getByRole('button', { name: 'Новый проект' }).click()
    await expect(page.getByRole('heading', { name: 'Создать проект' })).toBeVisible()
    await expect(page.getByLabel('Slug (URL-friendly)')).toBeEnabled()
  })

  test('POST на создание проекта с корректным body', async ({ page }) => {
    await setupApiMocks(page)
    let captured: Record<string, unknown> | null = null
    await page.route('**/api/projects', async route => {
      if (route.request().method() === 'POST') {
        captured = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ id: 99, ...captured, createdAt: '2026-04-17T00:00:00Z', updatedAt: '2026-04-17T00:00:00Z' }),
        })
      } else {
        await route.fulfill({
          status: 200, contentType: 'application/json', body: JSON.stringify(threeProjects),
        })
      }
    })
    await page.goto('/system/projects')
    await page.getByRole('button', { name: 'Новый проект' }).click()
    await page.getByLabel('Slug (URL-friendly)').fill('new-team')
    await page.getByLabel('Название').fill('New Team Project')
    await page.getByRole('button', { name: 'Сохранить' }).click()
    await expect.poll(() => captured).not.toBeNull()
    expect(captured).toMatchObject({ slug: 'new-team', displayName: 'New Team Project' })
  })

  test('редактирование существующего проекта — slug disabled', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(threeProjects) })
    })
    await page.goto('/system/projects')
    await page.getByRole('button', { name: 'Редактировать mobile-app' }).click()
    await expect(page.getByRole('heading', { name: /Редактировать: Mobile App/ })).toBeVisible()
    await expect(page.getByLabel('Slug (URL-friendly)')).toBeDisabled()
    await expect(page.getByLabel('Название')).toHaveValue('Mobile App')
  })
})
