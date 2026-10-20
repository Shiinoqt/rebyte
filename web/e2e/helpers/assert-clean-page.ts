import { test as base, expect, type Page } from '@playwright/test';
import { runtimeConfig } from './cognito-admin';

interface Failure { kind: string; url: string; status?: number; reason?: string }

export function monitorPage(page: Page, origins: string[], allowed: readonly ((failure: Failure) => boolean)[] = []): () => void {
  const issues: Failure[] = [];
  const url = (raw: string) => { const value = new URL(raw); return value.origin + value.pathname; };
  const record = (failure: Failure) => { if (!allowed.some(matches => matches(failure))) issues.push(failure); };
  const tracked = (raw: string) => origins.includes(new URL(raw).origin);
  page.on('pageerror', () => record({ kind: 'pageerror', url: url(page.url()) }));
  // The gateway deliberately rate limits /ws handshakes per IP; only that categorical reason is kept, never the message text.
  const rateLimitedSocket = (text: string | undefined) => /WebSocket connection to .*\/ws.* failed.*429/.test(text ?? '');
  page.on('console', message => {
    if (message.type() === 'error') {
      const text = message.text?.();
      const reason = rateLimitedSocket(text) ? 'websocket-rate-limited' : /status of 409/.test(text ?? '') ? 'conflict-response' : undefined;
      record({ kind: 'console-error', url: url(page.url()), ...(reason ? { reason } : {}) });
    }
  });
  page.on('requestfailed', request => {
    if (tracked(request.url())) record({ kind: 'requestfailed', url: url(request.url()), reason: request.failure?.()?.errorText });
  });
  page.on('response', response => {
    if (tracked(response.url()) && response.status() >= 400) {
      record({ kind: 'http', url: url(response.url()), status: response.status() });
    }
  });
  page.on('request', request => {
    const value = new URL(request.url());
    if (tracked(request.url()) && request.headers()['authorization']) {
      record({ kind: 'unexpected-public-authorization', url: url(request.url()) });
    }
    if (tracked(request.url()) && value.pathname === '/search' && value.searchParams.has('sort')) {
      record({ kind: 'unsupported-sort', url: url(request.url()) });
    }
  });
  return () => expect(issues, 'Unexpected public browser/API failures').toEqual([]);
}
export async function assertNoOverflow(page: Page): Promise<void> {
  const width = page.viewportSize()!.width;
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
}
export const test = base.extend<{ publicPageEvidence: void }>({
  publicPageEvidence: [async ({ page, baseURL }, use) => {
    const verify = monitorPage(page, [new URL(baseURL!).origin, new URL(runtimeConfig().apiBaseUrl).origin]);
    await use();
    verify();
  }, { auto: true }],
});
// Signed-in pages legitimately send Authorization, and a navigation cancels their in-flight profile and list refreshes.
export const authenticatedTest = base.extend<{ pageEvidence: void }>({
  pageEvidence: [async ({ page, baseURL }, use) => {
    const verify = monitorPage(page, [new URL(baseURL!).origin, new URL(runtimeConfig().apiBaseUrl).origin],
      [failure => failure.kind === 'unexpected-public-authorization',
        failure => failure.kind === 'requestfailed' && failure.reason === 'net::ERR_ABORTED',
        failure => failure.kind === 'console-error' && failure.reason === 'websocket-rate-limited']);
    await use();
    verify();
  }, { auto: true }],
});
export { expect };
