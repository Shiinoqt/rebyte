import { test, expect } from '@playwright/test';
import { assertNoOverflow } from './helpers/assert-clean-page';
import { assertFreshFixtures } from './helpers/fixture-state';
import { fixtureOrders, readOrder, signedInActor, type Actor, type FixtureOrders } from './helpers/fixture-actors';

// Mutating steps consume the recorded paid orders. With E2E_STRICT_FIXTURES=1 the run demands fresh fixtures;
// otherwise each step runs only while its starting state still holds and otherwise asserts the reached state.
test.use({ trace: 'off' });
test.describe.configure({ mode: 'serial' });

const STRICT = process.env['E2E_STRICT_FIXTURES'] === '1';
const LONG_REVIEW = `Arrived well packed and exactly as described. ${'LongUnbrokenReviewWord'.repeat(12)} ` +
  'The seller answered quickly and shipped on time, so I would happily buy from them again. '.repeat(6).trim();
let fixtures: FixtureOrders;
let seller: Actor;
let buyer: Actor;

// The report card names the problem by its stage, so the expected heading follows the status the backend reports.
function problemHeading(status: string | undefined, seller: boolean): string {
  if (status === 'RESOLVED') return 'Resolved report';
  return seller ? 'The buyer reported a problem' : 'Reported problem';
}
const hardwareUrl = () => `/orders/${fixtures.hardwareId}`;
const softwareUrl = () => `/orders/${fixtures.softwareId}`;
const orderCall = (id: string, action: string) => (r: { url(): string; request(): { method(): string }; ok(): boolean }) =>
  r.request().method() === 'POST' && new URL(r.url()).pathname === `/orders/${id}/${action}` && r.ok();

test.beforeAll(async ({ browser }, testInfo) => {
  fixtures = fixtureOrders();
  if (STRICT) {
    assertFreshFixtures(await readOrder(fixtures.hardwareId, fixtures.buyer), await readOrder(fixtures.softwareId, fixtures.buyer));
  }
  [seller, buyer] = await Promise.all([signedInActor(browser, testInfo, fixtures.seller), signedInActor(browser, testInfo, fixtures.buyer)]);
  await buyer.context.grantPermissions(['clipboard-read', 'clipboard-write']);
});

test.afterAll(async () => {
  await Promise.all([seller?.context.close(), buyer?.context.close()]);
});

