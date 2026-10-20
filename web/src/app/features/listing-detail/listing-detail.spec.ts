import { Component, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import {
  CursorPage,
  ListingDetailResponse,
  OfferView,
  OrderView,
  PublicProfileResponse,
  ReviewView,
  SearchHit,
  SearchResultPage,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { MessagingApi } from '../../core/api/messaging-api';
import { OrderApi } from '../../core/api/order-api';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter, AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { ToastService } from '../../shared/ui/feedback';
import { authInterceptor } from '../../core/http/auth.interceptor';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { ListingDetailPage } from './listing-detail.page';

@Component({ template: '' })
class BlankPage {}

export function appError(status: number, code: string, extras: AppError['extras'] = {}): AppError {
  return { status, code, type: 'about:blank', fieldErrors: {}, extras };
}

const hardware: ListingDetailResponse = {
  id: 'l1',
  title: 'ThinkPad X1 Carbon',
  description: 'Line one\nLine two',
  price: '450.00',
  status: 'ACTIVE',
  type: 'HARDWARE',
  publication: '2026-03-01T10:00:00Z',
  sellerSub: 'seller-1',
  categoryId: 'cat-1',
  photos: ['https://cdn.test/p1.jpg', 'https://cdn.test/p2.jpg', 'https://cdn.test/p3.jpg'],
  photoKeys: ['raw/key-1.jpg', 'raw/key-2.jpg', 'raw/key-3.jpg'],
  paymentMethods: ['PAYPAL'],
  condition: 'GOOD',
  shippingLocation: 'Milan',
  shippingCost: '12.50',
  software: null,
  category: { id: 'cat-1', name: 'Laptops', ancestors: ['Computers', 'Laptops'] },
  priceHistory: [
    { instant: '2026-03-03T09:00:00Z', oldPrice: '500.00', newPrice: '450.00', drop: true },
    { instant: '2026-03-01T10:00:00Z', oldPrice: '520.00', newPrice: '500.00', drop: true },
  ],
  attributes: [
    { name: 'ram_gb', type: 'INTEGER', value: 16, unit: 'GB' },
    { name: 'touch_screen', type: 'BOOLEAN', value: true, unit: null },
  ],
  seller: { sub: 'seller-1', username: 'marta', avatar: null, ratingAvg: 4.5, ratingCount: 2 },
};

const software: ListingDetailResponse = {
  ...hardware,
  id: 's1',
  title: 'Notewind writing app',
  type: 'SOFTWARE',
  condition: null,
  shippingLocation: null,
  shippingCost: null,
  priceHistory: [],
  attributes: [],
  photos: ['https://cdn.test/s1.jpg'],
  photoKeys: ['raw/soft-1.jpg'],
  software: {
    platforms: ['MACOS', 'LINUX'],
    versions: [
      {
        number: '2.0.0',
        release: '2026-02-01T00:00:00Z',
        notes: 'Dark mode',
        artifactAvailable: true,
        current: true,
      },
      {
        number: '1.0.0',
        release: '2025-12-01T00:00:00Z',
        notes: null,
        artifactAvailable: true,
        current: false,
      },
    ],
  },
};

const profile: PublicProfileResponse = {
  sub: 'seller-1',
  username: 'marta',
  avatar: null,
  registration: '2025-01-01T00:00:00Z',
  location: 'Milan',
  bio: null,
  isDeveloper: false,
  ratingAvg: 4.5,
  ratingCount: 2,
  completedSales: 7,
  activeListings: [],
  followerCount: 3,
  followingCount: 1,
  followedByMe: false,
};

function review(id: string, author: string | null, rating = 5): ReviewView {
  return {
    id,
    listingId: 'l1',
    listingTitle: 'ThinkPad X1 Carbon',
    authorUsername: author,
    sellerSub: 'seller-1',
    rating,
    comment: `Comment ${id}`,
    createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  };
}

function hit(id: string): SearchHit {
  return {
    id,
    title: `Hit ${id}`,
    description: '',
    price: '99.00',
    listingType: 'HARDWARE',
    status: 'ACTIVE',
    categoryId: 'cat-1',
    categoryPath: [],
    sellerUsername: null,
    publishedAt: '2026-03-01T00:00:00Z',
    condition: 'GOOD',
    shippingLocation: null,
    platforms: [],
    currentVersion: null,
    coverUrl: `https://cdn.test/${id}.jpg`,
    attr: {},
  };
}

function searchPage(hits: SearchHit[]): SearchResultPage {
  return { hits, total: hits.length, offset: 0, size: 5, facets: [] };
}

function reviewPage(items: ReviewView[], nextCursor: string | null): CursorPage<ReviewView> {
  return { items, nextCursor };
}

interface Harness {
  fixture: ComponentFixture<ListingDetailPage>;
  root: HTMLElement;
  catalog: Record<string, ReturnType<typeof vi.fn>>;
  users: Record<string, ReturnType<typeof vi.fn>>;
  orders: Record<string, ReturnType<typeof vi.fn>>;
  messaging: Record<string, ReturnType<typeof vi.fn>>;
  identity: ReturnType<typeof signal<AuthIdentity | null>>;
  router: Router;
}

interface Scenario {
  listing?: Observable<ListingDetailResponse>;
  profile?: Observable<PublicProfileResponse>;
  reviews?: (cursor: string | undefined) => Observable<CursorPage<ReviewView>>;
  similar?: Observable<SearchResultPage>;
  identity?: AuthIdentity | null;
  offers?: OfferView[];
  favouriteIds?: string[];
  purchases?: Pick<OrderView, 'id' | 'listingId' | 'sellerSub' | 'status' | 'review'>[];
}

async function setup(scenario: Scenario = {}): Promise<Harness> {
  const identity = signal<AuthIdentity | null>(scenario.identity ?? null);
  const auth = {
    identity,
    isAuthenticated: computed(() => identity() !== null),
    isAdmin: computed(() => identity()?.groups.includes('ADMIN') ?? false),
  };
  const catalog = {
    listing: vi.fn(() => scenario.listing ?? of(hardware)),
    search: vi.fn(() => scenario.similar ?? of(searchPage([]))),
    favourites: vi.fn(() =>
      of({
        favourites: (scenario.favouriteIds ?? []).map((listingId) => ({
          listingId,
          title: 'Saved',
          price: '1.00',
          photos: [],
          coverUrl: null,
          status: 'AVAILABLE' as const,
          createdAt: '2026-03-01T00:00:00Z',
        })),
        page: 0,
        size: 100,
        totalElements: (scenario.favouriteIds ?? []).length,
        totalPages: 1,
      }),
    ),
    addFavourite: vi.fn(),
    removeFavourite: vi.fn(),
    deleteListing: vi.fn(),
  };
  const users = {
    getPublicProfile: vi.fn(() => scenario.profile ?? of(profile)),
    follow: vi.fn(),
    unfollow: vi.fn(),
  };
  const orders = {
    sellerReviews: vi.fn((_sub: string, query?: { cursor?: string }) =>
      (scenario.reviews ?? (() => of(reviewPage([], null))))(query?.cursor),
    ),
    sentOffers: vi.fn(() => of({ items: scenario.offers ?? [], nextCursor: null })),
    purchases: vi.fn(() => of({ items: scenario.purchases ?? [], nextCursor: null })),
    createOffer: vi.fn(),
    withdrawOffer: vi.fn(),
  };
  const messaging = { startConversation: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'listings/:id', component: BlankPage },
        { path: 'login', component: BlankPage },
        { path: 'me', component: BlankPage },
        { path: 'conversations/:id', component: BlankPage },
        { path: '', component: BlankPage },
      ]),
      { provide: AuthStore, useValue: auth },
      { provide: CatalogApi, useValue: catalog },
      { provide: UserApi, useValue: users },
      { provide: OrderApi, useValue: orders },
      { provide: MessagingApi, useValue: messaging },
    ],
  });
  const router = TestBed.inject(Router);
  await router.navigateByUrl('/listings/l1');
  const fixture = TestBed.createComponent(ListingDetailPage);
  fixture.componentRef.setInput('id', 'l1');
  await fixture.whenStable();
  return {
    fixture,
    root: fixture.nativeElement,
    catalog,
    users,
    orders,
    messaging,
    identity,
    router,
  };
}

