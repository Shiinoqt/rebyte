import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { defineConfig, devices } from '@playwright/test';
import { runDirectory } from './e2e/helpers/cognito-admin';

process.env['E2E_RUN_ID'] ??= randomUUID();
const authDir = runDirectory(process.env['E2E_RUN_ID']);

export default defineConfig({
  testDir: './e2e',
  testIgnore: ['**/helpers/**', '**/fixtures/**', '**/demo-deployment.spec.ts'],
  workers: 1,
  retries: 0,
  use: { baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200', trace: 'off',
    screenshot: 'off', video: 'off' },
  webServer: { command: 'npm run start', url: 'http://localhost:4200', reuseExistingServer: !process.env['CI'] },
  projects: [
    { name: 'setup', testMatch: /global\.setup\.ts/, teardown: 'teardown', timeout: 180_000 },
    { name: 'teardown', testMatch: /global\.teardown\.ts/ },
    { name: 'desktop', testMatch: '**/*.spec.ts', dependencies: ['setup'], use: {
      ...devices['Desktop Chrome'], viewport: { width: 1280, height: 720 }, storageState: resolve(authDir, 'buyer.json'),
    } },
    { name: 'phone', testMatch: '**/*.spec.ts', dependencies: ['setup'], use: {
      ...devices['Pixel 7'], browserName: 'chromium', viewport: { width: 412, height: 839 },
      storageState: resolve(authDir, 'buyer.json'),
    } },
  ],
});
