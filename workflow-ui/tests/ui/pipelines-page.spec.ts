import { test, expect, Route } from '@playwright/test'
import { setupApiMocks } from '../fixtures/api-mocks'

const FEATURE_PIPELINE = {
  path: '/project/.ai-workflow/pipelines/feature.yaml',
  name: 'feature.yaml',
  pipelineName: 'feature',
  description: 'task.md → agent → build → test → commit',
}

const ENTRY_POINT = {
  id: 'implement',
  name: 'Implement a task.md ticket',
  description: 'Parses task.md and implements the feature',
  inputFields: [
    { name: 'requirement', label: 'Путь к task.md', type: 'text', required: true, placeholder: '/project/tasks/active/FEAT-001_slug.md' },
  ],
}

async function setupPipelineMocks(page: Parameters<typeof setupApiMocks>[0]) {
  await setupApiMocks(page, { pipelines: [FEATURE_PIPELINE] })
  await page.route('**/api/pipelines/entry-points**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([ENTRY_POINT]),
    })
  })
}

test.describe('PipelinesPage', () => {
  test('показывает список доступных пайплайнов', async ({ page }) => {
    await setupPipelineMocks(page)
    await page.goto('/pipelines')
    // Pipeline name in the right-panel list
    await expect(page.locator('ul button').filter({ hasText: 'feature' }).first()).toBeVisible()
  })

  test('выбор пайплайна загружает точку входа', async ({ page }) => {
    await setupPipelineMocks(page)
    await page.goto('/pipelines')
    await expect(page.getByText('Implement a task.md ticket')).toBeVisible()
    await expect(page.getByPlaceholder('/project/tasks/active/FEAT-001_slug.md')).toBeVisible()
  })

  test('кнопка "Запустить" заблокирована при пустом обязательном поле', async ({ page }) => {
    await setupPipelineMocks(page)
    await page.goto('/pipelines')
    await expect(page.getByRole('button', { name: 'Запустить' })).toBeDisabled()
  })

  test('кнопка "Запустить" активна после заполнения требования', async ({ page }) => {
    await setupPipelineMocks(page)
    await page.goto('/pipelines')
    await page.getByPlaceholder('/project/tasks/active/FEAT-001_slug.md').fill('/project/tasks/active/FEAT-001_test.md')
    await expect(page.getByRole('button', { name: 'Запустить' })).toBeEnabled()
  })

  test('успешный запуск перенаправляет на страницу запуска', async ({ page }) => {
    await setupPipelineMocks(page)
    const newRunId = 'cccccccc-0000-0000-0000-000000000099'
    await page.route('**/api/runs', async (route: Route) => {
      if (route.request().method() !== 'POST') { await route.fallback(); return }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ id: newRunId, runId: newRunId, status: 'RUNNING' }),
      })
    })

    await page.goto('/pipelines')
    await page.getByPlaceholder('/project/tasks/active/FEAT-001_slug.md').fill('/project/tasks/active/FEAT-001_test.md')
    await page.getByRole('button', { name: 'Запустить' }).click()
    await expect(page).toHaveURL(new RegExp(newRunId))
  })

  test('dry-run чекбокс меняет кнопку на "Dry-run запуск"', async ({ page }) => {
    await setupPipelineMocks(page)
    await page.goto('/pipelines')
    await page.getByRole('checkbox').check()
    await expect(page.getByRole('button', { name: 'Dry-run запуск' })).toBeVisible()
  })

  test('ошибка POST показывает сообщение об ошибке', async ({ page }) => {
    await setupPipelineMocks(page)
    await page.route('**/api/runs', async (route: Route) => {
      if (route.request().method() !== 'POST') { await route.fallback(); return }
      await route.fulfill({ status: 500, body: 'Internal Server Error' })
    })

    await page.goto('/pipelines')
    await page.getByPlaceholder('/project/tasks/active/FEAT-001_slug.md').fill('/project/tasks/active/FEAT-001_test.md')
    await page.getByRole('button', { name: 'Запустить' }).click()
    await expect(page.getByText(/Не удалось запустить/)).toBeVisible()
  })

  test('клик по пайплайну в правой панели выбирает его в форме', async ({ page }) => {
    const secondPipeline = {
      path: '/project/.ai-workflow/pipelines/hotfix.yaml',
      name: 'hotfix.yaml',
      pipelineName: 'hotfix',
    }
    await setupApiMocks(page, { pipelines: [FEATURE_PIPELINE, secondPipeline] })
    await page.route('**/api/pipelines/entry-points**', async (route: Route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    })

    await page.goto('/pipelines')
    // Click the pipeline button in the right-panel list (not the select option)
    await page.locator('ul button').filter({ hasText: 'hotfix' }).click()
    const select = page.locator('select').first()
    await expect(select).toHaveValue(secondPipeline.path)
  })
})
