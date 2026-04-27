# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm run dev          # dev server on port 5120 (proxies /api and /ws to localhost:8020)
npm run build        # tsc type-check + vite build → dist/
npm run preview      # serve dist/ on port 4173 (used by Playwright tests)
npm test             # Playwright tests against vite preview (no backend required)
npm run test:ui      # Playwright interactive mode
npm run test:e2e     # E2E tests only (tests/e2e/ — require a live backend)
```

Run a single test file:
```bash
npx playwright test tests/ui/run-page.spec.ts
npx playwright test tests/ui/run-page.spec.ts --headed   # with browser
```

## Architecture

**Tech stack:** React 18, TypeScript strict, Tailwind CSS, React Router v6, Vite 5, STOMP/SockJS WebSocket.

### State layers

| Layer | File | Purpose |
|---|---|---|
| Auth | `src/context/AuthContext.tsx` | Session, role, CSRF prime |
| Run stats | `src/context/RunsContext.tsx` | `activeCount`/`pendingApprovalCount` with 500ms WS debounce |
| Toast | `src/context/ToastContext.tsx` | Non-blocking notifications |
| Filters | `src/hooks/useRunsFilter.ts` | URL query params → filter state (shareable URLs) |
| Project scope | `src/services/projectContext.ts` | `currentProjectSlug()` from localStorage, attached as `X-Project-Slug` header |

### API client (`src/services/api.ts`)

Single `api` object with typed methods. The underlying `request()` wrapper:
- Reads `XSRF-TOKEN` cookie → sets `X-XSRF-TOKEN` header on every mutating call (Spring Security CSRF)
- Sets `X-Project-Slug` header on all non-auth/non-projects requests
- Redirects to `/login` on 401

**AuthContext primes CSRF** by calling `GET /api/auth/me` before the first POST, ensuring Spring issues the `XSRF-TOKEN` cookie.

### Routing (`src/App.tsx`)

- `RequireAuth` wraps all routes; unauthenticated → `/login`
- `/projects/:slug/*` → `ProjectWorkspacePage` (calls `setCurrentProjectSlug(slug)` on mount)
  - Nested tabs: `smart-start`, `launch`, `active`, `history`, `integrations`, `mcp`, `settings`
- `/runs/:runId` → `RunPage` (live block progress + approval dialog)
- `/runs/active` → `ActiveRunsPage` (global, uses `allProjects: true`)
- `/system/*` → admin pages (users, integrations, audit, kill-switch, cost, projects)

### WebSocket (`src/services/websocket.ts`)

STOMP over SockJS. Two subscription channels:
- `connectToRun(runId, cb)` → `/topic/runs/{runId}` — per-run events
- `connectToGlobalRuns(cb)` → `/topic/runs` — run lifecycle for stats badge

Message types: `BLOCK_STARTED`, `BLOCK_COMPLETE`, `BLOCK_PROGRESS`, `BLOCK_SKIPPED`, `APPROVAL_REQUEST`, `AUTO_NOTIFY`, `RUN_COMPLETE`.

### Block labels (`src/utils/blockLabels.ts`)

`blockIdLabel(id)` returns a Russian display name. Lookup order: block-ID map → block-type map → raw ID. Use this everywhere a block ID appears in the UI — never render raw IDs.

`BLOCK_TYPE_RECOMMENDED_PRESET` maps block types to model tiers (used in `PipelineConfigTab`).

### Approval flow

1. `APPROVAL_REQUEST` WS message arrives → `RunPage` shows `ApprovalDialog`
2. Dialog supports: `APPROVE`, `EDIT` (modify output JSON), `REJECT`, `SKIP`, `JUMP` (resume at block X)
3. Decision sent via `api.submitApproval(runId, decision)`
4. `BlockSnapshot` (parsed from `PipelineRun.configSnapshotJson`) drives per-block `approval_mode` badge in `BlockProgressTable`

### Config snapshot

`src/utils/configSnapshot.ts` parses `PipelineRun.configSnapshotJson` (captured at run-start). Use `parseConfigSnapshot(run)` to get per-block config without re-fetching the YAML — the snapshot is stable even if the pipeline YAML changes after a run starts.

## Testing

Tests use `vite preview` (production build) + Playwright route mocking — **no live backend needed** for `tests/ui/`.

**Pattern:**
```typescript
import { setupApiMocks, makeRun } from '../fixtures/api-mocks'

test('my test', async ({ page }) => {
  await setupApiMocks(page, {
    run: makeRun({ status: 'PAUSED_FOR_APPROVAL', currentBlock: 'plan' }),
  })
  await page.goto(`/runs/${RUN_ID}`)
})
```

`setupApiMocks` mocks: auth, runs CRUD, pipelines, projects, users, audit, kill-switch, cost, tool-calls. Override individual routes with `page.route()` **after** `setupApiMocks` (LIFO priority).

`makeRun(overrides)` extends the default completed run fixture. WS handshakes are aborted — simulate WS events by directly manipulating page state or by re-navigating.

Build must be current before running tests:
```bash
npm run build && npm test
```
