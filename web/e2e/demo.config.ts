import {defineConfig} from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'demo-deployment.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: {timeout: 30_000},
  reporter: [['line']],
  outputDir: '../test-results/demo',
  use: {
    browserName: 'chromium',
    baseURL: 'https://rebyte.tech',
    viewport: {width: 1280, height: 800},
    headless: process.env['DEMO_CARD_HANDOFF'] !== 'true',
    ignoreHTTPSErrors: false,
    storageState: {cookies: [], origins: []},
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
});
