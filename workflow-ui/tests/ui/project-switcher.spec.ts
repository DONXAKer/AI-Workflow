import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

test.describe('ProjectSwitcher', () => {
  test('показывает проекты на главной странице', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/')
    await expect(page.getByText('Default Project')).toBeVisible()
    await expect(page.getByText('Выберите проект для работы')).toBeVisible()
  })

  test('открывает рабочее пространство при клике на проект', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/')
    await page.getByText('Default Project').click()
    await expect(page).toHaveURL(/\/projects\/default/)
    await expect(page.getByText('Default Project').first()).toBeVisible()
  })

  test('навигация к проекту устанавливает slug в localStorage', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/projects/default/launch')
    // Wait for workspace to fully render before reading localStorage
    await expect(page.getByText('Default Project').first()).toBeVisible()
    const stored = await page.evaluate(() => localStorage.getItem('workflow:currentProjectSlug'))
    expect(stored).toBe('default')
  })
})
