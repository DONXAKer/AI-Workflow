---
name: UI stack and structure
description: Frontend framework, file layout, routing, and state management patterns
type: project
---

## Stack
- React 18 + TypeScript, Vite, Tailwind CSS 3, react-router-dom v6
- Icons: lucide-react
- Utility: clsx
- WebSocket: @stomp/stompjs + sockjs-client

## File structure (`workflow-ui/src/`)
```
App.tsx                          — root router + RunsProvider wrapper
types.ts                         — all shared TypeScript interfaces
services/api.ts                  — all REST calls (fetch-based, BASE = '/api')
services/websocket.ts            — STOMP/SockJS connection helpers
context/RunsContext.tsx          — global runs state (RunsProvider)
hooks/useRelativeTime.ts
hooks/useRunsFilter.ts
components/
  layout/Sidebar.tsx
  layout/PageHeader.tsx
  runs/RunStatusBadge.tsx        — canonical status badge component
  runs/RunsTable.tsx
  runs/FilterBar.tsx
  runs/RunDuration.tsx
  runs/CancelButton.tsx
  dashboard/StatCard.tsx
  integrations/IntegrationSlideOver.tsx
  ApprovalDialog.tsx
  BlockProgressTable.tsx
  LogPanel.tsx
  IntegrationsSettings.tsx
pages/
  DashboardPage.tsx
  PipelinesPage.tsx
  ActiveRunsPage.tsx
  RunHistoryPage.tsx
  RunPage.tsx
```

## Routing
| path | page |
|---|---|
| `/` | DashboardPage |
| `/pipelines` | PipelinesPage |
| `/runs/active` | ActiveRunsPage |
| `/runs/history` | RunHistoryPage |
| `/runs/:runId` | RunPage (also `/run/:runId`) |
| `/settings/integrations` | IntegrationsSettings |

## State management
- Global: `RunsProvider` (context) wraps entire app
- No Redux / Zustand — plain React context + local state
- WebSocket subscriptions are set up per-component using `connectToRun` / `connectToGlobalRuns` from `services/websocket.ts`
- REST calls go directly through `api.*` in `services/api.ts` — no React Query

## API error handling (updated 2026-03-28)
`services/api.ts` wraps all `fetch` calls in a `request<T>()` helper that throws a descriptive `Error` on non-2xx responses. Callers use `try/catch` and expose errors to the user via UI state (never silently swallow). `startRun` body type now explicitly includes `youtrackIssue?` and `injectedOutputs?`.

## Layout (updated 2026-03-28)
Dark theme: `bg-slate-950 text-slate-100`. Sidebar + main content flex layout. Main scrolls independently.

- Desktop (`lg+`): Sidebar is `hidden lg:flex` inside `AppLayout`. Root container is `flex-col lg:flex-row`.
- Mobile (`< lg`): Sidebar is hidden. A sticky `MobileTopBar` (height 12) with a hamburger button is rendered. Tapping the hamburger opens `MobileDrawer` — a `fixed inset-0` overlay that renders `<Sidebar />` inside a panel. The drawer closes on: backdrop click, Escape key, or any navigation (`useLocation` effect).
- `AppLayout` is extracted from `App.tsx` so it can use `useLocation` inside `<Router>` context.
- `RunsProvider` still wraps everything at the `App` root.

## RunPage patterns (updated 2026-03-28 — tab mount strategy)
- Both `BlockProgressTable` and `LogPanel` stay **mounted** at all times. The inactive panel is hidden with `className={activeTab !== tab ? 'hidden' : undefined}`. This preserves: (a) LogPanel scroll position, (b) OutputCell expanded state across tab switches, (c) avoids layout jump from re-mount.
- Tab buttons have `role="tab"` and `aria-selected`.
- `LogPanel` receives `visible={activeTab === 'logs'}`. When `visible` transitions to `true`, LogPanel scrolls to bottom immediately so logs accumulated while hidden are not missed.

## Page padding standard (updated 2026-03-28)
All page-level containers use `max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6`. Exception: IntegrationsSettings was historically `max-w-5xl py-8` — now standardised to match. All pages use `PageHeader` rather than raw `<h1>` tags.

