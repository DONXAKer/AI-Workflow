import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'

const HERE = path.dirname(fileURLToPath(import.meta.url))

/**
 * Filesystem-side mirror of PipelineConfigLoader.listPipelinesWithSources().
 *
 * We need the pipeline+entry-point set at Playwright test-discovery time (sync),
 * so the loader is duplicated in TS rather than fetched at runtime — the
 * integrity.spec.ts test asserts the two sets stay in sync.
 *
 * Resolution rules (must mirror workflow-core PipelineConfigLoader.java):
 *   - Platform configs: <PLATFORM_CONFIG_DIR>/*.yaml  (max-depth 1)
 *   - Platform configs: <PLATFORM_CONFIG_DIR>/.ai-workflow/pipelines/*.yaml (depth 1)
 *   - Project configs:  <projectWorkingDir>/.ai-workflow/*.yaml (depth 1)
 *
 * Project-side enumeration is supported here for completeness; the default test
 * fixture (mini-target-repo) ships without a .ai-workflow folder, so only
 * platform configs surface unless an operator adds one.
 */

export interface PipelineEntryPoint {
  id: string
  name?: string
  description?: string
  fromBlock?: string
  requiresInput?: string
}

export interface EnumeratedPipeline {
  filename: string
  absolutePath: string
  source: 'platform' | 'project'
  pipelineName?: string
  description?: string
  entryPoints: PipelineEntryPoint[]
}

interface RawPipelineConfig {
  name?: string
  description?: string
  entry_points?: Array<{
    id?: string
    name?: string
    description?: string
    from_block?: string
    requires_input?: string
  }>
}

const REPO_ROOT_GUESS = path.resolve(HERE, '..', '..', '..', '..')

function resolvePlatformConfigDir(): string {
  const override = process.env.WF_PLATFORM_CONFIG_DIR
  if (override) return path.resolve(override)
  return path.join(REPO_ROOT_GUESS, 'workflow-core', 'config')
}

function listYamlFilesShallow(dir: string): string[] {
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return []
  return fs
    .readdirSync(dir)
    .filter((f) => /\.ya?ml$/i.test(f))
    .map((f) => path.join(dir, f))
}

function tryLoad(absPath: string): RawPipelineConfig | null {
  try {
    const text = fs.readFileSync(absPath, 'utf8')
    const doc = yaml.load(text) as RawPipelineConfig | null
    return doc && typeof doc === 'object' ? doc : null
  } catch {
    return null
  }
}

function toEnumerated(
  absPath: string,
  source: 'platform' | 'project',
): EnumeratedPipeline | null {
  const raw = tryLoad(absPath)
  if (!raw) return null
  const filename = path.basename(absPath)
  const entryPoints: PipelineEntryPoint[] = Array.isArray(raw.entry_points)
    ? raw.entry_points
        .filter((ep): ep is { id: string } => !!ep && typeof ep.id === 'string')
        .map((ep) => ({
          id: ep.id,
          name: ep.name,
          description: ep.description,
          fromBlock: ep.from_block,
          requiresInput: ep.requires_input,
        }))
    : []
  return {
    filename,
    absolutePath: absPath,
    source,
    pipelineName: raw.name,
    description: raw.description,
    entryPoints,
  }
}

export interface EnumerateOpts {
  /** Optional project workingDir — for project-source pipelines (under .ai-workflow). */
  projectWorkingDir?: string
  /** Override the platform config root (test-fixture friendly). */
  platformConfigDir?: string
}

export function enumeratePipelines(opts: EnumerateOpts = {}): EnumeratedPipeline[] {
  const platformDir = opts.platformConfigDir ?? resolvePlatformConfigDir()
  const platformAiWorkflow = path.join(platformDir, '.ai-workflow', 'pipelines')
  const platformPaths = [
    ...listYamlFilesShallow(platformDir),
    ...listYamlFilesShallow(platformAiWorkflow),
  ]
  const out: EnumeratedPipeline[] = []
  for (const p of platformPaths) {
    const item = toEnumerated(p, 'platform')
    if (item) out.push(item)
  }
  if (opts.projectWorkingDir) {
    const projectAi = path.join(opts.projectWorkingDir, '.ai-workflow')
    for (const p of listYamlFilesShallow(projectAi)) {
      const item = toEnumerated(p, 'project')
      if (item) out.push(item)
    }
  }
  out.sort((a, b) => {
    if (a.source !== b.source) return a.source === 'project' ? -1 : 1
    return a.filename.localeCompare(b.filename)
  })
  return out
}
