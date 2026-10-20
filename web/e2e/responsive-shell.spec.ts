import { test, expect, assertNoOverflow } from './helpers/assert-clean-page';
import { testData } from './helpers/test-data';

test.use({ storageState: { cookies: [], origins: [] }, trace: 'retain-on-failure' });

test('anonymous shell keyboard navigation and filters stay reachable @phone', async ({ page }, info) => {
  await page.goto(`/?q=${encodeURIComponent(testData().runId)}`);
  await expect(page.getByRole('main').getByRole('article')).toHaveCount(24);
  await expect(page.getByRole('navigation', { name: 'Bottom navigation' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Log in', exact: true })).toBeVisible();
  const search = page.getByRole('searchbox', { name: 'Search listings' });
  await search.focus();
  await expect(search).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Dark mode', exact: true })).toBeFocused();
  if (info.project.name === 'phone') {
    const trigger = page.getByRole('button', { name: /^Filters/ });
    await trigger.click();
    await expect(page.getByRole('dialog', { name: 'Filters', exact: true })).toBeVisible();
    await expect(page.getByRole('radio', { name: 'All', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(trigger).toBeFocused();
  } else {
    await expect(page.getByRole('radio', { name: 'All', exact: true })).toBeVisible();
  }
  await assertNoOverflow(page);
});

test('long listing title, description and attributes stay inside the viewport @phone', async ({ page }) => {
  const fixture = testData().publicFixtures!;
  await page.goto(`/listings/${fixture.longListingId}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(fixture.longTitle);
  await expect(page.getByText('LongValue'.repeat(12), { exact: true })).toBeVisible();
  await expect(page.getByText('LongUnbrokenDescription'.repeat(25), { exact: true })).toBeVisible();
  await assertNoOverflow(page);
  const buy = page.getByRole('button', { name: 'Buy now', exact: true });
  await buy.focus();
  await expect(buy).toBeFocused();
  await buy.click();
  await expect(page.getByRole('dialog', { name: 'Log in to continue', exact: true })).toBeVisible();
});