describe('listing detail page', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['reducedMotion'];
    vi.restoreAllMocks();
  });

  it('renders a hardware listing with chips, price, shipping, attributes and price history', async () => {
    const { root } = await setup();
    expect(root.querySelector('h1')?.textContent?.trim()).toBe('ThinkPad X1 Carbon');
    const back = root.querySelector<HTMLAnchorElement>('a.back')!;
    expect(back.getAttribute('aria-label')).toBe('Back to listings');
    expect(back.getAttribute('href')).toBe('/');
    expect(back.querySelector('svg')).not.toBeNull();
    const chips = Array.from(root.querySelectorAll('.chips [data-ui="status-chip"]')).map((c) =>
      c.textContent?.trim(),
    );
    expect(chips).toEqual(['Computers / Laptops', 'Good']);
    expect(root.querySelector('.price')?.textContent).toContain('€450.00');
    expect(root.querySelector('.shipping')?.textContent).toContain('€12.50');
    expect(root.querySelector('.shipping')?.textContent).toContain('Milan');
    expect(root.querySelector('.description')?.textContent).toBe('Line one\nLine two');
    const attrs = Array.from(root.querySelectorAll('.attributes > div')).map(
      (d) => `${d.querySelector('dt')?.textContent}|${d.querySelector('dd')?.textContent}`,
    );
    expect(attrs).toEqual(['ram gb|16 GB', 'touch screen|Yes']);
    const history = root.querySelector('details.price-history')!;
    expect(history.querySelector('summary')?.textContent).toContain(
      'Dropped from €500.00 to €450.00 on 3 Mar 2026',
    );
    expect(history.querySelectorAll('li').length).toBe(2);
    expect(root.textContent).not.toContain('LICENCE KEY');
    expect(root.querySelector('.software')).toBeNull();
  });

  it('renders the gallery slides and thumbnails from the CDN photos', async () => {
    const { root } = await setup();
    const slides = Array.from(root.querySelectorAll<HTMLImageElement>('.slides img'));
    expect(slides.map((img) => img.getAttribute('src'))).toEqual(hardware.photos);
    expect(root.querySelectorAll('.thumbs button').length).toBe(3);
    expect(root.querySelector('.slides')?.getAttribute('tabindex')).toBe('0');
  });

  it('uses smooth gallery scrolling by default and automatic scrolling for the saved preference', async () => {
    localStorage.removeItem('rebyte.reducedMotion');
    const normal = await setup();
    const slides = normal.root.querySelector<HTMLElement>('.slides')!;
    const smoothScroll = vi.fn();
    slides.scrollTo = smoothScroll;
    normal.root.querySelectorAll<HTMLButtonElement>('.thumbs button')[1].click();
    expect(smoothScroll).toHaveBeenCalledWith({ left: slides.clientWidth, behavior: 'smooth' });

    normal.fixture.destroy();
    TestBed.resetTestingModule();
    localStorage.setItem('rebyte.reducedMotion', 'true');
    const reduced = await setup();
    const reducedSlides = reduced.root.querySelector<HTMLElement>('.slides')!;
    const automaticScroll = vi.fn();
    reducedSlides.scrollTo = automaticScroll;
    reduced.root.querySelectorAll<HTMLButtonElement>('.thumbs button')[1].click();
    expect(automaticScroll).toHaveBeenCalledWith({
      left: reducedSlides.clientWidth,
      behavior: 'auto',
    });
  });

  it('renders a software listing with licence chip, platforms and versions', async () => {
    const { root } = await setup({ listing: of(software) });
    const chips = Array.from(root.querySelectorAll('.chips [data-ui="status-chip"]')).map((c) =>
      c.textContent?.trim(),
    );
    expect(chips).toEqual(['Computers / Laptops', 'LICENCE KEY']);
    expect(root.querySelector('.shipping')).toBeNull();
    const platforms = Array.from(root.querySelectorAll('.software .platforms li')).map((p) =>
      p.textContent?.trim(),
    );
    expect(platforms).toEqual(['macOS', 'Linux']);
    expect(root.querySelector('.current-version')?.textContent).toContain('Version 2.0.0');
    const history = root.querySelector<HTMLDetailsElement>('details.version-history')!;
    expect(history.open).toBe(false);
    expect(history.querySelector('summary')?.textContent).toBe('Version history');
    expect(hasControl(root, 'Manage releases')).toBe(false);
    const versions = Array.from(root.querySelectorAll('.software .versions li'));
    expect(versions.length).toBe(2);
    expect(versions[0].textContent).toContain('2.0.0');
    expect(versions[0].textContent).toContain('CURRENT');
    expect(versions[0].textContent).toContain('Dark mode');
    expect(versions[1].textContent).not.toContain('CURRENT');
    expect(root.querySelector('.attributes')).toBeNull();
    expect(root.querySelector('details.price-history')).toBeNull();
    expect(root.textContent).not.toContain('Good');
  });

  it('shows the striped fallback and no thumbnails for a listing without photos', async () => {
    const { root } = await setup({ listing: of({ ...hardware, photos: [], photoKeys: [] }) });
    expect(root.querySelector('.slides img')).toBeNull();
    expect(root.querySelector('.slides .placeholder')).not.toBeNull();
    expect(root.querySelector('.thumbs')).toBeNull();
  });

  it('marks a sold listing', async () => {
    const { root } = await setup({ listing: of({ ...hardware, status: 'SOLD' }) });
    expect(root.querySelector('.price [data-ui="status-chip"]')?.textContent?.trim()).toBe('Sold');
  });

  it('shows the not-found state when the listing does not exist', async () => {
    const { root, router } = await setup({
      listing: throwError(() => appError(404, 'listing-not-found')),
    });
    const state = root.querySelector('app-error-state')!;
    expect(state.textContent).toContain("This listing isn't available");
    expect(state.textContent).toContain('It may have been sold or removed.');
    expect(root.querySelector('.slides')).toBeNull();
    const spy = vi.spyOn(router, 'navigateByUrl');
    state.querySelector('button')!.click();
    expect(spy).toHaveBeenCalledWith('/');
  });

  it('shows the removed state with the moderation reason to the owner or an admin', async () => {
    const { root } = await setup({
      listing: of({
        ...hardware,
        status: 'REMOVED',
        moderation: { reason: 'Counterfeit parts', instant: '2026-03-05T00:00:00Z' },
      }),
    });
    expect(root.querySelector('app-error-state')?.textContent).toContain(
      "This listing isn't available",
    );
    expect(root.querySelector('.moderation')?.textContent).toContain('Counterfeit parts');
    expect(root.querySelector('.slides')).toBeNull();
    expect(root.querySelector('h1')).not.toBeNull();
  });

  it('offers a retry for a failed load instead of the not-found state', async () => {
    const { fixture, root, catalog } = await setup({
      listing: throwError(() => appError(500, 'unknown-error')),
    });
    expect(root.querySelector('app-error-state')?.textContent).toContain("We couldn't load this.");
    catalog['listing'].mockReturnValue(of(hardware));
    root.querySelector<HTMLButtonElement>('app-error-state button')!.click();
    await fixture.whenStable();
    expect(root.querySelector('h1')?.textContent).toContain('ThinkPad');
  });

  it('shows a skeleton with a busy label while the listing loads', async () => {
    const { root } = await setup({ listing: NEVER });
    expect(root.querySelector('app-skeleton[aria-busy="true"]')).not.toBeNull();
    expect(root.querySelector('h1')).toBeNull();
  });

  it('degrades a partial seller without a crash', async () => {
    const { root } = await setup({
      listing: of({
        ...hardware,
        seller: { sub: 'seller-1', username: null, avatar: null, ratingAvg: null, ratingCount: 0 },
      }),
      profile: throwError(() => appError(404, 'user-not-found')),
    });
    const card = root.querySelector('.seller-card')!;
    expect(card.textContent).toContain('Seller unavailable');
    expect(card.textContent).toContain('No reviews yet');
    expect(card.querySelector('a')).toBeNull();
    expect(card.textContent).not.toContain('followers');
    expect(root.querySelector('h1')?.textContent).toContain('ThinkPad');
  });

  it('shows rating, link and follower counts from the seller profile', async () => {
    const { root } = await setup();
    const card = root.querySelector('.seller-card')!;
    expect(card.querySelector('a')?.getAttribute('href')).toBe('/u/seller-1');
    expect(card.textContent).toContain('@marta');
    expect(card.textContent).toContain('4.5 stars · 2 reviews');
    expect(card.textContent).toContain('3 followers · 1 following');
  });

  it('uses singular follower and review wording', async () => {
    const { root } = await setup({
      listing: of({
        ...hardware,
        seller: { sub: 'seller-1', username: 'marta', avatar: null, ratingAvg: 5, ratingCount: 1 },
      }),
      profile: of({ ...profile, followerCount: 1 }),
    });
    expect(root.querySelector('.seller-card')?.textContent).toContain('5.0 stars · 1 review');
    expect(root.querySelector('.seller-card')?.textContent).toContain('1 follower · 1 following');
  });

  it('lists reviews with @authorUsername only and an empty state', async () => {
    const { root } = await setup({
      reviews: () => of(reviewPage([review('r1', 'dario'), review('r2', null)], null)),
    });
    const cards = Array.from(root.querySelectorAll('.review'));
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('@dario');
    expect(cards[0].textContent).toContain('Comment r1');
    expect(cards[0].querySelector('.review-listing')).toBeNull();
    expect(cards[0].textContent).toContain('3 days ago');
    expect(cards[0].querySelector('a')).toBeNull();
    expect(cards[1].textContent).toContain('Unknown buyer');
    expect(root.querySelector('.reviews h2')?.textContent).toBe('Reviews');
    expect(root.querySelector('.reviews .section-head .caption')).toBeNull();
  });

  it('shows a listing-specific empty state without a generic review prompt', async () => {
    const { root } = await setup();
    expect(root.querySelector('.reviews')?.textContent).toContain(
      'No reviews for this listing yet.',
    );
    expect(root.querySelector('.review-gate')).toBeNull();
  });

  it('pages reviews with the cursor', async () => {
    const { root, orders } = await setup({
      reviews: (cursor) =>
        cursor
          ? of(reviewPage([review('r2', 'mira')], null))
          : of(reviewPage([review('r1', 'dario')], 'next-1')),
    });
    expect(root.querySelectorAll('.review').length).toBe(1);
    expect(orders['sellerReviews']).toHaveBeenCalledWith('seller-1', { listingId: 'l1', size: 5 });
    root.querySelector<HTMLButtonElement>('app-load-more button')!.click();
    await vi.waitFor(() => expect(root.querySelectorAll('.review').length).toBe(2));
    expect(orders['sellerReviews']).toHaveBeenLastCalledWith('seller-1', {
      cursor: 'next-1',
      size: 5,
      listingId: 'l1',
    });
    expect(root.querySelector('app-load-more button')).toBeNull();
  });

  it('only links to a delivered, unreviewed purchase of this listing', async () => {
    const { root } = await setup({
      identity: member,
      purchases: [
        { id: 'other-item', listingId: 'other', sellerSub: 'seller-1', status: 'DELIVERED' },
        { id: 'pending', listingId: 'l1', sellerSub: 'seller-1', status: 'PAID' },
        {
          id: 'reviewed',
          listingId: 'l1',
          sellerSub: 'seller-1',
          status: 'DELIVERED',
          review: review('existing', 'buyer'),
        },
        { id: 'matching', listingId: 'l1', sellerSub: 'seller-1', status: 'DELIVERED' },
      ],
    });
    expect(root.querySelector('.review-gate a')?.getAttribute('href')).toBe('/orders/matching');
  });

  it('does not offer review entry for a different listing from the same seller', async () => {
    const { root } = await setup({
      identity: member,
      purchases: [
        { id: 'other-item', listingId: 'other', sellerSub: 'seller-1', status: 'DELIVERED' },
      ],
    });
    expect(root.querySelector('.review-gate')).toBeNull();
  });

  it('keeps the page usable when reviews fail to load', async () => {
    const { root } = await setup({ reviews: () => throwError(() => appError(503, 'unknown')) });
    expect(root.querySelector('.reviews')?.textContent).toContain(
      'Reviews are unavailable right now.',
    );
    expect(root.querySelector('h1')?.textContent).toContain('ThinkPad');
  });

  it('lists up to four similar items and excludes the current listing', async () => {
    const { root, catalog } = await setup({
      similar: of(searchPage([hit('l1'), hit('a'), hit('b'), hit('c'), hit('d')])),
    });
    expect(catalog['search']).toHaveBeenCalledWith({ categoryId: 'cat-1', size: 5 });
    const links = Array.from(root.querySelectorAll('.similar a')).map((a) =>
      a.getAttribute('href'),
    );
    expect(links).toEqual(['/listings/a', '/listings/b', '/listings/c', '/listings/d']);
  });

  it('hides similar items when there are none or the search fails', async () => {
    const empty = await setup({ similar: of(searchPage([hit('l1')])) });
    expect(empty.root.querySelector('.similar')).toBeNull();
    TestBed.resetTestingModule();
    const failed = await setup({ similar: throwError(() => appError(503, 'unknown')) });
    expect(failed.root.querySelector('.similar')).toBeNull();
    expect(failed.root.querySelector('h1')).not.toBeNull();
  });

  it('never renders raw photo keys or an author sub', async () => {
    const leaked = { ...review('r1', 'dario'), authorSub: 'author-sub-secret' };
    const { root } = await setup({ reviews: () => of(reviewPage([leaked], null)) });
    const html = root.innerHTML;
    expect(html).not.toContain('raw/key-');
    expect(html).not.toContain('author-sub-secret');
    expect(html).not.toContain('authorSub');
  });
});

