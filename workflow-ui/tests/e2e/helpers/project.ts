import type { Page } from '@playwright/test'
import { apiGet, apiPost, apiPut } from './api-client'

export interface EnsureProjectArgs {
  slug: string
  displayName?: string
  workingDir: string
  configDir?: string
}

interface ProjectSummary {
  slug: string
  displayName?: string
  workingDir?: string
  configDir?: string
  defaultProvider?: string | null
}

/**
 * Creates or refreshes a Project for E2E.
 *
 * - If the slug exists already, PUT it with the current workingDir (idempotent).
 * - Otherwise POST a new Project (requires ADMIN — admin/admin satisfies this).
 *
 * Returns the slug (same as input — kept as a method shape for readability).
 */
export async function ensureProject(page: Page, args: EnsureProjectArgs): Promise<string> {
  const body: Record<string, unknown> = {
    slug: args.slug,
    displayName: args.displayName ?? `E2E ${args.slug}`,
    workingDir: args.workingDir,
  }
  if (args.configDir) body.configDir = args.configDir
  const existing = await apiGet<ProjectSummary | null>(page, `/api/projects/${args.slug}`, {
    expectOk: false,
  }).catch(() => null)
  if (existing && (existing as { slug?: string }).slug) {
    await apiPut(page, `/api/projects/${args.slug}`, body, { projectSlug: args.slug })
  } else {
    await apiPost(page, '/api/projects', body, { projectSlug: args.slug })
  }
  return args.slug
}

/**
 * Sets the project's defaultProvider to ALLTOKENS via PUT /api/projects/{slug}.
 * Also navigates the UI to the Settings tab and screenshots it — the screenshot
 * step is the caller's responsibility (this helper just performs the change).
 */
export async function setDefaultProviderAllTokens(page: Page, slug: string): Promise<void> {
  await apiPut(
    page,
    `/api/projects/${slug}`,
    { defaultProvider: 'ALLTOKENS' },
    { projectSlug: slug },
  )
}

/**
 * Navigates the UI to the project settings tab — used purely to set up a
 * screenshot capture after setDefaultProviderAllTokens has run via API.
 */
export async function visitProjectSettings(page: Page, slug: string): Promise<void> {
  await page.goto(`/projects/${slug}/settings`)
  await page.waitForLoadState('domcontentloaded')
}
