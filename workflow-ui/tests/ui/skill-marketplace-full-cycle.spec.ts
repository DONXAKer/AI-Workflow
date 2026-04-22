import { test, expect } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

/**
 * Full pipeline cycle screenshots: skill-marketplace / "GitHub skill import" feature.
 * From requirement intake → production deploy + release notes.
 *
 * Every test captures a named PNG to test-results/screenshots/skill-marketplace-cycle/
 * so each step of the workflow is documented visually.
 */

const SHOTS = 'test-results/screenshots/skill-marketplace-cycle'
const RUN_ID = 'aa000000-bbbb-cccc-dddd-eeeeeeeeeeee'

const REQUIREMENT =
  'Добавить возможность импорта скилла из GitHub: пользователь вводит URL репозитория, ' +
  'система скачивает манифест skill.json, проверяет структуру и версию, ' +
  'запускает smoke-тест и добавляет скилл в каталог с метаданными.'

/** Pipeline config snapshot — полный цикл (19 блоков) */
const PIPELINE_SNAPSHOT = {
  name: 'skill-marketplace-full-flow',
  pipeline: [
    { id: 'intake',        block: 'business_intake',  approval_mode: 'manual',      enabled: true },
    { id: 'analysis',      block: 'analysis',         approval_mode: 'auto_notify', enabled: true },
    { id: 'clarification', block: 'clarification',    approval_mode: 'manual',      enabled: true },
    { id: 'tasks',         block: 'youtrack_tasks',   approval_mode: 'manual',      enabled: true },
    { id: 'test_gen',      block: 'test_generation',  approval_mode: 'auto_notify', enabled: true },
    { id: 'codegen',       block: 'code_generation',  approval_mode: 'auto_notify', enabled: true },
    { id: 'verify_code',   block: 'verify',           approval_mode: 'auto',        enabled: true },
    { id: 'ai_review',     block: 'ai_review',        approval_mode: 'auto_notify', enabled: true },
    { id: 'mr',            block: 'gitlab_mr',        approval_mode: 'manual',      enabled: true },
    { id: 'ci',            block: 'gitlab_ci',        approval_mode: 'auto',        enabled: true },
    { id: 'merge',         block: 'vcs_merge',        approval_mode: 'manual',      enabled: true },
    { id: 'build',         block: 'build',            approval_mode: 'auto',        enabled: true },
    { id: 'deploy_test',   block: 'deploy',           approval_mode: 'auto',        enabled: true },
    { id: 'acceptance',    block: 'run_tests',        approval_mode: 'auto',        enabled: true },
    { id: 'deploy_staging',block: 'deploy',           approval_mode: 'auto_notify', enabled: true },
    { id: 'deploy_prod',   block: 'deploy',           approval_mode: 'manual',      enabled: true },
    { id: 'verify_prod',   block: 'verify_prod',      approval_mode: 'auto',        enabled: true },
    { id: 'rollback',      block: 'rollback',         approval_mode: 'manual',      enabled: true },
    { id: 'notes',         block: 'release_notes',    approval_mode: 'auto',        enabled: true },
  ],
}

const SM_PROJECT = {
  id: 2, slug: 'skill-marketplace', displayName: 'Skill Marketplace',
  description: 'Платформа для публикации и поиска AI-скиллов',
  configDir: '/projects/skill_marketplace',
  createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-04-20T00:00:00Z',
}

const PIPELINES = [
  { path: '/projects/skill_marketplace/config/full-flow.yaml', name: 'full-flow.yaml', pipelineName: 'skill-marketplace-full-flow', description: 'Full dev cycle' },
]

const ENTRY_POINTS = [
  { id: 'from_raw_text',     name: 'Новая задача',         fromBlock: 'intake',    requiresInput: 'requirement' },
  { id: 'from_tracker_issue',name: 'YouTrack issue',       fromBlock: 'analysis',  requiresInput: 'youtrack_issue' },
  { id: 'branch_exists',     name: 'Ветка уже создана',    fromBlock: 'mr',        requiresInput: 'branch_name' },
]

