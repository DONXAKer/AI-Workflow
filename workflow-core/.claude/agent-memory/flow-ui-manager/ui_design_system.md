---
name: Design system tokens and UI conventions
description: Status colors, badge patterns, icon usage, and Tailwind conventions
type: project
---

## Color palette (dark theme — bg-slate-950 base)

### Run status colors (from RunStatusBadge.tsx)
| status | bg | text | animation |
|---|---|---|---|
| PENDING | bg-slate-700/60 | text-slate-400 | none |
| RUNNING | bg-blue-900/50 | text-blue-300 | animate-spin on icon |
| PAUSED_FOR_APPROVAL | bg-amber-900/50 | text-amber-300 | animate-ping dot |
| COMPLETED | bg-green-900/50 | text-green-300 | none |
| FAILED | bg-red-900/50 | text-red-300 | none |

### Block status colors (BlockStatus type — from BlockProgressTable.tsx)
| status | badge | row bg | animation |
|---|---|---|---|
| pending | slate | none | none |
| running | blue | bg-blue-950/20 | animate-spin on Loader2 |
| awaiting_approval | amber | bg-amber-950/20 | animate-ping dot |
| complete | green | none | none |
| skipped | amber-400 | none | none |
| failed | red | bg-red-950/20 | none |

## Badge pattern
```tsx
<span className={clsx('inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium', cfg.classes)}>
  <Icon className={clsx('w-3.5 h-3.5', cfg.spin && 'animate-spin')} />
  {cfg.label}
  {cfg.pulse && (
    <span className="relative flex h-1.5 w-1.5 ml-0.5">
      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
      <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-amber-400" />
    </span>
  )}
</span>
```

## Icons (lucide-react)
- Clock → pending/waiting
- Loader2 + animate-spin → running/loading
- AlertCircle → approval needed / warning
- CheckCircle → success/complete
- XCircle → failed/error
- SkipForward → skipped

## Sidebar approval callout
When `pendingApprovalCount > 0`, a pulsing amber callout banner appears in the sidebar between the nav links and the settings section. It links to `/runs/active?status=PAUSED_FOR_APPROVAL`.

## Focus-visible ring convention (updated 2026-03-28)
All interactive elements on the dark background use `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-{color}-500 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-{900|950}`. Standard colors:
- Nav links / general buttons: `ring-blue-500 ring-offset-slate-900`
- Cancel/destructive buttons: `ring-red-500 ring-offset-slate-950`
- Amber/approval links: `ring-amber-500 ring-offset-slate-900`

## Empty state pattern (updated 2026-03-28)
Empty tables/sections use an inline SVG illustration (simple geometric shapes, 36–40px, `text-slate-700`) above a primary message (`text-slate-500 text-sm font-medium`) and a secondary hint (`text-slate-600 text-xs`). All SVG elements have `aria-hidden="true"`. The container has `py-14` (tables) or `py-8` (cards).

- RunsTable: stacked bars (empty list metaphor)
- ActiveRunsPage (no runs): circle with play triangle
- ActiveRunsPage (filter mismatch): funnel icon + "Try clearing the filter" hint
- IntegrationsSettings per-section: plug/connector icon

## OutputCell accordion (updated 2026-03-28)
The expand toggle in `BlockProgressTable.OutputCell` shows a `ChevronRight`/`ChevronDown` icon (lucide-react) to the left of the preview text, making it clearly interactive. Has `aria-expanded` and `aria-label`.

## General conventions
- `clsx` for conditional class merging (not `cn` or `cx`)
- All components are functional, no class components
- TypeScript interfaces for all props — no PropTypes
- No Redux/Zustand — React context + local state only
