import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import { ListingCard, PublicProfileResponse, ReviewView } from '../../core/api/api-models';
import { AuthAdapter, AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStatus, AuthStore } from '../../core/auth/auth.store';
import { authInterceptor } from '../../core/http/auth.interceptor';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { ToastService } from '../../shared/ui/feedback';
import { SellerProfilePage } from './seller-profile.page';
import { SELLER_PROFILE_ROUTES } from './seller-profile.routes';

const API = 'http://api.test';

function profile(overrides: Partial<PublicProfileResponse> = {}): PublicProfileResponse {
  return {
    sub: 'seller',
    username: 'marta_v',
    avatar: null,
    registration: '2026-03-14T10:00:00Z',
    location: 'Bologna',
    bio: 'Restores retro hardware.',
    isDeveloper: false,
    ratingAvg: 4.5,
    ratingCount: 12,
    completedSales: 30,
    activeListings: ['ignored-1', 'ignored-2', 'ignored-3'],
    followerCount: 24,
    followingCount: 7,
    followedByMe: false,
    ...overrides,
  };
}

function card(id: string): ListingCard {
  return {
    id,
    title: `Item ${id}`,
    price: '12.50',
    type: 'HARDWARE',
    status: 'ACTIVE',
    coverUrl: null,
    condition: 'GOOD',
    shippingLocation: 'Bologna',
    platforms: null,
    currentVersion: null,
    publication: '2026-09-01T10:00:00Z',
  };
}

function review(id: string, overrides: Partial<ReviewView> = {}): ReviewView {
  return {
    id,
    listingId: `listing-${id}`,
    listingTitle: `Sold item ${id}`,
    authorUsername: `buyer_${id}`,
    sellerSub: 'seller',
    rating: 4,
    comment: `Comment ${id}`,
    createdAt: '2026-09-02T10:00:00Z',
    ...overrides,
  };
}