describe('listing detail page requests', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    TestBed.resetTestingModule();
  });

  it('sends no bearer for an anonymous visitor', async () => {
    const adapter = { accessToken: vi.fn(async () => null) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        provideTestAppConfig(),
        { provide: AuthAdapter, useValue: adapter },
      ],
    });
    const fixture = TestBed.createComponent(ListingDetailPage);
    fixture.componentRef.setInput('id', 'l1');
    const http = TestBed.inject(HttpTestingController);
    const base = TEST_APP_CONFIG.apiBaseUrl;
    fixture.detectChanges();
    const take = async (url: string): Promise<TestRequest> => {
      const found: TestRequest[] = [];
      await vi.waitFor(() => {
        found.push(...http.match(url));
        expect(found.length).toBe(1);
      });
      return found[0];
    };
    const listingRequest = await take(`${base}/listings/l1`);
    expect(listingRequest.request.headers.has('Authorization')).toBe(false);
    listingRequest.flush(JSON.stringify(hardware));
    for (const path of [
      '/users/seller-1',
      '/users/seller-1/reviews?listingId=l1&size=5',
      '/search?categoryId=cat-1&size=5',
    ]) {
      const request = await take(`${base}${path}`);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush('', { status: 404, statusText: 'Not Found' });
    }
    await fixture.whenStable();
  });
});

