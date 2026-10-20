import { randomBytes } from 'node:crypto';
import type { Browser, BrowserContext, Page, TestInfo } from '@playwright/test';
import { authenticatedTest as test, expect, monitorPage } from './helpers/assert-clean-page';
import { runtimeConfig } from './helpers/cognito-admin';
import { testData } from './helpers/test-data';
import { jpegFixture } from './helpers/upload-fixtures';

test.use({ trace: 'off' });

async function sellerPage(browser: Browser, testInfo: TestInfo): Promise<{ context: BrowserContext; page: Page; verify: () => void }> {
  const context = await browser.newContext({ ...testInfo.project.use, storageState: testData().storageStates.seller });
  const page = await context.newPage();
  const origins = [new URL(testInfo.project.use.baseURL!).origin, new URL(runtimeConfig().apiBaseUrl).origin];
  return { context, page, verify: monitorPage(page, origins, [f => f.kind === 'unexpected-public-authorization']) };
}

test('account tabs, deep links and the settings entry work with the keyboard', async ({ page }) => {
  for (const [tab, heading] of [['listings', 'Your listings'], ['library', 'Library'], ['reviews', 'Reviews']]) {
    await page.goto(`/me?tab=${tab}`);
    await expect(page.getByRole('heading', { name: heading, exact: true, level: 1 })).toBeVisible();
    await expect(page.locator(`[data-tab="${tab}"]`)).toBeVisible();
  }
  await page.goto('/me?tab=bogus');
  await expect(page).toHaveURL(url => url.pathname === '/me' && url.searchParams.get('tab') === 'listings');

  await page.goto('/me');
  const settings = page.getByRole('link', { name: 'Settings and profile', exact: true });
  await settings.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(url => url.pathname === '/settings');
  await expect(page.getByRole('heading', { name: 'Account settings', exact: true })).toBeVisible();
});

test('buyer saves profile text and the email preference and both survive a reload', async ({ page }) => {
  const location = `Turin ${Date.now()}`;
  // A generated user_xxxxxxxx handle is reserved, so the first save has to pick a real username.
  const username = `e2e_buyer_${randomBytes(6).toString('hex')}`;
  await page.goto('/settings');
  await page.locator('#settings-username').fill(username);
  await page.locator('#settings-location').fill(location);
  await page.locator('#settings-bio').fill('Buyer biography saved by the account e2e.');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByRole('status').getByText('Changes saved', { exact: true })).toBeVisible();

  const emailSwitch = page.locator('#settings-email-enabled');
  await expect(emailSwitch).toBeEnabled();
  const before = await emailSwitch.isChecked();
  const saved = page.waitForResponse(r => r.request().method() === 'PUT' && r.url().endsWith('/notifications/email-preferences') && r.ok());
  await emailSwitch.click();
  await saved;
  await page.reload();
  await expect(page.locator('#settings-username')).toHaveValue(username);
  await expect(page.locator('#settings-location')).toHaveValue(location);
  await expect(page.locator('#settings-bio')).toHaveValue('Buyer biography saved by the account e2e.');
  await expect(page.locator('#settings-email-enabled')).toBeChecked({ checked: !before });
});

test('avatar goes to S3 without gateway credentials, can be replaced and removal repeats safely', async ({ browser }, testInfo) => {
  const { context, page, verify } = await sellerPage(browser, testInfo);
  const api = new URL(runtimeConfig().apiBaseUrl).origin;
  const storage: { url: string; authorization: string | undefined }[] = [];
  const gateway: string[] = [];
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.origin === api) gateway.push(`${request.method()} ${url.pathname}`);
    else if (request.method() === 'POST' && url.hostname.includes('amazonaws.com')) {
      storage.push({ url: url.origin, authorization: request.headers()['authorization'] });
    }
  });
  try {
    await page.goto('/settings');
    const file = page.locator('#settings-avatar-file');
    const photo = page.getByRole('img', { name: 'Your profile photo', exact: true });
    const upload = async (name: 'red' | 'blue') => {
      const confirmed = page.waitForResponse(r => r.url().endsWith('/users/me/avatar/confirm') && r.ok());
      await file.setInputFiles(jpegFixture(name));
      await confirmed;
    };
    await upload('red');
    await expect(photo).toBeVisible();
    await expect(page.getByRole('progressbar')).toHaveCount(0);
    await upload('blue');
    expect(storage).toHaveLength(2);
    expect(storage.every(upload => upload.authorization === undefined && !upload.url.startsWith(api))).toBe(true);
    const sequence = gateway.filter(call => call.includes('/users/me/avatar'));
    expect(sequence).toEqual(['POST /users/me/avatar/upload-policy', 'POST /users/me/avatar/confirm',
      'POST /users/me/avatar/upload-policy', 'POST /users/me/avatar/confirm']);

    const stale = await context.newPage();
    await stale.goto('/settings');
    await expect(stale.getByRole('img', { name: 'Your profile photo', exact: true })).toBeVisible();
    // Toasts queue one at a time, so the DELETE response is the reliable signal.
    const removed = (p: Page) => p.waitForResponse(r => r.request().method() === 'DELETE' && r.url().endsWith('/users/me/avatar') && r.ok());
    const first = removed(page);
    await page.getByRole('button', { name: 'Remove', exact: true }).click();
    await first;
    await expect(photo).toHaveCount(0);
    const repeat = removed(stale);
    await stale.getByRole('button', { name: 'Remove', exact: true }).click();
    await repeat;
    await expect(stale.getByRole('img', { name: 'Your profile photo', exact: true })).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('button', { name: 'Upload photo', exact: true })).toBeVisible();
    verify();
  } finally { await context.close(); }
});

test('seller profile settings keep the developer state and logout closes the private route', async ({ browser }, testInfo) => {
  const { context, page, verify } = await sellerPage(browser, testInfo);
  try {
    await page.goto('/settings');
    await expect(page.getByText('Developer profile: active', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.waitForURL(url => url.pathname === '/');
    await page.goto('/settings');
    await expect(page).toHaveURL(url => url.pathname === '/login' && url.searchParams.get('returnUrl') === '/settings');
    verify();
  } finally { await context.close(); }
});
