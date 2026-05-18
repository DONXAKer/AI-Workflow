import type { Page } from '@playwright/test'
import { apiGet, apiPost } from './api-client'
import { requireAllTokensApiKey } from './env'

interface IntegrationSummary {
  id?: number
  name?: string
  type?: string
  baseUrl?: string
  isDefault?: boolean
}

const NAME = 'alltokens-e2e'
const DISPLAY = 'AllTokens (E2E)'
const BASE_URL = 'https://api.alltokens.ru/api/v1'

/**
 * Ensures an AllTokens integration exists for the given project. Idempotent —
 * skips creation if one is already configured under this project slug.
 *
 * Returns true if a new integration was created, false if reused.
 */
export async function ensureAllTokensIntegration(
  page: Page,
  projectSlug: string,
): Promise<boolean> {
  const apiKey = requireAllTokensApiKey()
  const list = await apiGet<IntegrationSummary[]>(page, '/api/integrations', {
    projectSlug,
  })
  const existing = Array.isArray(list) ? list.find((i) => i?.type === 'ALLTOKENS') : null
  if (existing) return false
  await apiPost(
    page,
    '/api/integrations',
    {
      name: `${NAME}-${projectSlug}`,
      type: 'ALLTOKENS',
      displayName: DISPLAY,
      baseUrl: BASE_URL,
      token: apiKey,
      isDefault: true,
    },
    { projectSlug },
  )
  return true
}

/**
 * Navigates UI to the project's Integrations tab. Used by the spec to capture
 * a screenshot of the populated row — the actual creation is done via API in
 * ensureAllTokensIntegration above.
 */
export async function visitIntegrationsTab(page: Page, projectSlug: string): Promise<void> {
  await page.goto(`/projects/${projectSlug}/integrations`)
  await page.waitForLoadState('domcontentloaded')
}
