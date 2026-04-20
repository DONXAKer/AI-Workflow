import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

test.describe('ReturnDialog — возврат на доработку', () => {
  test('кнопка "Вернуть на доработку" видна на COMPLETED запуске', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('button', { name: 'Вернуть на доработку' })).toBeVisible()
  })

  test('кнопка не показывается на RUNNING запуске', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({ status: 'RUNNING', currentBlock: 'codegen', completedAt: null }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('button', { name: 'Вернуть на доработку' })).toHaveCount(0)
  })

  test('кнопка отключена если нет completedBlocks', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({ status: 'FAILED', completedBlocks: [], error: 'Failed early' }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await expect(page.getByRole('button', { name: 'Вернуть на доработку' })).toHaveCount(0)
  })

  test('открывает диалог и показывает список завершённых блоков', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Вернуть на доработку' }).click()
    await expect(page.getByRole('heading', { name: 'Вернуть на доработку' })).toBeVisible()
    const options = page.locator('select option')
    await expect(options).toHaveText(['analysis', 'codegen', 'verify_code'])
  })

  test('submit-кнопка disabled пока нет комментария', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Вернуть на доработку' }).click()
    const submit = page.getByRole('button', { name: 'Вернуть', exact: true })
    await expect(submit).toBeDisabled()
    await page.getByPlaceholder('Что нужно переделать и почему?').fill('Неправильная обработка ошибок')
    await expect(submit).toBeEnabled()
  })

  test('успешный submit отправляет правильный body', async ({ page }) => {
    let capturedBody: Record<string, unknown> | null = null
    await setupApiMocks(page, {
      onReturnSubmit: body => { capturedBody = body },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Вернуть на доработку' }).click()
    await page.locator('select').selectOption('codegen')
    await page.getByPlaceholder('Что нужно переделать и почему?').fill('Добавить try-catch вокруг вызова API')
    await page.getByRole('button', { name: 'Вернуть', exact: true }).click()
    await expect.poll(() => capturedBody).not.toBeNull()
    expect(capturedBody).toMatchObject({
      targetBlock: 'codegen',
      comment: 'Добавить try-catch вокруг вызова API',
      configPath: './config/pipeline.example.yaml',
    })
  })

  test('ошибка backend показывается в диалоге', async ({ page }) => {
    await setupApiMocks(page, { returnShouldFail: 'Target block was not completed' })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Вернуть на доработку' }).click()
    await page.getByPlaceholder('Что нужно переделать и почему?').fill('тест')
    await page.getByRole('button', { name: 'Вернуть', exact: true }).click()
    await expect(page.getByText('Target block was not completed')).toBeVisible()
  })

  test('кнопка "Отмена" закрывает диалог', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Вернуть на доработку' }).click()
    await expect(page.getByRole('heading', { name: 'Вернуть на доработку' })).toBeVisible()
    await page.getByRole('button', { name: 'Отмена' }).click()
    await expect(page.getByRole('heading', { name: 'Вернуть на доработку' })).toHaveCount(0)
  })
})
