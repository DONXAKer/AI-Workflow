import type { Page, APIResponse } from '@playwright/test'
import { loadEnv } from './env'

/**
 * Tiny wrapper around page.request that mirrors what src/services/api.ts does
 * in the SPA: reads XSRF-TOKEN cookie, sets X-XSRF-TOKEN + X-Project-Slug headers.
 *
 * Use this from helpers when you need to call backend APIs directly (e.g. polling
 * run status, posting approval). UI form interactions go through page.click /
 * page.fill instead — those auto-attach headers via the running SPA's api.ts.
 */

async function readXsrfToken(page: Page): Promise<string | null> {
  const cookies = await page.context().cookies()
  const match = cookies.find((c) => c.name === 'XSRF-TOKEN')
  return match ? decodeURIComponent(match.value) : null
}

function buildUrl(path: string): string {
  const env = loadEnv()
  if (/^https?:\/\//.test(path)) return path
  const base = env.apiBase.replace(/\/$/, '')
  const rel = path.startsWith('/') ? path : `/${path}`
  return `${base}${rel}`
}

async function buildHeaders(
  page: Page,
  projectSlug?: string,
  extra?: Record<string, string>,
): Promise<Record<string, string>> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    ...extra,
  }
  const xsrf = await readXsrfToken(page)
  if (xsrf) headers['X-XSRF-TOKEN'] = xsrf
  if (projectSlug) headers['X-Project-Slug'] = projectSlug
  return headers
}

export async function primeCsrf(page: Page): Promise<void> {
  await page.request.get(buildUrl('/api/auth/me'))
}

export interface ApiOpts {
  projectSlug?: string
  headers?: Record<string, string>
  expectOk?: boolean
}

async function asJson<T>(res: APIResponse, path: string, expectOk: boolean): Promise<T> {
  if (expectOk && !res.ok()) {
    const body = await res.text().catch(() => '')
    throw new Error(`API ${res.status()} for ${path}: ${body.slice(0, 500)}`)
  }
  const text = await res.text()
  if (!text) return undefined as unknown as T
  try {
    return JSON.parse(text) as T
  } catch {
    return text as unknown as T
  }
}

export async function apiGet<T = unknown>(
  page: Page,
  path: string,
  opts: ApiOpts = {},
): Promise<T> {
  const headers = await buildHeaders(page, opts.projectSlug, opts.headers)
  const res = await page.request.get(buildUrl(path), { headers })
  return asJson<T>(res, path, opts.expectOk !== false)
}

export async function apiPost<T = unknown>(
  page: Page,
  path: string,
  body: unknown,
  opts: ApiOpts = {},
): Promise<T> {
  const headers = await buildHeaders(page, opts.projectSlug, opts.headers)
  const res = await page.request.post(buildUrl(path), { headers, data: body })
  return asJson<T>(res, path, opts.expectOk !== false)
}

export async function apiPut<T = unknown>(
  page: Page,
  path: string,
  body: unknown,
  opts: ApiOpts = {},
): Promise<T> {
  const headers = await buildHeaders(page, opts.projectSlug, opts.headers)
  const res = await page.request.put(buildUrl(path), { headers, data: body })
  return asJson<T>(res, path, opts.expectOk !== false)
}
