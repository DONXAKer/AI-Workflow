import { Page, Route } from '@playwright/test'
import { BlockRegistryEntry, PipelineConfigDto, ValidationResult } from '../../src/types'

export const SAMPLE_REGISTRY: BlockRegistryEntry[] = [
  {
    type: 'task_md_input',
    description: 'Парсит task.md',
    metadata: {
      label: 'Task.md input', category: 'input', phase: 'INTAKE', hasCustomForm: false,
      configFields: [
        { name: 'file_path', label: 'Путь', type: 'string', required: true,
          description: 'task.md path', hints: {}, level: 'essential' },
      ],
      outputs: [
        { name: 'feat_id', label: 'feat_id', type: 'string' },
        { name: 'title', label: 'title', type: 'string' },
        { name: 'body', label: 'body', type: 'string' },
        { name: 'acceptance', label: 'acceptance', type: 'string' },
      ],
      recommendedRank: 100,
    },
  },
  {
    type: 'shell_exec',
    description: 'Команда shell',
    metadata: {
      label: 'Shell exec', category: 'infra', phase: 'ANY', hasCustomForm: false,
      configFields: [
        { name: 'command', label: 'Команда', type: 'string', required: true,
          description: '...', hints: { multiline: true, monospace: true }, level: 'essential' },
        { name: 'working_dir', label: 'Working dir', type: 'string', required: false,
          description: '', hints: {}, level: 'essential' },
        { name: 'timeout_sec', label: 'Таймаут', type: 'number', defaultValue: 300,
          required: false, description: '', hints: {}, level: 'advanced' },
        { name: 'allow_nonzero_exit', label: 'Non-zero', type: 'boolean', defaultValue: false,
          required: false, description: '', hints: {}, level: 'advanced' },
      ],
    },
  },
  {
    type: 'agent_with_tools',
    description: 'Агент',
    metadata: {
      label: 'Agent (tool-use)', category: 'agent', phase: 'IMPLEMENT', hasCustomForm: true,
      configFields: [],
      outputs: [
        { name: 'final_text', label: 'final_text', type: 'string' },
        { name: 'iterations_used', label: 'iterations_used', type: 'number' },
        { name: 'total_cost_usd', label: 'total_cost_usd', type: 'number' },
      ],
      recommendedRank: 100,
    },
  },
  {
    type: 'orchestrator',
    description: 'Orchestrator',
    metadata: {
      label: 'Orchestrator', category: 'agent', phase: 'IMPLEMENT', hasCustomForm: false,
      configFields: [
        { name: 'mode', label: 'Mode', type: 'enum',
          required: false, description: '', hints: { values: ['plan', 'review'] }, level: 'essential' },
      ],
    },
  },
  {
    type: 'verify',
    description: 'Verify',
    metadata: {
      label: 'Verify', category: 'verify', phase: 'VERIFY', hasCustomForm: true,
      configFields: [],
    },
  },
]

export const SAMPLE_FEATURE_CONFIG: PipelineConfigDto = {
  name: 'feature',
  description: 'Sample feature pipeline',
  defaults: { agent: { model: 'deepseek/deepseek-chat-v3-0324' } },
  entry_points: [
    { id: 'implement', name: 'Implement', from_block: 'task_md', requires_input: 'task_file' },
  ],
  pipeline: [
    { id: 'task_md', block: 'task_md_input', depends_on: [], approval: false,
      config: { file_path: '${input.requirement}' }, enabled: true },
    { id: 'create_branch', block: 'shell_exec', depends_on: ['task_md'], approval: true,
      config: { working_dir: '/project', command: 'git checkout -b feat/x' }, enabled: true },
    { id: 'impl', block: 'agent_with_tools', depends_on: ['create_branch'], approval: false,
      config: { user_message: 'Implement', working_dir: '/project',
                allowed_tools: ['Read', 'Write', 'Edit'] }, enabled: true },
    { id: 'review', block: 'verify', depends_on: ['impl'], approval: false,
      verify: {
        subject: 'impl',
        on_fail: { action: 'loopback', target: 'impl', max_iterations: 2 },
      },
      enabled: true },
  ],
}

export const PIPELINE_PATH = '/project/.ai-workflow/pipelines/feature.yaml'

export interface MockEditorOptions {
  config?: PipelineConfigDto
  registry?: BlockRegistryEntry[]
  validateResult?: ValidationResult
  onSaveBody?: (body: PipelineConfigDto) => void
  saveValidationErrors?: ValidationResult
}

/** Sets up the mocks needed for the Pipeline Editor tests. */
export async function setupEditorMocks(page: Page, opts: MockEditorOptions = {}) {
  const config = opts.config ?? SAMPLE_FEATURE_CONFIG
  const registry = opts.registry ?? SAMPLE_REGISTRY
  let currentConfig: PipelineConfigDto = JSON.parse(JSON.stringify(config))

  // Empty integrations list (Settings tab fetches it)
  await page.route('**/api/integrations', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.route('**/api/pipelines', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        { path: PIPELINE_PATH, name: 'feature.yaml', pipelineName: config.name },
      ]),
    })
  })

  await page.route('**/api/blocks/registry', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(registry),
    })
  })

  await page.route(/\/api\/pipelines\/config\?configPath=.+/, async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(currentConfig),
      })
      return
    }
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON() as PipelineConfigDto
      opts.onSaveBody?.(body)
      if (opts.saveValidationErrors && !opts.saveValidationErrors.valid) {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'Invalid pipeline config',
            errors: opts.saveValidationErrors.errors,
          }),
        })
        return
      }
      currentConfig = body
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ saved: true, config: body }),
      })
      return
    }
    await route.fulfill({ status: 405 })
  })

  await page.route(/\/api\/pipelines\/validate\?configPath=.+/, async (route: Route) => {
    const result: ValidationResult = opts.validateResult ?? { valid: true, errors: [] }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(result),
    })
  })

  await page.route(/\/api\/pipelines\/new$/, async (route: Route) => {
    const body = route.request().postDataJSON() as { slug: string; displayName: string }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        path: `/project/.ai-workflow/pipelines/${body.slug}.yaml`,
        name: `${body.slug}.yaml`,
        pipelineName: body.displayName,
      }),
    })
  })
}
