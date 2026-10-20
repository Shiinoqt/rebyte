import { Component, computed, inject, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  Router,
  Routes,
  provideRouter,
  withComponentInputBinding,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../../app.routes';
import { adminGuard, authGuard, guestGuard } from '../auth/auth.guards';
import { AuthStatus, AuthStore } from '../auth/auth.store';

const rows = [
  {
    path: '',
    url: '/',
    title: 'Home',
    guard: undefined,
    data: undefined,
    exportName: 'HOME_SEARCH_ROUTES',
  },
  ...['login', 'signup', 'confirm', 'forgot'].map((authStep) => ({
    path: authStep,
    url: '/' + authStep,
    title: 'Authentication',
    guard: guestGuard,
    data: { authStep },
    exportName: 'AUTH_ROUTES',
  })),
  {
    path: 'listings/:id/edit',
    url: '/listings/x/edit',
    title: 'Listing form',
    guard: authGuard,
    data: { mode: 'edit' },
    exportName: 'LISTING_FORM_ROUTES',
  },
  {
    path: 'listings/:id/releases',
    url: '/listings/x/releases',
    title: 'Developer releases',
    guard: authGuard,
    data: undefined,
    exportName: 'DEVELOPER_ROUTES',
  },
  {
    path: 'listings/:id',
    url: '/listings/x',
    title: 'Listing detail',
    guard: undefined,
    data: undefined,
    exportName: 'LISTING_DETAIL_ROUTES',
  },
  {
    path: 'u/:sub',
    url: '/u/seller',
    title: 'Seller profile',
    guard: undefined,
    data: undefined,
    exportName: 'SELLER_PROFILE_ROUTES',
  },
  {
    path: 'sell',
    url: '/sell',
    title: 'Listing form',
    guard: authGuard,
    data: { mode: 'create' },
    exportName: 'LISTING_FORM_ROUTES',
  },
  {
    path: 'checkout/return',
    url: '/checkout/return?token=T&PayerID=P',
    title: 'Payment return',
    guard: authGuard,
    data: undefined,
    exportName: 'PAYMENT_RETURN_ROUTES',
  },
  {
    path: 'checkout/cancel',
    url: '/checkout/cancel?token=T',
    title: 'Payment cancel',
    guard: authGuard,
    data: undefined,
    exportName: 'PAYMENT_CANCEL_ROUTES',
  },
  {
    path: 'checkout/:listingId',
    url: '/checkout/item?offer=offer-id',
    title: 'Checkout',
    guard: authGuard,
    data: undefined,
    exportName: 'CHECKOUT_ROUTES',
  },
  {
    path: 'favourites',
    url: '/favourites',
    title: 'Favourites',
    guard: authGuard,
    data: undefined,
    exportName: 'FAVOURITES_ROUTES',
  },
  {
    path: 'orders',
    url: '/orders',
    title: 'Your orders',
    guard: authGuard,
    data: undefined,
    exportName: 'ORDERS_ROUTES',
  },
  {
    path: 'offers',
    url: '/offers',
    title: 'Your offers',
    guard: authGuard,
    data: undefined,
    exportName: 'OFFERS_ROUTES',
  },
  {
    path: 'orders/:id',
    url: '/orders/order-id',
    title: 'Order detail',
    guard: authGuard,
    data: undefined,
    exportName: 'ORDER_DETAIL_ROUTES',
  },
  {
    path: 'conversations',
    url: '/conversations',
    title: 'Messages',
    guard: authGuard,
    data: undefined,
    exportName: 'MESSAGES_ROUTES',
  },
  {
    path: 'notifications',
    url: '/notifications',
    title: 'Notifications',
    guard: authGuard,
    data: undefined,
    exportName: 'NOTIFICATIONS_ROUTES',
  },
  {
    path: 'me',
    url: '/me',
    title: 'Your profile',
    guard: authGuard,
    data: undefined,
    exportName: 'ME_ROUTES',
  },
  {
    path: 'settings',
    url: '/settings',
    title: 'Account settings',
    guard: authGuard,
    data: undefined,
    exportName: 'SETTINGS_ROUTES',
  },
  {
    path: 'admin/problems',
    url: '/admin/problems',
    title: 'Admin problems',
    guard: adminGuard,
    data: undefined,
    exportName: 'ADMIN_MODERATION_ROUTES',
  },
  {
    path: 'admin/categories',
    url: '/admin/categories',
    title: 'Admin categories',
    guard: adminGuard,
    data: undefined,
    exportName: 'ADMIN_CATEGORIES_ROUTES',
  },
  {
    path: 'admin/operations',
    url: '/admin/operations',
    title: 'Admin operations',
    guard: adminGuard,
    data: undefined,
    exportName: 'ADMIN_OPERATIONS_ROUTES',
  },
];

// Feature pages own their own specs; here each lazy feature is replaced by a marker so the routing
// contract (order, guards, inherited data and params) is tested independently of page content.
@Component({ template: '<h1 tabindex="-1">{{ title }}</h1>' })
class FeatureMarker {
  readonly title = inject(ActivatedRoute).snapshot.data['markerTitle'] as string;
}

const titleByPath = new Map(rows.map((row) => [row.path, row.title]));
const markerRoutes: Routes = routes.map((route) => {
  if (!route.loadChildren) return route;
  const data = { markerTitle: titleByPath.get(route.path!) };
  const children: Routes =
    route.path === 'conversations'
      ? [
          { path: '', component: FeatureMarker, data },
          { path: ':id', component: FeatureMarker, data },
        ]
      : [{ path: '', component: FeatureMarker, data }];
  return { ...route, loadChildren: () => children };
});

describe('binding application routes', () => {
  const status = signal<AuthStatus>('authenticated');
  const admin = signal(true);
  const auth = {
    status,
    isAuthenticated: computed(() => status() === 'authenticated'),
    isAdmin: computed(() => status() === 'authenticated' && admin()),
    whenResolved: vi.fn(async (): Promise<void> => undefined),
  };

  beforeEach(() => {
    status.set('authenticated');
    admin.set(true);
    auth.whenResolved.mockReset().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(markerRoutes, withComponentInputBinding()),
        { provide: AuthStore, useValue: auth },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('has exactly the binding path order, guard functions and parent data', () => {
    expect(routes.map((route) => route.path)).toEqual([...rows.map((row) => row.path), '**']);
    expect(routes.filter((route) => route.loadChildren)).toHaveLength(24);
    expect(routes[0].pathMatch).toBe('full');
    rows.forEach((row, index) => {
      expect(routes[index].canActivate).toEqual(row.guard ? [row.guard] : undefined);
      expect(routes[index].data).toEqual(row.data);
      expect(routes[index].loadChildren?.toString()).toContain(row.exportName);
      expect(routes[index].component).toBeUndefined();
    });
    expect(routes[24].loadComponent).toBeDefined();
  });

  it('loads a non-empty route array from every real feature route file', async () => {
    for (const route of routes.filter((candidate) => candidate.loadChildren)) {
      const loaded = await (route.loadChildren as () => Promise<Routes>)();
      expect(Array.isArray(loaded) && loaded.length > 0).toBe(true);
    }
  });

  it.each(rows)('resolves row $path to its allowed feature', async (row) => {
    if (row.guard === guestGuard) status.set('guest');
    const harness = await RouterTestingHarness.create(row.url);
    const headings = harness.routeNativeElement?.querySelectorAll('h1');
    expect(headings).toHaveLength(1);
    expect(headings?.[0].textContent).toBe(row.title);
    expect(headings?.[0].getAttribute('tabindex')).toBe('-1');
    const snapshot = harness.routeDebugElement!.injector.get(ActivatedRoute).snapshot;
    for (const [key, value] of Object.entries(row.data ?? {}))
      expect(snapshot.data[key]).toBe(value);
    if (row.path.includes(':id'))
      expect(snapshot.params['id']).toBe(row.path === 'orders/:id' ? 'order-id' : 'x');
    if (row.path.includes(':sub')) expect(snapshot.params['sub']).toBe('seller');
    if (row.path.includes(':listingId')) expect(snapshot.params['listingId']).toBe('item');
    if (row.path === 'checkout/return')
      expect(snapshot.queryParams).toEqual({ token: 'T', PayerID: 'P' });
  });

  it('resolves row ** to a public not-found page with a home link', async () => {
    status.set('guest');
    const harness = await RouterTestingHarness.create('/unknown/path');
    expect(harness.routeNativeElement?.querySelectorAll('h1')).toHaveLength(1);
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Page not found');
    expect(harness.routeNativeElement?.querySelector('a')?.getAttribute('href')).toBe('/');
    expect(harness.routeNativeElement?.querySelector('h1')?.getAttribute('tabindex')).toBe('-1');
    expect(harness.routeNativeElement?.querySelector('a svg[aria-hidden="true"]')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('a')?.textContent).toContain('Back to home');
    expect(harness.routeNativeElement?.querySelector('p')?.textContent).toBe(
      'The page you are looking for does not exist or has moved.',
    );
  });

  it('resolves conversation deep links to the :id child', async () => {
    const harness = await RouterTestingHarness.create('/conversations/c1');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Messages');
    expect(harness.routeDebugElement!.injector.get(ActivatedRoute).snapshot.params['id']).toBe(
      'c1',
    );
  });

  it.each(rows.filter((row) => row.guard === authGuard || row.guard === adminGuard))(
    'redirects guests from $path preserving the full return URL',
    async (row) => {
      status.set('guest');
      const harness = await RouterTestingHarness.create(row.url);
      const url = TestBed.inject(Router).parseUrl(TestBed.inject(Router).url);
      expect(url.root.children['primary'].segments.map((segment) => segment.path)).toEqual([
        'login',
      ]);
      expect(url.queryParams['returnUrl']).toBe(row.url);
      expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Authentication');
    },
  );

  it.each(['problems', 'categories', 'operations'])(
    'refuses a signed-in non-admin at /admin/%s',
    async (path) => {
      admin.set(false);
      const harness = await RouterTestingHarness.create('/admin/' + path);
      expect(TestBed.inject(Router).url).toBe('/');
      expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Home');
    },
  );

  it.each(['login', 'signup', 'confirm', 'forgot'])(
    'redirects a signed-in user away from /%s',
    async (path) => {
      const harness = await RouterTestingHarness.create('/' + path);
      expect(TestBed.inject(Router).url).toBe('/');
      expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Home');
    },
  );

  it.each(['/messages', '/unsubscribe'])(
    'does not register the prohibited SPA alias %s',
    async (path) => {
      const harness = await RouterTestingHarness.create(path);
      expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Page not found');
    },
  );

  it('waits for authentication resolution before redirecting a protected return', async () => {
    status.set('resolving');
    let resolve!: () => void;
    auth.whenResolved.mockImplementation(
      () =>
        new Promise<void>((done) => {
          resolve = done;
        }),
    );
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/checkout/return?token=T&PayerID=P');
    await vi.waitFor(() => expect(auth.whenResolved).toHaveBeenCalled());
    expect(harness.routeNativeElement).toBeNull();
    status.set('guest');
    auth.whenResolved.mockResolvedValue(undefined);
    resolve();
    await navigation;
    expect(
      TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams['returnUrl'],
    ).toBe('/checkout/return?token=T&PayerID=P');
  });
});