## RunHistoryPage search debounce (updated 2026-03-28)
`FilterBar` is a controlled component that calls `onSearchChange` on every keystroke. To avoid hammering the API, RunHistoryPage maintains a separate `searchInput` state (shown in the input) and a `searchDebounceRef`. `handleSearchChange` updates `searchInput` immediately and sets a 300 ms timer to commit `setFilter('search', value)` + reset page to 0. A sync effect keeps `searchInput` in step with `filters.search` so the Reset button in FilterBar also clears the input.

## RunDuration invalid date guard (updated 2026-03-28)
`new Date(invalidString).getTime()` returns `NaN`. RunDuration now checks `isNaN(start)` and `isNaN(end)` early and returns `<span>—</span>` rather than displaying "NaNs".

## RunPage patterns (originally 2026-03-28)
- `loadRun(quiet?: boolean)` — reloads run + block statuses. Pass `quiet=true` to skip loading spinner (used on WS reconnect and cancel).
- `showApprovalDialog` state is independent of `pendingApproval` — closing the modal keeps the approval pending (block stays amber) and the "Review" button in BlockProgressTable reopens it.
- WS `onConnect` callback calls `loadRun(true)` when run is still active to recover missed events.
- Tabs (Block Outputs / Event Log) are shown for ALL runs, not just historical ones.
- LogPanel receives `onClear` callback for clearing in-memory logs.
- `onReviewApproval` is passed inline as a conditional prop — no duplicate `<BlockProgressTable>` branches needed.

## PipelinesPage URL prefill (updated 2026-03-28)
- Uses `useSearchParams` (not `window.location.search`) for router-aware param reading.
- `?pipeline=` is matched first by `p.path` (exact), then by `p.pipelineName` / `p.name` — needed because Re-run navigation sends `run.pipelineName` which is the display name, not the file path.
- Match resolution happens inside `.then(data => ...)` so the pipeline list is available before selection is attempted.

## LogPanel (updated 2026-03-28)
- Search input filters visible log lines; matched substrings are highlighted with a yellow `<mark>`.
- Level filter pills: All / Errors / Approval / Complete / Warnings — each shows the subset matching that log class.
- Entry count shows "N of M" when filters are active vs "M entries" when unfiltered.
- Auto-scroll to bottom is suppressed when any filter is active (so the user can browse results).

## ApprovalDialog reject confirmation (updated 2026-03-28)
- Reject is a two-step action: first click sets `confirmReject = true`, showing an inline confirmation banner + "Confirm Reject" and "Cancel" buttons. This prevents accidental run termination.
- `confirmReject` is reset to `false` when Edit or Jump mode is toggled.

## StatCard loading skeleton (updated 2026-03-28)
- `StatCard` accepts `isLoading?: boolean`. When `true`, it renders a `h-9 w-14 rounded-md bg-slate-700/60 animate-pulse` shimmer bar instead of the numeric value, and suppresses the pulse dot. `DashboardPage` passes `isLoading={loading}` to all four cards so they shimmer on the first fetch.

## Page fade-in transition (updated 2026-03-28, revised 2026-03-28)
- `index.css` defines a `@keyframes fade-in` (opacity 0→1, 150 ms ease-out) exposed as the custom utility class `animate-fade-in` inside `@layer utilities`.
- `AppLayout` renders a stable `<main>` with no `key`. Inside it, a `<div key={location.key} className="animate-fade-in">` wraps `<Routes>`. This pattern triggers the CSS animation on every navigation without remounting the `<main>` DOM node itself. Keeping `<main>` stable is important: it prevents RunPage's WebSocket from being torn down when the browser navigates away and back via the history stack.

## RunsContext WS debounce (added 2026-03-28)
- `connectToGlobalRuns` fires on every pipeline WS event (BLOCK_STARTED, BLOCK_COMPLETE, etc.). Without a debounce, each step in a pipeline triggers an immediate `api.getRunStats()` call, creating a burst of concurrent requests.
- `RunsProvider` now holds a `debounceTimerRef: useRef<ReturnType<typeof setTimeout> | null>`. The WS callback clears any pending timer then sets a 500 ms one. The actual `refresh()` executes 500 ms after the *last* event in a burst.
- The `disconnect` cleanup in `useEffect` also clears the debounce timer to prevent stale callbacks on unmount.

## RunPage default tab verified (2026-03-28)
- `activeTab` initial state is `'blocks'`, which maps to the "Block Outputs" tab. This is correct for all runs: live runs land on block outputs and event log is a secondary tab; historical runs also default to block outputs showing persisted step data. No change needed.
