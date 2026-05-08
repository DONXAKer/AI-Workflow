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
export type IntegrationType = 'YOUTRACK' | 'GITLAB' | 'GITHUB' | 'OPENROUTER' | 'UNREAL' | 'CLAUDE_CODE_CLI'

export interface ToolCallEntry {
  blockId: string
  iteration: number
  toolName: string
  inputJson: string
  isError: boolean
  durationMs: number
  outputText?: string
}

export type LlmProvider = 'OPENROUTER' | 'CLAUDE_CODE_CLI'

export interface LlmCallEntry {
  blockId: string
  iteration: number
  model: string
  tokensIn: number
  tokensOut: number
  costUsd: number
  durationMs: number
  /** Where the call physically went. Null on legacy rows written before this column existed. */
  provider?: LlmProvider
  /** Stop reason: end_turn / tool_calls / length / MAX_ITERATIONS / BUDGET_EXCEEDED / ERROR. */
  finishReason?: string
}

export interface StoredBlockOutput {
  blockId: string
  outputJson: string
  inputJson?: string
}

export interface BlockEvent {
  blockId: string
  iteration: number
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
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
  entryPointId?: string
  runInputsJson?: string | null
  /** Chronologically ordered block events with timing data from BlockOutput.startedAt/completedAt */
  events?: BlockEvent[]
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

export type Severity = 'ERROR' | 'WARN' | 'INFO'

/** Single error from `POST /api/pipelines/validate` (mirrors Java `ValidationError` record). */
export interface ValidationError {
  code: string
  message: string
  location: string | null
  blockId: string | null
  /** ERROR blocks save/run; WARN/INFO are advisory. Defaults to ERROR for legacy responses. */
  severity?: Severity
}

/** Result envelope from `POST /api/pipelines/validate`. */
export interface ValidationResult {
  valid: boolean
  errors: ValidationError[]
}

// ── Full PipelineConfig (matches Java POJOs in workflow-core/config/) ─────────

/** AgentConfig override (block- or pipeline-level). Mirrors Java AgentConfig. */
export interface AgentConfigDto {
  model?: string | null
  temperature?: number | null
  /** Java side serialises as "maxTokens"; "max_tokens" / "maxTokensOrDefault" accepted on read. */
  maxTokens?: number | null
  /** Java side serialises as "systemPrompt"; "system_prompt" accepted on read. */
  systemPrompt?: string | null
}

export interface DefaultsConfigDto {
  agent?: AgentConfigDto | null
}

export interface IntegrationsConfigDto {
  youtrack?: string | null
  gitlab?: string | null
  github?: string | null
  openrouter?: string | null
}

export interface KnowledgeSourceConfigDto {
  type?: string
  url?: string
  branch?: string
  localPath?: string
  path?: string
}

export interface KnowledgeBaseConfigDto {
  sources?: KnowledgeSourceConfigDto[]
}

export interface FieldCheckConfigDto {
  field?: string
  rule?: string
  value?: unknown
  message?: string
}

export interface LLMCheckConfigDto {
  enabled?: boolean
  prompt?: string
  minScore?: number
  model?: string
}

export interface OnFailConfigDto {
  /** loopback | fail | warn */
  action?: string
  target?: string
  /** Java @JsonProperty("max_iterations"). */
  max_iterations?: number
  /** Java @JsonProperty("inject_context"). */
  inject_context?: Record<string, string>
}

export interface VerifyConfigDto {
  subject?: string
  checks?: FieldCheckConfigDto[]
  /** Java @JsonProperty("llm_check"). */
  llm_check?: LLMCheckConfigDto | null
  /** Java @JsonProperty("on_fail"). */
  on_fail?: OnFailConfigDto | null
}

export interface OnFailureConfigDto {
  action?: string
  target?: string
  max_iterations?: number
  inject_context?: Record<string, string>
  /** Java @JsonProperty("failed_statuses"). */
  failed_statuses?: string[]
}

export interface GateConfigDto {
  name?: string
  expr?: string
  description?: string
}

export interface TimeoutConfigDto {
  action?: 'fail' | 'notify' | 'escalate'
  target?: string
  description?: string
}

export interface RetryConfigDto {
  max_attempts?: number
  backoff_ms?: number
  max_backoff_ms?: number
}

/** Full BlockConfig (mirrors Java BlockConfig POJO including @JsonProperty mappings). */
export interface BlockConfigDto {
  id: string
  block: string
  agent?: AgentConfigDto | null
  /** Boolean flag (legacy). True = manual approval. */
  approval?: boolean
  /** Java @JsonProperty("approval_mode"). */
  approval_mode?: 'manual' | 'auto' | 'auto_notify' | null
  enabled?: boolean
  /** Java @JsonProperty("depends_on"). */
  depends_on?: string[]
  /** Free-form per-block config. */
  config?: Record<string, unknown>
  verify?: VerifyConfigDto | null
  condition?: string | null
  /** Java @JsonProperty("on_failure"). */
  on_failure?: OnFailureConfigDto | null
  skills?: string[]
  profile?: string | null
  /** Java @JsonProperty("required_gates"). */
  required_gates?: GateConfigDto[]
  /** Java @JsonProperty("timeout") (timeoutSeconds field). */
  timeout?: number | null
  /** Java @JsonProperty("on_timeout"). */
  on_timeout?: TimeoutConfigDto | null
  retry?: RetryConfigDto | null
  /**
   * Per-instance phase override. Lower-case string ('intake' | 'analyze' |
   * 'implement' | 'verify' | 'publish' | 'release' | 'any'). Null/undefined
   * means inherit from BlockMetadataDto.phase. The validator parses
   * case-insensitively.
   */
  phase?: string | null
}

export interface EntryPointInjectionDto {
  /** Java @JsonProperty("block_id"). */
  block_id?: string
  source?: string
  config?: Record<string, unknown>
}

export interface EntryPointConfigDto {
  id?: string
  name?: string
  description?: string
  /** Java @JsonProperty("from_block"). */
  from_block?: string
  inject?: EntryPointInjectionDto[]
  /** Java @JsonProperty("auto_detect"). */
  auto_detect?: string | null
  /** Java @JsonProperty("requires_input"). */
  requires_input?: string
}

export interface TriggerConfigDto {
  id?: string
  type?: string
  provider?: string
  event?: string
  conditions?: Record<string, string>
  /** Java @JsonProperty("entry_point_id"). */
  entry_point_id?: string
  /** Java @JsonProperty("from_block"). */
  from_block?: string
  enabled?: boolean
}

/** Full PipelineConfig (top-level YAML root, mirrors Java PipelineConfig). */
export interface PipelineConfigDto {
  name?: string
  description?: string
  defaults?: DefaultsConfigDto | null
  integrations?: IntegrationsConfigDto | null
  knowledgeBase?: KnowledgeBaseConfigDto | null
  pipeline?: BlockConfigDto[]
  /** Java @JsonProperty("entry_points"). */
  entry_points?: EntryPointConfigDto[]
  triggers?: TriggerConfigDto[]
  /**
   * Disable Level 4 phase ordering check for this pipeline. Default true.
   * Set to false for legacy/exotic pipelines that do not fit the linear
   * phase model (e.g. multi-pass templates).
   */
  phase_check?: boolean
}

// ── Block registry (UI editor metadata) ───────────────────────────────────────

export type FieldSchemaType =
  | 'string' | 'number' | 'boolean' | 'string_array'
  | 'enum' | 'block_ref' | 'tool_list'

export interface FieldSchemaDto {
  name: string
  label: string
  type: FieldSchemaType
  required?: boolean
  defaultValue?: unknown
  description?: string
  hints?: Record<string, unknown>
  /**
   * UI tier for sectioned side-panel rendering (PR-1, 2026-05-07).
   * `essential` — render in always-open Essentials section.
   * `advanced`  — render in collapsed Advanced section.
   * Backend defaults from `required` when absent: required → essential,
   * optional → advanced. Optional on the wire so legacy responses keep parsing.
   */
  level?: 'essential' | 'advanced'
}

export type Phase =
  | 'INTAKE' | 'ANALYZE' | 'IMPLEMENT' | 'VERIFY' | 'PUBLISH' | 'RELEASE' | 'ANY'

export interface BlockMetadataDto {
  label: string
  category: string
  /**
   * Pipeline phase this block type belongs to. ANY = polymorphic block whose
   * phase the operator must pin per-instance via BlockConfigDto.phase override.
   */
  phase: Phase
  configFields: FieldSchemaDto[]
  hasCustomForm: boolean
  uiHints?: Record<string, unknown>
  /**
   * Fields the block produces in its run-output map (PR-1, 2026-05-07). Drives
   * the OutputsRefPicker autocomplete and a WARN-level REF_UNKNOWN_FIELD validator
   * check. Optional on the wire — empty / absent means the block hasn't declared
   * its output schema yet (warn-only behaviour, never blocks save/run).
   */
  outputs?: FieldSchemaDto[]
  /**
   * Creation-wizard hint (PR-3): higher number = more typically chosen as the
   * default block for this phase. `0` (or absent) = no preference declared.
   */
  recommendedRank?: number
}

export interface BlockRegistryEntry {
  type: string
  description: string
  metadata: BlockMetadataDto
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
  /** Default LLM provider for runs that don't pin one in inputs.provider. */
  defaultProvider?: LlmProvider | null
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
  iteration?: number
  blockType?: string
  status: 'pending' | 'running' | 'awaiting_approval' | 'complete' | 'skipped' | 'failed'
  output?: Record<string, unknown>
  input?: Record<string, unknown>
  /** ISO timestamp set when BLOCK_STARTED is received — used for per-step duration display */
  startedAt?: string
  /** ISO timestamp set when BLOCK_COMPLETE is received — used to compute per-step duration */
  completedAt?: string
  /** Pre-computed duration in ms from BlockOutput.startedAt/completedAt (historical runs) */
  durationMs?: number
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