const member: AuthIdentity = { sub: 'buyer-1', groups: [] };
const owner: AuthIdentity = { sub: 'seller-1', groups: [] };
const moderator: AuthIdentity = { sub: 'admin-1', groups: ['ADMIN'] };

function offer(over: Partial<OfferView> = {}): OfferView {
  return {
    id: 'o1',
    listingId: 'l1',
    listingTitle: 'ThinkPad X1 Carbon',
    buyerSub: 'buyer-1',
    sellerSub: 'seller-1',
    amount: '400.00',
    askingPrice: '450.00',
    status: 'PENDING',
    rejectionReason: null,
    expiresAt: null,
    orderId: null,
    createdAt: '2026-03-02T00:00:00Z',
    ...over,
  };
}

function control(root: HTMLElement, name: string): HTMLElement {
  const found = Array.from(root.querySelectorAll<HTMLElement>('button, a')).find(
    (element) =>
      element.textContent?.replace(/\s+/g, ' ').trim() === name ||
      element.getAttribute('aria-label') === name,
  );
  if (!found) throw new Error(`No control named "${name}"`);
  return found;
}

function hasControl(root: HTMLElement, name: string): boolean {
  try {
    control(root, name);
    return true;
  } catch {
    return false;
  }
}

function type(element: Element | null, value: string): void {
  const field = element as HTMLInputElement | HTMLTextAreaElement;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
}

