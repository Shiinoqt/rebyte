import { test, expect, type Locator, type Page, type Request } from '@playwright/test';
import { assertNoOverflow } from './helpers/assert-clean-page';
import { runtimeConfig } from './helpers/cognito-admin';
import { fixtureOrders, readOrder } from './helpers/fixture-actors';
import { assertOpenProblems } from './helpers/fixture-state';
import { testData } from './helpers/test-data';
import { openSession, type Session } from './helpers/two-session';

// The run's seller (an active developer) and admin drive the S15 release page and the S14 admin pages. The two OPEN
// problems plan 06-42 left on the fixture orders are resolved here: with E2E_STRICT_FIXTURES=1 both must start OPEN,
// otherwise each resolution runs only while its starting state holds and else asserts the state already reached.
test.use({ trace: 'off' });
test.describe.configure({ mode: 'serial', timeout: 180_000 });

const STRICT = process.env['E2E_STRICT_FIXTURES'] === '1';
const gateway = new URL(runtimeConfig().apiBaseUrl).origin;
const escape = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
let seller: Session;
let admin: Session;
let version = '';
let projectName = '';
let tag = '';

const api = (request: Request) => ({ method: request.method(), path: new URL(request.url()).pathname });
const post = (page: Page, matcher: RegExp | string) => page.waitForResponse(r => r.request().method() === 'POST' &&
  (typeof matcher === 'string' ? new URL(r.url()).pathname === matcher : matcher.test(new URL(r.url()).pathname)));
const read = (id: string) => readOrder(id, fixtureOrders().buyer);

test.beforeAll(async ({ browser }, testInfo) => {
  projectName = testInfo.project.name;
  tag = `[e2e] ${testData().runId} ${projectName}`;
  const states = testData().storageStates;
  [seller, admin] = await Promise.all([openSession(browser, testInfo, states.seller), openSession(browser, testInfo, states.admin)]);
});

test.afterAll(async () => { await Promise.all([seller?.context.close(), admin?.context.close()]); });

