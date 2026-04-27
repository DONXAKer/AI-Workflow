import { test, expect, Page } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

/**
 * Full pipeline cycle: task file → analysis → clarification → plan → codegen → build_test → review → done.
 * Every step captures a named PNG under test-results/screenshots/orchestrator-cycle/.
 *
 * Uses API mocks — no backend required. Run with:
 *   npx playwright test orchestrator-full-cycle --project=chromium
 */

const SHOTS = 'test-results/screenshots/orchestrator-cycle'
const RUN_ID = 'cc111111-dddd-eeee-ffff-000000000001'
const CONFIG_PATH = '/projects/skill_marketplace/.ai-workflow/pipelines/pipeline.yaml'
const TASK_FILE = '/projects/skill_marketplace/tasks/active/SM-42_add-github-import.md'

const REQUIREMENT =
  'Добавить возможность импорта скилла из GitHub: пользователь вводит URL репозитория, ' +
  'система скачивает skill.json, проверяет структуру и запускает smoke-тест.'

const SM_PROJECT = {
  id: 2, slug: 'skill-marketplace', displayName: 'Skill Marketplace',
  description: 'Платформа для публикации и поиска AI-скиллов',
  configDir: '/projects/skill_marketplace',
  createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-04-20T00:00:00Z',
}

const PIPELINES = [
  {
    path: CONFIG_PATH,
    name: 'pipeline.yaml',
    pipelineName: 'skill-marketplace',
    description: 'AI-pipeline для разработки skill_marketplace',
  },
]

const ENTRY_POINTS = [
  {
    id: 'from_task_file', name: 'Задача из файла', fromBlock: 'task_md', requiresInput: 'task_file',
    inputFields: [{ name: 'task_file', label: 'Путь к файлу задачи', type: 'text', placeholder: '/path/to/task.md', required: true }],
  },
  {
    id: 'from_youtrack', name: 'Задача из YouTrack', fromBlock: 'youtrack_input', requiresInput: 'youtrack_issue',
    inputFields: [{ name: 'youtrackIssue', label: 'ID задачи YouTrack', type: 'text', placeholder: 'SM-42', required: true }],
  },
  {
    id: 'code_only', name: 'Только кодогенерация', fromBlock: 'codegen', requiresInput: 'requirement',
    inputFields: [{ name: 'requirement', label: 'Требование', type: 'textarea', placeholder: 'Опишите задачу...', required: true }],
  },
]

/** Pipeline config snapshot отражает наш новый pipeline с orchestrator plan+review */
const PIPELINE_SNAPSHOT = {
  name: 'skill-marketplace',
  pipeline: [
    { id: 'task_md',      block: 'task_md_input',   approval_mode: 'auto',        enabled: true },
    { id: 'analysis',     block: 'analysis',         approval_mode: 'manual',      enabled: true },
    { id: 'clarification',block: 'clarification',    approval_mode: 'manual',      enabled: true },
    { id: 'plan',         block: 'orchestrator',     approval_mode: 'manual',      enabled: true },
    { id: 'codegen',      block: 'agent_with_tools', approval_mode: 'manual',      enabled: true },
    { id: 'build_test',   block: 'shell_exec',       approval_mode: 'auto',        enabled: true },
    { id: 'review',       block: 'orchestrator',     approval_mode: 'auto',        enabled: true },
  ],
}

