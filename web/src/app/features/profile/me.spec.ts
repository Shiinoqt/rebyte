import { BreakpointObserver } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import {
  FavouritePage,
  ListingCard,
  MeResponse,
  PublicProfileResponse,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { OrderApi } from '../../core/api/order-api';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter, AuthEvent, AuthIdentity } from '../../core/auth/auth.adapter';
import { authGuard } from '../../core/auth/auth.guards';
import { AuthStore } from '../../core/auth/auth.store';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ToastService } from '../../shared/ui/feedback';
import { MeTabsStore } from './me-tabs.store';
import { ME_ROUTES } from './me.routes';

const SUB = 'member-sub';

@Component({ template: '<h1>Elsewhere</h1>' })
class ElsewherePage {}

class FakeAuthAdapter {
  constructor(public identity: AuthIdentity | null) {}
  configure = vi.fn();
  fetchSession = vi.fn(() => Promise.resolve(this.identity));
  signOut = vi.fn(() => Promise.resolve());
  onAuthEvent(_listener: (event: AuthEvent) => void) {
    return () => undefined;
  }
}

function me(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    id: SUB,
    username: 'alice',
    registration: '2026-01-15T10:00:00Z',
    location: 'Milan',
    bio: 'I repair laptops.',
    avatar: null,
    ...overrides,
  };
}

function publicProfile(overrides: Partial<PublicProfileResponse> = {}): PublicProfileResponse {
  return {
    sub: SUB,
    username: 'alice',
    avatar: null,
    registration: '2026-01-15T10:00:00Z',
    location: 'Milan',
    bio: 'I repair laptops.',
    isDeveloper: false,
    ratingAvg: 4.5,
    ratingCount: 12,
    completedSales: 3,
    activeListings: [],
    followerCount: 5,
    followingCount: 2,
    followedByMe: false,
    ...overrides,
  };
}

function favouritePage(totalElements: number): FavouritePage {
  return { favourites: [], page: 0, size: 1, totalElements, totalPages: totalElements };
}

interface PhoneState {
  matches: boolean;
  breakpoints: Record<string, boolean>;
}

