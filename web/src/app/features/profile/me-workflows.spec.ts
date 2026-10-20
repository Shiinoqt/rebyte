import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { of, Subject, throwError } from 'rxjs';
import { OrderApi } from '../../core/api/order-api';
import { CatalogApi } from '../../core/api/catalog-api';
import { AuthStore } from '../../core/auth/auth.store';
import { LicenseView, OrderView, ReviewView } from '../../core/api/api-models';
import { MeReviewsStore, MeReviewsTab } from './me-reviews.tab';
import { MeLibraryStore, MeLibraryTab, validArtifactUrl } from './me-library.tab';

const review = (id: string): ReviewView => ({
  id,
  listingId: 'listing-' + id,
  listingTitle: 'Listing ' + id,
  authorUsername: null,
  sellerSub: 'seller',
  rating: 4,
  comment: 'Works well',
  createdAt: '2026-10-01T10:00:00Z',
});
const order = (id: string, reviewed = true, buyerSub = 'buyer'): OrderView => ({
  id,
  listingId: 'listing-' + id,
  listingTitle: 'Listing ' + id,
  listingType: 'SOFTWARE',
  buyerSub,
  sellerSub: 'seller',
  pricePaid: '20.00',
  itemPrice: '20.00',
  shippingCost: '0.00',
  paymentMethod: 'PAYPAL',
  status: 'DELIVERED',
  createdAt: '2026-10-01T10:00:00Z',
  expiryAt: '2026-10-02T10:00:00Z',
  ...(reviewed ? { review: review(id) } : {}),
});
const license = (id: string, status: LicenseView['status'] = 'ACTIVE'): LicenseView => ({
  orderId: id,
  listingId: 'listing',
  listingTitle: 'Utility',
  key: 'returned-key',
  status,
  issuedAt: '2026-10-01T10:00:00Z',
  revokedAt: null,
});
const settle = async () => {
  for (let i = 0; i < 30; i++) await Promise.resolve();
};

