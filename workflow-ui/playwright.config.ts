import { defineConfig, devices } from '@playwright/test'

/**
 * UI tests run against `vite preview` serving a production build.
 * They intercept /api calls with route fulfillment, so no backend is required.
 *
 * E2E tests (tests/e2e/*) should be run separately with the backend up —
 * they are not included in the default test run.
 */
export default defineConfig({
  testDir: './tests',
  testIgnore: ['**/e2e/**'],
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'on-first-retry',
    // Capture a screenshot after every test so PRs/CI have visual evidence of what each
    // test verified. Failed tests additionally get the failure-state screenshot.
    screenshot: 'on',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run build && npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
