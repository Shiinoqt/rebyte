import type { Page, Request } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { runtimeConfig } from './helpers/cognito-admin';
import { fixtureOrders, signedInActor, type Actor } from './helpers/fixture-actors';
import { jpegFixture, type JpegFixture } from './helpers/upload-fixtures';
import type { ListingDetailResponse } from '../src/app/core/api/api-models';

// The persistent fixture seller and buyer drive this suite through the SPA so the per-run seed listings stay untouched.
// Checkout stops at the PayPal approval handoff: the PayPal page itself is stubbed locally and no payment is made.
test.use({ trace: 'off' });
test.describe.configure({ mode: 'serial' });

const gateway = new URL(runtimeConfig().apiBaseUrl).origin;
const tag = `[e2e] commerce ${Date.now().toString(36)}`;
const ADDRESS = { recipient: 'E2E Commerce Buyer', street: 'Via Roma 1', city: 'Roma', postalCode: '00100', country: 'IT' };
let seller: Actor;
let buyer: Actor;
let tolerateLostOrderResponse = false;
let hardware = { id: '', keys: [] as string[], title: '' };
let offerId = '';
let softwareId = '';

interface Call { method: string; path: string; body: unknown; key: string | undefined }

function record(page: Page) {
  const calls: Call[] = [];
  const storage: { authorization: string | undefined; origin: string }[] = [];
  const policyKeys: string[] = [];
  const onRequest = (request: Request) => {
    const url = new URL(request.url());
    if (url.origin === gateway) {
      let body: unknown;
      try { body = request.postDataJSON(); } catch { body = undefined; }
      calls.push({ method: request.method(), path: url.pathname, body, key: request.headers()['idempotency-key'] });
    } else if (request.method() === 'POST' && url.hostname.endsWith('amazonaws.com')) {
      storage.push({ authorization: request.headers()['authorization'], origin: url.origin });
    }
  };
  const onResponse = (response: { url(): string; json(): Promise<{ key?: string }> }) => {
    if (new URL(response.url()).pathname.endsWith('/upload-policy')) {
      void response.json().then(policy => { if (policy.key) policyKeys.push(policy.key); }).catch(() => undefined);
    }
  };
  page.on('request', onRequest);
  page.on('response', onResponse);
  return {
    calls, storage, policyKeys,
    writes: (id: string) => calls.filter(call => call.method !== 'GET' && call.path.startsWith('/listings'))
      .map(call => ({ ...call, path: call.path.replace(`/listings/${id}`, '/listings/:id') })),
    stop: () => { page.off('request', onRequest); page.off('response', onResponse as never); },
  };
}

// Seed categories carry the run id in their name, so listings placed there would leak into the run-id searches of other specs.
async function chooseCatalogueLeaf(page: Page, name: string): Promise<void> {
  const select = page.locator('#listing-category');
  const value = await select.evaluate((element, wanted) => [...(element as HTMLSelectElement).options]
    .find(option => option.text.includes(wanted) && !option.text.includes('[e2e]'))?.value ?? '', name);
  expect(value, `No real catalogue category named ${name}`).not.toBe('');
  await select.selectOption(value);
}

async function publishHardware(page: Page, title: string, photos: JpegFixture[]): Promise<string> {
  await page.goto('/sell');
  await page.locator('#listing-title').fill(title);
  await chooseCatalogueLeaf(page, 'Mice');
  await page.locator('#listing-condition').selectOption('GOOD');
  await page.locator('#listing-description').fill('Commerce e2e hardware listing');
  await page.locator('#listing-price').fill('12.00');
  await page.locator('#listing-shipping-location').fill('Turin, Italy');
  await page.locator('#listing-shipping-cost').fill('3.00');
  await page.locator('input[aria-label="Select listing photos"]').setInputFiles(photos.map(jpegFixture));
  await expect(page.getByRole('img', { name: `Selected photo ${photos.length}`, exact: true })).toBeVisible();
  if (photos.length > 1) await page.getByRole('button', { name: 'Move photo 2 earlier', exact: true }).click();
  await page.getByRole('button', { name: 'Publish listing', exact: true }).click();
  await page.waitForURL(url => /^\/listings\/[^/]+$/.test(url.pathname), { timeout: 30_000 });
  return new URL(page.url()).pathname.split('/')[2];
}