describe('own profile hub', () => {
  let phone: BehaviorSubject<PhoneState>;

  beforeEach(() => {
    phone = new BehaviorSubject<PhoneState>({ matches: false, breakpoints: {} });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('renders the library workflow in the account section', async () => {
    const { root } = await open('/me?tab=library');
    expect(root.querySelector('app-me-library-tab')).not.toBeNull();
  });

  async function open(
    url: string,
    options: {
      signedIn?: boolean;
      account?: MeResponse;
      profile?: PublicProfileResponse;
      favourites?: ReturnType<typeof vi.fn>;
      publicProfile?: ReturnType<typeof vi.fn>;
      myListings?: ReturnType<typeof vi.fn>;
      deleteListing?: ReturnType<typeof vi.fn>;
    } = {},
  ) {
    const adapter = new FakeAuthAdapter(
      options.signedIn === false ? null : { sub: SUB, email: 'alice@example.test', groups: [] },
    );
    const users = {
      getMe: vi.fn(() => of(options.account ?? me())),
      getPublicProfile:
        options.publicProfile ?? vi.fn(() => of(options.profile ?? publicProfile())),
    };
    const catalog = {
      favourites: options.favourites ?? vi.fn(() => of(favouritePage(7))),
      myListings: options.myListings ?? vi.fn(() => of({ items: [], nextCursor: null })),
      deleteListing: options.deleteListing ?? vi.fn(() => of(undefined)),
    };
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideRouter([
          { path: 'me', canActivate: [authGuard], loadChildren: () => ME_ROUTES },
          { path: 'login', component: ElsewherePage },
          { path: 'settings', component: ElsewherePage },
          { path: '', component: ElsewherePage },
        ]),
        { provide: AuthAdapter, useValue: adapter },
        { provide: UserApi, useValue: users },
        { provide: CatalogApi, useValue: catalog },
        {
          provide: OrderApi,
          useValue: {
            licenses: vi.fn(() => of({ items: [], nextCursor: null })),
            sellerReviews: vi.fn(() => of({ items: [], nextCursor: null })),
            purchases: vi.fn(() => of({ items: [], nextCursor: null })),
          },
        },
        { provide: BreakpointObserver, useValue: { observe: () => phone } },
      ],
    });
    const store = TestBed.inject(AuthStore);
    await store.init();
    const realtime = TestBed.inject(RealtimeService);
    vi.spyOn(realtime, 'activate').mockImplementation(() => undefined);
    const deactivate = vi.spyOn(realtime, 'deactivate').mockResolvedValue(undefined);
    realtime.connectToSession();
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement as HTMLElement;
    return {
      harness,
      root,
      adapter,
      users,
      catalog,
      store,
      deactivate,
      router: TestBed.inject(Router),
    };
  }

  const text = (root: HTMLElement) => root.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const nav = (root: HTMLElement) =>
    root.querySelector<HTMLElement>('nav[aria-label="Your account"]');
  const rows = (root: HTMLElement) =>
    Array.from(nav(root)?.querySelectorAll<HTMLAnchorElement>('a.row') ?? []);
  const summary = (root: HTMLElement) =>
    root.querySelector<HTMLAnchorElement>('[data-account-summary]');
  const summaryText = (root: HTMLElement) => text(summary(root) as HTMLElement);
  const activeRow = (root: HTMLElement) =>
    root.querySelector('a.row[aria-current="page"] .row-label')?.textContent?.trim();
  const sectionTitle = (root: HTMLElement) =>
    root.querySelector('.account-main h1')?.textContent?.trim();
  const panel = (root: HTMLElement) => root.querySelector<HTMLElement>('[data-tab]');
  const logoutButton = (root: HTMLElement) =>
    root.querySelector<HTMLButtonElement>('button.logout');

  function listing(
    id: string,
    status: ListingCard['status'],
    type: ListingCard['type'] = 'HARDWARE',
  ): ListingCard {
    return {
      id,
      title: id,
      status,
      type,
      price: '20.00',
      coverUrl: null,
      condition: null,
      shippingLocation: null,
      platforms: null,
      currentVersion: null,
      publication: '2026-10-01T00:00:00Z',
    };
  }

  it('lazy loads every owner status, keeps cursor pages and exposes only valid actions', async () => {
    const mine = vi
      .fn()
      .mockReturnValueOnce(
        of({
          items: [
            listing('active', 'ACTIVE'),
            listing('sold', 'SOLD'),
            listing('removed', 'REMOVED'),
            listing('software', 'ACTIVE', 'SOFTWARE'),
          ],
          nextCursor: 'opaque',
        }),
      )
      .mockReturnValueOnce(of({ items: [listing('older', 'SOLD')], nextCursor: null }));
    const { harness, root, router } = await open('/me?tab=reviews', { myListings: mine });
    expect(mine).not.toHaveBeenCalled();
    await router.navigateByUrl('/me?tab=listings');
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(mine).toHaveBeenCalledWith({ size: 20 });
    expect(root.querySelectorAll('[data-owner-listing]')).toHaveLength(4);
    for (const id of ['sold', 'removed']) {
      const row = root.querySelector(`[data-owner-listing="${id}"]`)!;
      expect(row.textContent).not.toContain('Edit');
      expect(row.querySelector('button')).toBeNull();
    }
    expect(root.querySelector('a[href="/listings/software/releases"]')).not.toBeNull();
    expect(root.querySelector('a[href="/listings/active/releases"]')).toBeNull();
    root.querySelector<HTMLButtonElement>('[data-load-listings]')!.click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(mine).toHaveBeenLastCalledWith({ size: 20, cursor: 'opaque' });
    expect(root.querySelectorAll('[data-owner-listing]')).toHaveLength(5);
    await router.navigateByUrl('/me?tab=library');
    await router.navigateByUrl('/me?tab=listings');
    await harness.fixture.whenStable();
    expect(mine).toHaveBeenCalledTimes(2);
  });

  it.each(['success', 'listing-already-removed', 'listing-not-owned'])(
    'confirms owner removal without a reason and refetches on %s',
    async (outcome) => {
      const mine = vi
        .fn()
        .mockReturnValueOnce(of({ items: [listing('active', 'ACTIVE')], nextCursor: null }))
        .mockReturnValue(of({ items: [listing('active', 'REMOVED')], nextCursor: null }));
      const remove = vi.fn(() =>
        outcome === 'success' ? of(undefined) : throwError(() => ({ status: 409, code: outcome })),
      );
      const { harness, root, router } = await open('/me?tab=listings', {
        myListings: mine,
        deleteListing: remove,
      });
      root.querySelector<HTMLButtonElement>('[data-remove-listing]')!.click();
      harness.detectChanges();
      expect(remove).not.toHaveBeenCalled();
      expect(root.querySelector('dialog')?.textContent).toContain('Remove this listing?');
      root.querySelector<HTMLButtonElement>('[data-confirm-remove]')!.click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(remove).toHaveBeenCalledExactlyOnceWith('active');
      expect(mine).toHaveBeenCalledTimes(2);
      expect(router.url).toBe('/me?tab=listings');
      expect(root.querySelector('[data-owner-listing]')?.textContent).toContain('Removed');
    },
  );

  it('retries a failed replacement refresh even after the final cursor page', async () => {
    const mine = vi
      .fn()
      .mockReturnValueOnce(of({ items: [listing('active', 'ACTIVE')], nextCursor: null }))
      .mockReturnValueOnce(throwError(() => new Error('offline')))
      .mockReturnValueOnce(of({ items: [listing('active', 'REMOVED')], nextCursor: null }));
    const { harness, root } = await open('/me?tab=listings', { myListings: mine });
    root.querySelector<HTMLButtonElement>('[data-remove-listing]')!.click();
    harness.detectChanges();
    root.querySelector<HTMLButtonElement>('[data-confirm-remove]')!.click();
    harness.detectChanges();
    expect(root.querySelectorAll('[data-owner-listing]')).toHaveLength(1);
    root.querySelector<HTMLButtonElement>('app-error-state button')!.click();
    harness.detectChanges();
    expect(mine.mock.calls).toEqual([[{ size: 20 }], [{ size: 20 }], [{ size: 20 }]]);
    expect(root.querySelector('[data-owner-listing]')?.textContent).toContain('Removed');
  });

  it('redirects a guest to the login page and keeps the profile as the return URL', async () => {
    const { router } = await open('/me?tab=reviews', { signedIn: false });
    const url = router.parseUrl(router.url);
    expect(url.root.children['primary'].segments.map((segment) => segment.path)).toEqual(['login']);
    expect(url.queryParams['returnUrl']).toBe('/me?tab=reviews');
  });

  describe('desktop account sidebar', () => {
    it('summarises the real profile beside the open section', async () => {
      const { root, users, catalog } = await open('/me?tab=reviews');
      expect(users.getPublicProfile).toHaveBeenCalledWith(SUB);
      expect(catalog.favourites).toHaveBeenCalledWith({ page: 0, size: 1 });
      expect(summary(root)?.getAttribute('href')).toBe('/u/' + SUB);
      expect(summaryText(root)).toContain('@alice');
      expect(summaryText(root)).toContain('View public profile');
      expect(summaryText(root)).toContain('Milan · Member since Jan 2026');
      expect(summaryText(root)).toContain('4.5 stars (12 reviews) · 3 sold · 5 followers');
      expect(sectionTitle(root)).toBe('Reviews');
      expect(root.querySelector('app-pill-tabs')).toBeNull();
      expect(logoutButton(root)).toBeNull();
    });

    it('lists every account destination in the same groups as the phone', async () => {
      const { root } = await open('/me');
      expect(
        Array.from(nav(root)!.querySelectorAll('h2')).map((heading) => heading.textContent?.trim()),
      ).toEqual(['Buying', 'Selling', 'Account']);
      expect(
        rows(root).map((row) => [
          row.querySelector('.row-label')?.textContent?.trim(),
          row.getAttribute('href'),
        ]),
      ).toEqual([
        ['Favourites', '/favourites'],
        ['Orders', '/orders'],
        ['Offers', '/offers'],
        ['Library', '/me?tab=library'],
        ['Your listings', '/me?tab=listings'],
        ['Reviews', '/me?tab=reviews'],
        ['Settings and profile', '/settings'],
      ]);
      expect(rows(root)[0].querySelector('.row-count')?.textContent?.trim()).toBe('7');
    });

    it.each([
      ['/me?tab=listings', 'Your listings', 'listings'],
      ['/me?tab=library', 'Library', 'library'],
      ['/me?tab=reviews', 'Reviews', 'reviews'],
    ])('opens %s as the marked section', async (url, label, tab) => {
      const { root } = await open(url);
      expect(activeRow(root)).toBe(label);
      expect(sectionTitle(root)).toBe(label);
      expect(panel(root)?.getAttribute('data-tab')).toBe(tab);
      expect(root.querySelector(`[data-tab-content="${tab}"]`)).not.toBeNull();
    });

    it('opens Your listings when the tab parameter is missing', async () => {
      const { root, router } = await open('/me');
      expect(activeRow(root)).toBe('Your listings');
      expect(router.url).toBe('/me');
    });

    it.each([
      'bogus',
      'about',
      'favourites',
      'orders',
      'offers',
      'ABOUT',
      'following',
      '__proto__',
      'constructor',
      '',
    ])('normalises the unknown tab "%s" to listings in the page and in the URL', async (value) => {
      const { root, router } = await open('/me?tab=' + value);
      expect(activeRow(root)).toBe('Your listings');
      expect(router.url).toBe('/me?tab=listings');
      expect(panel(root)?.getAttribute('data-tab')).toBe('listings');
    });

    it('moves between sections through the sidebar and the URL', async () => {
      const { harness, root, router } = await open('/me?tab=reviews');
      rows(root)[3].click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(router.url).toBe('/me?tab=library');
      expect(activeRow(root)).toBe('Library');
      await router.navigateByUrl('/me?tab=reviews');
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(activeRow(root)).toBe('Reviews');
    });

    it('labels the single section region with its heading', async () => {
      const { root } = await open('/me?tab=library');
      const regions = root.querySelectorAll('[data-tab]');
      expect(regions).toHaveLength(1);
      const heading = root.querySelector('.account-main h1')!;
      expect(regions[0].getAttribute('aria-labelledby')).toBe(heading.id);
    });
  });

  it.each(['/me?tab=reviews', '/me'])(
    'does not show the generated username banner on %s',
    async (url) => {
      const { root } = await open(url, {
        account: me({ username: 'user_1a2b3c4d' }),
        profile: publicProfile({ username: 'user_1a2b3c4d' }),
      });
      expect(root.querySelector('app-banner')).toBeNull();
    },
  );

  it.each(['alice', 'user_friendly', 'user_1a2b3c4', 'user_1a2b3c4d5', 'user_1A2B3C4D'])(
    'shows no username banner for the chosen handle %s',
    async (username) => {
      const { root } = await open('/me?tab=reviews', {
        account: me({ username }),
        profile: publicProfile({ username }),
      });
      expect(root.querySelector('app-banner')).toBeNull();
    },
  );

  it('tolerates a missing location and avatar', async () => {
    const { root } = await open('/me?tab=reviews', {
      account: me({ bio: null, location: null, avatar: null }),
      profile: publicProfile({ bio: null, location: null, avatar: null }),
    });
    expect(summaryText(root)).toContain('Member since Jan 2026');
    expect(summaryText(root)).not.toContain('Milan');
    expect(summary(root)?.querySelector('app-avatar img')).toBeNull();
    expect(summary(root)?.querySelector('app-avatar')?.textContent?.trim()).toBe('A');
  });

  it('shows the avatar picture when present', async () => {
    const { root } = await open('/me?tab=reviews', {
      account: me({ avatar: 'https://cdn.test/a.jpg' }),
    });
    expect(summary(root)?.querySelector('app-avatar img')?.getAttribute('src')).toBe(
      'https://cdn.test/a.jpg',
    );
  });

  it('says No reviews yet and singular follower copy when the counts are small', async () => {
    const { root } = await open('/me?tab=reviews', {
      profile: publicProfile({ ratingAvg: null, ratingCount: 0, followerCount: 1 }),
    });
    expect(summaryText(root)).toContain('No reviews yet');
    expect(summaryText(root)).toContain('1 follower');
    expect(summaryText(root)).not.toContain('1 followers');
  });

  it('keeps the profile when the favourites count cannot be read', async () => {
    const { root } = await open('/me?tab=reviews', {
      favourites: vi.fn(() => throwError(() => new Error('down'))),
    });
    expect(summaryText(root)).toContain('3 sold');
    expect(rows(root)[0].querySelector('.row-count')).toBeNull();
    expect(root.querySelector('app-error-state')).toBeNull();
  });

  it('shows an error state with a working retry when the profile cannot load', async () => {
    let calls = 0;
    const getPublicProfile = vi.fn(() =>
      ++calls === 1 ? throwError(() => new Error('down')) : of(publicProfile()),
    );
    const { harness, root } = await open('/me?tab=reviews', { publicProfile: getPublicProfile });
    expect(summary(root)).toBeNull();
    const error = root.querySelector('app-error-state');
    expect(error).not.toBeNull();
    error?.querySelector('button')?.click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(getPublicProfile).toHaveBeenCalledTimes(2);
    expect(root.querySelector('app-error-state')).toBeNull();
    expect(summaryText(root)).toContain('@alice');
  });

  describe('phone account list', () => {
    it('shows only the grouped rows and Log out when no section is open', async () => {
      phone.next({ matches: true, breakpoints: {} });
      const { root } = await open('/me');
      expect(root.querySelector('[data-tab]')).toBeNull();
      expect(rows(root)).toHaveLength(7);
      expect(root.querySelector('h1')?.textContent?.trim()).toBe('Your account');
      expect(summaryText(root)).toContain('@alice');
      expect(logoutButton(root)).not.toBeNull();
      expect(
        nav(root)!.compareDocumentPosition(logoutButton(root)!) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it('opens a section full screen with a way back to the list', async () => {
      phone.next({ matches: true, breakpoints: {} });
      const { harness, root, router } = await open('/me');
      rows(root)[3].click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(router.url).toBe('/me?tab=library');
      expect(nav(root)).toBeNull();
      expect(sectionTitle(root)).toBe('Library');
      expect(panel(root)?.getAttribute('data-tab')).toBe('library');
      const back = root.querySelector<HTMLAnchorElement>('a.back')!;
      expect(back.getAttribute('href')).toBe('/me');
      expect(back.textContent).toContain('Account');
    });

    it('follows the viewport when it crosses the narrow breakpoint', async () => {
      const { harness, root } = await open('/me');
      expect(logoutButton(root)).toBeNull();
      phone.next({ matches: true, breakpoints: {} });
      harness.detectChanges();
      expect(logoutButton(root)).not.toBeNull();
      phone.next({ matches: false, breakpoints: {} });
      harness.detectChanges();
      expect(logoutButton(root)).toBeNull();
    });

    it('sizes every row and Log out to the 44 px touch minimum', async () => {
      phone.next({ matches: true, breakpoints: {} });
      const { root } = await open('/me');
      for (const control of [...rows(root), logoutButton(root)!]) {
        expect(getComputedStyle(control).minHeight).toBe('var(--hit-min)');
      }
    });

    it('logs out through the adapter, clears the session and stops realtime', async () => {
      phone.next({ matches: true, breakpoints: {} });
      const { harness, root, adapter, store, deactivate, router } = await open('/me');
      expect(store.isAuthenticated()).toBe(true);
      logoutButton(root)!.click();
      await harness.fixture.whenStable();
      expect(adapter.signOut).toHaveBeenCalledTimes(1);
      expect(store.isAuthenticated()).toBe(false);
      expect(deactivate).toHaveBeenCalled();
      expect(router.url).toBe('/');
    });

    it('keeps the session and tells the user when sign out fails', async () => {
      phone.next({ matches: true, breakpoints: {} });
      const { harness, root, adapter, store, deactivate, router } = await open('/me');
      const show = vi.spyOn(TestBed.inject(ToastService), 'show');
      adapter.signOut.mockRejectedValueOnce(new Error('offline'));
      const button = logoutButton(root)!;
      button.click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(show).toHaveBeenCalledWith({ text: 'Could not log out. Try again.', kind: 'error' });
      expect(store.isAuthenticated()).toBe(true);
      expect(deactivate).not.toHaveBeenCalled();
      expect(router.url).toBe('/me');
      expect(button.disabled).toBe(false);
    });
  });

  describe('section outlet states', () => {
    it('shows loading and error only for the open section', async () => {
      const { harness, root } = await open('/me?tab=library');
      const store = harness.routeDebugElement!.injector.get(MeTabsStore);
      expect(panel(root)?.querySelector('app-skeleton')).toBeNull();
      store.report('library', 'loading');
      harness.detectChanges();
      expect(panel(root)?.querySelector('app-skeleton')).not.toBeNull();
      store.report('library', 'error');
      harness.detectChanges();
      expect(panel(root)?.querySelector('app-error-state')).not.toBeNull();
      store.report('reviews', 'error');
      store.report('library', 'ready');
      harness.detectChanges();
      expect(panel(root)?.querySelector('app-error-state')).toBeNull();
      expect(panel(root)?.querySelector('[data-tab-content="library"]')).not.toBeNull();
    });
  });
});
