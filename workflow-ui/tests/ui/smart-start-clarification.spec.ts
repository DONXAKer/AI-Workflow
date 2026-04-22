import { test, expect } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

/**
 * SmartStart — уточнение при низкой уверенности (< 50%).
 *
 * Сценарий:
 *  1. Пользователь вводит расплывчатый текст.
 *  2. SmartDetect возвращает confidence 0.32 → кнопка "Запустить" блокируется
 *     (проверяем что launch-кнопка disabled когда нужно уточнение).
 *  3. Показывается amber-блок с уточняющим вопросом.
 *  4. Пользователь отвечает и нажимает "Уточнить".
 *  5. Повторный анализ возвращает confidence 0.87 → launch-кнопка активна.
 *  6. Пользователь запускает пайплайн.
 *
 * Скриншоты: test-results/screenshots/clarification/
 */

const SHOTS = 'test-results/screenshots/clarification'

const VAGUE_INPUT = 'надо что-то сделать со скиллами'

const CLARIFICATION_Q = 'Что именно нужно сделать со скиллами? ' +
  'Добавить новый скилл, удалить существующий, обновить каталог или что-то другое?'

const PIPELINES = [
  {
    path: '/projects/skill_marketplace/config/full-flow.yaml',
    name: 'full-flow.yaml',
    pipelineName: 'skill-marketplace-full-flow',
  },
]

const ENTRY_POINTS = [
  { id: 'from_raw_text',     name: 'Новая задача',      fromBlock: 'intake',   requiresInput: 'requirement' },
  { id: 'tasks_exist',       name: 'Задачи уже есть',   fromBlock: 'codegen',  requiresInput: null },
  { id: 'branch_exists',     name: 'Ветка уже создана', fromBlock: 'mr',       requiresInput: 'branch_name' },
]

async function setupSmartStartMocks(page: Parameters<typeof setupApiMocks>[0]) {
  await setupApiMocks(page)
  await page.route('**/api/projects', async route => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{
        id: 2, slug: 'skill-marketplace', displayName: 'Skill Marketplace',
        description: 'Платформа для публикации и поиска AI-скиллов',
        configDir: '/projects/skill_marketplace',
        createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-04-22T00:00:00Z',
      }]),
    })
  })
  await page.route('**/api/pipelines', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PIPELINES) })
  })
  await page.route('**/api/pipelines/entry-points**', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ENTRY_POINTS) })
  })
}