describe('account library and review data flows', () => {
  afterEach(() => TestBed.resetTestingModule());
  function setup(orders: object, catalog: object = {}) {
    let cleanup = () => {};
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        MeReviewsStore,
        MeLibraryStore,
        { provide: OrderApi, useValue: orders },
        { provide: CatalogApi, useValue: catalog },
        {
          provide: AuthStore,
          useValue: {
            identity: signal({ sub: 'buyer' }),
            registerCleanup: (fn: () => void) => {
              cleanup = fn;
              return () => {};
            },
          },
        },
      ],
    });
    return () => cleanup();
  }
  it('hydrates purchase details through sparse and empty continuation pages', async () => {
    const purchases = vi
      .fn()
      .mockReturnValueOnce(of({ items: [order('a', false)], nextCursor: 'p1' }))
      .mockReturnValueOnce(of({ items: [], nextCursor: 'p2' }))
      .mockReturnValueOnce(of({ items: [order('b', false)], nextCursor: null }));
    const detail = vi.fn((id: string) => of(order(id, id === 'b')));
    setup({ purchases, order: detail });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    expect(purchases.mock.calls.map((call) => call[0])).toEqual([
      { size: 20 },
      { size: 20, cursor: 'p1' },
      { size: 20, cursor: 'p2' },
    ]);
    expect(detail).toHaveBeenCalledTimes(2);
    expect(store.reviews().map((row) => row.orderId)).toEqual(['b']);
    expect(store.exhausted()).toBe(true);
  });
  it('shows batches of20, retains unconsumed hydrated overflow and finishes short batch honestly', async () => {
    const summaries = Array.from({ length: 23 }, (_, i) => order(String(i), false));
    const detail = vi.fn((id: string) => of(order(id)));
    setup({ purchases: vi.fn(() => of({ items: summaries, nextCursor: null })), order: detail });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    expect(store.reviews()).toHaveLength(20);
    expect(store.exhausted()).toBe(false);
    await store.scanMore();
    expect(store.reviews()).toHaveLength(23);
    expect(store.exhausted()).toBe(true);
    expect(detail).toHaveBeenCalledTimes(23);
  });
  it('retries failed hydration without skipping and caches later successful details', async () => {
    let fail = true;
    const detail = vi.fn((id: string) =>
      id === 'b' && fail ? throwError(() => new Error('offline')) : of(order(id)),
    );
    setup({
      purchases: () =>
        of({ items: ['a', 'b', 'c'].map((id) => order(id, false)), nextCursor: null }),
      order: detail,
    });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    expect(store.reviews().map((row) => row.orderId)).toEqual(['a']);
    expect(store.error()).toBe(true);
    expect(store.exhausted()).toBe(false);
    fail = false;
    await store.scanMore();
    expect(store.reviews().map((row) => row.orderId)).toEqual(['a', 'b', 'c']);
    expect(detail.mock.calls.filter((call) => call[0] === 'c')).toHaveLength(1);
    expect(store.exhausted()).toBe(true);
  });
  it('retries a purchase page failure at its unchanged cursor', async () => {
    const purchases = vi
      .fn()
      .mockReturnValueOnce(of({ items: [], nextCursor: 'p1' }))
      .mockReturnValueOnce(throwError(() => new Error('offline')))
      .mockReturnValueOnce(of({ items: [order('a', false)], nextCursor: null }));
    setup({ purchases, order: () => of(order('a')) });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    expect(store.error()).toBe(true);
    await store.scanMore();
    expect(purchases.mock.calls[1][0]).toEqual({ size: 20, cursor: 'p1' });
    expect(purchases.mock.calls[2][0]).toEqual({ size: 20, cursor: 'p1' });
    expect(store.reviews()).toHaveLength(1);
  });
  it('filters by enclosing buyer and dedupes orders and reviews', async () => {
    const detail = vi.fn((id: string) =>
      of(
        id === 'foreign'
          ? order(id, true, 'other')
          : id === 'duplicate'
            ? { ...order(id), review: review('a') }
            : order(id),
      ),
    );
    setup({
      purchases: () =>
        of({
          items: ['a', 'a', 'foreign', 'duplicate'].map((id) => order(id, false)),
          nextCursor: null,
        }),
      order: detail,
    });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    expect(store.reviews().map((row) => row.review.id)).toEqual(['a']);
    expect(store.exhausted()).toBe(true);
    expect(detail.mock.calls.filter((call) => call[0] === 'a')).toHaveLength(1);
  });
  it('caps hydration concurrency at4 and cancels/resumes a paused batch at the same positions', async () => {
    const subjects = new Map<string, Subject<OrderView>>();
    let active = 0,
      max = 0;
    const detail = vi.fn((id: string) => {
      const source = new Subject<OrderView>();
      subjects.set(id, source);
      active++;
      max = Math.max(max, active);
      return source;
    });
    setup({
      purchases: () =>
        of({
          items: Array.from({ length: 8 }, (_, i) => order(String(i), false)),
          nextCursor: null,
        }),
      order: detail,
    });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    expect(detail).toHaveBeenCalledTimes(4);
    expect(max).toBe(4);
    store.pause();
    expect([...subjects.values()].every((source) => !source.observed)).toBe(true);
    active = 0;
    store.activate();
    await settle();
    expect(detail).toHaveBeenCalledTimes(8);
    for (const [id, source] of subjects) {
      source.next(order(id));
      source.complete();
    }
    await settle();
    expect(store.reviews()).toHaveLength(4);
    expect(detail).toHaveBeenCalledTimes(12);
    store.pause();
  });
  it('retains received cursor independently and resets after refresh', async () => {
    const sellerReviews = vi
      .fn()
      .mockReturnValueOnce(of({ items: [review('r')], nextCursor: 'r1' }))
      .mockReturnValueOnce(of({ items: [], nextCursor: null }))
      .mockReturnValue(of({ items: [], nextCursor: null }));
    const purchases = vi.fn(() => of({ items: [], nextCursor: null }));
    setup({ sellerReviews, purchases });
    const store = TestBed.inject(MeReviewsStore);
    store.activate();
    await settle();
    store.select('authored');
    await settle();
    store.select('received');
    await store.loadReceived(true);
    expect(sellerReviews.mock.calls[1]).toEqual(['buyer', { size: 20, cursor: 'r1' }]);
    expect(purchases).toHaveBeenCalledTimes(1);
  });
  it('clears reviews and cancels reads on session cleanup', async () => {
    const source = new Subject<OrderView>();
    const clear = setup({
      purchases: () => of({ items: [order('a', false)], nextCursor: null }),
      order: () => source,
    });
    const store = TestBed.inject(MeReviewsStore);
    store.mode.set('authored');
    store.activate();
    await settle();
    clear();
    source.next(order('a'));
    await settle();
    expect(store.reviews()).toEqual([]);
    expect(source.observed).toBe(false);
  });
  it('pages licences independently, retains loaded state on section switch and clears returned keys on logout', async () => {
    const licenses = vi
      .fn()
      .mockReturnValueOnce(of({ items: [license('a')], nextCursor: 'l1' }))
      .mockReturnValueOnce(
        of({ items: [license('a'), license('b', 'REVOKED')], nextCursor: null }),
      );
    const clear = setup({ licenses });
    const store = TestBed.inject(MeLibraryStore);
    store.activate();
    await settle();
    store.pause();
    store.activate();
    await settle();
    expect(licenses).toHaveBeenCalledTimes(1);
    await store.load(true);
    expect(licenses.mock.calls[1][0]).toEqual({ size: 20, cursor: 'l1' });
    expect(store.items()).toHaveLength(2);
    clear();
    expect(store.items()).toEqual([]);
  });
  it('clears licence rows, cursor and revealed keys when the library tab is destroyed and reloads on remount', async () => {
    const licenses = vi.fn(() => of({ items: [license('a')], nextCursor: 'l1' }));
    setup({ licenses });
    const store = TestBed.inject(MeLibraryStore);
    const fixture = TestBed.createComponent(MeLibraryTab);
    await settle();
    fixture.componentInstance.toggleKey('a');
    expect(store.items()).toHaveLength(1);
    fixture.destroy();
    expect(store.items()).toEqual([]);
    expect(store.cursor()).toBeNull();
    expect(store.loaded()).toBe(false);
    expect(store.versions()).toEqual({});
    expect(fixture.componentInstance.revealed().size).toBe(0);
    TestBed.createComponent(MeLibraryTab);
    await settle();
    expect(licenses).toHaveBeenCalledTimes(2);
    expect(licenses).toHaveBeenLastCalledWith({ size: 20 });
  });
  it('shows a licence empty state with download guidance', async () => {
    setup({ licenses: () => of({ items: [], nextCursor: null }) });
    const fixture = TestBed.createComponent(MeLibraryTab);
    await settle();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('app-empty-state h2')?.textContent).toBe('No licences yet');
    expect(root.querySelector('app-empty-state p')?.textContent).toBe(
      'Software you buy will appear here with its licence key and downloads.',
    );
  });
  it('keeps licence keys out of the DOM until explicitly revealed and never synthesizes omitted keys', async () => {
    setup({
      licenses: () =>
        of({
          items: [license('a'), { ...license('b'), key: undefined }, license('c', 'REVOKED')],
          nextCursor: null,
        }),
    });
    const fixture = TestBed.createComponent(MeLibraryTab);
    await settle();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).not.toContain('returned-key');
    expect(root.querySelectorAll('app-copy-field')).toHaveLength(0);
    expect(
      [...root.querySelectorAll('button')].filter(
        (button) => button.getAttribute('aria-label') === 'Show key',
      ),
    ).toHaveLength(2);
    fixture.componentInstance.toggleKey('a');
    fixture.detectChanges();
    expect(root.querySelectorAll('app-copy-field')).toHaveLength(1);
    expect(root.textContent).toContain('returned-key');
    fixture.componentInstance.toggleKey('a');
    fixture.detectChanges();
    expect(root.textContent).not.toContain('returned-key');
  });
  it('refreshes loaded licence pages without losing version choices and keeps old rows on errors', async () => {
    const licenses = vi
      .fn()
      .mockReturnValueOnce(of({ items: [license('a')], nextCursor: 'old' }))
      .mockReturnValueOnce(of({ items: [license('b')], nextCursor: null }))
      .mockReturnValueOnce(of({ items: [license('new'), license('a')], nextCursor: 'fresh' }))
      .mockReturnValueOnce(of({ items: [license('b', 'REVOKED')], nextCursor: null }))
      .mockReturnValue(throwError(() => new Error('offline')));
    setup({ licenses });
    const store = TestBed.inject(MeLibraryStore);
    store.activate();
    await settle();
    await store.load(true);
    const versions = {
      listing: [{ number: '2', release: '', current: true, artifactAvailable: true, notes: null }],
    };
    store.versions.set(versions);
    expect(store.autoReload()).toBe(true);
    expect(store.items().map((row) => row.orderId)).toEqual(['new', 'a', 'b']);
    expect(store.items()[2].status).toBe('REVOKED');
    expect(store.versions()).toBe(versions);
    expect(licenses.mock.calls[3][0]).toEqual({ size: 20, cursor: 'fresh' });
    store.autoReload();
    expect(store.items()).toHaveLength(3);
    expect(store.error()).toBe(false);
    expect(store.refreshing()).toBe(false);
  });
  it('defers licence refresh during downloads and version reads, and cancels on pause', async () => {
    const source = new Subject<{ items: LicenseView[]; nextCursor: string | null }>();
    const licenses = vi
      .fn()
      .mockReturnValueOnce(of({ items: [license('a')], nextCursor: null }))
      .mockReturnValue(source);
    setup({ licenses });
    const store = TestBed.inject(MeLibraryStore);
    store.activate();
    await settle();
    store.downloading.set('a:2');
    expect(store.autoReload()).toBe(false);
    store.downloading.set(null);
    store.versionLoading.set({ listing: true });
    expect(store.autoReload()).toBe(false);
    store.versionLoading.set({});
    expect(store.autoReload()).toBe(true);
    expect(store.autoReload()).toBe(false);
    store.pause();
    expect(source.observed).toBe(false);
    expect(store.refreshing()).toBe(false);
    expect(store.autoReload()).toBe(false);
  });
  it('received reviews use honest author fallback and do not fabricate order links', async () => {
    setup({ sellerReviews: () => of({ items: [review('received')], nextCursor: null }) });
    const fixture = TestBed.createComponent(MeReviewsTab);
    await settle();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Member');
    expect(root.querySelector('a[href="/listings/listing-received"]')).not.toBeNull();
    expect(root.querySelector('a[href^="/orders/"]')).toBeNull();
  });
  it('uses explicit version choices and requests fresh authorized URLs per click', async () => {
    const artifactDownload = vi.fn(() =>
      of({
        url: 'https://bucket.s3.eu-south-1.amazonaws.com/artifact',
        expiresAt: new Date(Date.now() + 60000).toISOString(),
      }),
    );
    const versions = vi.fn(() =>
      of([
        {
          number: '2.0',
          release: '2026-01-01',
          current: true,
          artifactAvailable: true,
          notes: null,
        },
        {
          number: '1.0',
          release: '2025-01-01',
          current: false,
          artifactAvailable: false,
          notes: null,
        },
      ]),
    );
    setup(
      { licenses: () => of({ items: [license('a')], nextCursor: null }) },
      { artifactDownload, versions },
    );
    const store = TestBed.inject(MeLibraryStore);
    const navigate = vi.spyOn(store, 'navigateDownload').mockImplementation(() => {});
    store.activate();
    await settle();
    await store.loadVersions(license('a'));
    await store.download(license('a'), '2.0');
    await store.download(license('a'), '2.0');
    await store.download(license('a'), '1.0');
    await store.download(license('a', 'REVOKED'), '2.0');
    expect(artifactDownload).toHaveBeenCalledTimes(2);
    expect(artifactDownload).toHaveBeenCalledWith('listing', '2.0');
    expect(navigate).toHaveBeenCalledTimes(2);
  });
  it('reports download errors and rejects unsafe storage responses', async () => {
    const artifactDownload = vi
      .fn()
      .mockReturnValueOnce(
        throwError(() => ({
          code: 'licence-required',
          status: 403,
          type: '',
          extras: {},
          fieldErrors: {},
        })),
      )
      .mockReturnValueOnce(
        of({
          url: 'https://evil.example/artifact',
          expiresAt: new Date(Date.now() + 60000).toISOString(),
        }),
      );
    setup({ licenses: () => of({ items: [], nextCursor: null }) }, { artifactDownload });
    const store = TestBed.inject(MeLibraryStore);
    store.activate();
    await settle();
    store.versions.set({
      listing: [{ number: '2', release: '', current: true, artifactAvailable: true, notes: null }],
    });
    const navigate = vi.spyOn(store, 'navigateDownload').mockImplementation(() => {});
    await store.download(license('a'), '2');
    expect(store.message()).toContain('not active');
    await store.download(license('a'), '2');
    expect(store.message()).toContain('Could not download');
    expect(navigate).not.toHaveBeenCalled();
  });
  it.each([
    'http://bucket.s3.amazonaws.com/a',
    'https://bucket.s3.amazonaws.com.evil.test/a',
    'https://evil.amazonaws.com/a',
    'https://u:p@bucket.s3.amazonaws.com/a',
    'javascript:alert(1)',
    'https://bucket.s3.amazonaws.com/a#x',
  ])('rejects unsafe artifact URL %s', (url) =>
    expect(validArtifactUrl(url, new Date(Date.now() + 60000).toISOString())).toBe(false),
  );
  it('rejects expired download URLs', () =>
    expect(validArtifactUrl('https://bucket.s3.amazonaws.com/a', '2020-01-01T00:00:00Z')).toBe(
      false,
    ));
});
