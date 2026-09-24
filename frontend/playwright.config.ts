import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests. Default: an isolated stack — a fresh `enterprise_admin_e2e` schema (least-privilege users), the
 * backend on :8082 and the production build of the SPA on :4202 with its real CSP headers (`npm run e2e`, Docker MySQL
 * running). With E2E_BASE_URL set (plus E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD), no servers are started and the suite
 * runs against that URL — e.g. the production nginx stack (see docs/DEPLOYMENT.md). Serial on purpose: the tests
 * share one deterministic database.
 */
const external = process.env['E2E_BASE_URL'];
// CI uses Playwright's bundled Chromium (installed per run); locally the installed Google Chrome.
const channel = process.env['E2E_BROWSER_CHANNEL'] ?? (process.env['CI'] ? undefined : 'chrome');

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  outputDir: 'e2e-results',
  use: {
    baseURL: external ?? 'http://localhost:4202',
    channel,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'setup', testMatch: /seed\.setup\.ts/ },
    { name: 'chrome', testIgnore: /seed\.setup\.ts/, dependencies: ['setup'], use: { ...devices['Desktop Chrome'], channel } },
  ],
  webServer: external ? undefined : [
    {
      command: 'node e2e/start-backend.mjs',
      url: 'http://localhost:8082/api/health',
      timeout: 240_000,
      reuseExistingServer: !process.env['CI'],
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npx ng serve --configuration production --port 4202 --proxy-config e2e/proxy.e2e.json',
      url: 'http://localhost:4202',
      timeout: 180_000,
      reuseExistingServer: !process.env['CI'],
    },
  ],
});
