import { test, expect } from '@playwright/test';

test('marketplace opens with an isolated buyer session @phone', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveTitle(/rebyte/i);
});
