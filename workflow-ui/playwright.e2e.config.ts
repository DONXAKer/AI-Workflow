import { defineConfig, devices } from '@playwright/test'

/**
 * E2E config — runs pipeline flows against a real docker-compose backend with AllTokens.
 *
 * Prerequisites (operator-managed; not started by Playwright):
 *   - docker compose up -d  (backend :8020, ui :5120)
 *   - ALLTOKENS_API_KEY in env
 *
 * Spec layout:
 *   tests/e2e/flows/pipelines.spec.ts  — data-driven over all platform pipelines
 *   tests/e2e/flows/integrity.spec.ts  — FS-vs-API consistency check
 *
 * Outputs:
 *   workflow-ui/playwright-report-e2e/      — HTML report
 *   workflow-ui/test-results/e2e-flows/     — explicit named screenshots from helpers/screenshot.ts
 *   workflow-ui/test-results/e2e/           — videos, traces on failure
 */
export default defineConfig({
  testDir: './tests/e2e/flows',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60 * 60 * 1000,
  expect: { timeout: 30_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report-e2e', open: 'never' }],
  ],
  use: {
    baseURL: process.env.WF_UI_BASE ?? 'http://localhost:5120',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 30_000,
    navigationTimeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  outputDir: 'test-results/e2e',
})
