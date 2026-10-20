import { test, expect, type Page } from '@playwright/test';
import { fixtureOrders, signedInActor } from './helpers/fixture-actors';
import { testData } from './helpers/test-data';
import { jpegFixture } from './helpers/upload-fixtures';
import { lastTypingTtl, openSession, presenceTransitions, type Session } from './helpers/two-session';

// The run's buyer and seller talk about the seeded hardware listing through two isolated browser contexts. The STOMP
// socket of each context is the SPA's real one, passed through the gateway unchanged and only observed.
test.use({ trace: 'off' });
test.describe.configure({ mode: 'serial', timeout: 120_000 });

const tag = `[e2e] chat ${Date.now().toString(36)}`;
let buyer: Session;
let seller: Session;
let conversationId = '';
let sellerSub = '';
let buyerName = '';

const thread = () => `/conversations/${conversationId}`;
const shell = (page: Page) => page.locator('nav:visible');
const shellLink = (page: Page, path: string) => shell(page).locator(`a[href="${path}"]`);
const message = (session: Session, text: string) => session.page.locator('article.message', { hasText: text });
const composer = (session: Session) => session.page.getByRole('textbox', { name: 'Write a message' });
const send = (session: Session) => session.page.getByRole('button', { name: 'Send', exact: true });

async function badge(session: Session, noun: 'messages' | 'notifications'): Promise<number> {
  const label = shell(session.page).locator(`.count[aria-label$="unread ${noun}"]`);
  return (await label.count()) ? parseInt((await label.first().getAttribute('aria-label'))!, 10) : 0;
}

// On a phone an open thread replaces the inbox and the tab bar, leaving only its back link.
async function navigate(page: Page, path: string): Promise<void> {
  const link = shellLink(page, path);
  if (!(await link.count())) await page.getByRole('link', { name: 'Back to messages' }).click();
  await link.click();
}

async function dismissToasts(page: Page): Promise<void> {
  const close = page.getByRole('button', { name: 'Dismiss notification' });
  // A toast that times out while its close button is being clicked has already done what the click was for.
  for (let i = 0; i < 5 && (await close.count()); i++) await close.first().click({ timeout: 2000 }).catch(() => undefined);
}

test.beforeAll(async ({ browser }, testInfo) => {
  const states = testData().storageStates;
  [buyer, seller] = await Promise.all([openSession(browser, testInfo, states.buyer), openSession(browser, testInfo, states.seller)]);
  await seller.page.goto('/conversations');
  await buyer.page.goto(`/listings/${testData().hardwareListingId}`);
  await expect.poll(() => [buyer.trace.connected, seller.trace.connected]).toEqual([1, 1]);
});

test.afterAll(async () => { await Promise.all([buyer?.context.close(), seller?.context.close()]); });

test('the buyer opens the conversation from the listing and both sides land on the same thread', async () => {
  const page = buyer.page;
  const view = page.waitForResponse(r => r.request().method() === 'GET' && /^\/conversations\/[\w-]+$/.test(new URL(r.url()).pathname));
  await page.getByRole('button', { name: /^Message (seller|@\S+)$/ }).click();
  const started = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/conversations');
  await page.getByRole('button', { name: 'Send message', exact: true }).click();
  const response = await started;
  // A repeat run for another viewport finds the conversation the first one created.
  expect([201, 409]).toContain(response.status());
  const body = await response.json() as { id?: string; conversationId?: string };
  conversationId = response.status() === 201 ? body.id! : body.conversationId!;
  await page.waitForURL(url => url.pathname === thread());
  const conversation = await (await view).json() as { otherParticipantSub: string; otherParticipantUsername: string };
  sellerSub = conversation.otherParticipantSub;
  expect(sellerSub).toBeTruthy();

  // Opening the thread clears the seller's backlog so the following unread counts are exact.
  await seller.page.goto(thread());
  await expect(seller.page.getByRole('textbox', { name: 'Write a message' })).toBeVisible();
  buyerName = (await seller.page.locator('.conversation-title a').first().innerText()).trim();
  expect(buyerName).toMatch(/^@\S+/);
  await navigate(seller.page, '/conversations');
  await expect.poll(() => badge(seller, 'messages')).toBe(0);
  await expect.poll(() => seller.trace.connected).toBeGreaterThanOrEqual(1);
});

