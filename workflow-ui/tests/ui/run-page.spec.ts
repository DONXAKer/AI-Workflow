import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

test.describe('RunPage — рендер истории запуска', () => {
  test('показывает requirement, блоки, статус для COMPLETED', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByText('Implement user authentication')).toBeVisible()
    await expect(page.locator('body')).toContainText('analysis')
    await expect(page.locator('body')).toContainText('codegen')
    await expect(page.locator('body')).toContainText('verify_code')
  })

  test('показывает баннер ошибки для FAILED запуска', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        status: 'FAILED',
        error: 'Block codegen failed: LLM returned invalid JSON',
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByText('Запуск завершился с ошибкой')).toBeVisible()
    await expect(page.getByText('Block codegen failed: LLM returned invalid JSON')).toBeVisible()
  })

  test('кнопка "Перезапуск" видна для исторических запусков', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('button', { name: 'Перезапуск' })).toBeVisible()
  })

  test('журнал событий скрыт для исторических запусков', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('tab', { name: 'Выходы блоков' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Журнал событий' })).toHaveCount(0)
    await expect(page.getByText('Журнал событий доступен только для активных запусков')).toBeVisible()
  })
})
