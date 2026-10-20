import type { Page } from '@playwright/test';
import { test, expect, assertNoOverflow } from './helpers/assert-clean-page';
import { runtimeConfig } from './helpers/cognito-admin';
import { fixtureOrders, readOrder } from './helpers/fixture-actors';
import type { ReviewPage } from '../src/app/core/api/api-models';

// These reviews are the real ones the lifecycle suite posts on the paid-order fixtures; run it before this spec.
test.use({ storageState: { cookies: [], origins: [] }, trace: 'retain-on-failure' });

async function fixtureSeller(): Promise<string> {
  const fixtures = fixtureOrders();
  return (await readOrder(fixtures.hardwareId, fixtures.buyer)).sellerSub;
}

async function reviewPage(page: Page, sub: string, query: string): Promise<ReviewPage> {
  const response = await page.request.get(`${runtimeConfig().apiBaseUrl}/users/${sub}/reviews?${query}`);
  expect(response.ok()).toBe(true);
  return response.json() as Promise<ReviewPage>;
}

test('anonymous profile shows the real reviews, author usernames and a long comment without overflow @phone', async ({ page }) => {
  const sub = await fixtureSeller();
  const reviews = await reviewPage(page, sub, 'size=5');
  expect(reviews.items.length, 'The lifecycle suite has not posted both fixture reviews').toBeGreaterThanOrEqual(2);
  const all = await reviewPage(page, sub, 'size=50');
  const long = all.items.find(review => (review.comment ?? '').length >= 400);
  expect(long, 'No long real review found').toBeDefined();

  await page.goto(`/u/${sub}`);
  const column = page.locator('[data-column="reviews"]');
  await expect(column.locator('[data-review]')).toHaveCount(reviews.items.length);
  for (const [index, review] of reviews.items.entries()) {
    const card = column.locator('[data-review]').nth(index);
    await expect(card).toBeVisible();
    await expect(card.locator('.about')).toHaveText(review.listingTitle);
    if (review.authorUsername) await expect(card.locator('.author')).toHaveText(`@${review.authorUsername}`);
    await expect(card.locator('.stars')).toHaveAttribute('aria-label', `${review.rating} out of 5 stars`);
  }
  const more = column.getByRole('button', { name: 'Show more', exact: true });
  if (reviews.nextCursor !== null) {
    await expect(more).toBeVisible();
  } else {
    await expect(more).toHaveCount(0);
  }
  const longIndex = all.items.findIndex(review => review.id === long!.id);
  for (let loaded = reviews.items.length; loaded <= longIndex; loaded += 5) {
    await more.click();
    await expect(column.locator('[data-review]')).toHaveCount(Math.min(loaded + 5, all.items.length));
  }
  await expect(column.locator('[data-review]').nth(longIndex).locator('.comment')).toHaveText(long!.comment!);
  await assertNoOverflow(page);
});

test('real review cursor pages through the SPA at the real page size of 5 with the keyboard @phone', async ({ page }) => {
  const sub = await fixtureSeller();
  const all = await reviewPage(page, sub, 'size=50');
  expect(all.items.length, 'Real review pagination needs at least 6 real reviews from the fixture seller').toBeGreaterThanOrEqual(6);

  const requestSizes: string[] = [];
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname === `/users/${sub}/reviews`) {
      requestSizes.push(url.searchParams.get('size') ?? '');
    }
  });
  await page.goto(`/u/${sub}`);
  const column = page.locator('[data-column="reviews"]');
  await expect(column.locator('[data-review]')).toHaveCount(5);
  const more = column.getByRole('button', { name: 'Show more', exact: true });
  for (let loaded = 5; loaded < all.items.length; loaded += 5) {
    await more.focus();
    await page.keyboard.press('Enter');
    await expect(column.locator('[data-review]')).toHaveCount(Math.min(loaded + 5, all.items.length));
  }
  await expect(more).toHaveCount(0);
  expect(requestSizes.every(size => size === '5')).toBe(true);
  expect(requestSizes).toHaveLength(Math.ceil(all.items.length / 5));
  const renderedTitles = await column.locator('[data-review] .about').allTextContents();
  expect(renderedTitles).toEqual(all.items.map(review => review.listingTitle));
  await assertNoOverflow(page);
});
