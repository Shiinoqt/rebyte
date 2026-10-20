import { test, expect, assertNoOverflow } from './helpers/assert-clean-page';
import { testData } from './helpers/test-data';
import type { Page } from '@playwright/test';

test.use({ storageState: { cookies: [], origins: [] }, trace: 'retain-on-failure' });

async function searchUpdate(page: Page, change: () => Promise<unknown>, matches: (url: URL) => boolean): Promise<void> {
  const response = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/search' && response.status() === 200 && matches(url);
  });
  await change();
  await (await response).finished();
}

test('anonymous search filters preserve their URL, paginate and survive reload @phone', async ({ page }, info) => {
  const seed = testData();
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Log in', exact: true })).toBeVisible();
  await page.getByRole('searchbox', { name: 'Search listings' }).fill(seed.runId);
  await page.getByRole('searchbox', { name: 'Search listings' }).press('Enter');
  const cards = page.getByRole('main').getByRole('article');
  await expect(cards).toHaveCount(24);
  await page.getByRole('button', { name: 'Show more', exact: true }).click();
  await expect(cards).toHaveCount(27);
  await expect(page.getByRole('button', { name: 'Show more', exact: true })).toHaveCount(0);

  if (info.project.name === 'phone') await page.getByRole('button', { name: /^Filters/ }).click();
  await searchUpdate(page, () => page.getByRole('radio', { name: 'Hardware', exact: true }).click(),
    url => url.searchParams.get('listingType') === 'HARDWARE');
  await expect(cards).toHaveCount(24);
  if (info.project.name === 'desktop') await page.getByRole('button', { name: 'All categories', exact: true }).click();
  await searchUpdate(page, () => page.getByRole('button', { name: `[e2e] ${seed.runId} Hardware`, exact: true }).click(),
    url => url.searchParams.get('categoryId') === seed.hardwareCategoryId);
  await expect(page).toHaveURL(url => url.searchParams.get('cat') === seed.hardwareCategoryId);
  await searchUpdate(page, () => page.getByRole('combobox', { name: 'Fixture edition', exact: true }).selectOption('Standard'),
    url => url.searchParams.get('attr.fixture_edition') === 'Standard');
  await expect(page).toHaveURL(url => url.searchParams.get('attr.fixture_edition') === 'Standard');
  if (info.project.name === 'desktop') await page.getByRole('button', { name: /^Price/ }).click();
  await page.getByRole('textbox', { name: 'Minimum price in euros', exact: true }).fill('10.50');
  await searchUpdate(page, () => page.getByRole('textbox', { name: 'Minimum price in euros', exact: true }).press('Tab'),
    url => url.searchParams.get('minPrice') === '10.50');
  await expect(page).toHaveURL(url => url.searchParams.get('min') === '10.50');
  await page.getByRole('textbox', { name: 'Maximum price in euros', exact: true }).fill('12.00');
  await searchUpdate(page, () => page.getByRole('textbox', { name: 'Maximum price in euros', exact: true }).press('Tab'),
    url => url.searchParams.get('maxPrice') === '12.00');
  await expect(page).toHaveURL(url => url.searchParams.get('max') === '12.00');
  if (info.project.name === 'phone') await page.getByRole('button', { name: /^Show \d+ results?$/ }).click();
  else await page.keyboard.press('Escape');
  await expect(cards).toHaveCount(24);
  const shared = new URL(page.url());
  expect(shared.searchParams.get('q')).toBe(seed.runId);
  expect(shared.searchParams.get('type')).toBe('HARDWARE');
  expect(shared.searchParams.has('sort')).toBe(false);
  await page.reload();
  await expect(cards).toHaveCount(24);
  expect(new URL(page.url()).search).toBe(shared.search);
  await assertNoOverflow(page);
});

test('anonymous listing deep links render real no-photo fallback and gate buying @phone', async ({ page }) => {
  const seed = testData();
  await page.goto(`/listings/${seed.publicFixtures!.noPhotoListingId}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(`[e2e] ${seed.runId} hardware 1`);
  await expect(page.getByRole('group', { name: /^Photos of/ })).toContainText('no photo');
  await expect(page.getByRole('group', { name: /^Photos of/ }).getByRole('button')).toHaveCount(0);
  await expect(page.getByRole('main').getByRole('link', { name: /^@e2e_long_/ })).toBeVisible();
  await page.getByRole('button', { name: 'Buy now', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Log in to continue' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect(page).toHaveURL(url => url.pathname === '/login' &&
    url.searchParams.get('returnUrl') === `/listings/${seed.publicFixtures!.noPhotoListingId}`);
  await expect(page.getByRole('heading', { name: 'Log in', exact: true })).toBeVisible();
  await assertNoOverflow(page);
});
