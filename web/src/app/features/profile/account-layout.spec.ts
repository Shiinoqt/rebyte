import { BreakpointObserver } from '@angular/cdk/layout';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import { CatalogApi } from '../../core/api/catalog-api';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { FAVOURITES_ROUTES } from '../favourites/favourites.routes';
import { OFFERS_ROUTES } from '../offers/offers.routes';
import { ORDERS_ROUTES } from '../orders/orders.routes';

const SUB = 'member-sub';

describe('standalone account pages', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function open(url: string, narrow = false) {
    const viewport = new BehaviorSubject({ matches: narrow, breakpoints: {} });
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideRouter(
          [
            { path: 'favourites', loadChildren: () => FAVOURITES_ROUTES },
            { path: 'orders', loadChildren: () => ORDERS_ROUTES },
            { path: 'offers', loadChildren: () => OFFERS_ROUTES },
          ],
          withComponentInputBinding(),
        ),
        {
          provide: AuthAdapter,
          useValue: {
            configure: vi.fn(),
            fetchSession: vi.fn(() =>
              Promise.resolve({ sub: SUB, email: 'alice@example.test', groups: [] }),
            ),
            signOut: vi.fn(() => Promise.resolve()),
            onAuthEvent: () => () => undefined,
          },
        },
        {
          provide: UserApi,
          useValue: {
            getMe: vi.fn(() => of(null)),
            getPublicProfile: vi.fn(() =>
              of({
                sub: SUB,
                username: 'alice',
                avatar: null,
                registration: null,
                location: null,
                bio: null,
                isDeveloper: false,
                ratingAvg: null,
                ratingCount: 0,
                completedSales: 0,
                activeListings: [],
                followerCount: 0,
                followingCount: 0,
                followedByMe: false,
              }),
            ),
          },
        },
        {
          provide: CatalogApi,
          useValue: {
            favourites: vi.fn(() =>
              of({ favourites: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }),
            ),
          },
        },
        { provide: BreakpointObserver, useValue: { observe: () => viewport } },
      ],
    });
    await TestBed.inject(AuthStore).init();
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return harness.routeNativeElement as HTMLElement;
  }

  it.each([
    ['/favourites', 'Favourites', 'Favourites'],
    ['/orders', 'Your orders', 'Orders'],
    ['/offers', 'Your offers', 'Offers'],
  ])('shows %s beside the sidebar with its row marked', async (url, title, row) => {
    const root = await open(url);
    expect(root.querySelector('app-account-nav.sidebar')).not.toBeNull();
    expect(
      root.querySelector('a.row[aria-current="page"] .row-label')?.textContent?.trim(),
    ).toBe(row);
    expect(root.querySelector('.account-main h1')?.textContent?.trim()).toBe(title);
    expect(root.querySelector('a.back')).toBeNull();
  });

  it('swaps the sidebar for a back link to the account list on narrow screens', async () => {
    const root = await open('/orders', true);
    expect(root.querySelector('app-account-nav')).toBeNull();
    expect(root.querySelector('a.back')?.getAttribute('href')).toBe('/me');
    expect(root.querySelector('.account-main h1')?.textContent?.trim()).toBe('Your orders');
  });
});