test.describe('developer releases', () => {
  const releases = () => `/listings/${testData().softwareListingId}/releases`;

  test('a developer releases a higher version after every invalid, repeated and lower number is refused with mapped copy', async () => {
    const page = seller.page;
    await page.goto(releases());
    await expect(page.getByRole('heading', { name: 'Software releases', level: 1 })).toBeVisible();
    await expect(page.locator('.version-card').first()).toBeVisible();
    const latest = (await page.locator('.version-card h2').allInnerTexts()).map(text => text.replace(/^v/, ''))
      .map(text => Number(text.split('.')[1] ?? 0)).sort((a, b) => b - a)[0];
    version = `1.${latest + 1}`;

    await page.getByRole('button', { name: 'Release a new version', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Release a new version' });
    const refused = async (value: string, copy: RegExp) => {
      await dialog.locator('#version-number').fill(value);
      await dialog.getByRole('button', { name: 'Review release', exact: true }).click();
      await expect(dialog.locator('#version-error')).toHaveText(copy);
    };
    await refused('not-a-version', /Use 1 to 8 numeric parts/);
    await refused('1.0', /This version already exists/);
    await refused('1.0.0', /Trailing zeros count as the same version/);
    await refused('0.5', /higher than every existing release/);
    await refused('1.1234567890', /Use 1 to 8 numeric parts/);

    const calls: string[] = [];
    page.on('request', request => { if (request.method() !== 'GET' && new URL(request.url()).origin === gateway) calls.push(api(request).method + ' ' + api(request).path); });
    await dialog.locator('#version-number').fill(version);
    await dialog.locator('#release-notes').fill(`Release notes ${tag}`);
    await dialog.getByRole('button', { name: 'Review release', exact: true }).click();
    const confirm = page.getByRole('dialog', { name: `Release v${version}?` });
    await expect(confirm).toContainText('This release is permanent');
    const released = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === `/listings/${testData().softwareListingId}/versions`);
    await confirm.getByRole('button', { name: 'Release version', exact: true }).click();
    const response = await released;
    expect(response.status()).toBeLessThan(300);
    expect(response.request().postDataJSON()).toEqual({ number: version, notes: `Release notes ${tag}` });
    expect(calls).toEqual([`POST /listings/${testData().softwareListingId}/versions`]);
    await expect(page.getByRole('status').filter({ hasText: `Version ${version} released.` })).toBeVisible();
    const card = page.locator('.version-card', { has: page.getByRole('heading', { name: `v${version}`, exact: true }) });
    await expect(card).toContainText('Latest release');
    await expect(card).toContainText('No installer yet');
    await assertNoOverflow(page);
    seller.verify();
  });

  test('a developer attaches an installer straight to storage and an empty file is refused first', async () => {
    const page = seller.page;
    const card = page.locator('.version-card', { has: page.getByRole('heading', { name: `v${version}`, exact: true }) });
    const picker = page.locator(`input[aria-label="Installer for v${version}"]`);
    await picker.setInputFiles({ name: 'empty.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(0) });
    await expect(card.getByRole('alert')).toHaveText('Choose a file between 1 byte and 100 MiB.');
    await expect(card.getByRole('button', { name: 'Choose another file', exact: true })).toBeVisible();

    const gatewayCalls: string[] = [];
    const storage: { authorization: string | undefined; origin: string }[] = [];
    const onRequest = (request: Request) => {
      const url = new URL(request.url());
      if (url.origin === gateway && request.method() === 'POST') gatewayCalls.push(url.pathname);
      else if (request.method() === 'POST' && url.hostname.endsWith('amazonaws.com')) storage.push({ authorization: request.headers()['authorization'], origin: url.origin });
    };
    page.on('request', onRequest);
    await picker.setInputFiles({ name: 'installer.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(4096, 5) });
    const confirmed = page.waitForResponse(r => r.url().endsWith(`/versions/${version}/artifact/confirm`) && r.ok());
    await card.getByRole('button', { name: 'Upload installer', exact: true }).click();
    await confirmed;
    page.off('request', onRequest);
    await expect(card.getByText('Installer attached', { exact: true })).toBeVisible();
    const base = `/listings/${testData().softwareListingId}/versions/${version}`;
    expect(gatewayCalls).toEqual([`${base}/artifact-upload-policy`, `${base}/artifact/confirm`]);
    expect(storage).toHaveLength(1);
    expect(storage[0].authorization).toBeUndefined();
    expect(storage[0].origin).not.toBe(gateway);
    seller.verify();
  });

  test('a visitor who does not own the listing sees no release controls', async ({ page }) => {
    await page.goto(releases());
    await expect(page.getByRole('heading', { name: 'Release management is unavailable', level: 2 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Release a new version' })).toHaveCount(0);
  });
});

test.describe('admin access', () => {
  for (const path of ['/admin/problems', '/admin/categories', '/admin/operations']) {
    test(`${path} is closed to a signed-in buyer and to an anonymous visitor`, async ({ page, browser }, testInfo) => {
      await page.goto(path);
      await page.waitForURL(url => url.pathname === '/');
      await expect(page.getByRole('heading', { name: /^(Problems|Categories|Operations)$/, level: 1 })).toHaveCount(0);

      const anonymous = await browser.newContext({ ...testInfo.project.use, storageState: { cookies: [], origins: [] } });
      try {
        const visitor = await anonymous.newPage();
        await visitor.goto(path);
        await visitor.waitForURL(url => url.pathname === '/login');
        expect(new URL(visitor.url()).searchParams.get('returnUrl')).toBe(path);
      } finally { await anonymous.close(); }
    });
  }
});

test.describe('admin categories', () => {
  const tree = (page: Page) => page.getByRole('navigation', { name: 'Category tree' });

  async function choose(page: Page, name: string): Promise<void> {
    const button = tree(page).getByRole('button', { name, exact: true });
    const option = page.locator('#selected-category option', { hasText: new RegExp(`^${escape(name)}$`) });
    // The tree is refetched after a create, so the new row is awaited before choosing how to select it.
    await expect(button.or(option).first()).toBeAttached();
    if (await button.isVisible()) { await button.click(); return; }
    await page.locator('#selected-category').selectOption(await option.evaluate(element => (element as HTMLOptionElement).value));
  }

  test('an admin adds a category and an enum attribute, sees every refusal, and retires a value', async () => {
    const { page } = admin;
    await page.goto('/admin/categories');
    await expect(page.getByRole('heading', { name: 'Categories', level: 1 })).toBeVisible();
    // The run's own Hardware category already holds a listing, so the fixed root is the parent that may take children.
    const parent = 'Hardware';
    const child = `${tag} category`;
    await choose(page, parent);
    await expect(page.getByRole('heading', { name: parent, level: 2 })).toBeVisible();

    await page.getByRole('button', { name: 'Add category', exact: true }).click();
    // The dialog's accessible name changes from Add to Confirm when the review step opens.
    const editor = page.locator('dialog[open]');
    await editor.getByRole('button', { name: 'Review category', exact: true }).click();
    await expect(editor.locator('#category-name-error')).toHaveText('Enter a category name.');
    await editor.locator('#category-name').fill('Gadgets');
    await editor.locator('#category-parent').selectOption('');
    await editor.getByRole('button', { name: 'Review category', exact: true }).click();
    await expect(editor.locator('#category-name-error')).toHaveText('Root categories must be named Hardware or Software.');

    await editor.locator('#category-name').fill(child);
    await editor.locator('#category-parent').selectOption({ label: parent });
    await editor.getByRole('button', { name: 'Review category', exact: true }).click();
    await expect(editor).toContainText(child);
    await expect(editor).toContainText('Categories cannot be deleted or moved.');
    const created = post(page, '/categories');
    await editor.getByRole('button', { name: 'Add category', exact: true }).click();
    const createdResponse = await created;
    expect(createdResponse.status()).toBeLessThan(300);
    expect((createdResponse.request().postDataJSON() as { name: string }).name).toBe(child);
    await expect(editor).toHaveCount(0);

    await choose(page, `${parent} / ${child}`);
    await expect(page.getByRole('heading', { name: child, level: 2 })).toBeVisible();
    await expect(page.getByText('No attributes defined yet.')).toBeVisible();
    await page.getByRole('button', { name: 'Add attribute', exact: true }).click();
    const attribute = page.locator('dialog[open]');
    await attribute.locator('#attribute-name').fill('edition');
    await attribute.getByRole('button', { name: 'Review attribute', exact: true }).click();
    await expect(attribute.locator('#values-error')).toHaveText('Add at least one nonblank value.');
    for (const value of ['Alpha', 'alpha']) {
      await attribute.locator('#allowed-value').fill(value);
      await attribute.getByRole('button', { name: 'Add value', exact: true }).click();
    }
    await attribute.getByRole('button', { name: 'Review attribute', exact: true }).click();
    await expect(attribute.locator('#values-error')).toHaveText('Each allowed value must be unique.');
    await attribute.getByRole('button', { name: 'Remove alpha', exact: true }).click();
    await attribute.locator('#allowed-value').fill('Beta');
    await attribute.getByRole('button', { name: 'Add value', exact: true }).click();
    await attribute.getByRole('checkbox', { name: 'Required' }).check();
    await attribute.getByRole('button', { name: 'Review attribute', exact: true }).click();
    const defined = post(page, /^\/categories\/[\w-]+\/attributes$/);
    await attribute.getByRole('button', { name: 'Add attribute', exact: true }).click();
    const definedResponse = await defined;
    expect(definedResponse.request().postDataJSON()).toEqual({ name: 'edition', type: 'ENUM', required: true, allowedValues: ['Alpha', 'Beta'], unit: null });

    const row = page.locator('.attribute-row', { has: page.getByRole('heading', { name: 'edition', level: 3 }) });
    await expect(row).toContainText('ENUM');
    await expect(row).toContainText('Required');
    await row.getByRole('button', { name: 'Retire Beta from edition' }).click();
    const retire = page.getByRole('dialog', { name: 'Confirm retirement' });
    await expect(retire).toContainText('Existing listings keep their value.');
    const retired = page.waitForResponse(r => r.request().method() === 'DELETE' && r.url().endsWith('/allowed-values/Beta'));
    await retire.getByRole('button', { name: 'Retire value', exact: true }).click();
    // The DELETE answer is asserted; the refetched schema is not, because the catalog service does not persist the removal (deferred-items.md).
    const retiredResponse = await retired;
    expect(retiredResponse.status()).toBe(200);
    expect(((await retiredResponse.json()) as { allowedValues: string[] }).allowedValues).toEqual(['Alpha']);
    await expect(page.getByRole('status').filter({ hasText: 'Allowed value retired.' })).toBeVisible();
    await assertNoOverflow(page);
    admin.verify();
  });
});

test.describe('admin operations', () => {
  test('a fixed queue list, empty replays on both services and a bounded drift check', async () => {
    const { page } = admin;
    await page.goto('/admin/operations');
    await expect(page.getByRole('heading', { name: 'Operations', level: 1 })).toBeVisible();
    await expect(page.locator('#search-heading + .chip')).toHaveText(/^(Completed|Never run|Running|Failed|Stopped)$/);
    await expect(page.locator('input[type="text"]')).toHaveCount(0);

    const queues = async (id: string) => page.locator(`${id} option`).allInnerTexts();
    expect(await queues('#order-queue')).toEqual(['order.payment-result.dlq', 'order.listing-lifecycle.dlq']);
    expect(await queues('#messaging-queue')).toEqual(['notify.favourite.dlq', 'notify.follow.dlq', 'notify.price-drop.dlq',
      'notify.version-released.dlq', 'notify.review.dlq', 'notify.offer.dlq', 'chat.offer-card.dlq', 'notify.order.dlq', 'messaging.email-send.dlq']);

    for (const [service, queue, path] of [['order', 'order.payment-result.dlq', /\/order\//], ['messaging', 'notify.favourite.dlq', /\/messaging\//]] as const) {
      await page.locator(`#${service}-queue`).selectOption(queue);
      await page.getByRole('button', { name: `Replay one ${service} message` }).click();
      const dialog = page.getByRole('dialog', { name: 'Replay this message?' });
      await expect(dialog).toContainText(queue);
      const replayed = page.waitForResponse(r => r.request().method() === 'POST' && /dead-letter|replay/.test(r.url()) && r.url().includes(queue));
      await dialog.getByRole('button', { name: 'Replay one message', exact: true }).click();
      const response = await replayed;
      // A parked message would answer 200 with an X-Audit-Id header; an empty queue answers 204 and no audit id.
      expect([200, 204]).toContain(response.status());
      if (response.status() === 204) {
        await expect(page.getByText('Queue is empty. No message was replayed.')).toBeVisible();
        expect(response.headers()['x-audit-id']).toBeUndefined();
      } else {
        expect(response.headers()['x-audit-id']).toBeTruthy();
        await expect(page.getByText('One message returned to processing.')).toBeVisible();
      }
      await expect(dialog).toHaveCount(0);
    }

    const drift = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/admin/search/drift-check');
    await page.getByRole('button', { name: 'Check drift', exact: true }).click();
    expect((await drift).status()).toBe(200);
    const counts = page.getByRole('group', { name: 'Drift results' }).or(page.locator('dl[aria-label="Drift results"]'));
    await expect(counts).toBeVisible();
    await expect(counts.locator('dd')).toHaveCount(3);
    await assertNoOverflow(page);
    admin.verify();
  });

  test('the stuck order list reads its page and a refund needs a reason before anything is sent', async () => {
    const { page } = admin;
    await page.goto('/admin/operations');
    await expect(page.getByRole('heading', { name: 'Stuck orders', level: 2 })).toBeVisible();
    const listed = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/admin/orders/stuck');
    await page.getByRole('button', { name: 'Refresh orders', exact: true }).click();
    expect((await listed).status()).toBe(200);
    const rows = page.locator('.order-row');
    await expect(rows.first().or(page.getByRole('heading', { name: /^No (stuck orders|matches on this page)$/ }))).toBeVisible();
    if (await rows.count()) {
      const refunds: string[] = [];
      page.on('request', request => { if (request.method() === 'POST' && /refund/.test(request.url())) refunds.push(request.url()); });
      await rows.first().getByRole('button', { name: /^Refund in full for / }).click();
      const dialog = page.getByRole('dialog', { name: 'Refund this order in full?' });
      await dialog.getByRole('button', { name: 'Request full refund', exact: true }).click();
      await expect(dialog.locator('#reason-error')).toBeVisible();
      expect(refunds).toEqual([]);
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    }
    admin.verify();
  });

  test('a rebuild reports its running state and settles within a bound', async ({}, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop', 'The shared search index is rebuilt once, from the desktop project');
    const { page } = admin;
    await page.goto('/admin/operations');
    const chip = page.locator('#search-heading + .chip');
    await expect(chip).not.toHaveText('Checking status');
    await page.getByRole('button', { name: 'Rebuild index', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Rebuild the search index?' });
    await expect(dialog).toContainText('Results can be incomplete while it runs.');
    const started = post(page, '/admin/search/rebuild');
    await dialog.getByRole('button', { name: 'Rebuild index', exact: true }).click();
    expect((await started).status()).toBeLessThan(300);
    // Never labelled complete early: the chip may only reach Completed after the status read says the run ended.
    await expect(chip).toHaveText(/^(Running|Completed)$/);
    await expect(chip).toHaveText('Completed', { timeout: 150_000 });
    await expect(page.getByRole('button', { name: 'Rebuild index', exact: true })).toBeEnabled();
    admin.verify();
  });
});

test.describe('admin problems', () => {
  async function rowFor(page: Page, orderId: string): Promise<Locator> {
    const row = page.locator('tbody tr', { has: page.getByRole('link', { name: orderId, exact: true }) });
    const more = page.getByRole('button', { name: 'Show more', exact: true });
    for (let i = 0; i < 10 && !(await row.count()) && (await more.count()); i++) {
      await more.click();
      await expect(page.getByRole('button', { name: 'Loading...' })).toHaveCount(0);
    }
    return row;
  }

  test('the queue renders every active problem without overflow', async () => {
    const { page } = admin;
    await page.goto('/admin/problems');
    await expect(page.getByRole('heading', { name: 'Problems', level: 1 })).toBeVisible();
    await expect(page.locator('tbody tr').first().or(page.getByRole('heading', { name: /^No (open problems|problems match)/ }))).toBeVisible();
    await page.getByRole('combobox', { name: 'Problem status' }).selectOption('REFUND_PENDING');
    await expect(page).toHaveURL(/status=REFUND_PENDING/);
    await page.getByRole('combobox', { name: 'Problem status' }).selectOption('ALL');
    await assertNoOverflow(page);
    admin.verify();
  });

  test('the hardware fixture problem is refunded in full: 202 and Refund pending', async ({}, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop', 'Resolving the fixture problems is a one-time mutation, run from the desktop project');
    const fixtures = fixtureOrders();
    const [hardware, software] = await Promise.all([read(fixtures.hardwareId), read(fixtures.softwareId)]);
    if (STRICT) assertOpenProblems(hardware, software);
    const { page } = admin;
    await page.goto('/admin/problems');
    if (hardware.problem?.status === 'OPEN') {
      const row = await rowFor(page, fixtures.hardwareId);
      await expect(row).toContainText('Open');
      await page.getByRole('button', { name: `Refund in full for order ${fixtures.hardwareId}` }).click();
      const dialog = page.getByRole('dialog', { name: 'Refund this order in full?' });
      await expect(dialog).toContainText(fixtures.hardwareId);
      await dialog.locator('#resolution-note').fill('x'.repeat(1001));
      await dialog.locator('#resolution-note').blur();
      await expect(dialog.locator('#note-error')).not.toHaveText('');
      await expect(dialog.getByRole('button', { name: 'Request full refund', exact: true })).toBeDisabled();
      await dialog.locator('#resolution-note').fill('E2E: refund the fixture hardware order');
      const refunded = post(page, `/admin/problems/${hardware.problem.id}/refund`);
      await dialog.getByRole('button', { name: 'Request full refund', exact: true }).click();
      const response = await refunded;
      expect(response.status()).toBe(202);
      expect(((await response.json()) as { status: string }).status).toBe('REFUND_PENDING');
      await expect(page.getByRole('status').filter({ hasText: 'Refund requested. Awaiting payment confirmation.' })).toBeVisible();
      await expect(dialog).toHaveCount(0);
    }
    // Payment confirmation can arrive at any moment, so the pending truth is read back as pending or already confirmed.
    const after = await read(fixtures.hardwareId);
    expect(['REFUND_PENDING', 'RESOLVED']).toContain(after.problem?.status);
    if (after.problem?.status === 'RESOLVED') expect(after.problem.outcome).toBe('REFUND');
    admin.verify();
  });

  test('the software fixture problem is dismissed with a note: RESOLVED with outcome DISMISS', async ({}, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop', 'Resolving the fixture problems is a one-time mutation, run from the desktop project');
    const fixtures = fixtureOrders();
    const before = await read(fixtures.softwareId);
    const { page } = admin;
    await page.goto('/admin/problems');
    if (before.problem?.status === 'OPEN') {
      const row = await rowFor(page, fixtures.softwareId);
      await expect(row).toBeVisible();
      await page.getByRole('button', { name: `Dismiss problem for order ${fixtures.softwareId}` }).click();
      const dialog = page.getByRole('dialog', { name: 'Dismiss this problem?' });
      // A dismissal without a reason cannot be submitted at all.
      await expect(dialog.getByRole('button', { name: 'Dismiss problem', exact: true })).toBeDisabled();
      await dialog.locator('#resolution-note').focus();
      await dialog.locator('#resolution-note').blur();
      await expect(dialog.locator('#note-error')).not.toHaveText('');
      const note = 'E2E: the licence worked, no refund due';
      await dialog.locator('#resolution-note').fill(note);
      const dismissed = post(page, `/admin/problems/${before.problem.id}/dismiss`);
      await dialog.getByRole('button', { name: 'Dismiss problem', exact: true }).click();
      const response = await dismissed;
      expect(response.status()).toBe(200);
      expect(response.request().postDataJSON()).toEqual({ note });
      await expect(page.getByRole('status').filter({ hasText: 'Problem dismissed.' })).toBeVisible();
      await expect(page.locator('tbody tr', { has: page.getByRole('link', { name: fixtures.softwareId, exact: true }) })).toHaveCount(0);
    }
    const after = await read(fixtures.softwareId);
    expect(after.problem?.status).toBe('RESOLVED');
    expect(after.problem?.outcome).toBe('DISMISS');
    expect(after.problem?.resolutionNote).toBeTruthy();
    admin.verify();
  });
});