test('a message arriving outside the thread raises one toast, the unread badges and the inbox row, then converges after reading', async () => {
  await dismissToasts(seller.page);
  const text = `${tag} first`;
  await composer(buyer).fill(text);
  await send(buyer).click();
  await expect(message(buyer, text)).toHaveCount(1);

  const row = seller.page.locator(`a.thread-row[href="${thread()}"]`);
  await expect(row).toHaveClass(/unread/);
  await expect(row.locator('.preview')).toHaveText(text);
  await expect.poll(() => badge(seller, 'messages')).toBe(1);
  await expect.poll(() => badge(seller, 'notifications')).toBeGreaterThanOrEqual(1);
  const toast = seller.page.locator('[data-toast-action]');
  await expect(toast).toHaveCount(1);
  await expect(seller.page.getByText(`New message from ${buyerName}`).first()).toBeVisible();
  await dismissToasts(seller.page);
  // The same pushed message must not queue a second toast.
  await expect(toast).toHaveCount(0);

  await row.click();
  await expect(message(seller, text)).toHaveCount(1);

  // The server's view after a reload equals what the realtime path rendered.
  await seller.page.reload();
  await expect(message(seller, text)).toHaveCount(1);
  await expect.poll(() => seller.trace.connected).toBeGreaterThanOrEqual(2);
  await navigate(seller.page, '/conversations');
  await expect(seller.page.locator(`a.thread-row[href="${thread()}"]`)).not.toHaveClass(/unread/);
  await expect.poll(() => badge(seller, 'messages')).toBe(0);
  seller.verify();
  buyer.verify();
});

test('the toast Open action leads to the thread and an open thread shows a message without any toast', async () => {
  await navigate(seller.page, '/notifications');
  await dismissToasts(seller.page);
  const text = `${tag} second`;
  await composer(buyer).fill(text);
  await send(buyer).click();
  await seller.page.locator('[data-toast-action]').click();
  await seller.page.waitForURL(url => url.pathname === thread());
  await expect(message(seller, text)).toHaveCount(1);

  const third = `${tag} third`;
  await composer(buyer).fill(third);
  await send(buyer).click();
  await expect(message(seller, third)).toHaveCount(1);
  await expect(seller.page.locator('[data-toast-action]')).toHaveCount(0);
  await expect.poll(() => badge(seller, 'messages')).toBe(0);
});

test('typing shows on the partner side and ends once the ttl carried by the frame has elapsed', async () => {
  // The backend throttles typing frames per user, so give the previous sends time to leave that window.
  await buyer.page.waitForTimeout(1700);
  const bubble = seller.page.locator('.typing-bubble');
  const seen = seller.trace.received.filter(frame => frame.queue === 'typing').length;
  await composer(buyer).fill('typing right now');
  await expect(bubble).toBeVisible();
  await expect.poll(() => seller.trace.received.filter(frame => frame.queue === 'typing').length).toBeGreaterThan(seen);
  const frames = seller.trace.received;
  const ttl = lastTypingTtl(frames);
  const receivedAt = frames.filter(frame => frame.queue === 'typing').at(-1)!.at;
  expect(ttl).toBeGreaterThan(0);
  await expect(bubble).toBeHidden({ timeout: ttl + 5000 });
  expect(Date.now() - receivedAt).toBeGreaterThanOrEqual(ttl - 150);
  await composer(buyer).fill('');
});

