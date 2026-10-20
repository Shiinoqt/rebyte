import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RouterTestingHarness } from '@angular/router/testing';
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../testing/test-config';
import { appConfig } from './app.config';
import { AuthAdapter, AuthIdentity } from './core/auth/auth.adapter';
import { AuthStore } from './core/auth/auth.store';
import { AppConfigService } from './core/config/app-config';
import { AppError } from './core/http/app-error';
import { RealtimeService } from './core/realtime/realtime.service';
import { StompAdapter } from './core/realtime/stomp.adapter';

describe('application composition', () => {
  const identity: AuthIdentity = { sub: 'test-user', groups: [] };
  let adapter: Pick<AuthAdapter, 'configure' | 'onAuthEvent' | 'fetchSession' | 'accessToken'>;
  let stomp: Pick<StompAdapter, 'createClient'>;

  beforeEach(() => {
    adapter = {
      configure: vi.fn(),
      onAuthEvent: vi.fn(() => () => undefined),
      fetchSession: vi.fn(async () => identity),
      accessToken: vi.fn(async () => 'test-token'),
    };
    stomp = {
      createClient: vi.fn(
        () => ({ activate: vi.fn(), deactivate: vi.fn(async () => undefined) }) as never,
      ),
    };
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        provideTestAppConfig(),
        { provide: AuthAdapter, useValue: adapter },
        { provide: StompAdapter, useValue: stomp },
      ],
    });
  });

  afterEach(() => {
    try {
      TestBed.inject(HttpTestingController).verify();
    } finally {
      TestBed.resetTestingModule();
      vi.restoreAllMocks();
    }
  });

  async function boot(): Promise<void> {
    await TestBed.inject(ApplicationInitStatus).donePromise;
    TestBed.tick();
  }

  it('adds the bearer to API requests through the application providers', async () => {
    await boot();
    const result = firstValueFrom(
      TestBed.inject(HttpClient).get(`${TEST_APP_CONFIG.apiBaseUrl}/users/me`),
    );
    const request = await vi.waitFor(() =>
      TestBed.inject(HttpTestingController).expectOne(`${TEST_APP_CONFIG.apiBaseUrl}/users/me`),
    );
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-token');
    request.flush({ sub: 'test-user' });
    await expect(result).resolves.toEqual({ sub: 'test-user' });
  });

  it('does not send Authorization to a presigned S3 URL', async () => {
    await boot();
    const url = 'https://bucket.s3.eu-south-1.amazonaws.com/x';
    const result = firstValueFrom(TestBed.inject(HttpClient).get(url));
    const request = TestBed.inject(HttpTestingController).expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
    await result;
  });

  it('refreshes once on a raw 401 and delivers the successful retry', async () => {
    await boot();
    const url = `${TEST_APP_CONFIG.apiBaseUrl}/users/me`;
    const result = firstValueFrom(TestBed.inject(HttpClient).get(url));
    void result.catch(() => undefined);
    const first = await vi.waitFor(() => TestBed.inject(HttpTestingController).expectOne(url));
    first.flush({}, { status: 401, statusText: 'Unauthorized' });
    const retry = await vi.waitFor(() => TestBed.inject(HttpTestingController).expectOne(url));
    expect(retry.request.headers.get('Authorization')).toBe('Bearer test-token');
    retry.flush({ sub: 'test-user' });
    await expect(result).resolves.toEqual({ sub: 'test-user' });
    expect(adapter.accessToken).toHaveBeenCalledWith({ forceRefresh: true });
    expect(
      vi.mocked(adapter.accessToken).mock.calls.filter(([options]) => options?.forceRefresh),
    ).toHaveLength(1);
  });

  it('maps problem+json to the structural AppError contract', async () => {
    await boot();
    const url = `${TEST_APP_CONFIG.apiBaseUrl}/listings/missing`;
    const result = firstValueFrom(TestBed.inject(HttpClient).get(url)).catch(
      (error: unknown) => error,
    );
    const request = await vi.waitFor(() => TestBed.inject(HttpTestingController).expectOne(url));
    request.flush(
      { title: 'listing-not-found', type: 'urn:rebyte:problem:listing-not-found' },
      {
        status: 404,
        statusText: 'Not Found',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    const error = await result;
    expect(error).not.toBeInstanceOf(HttpErrorResponse);
    expect(error).toEqual({
      status: 404,
      code: 'listing-not-found',
      type: 'urn:rebyte:problem:listing-not-found',
      fieldErrors: {},
      extras: {},
    } satisfies AppError);
  });

  it('awaits config load and session resolution before binding realtime', async () => {
    const order: string[] = [];
    let releaseConfig!: () => void;
    let releaseSession!: (value: AuthIdentity) => void;
    const configReady = new Promise<void>((resolve) => {
      releaseConfig = resolve;
    });
    const sessionReady = new Promise<AuthIdentity>((resolve) => {
      releaseSession = resolve;
    });
    TestBed.overrideProvider(AppConfigService, {
      useValue: {
        config: TEST_APP_CONFIG,
        load: vi.fn(() => {
          order.push('config');
          return configReady;
        }),
      },
    });
    vi.mocked(adapter.fetchSession).mockImplementation(() => sessionReady);
    const init = AuthStore.prototype.init;
    vi.spyOn(AuthStore.prototype, 'init').mockImplementation(function (this: AuthStore) {
      order.push('auth');
      return init.call(this);
    });
    const connect = RealtimeService.prototype.connectToSession;
    vi.spyOn(RealtimeService.prototype, 'connectToSession').mockImplementation(function (
      this: RealtimeService,
    ) {
      order.push('realtime');
      connect.call(this);
    });
    const initialization = TestBed.inject(ApplicationInitStatus).donePromise;
    expect(order).toEqual(['config']);
    releaseConfig();
    await Promise.resolve();
    expect(order).toEqual(['config', 'auth']);
    expect(stomp.createClient).not.toHaveBeenCalled();
    releaseSession(identity);
    await initialization;
    expect(order).toEqual(['config', 'auth', 'realtime']);
    TestBed.tick();
    expect(stomp.createClient).toHaveBeenCalledOnce();
    TestBed.inject(RealtimeService).connectToSession();
    TestBed.tick();
    expect(stomp.createClient).toHaveBeenCalledOnce();
  });

  it('resolves / through the real providers to the home page with one focusable heading', async () => {
    await boot();
    const harness = await RouterTestingHarness.create('/');
    const headings = harness.routeNativeElement?.querySelectorAll('h1');
    expect(headings).toHaveLength(1);
    expect(headings?.[0].getAttribute('tabindex')).toBe('-1');
    // The home page loads its data through the registered interceptors; drain it for verify().
    const http = TestBed.inject(HttpTestingController);
    await vi.waitFor(() => expect(http.match(() => true).length).toBeGreaterThan(0));
  });
});
