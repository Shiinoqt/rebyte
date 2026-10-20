import type { Request, Response } from '@playwright/test';
import { authenticatedTest as test, expect } from './helpers/assert-clean-page';
import { runtimeConfig } from './helpers/cognito-admin';
import { testData } from './helpers/test-data';

test.use({ trace: 'off' });

test('bare payment return and cancel routes never mutate an awaiting order @phone', async ({
  page,
}) => {
  const gateway = new URL(runtimeConfig().apiBaseUrl).origin;
  const sandbox = (url: URL) =>
    url.hostname === 'www.sandbox.paypal.com' || url.hostname === 'sandbox.paypal.com';
  const mutations: string[] = [];
  const orders = new Map<Request, { id: string; status: string }>();
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (
      url.origin === gateway &&
      request.method() === 'POST' &&
      /^\/(?:orders|payments)\//.test(url.pathname)
    ) {
      mutations.push(url.pathname);
    }
  });
  const orderRead = (response: Response) => {
    const url = new URL(response.url());
    return (
      url.origin === gateway &&
      response.request().method() === 'GET' &&
      /^\/orders\/[^/]+$/.test(url.pathname)
    );
  };

  // Capture gateway bodies before a navigation can discard Chromium's copy.
  await page.route(
    (url) => url.origin === gateway && /^\/orders\/[^/]+$/.test(url.pathname),
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const response = await route.fetch();
      orders.set(route.request(), await response.json());
      await route.fulfill({ response });
    },
  );

  await page.goto(`/checkout/${testData().softwareListingId}`);
  await page.route(sandbox, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/html',
      body: '<title>PayPal approval stub</title>',
    }),
  );
  const handoff = page.waitForURL(sandbox);
  await page.getByRole('button', { name: /^Pay with PayPal/ }).click();
  await handoff;

  let orderId: string | undefined;
  for (const path of ['/checkout/return', '/checkout/cancel']) {
    for (const reload of [false, true]) {
      const read = page.waitForResponse(orderRead);
      if (reload) await page.reload();
      else await page.goto(path);
      const response = await read;
      expect(response.status()).toBe(200);
      const order = orders.get(response.request())!;
      expect(order.status).toBe('AWAITING_PAYMENT');
      expect(order.id).toEqual(orderId ?? expect.any(String));
      orderId = order.id;
      await expect(
        page.getByRole('heading', { name: 'Payment confirmation is pending', exact: true }),
      ).toBeVisible();
      expect(mutations).toEqual([]);
    }
  }

  const read = page.waitForResponse(orderRead);
  await page.goto(`/orders/${orderId}`);
  const response = await read;
  expect(response.status()).toBe(200);
  const order = orders.get(response.request())!;
  expect(order.id).toBe(orderId);
  expect(order.status).toBe('AWAITING_PAYMENT');
  expect(mutations).toEqual([]);
});