test('a dropped socket flips presence offline then online and the reconnect refetches what was missed exactly once', async () => {
  const online = buyer.page.locator('.conversation-header .online-label');
  await expect(online).toBeVisible();
  const before = presenceTransitions(buyer.trace.received, sellerSub).length;
  const connects = { bearer: seller.trace.bearerConnects, connected: seller.trace.connected };

  seller.trace.blocked = true;
  seller.trace.drop();
  await expect.poll(() => presenceTransitions(buyer.trace.received, sellerSub).slice(before)).toEqual([false]);
  await expect(online).toBeHidden();

  const missed = `${tag} while offline`;
  await composer(buyer).fill(missed);
  await send(buyer).click();
  await expect(message(buyer, missed)).toHaveCount(1);
  // Three SPA reconnect delays: a connected seller would have received the live message by now.
  await seller.page.waitForTimeout(3000);
  expect(presenceTransitions(buyer.trace.received, sellerSub).slice(before)).toEqual([false]);
  expect(seller.trace.connected).toBe(connects.connected);
  await expect(message(seller, missed)).toHaveCount(0);

  seller.trace.blocked = false;
  await expect.poll(() => presenceTransitions(buyer.trace.received, sellerSub).slice(before), { timeout: 30_000 }).toEqual([false, true]);
  await expect(online).toBeVisible();
  // Every reconnect authenticates again with a bearer CONNECT and the open thread is restored in place.
  expect(seller.trace.connected).toBe(connects.connected + 1);
  expect(seller.trace.bearerConnects).toBe(connects.bearer + 1);
  await expect(seller.page).toHaveURL(new RegExp(`${thread()}$`));
  await expect(message(seller, missed)).toHaveCount(1);
  await expect(message(seller, `${tag} third`)).toHaveCount(1);
  await expect.poll(() => badge(seller, 'messages')).toBe(0);
  seller.verify();
  buyer.verify();
});

