import { Page, Route } from '@playwright/test'

export interface MockRun {
  id: string
  pipelineName: string
  requirement: string
  status: 'PENDING' | 'RUNNING' | 'PAUSED_FOR_APPROVAL' | 'COMPLETED' | 'FAILED'
  currentBlock: string | null
  error: string | null
  startedAt: string
  completedAt: string | null
  completedBlocks: string[]
  autoApprove: string[]
  outputs?: { blockId: string; outputJson: string }[]
  loopHistoryJson?: string | null
}

export interface MockPipeline {
  path: string
  name: string
  pipelineName?: string
  description?: string
}

export interface MockUser {
  id: number
  username: string
  displayName: string | null
  email: string | null
  role: 'VIEWER' | 'OPERATOR' | 'RELEASE_MANAGER' | 'ADMIN'
}

export interface MockSetup {
  run?: MockRun
  runs?: MockRun[]          // list of runs for GET /api/runs (active-runs page, dashboard)
  pipelines?: MockPipeline[]
  user?: MockUser | null
  onReturnSubmit?: (body: Record<string, unknown>) => void
  onApprovalSubmit?: (body: Record<string, unknown>) => void
  returnShouldFail?: string
}

const defaultUser: MockUser = {
  id: 1,
  username: 'admin',
  displayName: 'Admin',
  email: 'admin@example.com',
  role: 'ADMIN',
}

const defaultRun: MockRun = {
  id: '11111111-2222-3333-4444-555555555555',
  pipelineName: 'example-pipeline',
  requirement: 'Implement user authentication',
  status: 'COMPLETED',
  currentBlock: null,
  error: null,
  startedAt: '2026-04-15T10:00:00Z',
  completedAt: '2026-04-15T10:30:00Z',
  completedBlocks: ['analysis', 'codegen', 'verify_code'],
  autoApprove: [],
  outputs: [
    { blockId: 'analysis', outputJson: JSON.stringify({ summary: 'Auth system analysis', estimated_complexity: 'medium' }) },
    { blockId: 'codegen', outputJson: JSON.stringify({ changes: [{ path: 'auth.ts', content: '...' }], commit_message: 'add auth' }) },
    { blockId: 'verify_code', outputJson: JSON.stringify({ passed: true, issues: [] }) },
  ],
}

const defaultPipelines: MockPipeline[] = [
  { path: './config/pipeline.example.yaml', name: 'pipeline.example.yaml', pipelineName: 'example-pipeline' },
]

export async function setupApiMocks(page: Page, setup: MockSetup = {}) {
  const run = setup.run ?? defaultRun
  const pipelines = setup.pipelines ?? defaultPipelines
  const user = setup.user === null ? null : (setup.user ?? defaultUser)

  await page.route('**/api/auth/me', async (route: Route) => {
    if (user === null) {
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"Not authenticated"}' })
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(user) })
  })

  await page.route('**/api/auth/login', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(user ?? defaultUser) })
  })

  await page.route('**/api/auth/logout', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"success":true}' })
  })

  // Default audit list — empty page. Tests can override with their own route().
  await page.route('**/api/audit**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 50 }),
    })
  })

  // Default kill switch — inactive.
  await page.route('**/api/admin/kill-switch', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ active: false, reason: null, activatedBy: null, activatedAt: null }),
    })
  })

  // Default project list — one default project.
  await page.route('**/api/projects', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 1, slug: 'default', displayName: 'Default Project',
          description: null, configDir: './config',
          createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
        },
      ]),
    })
  })

  // Default users list — just the current admin.
  await page.route('**/api/users', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 1, username: user?.username ?? 'admin',
          displayName: user?.displayName ?? 'Admin', email: user?.email ?? null,
          role: user?.role ?? 'ADMIN', enabled: true,
          createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
        },
      ]),
    })
  })

  // Default cost summary — empty.
  await page.route('**/api/cost/summary**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        from: '2026-03-17T00:00:00Z',
        to: '2026-04-17T00:00:00Z',
        totalCostUsd: 0,
        totalCalls: 0,
        totalTokensIn: 0,
        totalTokensOut: 0,
        byModel: [],
      }),
    })
  })

  await page.route('**/api/runs/*/return', async (route: Route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>
    setup.onReturnSubmit?.(body)
    if (setup.returnShouldFail) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ error: setup.returnShouldFail }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        runId: run.id,
        targetBlock: body.targetBlock,
        status: 'RUNNING',
      }),
    })
  })

  await page.route('**/api/runs/*/approval', async (route: Route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>
    setup.onApprovalSubmit?.(body)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true }),
    })
  })

  await page.route('**/api/runs/*/cancel', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true }),
    })
  })

  // Runs list: GET /api/runs?status=...&page=...&size=...
  // Matches list calls (no UUID segment). Individual run detail is matched by the regex below.
  await page.route(/\/api\/runs(\?|$)/, async (route: Route) => {
    const runsList = setup.runs ?? []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        content: runsList,
        totalElements: runsList.length,
        totalPages: 1,
        page: 0,
        size: 100,
      }),
    })
  })

  await page.route(/\/api\/runs\/[^/?]+$/, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(run),
    })
  })

  await page.route('**/api/pipelines', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(pipelines),
    })
  })

  // Block WebSocket STOMP handshakes so SockJS falls back silently without noise.
  await page.route('**/ws/**', (route: Route) => route.abort())
  await page.route('**/ws', (route: Route) => route.abort())
}

export function makeRun(overrides: Partial<MockRun>): MockRun {
  return { ...defaultRun, ...overrides }
}
