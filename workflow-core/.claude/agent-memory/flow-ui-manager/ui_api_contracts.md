---
name: API contracts and WebSocket event schemas
description: REST endpoints, TypeScript types, and WebSocket message shapes used by the UI
type: project
---

## REST API (BASE = '/api')

### Runs
| method | path | notes |
|---|---|---|
| POST | `/runs` | body: `{configPath, requirement?, youtrackIssue?, fromBlock?}` |
| GET | `/runs/:id` | returns `PipelineRun` — includes `outputs?: StoredBlockOutput[]` if serialized (may be absent due to JPA circular-ref risk on BlockOutput.run back-reference) |
| GET | `/runs?status&pipelineName&search&from&to&page&size` | returns `PaginatedResponse<PipelineRunSummary>` |
| POST | `/runs/:id/cancel` | no body |
| GET | `/runs/stats` | returns `RunStats` |
| POST | `/runs/:id/approval` | body: `ApprovalDecision` |

### Pipelines
| method | path |
|---|---|
| GET | `/pipelines` | returns `{path, name, pipelineName?, description?, error?}[]` |

### Integrations
| method | path |
|---|---|
| GET | `/integrations` | |
| POST | `/integrations` | |
| PUT | `/integrations/:id` | |
| DELETE | `/integrations/:id` | |
| POST | `/integrations/:id/test` | returns `{success, message}` |

## TypeScript types (from `types.ts`)

```ts
type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED_FOR_APPROVAL' | 'COMPLETED' | 'FAILED'
type IntegrationType = 'YOUTRACK' | 'GITLAB' | 'GITHUB' | 'OPENROUTER'

interface StoredBlockOutput { blockId: string; outputJson: string }

interface PipelineRun {
  id: string; pipelineName: string; requirement: string; status: RunStatus
  currentBlock: string | null; error: string | null
  startedAt: string; completedAt: string | null
  completedBlocks: string[]; autoApprove: string[]
  outputs?: StoredBlockOutput[]  // persisted outputs, optional — may not appear in API response
}

interface PipelineRunSummary {
  id, pipelineName, requirement, status, currentBlock, error, startedAt, completedAt, blockCount
}

interface BlockOutput { blockId: string; outputJson: string }  // legacy alias for StoredBlockOutput

interface BlockStatus {
  blockId: string; blockType?: string
  status: 'pending' | 'running' | 'awaiting_approval' | 'complete' | 'skipped' | 'failed'
  output?: Record<string, unknown>
}

type WsMessageType = 'BLOCK_STARTED' | 'BLOCK_COMPLETE' | 'APPROVAL_REQUEST' | 'RUN_COMPLETE'
interface WsMessage {
  type: WsMessageType; blockId?: string; status?: string
  output?: Record<string, unknown>; description?: string; runId?: string
}

type ApprovalDecisionType = 'APPROVE' | 'EDIT' | 'REJECT' | 'SKIP' | 'JUMP'
interface ApprovalDecision {
  blockId: string; decision: ApprovalDecisionType
  output?: Record<string, unknown>; skipFuture?: boolean; targetBlockId?: string
}
```

## WebSocket (STOMP over SockJS)

- SockJS endpoint: `/ws`
- Per-run topic: `/topic/runs/:runId`
- Global topic: `/topic/runs`
- Reconnect delay: 3000ms
- Connection helpers: `connectToRun(runId, onMessage, onConnect?)` and `connectToGlobalRuns(onMessage, onConnect?)` — both return a cleanup function `() => void`

## Known serialization caveat

`BlockOutput` entity has a `@ManyToOne(fetch = LAZY)` back-reference to `PipelineRun`. Serializing `PipelineRun.outputs` directly via Spring's default Jackson config risks circular reference or LazyInitializationException. The UI treats `outputs` as optional and gracefully falls back when absent.
