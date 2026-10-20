import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavouritePage, SearchHit } from '../../core/api/api-models';
import { AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStatus, AuthStore } from '../../core/auth/auth.store';
import { authInterceptor } from '../../core/http/auth.interceptor';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { ToastService } from '../../shared/ui/feedback';
import { provideTestAppConfig } from '../../../testing/test-config';
import { HomeFavourites } from './home-favourites.service';
import { HOME_SEARCH_ROUTES } from './home-search.routes';
import { HomePage } from './home.page';

const API = 'http://api.test';
const entry = (listingId: string) => ({
  listingId,
  title: `Listing ${listingId}`,
  price: '10.00',
  photos: [],
  coverUrl: null,
  status: 'AVAILABLE' as const,
  createdAt: '2026-10-01T00:00:00Z',
});
const page = (
  favourites: FavouritePage['favourites'],
  page: number,
  totalPages: number,
): FavouritePage => ({
  favourites,
  page,
  size: 100,
  totalElements: totalPages * 100,
  totalPages,
});

describe('HomeFavourites', () => {
  const status = signal<AuthStatus>('guest');
  const identity = signal<AuthIdentity | null>(null);
  const auth = {
    status,
    identity,
    isAuthenticated: computed(() => status() === 'authenticated'),
    isAdmin: computed(() => false),
    clearSession: vi.fn(),
  };
  let http: HttpTestingController;
  let toasts: ToastService;
  let favourites: HomeFavourites;

  beforeEach(() => {
    status.set('guest');
    identity.set(null);
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideHttpClient(withInterceptors([problemInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthStore, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    toasts = TestBed.inject(ToastService);
    favourites = TestBed.inject(HomeFavourites);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  async function signedIn(sub = 'buyer-a') {
    identity.set({ sub, groups: [] });
    status.set('authenticated');
    return vi.waitFor(() =>
      http.expectOne((req) => req.url === `${API}/favourites` && req.params.get('page') === '0'),
    );
  }

  it('loads page zero at size 100 through the last page once per authenticated session', async () => {
    const first = await signedIn();
    expect(first.request.params.get('size')).toBe('100');
    expect(first.request.method).toBe('GET');
    first.flush(page([entry('a')], 0, 2));
    const second = await vi.waitFor(() =>
      http.expectOne((req) => req.url === `${API}/favourites` && req.params.get('page') === '1'),
    );
    expect(second.request.params.get('size')).toBe('100');
    second.flush(page([entry('b')], 1, 2));
    await vi.waitFor(() => expect(favourites.ids()).toEqual(new Set(['a', 'b'])));
    identity.set({ sub: 'buyer-a', email: 'new@example.test', groups: [] });
    await Promise.resolve();
    expect(http.match((req) => req.url === `${API}/favourites`)).toHaveLength(0);
  });

  it('optimistically toggles a favourite and restores it with a toast after a failed mutation', async () => {
    (await signedIn()).flush(page([], 0, 0));
    favourites.toggle('listing-1', true);
    expect(favourites.ids().has('listing-1')).toBe(true);
    const request = await vi.waitFor(() => http.expectOne(`${API}/favourites/listing-1`));
    expect(request.request.method).toBe('PUT');
    request.flush({ title: 'Unavailable' }, { status: 503, statusText: 'Service Unavailable' });
    expect(favourites.ids().has('listing-1')).toBe(false);
    expect(toasts.current()?.kind).toBe('error');
  });

  it('removes an authoritative favourite missing from the shared hydration set', async () => {
    (await signedIn()).flush(page([], 0, 0));
    const result = favourites.toggle('missing', false);
    const request = await vi.waitFor(() => http.expectOne(`${API}/favourites/missing`));
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    const completed = vi.fn();
    result.subscribe(completed);
    expect(completed).toHaveBeenCalledExactlyOnceWith(true);
    http.expectNone(`${API}/favourites/missing`);
  });

  it.each([true, false])(
    'replays authoritative removal success=%s without duplicate requests',
    async (success) => {
      (await signedIn()).flush(page([entry('one')], 0, 1));
      const result = favourites.toggle('one', false);
      const request = await vi.waitFor(() => http.expectOne(`${API}/favourites/one`));
      if (success) request.flush(null);
      else request.flush(null, { status: 503, statusText: 'Unavailable' });
      const first = vi.fn();
      const second = vi.fn();
      result.subscribe(first);
      result.subscribe(second);
      expect(first).toHaveBeenCalledExactlyOnceWith(success);
      expect(second).toHaveBeenCalledExactlyOnceWith(success);
      expect(favourites.ids().has('one')).toBe(!success);
      http.expectNone(`${API}/favourites/one`);
    },
  );

  it('keeps a user toggle when an older hydration page arrives later', async () => {
    const hydration = await signedIn();
    favourites.toggle('listing-1', true);
    const mutation = await vi.waitFor(() => http.expectOne(`${API}/favourites/listing-1`));
    mutation.flush({
      listingId: 'listing-1',
      sellerSub: 'buyer-a',
      createdAt: '2026-10-01T00:00:00Z',
    });
    hydration.flush(page([], 0, 0));
    expect(favourites.ids().has('listing-1')).toBe(true);
  });

  it('applies DELETE optimistically, ignores duplicate toggles, and restores state on failure', async () => {
    (await signedIn()).flush(page([entry('listing-1')], 0, 1));
    favourites.toggle('listing-1', false);
    expect(favourites.ids().has('listing-1')).toBe(false);
    favourites.toggle('listing-1', true);
    const request = await vi.waitFor(() => http.expectOne(`${API}/favourites/listing-1`));
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 503, statusText: 'Service Unavailable' });
    expect(favourites.ids().has('listing-1')).toBe(true);
    expect(toasts.current()?.kind).toBe('error');
    http.expectNone(`${API}/favourites/listing-1`);
  });

  it('cancels an in-flight mutation and drops the previous account cache on signout', async () => {
    (await signedIn()).flush(page([], 0, 0));
    favourites.toggle('listing-1', true);
    const mutation = await vi.waitFor(() => http.expectOne(`${API}/favourites/listing-1`));
    status.set('guest');
    identity.set(null);
    await vi.waitFor(() => expect(mutation.cancelled).toBe(true));
    expect(favourites.ids().size).toBe(0);
    expect(toasts.current()).toBeNull();
  });

  it('cancels hydration and clears user state when the account changes', async () => {
    const stale = await signedIn();
    identity.set({ sub: 'buyer-b', groups: [] });
    favourites.toggle('buyer-b-listing', true);
    http.expectNone(`${API}/favourites/buyer-b-listing`);
    await vi.waitFor(() => expect(stale.cancelled).toBe(true));
    expect(favourites.ids().size).toBe(0);
    const current = await vi.waitFor(() =>
      http.expectOne((req) => req.url === `${API}/favourites` && req.params.get('page') === '0'),
    );
    current.flush(page([entry('b')], 0, 1));
    await vi.waitFor(() => expect(favourites.ids()).toEqual(new Set(['b'])));
  });
});

describe('HomePage favourites and live result count', () => {
  const status = signal<AuthStatus>('guest');
  const identity = signal<AuthIdentity | null>(null);
  const auth = {
    status,
    identity,
    isAuthenticated: computed(() => status() === 'authenticated'),
    isAdmin: computed(() => false),
    clearSession: vi.fn(),
  };
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(() => {
    status.set('guest');
    identity.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideHttpClient(withInterceptors([problemInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        provideRouter(HOME_SEARCH_ROUTES),
        { provide: AuthStore, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('gates visitor favourites with the return URL and restores focus after Escape', async () => {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/?q=keyboard', HomePage);
    harness.detectChanges();
    (await vi.waitFor(() => http.expectOne((req) => req.url === `${API}/categories`))).flush([]);
    const hit: SearchHit = {
      id: 'listing-1',
      title: 'Keyboard',
      description: 'A keyboard',
      price: '10.00',
      listingType: 'HARDWARE',
      status: 'ACTIVE',
      categoryId: 'components',
      categoryPath: [],
      sellerUsername: 'seller',
      publishedAt: '2026-10-01T00:00:00Z',
      condition: 'GOOD',
      shippingLocation: 'Bologna',
      platforms: [],
      currentVersion: null,
      coverUrl: null,
      attr: {},
    };
    (
      await vi.waitFor(() =>
        http.expectOne((req) => req.url === `${API}/search` && req.params.get('q') === 'keyboard'),
      )
    ).flush({ hits: [hit], total: 1, offset: 0, size: 24, facets: [] });
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement as HTMLElement;
    const count = root.querySelector('.result-count')!;
    expect(count.getAttribute('role')).toBe('status');
    expect(count.getAttribute('aria-live')).toBe('polite');
    expect(count.getAttribute('aria-atomic')).toBe('true');
    expect(count.textContent?.trim()).toBe('1 listing');
    const favourite = root.querySelector<HTMLButtonElement>('.favourite')!;
    favourite.focus();
    favourite.click();
    harness.detectChanges();
    const dialog = root.querySelector<HTMLDialogElement>('dialog')!;
    expect(dialog.open).toBe(true);
    expect(dialog.querySelector('h2')?.textContent?.trim()).toBe('Log in to save favourites');
    const login = dialog.querySelector<HTMLAnchorElement>('a[routerLink="/login"]')!;
    expect(new URL(login.href).searchParams.get('returnUrl')).toBe('/?q=keyboard');
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    harness.detectChanges();
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(favourite);
  });

  it('shows fetched signed-in favourites on cards and routes toggles through the session service', async () => {
    status.set('authenticated');
    identity.set({ sub: 'buyer-a', groups: [] });
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/', HomePage);
    harness.detectChanges();
    (await vi.waitFor(() => http.expectOne((req) => req.url === `${API}/categories`))).flush([]);
    const hit: SearchHit = {
      id: 'listing-1',
      title: 'Keyboard',
      description: 'A keyboard',
      price: '10.00',
      listingType: 'HARDWARE',
      status: 'ACTIVE',
      categoryId: 'components',
      categoryPath: [],
      sellerUsername: 'seller',
      publishedAt: '2026-10-01T00:00:00Z',
      condition: 'GOOD',
      shippingLocation: 'Bologna',
      platforms: [],
      currentVersion: null,
      coverUrl: null,
      attr: {},
    };
    (await vi.waitFor(() => http.expectOne((req) => req.url === `${API}/search`))).flush({
      hits: [hit],
      total: 1,
      offset: 0,
      size: 24,
      facets: [],
    });
    const loaded = await vi.waitFor(() =>
      http.expectOne((req) => req.url === `${API}/favourites` && req.params.get('page') === '0'),
    );
    loaded.flush(page([entry('listing-1')], 0, 1));
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement as HTMLElement;
    const favourite = root.querySelector<HTMLButtonElement>('.favourite')!;
    expect(favourite.getAttribute('aria-pressed')).toBe('true');
    favourite.click();
    const mutation = await vi.waitFor(() => http.expectOne(`${API}/favourites/listing-1`));
    expect(mutation.request.method).toBe('DELETE');
    mutation.flush(null);
    expect(favourite.getAttribute('aria-pressed')).toBe('false');
  });
});