async function approvalHandoff(page: Page): Promise<URL> {
  await page.route(url => url.hostname.endsWith('paypal.com'),
    route => route.fulfill({ status: 200, contentType: 'text/html', body: '<title>PayPal approval stub</title>' }));
  return new URL(await page.waitForURL(url => url.hostname.endsWith('paypal.com'), { timeout: 30_000 }).then(() => page.url()));
}

async function fillAddress(page: Page): Promise<void> {
  await page.locator('#checkout-recipient').fill(ADDRESS.recipient);
  await page.locator('#checkout-street').fill(ADDRESS.street);
  await page.locator('#checkout-city').fill(ADDRESS.city);
  await page.locator('#checkout-postal-code').fill(ADDRESS.postalCode);
  await page.locator('#checkout-country').selectOption(ADDRESS.country);
}

test.beforeAll(async ({ browser }, testInfo) => {
  const fixtures = fixtureOrders();
  const tolerated = (failure: { kind: string; url: string }) => tolerateLostOrderResponse &&
    (failure.kind === 'console-error' || (failure.kind === 'requestfailed' && failure.url.endsWith('/orders')));
  [seller, buyer] = await Promise.all([signedInActor(browser, testInfo, fixtures.seller),
    signedInActor(browser, testInfo, fixtures.buyer, [tolerated])]);
});

test.afterAll(async () => {
  await Promise.all([seller?.context.close(), buyer?.context.close()]);
});

test('seller publishes hardware with ordered direct-upload photos that never carry gateway credentials', async () => {
  const rec = record(seller.page);
  hardware.title = `${tag} hardware`;
  hardware.id = await publishHardware(seller.page, hardware.title, ['red', 'blue']);
  rec.stop();
  expect(rec.writes(hardware.id).map(call => `${call.method} ${call.path}`)).toEqual([
    'POST /listings/hardware',
    'POST /listings/:id/photos/upload-policy', 'POST /listings/:id/photos/confirm',
    'POST /listings/:id/photos/upload-policy', 'POST /listings/:id/photos/confirm',
    'PUT /listings/:id',
  ]);
  const writes = rec.writes(hardware.id);
  expect(writes[0].body).toMatchObject({ title: hardware.title, photos: [], paymentMethods: ['PAYPAL'], price: '12.00', shippingCost: '3.00' });
  const put = writes[5].body as { photos: string[]; title: string };
  expect(put.photos).toHaveLength(2);
  expect(put.photos).toEqual(rec.policyKeys);
  expect(rec.storage).toHaveLength(2);
  expect(rec.storage.every(upload => upload.authorization === undefined && upload.origin !== gateway)).toBe(true);
  await expect.poll(async () => ((await (await seller.page.request.get(`${gateway}/listings/${hardware.id}`)).json()) as ListingDetailResponse).photoKeys)
    .toEqual(put.photos);
  hardware.keys = put.photos;
  seller.verify();
});

test('seller reorders the stored photos and edits the title', async () => {
  const rec = record(seller.page);
  await seller.page.goto(`/listings/${hardware.id}/edit`);
  await expect(seller.page.getByRole('img', { name: 'Selected photo 2', exact: true })).toBeVisible();
  hardware.title = `${tag} hardware edited`;
  await seller.page.locator('#listing-title').fill(hardware.title);
  await seller.page.getByRole('button', { name: 'Move photo 2 earlier', exact: true }).click();
  await seller.page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await seller.page.waitForURL(url => url.pathname === `/listings/${hardware.id}`, { timeout: 30_000 });
  rec.stop();
  const writes = rec.writes(hardware.id);
  expect(writes.map(call => `${call.method} ${call.path}`)).toEqual(['PUT /listings/:id']);
  expect(writes[0].body).toMatchObject({ title: hardware.title, photos: [...hardware.keys].reverse() });
  await expect.poll(async () => ((await (await seller.page.request.get(`${gateway}/listings/${hardware.id}`)).json()) as ListingDetailResponse).photoKeys)
    .toEqual([...hardware.keys].reverse());
  await expect(seller.page.getByRole('heading', { level: 1 })).toHaveText(hardware.title);
  seller.verify();
});