async function press(harness: Harness, name: string): Promise<void> {
  control(harness.root, name).click();
  await harness.fixture.whenStable();
}

describe('listing actions', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  function toastText(): string | undefined {
    return TestBed.inject(ToastService).current()?.text;
  }

  describe('visitor', () => {
    it.each([
      ['Buy now', 'Log in to continue'],
      ['Message seller', 'Log in to continue'],
      ['Make an offer', 'Log in to continue'],
      ['Add to favourites', 'Log in to save favourites'],
      ['Follow', 'Log in to continue'],
    ])('opens the login dialog from "%s"', async (name, heading) => {
      const harness = await setup();
      await press(harness, name);
      expect(harness.root.querySelector('app-login-required-dialog h2')?.textContent).toBe(heading);
      await press(harness, 'Not now');
      expect(harness.root.querySelector('app-login-required-dialog')).toBeNull();
    });

    it('sends the visitor to login with the listing as returnUrl', async () => {
      const harness = await setup();
      const navigate = vi.spyOn(harness.router, 'navigate').mockResolvedValue(true);
      await press(harness, 'Buy now');
      await press(harness, 'Log in');
      expect(navigate).toHaveBeenCalledWith(['/login'], {
        queryParams: { returnUrl: '/listings/l1' },
      });
    });

    it('does not load offers or favourites for a visitor', async () => {
      const { orders, catalog } = await setup();
      expect(orders['sentOffers']).not.toHaveBeenCalled();
      expect(catalog['favourites']).not.toHaveBeenCalled();
    });

    it('closes a dialog with Escape', async () => {
      const harness = await setup();
      await press(harness, 'Buy now');
      harness.root
        .querySelector('dialog')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await harness.fixture.whenStable();
      expect(harness.root.querySelector('dialog')).toBeNull();
    });
  });

  describe('signed-in buyer', () => {
    it('links Buy now to the checkout and offers every buyer action', async () => {
      const harness = await setup({ identity: member });
      expect(control(harness.root, 'Buy now').getAttribute('href')).toBe('/checkout/l1');
      for (const name of ['Message seller', 'Add to favourites', 'Make an offer', 'Follow']) {
        expect(hasControl(harness.root, name)).toBe(true);
      }
      expect(harness.root.textContent).not.toContain('This is your listing');
    });

    it('hides offers for software listings', async () => {
      const harness = await setup({ identity: member, listing: of(software) });
      expect(hasControl(harness.root, 'Buy now')).toBe(true);
      expect(hasControl(harness.root, 'Make an offer')).toBe(false);
      expect(harness.orders['sentOffers']).not.toHaveBeenCalled();
    });

    it('disables Buy now with the exact reason when the listing is sold', async () => {
      const harness = await setup({
        identity: member,
        listing: of({ ...hardware, status: 'SOLD' }),
      });
      const buy = control(harness.root, 'Buy now') as HTMLButtonElement;
      expect(buy.tagName).toBe('BUTTON');
      expect(buy.disabled).toBe(true);
      const reason = harness.root.querySelector('#buy-reason')!;
      expect(reason.textContent).toBe('This item is no longer available.');
      expect(buy.getAttribute('aria-describedby')).toBe('buy-reason');
      expect(hasControl(harness.root, 'Make an offer')).toBe(false);
    });
  });

  describe('own listing', () => {
    it('shows only the owner panel, never a purchase, message, offer or favourite action', async () => {
      const harness = await setup({ identity: owner });
      expect(harness.root.querySelector('.panel')).not.toBeNull();
      expect(harness.root.textContent).not.toContain('This is your listing');
      expect(control(harness.root, 'Edit listing').getAttribute('href')).toBe('/listings/l1/edit');
      expect(hasControl(harness.root, 'Remove listing')).toBe(true);
      for (const name of [
        'Buy now',
        'Message seller',
        'Make an offer',
        'Add to favourites',
        'Follow',
      ]) {
        expect(hasControl(harness.root, name)).toBe(false);
      }
      expect(harness.orders['sentOffers']).not.toHaveBeenCalled();
      expect(harness.orders['purchases']).not.toHaveBeenCalled();
    });

    it('offers the release action on an owned software listing', async () => {
      const harness = await setup({ identity: owner, listing: of(software) });
      const action = control(harness.root, 'Manage releases');
      expect(action.closest('.software-owner')).not.toBeNull();
      expect(action.getAttribute('href')).toBe('/listings/s1/releases');
      await press(harness, 'Remove listing');
      expect(harness.root.querySelector('app-remove-listing-dialog')).not.toBeNull();
    });

    it('removes the listing without a reason and returns to my listings', async () => {
      const harness = await setup({ identity: owner });
      const navigate = vi.spyOn(harness.router, 'navigate').mockResolvedValue(true);
      harness.catalog['deleteListing'].mockReturnValue(of(undefined));
      await press(harness, 'Remove listing');
      const dialog = harness.root.querySelector('app-remove-listing-dialog')!;
      expect(dialog.querySelector('h2')?.textContent).toBe('Remove this listing?');
      expect(dialog.textContent).toContain('“ThinkPad X1 Carbon” will be taken down');
      expect(dialog.querySelector('textarea')).toBeNull();
      await press(harness, 'Remove');
      expect(harness.catalog['deleteListing']).toHaveBeenCalledTimes(1);
      expect(harness.catalog['deleteListing'].mock.calls[0]).toEqual(['l1']);
      expect(navigate).toHaveBeenCalledWith(['/me'], { queryParams: { tab: 'listings' } });
      expect(toastText()).toBe('Listing removed');
    });

    it('keeps the listing when the owner chooses Keep it', async () => {
      const harness = await setup({ identity: owner });
      await press(harness, 'Remove listing');
      await press(harness, 'Keep it');
      expect(harness.catalog['deleteListing']).not.toHaveBeenCalled();
      expect(harness.root.querySelector('dialog')).toBeNull();
    });

    it('shows the failure and refetches when the backend rejects the removal', async () => {
      const harness = await setup({ identity: owner });
      harness.catalog['deleteListing'].mockReturnValue(
        throwError(() => appError(403, 'access-denied')),
      );
      await press(harness, 'Remove listing');
      await press(harness, 'Remove');
      expect(harness.root.querySelector('app-remove-listing-dialog .error')?.textContent).toBe(
        "You don't have permission to do that.",
      );
      expect(harness.catalog['listing']).toHaveBeenCalledTimes(2);
    });
  });

  describe('admin moderation', () => {
    it("requires a reason before removing someone else's listing", async () => {
      const harness = await setup({ identity: moderator });
      harness.catalog['deleteListing'].mockReturnValue(of(undefined));
      expect(hasControl(harness.root, 'Edit listing')).toBe(false);
      await press(harness, 'Remove listing (moderation)');
      const dialog = harness.root.querySelector('app-remove-listing-dialog')!;
      expect(dialog.querySelector('h2')?.textContent).toBe('Remove this listing (moderation)?');
      await press(harness, 'Remove');
      expect(harness.catalog['deleteListing']).not.toHaveBeenCalled();
      expect(dialog.textContent).toContain('Enter a reason for the removal.');
      type(dialog.querySelector('textarea'), '   ');
      await press(harness, 'Remove');
      expect(harness.catalog['deleteListing']).not.toHaveBeenCalled();
      type(dialog.querySelector('textarea'), ' Counterfeit parts ');
      await press(harness, 'Remove');
      expect(harness.catalog['deleteListing']).toHaveBeenCalledWith('l1', 'Counterfeit parts');
      expect(harness.catalog['listing']).toHaveBeenCalledTimes(2);
      expect(toastText()).toBe('Listing removed');
    });
  });

  describe('favourite', () => {
    it('starts pressed when the listing is in the caller favourites', async () => {
      const harness = await setup({ identity: member, favouriteIds: ['x', 'l1'] });
      const heart = control(harness.root, 'Remove from favourites');
      expect(heart.getAttribute('aria-pressed')).toBe('true');
    });

    it('toggles optimistically and keeps the state on success', async () => {
      const harness = await setup({ identity: member });
      const request = new Subject<unknown>();
      harness.catalog['addFavourite'].mockReturnValue(request);
      await press(harness, 'Add to favourites');
      expect(control(harness.root, 'Remove from favourites').getAttribute('aria-pressed')).toBe(
        'true',
      );
      expect(harness.catalog['addFavourite']).toHaveBeenCalledWith('l1');
      request.next({});
      request.complete();
      await harness.fixture.whenStable();
      expect(control(harness.root, 'Remove from favourites')).not.toBeNull();
      harness.catalog['removeFavourite'].mockReturnValue(of(undefined));
      await press(harness, 'Remove from favourites');
      expect(harness.catalog['removeFavourite']).toHaveBeenCalledWith('l1');
      expect(control(harness.root, 'Add to favourites').getAttribute('aria-pressed')).toBe('false');
    });

    it('rolls back and explains when the request fails', async () => {
      const harness = await setup({ identity: member });
      const request = new Subject<unknown>();
      harness.catalog['addFavourite'].mockReturnValue(request);
      await press(harness, 'Add to favourites');
      expect(hasControl(harness.root, 'Remove from favourites')).toBe(true);
      request.error(appError(503, 'unknown-error'));
      await harness.fixture.whenStable();
      expect(control(harness.root, 'Add to favourites').getAttribute('aria-pressed')).toBe('false');
      expect(toastText()).toBe('Something went wrong. Try again in a minute.');
    });

    it('rolls back, refetches and names the cause when the listing is no longer available', async () => {
      const harness = await setup({ identity: member });
      harness.catalog['addFavourite'].mockReturnValue(
        throwError(() => appError(422, 'listing-not-available')),
      );
      await press(harness, 'Add to favourites');
      expect(hasControl(harness.root, 'Add to favourites')).toBe(true);
      expect(toastText()).toBe('This item is no longer available.');
      expect(harness.catalog['listing']).toHaveBeenCalledTimes(2);
    });
  });

  describe('follow', () => {
    it('toggles optimistically and adjusts the follower count', async () => {
      const harness = await setup({ identity: member });
      const request = new Subject<unknown>();
      harness.users['follow'].mockReturnValue(request);
      await press(harness, 'Follow');
      expect(harness.users['follow']).toHaveBeenCalledWith('seller-1');
      expect(hasControl(harness.root, 'Following')).toBe(true);
      expect(harness.root.querySelector('.followers')?.textContent).toContain('4 followers');
      request.next(undefined);
      request.complete();
      await harness.fixture.whenStable();
      harness.users['unfollow'].mockReturnValue(of(undefined));
      await press(harness, 'Following');
      expect(harness.users['unfollow']).toHaveBeenCalledWith('seller-1');
      expect(harness.root.querySelector('.followers')?.textContent).toContain('3 followers');
    });

    it('restores the label and the count when the request fails', async () => {
      const harness = await setup({ identity: member });
      const request = new Subject<unknown>();
      harness.users['follow'].mockReturnValue(request);
      await press(harness, 'Follow');
      request.error(appError(429, 'rate-limited'));
      await harness.fixture.whenStable();
      expect(hasControl(harness.root, 'Follow')).toBe(true);
      expect(harness.root.querySelector('.followers')?.textContent).toContain('3 followers');
      expect(toastText()).toBe("You're doing that too quickly. Wait a moment and try again.");
    });

    it('starts as Following when followedByMe is true', async () => {
      const harness = await setup({
        identity: member,
        profile: of({ ...profile, followedByMe: true }),
      });
      expect(hasControl(harness.root, 'Following')).toBe(true);
    });
  });

  describe('make an offer', () => {
    async function openOffer(scenario: Scenario = {}): Promise<Harness> {
      const harness = await setup({ identity: member, ...scenario });
      await press(harness, 'Make an offer');
      return harness;
    }

    function dialog(harness: Harness): Element {
      return harness.root.querySelector('app-make-offer-dialog')!;
    }

    it('describes the listing and sends no note field', async () => {
      const harness = await openOffer();
      const view = dialog(harness);
      expect(view.querySelector('h2')?.textContent).toBe('Make an offer');
      expect(view.querySelector('.sub')?.textContent).toBe(
        'ThinkPad X1 Carbon · listed at €450.00',
      );
      expect(view.querySelector('textarea')).toBeNull();
      expect(view.querySelector('#offer-hint')?.textContent).toBe('Enter your offer');
      expect((control(view as HTMLElement, 'Send offer') as HTMLButtonElement).disabled).toBe(true);
    });

    it('computes the 5, 10 and 15 percent shortcuts in cents', async () => {
      const harness = await openOffer();
      const chips = Array.from(dialog(harness).querySelectorAll('.chips button')).map(
        (chip) =>
          `${chip.querySelector('.price')?.textContent}|${chip.querySelector('.label')?.textContent}`,
      );
      expect(chips).toEqual(['€427.50|5% off', '€405.00|10% off', '€382.50|15% off']);
    });

    it('rounds shortcuts without floating point drift', async () => {
      const harness = await openOffer({ listing: of({ ...hardware, price: '19.99' }) });
      const chips = Array.from(dialog(harness).querySelectorAll('.chips .price')).map(
        (price) => price.textContent,
      );
      expect(chips).toEqual(['€18.99', '€17.99', '€16.99']);
    });

    it('disables shortcuts that would reach zero or the asking price', async () => {
      const harness = await openOffer({ listing: of({ ...hardware, price: '0.01' }) });
      const disabled = Array.from(
        dialog(harness).querySelectorAll<HTMLButtonElement>('.chips button'),
      ).map((chip) => chip.disabled);
      expect(disabled).toEqual([true, true, true]);
    });

    it('fills the amount from a shortcut', async () => {
      const harness = await openOffer();
      dialog(harness).querySelectorAll<HTMLButtonElement>('.chips button')[1].click();
      await harness.fixture.whenStable();
      expect((dialog(harness).querySelector('input') as HTMLInputElement).value).toBe('405.00');
      expect(dialog(harness).querySelector('#offer-hint')?.textContent).toBe(
        "You'd save €45.00. Shipping is added at checkout.",
      );
    });

    it.each([
      ['0', 'Enter an amount greater than zero.', false],
      ['0.00', 'Enter an amount greater than zero.', false],
      ['abc', 'Use an amount with at most 2 decimals.', false],
      ['12.345', 'Use an amount with at most 2 decimals.', false],
      ['-5', 'Use an amount with at most 2 decimals.', false],
      ['449.995', 'Use an amount with at most 2 decimals.', false],
      ['450', "That's the asking price. Use Buy now instead.", false],
      ['450.00', "That's the asking price. Use Buy now instead.", false],
      ['999', "That's the asking price. Use Buy now instead.", false],
      ['449.99', "You'd save €0.01. Shipping is added at checkout.", true],
      ['0.01', "You'd save €449.99. Shipping is added at checkout.", true],
      ['300.5', "You'd save €149.50. Shipping is added at checkout.", true],
    ])('validates the amount %s', async (amount, hint, enabled) => {
      const harness = await openOffer();
      type(dialog(harness).querySelector('input'), amount);
      await harness.fixture.whenStable();
      expect(dialog(harness).querySelector('#offer-hint')?.textContent).toBe(hint);
      expect(
        (control(dialog(harness) as HTMLElement, 'Send offer') as HTMLButtonElement).disabled,
      ).toBe(!enabled);
      expect(dialog(harness).querySelector('#offer-hint')?.classList.contains('warn')).toBe(
        hint.startsWith("That's"),
      );
    });

    it('submits only the listing id and the amount', async () => {
      const harness = await openOffer();
      harness.orders['createOffer'].mockReturnValue(of(offer({ amount: '449.99' })));
      type(dialog(harness).querySelector('input'), '449.99');
      await harness.fixture.whenStable();
      await press(harness, 'Send offer');
      expect(harness.orders['createOffer']).toHaveBeenCalledTimes(1);
      const request = harness.orders['createOffer'].mock.calls[0][0] as Record<string, unknown>;
      expect(request).toEqual({ listingId: 'l1', amount: '449.99' });
      expect(Object.keys(request)).toEqual(['listingId', 'amount']);
      expect(harness.root.querySelector('app-make-offer-dialog')).toBeNull();
      expect(harness.root.querySelector('app-banner')?.textContent).toContain(
        'Your offer of €449.99 is waiting for the seller',
      );
      expect(toastText()).toBe('Offer sent');
    });

    it('normalises the amount to two decimals', async () => {
      const harness = await openOffer();
      harness.orders['createOffer'].mockReturnValue(of(offer({ amount: '300.50' })));
      type(dialog(harness).querySelector('input'), '300.5');
      await harness.fixture.whenStable();
      await press(harness, 'Send offer');
      expect(harness.orders['createOffer'].mock.calls[0][0]).toEqual({
        listingId: 'l1',
        amount: '300.50',
      });
    });

    it.each([
      [409, 'duplicate-pending-offer', 'You already have a pending offer on this item.'],
      [422, 'invalid-offer-amount', 'Enter an amount below the asking price.'],
      [429, 'rate-limited', "You're doing that too quickly. Wait a moment and try again."],
    ])('maps %s %s to its copy and refetches the state', async (status, code, copy) => {
      const harness = await openOffer();
      harness.orders['createOffer'].mockReturnValue(throwError(() => appError(status, code)));
      type(dialog(harness).querySelector('input'), '400');
      await harness.fixture.whenStable();
      await press(harness, 'Send offer');
      expect(dialog(harness).querySelector('.error')?.textContent).toBe(copy);
      expect(harness.root.querySelector('app-make-offer-dialog')).not.toBeNull();
      expect(harness.catalog['listing']).toHaveBeenCalledTimes(status === 429 ? 1 : 2);
    });
  });

  describe('offer states', () => {
    it('shows a pending offer with an inline withdraw confirmation', async () => {
      const harness = await setup({ identity: member, offers: [offer()] });
      expect(harness.orders['sentOffers']).toHaveBeenCalledWith({ listingId: 'l1' });
      expect(harness.root.querySelector('app-banner')?.textContent).toContain(
        'Your offer of €400.00 is waiting for the seller',
      );
      expect(hasControl(harness.root, 'Make an offer')).toBe(false);
      harness.orders['withdrawOffer'].mockReturnValue(of(offer({ status: 'WITHDRAWN' })));
      await press(harness, 'Withdraw offer');
      expect(harness.root.querySelector('[aria-label="Withdraw offer?"]')).not.toBeNull();
      await press(harness, 'Keep');
      expect(harness.root.querySelector('[aria-label="Withdraw offer?"]')).toBeNull();
      await press(harness, 'Withdraw offer');
      await press(harness, 'Withdraw');
      expect(harness.orders['withdrawOffer']).toHaveBeenCalledWith('o1');
      expect(harness.root.querySelector('app-banner')).toBeNull();
      expect(hasControl(harness.root, 'Make an offer')).toBe(true);
      expect(toastText()).toBe('Offer withdrawn');
    });

    it('refetches when the pending offer was already answered', async () => {
      const harness = await setup({ identity: member, offers: [offer()] });
      harness.orders['withdrawOffer'].mockReturnValue(
        throwError(() => appError(409, 'offer-not-pending')),
      );
      await press(harness, 'Withdraw offer');
      await press(harness, 'Withdraw');
      expect(toastText()).toBe('This offer was already answered.');
      expect(harness.orders['sentOffers']).toHaveBeenCalledTimes(2);
    });

    it('links an accepted offer to the checkout with a countdown', async () => {
      const expiresAt = new Date(Date.now() + 3_600_000).toISOString();
      const harness = await setup({
        identity: member,
        offers: [offer({ id: 'o2', status: 'ACCEPTED', expiresAt })],
      });
      const banner = harness.root.querySelector('app-banner')!;
      expect(banner.textContent).toContain('@marta accepted your offer of €400.00. Pay before');
      expect(banner.textContent).toContain('to keep the item.');
      const link = control(harness.root, 'Checkout at €400.00');
      expect(link.getAttribute('href')).toBe('/checkout/l1?offer=o2');
      expect(harness.root.querySelector('[role="timer"]')?.textContent).toMatch(/left$/);
      expect(hasControl(harness.root, 'Make an offer')).toBe(false);
    });

    it('treats an expired acceptance as no offer', async () => {
      const expiresAt = new Date(Date.now() - 60_000).toISOString();
      const harness = await setup({
        identity: member,
        offers: [offer({ status: 'ACCEPTED', expiresAt })],
      });
      expect(harness.root.querySelector('app-banner')).toBeNull();
      expect(hasControl(harness.root, 'Make an offer')).toBe(true);
    });

    it.each([
      ['SELLER', 'The seller declined your offer.'],
      ['OTHER_ACCEPTED', 'The seller accepted another offer.'],
      ['LISTING_UNAVAILABLE', 'The item is no longer available.'],
      ['PRICE_CHANGED', 'The price changed, so your offer was closed.'],
    ] as const)('explains a %s rejection and allows a new offer', async (rejectionReason, copy) => {
      const harness = await setup({
        identity: member,
        offers: [offer({ status: 'REJECTED', rejectionReason })],
      });
      expect(harness.root.querySelector('app-banner')?.textContent).toContain(copy);
      expect(hasControl(harness.root, 'Make an offer')).toBe(true);
    });

    it('shows no banner when the newest offer expired or was withdrawn', async () => {
      const harness = await setup({
        identity: member,
        offers: [
          offer({ id: 'o3', status: 'WITHDRAWN' }),
          offer({ id: 'o2', status: 'REJECTED', rejectionReason: 'SELLER' }),
        ],
      });
      expect(harness.root.querySelector('app-banner')).toBeNull();
      expect(hasControl(harness.root, 'Make an offer')).toBe(true);
    });
  });

  describe('message the seller', () => {
    async function openMessage(): Promise<Harness> {
      const harness = await setup({ identity: member });
      vi.spyOn(harness.router, 'navigate').mockResolvedValue(true);
      await press(harness, 'Message seller');
      return harness;
    }

    function field(harness: Harness): HTMLTextAreaElement {
      return harness.root.querySelector('app-message-seller-dialog textarea')!;
    }

    it('prefills the greeting and starts the conversation', async () => {
      const harness = await openMessage();
      expect(harness.root.querySelector('app-message-seller-dialog h2')?.textContent).toBe(
        'Message @marta',
      );
      expect(field(harness).value).toBe('Hi, is this still available?');
      harness.messaging['startConversation'].mockReturnValue(of({ id: 'c9' }));
      await press(harness, 'Send message');
      expect(harness.messaging['startConversation']).toHaveBeenCalledWith({
        listingId: 'l1',
        text: 'Hi, is this still available?',
        imageKeys: [],
      });
      expect(harness.router.navigate).toHaveBeenCalledWith(['/conversations', 'c9']);
    });

    it('opens the existing conversation and keeps the typed draft', async () => {
      const harness = await openMessage();
      harness.messaging['startConversation'].mockReturnValue(
        throwError(() => appError(409, 'conversation-exists', { conversationId: 'c1' })),
      );
      type(field(harness), 'Could you ship to Rome?');
      await press(harness, 'Send message');
      expect(harness.router.navigate).toHaveBeenCalledWith(['/conversations', 'c1'], {
        state: { draft: 'Could you ship to Rome?' },
      });
    });

    it('shows inline errors for self messages and blank text', async () => {
      const harness = await openMessage();
      type(field(harness), '   ');
      await press(harness, 'Send message');
      expect(harness.messaging['startConversation']).not.toHaveBeenCalled();
      expect(harness.root.querySelector('app-message-seller-dialog')?.textContent).toContain(
        'Write a message first.',
      );
      harness.messaging['startConversation'].mockReturnValue(
        throwError(() => appError(422, 'self-conversation')),
      );
      type(field(harness), 'Hello');
      await press(harness, 'Send message');
      expect(harness.root.querySelector('app-message-seller-dialog')?.textContent).toContain(
        "You can't message yourself.",
      );
    });
  });

  describe('photo viewer', () => {
    it('opens the full-screen viewer for a photo', async () => {
      const harness = await setup();
      await press(harness, 'View photo 2 full screen');
      const image = harness.root.querySelector('app-modal-dialog.viewer img, .viewer-image');
      expect(image?.getAttribute('src')).toBe(hardware.photos[1]);
      await press(harness, 'Close');
      expect(harness.root.querySelector('.viewer-image')).toBeNull();
    });
  });
});