test.describe('SmartStart — уточнение при низкой уверенности', () => {
  test.describe.configure({ mode: 'serial' })

  // ── Шаг 1: вводим расплывчатый текст, анализируем ──────────────────────────
  test('01-vague-input-low-confidence', async ({ page }) => {
    await setupSmartStartMocks(page)

    let callCount = 0
    await page.route('**/api/runs/smart-detect', async route => {
      callCount++
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          suggested: { entryPointId: 'from_raw_text', confidence: 0.32, intentLabel: 'Неизвестное действие' },
          explanation: 'Текст слишком расплывчатый. Не удаётся однозначно определить тип задачи.',
          detectedInputs: {},
          clarificationQuestion: CLARIFICATION_Q,
        }),
      })
    })

    await page.goto('/projects/skill-marketplace/smart-start')
    await page.waitForLoadState('networkidle')

    // Вводим непонятный текст
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(VAGUE_INPUT)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/01-vague-text-entered.png`, fullPage: true })

    // Нажимаем анализировать
    await page.getByRole('button', { name: /анализировать/i }).click()
    await page.waitForTimeout(300)

    // Проверяем: уверенность 32% показана красным
    await expect(page.getByText('32%')).toBeVisible()

    // Проверяем: amber-блок с уточняющим вопросом виден
    await expect(page.getByText(CLARIFICATION_Q)).toBeVisible()

    // Проверяем: кнопка "Запустить" присутствует (видна пользователю)
    const launchBtn = page.getByRole('button', { name: /запустить/i })
    await expect(launchBtn).toBeVisible()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/01-low-confidence-result.png`, fullPage: true })
  })

  // ── Шаг 2: кнопка "Запустить" видна, но confidence красный — фиксируем состояние ─
  test('02-launch-button-state-at-low-confidence', async ({ page }) => {
    await setupSmartStartMocks(page)

    await page.route('**/api/runs/smart-detect', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          suggested: { entryPointId: 'from_raw_text', confidence: 0.32, intentLabel: 'Неизвестное действие' },
          explanation: 'Текст слишком расплывчатый.',
          detectedInputs: {},
          clarificationQuestion: CLARIFICATION_Q,
        }),
      })
    })

    await page.goto('/projects/skill-marketplace/smart-start')
    await page.waitForLoadState('networkidle')
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(VAGUE_INPUT)
    await page.getByRole('button', { name: /анализировать/i }).click()
    await page.waitForTimeout(300)

    // Кнопка "Уточнить" заблокирована пока поле ответа пусто
    const clarifyBtn = page.getByRole('button', { name: /уточнить/i })
    await expect(clarifyBtn).toBeVisible()
    await expect(clarifyBtn).toBeDisabled()

    // Уверенность отображается красным (< 60%)
    const confidenceEl = page.getByText('32%')
    await expect(confidenceEl).toBeVisible()
    await expect(confidenceEl).toHaveClass(/text-red-400/)

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/02-clarify-btn-disabled-empty-answer.png`, fullPage: true })
  })

  // ── Шаг 3: пользователь вводит ответ, кнопка "Уточнить" активируется ────────
  test('03-answer-typed-clarify-enabled', async ({ page }) => {
    await setupSmartStartMocks(page)

    await page.route('**/api/runs/smart-detect', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          suggested: { entryPointId: 'from_raw_text', confidence: 0.32, intentLabel: 'Неизвестное действие' },
          explanation: 'Текст слишком расплывчатый.',
          detectedInputs: {},
          clarificationQuestion: CLARIFICATION_Q,
        }),
      })
    })

    await page.goto('/projects/skill-marketplace/smart-start')
    await page.waitForLoadState('networkidle')
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(VAGUE_INPUT)
    await page.getByRole('button', { name: /анализировать/i }).click()
    await page.waitForTimeout(300)

    // Вводим ответ на уточнение
    const answerInput = page.getByPlaceholder(/Ваш ответ/i)
    await answerInput.fill('Нужно добавить возможность импортировать скилл из GitHub по URL репозитория')

    // Кнопка "Уточнить" становится активной
    const clarifyBtn = page.getByRole('button', { name: /уточнить/i })
    await expect(clarifyBtn).toBeEnabled()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/03-answer-typed-clarify-active.png`, fullPage: true })
  })

  // ── Шаг 4: нажимаем "Уточнить" — повторный анализ, confidence 87% ───────────
  test('04-after-clarification-high-confidence', async ({ page }) => {
    await setupSmartStartMocks(page)

    let callCount = 0
    await page.route('**/api/runs/smart-detect', async route => {
      callCount++
      if (callCount === 1) {
        // Первый вызов — низкая уверенность
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            suggested: { entryPointId: 'from_raw_text', confidence: 0.32, intentLabel: 'Неизвестное действие' },
            explanation: 'Текст слишком расплывчатый.',
            detectedInputs: {},
            clarificationQuestion: CLARIFICATION_Q,
          }),
        })
      } else {
        // Второй вызов (после уточнения) — высокая уверенность
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            suggested: { entryPointId: 'from_raw_text', confidence: 0.87, intentLabel: 'Новая задача' },
            explanation: 'После уточнения определено: импорт скилла из GitHub — новая функциональность. Рекомендую полный цикл от постановки задачи.',
            detectedInputs: { requirement: VAGUE_INPUT },
            clarificationQuestion: null,
          }),
        })
      }
    })

    await page.goto('/projects/skill-marketplace/smart-start')
    await page.waitForLoadState('networkidle')

    // Первый анализ (низкая уверенность)
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(VAGUE_INPUT)
    await page.getByRole('button', { name: /анализировать/i }).click()
    await page.waitForTimeout(300)
    await expect(page.getByText('32%')).toBeVisible()

    // Вводим уточнение
    await page.getByPlaceholder(/Ваш ответ/i).fill(
      'Нужно добавить возможность импортировать скилл из GitHub по URL репозитория'
    )
    await page.getByRole('button', { name: /уточнить/i }).click()
    await page.waitForTimeout(400)

    // После уточнения: уверенность 87% (amber ≥ 60%)
    await expect(page.getByText('87%')).toBeVisible()

    // Уточняющий вопрос исчез
    await expect(page.getByText(CLARIFICATION_Q)).not.toBeVisible()

    // Кнопка "Запустить" видна и не заблокирована по логике уточнения
    await expect(page.getByRole('button', { name: /запустить/i })).toBeVisible()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/04-after-clarification-high-confidence.png`, fullPage: true })

    // Проверяем что smartDetect был вызван дважды (исходный + уточнение)
    expect(callCount).toBe(2)
  })

  // ── Шаг 5: запуск пайплайна после уточнения ─────────────────────────────────
  test('05-launch-after-clarification', async ({ page }) => {
    const RUN_ID = 'cc111111-dddd-eeee-ffff-000000000001'
    await setupSmartStartMocks(page)

    let detectCount = 0
    await page.route('**/api/runs/smart-detect', async route => {
      detectCount++
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify(
          detectCount === 1
            ? {
                suggested: { entryPointId: 'from_raw_text', confidence: 0.32, intentLabel: 'Неизвестное действие' },
                explanation: 'Текст слишком расплывчатый.',
                detectedInputs: {},
                clarificationQuestion: CLARIFICATION_Q,
              }
            : {
                suggested: { entryPointId: 'from_raw_text', confidence: 0.87, intentLabel: 'Новая задача' },
                explanation: 'После уточнения: импорт скилла из GitHub — новая функциональность.',
                detectedInputs: { requirement: VAGUE_INPUT },
                clarificationQuestion: null,
              }
        ),
      })
    })

    await page.route('**/api/runs', async route => {
      if (route.request().method() !== 'POST') { await route.fallback(); return }
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: RUN_ID, runId: RUN_ID, status: 'RUNNING' }),
      })
    })

    await page.goto('/projects/skill-marketplace/smart-start')
    await page.waitForLoadState('networkidle')

    // Первый анализ
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(VAGUE_INPUT)
    await page.getByRole('button', { name: /анализировать/i }).click()
    await page.waitForTimeout(300)

    // Уточнение
    await page.getByPlaceholder(/Ваш ответ/i).fill(
      'Нужно добавить возможность импортировать скилл из GitHub по URL репозитория'
    )
    await page.getByRole('button', { name: /уточнить/i }).click()
    await page.waitForTimeout(400)

    // Проверяем высокую уверенность перед запуском
    await expect(page.getByText('87%')).toBeVisible()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/05-ready-to-launch.png`, fullPage: true })

    // Запускаем
    await page.getByRole('button', { name: /запустить/i }).click()

    // Должен перейти на страницу run
    await page.waitForURL(`**/runs/${RUN_ID}`, { timeout: 5000 })
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/05-run-started.png`, fullPage: true })
  })
})