test('developer publishes software and attaches the first installer straight to storage', async () => {
  const page = seller.page;
  await page.goto('/sell');
  await page.getByRole('radio', { name: 'Software', exact: true }).click();
  await page.locator('#listing-title').fill(`${tag} software`);
  await chooseCatalogueLeaf(page, 'Operating systems');
  await page.locator('#listing-description').fill('Commerce e2e software listing');
  await page.locator('#listing-price').fill('5.00');
  await page.getByRole('checkbox', { name: 'Linux', exact: true }).check();
  await page.locator('#listing-version').fill('1.0');
  await page.getByRole('button', { name: 'Publish listing', exact: true }).click();
  await page.waitForURL(url => /^\/listings\/[^/]+$/.test(url.pathname), { timeout: 30_000 });
  softwareId = new URL(page.url()).pathname.split('/')[2];

  const rec = record(page);
  await page.goto(`/listings/${softwareId}/releases`);
  await page.locator('input[aria-label="Installer for v1.0"]').setInputFiles({
    name: 'installer.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(2048, 7) });
  const confirmed = page.waitForResponse(r => r.url().endsWith('/versions/1.0/artifact/confirm') && r.ok());
  await page.getByRole('button', { name: 'Upload installer', exact: true }).click();
  await confirmed;
  await expect(page.getByText('Installer attached', { exact: true })).toBeVisible();
  rec.stop();
  expect(rec.writes(softwareId).map(call => `${call.method} ${call.path}`)).toEqual([
    'POST /listings/:id/versions/1.0/artifact-upload-policy', 'POST /listings/:id/versions/1.0/artifact/confirm']);
  expect(rec.storage).toHaveLength(1);
  expect(rec.storage[0].authorization).toBeUndefined();
  expect(rec.storage[0].origin).not.toBe(gateway);
  seller.verify();
});

test('buyer favourites, follows, messages and offers on the listing', async () => {
  const page = buyer.page;
  const rec = record(page);
  await page.goto(`/listings/${hardware.id}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(hardware.title);

  const favourited = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/favourites/${hardware.id}` && r.ok());
  await page.getByRole('button', { name: 'Add to favourites', exact: true }).click();
  await favourited;
  await expect(page.getByRole('button', { name: 'Remove from favourites', exact: true })).toHaveAttribute('aria-pressed', 'true');

  // The fixture buyer persists across runs, so follow state is toggled and restored rather than assumed.
  const follow = page.getByRole('button', { name: /^(Follow|Unfollow|Following)\b/ });
  const wasFollowing = /^(Unfollow|Following)/.test(await follow.first().innerText() + await follow.first().getAttribute('aria-label'));
  for (const method of wasFollowing ? ['DELETE', 'PUT'] : ['PUT', 'DELETE']) {
    const changed = page.waitForResponse(r => r.request().method() === method && /^\/users\/[^/]+\/follow$/.test(new URL(r.url()).pathname) && r.ok());
    await follow.first().click();
    await changed;
  }

  // An offer can open the conversation asynchronously, so establish the chat first.
  await page.getByRole('button', { name: 'Message seller', exact: true }).click();
  const conversation = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/conversations');
  await page.getByRole('button', { name: 'Send message', exact: true }).click();
  const started = await conversation;
  expect(started.status()).toBe(201);
  const conversationId = ((await started.json()) as { id: string }).id;
  await page.waitForURL(url => url.pathname === `/conversations/${conversationId}`);
  await expect(page.locator('article.message', { hasText: 'Hi, is this still available?' })).toHaveCount(1);

  await page.goto(`/listings/${hardware.id}`);
  await page.getByRole('button', { name: 'Make an offer', exact: true }).click();
  await page.locator('#offer-amount').fill('10.00');
  const offered = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/offers');
  await page.getByRole('button', { name: 'Send offer', exact: true }).click();
  const offer = await offered;
  expect(offer.status()).toBe(201);
  offerId = ((await offer.json()) as { id: string }).id;
  rec.stop();
  expect(rec.calls.find(call => call.path === '/conversations')?.body)
    .toEqual({ listingId: hardware.id, text: 'Hi, is this still available?', imageKeys: [] });
  expect(rec.calls.find(call => call.path === '/offers')?.body).toEqual({ listingId: hardware.id, amount: '10.00' });
  buyer.verify();
});

test('seller accepts the offer from the offers inbox', async () => {
  const page = seller.page;
  await page.goto('/offers');
  const row = page.locator(`[data-offer="${offerId}"]`);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: 'Accept offer', exact: true }).click();
  const accepted = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === `/offers/${offerId}/accept` && r.ok());
  await page.getByRole('dialog', { name: 'Accept offer?', exact: true }).getByRole('button', { name: 'Accept offer', exact: true }).click();
  await accepted;
  await expect(row.getByText('Accepted', { exact: true })).toBeVisible();
  seller.verify();
});