async function withProjectMocks(page: Parameters<typeof setupApiMocks>[0], setup: Parameters<typeof setupApiMocks>[1] = {}) {
  await setupApiMocks(page, setup)
  await page.route('**/api/projects', async route => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([
        { id: 1, slug: 'default', displayName: 'Default Project', description: null,
          configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
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
}

function runAt(currentBlock: string, completedBlocks: string[], status = 'PAUSED_FOR_APPROVAL', extra: Record<string, unknown> = {}) {
  return {
    id: RUN_ID,
    pipelineName: 'skill-marketplace-full-flow',
    requirement: REQUIREMENT,
    status,
    currentBlock,
    error: null,
    startedAt: '2026-04-22T08:00:00Z',
    completedAt: status === 'COMPLETED' ? '2026-04-22T14:30:00Z' : null,
    completedBlocks,
    autoApprove: [],
    loopHistoryJson: null,
    configSnapshotJson: JSON.stringify(PIPELINE_SNAPSHOT),
    ...extra,
  }
}

test.describe('Skill Marketplace — полный цикл от задачи до прода', () => {
  test.describe.configure({ mode: 'serial' })

  // ─── 00. Список проектов ────────────────────────────────────────────────────
  test('00-projects-list', async ({ page }) => {
    await withProjectMocks(page)
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/00-projects-list.png`, fullPage: true })
  })

  // ─── 01. Smart Start — ввод требования ──────────────────────────────────────
  test('01-smart-start-input', async ({ page }) => {
    await withProjectMocks(page)
    await page.route('**/api/runs/smart-detect', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          suggested: { entryPointId: 'from_raw_text', confidence: 0.94, intentLabel: 'Новая задача' },
          explanation: 'Обнаружено описание новой функциональности с техническими деталями. Рекомендую запустить полный цикл от постановки задачи.',
          detectedInputs: { requirement: REQUIREMENT },
          clarificationQuestion: null,
        }),
      })
    })
    await page.goto('/projects/skill-marketplace/smart-start')
    await page.waitForLoadState('networkidle')
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(REQUIREMENT)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/01-smart-start-input.png`, fullPage: true })
  })

  // ─── 02. Smart Detect — предложен entry point ────────────────────────────────
  test('02-smart-detect-result', async ({ page }) => {
    await withProjectMocks(page)
    await page.route('**/api/runs/smart-detect', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          suggested: { entryPointId: 'from_raw_text', confidence: 0.94, intentLabel: 'Новая задача' },
          explanation: 'Обнаружено описание новой функциональности с техническими деталями. Рекомендую запустить полный цикл от постановки задачи.',
          detectedInputs: { requirement: REQUIREMENT },
        }),
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
    await page.getByPlaceholder(/Вставьте ссылку/i).fill(REQUIREMENT)
    await page.getByRole('button', { name: /анализировать/i }).click()
    await page.waitForTimeout(400)
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/02-smart-detect-result.png`, fullPage: true })
  })

  // ─── 03. Run запущен — блок intake ожидает апрув ────────────────────────────
  test('03-run-intake-approval', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('intake', [], 'PAUSED_FOR_APPROVAL', {
        outputs: [{
          blockId: 'intake',
          outputJson: JSON.stringify({
            formalized_requirement: REQUIREMENT,
            business_value: 'Расширяет экосистему скиллов за счёт open-source репозиториев GitHub',
            scope: 'Новый endpoint + UI-кнопка "Импортировать из GitHub"',
            out_of_scope: 'Публикация в сторонние реестры (npm, PyPI)',
          }),
        }],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/03-run-intake-approval.png`, fullPage: true })
  })

  // ─── 04. Analysis завершён ──────────────────────────────────────────────────
  test('04-analysis-completed', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('clarification', ['intake', 'analysis'], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'intake', outputJson: JSON.stringify({ formalized_requirement: REQUIREMENT }) },
          {
            blockId: 'analysis',
            outputJson: JSON.stringify({
              summary: 'Новый модуль импорта скиллов из GitHub с верификацией манифеста и smoke-тестом.',
              affected_components: ['SkillCatalogService', 'GitHubClient', 'SkillValidator', 'CatalogController', 'ImportSkillModal'],
              technical_approach: 'REST endpoint POST /api/skills/import → GitHubClient.fetchManifest → SkillValidator.validate → CatalogService.save',
              estimated_complexity: 'medium',
              risks: ['Rate-limiting GitHub API (60 req/h без токена)', 'Вредоносный код в манифесте'],
            }),
          },
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/04-analysis-completed.png`, fullPage: true })
  })

  // ─── 05. Clarification — вопрос по задаче ───────────────────────────────────
  test('05-clarification-approval', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('clarification', ['intake', 'analysis'], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'analysis', outputJson: JSON.stringify({ estimated_complexity: 'medium' }) },
          {
            blockId: 'clarification',
            outputJson: JSON.stringify({
              questions: [
                { id: 'q1', question: 'Нужна ли поддержка private-репозиториев GitHub (с токеном)?', default: 'Нет, только публичные репозитории в MVP.' },
                { id: 'q2', question: 'Какой формат манифеста считать валидным (skill.json, package.json, оба)?', default: 'skill.json (приоритет), с fallback на package.json.' },
                { id: 'q3', question: 'Как обрабатывать дубликаты (одинаковый githubUrl + version)?', default: 'Возвращать 409 Conflict с ссылкой на существующий скилл.' },
              ],
              resolved: true,
            }),
          },
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/05-clarification-approval.png`, fullPage: true })
  })

  // ─── 06. Tasks — YouTrack subtasks готовы ───────────────────────────────────
  test('06-tasks-approval', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('tasks', ['intake', 'analysis', 'clarification'], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'analysis', outputJson: JSON.stringify({ estimated_complexity: 'medium' }) },
          {
            blockId: 'tasks',
            outputJson: JSON.stringify({
              created_tasks: [
                { id: 'SKL-42', summary: 'GitHubClient: fetchManifest + rate-limit retry', estimate: '3h' },
                { id: 'SKL-43', summary: 'SkillValidator: validate skill.json / package.json', estimate: '2h' },
                { id: 'SKL-44', summary: 'POST /api/skills/import endpoint + 409 handling', estimate: '3h' },
                { id: 'SKL-45', summary: 'ImportSkillModal: UI + error states', estimate: '4h' },
                { id: 'SKL-46', summary: 'Integration tests: GitHub mock + validator', estimate: '2h' },
              ],
              parent_issue: 'SKL-40',
              total_estimate: '14h',
            }),
          },
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/06-tasks-approval.png`, fullPage: true })
  })

  // ─── 07. Test Generation + Code Generation ──────────────────────────────────
  test('07-codegen-approval', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('codegen', ['intake', 'analysis', 'clarification', 'tasks', 'test_gen'], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'test_gen', outputJson: JSON.stringify({
            test_files: [
              { path: 'src/test/java/com/skillmarket/GitHubClientTest.java', tests: 4 },
              { path: 'src/test/java/com/skillmarket/SkillValidatorTest.java', tests: 7 },
              { path: 'src/test/java/com/skillmarket/ImportSkillIT.java', tests: 3 },
            ],
          })},
          { blockId: 'codegen', outputJson: JSON.stringify({
            branch_name: 'feature/SKL-40-github-skill-import',
            commit_message: 'feat(skills): add GitHub skill import with manifest validation',
            changes: [
              { path: 'src/main/java/com/skillmarket/GitHubClient.java',         action: 'create', lines: 87 },
              { path: 'src/main/java/com/skillmarket/SkillValidator.java',        action: 'create', lines: 64 },
              { path: 'src/main/java/com/skillmarket/CatalogController.java',     action: 'modify', lines: 23 },
              { path: 'src/main/java/com/skillmarket/SkillImportService.java',    action: 'create', lines: 112 },
              { path: 'src/main/resources/messages.properties',                   action: 'modify', lines: 5 },
              { path: 'frontend/src/components/ImportSkillModal.tsx',             action: 'create', lines: 156 },
              { path: 'frontend/src/pages/CatalogPage.tsx',                       action: 'modify', lines: 18 },
            ],
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/07-codegen-approval.png`, fullPage: true })
  })

  // ─── 08. Verify + loopback (первая итерация) ────────────────────────────────
  test('08-verify-loopback', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('codegen', ['intake', 'analysis', 'clarification', 'tasks', 'test_gen'], 'RUNNING', {
        loopHistoryJson: JSON.stringify([
          {
            timestamp: '2026-04-22T10:15:00Z',
            from_block: 'verify_code',
            to_block: 'codegen',
            iteration: 1,
            issues: [
              'GitHubClient не обрабатывает HTTP 403 (токен не передан) — выбрасывает NPE',
              'SkillValidator пропускает манифесты без поля `version`',
              'ImportSkillModal не показывает spinner во время загрузки',
            ],
          },
        ]),
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.getByRole('tab', { name: /история итераций/i }).click()
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/08-verify-loopback.png`, fullPage: true })
  })

  // ─── 09. AI Review approved ─────────────────────────────────────────────────
  test('09-ai-review-completed', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('mr', ['intake','analysis','clarification','tasks','test_gen','codegen','verify_code','ai_review'], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'verify_code', outputJson: JSON.stringify({ passed: true, issues: [], score: 8.5 }) },
          { blockId: 'ai_review', outputJson: JSON.stringify({
            verdict: 'approve',
            score: 9,
            summary: 'Код чистый, покрытие полное. GitHubClient корректно обрабатывает все HTTP-коды.',
            comments: [
              { file: 'GitHubClient.java', line: 45, severity: 'suggestion', text: 'Можно вынести retry-логику в отдельный утилитный метод.' },
            ],
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/09-ai-review-completed.png`, fullPage: true })
  })

  // ─── 10. MR создан, ожидает апрув ───────────────────────────────────────────
  test('10-mr-approval', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('mr', ['intake','analysis','clarification','tasks','test_gen','codegen','verify_code','ai_review'], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'mr', outputJson: JSON.stringify({
            mr_url: 'https://gitlab.example.com/skill-marketplace/-/merge_requests/87',
            mr_iid: 87,
            title: 'feat(skills): GitHub skill import SKL-40',
            source_branch: 'feature/SKL-40-github-skill-import',
            target_branch: 'main',
            reviewers: ['alice', 'bob'],
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/10-mr-approval.png`, fullPage: true })
  })

  // ─── 11. CI — пайплайн проходит ─────────────────────────────────────────────
  test('11-ci-running', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('ci', ['intake','analysis','clarification','tasks','test_gen','codegen','verify_code','ai_review','mr'], 'RUNNING', {
        outputs: [
          { blockId: 'ci', outputJson: JSON.stringify({
            pipeline_id: 4412,
            pipeline_url: 'https://gitlab.example.com/skill-marketplace/-/pipelines/4412',
            status: 'running',
            stages: [
              { name: 'build',    status: 'success', duration_s: 47 },
              { name: 'test',     status: 'success', duration_s: 93 },
              { name: 'lint',     status: 'running', duration_s: null },
              { name: 'security', status: 'pending', duration_s: null },
            ],
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/11-ci-running.png`, fullPage: true })
  })

  // ─── 12. Merge + Build ───────────────────────────────────────────────────────
  test('12-merge-and-build', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('build', ['intake','analysis','clarification','tasks','test_gen','codegen','verify_code','ai_review','mr','ci','merge'], 'RUNNING', {
        outputs: [
          { blockId: 'merge', outputJson: JSON.stringify({ strategy: 'squash', merge_commit: 'a3f9c12', merged_by: 'alice' }) },
          { blockId: 'build', outputJson: JSON.stringify({
            image: 'registry.example.com/skill-marketplace:0.14.0',
            digest: 'sha256:e3b0c4429…',
            build_duration_s: 134,
            status: 'building',
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/12-merge-and-build.png`, fullPage: true })
  })

  // ─── 13. Deploy test + Acceptance tests ─────────────────────────────────────
  test('13-acceptance-tests', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('deploy_staging', ['intake','analysis','clarification','tasks','test_gen','codegen','verify_code','ai_review','mr','ci','merge','build','deploy_test','acceptance'], 'RUNNING', {
        outputs: [
          { blockId: 'deploy_test', outputJson: JSON.stringify({
            env: 'test', url: 'https://test.skill-marketplace.example.com',
            strategy: 'rolling', replicas: 2, status: 'healthy',
          })},
          { blockId: 'acceptance', outputJson: JSON.stringify({
            passed: 14, failed: 0, skipped: 1,
            suite: 'smoke',
            tests: [
              { name: 'POST /api/skills/import — валидный GitHub URL', status: 'passed', ms: 312 },
              { name: 'POST /api/skills/import — невалидный манифест → 422', status: 'passed', ms: 89 },
              { name: 'POST /api/skills/import — дубликат → 409', status: 'passed', ms: 76 },
              { name: 'GET /api/catalog — скилл появился после импорта', status: 'passed', ms: 58 },
              { name: 'ImportSkillModal — happy path в UI', status: 'passed', ms: 1820 },
            ],
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/13-acceptance-tests.png`, fullPage: true })
  })

  // ─── 14. Deploy Prod — ручное подтверждение с gate-проверками ────────────────
  test('14-deploy-prod-approval', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('deploy_prod', [
        'intake','analysis','clarification','tasks','test_gen','codegen','verify_code',
        'ai_review','mr','ci','merge','build','deploy_test','acceptance','deploy_staging',
      ], 'PAUSED_FOR_APPROVAL', {
        outputs: [
          { blockId: 'deploy_staging', outputJson: JSON.stringify({
            env: 'staging', url: 'https://staging.skill-marketplace.example.com',
            strategy: 'rolling', replicas: 3, status: 'healthy',
          })},
          { blockId: 'deploy_prod', outputJson: JSON.stringify({
            required_gates: {
              staging_ok:  { passed: true,  label: 'Staging healthy' },
              smoke_ok:    { passed: true,  label: 'Acceptance tests passed (14/14)' },
              review_ok:   { passed: true,  label: 'AI Review approved (score 9/10)' },
            },
            env: 'production',
            strategy: 'blue-green',
            image: 'registry.example.com/skill-marketplace:0.14.0',
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/14-deploy-prod-approval.png`, fullPage: true })
  })

  // ─── 15. Verify Prod — health checks ────────────────────────────────────────
  test('15-verify-prod', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('verify_prod', [
        'intake','analysis','clarification','tasks','test_gen','codegen','verify_code',
        'ai_review','mr','ci','merge','build','deploy_test','acceptance','deploy_staging','deploy_prod',
      ], 'RUNNING', {
        outputs: [
          { blockId: 'deploy_prod', outputJson: JSON.stringify({ env: 'production', strategy: 'blue-green', status: 'deployed' }) },
          { blockId: 'verify_prod', outputJson: JSON.stringify({
            checks: [
              { name: 'HTTP /health → 200',                  status: 'pass', value: '200 OK / 23ms' },
              { name: 'HTTP /api/skills/import → 200',       status: 'pass', value: '200 OK / 87ms' },
              { name: 'Error rate < 1%',                     status: 'pass', value: '0.03%' },
              { name: 'P95 latency < 500ms',                 status: 'pass', value: '134ms' },
              { name: 'Observation window (5min)',            status: 'running', value: '2m 18s elapsed' },
            ],
          })},
        ],
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/15-verify-prod.png`, fullPage: true })
  })

  // ─── 16. Release Notes + Run COMPLETED ──────────────────────────────────────
  test('16-release-notes-completed', async ({ page }) => {
    await withProjectMocks(page, {
      run: runAt('notes', [
        'intake','analysis','clarification','tasks','test_gen','codegen','verify_code',
        'ai_review','mr','ci','merge','build','deploy_test','acceptance','deploy_staging',
        'deploy_prod','verify_prod','notes',
      ], 'COMPLETED', {
        outputs: [
          { blockId: 'verify_prod', outputJson: JSON.stringify({ all_checks_passed: true, error_rate: '0.03%', p95_latency_ms: 134 }) },
          {
            blockId: 'notes',
            outputJson: JSON.stringify({
              version: '0.14.0',
              release_date: '2026-04-22',
              title: '🚀 Skill Marketplace v0.14.0 — Импорт скиллов из GitHub',
              summary: 'В этом релизе добавлена возможность импортировать скиллы напрямую из публичных GitHub-репозиториев.',
              highlights: [
                'Новый endpoint `POST /api/skills/import` принимает GitHub URL',
                'Автоматическая валидация манифеста `skill.json`',
                'Smoke-тест скилла перед добавлением в каталог',
                '409 Conflict при попытке импортировать дубликат',
                'Новая кнопка "Импортировать из GitHub" в каталоге',
              ],
              commits: 8,
              files_changed: 7,
              tasks_closed: ['SKL-40', 'SKL-42', 'SKL-43', 'SKL-44', 'SKL-45', 'SKL-46'],
              posted_to: ['#releases', 'YouTrack SKL-40'],
            }),
          },
        ],
        loopHistoryJson: JSON.stringify([
          {
            timestamp: '2026-04-22T10:15:00Z',
            from_block: 'verify_code', to_block: 'codegen', iteration: 1,
            issues: ['NPE в GitHubClient при HTTP 403', 'SkillValidator пропускает отсутствующий `version`'],
          },
        ]),
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/16-release-notes-completed.png`, fullPage: true })
  })

  // ─── 17. Loopback timeline — история итераций ────────────────────────────────
  test('17-loopback-timeline', async ({ page }) => {
    await withProjectMocks(page, {
      run: makeRun({
        id: RUN_ID,
        pipelineName: 'skill-marketplace-full-flow',
        requirement: REQUIREMENT,
        status: 'COMPLETED',
        completedBlocks: ['intake','analysis','clarification','tasks','test_gen','codegen','verify_code','ai_review','mr','ci','merge','build','deploy_test','acceptance','deploy_staging','deploy_prod','verify_prod','notes'],
        loopHistoryJson: JSON.stringify([
          {
            timestamp: '2026-04-22T10:15:00Z',
            from_block: 'verify_code', to_block: 'codegen', iteration: 1,
            issues: [
              'GitHubClient не обрабатывает HTTP 403 — выбрасывает NPE',
              'SkillValidator пропускает манифесты без поля `version`',
              'ImportSkillModal не показывает spinner при загрузке',
            ],
          },
          {
            timestamp: '2026-04-22T11:40:00Z',
            source: 'operator_return', to_block: 'analysis', iteration: 1,
            comment: 'Добавьте в анализ раздел о rate-limiting GitHub API — нужно выбрать стратегию: кэш или очередь.',
            issues: ['Анализ стратегии rate-limit для GitHub API'],
          },
        ]),
      }),
    })
    await page.goto(`/runs/${RUN_ID}`)
    await page.waitForLoadState('networkidle')
    await page.getByRole('tab', { name: /история итераций/i }).click()
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/17-loopback-timeline.png`, fullPage: true })
  })
})