/** Outputs for each completed block */
const BLOCK_OUTPUTS = {
  task_md: {
    title: 'Импорт скилла из GitHub',
    goal: 'Добавить GitHub-импорт скилла в catalog',
    requirements: ['Пользователь вводит URL репозитория', 'Система скачивает skill.json', 'Проверяет структуру'],
    complexity_hint: 'medium',
  },
  analysis: {
    summary: 'Добавление GitHub-импорта скиллов в catalog-сервис',
    affected_components: ['CatalogController', 'SkillImportService', 'skills таблица'],
    technical_approach: 'Новый REST endpoint POST /api/catalog/import-from-github. GitHubClient скачивает skill.json, SkillValidator проверяет схему, CatalogService сохраняет.',
    estimated_complexity: 'medium',
    risks: ['Rate limiting GitHub API', 'Валидация манифеста может быть нестрогой'],
    open_questions: [],
  },
  clarification: {
    refined_requirement: 'POST /api/catalog/import-from-github принимает {url}, скачивает skill.json, валидирует по JSON Schema, запускает smoke-тест и возвращает созданный скилл.',
    approved_approach: 'Новый GitHubImportController + SkillManifestValidator + smoke-тест через существующий TestRunnerService.',
    clarifications: {},
  },
  plan: {
    mode: 'plan',
    goal: 'Реализовать POST /api/catalog/import-from-github с валидацией манифеста и smoke-тестом',
    files_to_touch: 'api/src/main/java/com/skillmarketplace/catalog/GitHubImportController.java\napi/src/main/java/com/skillmarketplace/catalog/SkillManifestValidator.java\napi/src/test/java/com/skillmarketplace/catalog/GitHubImportControllerTest.java',
    approach: '1. Создать GitHubImportController с POST /import-from-github\n2. GitHubClient.fetchManifest(url)\n3. SkillManifestValidator.validate(json)\n4. CatalogService.save(skill)\n5. Тест: MockWebServer + MockMvc',
    definition_of_done: '✓ Endpoint возвращает 201 при валидном манифесте\n✓ Возвращает 422 при невалидном манифесте\n✓ Тест покрывает happy path и невалидный манифест\n✓ OpenAPI аннотации на endpoint\n✓ Flyway миграция если схема изменилась',
    tools_to_use: 'Read, Edit, Write, Bash',
    iterations_used: 8,
    total_cost_usd: 0.31,
  },
  codegen: {
    files_changed: ['GitHubImportController.java', 'SkillManifestValidator.java', 'GitHubImportControllerTest.java'],
    summary: 'Создан endpoint импорта, валидатор манифеста и тест',
    iterations_used: 22,
    total_cost_usd: 1.14,
  },
  build_test: {
    exit_code: 0,
    output: '=== API build ===\n=== API tests (unit only) ===\nBUILD SUCCESSFUL in 47s\n=== Frontend type check ===\n✓ Compiled successfully\n=== DONE ===',
    duration_ms: 47_000,
  },
  review: {
    mode: 'review',
    passed: true,
    issues: '',
    action: 'continue',
    retry_instruction: '',
    carry_forward: 'GitHubImportController реализован с валидацией манифеста, тест покрывает happy path и 422. OpenAPI аннотации добавлены. Сборка зелёная.',
    iterations_used: 5,
    total_cost_usd: 0.22,
  },
}

/** Build a run object at a given pipeline state */
function runAt(
  currentBlock: string | null,
  completedBlocks: string[],
  status: 'RUNNING' | 'PAUSED_FOR_APPROVAL' | 'COMPLETED' | 'FAILED' = 'PAUSED_FOR_APPROVAL',
) {
  return makeRun({
    id: RUN_ID,
    pipelineName: 'skill-marketplace',
    requirement: REQUIREMENT,
    status,
    currentBlock,
    completedBlocks,
    configSnapshotJson: JSON.stringify(PIPELINE_SNAPSHOT),
    outputs: completedBlocks.map(blockId => ({
      blockId,
      outputJson: JSON.stringify(BLOCK_OUTPUTS[blockId as keyof typeof BLOCK_OUTPUTS] ?? {}),
    })),
  })
}

/** Register project + pipeline mocks on top of base mocks */
async function withProjectMocks(page: Page, run = runAt(null, [], 'RUNNING')) {
  await setupApiMocks(page, { run })
  await page.route('**/api/projects', async route => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([
        { id: 1, slug: 'default', displayName: 'Default Project', description: null, configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
        SM_PROJECT,
      ]),
    })
  })
  await page.route('**/api/projects/skill-marketplace', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SM_PROJECT) })
  })
  await page.route('**/api/pipelines', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PIPELINES) })
  })
  await page.route('**/api/pipelines/entry-points**', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ENTRY_POINTS) })
  })
  await page.route('**/api/runs', async route => {
    if (route.request().method() !== 'POST') { await route.fallback(); return }
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ id: RUN_ID, status: 'RUNNING' }),
    })
  })
}

/** Navigate to a run page. First visits / to establish auth in React state, then SPA-navigates
 *  via pushState + popstate so React auth context is NOT re-initialised. */
async function gotoRunPage(page: Page, runId: string) {
  await page.goto('/')
  await page.waitForLoadState('networkidle')
  // SPA navigation — avoids a full page reload that would race the auth mock
  await page.evaluate((id) => {
    window.history.pushState(null, '', `/runs/${id}`)
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
  }, runId)
  await page.waitForLoadState('networkidle')
}

// ─────────────────────────────────────────────────────────────────────────────