test.beforeEach(({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop' && !testInfo.title.includes('@phone'), 'Mutating lifecycle steps run on desktop only');
});

test('only the seller can ship the hardware fixture and the buyer never sees ship or receipt controls', async () => {
  const before = await readOrder(fixtures.hardwareId, fixtures.buyer);
  await buyer.page.goto(hardwareUrl());
  await expect(buyer.page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(buyer.page.getByRole('button', { name: 'Mark as shipped', exact: true })).toHaveCount(0);

  await seller.page.goto(hardwareUrl());
  await expect(seller.page.getByRole('heading', { level: 1 })).toBeVisible();
  if (before.status === 'PAID') {
    await seller.page.getByRole('button', { name: 'Mark as shipped', exact: true }).click();
    const dialog = seller.page.getByRole('dialog', { name: 'Mark as shipped', exact: true });
    await dialog.locator('#ship-carrier').selectOption('GLS');
    await dialog.locator('#ship-tracking').fill('E2E-TRACK-0001');
    const shipped = seller.page.waitForResponse(orderCall(fixtures.hardwareId, 'ship'));
    await dialog.getByRole('button', { name: 'Mark as shipped', exact: true }).click();
    await shipped;
    await expect(seller.page.getByText('Shipped', { exact: true }).first()).toBeVisible();
  }
  const after = await readOrder(fixtures.hardwareId, fixtures.buyer);
  // Plan 06-43 refunds this order through the admin queue, which ends in REFUNDED once payment confirms.
  expect(['SHIPPED', 'DELIVERED', 'REFUNDED']).toContain(after.status);
  expect(after.carrier).toBe('GLS');
  await seller.page.reload();
  const shipment = seller.page.getByRole('region', { name: 'Shipment' });
  await expect(shipment).toContainText('GLS');
  await expect(shipment).toContainText('E2E-TRACK-0001');
  await expect(seller.page.getByRole('button', { name: 'Confirm receipt', exact: true })).toHaveCount(0);
  seller.verify();
  buyer.verify();
});

test('the buyer confirms receipt of the shipped hardware order', async () => {
  const before = await readOrder(fixtures.hardwareId, fixtures.buyer);
  await buyer.page.goto(hardwareUrl());
  if (before.status === 'SHIPPED') {
    await buyer.page.getByRole('button', { name: 'Confirm receipt', exact: true }).click();
    const dialog = buyer.page.getByRole('dialog', { name: 'Confirm receipt?', exact: true });
    const delivered = buyer.page.waitForResponse(orderCall(fixtures.hardwareId, 'deliver'));
    await dialog.getByRole('button', { name: 'Confirm receipt', exact: true }).click();
    await delivered;
  }
  const reached = (await readOrder(fixtures.hardwareId, fixtures.buyer)).status;
  expect(['DELIVERED', 'REFUNDED']).toContain(reached);
  await buyer.page.reload();
  await expect(buyer.page.getByText(reached === 'DELIVERED' ? 'Delivered' : 'Refunded', { exact: true }).first()).toBeVisible();
  await expect(buyer.page.getByRole('button', { name: 'Confirm receipt', exact: true })).toHaveCount(0);
  buyer.verify();
});

test('the buyer reviews the delivered hardware order with a long comment', async () => {
  const before = await readOrder(fixtures.hardwareId, fixtures.buyer);
  await buyer.page.goto(hardwareUrl());
  if (!before.review) {
    await buyer.page.getByRole('radio', { name: '5 stars', exact: true }).click();
    await buyer.page.locator('#review-comment').fill(LONG_REVIEW);
    const posted = buyer.page.waitForResponse(orderCall(fixtures.hardwareId, 'review'));
    await buyer.page.getByRole('button', { name: 'Post review', exact: true }).click();
    await posted;
  }
  const after = await readOrder(fixtures.hardwareId, fixtures.buyer);
  expect(after.review?.rating).toBeGreaterThanOrEqual(1);
  await buyer.page.reload();
  const review = buyer.page.getByRole('heading', { name: 'Review', exact: true, level: 2 }).locator('..');
  await expect(review).toBeVisible();
  await expect(review.locator('p.prose')).toHaveText(after.review!.comment!);
  await assertNoOverflow(buyer.page);
  buyer.verify();
});

test('the buyer reports a problem on the hardware order and it stays open', async () => {
  const before = await readOrder(fixtures.hardwareId, fixtures.buyer);
  await buyer.page.goto(hardwareUrl());
  if (!before.problem) {
    await buyer.page.getByRole('button', { name: 'Report a problem', exact: true }).click();
    const dialog = buyer.page.getByRole('dialog', { name: 'Report a problem', exact: true });
    await dialog.locator('#problem-reason').selectOption('NOT_AS_DESCRIBED');
    await dialog.locator('#problem-description').fill('E2E lifecycle problem report on the hardware fixture, left open for admin resolution.');
    const reported = buyer.page.waitForResponse(orderCall(fixtures.hardwareId, 'problem'));
    await dialog.getByRole('button', { name: 'Send report', exact: true }).click();
    await reported;
  }
  const status = (await readOrder(fixtures.hardwareId, fixtures.buyer)).problem?.status;
  expect(STRICT ? ['OPEN'] : ['OPEN', 'REFUND_PENDING', 'RESOLVED']).toContain(status);
  await buyer.page.reload();
  await expect(buyer.page.getByRole('heading', { name: problemHeading(status, false), exact: true })).toBeVisible();
  buyer.verify();
});

test('the buyer reads the licence key from the page, copies it and requests a download URL', async () => {
  await buyer.page.goto(softwareUrl());
  const card = buyer.page.locator('app-licence-card');
  await expect(card.getByRole('heading', { name: 'Software licence', exact: true })).toBeVisible();
  await expect(card.getByRole('img', { name: 'Licence key hidden', exact: true })).toBeVisible();
  await card.getByRole('button', { name: 'Show key', exact: true }).click();
  const key = (await card.locator('app-copy-field code').textContent())?.trim() ?? '';
  // Assert on booleans only so a failure message can never print the key.
  expect(/^[A-Z0-9][A-Z0-9-]{14,62}[A-Z0-9]$/.test(key)).toBe(true);
  await card.getByRole('button', { name: 'Copy Licence key', exact: true }).click();
  await expect.poll(() => buyer.page.evaluate(async expected => (await navigator.clipboard.readText()) === expected, key)).toBe(true);

  await buyer.page.route(url => url.hostname.endsWith('amazonaws.com'), route => route.abort());
  await expect(card.getByRole('button', { name: 'Download', exact: true })).toBeEnabled();
  const policy = buyer.page.waitForResponse(r => r.request().method() === 'GET' && /\/versions\/[^/]+\/download$/.test(new URL(r.url()).pathname) && r.ok());
  const handoff = buyer.page.waitForRequest(r => r.isNavigationRequest() && new URL(r.url()).hostname.endsWith('amazonaws.com'));
  await card.getByRole('button', { name: 'Download', exact: true }).click();
  await policy;
  // The response body is gone once the page navigates, so the signed URL is checked on the navigation request.
  const target = new URL((await handoff).url());
  expect(target.protocol).toBe('https:');
  expect(target.hostname.endsWith('amazonaws.com')).toBe(true);
  await buyer.page.unroute(url => url.hostname.endsWith('amazonaws.com'));
  buyer.verify();
});

test('the buyer reviews the delivered software order', async () => {
  const before = await readOrder(fixtures.softwareId, fixtures.buyer);
  await buyer.page.goto(softwareUrl());
  if (!before.review) {
    await buyer.page.getByRole('radio', { name: '4 stars', exact: true }).click();
    await buyer.page.locator('#review-comment').fill('Licence key worked and the installer downloaded without trouble.');
    const posted = buyer.page.waitForResponse(orderCall(fixtures.softwareId, 'review'));
    await buyer.page.getByRole('button', { name: 'Post review', exact: true }).click();
    await posted;
  }
  expect((await readOrder(fixtures.softwareId, fixtures.buyer)).review?.rating).toBeGreaterThanOrEqual(1);
  buyer.verify();
});

test('the buyer reports a problem on the software order and it stays open', async () => {
  const before = await readOrder(fixtures.softwareId, fixtures.buyer);
  await buyer.page.goto(softwareUrl());
  if (!before.problem) {
    await buyer.page.getByRole('button', { name: 'Report a problem', exact: true }).click();
    const dialog = buyer.page.getByRole('dialog', { name: 'Report a problem', exact: true });
    await dialog.locator('#problem-reason').selectOption('LICENCE_NOT_WORKING');
    await dialog.locator('#problem-description').fill('E2E lifecycle problem report on the software fixture, left open for admin resolution.');
    const reported = buyer.page.waitForResponse(orderCall(fixtures.softwareId, 'problem'));
    await dialog.getByRole('button', { name: 'Send report', exact: true }).click();
    await reported;
  }
  // Admin resolution in plan 06-43 dismisses this problem.
  expect(['OPEN', 'RESOLVED']).toContain((await readOrder(fixtures.softwareId, fixtures.buyer)).problem?.status);
  buyer.verify();
});

test('both consumed fixture orders render read-only for buyer and seller without overflow @phone', async () => {
  for (const [actor, label] of [[buyer, 'buyer'], [seller, 'seller']] as const) {
    for (const url of [hardwareUrl(), softwareUrl()]) {
      await actor.page.goto(url);
      await expect(actor.page.getByRole('heading', { level: 1 })).toBeVisible();
      const status = (await readOrder(url === hardwareUrl() ? fixtures.hardwareId : fixtures.softwareId, fixtures.buyer)).problem?.status;
      await expect(actor.page.getByRole('heading', { name: problemHeading(status, label === 'seller'), exact: true })).toBeVisible();
      for (const control of ['Mark as shipped', 'Confirm receipt', 'Cancel order', 'Report a problem', 'Post review']) {
        await expect(actor.page.getByRole('button', { name: control, exact: true })).toHaveCount(0);
      }
      await assertNoOverflow(actor.page);
    }
    actor.verify();
  }
});