test('buyer checks out the accepted offer; a lost response retries with the same Idempotency-Key', async () => {
  const page = buyer.page;
  const rec = record(page);
  await page.goto('/offers');
  await page.getByRole('radio', { name: 'Sent', exact: true }).click();
  await page.locator(`[data-offer="${offerId}"]`).getByRole('link', { name: /^Checkout at/ }).click();
  await page.waitForURL(url => url.pathname === `/checkout/${hardware.id}` && url.searchParams.get('offer') === offerId);
  await fillAddress(page);

  let aborted = 0;
  await page.route(url => url.origin === gateway && url.pathname === '/orders', route => {
    if (route.request().method() === 'POST' && aborted === 0) { aborted++; return route.abort('failed'); }
    return route.fallback();
  });
  tolerateLostOrderResponse = true;
  const pay = page.getByRole('button', { name: /^Pay with PayPal/ });
  const handoff = approvalHandoff(page);
  await pay.click();
  await expect.poll(() => aborted).toBe(1);
  await expect(pay).toBeEnabled();
  await pay.click();
  const approval = await handoff;
  tolerateLostOrderResponse = false;
  rec.stop();

  const orders = rec.calls.filter(call => call.method === 'POST' && call.path === '/orders');
  expect(orders).toHaveLength(2);
  expect(orders[0].key).toBeTruthy();
  expect(orders[1].key).toBe(orders[0].key);
  expect(orders[1].body).toEqual({ listingId: hardware.id, method: 'PAYPAL', expectedTotal: '13.00', address: ADDRESS, offerId });
  expect(approval.protocol).toBe('https:');
  expect(approval.hostname.endsWith('sandbox.paypal.com')).toBe(true);
  buyer.verify();
});

test('buyer checks out hardware without an offer: address, no offerId, trusted approval host', async () => {
  const plainId = await publishHardware(seller.page, `${tag} plain`, ['red']);
  const page = buyer.page;
  const rec = record(page);
  await page.goto(`/checkout/${plainId}`);
  await fillAddress(page);
  const handoff = approvalHandoff(page);
  await page.getByRole('button', { name: /^Pay with PayPal/ }).click();
  const approval = await handoff;
  rec.stop();
  const orders = rec.calls.filter(call => call.method === 'POST' && call.path === '/orders');
  expect(orders).toHaveLength(1);
  expect(orders[0].body).toEqual({ listingId: plainId, method: 'PAYPAL', expectedTotal: '15.00', address: ADDRESS });
  expect(approval.protocol).toBe('https:');
  expect(approval.hostname.endsWith('sandbox.paypal.com')).toBe(true);
  seller.verify();
  buyer.verify();
});

test('buyer checks out software with no address in the request', async () => {
  const page = buyer.page;
  const rec = record(page);
  await page.goto(`/checkout/${softwareId}`);
  await expect(page.locator('#checkout-recipient')).toHaveCount(0);
  const handoff = approvalHandoff(page);
  await page.getByRole('button', { name: /^Pay with PayPal/ }).click();
  const approval = await handoff;
  rec.stop();
  const orders = rec.calls.filter(call => call.method === 'POST' && call.path === '/orders');
  expect(orders).toHaveLength(1);
  expect(orders[0].body).toEqual({ listingId: softwareId, method: 'PAYPAL', expectedTotal: '5.00' });
  expect(approval.protocol).toBe('https:');
  expect(approval.hostname.endsWith('sandbox.paypal.com')).toBe(true);
  buyer.verify();
});
