export interface EntryPointField {
  name: string
  label: string
  type: 'text' | 'textarea' | 'number'
  placeholder?: string
  required?: boolean
}

export interface EntryPoint {
  id: string
  name: string
  description?: string
  fromBlock: string
  requiresInput?: string
  autoDetect?: string
  inputFields: EntryPointField[]
}

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED_FOR_APPROVAL' | 'COMPLETED' | 'FAILED'
export type IntegrationType = 'YOUTRACK' | 'GITLAB' | 'GITHUB' | 'OPENROUTER' | 'UNREAL'

export interface ToolCallEntry {
  blockId: string
  iteration: number
  toolName: string
  inputJson: string
  isError: boolean
  durationMs: number
  outputText?: string
}

export interface StoredBlockOutput {
  blockId: string
  outputJson: string
  inputJson?: string
}

export interface PipelineRun {
  id: string
  pipelineName: string
  requirement: string
  status: RunStatus
  currentBlock: string | null
  error: string | null
  startedAt: string
  completedAt: string | null
  completedBlocks: string[]
  autoApprove: string[]
  // Persisted block outputs — present on completed/failed runs loaded from API
  outputs?: StoredBlockOutput[]
  loopHistoryJson?: string | null
  configSnapshotJson?: string | null
  projectSlug?: string
  dryRun?: boolean
}

export type ApprovalMode = 'manual' | 'auto' | 'auto_notify'

/** Minimal view of a block's config extracted from {@code configSnapshotJson}. */
export interface BlockSnapshot {
  id: string
  block: string
  approval_mode?: ApprovalMode
  approval?: boolean
  enabled?: boolean
  timeout?: number
  condition?: string
}

export interface LoopbackEntry {
  timestamp: string
  source?: 'operator_return' | 'verify' | 'ci_failure' | string
  from_block?: string
  to_block: string
  iteration: number
  comment?: string
  issues?: string[]
}

export interface PipelineRunSummary {
  id: string
  pipelineName: string
  requirement: string
  status: RunStatus
  currentBlock: string | null
  error: string | null
  startedAt: string
  completedAt: string | null
  blockCount: number
}

export interface PaginatedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}

export interface RunFilters {
  status?: RunStatus[]
  pipelineName?: string
  search?: string
  from?: string
  to?: string
  page?: number
  size?: number
  allProjects?: boolean
}

export interface RunStats {
  activeRuns: number
  awaitingApproval: number
  completedToday: number
  failedToday: number
}

export interface BlockOutput {
  blockId: string
  outputJson: string
}

export interface IntegrationConfig {
  id?: number
  name: string
  type: IntegrationType
  displayName: string
  baseUrl: string
  token: string
  project?: string
  owner?: string
  repo?: string
  extraConfigJson?: string
  isDefault: boolean
}

export type WsMessageType =
  | 'BLOCK_STARTED'
  | 'BLOCK_COMPLETE'
  | 'BLOCK_PROGRESS'
  | 'APPROVAL_REQUEST'
  | 'BASH_APPROVAL_REQUEST'
  | 'AUTO_NOTIFY'
  | 'BLOCK_SKIPPED'
  | 'RUN_COMPLETE'

export interface WsMessage {
  type: WsMessageType
  blockId?: string
  status?: string
  output?: Record<string, unknown>
  description?: string
  detail?: string
  runId?: string
  // BASH_APPROVAL_REQUEST fields
  command?: string
  requestId?: string
}

export interface AgentProfile {
  id?: number
  name: string
  displayName: string
  description: string
  rolePrompt: string
  customPrompt: string
  model: string
  maxTokens: number
  temperature: number
  skills: string[]
  knowledgeSources?: string[]
  useExamples?: boolean
  recommendedPreset?: string | null
  builtin?: boolean
}

export interface SkillInfo {
  name: string
  description: string
}

export type UserRole = 'VIEWER' | 'OPERATOR' | 'RELEASE_MANAGER' | 'ADMIN'

export interface CurrentUser {
  id: number
  username: string
  displayName: string | null
  email: string | null
  role: UserRole
}

export interface AuditEntry {
  id: number
  timestamp: string
  actor: string
  action: string
  targetType: string | null
  targetId: string | null
  outcome: 'SUCCESS' | 'FAILURE'
  detailsJson: string | null
  remoteAddr: string | null
}

export interface AuditFilters {
  actor?: string
  action?: string
  targetType?: string
  targetId?: string
  outcome?: 'SUCCESS' | 'FAILURE'
  from?: string
  to?: string
  page?: number
  size?: number
}

export interface KillSwitchState {
  active: boolean
  reason: string | null
  activatedBy: string | null
  activatedAt: string | null
}

export interface CostByModel {
  model: string
  calls: number
  tokensIn: number
  tokensOut: number
  costUsd: number
}

export interface CostSummary {
  from: string
  to: string
  totalCostUsd: number
  totalCalls: number
  totalTokensIn: number
  totalTokensOut: number
  byModel: CostByModel[]
}

export interface PipelineAgentOverride {
  model?: string | null
  temperature?: number | null
  maxTokens?: number | null
  systemPrompt?: string | null
}

export interface PipelineBlockSetting {
  id: string
  block: string
  enabled: boolean
  approval: boolean
  profile?: string | null
  skills: string[]
  agent: PipelineAgentOverride
}

export interface PipelineDefaultsSetting {
  agent?: PipelineAgentOverride | null
}

export interface PipelineConfigSettings {
  defaults: PipelineDefaultsSetting | null
  blocks: PipelineBlockSetting[]
}

export interface ProjectInfo {
  id: number
  slug: string
  displayName: string
  description: string | null
  configDir: string | null
  workingDir: string | null
  orchestratorEnabled?: boolean
  orchestratorModel?: string | null
  orchestratorSystemPromptExtra?: string | null
  createdAt: string
  updatedAt: string
}

export interface UserInfo {
  id: number
  username: string
  displayName: string | null
  email: string | null
  role: UserRole
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateUserBody {
  username: string
  password: string
  displayName?: string
  email?: string
  role?: UserRole
}

export interface UpdateUserBody {
  displayName?: string
  email?: string
  role?: UserRole
  enabled?: boolean
  password?: string
}

export type ApprovalDecisionType = 'APPROVE' | 'EDIT' | 'REJECT' | 'SKIP' | 'JUMP'

export interface ApprovalDecision {
  blockId: string
  decision: ApprovalDecisionType
  output?: Record<string, unknown>
  skipFuture?: boolean
  targetBlockId?: string
}

export interface BlockStatus {
  blockId: string
  blockType?: string
  status: 'pending' | 'running' | 'awaiting_approval' | 'complete' | 'skipped' | 'failed'
  output?: Record<string, unknown>
  input?: Record<string, unknown>
  /** ISO timestamp set when BLOCK_STARTED is received — used for per-step duration display */
  startedAt?: string
  /** Latest progress detail from BLOCK_PROGRESS WS events */
  progressDetail?: string
}

export interface McpServer {
  id?: number
  name: string
  description?: string
  url: string
  headersJson?: string
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}
