import { randomBytes } from 'node:crypto';
import { authenticatedTest as test, expect } from './helpers/assert-clean-page';
import { CognitoAdmin, newCredentials, runtimeConfig, shouldDeleteUser } from './helpers/cognito-admin';

// No trace: the signup and login forms carry the dedicated user's password.
test.use({ storageState: { cookies: [], origins: [] }, trace: 'off' });
test.describe.configure({ mode: 'serial' });

const runId = process.env['E2E_RUN_ID']!;
const email = `e2e-${runId}-signup@example.com`;
const { password } = newCredentials(email);
const username = `e2e_su_${randomBytes(5).toString('hex')}`;
let created = false;

test.beforeEach(({}, testInfo) => test.skip(testInfo.project.name !== 'desktop', 'One real signup per run'));

test.afterAll(() => {
  if (created && shouldDeleteUser(email, runId)) new CognitoAdmin(runtimeConfig().cognito).deleteUser(email);
});

test('real Cognito signup, admin confirmation, login, pending username and logout', async ({ page }) => {
  await page.goto('/signup');
  await page.locator('#auth-username').fill(username);
  await expect(page.locator('[data-state="available"]')).toBeVisible();
  await page.locator('#auth-email').fill(email);
  await page.locator('#auth-password').fill(password);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  created = true;
  await expect(page.getByRole('heading', { name: 'Check your email', exact: true })).toBeVisible();
  await expect(page.getByText(email, { exact: true })).toBeVisible();

  new CognitoAdmin(runtimeConfig().cognito).confirmSignUp(email);

  await page.getByRole('link', { name: 'Back to log in', exact: true }).click();
  await page.locator('#auth-email').fill(email);
  await page.locator('#auth-password').fill(password);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await page.waitForURL(url => url.pathname === '/', { timeout: 30_000 });

  await page.goto('/settings');
  await expect(page.locator('#settings-username')).toHaveValue(username);
  await expect(page.getByText('Your username was generated for you.')).toHaveCount(0);

  await page.reload();
  await expect(page.locator('#settings-username')).toHaveValue(username);

  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  await page.waitForURL(url => url.pathname === '/');
  await page.goto('/me');
  await expect(page).toHaveURL(url => url.pathname === '/login' && url.searchParams.get('returnUrl') === '/me');
});
