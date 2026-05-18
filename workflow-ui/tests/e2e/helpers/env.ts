/**
 * Centralised env-var loader for E2E tests. Throws useful messages when a required
 * variable is missing, so failures surface at the point of use rather than mid-launch.
 *
 * Note: `alltokensApiKey` is lazy — call {@link requireAllTokensApiKey} only when
 * actually creating an AllTokens integration. The cheap `integrity.spec.ts` does not
 * need the key, so it should not be a precondition for the whole suite.
 */

export interface E2eEnv {
  uiBase: string
  apiBase: string
  pipelineExampleEnabled: boolean
  keepTempRepos: boolean
  youtrackToken?: string
  gitlabToken?: string
  githubToken?: string
}

let cached: E2eEnv | null = null

export function loadEnv(): E2eEnv {
  if (cached) return cached
  cached = {
    uiBase: process.env.WF_UI_BASE ?? 'http://localhost:5120',
    apiBase: process.env.WF_API_BASE ?? 'http://localhost:8020',
    pipelineExampleEnabled: process.env.E2E_PIPELINE_EXAMPLE === '1',
    keepTempRepos: process.env.E2E_KEEP !== '0',
    youtrackToken: process.env.YOUTRACK_TOKEN || undefined,
    gitlabToken: process.env.GITLAB_TOKEN || undefined,
    githubToken: process.env.GITHUB_TOKEN || undefined,
  }
  return cached
}

export function requireAllTokensApiKey(): string {
  const apiKey = process.env.ALLTOKENS_API_KEY
  if (!apiKey) {
    throw new Error(
      'ALLTOKENS_API_KEY is not set. Set it in your shell before running pipelines.spec.ts. ' +
        'See workflow-ui/tests/e2e/README.md for the full prerequisite list.',
    )
  }
  return apiKey
}