describe('SellerProfilePage', () => {
  const status = signal<AuthStatus>('guest');
  const identity = signal<AuthIdentity | null>(null);
  const auth = {
    status,
    identity,
    isAuthenticated: computed(() => status() === 'authenticated'),
    isAdmin: computed(() => false),
    clearSession: vi.fn(),
  };
  const adapter = {
    accessToken: vi.fn(async () => (status() === 'authenticated' ? 'viewer-token' : null)),
  };
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(() => {
    status.set('guest');
    identity.set(null);
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideHttpClient(withInterceptors([problemInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([{ path: 'u/:sub', loadChildren: () => SELLER_PROFILE_ROUTES }]),
        { provide: AuthStore, useValue: auth },
        { provide: AuthAdapter, useValue: adapter },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  async function open(sub = 'seller') {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/u/${sub}`, SellerProfilePage);
    harness.detectChanges();
    return harness.routeNativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  async function request(path: string, match: (params: URLSearchParams) => boolean = () => true) {
    const url = new URL(`${API}${path}`);
    return vi.waitFor(() =>
      http.expectOne(
        (req) =>
          req.url === `${url.origin}${url.pathname}` &&
          match(new URLSearchParams(req.params.toString())),
      ),
    );
  }

  async function respond(
    el: HTMLElement,
    data: {
      profile?: PublicProfileResponse;
      listings?: { items: ListingCard[]; nextCursor: string | null };
      reviews?: { items: ReviewView[]; nextCursor: string | null };
    } = {},
  ) {
    const p = await request('/users/seller');
    const l = await request('/users/seller/listings');
    const r = await request('/users/seller/reviews');
    for (const req of [p, l, r]) {
      expect(req.request.headers.has('Authorization')).toBe(status() === 'authenticated');
    }
    p.flush(data.profile ?? profile());
    l.flush(data.listings ?? { items: [card('a'), card('b')], nextCursor: 'L1' });
    r.flush(data.reviews ?? { items: [review('1'), review('2')], nextCursor: 'R1' });
    await settle();
    return el;
  }

  const q = (el: HTMLElement, selector: string) => el.querySelector<HTMLElement>(selector);
  const all = (el: HTMLElement, selector: string) =>
    Array.from(el.querySelectorAll<HTMLElement>(selector));
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it.each([
    ['seller', 'user_1a2b3c4d', true],
    ['viewer', 'user_1a2b3c4d', false],
    ['seller', 'chosen_name', false],
    [null, 'user_1a2b3c4d', false],
  ])('shows the username banner only for an own generated handle (%s, %s)', async (viewer, username, visible) => {
    if (viewer) {
      status.set('authenticated');
      identity.set({ sub: viewer, groups: [] });
    }
    const el = await respond(await open(), { profile: profile({ username }) });
    const banner = q(el, 'app-banner');
    expect(banner !== null).toBe(visible);
    if (visible) {
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      expect(text(banner)).toContain('Your username was generated for you.');
      banner!.querySelector('button')!.click();
      expect(navigate).toHaveBeenCalledWith('/settings#settings-username');
    }
  });

  it('shows a loading skeleton for the header and both columns before any response', async () => {
    const el = await open();
    expect(all(el, '[data-ui="skeleton"]').length).toBeGreaterThanOrEqual(3);
    expect(text(q(el, 'h1'))).toBe('Seller profile');
    await respond(el);
    expect(all(el, '[data-ui="skeleton"]')).toHaveLength(0);
  });

  it('renders an anonymous visitor profile with counts, 20 listings per page and 5 reviews per page', async () => {
    const el = await open();
    const p = await request('/users/seller');
    const l = await request('/users/seller/listings', (params) => params.get('size') === '20');
    const r = await request('/users/seller/reviews', (params) => params.get('size') === '5');
    expect(l.request.params.has('cursor')).toBe(false);
    expect(r.request.params.has('cursor')).toBe(false);
    for (const req of [p, l, r]) {
      expect(req.request.headers.has('Authorization')).toBe(false);
    }
    p.flush(profile({ isDeveloper: true }));
    l.flush({ items: [card('a'), card('b')], nextCursor: 'L1' });
    r.flush({ items: [review('1', { rating: 5 })], nextCursor: null });
    await settle();

    expect(text(q(el, 'h1'))).toBe('@marta_v');
    const header = q(el, '[data-section="header"]')!;
    expect(text(header)).toContain('Bologna');
    expect(text(header)).toContain('Member since Mar 2026');
    expect(text(header)).toContain('4.5');
    expect(text(header)).toContain('(12 reviews)');
    expect(text(header)).toContain('30 sales');
    expect(text(header)).toContain('24 followers');
    expect(text(header)).toContain('7 following');
    expect(text(header)).toContain('Restores retro hardware.');
    expect(text(q(header, '[data-ui="status-chip"]'))).toBe('DEVELOPER');
    expect(q(header, 'app-avatar')).not.toBeNull();

    const listings = q(el, '[data-column="listings"]')!;
    expect(text(q(listings, 'h2'))).toBe('Listings by @marta_v');
    expect(all(listings, '[data-ui="listing-card"]')).toHaveLength(2);
    expect(text(listings)).toContain('Item a');
    expect(q(listings, '[data-ui="load-more"] button')).not.toBeNull();

    const reviews = q(el, '[data-column="reviews"]')!;
    expect(text(q(reviews, 'h2'))).toBe('Reviews');
    expect(all(reviews, '[data-review]')).toHaveLength(1);
    expect(text(reviews)).toContain('@buyer_1');
    expect(text(reviews)).toContain('Comment 1');
    expect(q(reviews, '[data-ui="load-more"] button')).toBeNull();
  });

  it('does not use activeListings to render the listings column', async () => {
    const el = await open();
    await respond(el, { listings: { items: [card('only')], nextCursor: null } });
    expect(all(el, '[data-column="listings"] [data-ui="listing-card"]')).toHaveLength(1);
  });

  it('shows not found when the synthesised profile has no registration', async () => {
    const el = await open('seller');
    await respond(el, { profile: profile({ registration: null, username: 'ghost' }) });
    expect(text(q(el, '[data-ui="error-state"]'))).toContain("This profile doesn't exist");
    expect(q(el, '[data-section="header"]')).toBeNull();
    expect(q(el, '[data-column]')).toBeNull();
    expect(q(el, '[data-follow]')).toBeNull();
    expect(text(q(el, 'h1'))).toBe('Seller profile');
  });

  it('shows the empty copy for no listings and no reviews', async () => {
    const el = await open();
    await respond(el, {
      listings: { items: [], nextCursor: null },
      reviews: { items: [], nextCursor: null },
    });
    expect(text(q(el, '[data-column="listings"]'))).toContain('No active listings right now.');
    expect(text(q(el, '[data-column="reviews"]'))).toContain('No reviews yet.');
    expect(all(el, '[data-ui="load-more"]')).toHaveLength(0);
  });

  it('uses singular counts for one sale, one review and one follower', async () => {
    const el = await open();
    await respond(el, {
      profile: profile({ completedSales: 1, ratingCount: 1, followerCount: 1, followingCount: 1 }),
    });
    const header = text(q(el, '[data-section="header"]'));
    expect(header).toContain('1 sale');
    expect(header).not.toContain('1 sales');
    expect(header).toContain('(1 review)');
    expect(header).not.toContain('(1 reviews)');
    expect(header).toContain('1 follower');
    expect(header).not.toContain('1 followers');
    expect(header).toContain('1 following');
  });

  it('shows zero sales and a no-reviews rating when the seller has no rating', async () => {
    const el = await open();
    await respond(el, {
      profile: profile({ ratingAvg: null, ratingCount: 0, completedSales: 0, followerCount: 0 }),
    });
    const header = text(q(el, '[data-section="header"]'));
    expect(header).toContain('No reviews yet');
    expect(header).toContain('0 sales');
    expect(header).toContain('0 followers');
  });

  it('omits missing meta parts and the bio when the profile does not have them', async () => {
    const el = await open();
    await respond(el, { profile: profile({ location: null, bio: null }) });
    const meta = text(q(el, '[data-section="meta"]'));
    expect(meta).toBe('Member since Mar 2026');
    expect(q(el, '[data-section="bio"]')).toBeNull();
  });

  it('renders a 500 character bio in full and wraps a long username', async () => {
    const bio = 'x'.repeat(500);
    const username = 'u'.repeat(60);
    const el = await open();
    await respond(el, { profile: profile({ bio, username }) });
    expect(text(q(el, '[data-section="bio"]'))).toBe(bio);
    expect(text(q(el, 'h1'))).toBe(`@${username}`);
  });

  it('appends each column cursor page without touching the other column', async () => {
    const el = await open();
    await respond(el);

    q(el, '[data-column="listings"] [data-ui="load-more"] button')!.click();
    const more = await request('/users/seller/listings', (p) => p.get('cursor') === 'L1');
    expect(more.request.params.get('size')).toBe('20');
    http.expectNone((req) => req.url === `${API}/users/seller/reviews`);
    more.flush({ items: [card('c')], nextCursor: null });
    await settle();
    expect(all(el, '[data-column="listings"] [data-ui="listing-card"]')).toHaveLength(3);
    expect(all(el, '[data-column="reviews"] [data-review]')).toHaveLength(2);
    expect(q(el, '[data-column="listings"] [data-ui="load-more"] button')).toBeNull();
    expect(q(el, '[data-column="reviews"] [data-ui="load-more"] button')).not.toBeNull();

    q(el, '[data-column="reviews"] [data-ui="load-more"] button')!.click();
    const moreReviews = await request('/users/seller/reviews', (p) => p.get('cursor') === 'R1');
    http.expectNone((req) => req.url === `${API}/users/seller/listings`);
    moreReviews.flush({ items: [review('3')], nextCursor: null });
    await settle();
    expect(all(el, '[data-column="reviews"] [data-review]')).toHaveLength(3);
    expect(all(el, '[data-column="listings"] [data-ui="listing-card"]')).toHaveLength(3);
  });

  it('retries only the column that failed', async () => {
    const el = await open();
    const p = await request('/users/seller');
    const l = await request('/users/seller/listings');
    const r = await request('/users/seller/reviews');
    p.flush(profile());
    l.flush({}, { status: 500, statusText: 'Server Error' });
    r.flush({ items: [review('1')], nextCursor: null });
    await settle();

    const listings = q(el, '[data-column="listings"]')!;
    expect(q(listings, '[data-ui="error-state"]')).not.toBeNull();
    expect(all(el, '[data-column="reviews"] [data-review]')).toHaveLength(1);
    expect(q(el, '[data-column="reviews"] [data-ui="error-state"]')).toBeNull();

    q(listings, '[data-ui="error-state"] button')!.click();
    const retry = await request('/users/seller/listings');
    http.expectNone((req) => req.url === `${API}/users/seller/reviews`);
    retry.flush({ items: [card('z')], nextCursor: null });
    await settle();
    expect(all(el, '[data-column="listings"] [data-ui="listing-card"]')).toHaveLength(1);
  });

  it('keeps the loaded cards and offers the button again when a Show more page fails', async () => {
    const el = await open();
    await respond(el);
    q(el, '[data-column="listings"] [data-ui="load-more"] button')!.click();
    const more = await request('/users/seller/listings', (p) => p.get('cursor') === 'L1');
    more.flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(all(el, '[data-column="listings"] [data-ui="listing-card"]')).toHaveLength(2);
    expect(q(el, '[data-column="listings"] [data-ui="load-more"] button')).not.toBeNull();
  });

  it('shows an error state with retry when the profile itself fails to load', async () => {
    const el = await open();
    const p = await request('/users/seller');
    const l = await request('/users/seller/listings');
    const r = await request('/users/seller/reviews');
    p.flush({}, { status: 500, statusText: 'Server Error' });
    l.flush({ items: [], nextCursor: null });
    r.flush({ items: [], nextCursor: null });
    await settle();
    expect(q(el, '[data-section="header"]')).toBeNull();
    q(el, '[data-ui="error-state"] button')!.click();
    const retry = await request('/users/seller');
    retry.flush(profile());
    await settle();
    expect(text(q(el, 'h1'))).toBe('@marta_v');
  });

  describe('follow state', () => {
    function signIn(sub: string): void {
      identity.set({ sub, groups: [] });
      status.set('authenticated');
    }

    const followButton = (el: HTMLElement) => q(el, '[data-follow]') as HTMLButtonElement | null;

    async function openWithProfile(overrides: Partial<PublicProfileResponse> = {}) {
      const el = await open();
      await respond(el, { profile: profile(overrides) });
      return el;
    }

    it('sends a guest to the login dialog with a safe returnUrl and sends no request', async () => {
      const el = await openWithProfile();
      expect(text(followButton(el))).toBe('Follow');
      followButton(el)!.click();
      await settle();
      const dialog = q(el, 'dialog')!;
      expect(dialog.hasAttribute('open')).toBe(true);
      expect(text(q(dialog, 'h2'))).toBe('Log in to continue');
      const login = q(dialog, 'a')!;
      expect(text(login)).toBe('Log in');
      expect(login.getAttribute('href')).toBe('/login?returnUrl=%2Fu%2Fseller');
      expect(text(dialog)).toContain('Not now');
      http.expectNone((req) => req.url.endsWith('/follow'));
    });

    it('closes the login dialog from Not now', async () => {
      const el = await openWithProfile();
      followButton(el)!.click();
      await settle();
      const notNow = all(el, 'dialog button').find((b) => text(b) === 'Not now')!;
      notNow.click();
      await settle();
      expect(q(el, 'dialog')!.hasAttribute('open')).toBe(false);
    });

    it('hides the toggle on the own profile and offers Edit profile', async () => {
      signIn('seller');
      const el = await openWithProfile();
      expect(followButton(el)).toBeNull();
      const edit = all(el, '[data-section="header"] a').find((a) => text(a) === 'Edit profile')!;
      expect(edit.getAttribute('href')).toBe('/settings');
    });

    it('shows Follow to a stranger who does not follow and Following to one who does', async () => {
      signIn('viewer');
      const el = await openWithProfile({ followedByMe: false });
      expect(text(followButton(el))).toBe('Follow');
      expect(q(el, '[data-section="header"] a[href="/settings"]')).toBeNull();
    });

    it('shows Following when followedByMe is true', async () => {
      signIn('viewer');
      const el = await openWithProfile({ followedByMe: true });
      expect(text(followButton(el))).toBe('Following');
    });

    it('flips state and count optimistically, then adopts the refetched profile', async () => {
      signIn('viewer');
      const el = await openWithProfile({ followerCount: 24 });
      followButton(el)!.click();
      harness.detectChanges();
      expect(text(followButton(el))).toBe('Following');
      expect(text(q(el, '[data-section="header"]'))).toContain('25 followers');
      expect(followButton(el)!.disabled).toBe(true);

      const put = await vi.waitFor(() =>
        http.expectOne((r) => r.method === 'PUT' && r.url === `${API}/users/seller/follow`),
      );
      expect(put.request.headers.get('Authorization')).toBe('Bearer viewer-token');
      put.flush(null, { status: 204, statusText: 'No Content' });
      const refetch = await request('/users/seller');
      refetch.flush(profile({ followerCount: 26, followedByMe: true }));
      await settle();
      expect(text(q(el, '[data-section="header"]'))).toContain('26 followers');
      expect(text(followButton(el))).toBe('Following');
      expect(followButton(el)!.disabled).toBe(false);
    });

    it('unfollows with DELETE and decrements the count', async () => {
      signIn('viewer');
      const el = await openWithProfile({ followedByMe: true, followerCount: 5 });
      followButton(el)!.click();
      harness.detectChanges();
      expect(text(followButton(el))).toBe('Follow');
      expect(text(q(el, '[data-section="header"]'))).toContain('4 followers');
      const del = await vi.waitFor(() =>
        http.expectOne((r) => r.method === 'DELETE' && r.url === `${API}/users/seller/follow`),
      );
      del.flush(null, { status: 204, statusText: 'No Content' });
      const refetch = await request('/users/seller');
      refetch.flush(profile({ followedByMe: false, followerCount: 4 }));
      await settle();
      expect(text(q(el, '[data-section="header"]'))).toContain('4 followers');
    });

    it('ignores a second click while the first toggle is pending', async () => {
      signIn('viewer');
      const el = await openWithProfile();
      const button = followButton(el)!;
      button.click();
      button.click();
      harness.detectChanges();
      button.click();
      const put = await vi.waitFor(() =>
        http.expectOne((r) => r.method === 'PUT' && r.url === `${API}/users/seller/follow`),
      );
      http.expectNone((r) => r.url === `${API}/users/seller/follow`);
      put.flush(null, { status: 204, statusText: 'No Content' });
      const refetch = await request('/users/seller');
      refetch.flush(profile({ followerCount: 25, followedByMe: true }));
      await settle();
      expect(text(q(el, '[data-section="header"]'))).toContain('25 followers');
    });

    it('rolls back both fields and shows an error toast when the follow fails', async () => {
      signIn('viewer');
      const el = await openWithProfile({ followerCount: 24 });
      followButton(el)!.click();
      harness.detectChanges();
      const put = await vi.waitFor(() =>
        http.expectOne((r) => r.method === 'PUT' && r.url === `${API}/users/seller/follow`),
      );
      put.flush({}, { status: 500, statusText: 'Server Error' });
      await settle();
      expect(text(followButton(el))).toBe('Follow');
      expect(text(q(el, '[data-section="header"]'))).toContain('24 followers');
      expect(followButton(el)!.disabled).toBe(false);
      expect(TestBed.inject(ToastService).current()?.kind).toBe('error');
      http.expectNone((r) => r.url === `${API}/users/seller`);
    });

    it('rolls an unfollow back to Following when DELETE fails', async () => {
      signIn('viewer');
      const el = await openWithProfile({ followedByMe: true, followerCount: 5 });
      followButton(el)!.click();
      const del = await vi.waitFor(() =>
        http.expectOne((r) => r.method === 'DELETE' && r.url === `${API}/users/seller/follow`),
      );
      del.flush({}, { status: 503, statusText: 'Unavailable' });
      await settle();
      expect(text(followButton(el))).toBe('Following');
      expect(text(q(el, '[data-section="header"]'))).toContain('5 followers');
    });

    it('exposes counts but no follower list, following list or Following tab', async () => {
      signIn('viewer');
      const el = await openWithProfile();
      const header = q(el, '[data-section="header"]')!;
      expect(text(header)).toContain('24 followers');
      expect(text(header)).toContain('7 following');
      expect(all(el, '[role="tab"], [role="tablist"]')).toHaveLength(0);
      expect(all(header, 'a[href^="/u/"]')).toHaveLength(0);
      expect(all(header, 'li')).toHaveLength(4);
    });

    it('asks a guest to log in from the favourite heart', async () => {
      const el = await openWithProfile();
      expect(q(el, 'dialog')!.hasAttribute('open')).toBe(false);
      q(el, '[data-ui="listing-card"] .favourite')!.click();
      await settle();
      expect(q(el, 'dialog')!.hasAttribute('open')).toBe(true);
      expect(text(q(el, 'dialog h2'))).toBe('Log in to save favourites');
      http.expectNone((req) => req.url.includes('/favourites'));
    });

    it('favourites optimistically for a member and rolls back on failure', async () => {
      signIn('viewer');
      const el = await openWithProfile();
      const heart = () => q(el, '[data-ui="listing-card"] .favourite')!;
      heart().click();
      harness.detectChanges();
      expect(heart().getAttribute('aria-pressed')).toBe('true');
      const put = await vi.waitFor(() =>
        http.expectOne((r) => r.method === 'PUT' && r.url === `${API}/favourites/a`),
      );
      put.flush({}, { status: 500, statusText: 'Server Error' });
      await settle();
      expect(heart().getAttribute('aria-pressed')).toBe('false');
      expect(TestBed.inject(ToastService).current()?.kind).toBe('error');
    });
  });
});
