import { test, expect, assertNoOverflow } from './helpers/assert-clean-page';
import { testData } from './helpers/test-data';
import { runtimeConfig } from './helpers/cognito-admin';
import type { Page } from '@playwright/test';
import type { ListingDetailResponse, PublicProfileResponse, ReviewPage } from '../src/app/core/api/api-models';

test.use({ storageState: { cookies: [], origins: [] }, trace: 'retain-on-failure' });

async function publicSeller(page: Page): Promise<PublicProfileResponse> {
  const api = runtimeConfig().apiBaseUrl;
  const listingResponse = await page.request.get(`${api}/listings/${testData().hardwareListingId}`);
  expect(listingResponse.ok()).toBe(true);
  const listing = await listingResponse.json() as ListingDetailResponse;
  const profileResponse = await page.request.get(`${api}/users/${listing.sellerSub}`);
  expect(profileResponse.ok()).toBe(true);
  return profileResponse.json() as Promise<PublicProfileResponse>;
}

test('anonymous seller link exposes real aggregates, listing pagination and empty reviews @phone', async ({ page }) => {
  const seller = await publicSeller(page);
  await page.goto(`/listings/${testData().hardwareListingId}`);
  const sellerLink = page.getByRole('main').getByRole('link', { name: `@${seller.username}` });
  await expect(sellerLink).toBeVisible();
  await sellerLink.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(url => url.pathname === `/u/${seller.sub}`);
  await expect(page.getByRole('heading', { name: `@${seller.username}`, exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Log in', exact: true })).toBeVisible();
  await expect(page.getByText(`${seller.completedSales} sales`, { exact: true })).toBeVisible();
  await expect(page.getByText(`${seller.followerCount} followers`, { exact: true })).toBeVisible();
  await expect(page.getByText(`${seller.followingCount} following`, { exact: true })).toBeVisible();
  await expect(page.getByText('DEVELOPER', { exact: true })).toBeVisible();
  const avatar = page.getByRole('img', { name: `@${seller.username}`, exact: true });
  expect(seller.avatar).toBeNull();
  await expect(avatar).toHaveText(seller.username[0].toUpperCase());
  await expect(avatar.locator('img')).toHaveCount(0);

  const listings = page.locator('[data-column="listings"]');
  await expect(listings.getByRole('article')).toHaveCount(20);
  await assertNoOverflow(page);
  await listings.getByRole('button', { name: 'Show more', exact: true }).click();
  await expect(listings.getByRole('article')).toHaveCount(27);
  await expect(listings.getByRole('button', { name: 'Show more', exact: true })).toHaveCount(0);
  const reviewsResponse = await page.request.get(`${runtimeConfig().apiBaseUrl}/users/${seller.sub}/reviews?size=20`);
  expect(reviewsResponse.ok()).toBe(true);
  const reviews = await reviewsResponse.json() as ReviewPage;
  expect(reviews.items).toEqual([]);
  expect(reviews.nextCursor).toBeNull();
  await expect(page.locator('[data-column="reviews"]')).toContainText('No reviews yet.');
  await assertNoOverflow(page);
});

test('anonymous direct profile wraps long identity and bio and gates following @phone', async ({ page }) => {
  const seller = await publicSeller(page);
  expect(seller.username).toHaveLength(30);
  expect(seller.bio).toHaveLength(500);
  await page.goto(`/u/${seller.sub}`);
  await expect(page.getByRole('heading', { name: `@${seller.username}`, exact: true })).toBeVisible();
  await expect(page.getByText(seller.bio!, { exact: true })).toBeVisible();
  const follow = page.getByRole('button', { name: `Follow @${seller.username}`, exact: true });
  await follow.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Log in to continue', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Not now', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(follow).toBeFocused();
  await assertNoOverflow(page);
  await follow.click();
  await dialog.getByRole('link', { name: 'Log in', exact: true }).click();
  await expect(page).toHaveURL(url => url.pathname === '/login' && url.searchParams.get('returnUrl') === `/u/${seller.sub}`);
});
