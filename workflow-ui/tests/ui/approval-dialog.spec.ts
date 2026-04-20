import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

test.describe('ApprovalDialog — режимы одобрения блока', () => {
  test.beforeEach(async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        status: 'PAUSED_FOR_APPROVAL',
        currentBlock: 'codegen',
        completedBlocks: ['analysis'],
        completedAt: null,
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
  })

  test('диалог появляется автоматически для PAUSED_FOR_APPROVAL', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Требуется одобрение' })).toBeVisible()
    await expect(page.getByText(/Блок:/).first()).toBeVisible()
  })

  test('APPROVE отправляет decision=APPROVE', async ({ page }) => {
    let captured: Record<string, unknown> | null = null
    await page.route('**/api/runs/*/approval', async route => {
      captured = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"success":true}' })
    })
    await page.getByRole('button', { name: 'Одобрить' }).click()
    await expect.poll(() => captured).not.toBeNull()
    expect(captured).toMatchObject({ blockId: 'codegen', decision: 'APPROVE' })
  })

  test('edit-режим включается и показывает textarea', async ({ page }) => {
    await page.getByRole('button', { name: 'Редактировать' }).click()
    await expect(page.locator('textarea').first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Сохранить' })).toBeVisible()
  })

  test('невалидный JSON в edit-режиме блокирует сохранение', async ({ page }) => {
    await page.getByRole('button', { name: 'Редактировать' }).click()
    const textarea = page.locator('textarea').first()
    await textarea.fill('{ invalid json }')
    await expect(page.getByRole('button', { name: 'Сохранить' })).toBeDisabled()
    // Сам текст ошибки берётся из V8 и локализован браузером — проверяем наличие красного ринга.
    await expect(textarea).toHaveClass(/border-red-600/)
  })

  test('REJECT требует подтверждения (двухшаговый)', async ({ page }) => {
    await page.getByRole('button', { name: 'Отклонить' }).click()
    await expect(page.getByText('Это остановит запуск. Подтвердить?')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Подтвердить отказ' })).toBeVisible()
  })

  test('SKIP отправляет decision=SKIP', async ({ page }) => {
    let captured: Record<string, unknown> | null = null
    await page.route('**/api/runs/*/approval', async route => {
      captured = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"success":true}' })
    })
    await page.getByRole('button', { name: 'Пропустить' }).click()
    await expect.poll(() => captured).not.toBeNull()
    expect(captured).toMatchObject({ decision: 'SKIP' })
  })
})
