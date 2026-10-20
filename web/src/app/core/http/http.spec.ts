import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import {
  HttpClient,
  HttpErrorResponse,
  HttpInterceptorFn,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { firstValueFrom, retry } from 'rxjs';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import { AppConfigService } from '../config/app-config';
import { AccessTokenOptions, AuthAdapter } from '../auth/auth.adapter';
import { AuthStore } from '../auth/auth.store';
import { ApiClient } from '../api/api-client';
import { authInterceptor } from './auth.interceptor';
import { AppError } from './app-error';
import { errorCopy } from './error-copy';
import { problemInterceptor } from './problem.interceptor';

class FakeAuthAdapter implements AuthAdapter {
  configure = vi.fn();
  signUp = vi.fn();
  confirmSignUp = vi.fn();
  resendSignUpCode = vi.fn();
  autoSignIn = vi.fn();
  signIn = vi.fn();
  resetPassword = vi.fn();
  confirmResetPassword = vi.fn();
  updatePassword = vi.fn();
  signOut = vi.fn();
  fetchSession = vi.fn(async () => ({ sub: 'member', groups: ['BUYER'] }));
  onAuthEvent = vi.fn(() => () => undefined);
  accessToken = vi.fn(async (options?: AccessTokenOptions): Promise<string | null> =>
    options?.forceRefresh ? 'fresh-access' : 'stale-access',
  );
}

const API = 'http://api.test';
let backend: HttpTestingController;

async function setup(interceptors: HttpInterceptorFn[] = [authInterceptor], apiBaseUrl = API) {
  const adapter = new FakeAuthAdapter();
  const router = { url: '/checkout/item?offer=offer-id', navigate: vi.fn(async () => true) };
  TestBed.configureTestingModule({
    providers: [
      provideTestAppConfig(),
      { provide: AppConfigService, useValue: { config: { ...TEST_APP_CONFIG, apiBaseUrl } } },
      { provide: AuthAdapter, useValue: adapter },
      { provide: Router, useValue: router },
      provideHttpClient(withInterceptors(interceptors)),
      provideHttpClientTesting(),
    ],
  });
  backend = TestBed.inject(HttpTestingController);
  const store = TestBed.inject(AuthStore);
  await store.init();
  const cleanup = vi.fn();
  store.registerCleanup(cleanup);
  return { client: TestBed.inject(HttpClient), adapter, router, store, cleanup };
}

function request(url: string): Promise<TestRequest> {
  return vi.waitFor(() => backend.expectOne(url));
}

function unauthorized(req: TestRequest) {
  req.flush(
    { type: 'https://rebyte.it/problems/unauthenticated', detail: 'private detail' },
    {
      status: 401,
      statusText: 'Unauthorized',
    },
  );
}

afterEach(() => {
  try {
    backend?.verify();
  } finally {
    TestBed.resetTestingModule();
  }
});

describe('authInterceptor', () => {
  it('retries a protected write once with a forced fresh access token', async () => {
    const { client, adapter, router, store } = await setup();
    const result = firstValueFrom(client.post(`${API}/orders`, { listingId: 'item' }));
    const initial = await request(`${API}/orders`);
    expect(initial.request.headers.get('Authorization')).toBe('Bearer stale-access');
    unauthorized(initial);
    const refreshed = await request(`${API}/orders`);
    expect(refreshed.request.headers.get('Authorization')).toBe('Bearer fresh-access');
    expect(refreshed.request.body).toEqual({ listingId: 'item' });
    expect(refreshed.request.method).toBe('POST');
    expect(refreshed.request.headers.keys()).toEqual(['Authorization']);
    refreshed.flush({ id: 'order' });
    expect(await result).toEqual({ id: 'order' });
    expect(adapter.accessToken.mock.calls).toEqual([[], [{ forceRefresh: true }]]);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(store.isAuthenticated()).toBe(true);
  });

  it('clears session and redirects after the second protected 401 without looping', async () => {
    const { client, adapter, router, store, cleanup } = await setup();
    const result = firstValueFrom(client.post(`${API}/orders`, {})).catch(
      (error: unknown) => error,
    );
    unauthorized(await request(`${API}/orders`));
    unauthorized(await request(`${API}/orders`));
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(adapter.accessToken.mock.calls).toEqual([[], [{ forceRefresh: true }]]);
    expect(store.status()).toBe('guest');
    expect(cleanup).toHaveBeenCalledTimes(1);
    expect(router.navigate).toHaveBeenCalledExactlyOnceWith(['/login'], {
      queryParams: { returnUrl: '/checkout/item?offer=offer-id' },
    });
    backend.expectNone(`${API}/orders`);
  });

  it.each([
    '/search?q=gpu',
    '/search/facets',
    '/categories',
    '/categories/leaf/schema',
    '/listings/item',
    '/users/member',
    '/users/member/reviews',
    '/users/member/listings',
  ])('recovers public GET %s anonymously after two bearer 401s', async (path) => {
    const { client, adapter, router, store, cleanup } = await setup();
    const result = firstValueFrom(client.get(API + path));
    unauthorized(await request(API + path));
    unauthorized(await request(API + path));
    const anonymous = await request(API + path);
    expect(anonymous.request.headers.has('Authorization')).toBe(false);
    anonymous.flush({ items: [] });
    expect(await result).toEqual({ items: [] });
    expect(adapter.accessToken.mock.calls).toEqual([[], [{ forceRefresh: true }]]);
    expect(store.status()).toBe('guest');
    expect(cleanup).toHaveBeenCalledTimes(1);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it.each([
    '/users/me',
    '/users/me/reviews',
    '/users/%6de',
    '/listings/mine',
    '/listings/%6dine',
    '/listings/item/photos',
    '/orders',
    '/searching',
  ])('does not treat protected GET %s as public', async (path) => {
    const { client, router } = await setup();
    const result = firstValueFrom(client.get(API + path)).catch((error: unknown) => error);
    unauthorized(await request(API + path));
    unauthorized(await request(API + path));
    backend.expectNone(API + path);
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(router.navigate).toHaveBeenCalledTimes(1);
    backend.expectNone(API + path);
  });

  it('never retries writes to a public read path anonymously', async () => {
    const { client, router } = await setup();
    const result = firstValueFrom(client.post(`${API}/search`, {})).catch(
      (error: unknown) => error,
    );
    unauthorized(await request(`${API}/search`));
    unauthorized(await request(`${API}/search`));
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(router.navigate).toHaveBeenCalledTimes(1);
    backend.expectNone(`${API}/search`);
  });

  it('ends the public retry after one anonymous 401', async () => {
    const { client, adapter, router } = await setup();
    const result = firstValueFrom(client.get(`${API}/search`)).catch((error: unknown) => error);
    unauthorized(await request(`${API}/search`));
    unauthorized(await request(`${API}/search`));
    const anonymous = await request(`${API}/search`);
    expect(anonymous.request.headers.has('Authorization')).toBe(false);
    unauthorized(anonymous);
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(adapter.accessToken).toHaveBeenCalledTimes(2);
    expect(router.navigate).toHaveBeenCalledTimes(1);
    backend.expectNone(`${API}/search`);
  });

  it.each([null, 'reject'] as const)(
    'falls back to anonymous when refresh returns %s',
    async (outcome) => {
      const { client, adapter, router, store } = await setup();
      const result = firstValueFrom(client.get(`${API}/search`));
      const initial = await request(`${API}/search`);
      if (outcome === 'reject')
        adapter.accessToken.mockRejectedValueOnce(new Error('refresh failed'));
      else adapter.accessToken.mockResolvedValueOnce(null);
      unauthorized(initial);
      const anonymous = await request(`${API}/search`);
      expect(anonymous.request.headers.has('Authorization')).toBe(false);
      anonymous.flush([]);
      expect(await result).toEqual([]);
      expect(store.status()).toBe('guest');
      expect(router.navigate).not.toHaveBeenCalled();
      expect(adapter.accessToken).toHaveBeenCalledTimes(2);
    },
  );

  it('validates the router URL before redirecting when refresh is unavailable', async () => {
    const { client, adapter, router } = await setup();
    router.url = '/%2f%2fevil.test';
    const result = firstValueFrom(client.post(`${API}/orders`, {})).catch(
      (error: unknown) => error,
    );
    const initial = await request(`${API}/orders`);
    adapter.accessToken.mockResolvedValueOnce(null);
    unauthorized(initial);
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(router.navigate).toHaveBeenCalledExactlyOnceWith(['/login'], {
      queryParams: { returnUrl: '/' },
    });
    backend.expectNone(`${API}/orders`);
  });

  it('sends anonymous requests without a header when no access token exists', async () => {
    const { client, adapter } = await setup();
    adapter.accessToken.mockResolvedValue(null);
    const result = firstValueFrom(client.get(`${API}/search`));
    const anonymous = await request(`${API}/search`);
    expect(anonymous.request.headers.has('Authorization')).toBe(false);
    anonymous.flush([]);
    expect(await result).toEqual([]);
  });

  it.each([
    'https://bucket.s3.eu-south-1.amazonaws.com/key?X-Amz-Signature=signature',
    'https://cognito-idp.eu-south-1.amazonaws.com',
    'https://www.paypal.com/orders',
    'http://api.test.evil.test/orders',
    'http://api.test@evil.test/orders',
    'http://api.test:81/orders',
    'https://api.test/orders',
    '//evil.test/orders',
    '/orders',
  ])('does not send credentials or refresh on unrelated URL %s', async (url) => {
    const { client, adapter, router, store } = await setup();
    const result = firstValueFrom(
      client.post(url, {}, { headers: { Authorization: 'Bearer supplied-access' } }),
    ).catch((error: unknown) => error);
    const external = await request(url);
    expect(external.request.headers.has('Authorization')).toBe(false);
    unauthorized(external);
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(adapter.accessToken).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(store.isAuthenticated()).toBe(true);
  });

  it.each(['/api', '/api/', '/api/orders'])(
    'attaches a token inside the configured API path at %s',
    async (path) => {
      const { client } = await setup([authInterceptor], API + '/api/');
      const result = firstValueFrom(client.get(API + path));
      const scoped = await request(API + path);
      expect(scoped.request.headers.get('Authorization')).toBe('Bearer stale-access');
      scoped.flush([]);
      expect(await result).toEqual([]);
    },
  );

  it.each(['/api-evil/orders', '/apievil', '/orders', '/api/../outside', '/api/%2e%2e/outside'])(
    'excludes URL %s outside the API base path',
    async (path) => {
      const { client, adapter } = await setup([authInterceptor], API + '/api');
      const result = firstValueFrom(client.get(API + path));
      const external = await request(API + path);
      expect(external.request.headers.has('Authorization')).toBe(false);
      external.flush([]);
      expect(await result).toEqual([]);
      expect(adapter.accessToken).not.toHaveBeenCalled();
    },
  );

  it('classifies public GETs relative to the configured API base path', async () => {
    const { client, router } = await setup([authInterceptor], API + '/api');
    const result = firstValueFrom(client.get(`${API}/api/search`));
    unauthorized(await request(`${API}/api/search`));
    unauthorized(await request(`${API}/api/search`));
    const anonymous = await request(`${API}/api/search`);
    expect(anonymous.request.headers.has('Authorization')).toBe(false);
    anonymous.flush([]);
    expect(await result).toEqual([]);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does not refresh or clear a session for a non-401 error', async () => {
    const { client, adapter, router, store } = await setup();
    const result = firstValueFrom(client.get(`${API}/orders`)).catch((error: unknown) => error);
    (await request(`${API}/orders`)).flush({}, { status: 403, statusText: 'Forbidden' });
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(adapter.accessToken).toHaveBeenCalledTimes(1);
    expect(store.isAuthenticated()).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('retains the refresh marker when a caller resubscribes through retry', async () => {
    const { client, adapter } = await setup();
    const result = firstValueFrom(client.post(`${API}/orders`, {}).pipe(retry(1))).catch(
      (error: unknown) => error,
    );
    unauthorized(await request(`${API}/orders`));
    unauthorized(await request(`${API}/orders`));
    unauthorized(await request(`${API}/orders`));
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    expect(
      adapter.accessToken.mock.calls.filter(([options]) => options?.forceRefresh),
    ).toHaveLength(1);
    backend.expectNone(`${API}/orders`);
  });
});

@Component({ template: '<p>{{ copy }}</p>' })
class ErrorScreen {
  copy = '';
}

async function mappedError(body: unknown, status = 400): Promise<AppError> {
  const { client } = await setup([problemInterceptor, authInterceptor]);
  const result = firstValueFrom(client.get(`${API}/orders`)).catch((error: unknown) => error);
  const req = await request(`${API}/orders`);
  req.flush(body as object, {
    status,
    statusText: 'Failure',
    headers: { 'Content-Type': 'application/problem+json' },
  });
  return (await result) as AppError;
}

describe('problemInterceptor with authInterceptor', () => {
  it('normalizes a service problem and preserves only safe typed extras', async () => {
    const error = await mappedError(
      {
        title: 'price-mismatch',
        type: 'urn:rebyte:problem:price-mismatch',
        status: 200,
        detail: 'private backend detail',
        currentTotal: 49.95,
        conversationId: 'conversation-id',
        stack: 'private stack',
        diagnostic: { password: 'secret' },
        unknown: 'private value',
      },
      409,
    );
    expect(error).toEqual({
      status: 409,
      code: 'price-mismatch',
      type: 'urn:rebyte:problem:price-mismatch',
      fieldErrors: {},
      extras: { currentTotal: 49.95, conversationId: 'conversation-id' },
    });
    expect(errorCopy(error)).toBe('The total changed to 49.95. Review it and try again.');
    expect(JSON.stringify(error)).not.toMatch(/private|secret|detail|stack|diagnostic/);
  });

  it.each([
    [403, 'access-denied', 'Forbidden'],
    [429, 'rate-limited', 'Too Many Requests'],
  ])('maps gateway %s by type and status instead of human title', async (status, code, title) => {
    const error = await mappedError(
      {
        title,
        type: `https://rebyte.it/problems/${code}`,
        detail: 'private gateway detail',
      },
      status,
    );
    expect(error.code).toBe(code);
    expect(error.status).toBe(status);
    expect(error.type).toBe(`https://rebyte.it/problems/${code}`);
    expect(errorCopy(error)).not.toContain('private');
  });

  it('does not let a mismatched gateway type override the HTTP status', async () => {
    const error = await mappedError(
      { title: 'Forbidden', type: 'https://rebyte.it/problems/rate-limited' },
      403,
    );
    expect(error.code).toBe('access-denied');
  });

  it('keeps the validation map as fieldErrors and filters nested values and prototype keys', async () => {
    const errors = JSON.parse(
      '{"email":"must be an email","amount":"must be positive","nested":{"detail":"secret"},"array":["secret"],"count":9,"__proto__":"secret","constructor":"secret"}',
    );
    const error = await mappedError({
      title: 'validation-failed',
      type: 'urn:rebyte:problem:validation-failed',
      errors,
      detail: 'private validation detail',
    });
    expect(error.fieldErrors).toEqual({ email: 'must be an email', amount: 'must be positive' });
    expect(error.extras).toEqual({});
    expect(errorCopy(error)).toBe('Check the highlighted fields and try again.');
    expect(JSON.stringify(error)).not.toMatch(/secret|private/);
  });

  it.each([[], 'not a map', null])('rejects malformed validation map %s', async (errors) => {
    const error = await mappedError({ title: 'validation-failed', errors });
    expect(error.fieldErrors).toEqual({});
  });

  it('maps a network failure to offline without carrying the original event', async () => {
    const { client, adapter } = await setup([problemInterceptor, authInterceptor]);
    const result = firstValueFrom(client.get(`${API}/orders`)).catch((error: unknown) => error);
    (await request(`${API}/orders`)).error(new ProgressEvent('error'));
    const error = (await result) as AppError;
    expect(error).toEqual({
      status: 0,
      code: 'offline',
      type: 'about:blank',
      fieldErrors: {},
      extras: {},
    });
    expect(errorCopy(error)).toBe('You appear to be offline.');
    expect(adapter.accessToken).toHaveBeenCalledTimes(1);
  });

  it.each([
    '<script>steal()</script>',
    'Bad Request',
    'Price-Mismatch',
    '-invalid',
    'invalid-',
    'invalid--code',
    '',
    null,
    { detail: 'secret' },
  ])('discards hostile or non-kebab title %s and never renders detail', async (title) => {
    const error = await mappedError({
      title,
      type: { detail: 'secret' },
      detail: '<script>private detail</script>',
    });
    expect(error.code).toBe('unknown-error');
    expect(error.type).toBe('about:blank');
    const fixture = TestBed.createComponent(ErrorScreen);
    fixture.componentInstance.copy = errorCopy(error);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toBe('Something went wrong. Try again in a minute.');
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(JSON.stringify(error)).not.toMatch(/private|secret|script|detail/);
  });

  it.each([null, 'private plain text', ['private array'], 42])(
    'normalizes non-problem body %s safely',
    async (body) => {
      const error = await mappedError(body);
      expect(error).toEqual({
        status: 400,
        code: 'unknown-error',
        type: 'about:blank',
        fieldErrors: {},
        extras: {},
      });
    },
  );

  it.each([500, 502, 503, 599])(
    'always gives generic copy for status %s, even with a known code',
    async (status) => {
      const error = await mappedError(
        { title: 'payment-unavailable', detail: 'private server internals' },
        status,
      );
      expect(error.status).toBe(status);
      expect(error.code).toBe('payment-unavailable');
      expect(errorCopy(error)).toBe('Something went wrong. Try again in a minute.');
      expect(JSON.stringify(error)).not.toMatch(/private|internals|detail/);
    },
  );

  it('rejects unsafe extra values rather than interpolating backend content', async () => {
    const error = await mappedError(
      {
        title: 'price-mismatch',
        currentTotal: '<script>private</script>',
        conversationId: { detail: 'private' },
        extras: { currentTotal: 10 },
      },
      409,
    );
    expect(error.extras).toEqual({});
    expect(errorCopy(error)).toBe('The total changed. Review it and try again.');
  });

  it('lets auth recover a raw 401 before mapping errors', async () => {
    const { client, adapter, router } = await setup([problemInterceptor, authInterceptor]);
    const result = firstValueFrom(client.post(`${API}/orders`, {}));
    unauthorized(await request(`${API}/orders`));
    const refreshed = await request(`${API}/orders`);
    expect(refreshed.request.headers.get('Authorization')).toBe('Bearer fresh-access');
    refreshed.flush({ id: 'order' });
    expect(await result).toEqual({ id: 'order' });
    expect(adapter.accessToken.mock.calls).toEqual([[], [{ forceRefresh: true }]]);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('maps a repeated protected 401 only after one refresh and session cleanup', async () => {
    const { client, adapter, store, cleanup, router } = await setup([
      problemInterceptor,
      authInterceptor,
    ]);
    const result = firstValueFrom(client.post(`${API}/orders`, {})).catch(
      (error: unknown) => error,
    );
    unauthorized(await request(`${API}/orders`));
    unauthorized(await request(`${API}/orders`));
    expect(await result).toEqual({
      status: 401,
      code: 'unauthenticated',
      type: 'https://rebyte.it/problems/unauthenticated',
      fieldErrors: {},
      extras: {},
    });
    expect(adapter.accessToken.mock.calls).toEqual([[], [{ forceRefresh: true }]]);
    expect(store.status()).toBe('guest');
    expect(cleanup).toHaveBeenCalledTimes(1);
    expect(router.navigate).toHaveBeenCalledTimes(1);
    backend.expectNone(`${API}/orders`);
  });

  it('retains anonymous public recovery with the final interceptor order', async () => {
    const { client, router } = await setup([problemInterceptor, authInterceptor]);
    const result = firstValueFrom(client.get(`${API}/search`));
    unauthorized(await request(`${API}/search`));
    unauthorized(await request(`${API}/search`));
    const anonymous = await request(`${API}/search`);
    expect(anonymous.request.headers.has('Authorization')).toBe(false);
    anonymous.flush([]);
    expect(await result).toEqual([]);
    expect(router.navigate).not.toHaveBeenCalled();
  });
});

describe('errorCopy', () => {
  it.each([
    ['listing-unavailable', 'This item is no longer available.'],
    ['self-purchase-forbidden', "You can't buy your own listing."],
    ['payment-method-not-allowed', 'This listing does not accept PayPal.'],
    [
      'payment-unavailable',
      "PayPal is not responding. You haven't been charged. Try again in a minute.",
    ],
    [
      'checkout-outcome-unknown',
      "We couldn't confirm your order. Check your orders before trying again.",
    ],
    ['duplicate-pending-offer', 'You already have a pending offer on this item.'],
    ['self-offer-forbidden', "You can't make an offer on your own listing."],
    ['offer-hardware-only', 'Offers are for hardware listings only.'],
    ['invalid-offer-amount', 'Enter an amount below the asking price.'],
    ['offer-not-pending', 'This offer was already answered.'],
    ['problem-not-reportable', "A problem can't be reported for this order now."],
    ['problem-already-reported', 'A problem was already reported for this order.'],
    ['order-not-reviewable', 'You can review this order once it is delivered.'],
    ['review-already-exists', 'You already reviewed this order.'],
    ['conversation-exists', ''],
    ['self-conversation', "You can't message yourself."],
    ['listing-not-available', 'This listing is no longer available to message about.'],
    ['listing-not-editable', "Sold or removed listings can't be edited."],
    ['photo-limit-exceeded', 'A listing can have up to 5 photos.'],
    ['developer-profile-required', 'Become a developer to sell software.'],
    ['category-not-leaf', 'Choose the most specific category.'],
    [
      'photo-key-not-on-listing',
      'One of the photos is no longer on this listing. Reload the page and try again.',
    ],
    ['licence-required', 'Your licence for this item is not active.'],
    ['artifact-not-available', 'This version has no installer yet.'],
    ['listing-moderated', 'This listing was removed.'],
    ['unauthenticated', 'Log in to continue.'],
    ['access-denied', "You don't have permission to do that."],
    ['rate-limited', "You're doing that too quickly. Wait a moment and try again."],
  ])('provides screen copy for normalized code %s', (code, copy) => {
    expect(errorCopy({ status: 400, code, type: 'about:blank', fieldErrors: {}, extras: {} })).toBe(
      copy,
    );
  });

  it.each(['unknown-code', 'constructor', 'toString', '__proto__'])(
    'uses generic copy for unknown code %s',
    (code) => {
      expect(
        errorCopy({ status: 400, code, type: 'about:blank', fieldErrors: {}, extras: {} }),
      ).toBe('Something went wrong. Try again in a minute.');
    },
  );
});

describe('problemInterceptor behind ApiClient', () => {
  it('decodes the text problem body ApiClient requests and keeps the decimal total', async () => {
    await setup([problemInterceptor, authInterceptor]);
    const api = TestBed.inject(ApiClient);
    const failure = firstValueFrom(api.post('/orders', {})).catch((error: unknown) => error);
    const req = await request(`${API}/orders`);
    req.flush(
      '{"type":"urn:rebyte:problem:price-mismatch","title":"price-mismatch","status":409,"detail":"private","currentTotal":105.00}',
      { status: 409, statusText: 'Conflict' },
    );
    expect(await failure).toEqual({
      status: 409,
      code: 'price-mismatch',
      type: 'urn:rebyte:problem:price-mismatch',
      fieldErrors: {},
      extras: { currentTotal: '105.00' },
    });
    expect(errorCopy((await failure) as AppError)).toBe(
      'The total changed to 105.00. Review it and try again.',
    );
  });
});
