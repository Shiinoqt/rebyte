import { randomUUID } from 'node:crypto';
import { test, type Page } from '@playwright/test';
import { CognitoAdmin, loginThroughSpa, newCredentials, runtimeConfig, type Credentials } from '../helpers/cognito-admin';
import { GatewaySeed, waitForOrder } from '../helpers/api-seed';
import { readFixtureEnv, writeFixtureEnv, type FixtureKey } from '../helpers/fixture-env';

// Run with AWS_PROFILE=rebyte and npm --prefix web run e2e:fixtures; approve each sandbox payment in the headed browser.
function fixtureUser(admin: CognitoAdmin, role: 'buyer' | 'seller'): Credentials {
  const env = readFixtureEnv();
  const emailKey = `E2E_FIXTURE_${role.toUpperCase()}_EMAIL` as FixtureKey;
  const passwordKey = `E2E_FIXTURE_${role.toUpperCase()}_PASSWORD` as FixtureKey;
  if (env[emailKey] || env[passwordKey]) {
    if (!env[emailKey]?.startsWith('e2e-fixture-') || !env[passwordKey]) {
      throw new Error('Incomplete or invalid persistent fixture credentials');
    }
    return { email: env[emailKey]!, password: env[passwordKey]! };
  }
  const credentials = newCredentials(`e2e-fixture-${randomUUID()}-${role}@example.com`);
  try {
    admin.createUser(credentials, role);
    writeFixtureEnv({ [emailKey]: credentials.email, [passwordKey]: credentials.password });
  } catch {
    try { admin.deleteUser(credentials.email); } catch { /* Keep the original redacted setup failure. */ }
    throw new Error('Persistent fixture user creation failed');
  }
  return credentials;
}

async function existingOrder(gateway: GatewaySeed, id: string | undefined, token: string,
  status: 'PAID' | 'DELIVERED'): Promise<string | undefined> {
  if (!id) return undefined;
  const order = await gateway.request<{ status: string; review?: unknown; problem?: unknown }>(
    'GET', `/orders/${encodeURIComponent(id)}`, token);
  // Matches assertFreshFixtures: a reviewed or reported order is consumed even while its status still fits.
  return order.status === status && !order.review && !order.problem ? id : undefined;
}

async function checkout(page: Page, listingId: string, hardware: boolean): Promise<string> {
  await page.goto(`/checkout/${encodeURIComponent(listingId)}`);
  if (hardware) {
    await page.locator('#checkout-recipient').fill('E2E Fixture Buyer');
    await page.locator('#checkout-street').fill('Via Roma 1');
    await page.locator('#checkout-city').fill('Roma');
    await page.locator('#checkout-postal-code').fill('00100');
    await page.locator('#checkout-country').selectOption('IT');
  }
  let orderId: string | undefined;
  const ordersPath = (url: URL) => url.pathname === '/orders';
  // The SPA redirects to PayPal as soon as the response lands, and Chromium then discards its body,
  // so the order id is read in the runner before the page receives the response.
  await page.route(ordersPath, async route => {
    if (route.request().method() !== 'POST') return route.fallback();
    const response = await route.fetch();
    if (response.ok()) {
      const body = await response.json().catch(() => undefined);
      if (typeof body?.orderId === 'string' && body.orderId) orderId = body.orderId;
    }
    await route.fulfill({ response });
  });
  await page.getByRole('button', { name: /^Pay with PayPal/ }).click();
  await page.waitForURL(url => url.hostname === 'www.sandbox.paypal.com' || url.hostname === 'sandbox.paypal.com');
  await page.unroute(ordersPath);
  if (!orderId) throw new Error('Fixture checkout did not create an order');
  await page.waitForURL(url => url.pathname === '/checkout/return', { timeout: 10 * 60_000 });
  return orderId;
}

test('create reusable PAID hardware and DELIVERED software orders with human sandbox approvals', async ({ page }) => {
  const config = runtimeConfig();
  const admin = new CognitoAdmin(config.cognito);
  const buyer = fixtureUser(admin, 'buyer');
  const seller = fixtureUser(admin, 'seller');
  const buyerToken = await admin.accessToken(buyer);
  const sellerToken = await admin.accessToken(seller);
  const gateway = new GatewaySeed(config.apiBaseUrl);
  const env = readFixtureEnv();
  let hardwareOrder = await existingOrder(gateway, env.E2E_FIXTURE_HW_PAID_ORDER_ID, buyerToken, 'PAID');
  let softwareOrder = await existingOrder(gateway, env.E2E_FIXTURE_SW_DELIVERED_ORDER_ID, buyerToken, 'DELIVERED');
  if (hardwareOrder && softwareOrder) return;

  const runId = randomUUID();
  const seedAdmin = newCredentials(`e2e-${runId}-admin@example.com`);
  try {
    admin.createUser(seedAdmin, 'admin');
    const seed = await gateway.seed(runId, await admin.accessToken(seedAdmin), sellerToken);
    await gateway.artifact(seed.softwareListingId, sellerToken);
    await loginThroughSpa(page, buyer);
    if (!hardwareOrder) {
      hardwareOrder = await checkout(page, seed.hardwareListingId, true);
      await waitForOrder(gateway, hardwareOrder, buyerToken, 'PAID');
    }
    if (!softwareOrder) {
      softwareOrder = await checkout(page, seed.softwareListingId, false);
      await waitForOrder(gateway, softwareOrder, buyerToken, 'DELIVERED');
    }
    writeFixtureEnv({ E2E_FIXTURE_HW_PAID_ORDER_ID: hardwareOrder,
      E2E_FIXTURE_SW_DELIVERED_ORDER_ID: softwareOrder });
  } finally { admin.deleteUser(seedAdmin.email); }
});