test.describe('Orchestrator pipeline — полный цикл от task.md до review', () => {
  test.describe.configure({ mode: 'serial' })

  // ── 00. Главная — список проектов ────────────────────────────────────────────
  test('00-projects-list', async ({ page }) => {
    await withProjectMocks(page)
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/00-projects-list.png`, fullPage: true })
    await expect(page.getByText('Skill Marketplace')).toBeVisible()
  })

  // ── 01. Страница проекта — выбор entry point ─────────────────────────────────
  test('01-project-entry-points', async ({ page }) => {
    await withProjectMocks(page)
    await page.goto('/projects/skill-marketplace/launch')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/01-project-entry-points.png`, fullPage: true })
    await expect(page.getByText('Задача из файла')).toBeVisible()
  })

  // ── 02. Форма запуска — from_task_file ──────────────────────────────────────
  test('02-launch-form-task-file', async ({ page }) => {
    await withProjectMocks(page)
    await page.goto('/projects/skill-marketplace/launch')
    await page.waitForLoadState('networkidle')

    // Выбираем entry point "Задача из файла"
    const ep = page.getByText('Задача из файла')
    if (await ep.isVisible()) await ep.click()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/02-launch-form-task-file.png`, fullPage: true })
  })

  // ── 03. Run запущен — task_md и analysis выполняются ────────────────────────
  test('03-run-started-task-md-running', async ({ page }) => {
    await withProjectMocks(page, runAt('task_md', [], 'RUNNING'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/03-run-started-task-md-running.png`, fullPage: true })
    await expect(page.getByText(RUN_ID.substring(0, 8), { exact: false }).first()).toBeVisible()
  })

  // ── 04. Analysis завершён — ожидание одобрения ───────────────────────────────
  test('04-analysis-awaiting-approval', async ({ page }) => {
    await withProjectMocks(page, runAt('analysis', ['task_md'], 'PAUSED_FOR_APPROVAL'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/04-analysis-awaiting-approval.png`, fullPage: true })
    await expect(page.getByRole('heading', { name: 'Требуется одобрение' })).toBeVisible()
    await expect(page.getByText('analysis', { exact: false }).first()).toBeVisible()
  })

  // ── 05. Одобрение analysis — просмотр вывода ────────────────────────────────
  test('05-analysis-output-expanded', async ({ page }) => {
    await withProjectMocks(page, runAt('analysis', ['task_md'], 'PAUSED_FOR_APPROVAL'))
    await gotoRunPage(page, RUN_ID)

    const dismissBtn = page.getByTitle('Dismiss (keep run paused)')
    if (await dismissBtn.isVisible()) await dismissBtn.click()

    const taskMdRow = page.getByText('task_md').first()
    if (await taskMdRow.isVisible()) await taskMdRow.click()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/05-analysis-output-expanded.png`, fullPage: true })
  })

  // ── 06. Одобрили analysis — clarification запущен ────────────────────────────
  test('06-clarification-awaiting-approval', async ({ page }) => {
    await withProjectMocks(page, runAt('clarification', ['task_md', 'analysis'], 'PAUSED_FOR_APPROVAL'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/06-clarification-awaiting-approval.png`, fullPage: true })
    await expect(page.getByRole('heading', { name: 'Требуется одобрение' })).toBeVisible()
  })

  // ── 07. Plan (orchestrator) запущен ─────────────────────────────────────────
  test('07-plan-running', async ({ page }) => {
    await withProjectMocks(page, runAt('plan', ['task_md', 'analysis', 'clarification'], 'RUNNING'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/07-plan-running.png`, fullPage: true })
  })

  // ── 08. Plan завершён — definition_of_done видны ─────────────────────────────
  test('08-plan-awaiting-approval', async ({ page }) => {
    await withProjectMocks(page, runAt('plan', ['task_md', 'analysis', 'clarification'], 'PAUSED_FOR_APPROVAL'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/08-plan-awaiting-approval.png`, fullPage: true })
    await expect(page.getByRole('heading', { name: 'Требуется одобрение' })).toBeVisible()
  })

  // ── 09. Codegen (agent_with_tools) выполняется — прогресс инструментов ──────
  test('09-codegen-running-tool-progress', async ({ page }) => {
    await withProjectMocks(page, runAt('codegen', ['task_md', 'analysis', 'clarification', 'plan'], 'RUNNING'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/09-codegen-running-tool-progress.png`, fullPage: true })
  })

  // ── 10. Codegen завершён — ожидание одобрения ────────────────────────────────
  test('10-codegen-awaiting-approval', async ({ page }) => {
    await withProjectMocks(page,
      runAt('codegen', ['task_md', 'analysis', 'clarification', 'plan'], 'PAUSED_FOR_APPROVAL'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/10-codegen-awaiting-approval.png`, fullPage: true })
    await expect(page.getByRole('heading', { name: 'Требуется одобрение' })).toBeVisible()

    let approvalBody: Record<string, unknown> | null = null
    await page.route('**/api/runs/*/approval', async route => {
      approvalBody = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"success":true}' })
    })
    await page.getByRole('button', { name: 'Одобрить' }).click()
    await expect.poll(() => approvalBody).not.toBeNull()
    expect(approvalBody).toMatchObject({ decision: 'APPROVE' })
  })

  // ── 11. Build & Test выполняется (auto) ──────────────────────────────────────
  test('11-build-test-running', async ({ page }) => {
    await withProjectMocks(page,
      runAt('build_test', ['task_md', 'analysis', 'clarification', 'plan', 'codegen'], 'RUNNING'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/11-build-test-running.png`, fullPage: true })
  })

  // ── 12. Build прошёл — Review (orchestrator) запущен ────────────────────────
  test('12-review-running', async ({ page }) => {
    await withProjectMocks(page,
      runAt('review', ['task_md', 'analysis', 'clarification', 'plan', 'codegen', 'build_test'], 'RUNNING'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/12-review-running.png`, fullPage: true })
  })

  // ── 13. Review passed — run завершён ────────────────────────────────────────
  test('13-run-completed', async ({ page }) => {
    const allBlocks = ['task_md', 'analysis', 'clarification', 'plan', 'codegen', 'build_test', 'review']
    await withProjectMocks(page, runAt(null, allBlocks, 'COMPLETED'))
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/13-run-completed.png`, fullPage: true })
    await expect(page.getByText('Завершён', { exact: false }).first()).toBeVisible()
  })

  // ── 14. Run summary — итоги прогона ────────────────────────────────────────
  test('14-run-summary-tab', async ({ page }) => {
    const allBlocks = ['task_md', 'analysis', 'clarification', 'plan', 'codegen', 'build_test', 'review']
    await withProjectMocks(page, runAt(null, allBlocks, 'COMPLETED'))
    await gotoRunPage(page, RUN_ID)

    const summaryTab = page.getByRole('tab', { name: /summary|итог/i })
    if (await summaryTab.isVisible()) await summaryTab.click()

    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/14-run-summary-tab.png`, fullPage: true })
  })

  // ── 15. Review failed — loopback к codegen ──────────────────────────────────
  test('15-review-failed-loopback', async ({ page }) => {
    const failedReviewOutput = {
      ...BLOCK_OUTPUTS.review,
      passed: false,
      action: 'retry',
      issues: 'Тест GitHubImportControllerTest не покрывает 422 ответ при невалидном манифесте. OpenAPI аннотация @ApiResponse для 422 отсутствует.',
      retry_instruction: 'Добавь тест для случая невалидного манифеста (ожидаемый HTTP 422). Добавь @ApiResponse(responseCode = "422") на endpoint.',
      carry_forward: 'GitHubImportController создан, happy path работает.',
    }
    const blocksBeforeReview = ['task_md', 'analysis', 'clarification', 'plan', 'codegen', 'build_test']
    const runWithFailedReview = makeRun({
      id: RUN_ID,
      pipelineName: 'skill-marketplace',
      requirement: REQUIREMENT,
      status: 'RUNNING',
      currentBlock: 'codegen', // loopback вернул на codegen
      completedBlocks: blocksBeforeReview,
      configSnapshotJson: JSON.stringify(PIPELINE_SNAPSHOT),
      loopHistoryJson: JSON.stringify([
        {
          fromBlock: 'review', targetBlock: 'codegen', iteration: 1,
          reason: failedReviewOutput.issues,
          timestamp: '2026-04-22T13:00:00Z',
        },
      ]),
      outputs: [
        ...blocksBeforeReview.map(blockId => ({
          blockId,
          outputJson: JSON.stringify(BLOCK_OUTPUTS[blockId as keyof typeof BLOCK_OUTPUTS] ?? {}),
        })),
        { blockId: 'review', outputJson: JSON.stringify(failedReviewOutput) },
      ],
    })

    await withProjectMocks(page, runWithFailedReview)
    await gotoRunPage(page, RUN_ID)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/15-review-failed-loopback.png`, fullPage: true })
  })
})
