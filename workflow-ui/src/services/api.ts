import { IntegrationConfig, PipelineRun, ApprovalDecision, PipelineRunSummary, PaginatedResponse, RunFilters, RunStats, AgentProfile, SkillInfo, EntryPoint, CurrentUser, AuditEntry, AuditFilters, KillSwitchState, CostSummary, ProjectInfo, UserInfo, CreateUserBody, UpdateUserBody, ToolCallEntry, PipelineConfigSettings } from '../types'
import { currentProjectSlug } from './projectContext'

const BASE = '/api'

/** Reads the XSRF-TOKEN cookie set by Spring Security's CookieCsrfTokenRepository. */
function readCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

/** Thin wrapper: throws a descriptive Error for non-2xx responses. Auto-adds CSRF header. */
async function request<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const needsCsrf = method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS'
  const headers = new Headers(init?.headers)
  if (needsCsrf) {
    const token = readCsrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  // Always attach the project scope. Exempt auth/project endpoints which are inherently
  // cross-project (login, project list, etc.).
  const urlStr = typeof input === 'string' ? input : input.url
  const isCrossProject = urlStr.includes('/auth/') || urlStr.includes('/projects')
  if (!isCrossProject && !headers.has('X-Project-Slug')) {
    headers.set('X-Project-Slug', currentProjectSlug())
  }
  const res = await fetch(input, { ...init, headers, credentials: 'same-origin' })
  const url = typeof input === 'string' ? input : input.url
  const isAuthEndpoint = url.includes('/auth/me') || url.includes('/auth/login') || url.includes('/auth/logout')
  if (res.status === 401 && !isAuthEndpoint) {
    // Global 401 on a protected endpoint → redirect to login unless already there.
    if (!location.pathname.startsWith('/login')) location.href = '/login'
    throw new Error('Not authenticated')
  }
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.error) message = body.error
    } catch {
      // ignore parse error — use status message
    }
    throw new Error(message)
  }
  // 204 No Content — return undefined cast to T
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export const api = {
  // Auth
  login: (username: string, password: string): Promise<CurrentUser> =>
    request<CurrentUser>(
      `${BASE}/auth/login`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ username, password }) }
    ),

  logout: (): Promise<{ success: boolean }> =>
    request<{ success: boolean }>(`${BASE}/auth/logout`, { method: 'POST' }),

  me: (): Promise<CurrentUser> =>
    request<CurrentUser>(`${BASE}/auth/me`),

  // Runs
  startRun: (body: {
    configPath: string
    requirement?: string
    youtrackIssue?: string
    branchName?: string
    mrIid?: number
    fromBlock?: string
    entryPointId?: string
    injectedOutputs?: Record<string, Record<string, unknown>>
    dryRun?: boolean
  }) =>
    request<{ id: string; runId: string; status: string }>(
      `${BASE}/runs`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  getEntryPoints: (configPath: string): Promise<EntryPoint[]> =>
    request<EntryPoint[]>(`${BASE}/pipelines/entry-points?configPath=${encodeURIComponent(configPath)}`),

  smartDetect: (body: { rawInput: string; configPath?: string }): Promise<{
    suggested: { entryPointId: string; confidence: number; intentLabel: string }
    explanation: string
    detectedInputs: Record<string, unknown>
    clarificationQuestion?: string
  }> =>
    request(`${BASE}/runs/smart-detect`, {
      method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body)
    }),

  getRun: (runId: string): Promise<PipelineRun> =>
    request<PipelineRun>(`${BASE}/runs/${runId}`),

  getRunToolCalls: (runId: string): Promise<ToolCallEntry[]> =>
    request<ToolCallEntry[]>(`${BASE}/runs/${runId}/tool-calls`),

  listRuns: (filters?: RunFilters): Promise<PaginatedResponse<PipelineRunSummary>> => {
    const params = new URLSearchParams()
    if (filters?.status?.length) filters.status.forEach(s => params.append('status', s))
    if (filters?.pipelineName) params.set('pipelineName', filters.pipelineName)
    if (filters?.search) params.set('search', filters.search)
    if (filters?.from) params.set('from', filters.from)
    if (filters?.to) params.set('to', filters.to)
    if (filters?.page !== undefined) params.set('page', String(filters.page))
    if (filters?.size !== undefined) params.set('size', String(filters.size))
    return request<PaginatedResponse<PipelineRunSummary>>(`${BASE}/runs?${params}`)
  },

  cancelRun: (runId: string): Promise<void> =>
    request<void>(`${BASE}/runs/${runId}/cancel`, { method: 'POST' }),

  returnRun: (runId: string, body: { targetBlock: string; comment: string; configPath: string }) =>
    request<{ success: boolean; runId: string; targetBlock: string; status: string }>(
      `${BASE}/runs/${runId}/return`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  getRunStats: (): Promise<RunStats> =>
    request<RunStats>(`${BASE}/runs/stats`),

  submitApproval: (runId: string, decision: ApprovalDecision) =>
    request<Record<string, unknown>>(
      `${BASE}/runs/${runId}/approval`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(decision) }
    ),

  listPipelines: (): Promise<{ path: string; name: string; pipelineName?: string; description?: string; error?: string }[]> =>
    request(`${BASE}/pipelines`),

  getPipelineConfig: (configPath: string): Promise<PipelineConfigSettings> =>
    request<PipelineConfigSettings>(`${BASE}/pipelines/config?configPath=${encodeURIComponent(configPath)}`),

  savePipelineConfig: (configPath: string, settings: PipelineConfigSettings): Promise<{ saved: boolean }> =>
    request<{ saved: boolean }>(
      `${BASE}/pipelines/config?configPath=${encodeURIComponent(configPath)}`,
      { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(settings) }
    ),

  // Integrations
  listIntegrations: (): Promise<IntegrationConfig[]> =>
    request<IntegrationConfig[]>(`${BASE}/integrations`),

  createIntegration: (body: IntegrationConfig): Promise<IntegrationConfig> =>
    request<IntegrationConfig>(
      `${BASE}/integrations`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  updateIntegration: (id: number, body: Partial<IntegrationConfig>): Promise<IntegrationConfig> =>
    request<IntegrationConfig>(
      `${BASE}/integrations/${id}`,
      { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  deleteIntegration: (id: number): Promise<void> =>
    request<void>(`${BASE}/integrations/${id}`, { method: 'DELETE' }),

  testIntegration: (id: number): Promise<{ success: boolean; message: string }> =>
    request<{ success: boolean; message: string }>(
      `${BASE}/integrations/${id}/test`,
      { method: 'POST' }
    ),

  // Agent Profiles
  listAgentProfiles: (): Promise<AgentProfile[]> =>
    request<AgentProfile[]>(`${BASE}/agent-profiles`),

  createAgentProfile: (body: AgentProfile): Promise<AgentProfile> =>
    request<AgentProfile>(
      `${BASE}/agent-profiles`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  updateAgentProfile: (id: number, body: Partial<AgentProfile>): Promise<AgentProfile> =>
    request<AgentProfile>(
      `${BASE}/agent-profiles/${id}`,
      { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  deleteAgentProfile: (id: number): Promise<void> =>
    request<void>(`${BASE}/agent-profiles/${id}`, { method: 'DELETE' }),

  listAvailableSkills: (): Promise<SkillInfo[]> =>
    request<SkillInfo[]>(`${BASE}/agent-profiles/skills`),

  // Audit
  listAudit: (filters?: AuditFilters): Promise<PaginatedResponse<AuditEntry>> => {
    const params = new URLSearchParams()
    if (filters?.actor) params.set('actor', filters.actor)
    if (filters?.action) params.set('action', filters.action)
    if (filters?.targetType) params.set('targetType', filters.targetType)
    if (filters?.targetId) params.set('targetId', filters.targetId)
    if (filters?.outcome) params.set('outcome', filters.outcome)
    if (filters?.from) params.set('from', filters.from)
    if (filters?.to) params.set('to', filters.to)
    if (filters?.page !== undefined) params.set('page', String(filters.page))
    if (filters?.size !== undefined) params.set('size', String(filters.size))
    return request<PaginatedResponse<AuditEntry>>(`${BASE}/audit?${params}`)
  },

  // Admin — kill switch
  getKillSwitch: (): Promise<KillSwitchState> =>
    request<KillSwitchState>(`${BASE}/admin/kill-switch`),

  toggleKillSwitch: (body: { active: boolean; reason?: string; cancelActive?: boolean }): Promise<KillSwitchState> =>
    request<KillSwitchState>(
      `${BASE}/admin/kill-switch`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  // Cost
  getCostSummary: (from?: string, to?: string): Promise<CostSummary> => {
    const params = new URLSearchParams()
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    const qs = params.toString()
    return request<CostSummary>(`${BASE}/cost/summary${qs ? '?' + qs : ''}`)
  },

  // Projects
  listProjects: (): Promise<ProjectInfo[]> =>
    request<ProjectInfo[]>(`${BASE}/projects`),

  createProject: (body: { slug: string; displayName: string; description?: string; configDir?: string }) =>
    request<ProjectInfo>(
      `${BASE}/projects`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  updateProject: (slug: string, body: Partial<ProjectInfo>) =>
    request<ProjectInfo>(
      `${BASE}/projects/${slug}`,
      { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  deleteProject: (slug: string) =>
    request<{ success: boolean } | { error: string }>(
      `${BASE}/projects/${slug}`,
      { method: 'DELETE' }
    ),

  browseFs: (path?: string): Promise<{ path: string; parent: string; directories: string[]; root: string }> =>
    request(`${BASE}/fs/browse${path ? `?path=${encodeURIComponent(path)}` : ''}`),

  // Users (ADMIN)
  listUsers: (): Promise<UserInfo[]> =>
    request<UserInfo[]>(`${BASE}/users`),

  createUser: (body: CreateUserBody): Promise<UserInfo> =>
    request<UserInfo>(
      `${BASE}/users`,
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  updateUser: (id: number, body: UpdateUserBody): Promise<UserInfo> =>
    request<UserInfo>(
      `${BASE}/users/${id}`,
      { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }
    ),

  deleteUser: (id: number): Promise<{ success: boolean } | { error: string }> =>
    request<{ success: boolean } | { error: string }>(
      `${BASE}/users/${id}`,
      { method: 'DELETE' }
    ),
}
