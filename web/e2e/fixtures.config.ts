import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './fixtures', testMatch: '**/*.fixture.ts', workers: 1, retries: 0,
  timeout: 30 * 60_000,
  use: { baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200', headless: false,
    trace: 'off', screenshot: 'off', video: 'off' },
  webServer: { command: 'npm run start', cwd: '..', url: 'http://localhost:4200', reuseExistingServer: true },
  projects: [{ name: 'paid-order-fixtures', use: {
    ...devices['Desktop Chrome'], viewport: { width: 1280, height: 720 },
  } }],
});
