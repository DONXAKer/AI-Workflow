import type { Page } from '@playwright/test'
import { primeCsrf } from './api-client'

const DEFAULT_USERNAME = 'admin'
const DEFAULT_PASSWORD = 'admin'

/**
 * Logs in as ADMIN via the real LoginPage. Idempotent — if /api/auth/me already
 * returns the admin user, navigation skips login and goes straight to root.
 */
export async function loginAsAdmin(
  page: Page,
  user: string = DEFAULT_USERNAME,
  pass: string = DEFAULT_PASSWORD,
): Promise<void> {
  await primeCsrf(page)
  await page.goto('/login')
  // If session is already valid the SPA bounces /login → / immediately.
  if (!/\/login$/.test(page.url())) return
  await page.locator('#username').fill(user)
  await page.locator('#password').fill(pass)
  await Promise.all([
    page.waitForURL((url) => !/\/login$/.test(url.pathname), { timeout: 30_000 }),
    page.getByRole('button', { name: /войти/i }).click(),
  ])
}
