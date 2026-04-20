import { test } from '@playwright/test'
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

/**
 * Documentation snapshots. Each test captures a named full-page PNG into
 * {@code test-results/screenshots/} so PR reviewers can see how the UI renders for
 * the feature in question without running the app locally. Filenames are stable
 * so that diff tooling can compare across branches.
 */

const SHOTS = 'test-results/screenshots'

test.describe('Documentation screenshots', () => {
  test.describe.configure({ mode: 'serial' })

  test('01-login-page', async ({ page }) => {
    await setupApiMocks(page, { user: null })
    await page.goto('/login')
    await page.getByLabel('Логин').fill('admin')
    await page.getByLabel('Пароль').fill('••••••••')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/01-login-page.png`, fullPage: true })
  })

  test('02-run-page-completed', async ({ page }) => {
    await setupApiMocks(page, {
      run: {
        id: '11111111-2222-3333-4444-555555555555',
        pipelineName: 'example-pipeline',
        requirement: 'Implement user authentication',
        status: 'COMPLETED',
        currentBlock: null,
        error: null,
        startedAt: '2026-04-15T10:00:00Z',
        completedAt: '2026-04-15T10:30:00Z',
        completedBlocks: ['analysis', 'codegen', 'verify_code', 'ai_review'],
        autoApprove: [],
        outputs: [
          { blockId: 'analysis', outputJson: JSON.stringify({ summary: 'Auth system analysis', estimated_complexity: 'medium' }) },
          { blockId: 'codegen', outputJson: JSON.stringify({ changes: [{ path: 'auth.ts' }], commit_message: 'add auth' }) },
          { blockId: 'verify_code', outputJson: JSON.stringify({ passed: true, issues: [] }) },
          { blockId: 'ai_review', outputJson: JSON.stringify({ verdict: 'approve', score: 9 }) },
        ],
        configSnapshotJson: JSON.stringify({
          name: 'example-pipeline',
          pipeline: [
            { id: 'analysis', block: 'analysis', approval_mode: 'manual', enabled: true },
            { id: 'codegen', block: 'code_generation', approval_mode: 'auto_notify', enabled: true },
            { id: 'verify_code', block: 'verify', approval_mode: 'auto', enabled: true },
            { id: 'ai_review', block: 'ai_review', approval_mode: 'auto_notify', enabled: true },
          ],
        }),
      },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/02-run-page-completed.png`, fullPage: true })
  })

  test('03-run-page-failed', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        status: 'FAILED',
        error: 'Block codegen failed: LLM returned invalid JSON',
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/03-run-page-failed.png`, fullPage: true })
  })

  test('04-return-dialog', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Вернуть на доработку' }).click()
    await page.locator('select').selectOption('codegen')
    await page.getByPlaceholder('Что нужно переделать и почему?').fill(
      'Добавить обработку ошибок в блоке вызова внешнего API и покрыть тестами негативные сценарии.'
    )
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/04-return-dialog.png`, fullPage: true })
  })

  test('05-approval-dialog', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        status: 'PAUSED_FOR_APPROVAL',
        currentBlock: 'codegen',
        completedBlocks: ['analysis'],
        completedAt: null,
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/05-approval-dialog.png`, fullPage: true })
  })

  test('06-approval-dialog-edit', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        status: 'PAUSED_FOR_APPROVAL',
        currentBlock: 'codegen',
        completedBlocks: ['analysis'],
        completedAt: null,
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Редактировать' }).click()
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/06-approval-dialog-edit.png`, fullPage: true })
  })

  test('07-sidebar-admin', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.locator('aside').first().screenshot({ animations: 'disabled', path: `${SHOTS}/07-sidebar-admin.png` })
  })

  test('08-sidebar-operator', async ({ page }) => {
    await setupApiMocks(page, {
      user: { id: 2, username: 'op', displayName: 'Operator', email: null, role: 'OPERATOR' },
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.locator('aside').first().screenshot({ animations: 'disabled', path: `${SHOTS}/08-sidebar-operator.png` })
  })

  test('09-audit-log', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/audit**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [
            { id: 1, timestamp: '2026-04-16T10:00:00Z', actor: 'admin', action: 'LOGIN',
              targetType: 'user', targetId: 'admin', outcome: 'SUCCESS', detailsJson: null, remoteAddr: '127.0.0.1' },
            { id: 2, timestamp: '2026-04-16T10:05:00Z', actor: 'op', action: 'RUN_START',
              targetType: 'run', targetId: 'abc-123', outcome: 'SUCCESS', detailsJson: '{"configPath":"./config/pipeline.yaml"}', remoteAddr: '10.0.0.1' },
            { id: 3, timestamp: '2026-04-16T10:07:00Z', actor: 'op', action: 'APPROVAL_RESOLVE',
              targetType: 'run', targetId: 'abc-123', outcome: 'SUCCESS', detailsJson: '{"decision":"APPROVE"}', remoteAddr: '10.0.0.1' },
            { id: 4, timestamp: '2026-04-16T10:10:00Z', actor: 'alice', action: 'LOGIN',
              targetType: 'user', targetId: 'alice', outcome: 'FAILURE', detailsJson: '{"reason":"BadCredentials"}', remoteAddr: '10.0.0.2' },
            { id: 5, timestamp: '2026-04-16T10:15:00Z', actor: 'admin', action: 'KILL_SWITCH_ACTIVATE',
              targetType: 'system', targetId: 'kill-switch', outcome: 'SUCCESS', detailsJson: '{"reason":"deploy freeze"}', remoteAddr: '127.0.0.1' },
          ],
          totalElements: 5, totalPages: 1, page: 0, size: 50,
        }),
      })
    })
    await page.goto('/settings/audit')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/09-audit-log.png`, fullPage: true })
  })

  test('10-kill-switch-inactive', async ({ page }) => {
    await setupApiMocks(page)
    await page.goto('/settings/kill-switch')
    await page.getByPlaceholder(/инцидент в проде/i).fill('Заморозка на время миграции БД')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/10-kill-switch-inactive.png`, fullPage: true })
  })

  test('11-kill-switch-active', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/admin/kill-switch', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          active: true,
          reason: 'Прод-инцидент — расследование в процессе',
          activatedBy: 'admin',
          activatedAt: '2026-04-16T12:00:00Z',
        }),
      })
    })
    await page.goto('/settings/kill-switch')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/11-kill-switch-active.png`, fullPage: true })
  })

  test('12-cost-dashboard', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/cost/summary**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          from: '2026-03-17T00:00:00Z',
          to: '2026-04-17T00:00:00Z',
          totalCostUsd: 47.8234,
          totalCalls: 523,
          totalTokensIn: 4_820_000,
          totalTokensOut: 2_340_000,
          byModel: [
            { model: 'anthropic/claude-opus-4-7', calls: 120, tokensIn: 2_800_000, tokensOut: 1_400_000, costUsd: 32.5 },
            { model: 'anthropic/claude-sonnet-4-6', calls: 290, tokensIn: 1_600_000, tokensOut: 800_000, costUsd: 11.8 },
            { model: 'anthropic/claude-haiku-4-5', calls: 85, tokensIn: 350_000, tokensOut: 120_000, costUsd: 2.4 },
            { model: 'openai/gpt-4o-mini', calls: 28, tokensIn: 70_000, tokensOut: 20_000, costUsd: 1.1234 },
          ],
        }),
      })
    })
    await page.goto('/cost')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/12-cost-dashboard.png`, fullPage: true })
  })

  test('14-loopback-timeline', async ({ page }) => {
    await setupApiMocks(page, {
      run: makeRun({
        loopHistoryJson: JSON.stringify([
          {
            timestamp: '2026-04-16T10:05:00Z',
            from_block: 'verify_code',
            to_block: 'codegen',
            iteration: 1,
            issues: [
              'В CodeGen отсутствует обработка ошибок вызова внешнего API',
              'Нет unit-тестов на граничные случаи',
            ],
          },
          {
            timestamp: '2026-04-16T10:30:00Z',
            source: 'operator_return',
            to_block: 'analysis',
            iteration: 1,
            comment: 'Нужно учесть интеграцию с legacy-системой — она влияет на схему БД и требует миграции данных.',
            issues: ['Учесть legacy-интеграцию', 'Спланировать data-миграцию'],
          },
          {
            timestamp: '2026-04-16T11:00:00Z',
            from_block: 'ci',
            to_block: 'codegen',
            iteration: 2,
            issues: ['CI job failed: lint errors в 3 файлах', 'Unit-тесты упали на новой схеме БД'],
          },
        ]),
      }),
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('tab', { name: 'История итераций' }).click()
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/14-loopback-timeline.png`, fullPage: true })
  })

  test('15-projects-settings', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify([
          { id: 1, slug: 'default', displayName: 'Default Project',
            description: 'Auto-created on first startup. Rename or add more.',
            configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
          { id: 2, slug: 'mobile-app', displayName: 'Mobile App',
            description: 'iOS + Android flows для команды мобильной разработки',
            configDir: './config-mobile', createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
          { id: 3, slug: 'backend-platform', displayName: 'Backend Platform',
            description: 'Сервисы платформенной команды — API gateway, auth, billing',
            configDir: './config-backend', createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
          { id: 4, slug: 'data-pipeline', displayName: 'Data Pipeline',
            description: 'ETL и ML модели для аналитической платформы',
            configDir: './config-data', createdAt: '2026-04-01T00:00:00Z', updatedAt: '2026-04-01T00:00:00Z' },
        ]),
      })
    })
    await page.goto('/settings/projects')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/15-projects-settings.png`, fullPage: true })
  })

  test('16-users-settings', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/users', async route => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify([
          { id: 1, username: 'admin', displayName: 'Admin', email: 'admin@example.com',
            role: 'ADMIN', enabled: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
          { id: 2, username: 'alice', displayName: 'Alice Johnson', email: 'alice@example.com',
            role: 'RELEASE_MANAGER', enabled: true, createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
          { id: 3, username: 'bob', displayName: 'Bob Smith', email: 'bob@example.com',
            role: 'OPERATOR', enabled: true, createdAt: '2026-02-15T00:00:00Z', updatedAt: '2026-02-15T00:00:00Z' },
          { id: 4, username: 'diana', displayName: 'Diana King', email: 'diana@example.com',
            role: 'OPERATOR', enabled: true, createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
          { id: 5, username: 'carol', displayName: 'Carol Lee', email: null,
            role: 'VIEWER', enabled: false, createdAt: '2026-03-10T00:00:00Z', updatedAt: '2026-03-10T00:00:00Z' },
        ]),
      })
    })
    await page.goto('/settings/users')
    await page.waitForLoadState('networkidle')
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/16-users-settings.png`, fullPage: true })
  })

  test('17-project-switcher', async ({ page }) => {
    await setupApiMocks(page)
    await page.route('**/api/projects', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 1, slug: 'default', displayName: 'Default Project', description: null,
            configDir: './config', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
          { id: 2, slug: 'mobile-app', displayName: 'Mobile App', description: null,
            configDir: './config-mobile', createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z' },
          { id: 3, slug: 'backend-platform', displayName: 'Backend Platform', description: null,
            configDir: './config-backend', createdAt: '2026-03-01T00:00:00Z', updatedAt: '2026-03-01T00:00:00Z' },
        ]),
      })
    })
    await page.goto('/runs/11111111-2222-3333-4444-555555555555')
    await page.getByRole('button', { name: 'Выбрать проект' }).click()
    await page.screenshot({ animations: 'disabled', path: `${SHOTS}/13-project-switcher.png`, fullPage: true })
  })
})
