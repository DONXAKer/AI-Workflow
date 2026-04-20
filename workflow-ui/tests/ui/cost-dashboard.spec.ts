import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

const sampleCost = {
  from: '2026-03-17T00:00:00Z',
  to: '2026-04-17T00:00:00Z',
  totalCostUsd: 12.3456,
  totalCalls: 142,
  totalTokensIn: 1_250_000,
  totalTokensOut: 850_000,
  byModel: [
    { model: 'anthropic/claude-opus-4-7', calls: 40, tokensIn: 800_000, tokensOut: 500_000, costUsd: 9.0 },
    { model: 'anthropic/claude-sonnet-4-6', calls: 80, tokensIn: 400_000, tokensOut: 300_000, costUsd: 3.0 },
    { model: 'openai/gpt-4o-mini', calls: 22, tokensIn: 50_000, tokensOut: 50_000, costUsd: 0.3456 },
  ],
}

test.describe('CostDashboardPage', () => {
  test('ссылка "Стоимость" видна в сайдбаре', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('link', { name: 'Стоимость' })).toBeVisible()
  })

  test('показывает total + breakdown по моделям', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/cost/summary**', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify(sampleCost),
      })
    })
    await page.goto('/cost')
    await expect(page.getByRole('heading', { name: 'Стоимость LLM' })).toBeVisible()
    await expect(page.getByText('$12.3456')).toBeVisible()
    await expect(page.locator('body')).toContainText('anthropic/claude-opus-4-7')
    await expect(page.locator('body')).toContainText('anthropic/claude-sonnet-4-6')
    await expect(page.locator('body')).toContainText('openai/gpt-4o-mini')
  })

  test('пустой результат показывает placeholder', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/cost')
    await expect(page.getByText('Нет вызовов за выбранный период')).toBeVisible()
  })

  test('смена дат пушит параметры в API', async ({ page }) => {
    await setupApiMocks(page)
    const params: string[] = []
    await page.route('**/api/cost/summary**', async route => {
      params.push(route.request().url())
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify(sampleCost),
      })
    })
    await page.goto('/cost')
    await page.getByLabel('От даты').fill('2026-01-01')
    await expect.poll(() => params.some(u => u.includes('from=2026-01-01'))).toBe(true)
  })
})