test('a photo travels through direct storage upload and appears once for both sides without a bearer', async () => {
  const images = (session: Session) => session.page.locator('img.message-image');
  const [buyerBefore, sellerBefore] = [await images(buyer).count(), await images(seller).count()];
  await buyer.page.locator('input[type="file"]').setInputFiles(jpegFixture('red'));
  await expect(send(buyer)).toBeEnabled({ timeout: 30_000 });
  await send(buyer).click();
  await expect(images(buyer)).toHaveCount(buyerBefore + 1);
  await expect(images(seller)).toHaveCount(sellerBefore + 1);
  await expect.poll(() => images(seller).last().evaluate(image => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  seller.verify();
  buyer.verify();
});

test('an offer card comes from the order offer command, shows once for both sides and a decline converges', async () => {
  const cards = (session: Session) => session.page.locator('.offer-card');
  const rejected = (session: Session) => cards(session).filter({ hasText: 'Rejected' });
  const [buyerBefore, sellerBefore] = [await cards(buyer).count(), await cards(seller).count()];
  const [buyerRejected, sellerRejected] = [await rejected(buyer).count(), await rejected(seller).count()];
  const calls: string[] = [];
  buyer.page.on('request', request => { if (request.method() === 'POST') calls.push(new URL(request.url()).pathname); });

  await buyer.page.getByRole('button', { name: 'Make an offer', exact: true }).click();
  await buyer.page.locator('input#message-offer-amount').fill('10.00');
  const created = buyer.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/offers');
  await buyer.page.getByRole('button', { name: 'Send offer', exact: true }).click();
  expect((await created).status()).toBe(201);
  // The card is written by the messaging service after the order service publishes the offer event.
  await expect(cards(seller)).toHaveCount(sellerBefore + 1, { timeout: 30_000 });
  await expect(cards(buyer)).toHaveCount(buyerBefore + 1, { timeout: 30_000 });
  const card = cards(seller).last();
  await expect(card).toContainText('Pending');
  await expect(card).toContainText(/10[.,]00/);
  expect(calls.filter(path => /^\/conversations\/[\w-]+\/messages$/.test(path))).toEqual([]);

  const declined = seller.page.waitForResponse(r => r.request().method() === 'POST' && /^\/offers\/[\w-]+\/reject$/.test(new URL(r.url()).pathname));
  await card.getByRole('button', { name: 'Decline', exact: true }).click();
  expect((await declined).status()).toBeLessThan(300);
  await expect(cards(seller).last()).toContainText('Rejected');
  await expect(cards(buyer).last()).toContainText('Rejected');
  // Each transition appends its own card; both sides must hold exactly the two cards of this offer and no copy of either.
  await expect(cards(buyer)).toHaveCount(buyerBefore + 2, { timeout: 30_000 });
  await expect(cards(seller)).toHaveCount(sellerBefore + 2, { timeout: 30_000 });
  await expect(rejected(buyer)).toHaveCount(buyerRejected + 2);
  await expect(rejected(seller)).toHaveCount(sellerRejected + 2);
  seller.verify();
  buyer.verify();
});

test('a reply reaches a buyer outside the thread as a notification row, toast and badge, and the deep link opens the thread', async () => {
  await navigate(buyer.page, '/notifications');
  await dismissToasts(buyer.page);
  const reply = `${tag} reply`;
  const before = await badge(buyer, 'notifications');
  await composer(seller).fill(reply);
  await send(seller).click();
  await expect(buyer.page.locator('[data-toast-action]')).toHaveCount(1);
  await expect.poll(() => badge(buyer, 'notifications')).toBeGreaterThanOrEqual(before);
  const link = buyer.page.locator(`.notification-list a[href="${thread()}"]`).first();
  await expect(link).toBeVisible();
  await link.click();
  await buyer.page.waitForURL(url => url.pathname === thread());
  await expect(message(buyer, reply)).toHaveCount(1);
  await expect.poll(() => badge(buyer, 'messages')).toBe(0);
  buyer.verify();
});

test('a favourite reaches the seller live and marks read, deep links to the listing and deletes with the server agreeing', async () => {
  const { page } = seller;
  await navigate(page, '/notifications');
  await expect(page.getByRole('heading', { name: 'Notifications', level: 1 })).toBeVisible();
  const favourites = page.locator('.notification-row').filter({ hasText: 'to their favourites' });
  const [rowsBefore, unreadBefore] = [await favourites.count(), await badge(seller, 'notifications')];

  const listing = `/listings/${testData().hardwareListingId}`;
  // The page scans the caller's favourites after rendering, so the button only settles once that read is done.
  const scanned = buyer.page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/favourites');
  await buyer.page.goto(listing);
  await scanned;
  const add = buyer.page.getByRole('button', { name: 'Add to favourites', exact: true });
  const remove = buyer.page.getByRole('button', { name: 'Remove from favourites', exact: true });
  await expect(add.or(remove)).toBeVisible();
  // The other viewport project may have left the listing favourited; start from the unfavourited state either way.
  if (await remove.isVisible()) { await remove.click(); await expect(add).toBeVisible(); }
  const favourited = buyer.page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/favourites/${testData().hardwareListingId}` && r.ok());
  await add.click();
  await favourited;

  await expect(favourites).toHaveCount(rowsBefore + 1);
  const row = favourites.first();
  await expect(row).toHaveClass(/unread/);
  await expect(row).toContainText(buyerName);
  await expect.poll(() => badge(seller, 'notifications')).toBe(unreadBefore + 1);

  await row.getByRole('button', { name: 'Mark read', exact: true }).click();
  await expect(row).not.toHaveClass(/unread/);
  await expect.poll(() => badge(seller, 'notifications')).toBe(unreadBefore);

  await row.getByRole('link').click();
  await page.waitForURL(url => url.pathname === listing);
  await navigate(page, '/notifications');
  await expect(favourites).toHaveCount(rowsBefore + 1);

  const removed = page.waitForResponse(r => r.request().method() === 'DELETE' && /^\/notifications\/[\w-]+$/.test(new URL(r.url()).pathname) && r.ok());
  await favourites.first().getByRole('button', { name: 'Delete notification' }).click();
  await removed;
  await expect(favourites).toHaveCount(rowsBefore);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Notifications', level: 1 })).toBeVisible();
  await expect(favourites).toHaveCount(rowsBefore);
  await expect.poll(() => badge(seller, 'notifications')).toBe(unreadBefore);
  seller.verify();
  buyer.verify();
});

test('a message notification deep links into its conversation', async () => {
  const { page } = seller;
  await navigate(page, '/notifications');
  const link = page.locator(`.notification-list a[href="${thread()}"]`).first();
  await expect(link).toContainText('sent you a message about');
  await link.click();
  await page.waitForURL(url => url.pathname === thread());
  await expect(page.getByRole('textbox', { name: 'Write a message' })).toBeVisible();
  seller.verify();
});

test('an order notification deep links into the order it names', async ({ browser }, testInfo) => {
  const fixtures = fixtureOrders();
  const actor = await signedInActor(browser, testInfo, fixtures.buyer);
  try {
    await actor.page.goto('/notifications');
    const link = actor.page.locator(`.notification-list a[href="/orders/${fixtures.hardwareId}"]`).first();
    await expect(link).toBeVisible();
    await link.click();
    await actor.page.waitForURL(url => url.pathname === `/orders/${fixtures.hardwareId}`);
    await expect(actor.page.getByRole('heading', { level: 1 })).toBeVisible();
    actor.verify();
  } finally { await actor.context.close(); }
});
