import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import { SearchHit, SearchResultPage } from '../../core/api/api-models';
import { AuthAdapter } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { authInterceptor } from '../../core/http/auth.interceptor';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { HOME_SEARCH_ROUTES } from './home-search.routes';
import { SearchStateService } from './search-state.service';

const API = 'http://api.test';

function hit(id: string, overrides: Partial<SearchHit> = {}): SearchHit {
  return {
    id,
    title: `Item ${id}`,
    description: '',
    price: '120.00',
    listingType: 'HARDWARE',
    status: 'ACTIVE',
    categoryId: 'gpus',
    categoryPath: ['Components', 'GPUs'],
    sellerUsername: 'alice',
    publishedAt: '2026-01-01T00:00:00Z',
    condition: 'GOOD',
    shippingLocation: 'Roma',
    platforms: [],
    currentVersion: null,
    coverUrl: null,
    attr: {},
    ...overrides,
  };
}

function page(
  ids: string[],
  total: number,
  offset = 0,
  overrides: Partial<SearchResultPage> = {},
): SearchResultPage {
  return { hits: ids.map((id) => hit(id)), total, offset, size: 24, facets: [], ...overrides };
}

function range(from: number, count: number): string[] {
  return Array.from({ length: count }, (_, index) => `h${from + index}`);
}

describe('home and search (S2)', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  const adapter = { accessToken: vi.fn(async () => null) };
  const auth = {
    clearSession: vi.fn(),
    isAuthenticated: signal(false),
    identity: signal(null),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    auth.isAuthenticated.set(false);
    auth.identity.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '', children: HOME_SEARCH_ROUTES }]),
        provideHttpClient(withInterceptors([problemInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        provideTestAppConfig(),
        { provide: AuthAdapter, useValue: adapter },
        { provide: AuthStore, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  async function open(url: string) {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
  }

  async function settle() {
    await vi.waitFor(() => harness.fixture.whenStable());
    harness.detectChanges();
  }

  function searches() {
    return http.match((request) => request.url === `${API}/search`);
  }

  function flushCategories(tree: unknown[] = []) {
    http.expectOne(`${API}/categories`).flush(tree);
  }

  async function respond(body: SearchResultPage) {
    const request = await vi.waitFor(() =>
      http.expectOne((candidate) => candidate.url === `${API}/search`),
    );
    request.flush(body);
    await settle();
    return request.request;
  }

  function text() {
    return (harness.routeNativeElement as HTMLElement).textContent ?? '';
  }

  function cards() {
    return (harness.routeNativeElement as HTMLElement).querySelectorAll('[data-ui="listing-card"]');
  }

  describe('URL-owned query', () => {
    it('requests the first 24 results for a shared URL without any Authorization header', async () => {
      await open('/?q=gpu&cat=c&type=HARDWARE');
      flushCategories();
      const request = await respond(page(['a', 'b'], 2));
      expect(request.params.get('q')).toBe('gpu');
      expect(request.params.get('categoryId')).toBe('c');
      expect(request.params.get('listingType')).toBe('HARDWARE');
      expect(request.params.get('offset')).toBe('0');
      expect(request.params.get('size')).toBe('24');
      expect(request.headers.has('Authorization')).toBe(false);
      expect(cards()).toHaveLength(2);
    });

    it('sends only the allowlisted parameters and drops malformed URL values', async () => {
      await open(
        '/?q=gpu&type=BOGUS&min=abc&max=1e9&seller=bob&sort=price&offset=999&size=500&condition=GOOD',
      );
      flushCategories();
      const request = await respond(page([], 0));
      expect(request.params.get('q')).toBe('gpu');
      for (const key of ['listingType', 'minPrice', 'maxPrice', 'seller', 'sort', 'condition']) {
        expect(request.params.has(key)).toBe(false);
      }
      expect(request.params.get('offset')).toBe('0');
      expect(request.params.get('size')).toBe('24');
    });

    it('treats slider endpoint bounds in a shared URL as no active price filter', async () => {
      await open('/?min=0&max=2000');
      flushCategories();
      const request = await respond(page([], 0));
      const root = harness.routeNativeElement as HTMLElement;
      const service = harness.routeDebugElement!.injector.get(SearchStateService);

      expect(request.params.has('minPrice')).toBe(false);
      expect(request.params.has('maxPrice')).toBe(false);
      expect(service.filters()).toBe(0);
      expect(root.querySelector('.hero')).toBeNull();
      root.querySelector<HTMLButtonElement>('app-price-control > button')?.click();
      harness.detectChanges();
      expect(
        root.querySelector<HTMLInputElement>('input[aria-label="Minimum price in euros"]')?.value,
      ).toBe('');
      expect(
        root.querySelector<HTMLInputElement>('input[aria-label="Maximum price in euros"]')?.value,
      ).toBe('');
      expect(root.querySelector('button.clear')).toBeNull();
      expect(TestBed.inject(Router).url).toBe('/');
    });

    it('merges consecutive filter updates before router navigation settles', async () => {
      await open('/');
      flushCategories();
      await respond(page(['a'], 1));
      const router = TestBed.inject(Router);
      const service = harness.routeDebugElement!.injector.get(SearchStateService);

      service.update({ cat: 'gpus' });
      service.update({ type: 'SOFTWARE' });
      await settle();

      expect(router.url).toBe('/?cat=gpus&type=SOFTWARE');
      const request = await respond(page(['b'], 1));
      expect(request.params.get('categoryId')).toBe('gpus');
      expect(request.params.get('listingType')).toBe('SOFTWARE');
    });

    it('uses restored URL state as the base for later filter edits', async () => {
      await open('/');
      flushCategories();
      await respond(page(['a'], 1));
      const router = TestBed.inject(Router);
      const service = harness.routeDebugElement!.injector.get(SearchStateService);

      service.update({ q: 'gpu' }, { history: true });
      await settle();
      await respond(page(['a'], 1));
      service.update({ q: 'ssd' }, { history: true });
      await settle();
      await respond(page(['b'], 1));

      await harness.navigateByUrl('/?q=gpu');
      await settle();
      expect(router.url).toBe('/?q=gpu');
      expect((await respond(page(['a'], 1))).params.get('q')).toBe('gpu');

      service.update({ type: 'SOFTWARE' });
      await settle();
      expect(router.url).toBe('/?q=gpu&type=SOFTWARE');
      const request = await respond(page(['c'], 1));
      expect(request.params.get('q')).toBe('gpu');
      expect(request.params.get('listingType')).toBe('SOFTWARE');
    });

    it('round-trips a typed query through the URL and keeps text submissions in history', async () => {
      await open('/');
      flushCategories();
      await respond(page(['a'], 1));
      const router = TestBed.inject(Router);
      const navigate = vi.spyOn(router, 'navigate');
      const service = harness.routeDebugElement!.injector.get(SearchStateService);

      service.update({ q: 'ssd' }, { history: true });
      await settle();
      expect(router.url).toBe('/?q=ssd');
      expect(navigate.mock.calls.at(-1)?.[1]?.replaceUrl).toBe(false);
      expect(service.state().q).toBe('ssd');
      expect((await respond(page(['b'], 1))).params.get('q')).toBe('ssd');

      service.update({ type: 'SOFTWARE' });
      await settle();
      expect(router.url).toBe('/?q=ssd&type=SOFTWARE');
      expect(navigate.mock.calls.at(-1)?.[1]?.replaceUrl).toBe(true);
      expect((await respond(page(['c'], 1))).params.get('listingType')).toBe('SOFTWARE');
    });

    it('ignores URL changes that leave the search request unchanged', async () => {
      await open('/?q=gpu');
      flushCategories();
      await respond(page(['a'], 1));
      await harness.navigateByUrl('/?q=gpu&utm=1');
      await settle();
      expect(searches()).toHaveLength(0);
    });
  });

  describe('Show more', () => {
    it('appends the next 24 results without duplicates and announces the count', async () => {
      await open('/');
      flushCategories();
      await respond(page(range(0, 24), 50));
      expect(cards()).toHaveLength(24);
      const button = () =>
        (harness.routeNativeElement as HTMLElement).querySelector<HTMLButtonElement>(
          '[data-ui="load-more"] button',
        );
      expect(button()?.textContent?.trim()).toBe('Show more');

      button()!.click();
      const more = await respond(page(['h23', ...range(24, 23)], 50, 24));
      expect(more.params.get('offset')).toBe('24');
      expect(more.params.get('size')).toBe('24');
      expect(cards()).toHaveLength(47);
      expect(cards()[0].textContent).toContain('Item h0');
      expect(
        (harness.routeNativeElement as HTMLElement).querySelector('.count-announcement')
          ?.textContent,
      ).toContain('47 listings');
    });

    it('hides Show more once every result is loaded', async () => {
      await open('/');
      flushCategories();
      await respond(page(range(0, 24), 30));
      (harness.routeNativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('[data-ui="load-more"] button')!
        .click();
      await respond(page(range(24, 6), 30, 24));
      expect(
        (harness.routeNativeElement as HTMLElement).querySelector('[data-ui="load-more"] button'),
      ).toBeNull();
      expect(cards()).toHaveLength(30);
    });

    it('starts again at offset 0 whenever a filter changes', async () => {
      await open('/');
      flushCategories();
      await respond(page(range(0, 24), 50));
      (harness.routeNativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('[data-ui="load-more"] button')!
        .click();
      await respond(page(range(24, 24), 50, 24));
      expect(cards()).toHaveLength(48);

      await harness.navigateByUrl('/?type=SOFTWARE');
      const reset = await respond(page(['s1'], 1));
      expect(reset.params.get('offset')).toBe('0');
      expect(cards()).toHaveLength(1);
    });
  });

  describe('states', () => {
    it('shows eight skeletons without a browse hero while the first page loads', async () => {
      await open('/');
      flushCategories();
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelectorAll('[data-ui="skeleton"]')).toHaveLength(8);
      expect(root.querySelector('.hero')).toBeNull();
      expect(root.querySelectorAll('h1')).toHaveLength(1);
      expect(root.querySelector('h1')?.textContent).toBe('All listings');
      await respond(page(['a'], 1));
      expect(root.querySelectorAll('[data-ui="skeleton"]')).toHaveLength(0);
    });

    it('keeps the browse hero out and titles the page for an active query', async () => {
      await open('/?q=gpu');
      flushCategories();
      await respond(page(['a', 'b'], 2));
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('.hero')).toBeNull();
      expect(root.querySelector('.results-header')).toBeNull();
      const heading = root.querySelector('h1');
      expect(heading?.textContent).toBe('Results for "gpu"');
      expect(heading?.classList.contains('visually-hidden')).toBe(true);
      const status = root.querySelector('[role="status"][aria-live="polite"]');
      expect(status?.classList.contains('visually-hidden')).toBe(true);
      expect(status?.getAttribute('aria-atomic')).toBe('true');
      expect(status?.textContent).toContain('2 listings');
      expect(text()).toContain('2 listings');
    });

    it('names the page after the selected category and uses the singular count', async () => {
      await open('/?cat=components');
      flushCategories([
        {
          id: 'components',
          name: 'Components',
          leaf: false,
          children: [{ id: 'gpus', name: 'Graphics cards', leaf: true, children: [] }],
        },
      ]);
      await respond(page(['a'], 1));
      expect((harness.routeNativeElement as HTMLElement).querySelector('h1')?.textContent).toBe(
        'Components',
      );
      expect(text()).toContain('1 listing');
      expect(text()).not.toContain('1 listings');
    });

    it('shows an error state and retries the same request', async () => {
      await open('/?q=gpu');
      flushCategories();
      const failing = await vi.waitFor(() =>
        http.expectOne((candidate) => candidate.url === `${API}/search`),
      );
      failing.flush({ title: 'search-unavailable', status: 503 }, { status: 503, statusText: 'x' });
      await settle();
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('[data-ui="error-state"]')).not.toBeNull();
      root.querySelector<HTMLButtonElement>('[data-ui="error-state"] button')!.click();
      const retry = await respond(page(['a'], 1));
      expect(retry.params.get('q')).toBe('gpu');
      expect(root.querySelector('[data-ui="error-state"]')).toBeNull();
      expect(cards()).toHaveLength(1);
    });

    it('shows "Nothing matches" with Clear filters when a filtered search is empty', async () => {
      await open('/?q=zzz');
      flushCategories();
      await respond(page([], 0));
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('[data-ui="empty-state"] h2')?.textContent).toBe('Nothing matches');
      const clear = root.querySelector<HTMLButtonElement>('[data-ui="empty-state"] button')!;
      expect(clear.textContent?.trim()).toBe('Clear filters');
      clear.click();
      const cleared = await respond(page(['a'], 1));
      expect(cleared.params.has('q')).toBe(false);
      expect(TestBed.inject(Router).url).toBe('/');
    });

    it('shows "No listings yet" with a Sell an item link on an empty marketplace', async () => {
      await open('/');
      flushCategories();
      await respond(page([], 0));
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('[data-ui="empty-state"] h2')?.textContent).toBe('No listings yet');
      expect(root.querySelector('[data-ui="empty-state"]')?.textContent).toContain(
        'Be the first to sell something.',
      );
      expect(root.querySelector('[data-ui="empty-state"] button')?.textContent?.trim()).toBe(
        'Sell an item',
      );
    });

    it('renders a partial hit with a placeholder cover and no seller', async () => {
      await open('/');
      flushCategories();
      await respond({
        hits: [hit('p', { coverUrl: null, sellerUsername: null, shippingLocation: null })],
        total: 1,
        offset: 0,
        size: 24,
        facets: [],
      });
      const card = cards()[0];
      expect(card.textContent).toContain('Item p');
      expect(card.querySelector('.placeholder')).not.toBeNull();
      expect(card.querySelector('img')).toBeNull();
    });

    it('uses coverUrl for the card image and links to the listing', async () => {
      await open('/');
      flushCategories();
      await respond({
        hits: [hit('c', { coverUrl: 'https://cdn.test/c.jpg' })],
        total: 1,
        offset: 0,
        size: 24,
        facets: [],
      });
      expect(cards()[0].querySelector('img')?.getAttribute('src')).toBe('https://cdn.test/c.jpg');
      expect(cards()[0].querySelector('a')?.getAttribute('href')).toBe('/listings/c');
    });
  });

  describe('filters', () => {
    const tree = [
      {
        id: 'hardware-root',
        name: 'Hardware',
        leaf: false,
        children: [
          {
            id: 'components',
            name: 'Components',
            leaf: false,
            children: [
              { id: 'gpus', name: 'Graphics cards', leaf: true, children: [] },
              { id: 'cpus', name: 'Processors', leaf: true, children: [] },
            ],
          },
          { id: 'monitors', name: 'Monitors', leaf: true, children: [] },
        ],
      },
      {
        id: 'software-root',
        name: 'Software',
        leaf: false,
        children: [
          { id: 'development-tools', name: 'Developer tools', leaf: true, children: [] },
          { id: 'games', name: 'Games', leaf: true, children: [] },
        ],
      },
    ];
    const schema = {
      categoryId: 'gpus',
      attributes: [
        {
          id: 'a1',
          name: 'vram_gb',
          type: 'INTEGER',
          required: true,
          allowedValues: [],
          unit: 'GB',
          definedAt: '2026-01-01T00:00:00Z',
        },
        {
          id: 'a2',
          name: 'interface',
          type: 'ENUM',
          required: false,
          allowedValues: ['PCIe', 'NVLink'],
          unit: null,
          definedAt: '2026-01-01T00:00:00Z',
        },
        {
          id: 'a3',
          name: 'ecc',
          type: 'BOOLEAN',
          required: false,
          allowedValues: [],
          unit: null,
          definedAt: '2026-01-01T00:00:00Z',
        },
        {
          id: 'a4',
          name: 'cuda_cores',
          type: 'INTEGER',
          required: false,
          allowedValues: [],
          unit: null,
          definedAt: '2026-01-01T00:00:00Z',
        },
      ],
    };
    const facets = [{ attribute: 'interface', values: { PCIe: 7, NVLink: 2 } }];
    const original = window.matchMedia;

    afterEach(() => {
      window.matchMedia = original;
    });

    function phone(matches: boolean, compact = matches) {
      const listeners = new Map<string, (event: MediaQueryListEvent) => void>();
      window.matchMedia = vi.fn((query: string) => ({
        matches: query.includes('560') ? compact : matches && query.includes('640'),
        media: query,
        addEventListener: vi.fn((_, listener) => listeners.set(query, listener)),
        removeEventListener: vi.fn((_, listener) => {
          if (listeners.get(query) === listener) listeners.delete(query);
        }),
      })) as unknown as typeof window.matchMedia;
      return (phoneMatches: boolean, compactMatches: boolean) => {
        for (const [query, listener] of listeners) {
          listener({
            matches: query.includes('560') ? compactMatches : phoneMatches,
          } as MediaQueryListEvent);
        }
      };
    }

    async function waitForOne(url: string) {
      return vi.waitFor(() => http.expectOne(url));
    }

    // Opens a URL and answers the categories, the schema (leaf categories only) and the first page.
    async function openWithFilters(url: string, first = page(['a'], 1, 0, { facets })) {
      await open(url);
      flushCategories(tree);
      await settle();
      const cat = new URL(url, 'http://x').searchParams.get('cat');
      if (cat === 'gpus') {
        (await waitForOne(`${API}/categories/gpus/schema`)).flush(schema);
        await settle();
      }
      return respond(first);
    }

    function root() {
      return harness.routeNativeElement as HTMLElement;
    }

    function thumb(which: 'Minimum' | 'Maximum') {
      return root().querySelector<HTMLElement>(`[role="slider"][aria-label="${which} price"]`)!;
    }

    function press(element: HTMLElement, key: string) {
      element.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      element.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true }));
    }

    function type(input: HTMLInputElement, value: string) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('blur'));
      harness.detectChanges();
    }

    function field(label: string) {
      return root().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;
    }

    function openPricePopover() {
      root().querySelector<HTMLButtonElement>('app-price-control > button')!.click();
      harness.detectChanges();
    }

    function url() {
      return decodeURIComponent(TestBed.inject(Router).url);
    }

    describe('sort', () => {
      function sortTrigger() {
        return root().querySelector<HTMLButtonElement>('app-menu-select > button')!;
      }

      function choose(label: string) {
        sortTrigger().click();
        harness.detectChanges();
        Array.from(root().querySelectorAll<HTMLButtonElement>('app-menu-select [data-row]'))
          .find((row) => row.textContent?.trim() === label)!
          .click();
        harness.detectChanges();
      }

      it('sends the sort from a shared URL on the first request', async () => {
        const request = await openWithFilters('/?sort=newest');
        expect(request.params.get('sort')).toBe('newest');
        expect(sortTrigger().textContent).toContain('Most recent');
      });

      it('opens a menu that marks the current sort and closes on Escape', async () => {
        await openWithFilters('/?sort=oldest');
        const trigger = sortTrigger();
        trigger.click();
        harness.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('true');
        const current = root().querySelector<HTMLButtonElement>('app-menu-select [aria-current="true"]')!;
        expect(current.textContent?.trim()).toBe('Oldest');
        current.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        harness.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        expect(root().querySelector('app-menu-select [data-row]')).toBeNull();
      });

      it('writes the chosen sort to the URL, sends it from offset 0, and Relevance removes it', async () => {
        await openWithFilters('/?q=gpu');
        expect(sortTrigger().textContent).toContain('Relevance');

        choose('Oldest');
        const oldest = await respond(page(['a'], 1));
        expect(url()).toBe('/?q=gpu&sort=oldest');
        expect(oldest.params.get('sort')).toBe('oldest');
        expect(oldest.params.get('offset')).toBe('0');

        choose('Relevance');
        const relevance = await respond(page(['a'], 1));
        expect(url()).toBe('/?q=gpu');
        expect(relevance.params.has('sort')).toBe(false);
      });

      it('keeps the sort when Clear removes the filters', async () => {
        await openWithFilters('/?type=HARDWARE&sort=newest');
        const clear = Array.from(root().querySelectorAll<HTMLButtonElement>('button')).find(
          (button) => button.textContent?.trim() === 'Clear',
        )!;
        clear.click();
        const cleared = await respond(page(['a'], 1));
        expect(url()).toBe('/?sort=newest');
        expect(cleared.params.get('sort')).toBe('newest');
        expect(cleared.params.has('listingType')).toBe(false);
      });
    });

    describe('price control', () => {
      it('shows an active range on the compact trigger and opens the existing slider control', async () => {
        await openWithFilters('/?min=100&max=300');
        const trigger = root().querySelector<HTMLButtonElement>('app-price-control > button')!;
        expect(trigger.textContent).toContain('€100–€300');
        openPricePopover();
        await harness.fixture.whenStable();
        const panel = root().querySelector<HTMLElement>('.price-panel')!;
        expect(document.activeElement).toBe(panel);
        expect(panel.querySelector('.range-readout')?.textContent?.trim()).toBe('€100 – €300');
        expect(panel.querySelector('.track')!.nextElementSibling?.classList.contains('price')).toBe(true);
        const price = root().querySelector('.price')!;
        const order = Array.from(price.children).map((child) => {
          if (child.classList.contains('price-label')) return 'label';
          if (child.classList.contains('track')) return 'slider';
          if (child.classList.contains('amount')) {
            return child.querySelector('input')?.getAttribute('aria-label') ===
              'Minimum price in euros'
              ? 'minimum'
              : 'maximum';
          }
          return child.tagName.toLowerCase();
        });

        expect(order).toEqual(['minimum', 'maximum']);
        expect(price.querySelectorAll('label.amount')).toHaveLength(2);
      });

      it('applies a quick range in one step and marks the matching preset', async () => {
        await openWithFilters('/');
        openPricePopover();
        await harness.fixture.whenStable();
        const preset = (label: string) =>
          Array.from(root().querySelectorAll<HTMLButtonElement>('.preset')).find(
            (button) => button.textContent?.trim() === label,
          )!;
        preset('€50–200').click();
        await respond(page(['a'], 1));
        harness.detectChanges();
        expect(url()).toContain('min=50');
        expect(url()).toContain('max=200');
        expect(preset('€50–200').getAttribute('aria-pressed')).toBe('true');
        expect(preset('€500+').getAttribute('aria-pressed')).toBe('false');
      });

      it('dismisses on Escape and outside click, returning focus after Escape', async () => {
        await openWithFilters('/?min=100');
        const trigger = root().querySelector<HTMLButtonElement>('app-price-control > button')!;
        openPricePopover();
        field('Minimum price in euros').dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
        );
        harness.detectChanges();
        expect(root().querySelector('app-price-control .price-panel')).toBeNull();
        expect(document.activeElement).toBe(trigger);

        openPricePopover();
        document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        harness.detectChanges();
        expect(root().querySelector('app-price-control .price-panel')).toBeNull();
      });

      it('clears only the price bounds from the popover', async () => {
        await openWithFilters('/?q=gpu&min=100&max=300&type=HARDWARE');
        openPricePopover();
        root().querySelector<HTMLButtonElement>('.clear-price')!.click();
        const request = await respond(page(['a'], 1));
        expect(url()).toBe('/?q=gpu&type=HARDWARE');
        expect(request.params.has('minPrice')).toBe(false);
        expect(request.params.has('maxPrice')).toBe(false);
        expect(root().querySelector<HTMLButtonElement>('app-price-control > button')?.textContent)
          .toContain('Price');
      });

      it('renders accessible slider thumbs over 0 to 2000 and keeps Any price at the top end', async () => {
        await openWithFilters('/?min=100&max=300');
        openPricePopover();
        expect(thumb('Minimum').getAttribute('aria-valuemin')).toBe('0');
        expect(thumb('Minimum').getAttribute('aria-valuemax')).toBe('2000');
        expect(thumb('Minimum').getAttribute('aria-valuenow')).toBe('100');
        expect(thumb('Minimum').getAttribute('aria-valuetext')).toBe('€100');
        expect(thumb('Maximum').getAttribute('aria-valuenow')).toBe('300');
        press(thumb('Maximum'), 'End');
        const request = await respond(page(['a'], 1));
        expect(thumb('Maximum').getAttribute('aria-valuenow')).toBe('2000');
        expect(thumb('Maximum').getAttribute('aria-valuetext')).toBe('Any price');
        expect(url()).toBe('/?min=100');
        expect(request.params.has('maxPrice')).toBe(false);
        expect(request.params.get('minPrice')).toBe('100');
        expect(field('Maximum price in euros').placeholder).toBe('Any');
      });

      it('moves one step per arrow key and ten steps per page key', async () => {
        await openWithFilters('/?min=100&max=500');
        openPricePopover();
        press(thumb('Minimum'), 'ArrowRight');
        await respond(page(['a'], 1));
        expect(url()).toBe('/?min=110&max=500');
        press(thumb('Minimum'), 'PageUp');
        await respond(page(['a'], 1));
        expect(url()).toBe('/?min=210&max=500');
        press(thumb('Minimum'), 'ArrowLeft');
        await respond(page(['a'], 1));
        expect(url()).toBe('/?min=200&max=500');
        press(thumb('Minimum'), 'Home');
        await respond(page(['a'], 1));
        expect(url()).toBe('/?max=500');
      });

      it('never lets the thumbs cross', async () => {
        await openWithFilters('/?min=100&max=200');
        openPricePopover();
        press(thumb('Minimum'), 'End');
        const request = await respond(page(['a'], 1));
        expect(url()).toBe('/?min=200&max=200');
        expect(request.params.get('minPrice')).toBe('200');
        press(thumb('Minimum'), 'ArrowRight');
        expect(searches()).toHaveLength(0);
        expect(thumb('Minimum').getAttribute('aria-valuenow')).toBe('200');
        press(thumb('Maximum'), 'Home');
        expect(searches()).toHaveLength(0);
        expect(thumb('Maximum').getAttribute('aria-valuenow')).toBe('200');
      });

      it('keeps the text inputs and thumbs synchronised, with the input winning', async () => {
        await openWithFilters('/');
        openPricePopover();
        type(field('Minimum price in euros'), '125');
        await respond(page(['a'], 1));
        expect(url()).toBe('/?min=125');
        expect(thumb('Minimum').getAttribute('aria-valuenow')).toBe('130');
        expect(field('Minimum price in euros').value).toBe('125');

        type(field('Maximum price in euros'), '3000');
        const request = await respond(page(['a'], 1));
        expect(url()).toBe('/?min=125&max=3000');
        expect(request.params.get('maxPrice')).toBe('3000');
        expect(thumb('Maximum').getAttribute('aria-valuenow')).toBe('2000');
        expect(field('Maximum price in euros').value).toBe('3000');
      });

      it('omits the bound when a typed value equals the slider ends', async () => {
        await openWithFilters('/?min=50&max=900');
        openPricePopover();
        type(field('Maximum price in euros'), '2000');
        const request = await respond(page(['a'], 1));
        expect(url()).toBe('/?min=50');
        expect(request.params.has('maxPrice')).toBe(false);
        type(field('Minimum price in euros'), '0');
        const cleared = await respond(page(['a'], 1));
        expect(url()).toBe('/');
        expect(cleared.params.has('minPrice')).toBe(false);
        expect(field('Minimum price in euros').value).toBe('');
      });

      it('rejects malformed amounts without changing the URL', async () => {
        await openWithFilters('/');
        openPricePopover();
        type(field('Minimum price in euros'), '1.234');
        expect(url()).toBe('/');
        expect(searches()).toHaveLength(0);
        expect(root().querySelector('[role="alert"]')?.textContent).toContain('2 decimals');
      });

      it('shows a message and sends no price when the minimum is above the maximum', async () => {
        const request = await openWithFilters('/?min=500&max=100');
        openPricePopover();
        expect(root().textContent).toContain("The minimum can't be above the maximum.");
        expect(request.params.has('minPrice')).toBe(false);
        expect(request.params.has('maxPrice')).toBe(false);
      });

      it('drags a thumb and writes the URL only on release', async () => {
        await openWithFilters('/');
        openPricePopover();
        const track = root().querySelector<HTMLElement>('.track')!;
        track.getBoundingClientRect = () =>
          ({
            left: 0,
            width: 400,
            top: 0,
            height: 4,
            right: 400,
            bottom: 4,
            x: 0,
            y: 0,
          }) as DOMRect;
        const fire = (type: string, x: number) =>
          track.dispatchEvent(
            new MouseEvent(type, { clientX: x, bubbles: true, cancelable: true }),
          );
        fire('pointerdown', 100);
        // 60% of the track is the 500 EUR knee; 80% sits halfway through the upper segment.
        fire('pointermove', 240);
        harness.detectChanges();
        expect(thumb('Minimum').getAttribute('aria-valuenow')).toBe('500');
        fire('pointermove', 320);
        harness.detectChanges();
        expect(url()).toBe('/');
        expect(thumb('Minimum').getAttribute('aria-valuenow')).toBe('1250');
        fire('pointerup', 320);
        const request = await respond(page(['a'], 1));
        expect(url()).toBe('/?min=1250');
        expect(request.params.get('minPrice')).toBe('1250');
      });
    });

    describe('attribute filters', () => {
      it('renders only the searchable controls for a leaf schema', async () => {
        await openWithFilters('/?cat=gpus&type=HARDWARE');
        const attributes = root().querySelector('app-attribute-filters')!;
        expect(attributes.textContent).toContain('Vram gb');
        expect(attributes.textContent).toContain('GB');
        expect(attributes.querySelector('input[aria-label="Vram gb minimum"]')).not.toBeNull();
        expect(attributes.querySelector('input[aria-label="Vram gb maximum"]')).not.toBeNull();
        expect(attributes.querySelectorAll('input[aria-label^="Cuda cores"]')).toHaveLength(1);
        expect(attributes.querySelector('input[aria-label="Cuda cores"]')).not.toBeNull();
        const options = Array.from(attributes.querySelectorAll('select option')).map((option) =>
          option.textContent?.trim(),
        );
        expect(options).toEqual(['Any', 'PCIe (7)', 'NVLink (2)']);
        const ecc = attributes.querySelectorAll('[role="radio"]');
        expect(Array.from(ecc).map((chip) => chip.textContent?.trim())).toEqual([
          'Any',
          'Yes',
          'No',
        ]);
      });

      it('translates every control to the exact attr.<name> keys', async () => {
        await openWithFilters('/?cat=gpus&type=HARDWARE');
        const attributes = root().querySelector('app-attribute-filters') as HTMLElement;
        type(field('Vram gb minimum'), '8');
        expect((await respond(page(['a'], 1))).params.get('attr.vram_gb.min')).toBe('8');
        type(field('Vram gb maximum'), '24');
        const range = await respond(page(['a'], 1));
        expect(range.params.get('attr.vram_gb.max')).toBe('24');
        expect(range.params.has('attr.vram_gb')).toBe(false);

        type(field('Cuda cores'), '4096');
        const exact = await respond(page(['a'], 1));
        expect(exact.params.get('attr.cuda_cores')).toBe('4096');
        expect(exact.params.has('attr.cuda_cores.min')).toBe(false);

        const select = attributes.querySelector('select')!;
        select.value = 'PCIe';
        select.dispatchEvent(new Event('change', { bubbles: true }));
        expect((await respond(page(['a'], 1))).params.get('attr.interface')).toBe('PCIe');

        const yes = Array.from(attributes.querySelectorAll<HTMLElement>('[role="radio"]')).find(
          (chip) => chip.textContent?.trim() === 'Yes',
        )!;
        yes.click();
        const bool = await respond(page(['a'], 1));
        expect(bool.params.get('attr.ecc')).toBe('true');
        expect(url()).toBe(
          '/?cat=gpus&type=HARDWARE&attr.vram_gb.min=8&attr.vram_gb.max=24&attr.cuda_cores=4096&attr.interface=PCIe&attr.ecc=true',
        );
      });

      it('rejects a non-integer exact value without changing the URL', async () => {
        await openWithFilters('/?cat=gpus&type=HARDWARE');
        type(field('Cuda cores'), '4.5');
        expect(searches()).toHaveLength(0);
        expect(url()).toBe('/?cat=gpus&type=HARDWARE');
        expect(root().textContent).toContain('Enter a whole number.');
      });

      it('renders no attribute filters and fetches no schema for a non-leaf category', async () => {
        await open('/?cat=components&type=HARDWARE');
        flushCategories(tree);
        await respond(page(['a'], 1));
        expect(root().querySelector('app-attribute-filters')).toBeNull();
        http.expectNone(`${API}/categories/components/schema`);
      });

      it('clears attribute filters when the category changes', async () => {
        await openWithFilters('/?cat=gpus&type=HARDWARE&attr.vram_gb.min=8&attr.interface=PCIe');
        const trigger = root().querySelector<HTMLButtonElement>('app-category-picker > button')!;
        trigger.click();
        harness.detectChanges();
        const monitors = Array.from(root().querySelectorAll<HTMLButtonElement>('[data-row]')).find(
          (row) => row.textContent?.includes('Monitors'),
        )!;
        monitors.click();
        const request = await respond(page(['a'], 1));
        expect(url()).toBe('/?cat=monitors&type=HARDWARE');
        expect(request.params.keys().filter((key) => key.startsWith('attr.'))).toEqual([]);
        expect(request.params.get('categoryId')).toBe('monitors');
        http.expectNone(`${API}/categories/gpus/schema`);
        await waitForOne(`${API}/categories/monitors/schema`).then((schemaRequest) =>
          schemaRequest.flush({ categoryId: 'monitors', attributes: [] }),
        );
      });

      it('shows the not available banner and drops a refused range filter', async () => {
        await open('/?cat=gpus&type=HARDWARE&attr.vram_gb.min=8&attr.interface=PCIe');
        flushCategories(tree);
        (await waitForOne(`${API}/categories/gpus/schema`)).flush(schema);
        const refused = await vi.waitFor(() =>
          http.expectOne((candidate) => candidate.url === `${API}/search`),
        );
        refused.flush(
          { title: 'attribute-filter-not-allowed', status: 422 },
          { status: 422, statusText: 'Unprocessable' },
        );
        await settle();
        const retry = await respond(page(['a'], 1));
        expect(root().querySelector('[data-ui="banner"]')?.textContent).toContain(
          'This filter is not available yet',
        );
        expect(url()).toBe('/?cat=gpus&type=HARDWARE&attr.interface=PCIe');
        expect(retry.params.has('attr.vram_gb.min')).toBe(false);
        expect(retry.params.get('attr.interface')).toBe('PCIe');
        expect(cards()).toHaveLength(1);
      });
    });

    describe('category picker and type', () => {
      it('shows every descendant as an indented, directly selectable category row', async () => {
        await openWithFilters('/?type=HARDWARE');
        const barOrder = Array.from(root().querySelector('.bar')!.children).map((child) =>
          child.tagName.toLowerCase(),
        );
        expect(barOrder).toEqual([
          'app-segmented-control',
          'app-category-picker',
          'app-price-control',
          'app-menu-select',
          'button',
        ]);
        const trigger = root().querySelector<HTMLButtonElement>('app-category-picker > button')!;
        expect(trigger.textContent).toContain('All categories');
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        trigger.click();
        harness.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('true');
        const rows = () => Array.from(root().querySelectorAll<HTMLButtonElement>('[data-row]'));
        expect(rows().map((row) => row.textContent?.trim())).toEqual([
          'All categories',
          'Components',
          'Graphics cards',
          'Processors',
          'Monitors',
        ]);
        expect(rows()[1].style.paddingLeft).toBe('0.75rem');
        expect(rows()[2].style.paddingLeft).toBe('2rem');
        rows()[1].click();
        const request = await respond(page(['a'], 1));
        expect(request.params.get('categoryId')).toBe('components');
        expect(url()).toBe('/?cat=components&type=HARDWARE');
        expect(trigger.textContent).toContain('Components');
        expect(root().querySelectorAll('[data-row]')).toHaveLength(0);

        trigger.click();
        harness.detectChanges();
        rows()[0].click();
        await respond(page(['a'], 1));
        expect(url()).toBe('/?type=HARDWARE');
      });

      it('shows a legacy selected type root as All categories without a root row', async () => {
        await openWithFilters('/?cat=hardware-root&type=HARDWARE');
        const picker = root().querySelector('app-category-picker')!;
        expect(picker.querySelector('button')?.textContent).toContain('All categories');
        picker.querySelector<HTMLButtonElement>('button')!.click();
        harness.detectChanges();
        const rows = Array.from(picker.querySelectorAll<HTMLButtonElement>('[data-row]'));
        expect(rows.map((row) => row.textContent?.trim())).toEqual([
          'All categories',
          'Components',
          'Graphics cards',
          'Processors',
          'Monitors',
        ]);
        expect(rows.some((row) => row.textContent?.trim() === 'Hardware')).toBe(false);
      });

      it('requires a type before category browsing and clears the category when choosing All', async () => {
        await openWithFilters('/?q=gpu&min=20');
        const trigger = root().querySelector<HTMLButtonElement>('app-category-picker > button')!;
        const rows = () => Array.from(root().querySelectorAll<HTMLButtonElement>('[data-row]'));
        expect(trigger.disabled).toBe(true);
        expect(trigger.textContent).toContain('Choose a type first');
        trigger.click();
        harness.detectChanges();
        expect(rows()).toHaveLength(0);

        const chooseType = async (label: string) => {
          const chip = Array.from(
            root().querySelectorAll<HTMLElement>('app-segmented-control [role="radio"]'),
          ).find((item) => item.textContent?.trim() === label)!;
          chip.click();
          await respond(page(['a'], 1));
        };

        await chooseType('Software');
        trigger.click();
        harness.detectChanges();
        expect(rows().map((row) => row.textContent?.trim())).toEqual([
          'All categories',
          'Developer tools',
          'Games',
        ]);
        rows()[2].click();
        await respond(page(['game'], 1));
        expect(url()).toBe('/?q=gpu&cat=games&type=SOFTWARE&min=20');
        (await waitForOne(`${API}/categories/games/schema`)).flush({
          categoryId: 'games',
          attributes: [],
        });
        await settle();

        await chooseType('All');
        harness.detectChanges();
        expect(url()).toBe('/?q=gpu&min=20');
        expect(trigger.disabled).toBe(true);
        expect(trigger.textContent).toContain('Choose a type first');
        expect(rows()).toHaveLength(0);
      });

      it('closes an open category picker when All disables it and does not reopen on re-enable', async () => {
        await openWithFilters('/?type=HARDWARE');
        const trigger = root().querySelector<HTMLButtonElement>('app-category-picker > button')!;
        trigger.click();
        harness.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('true');

        const chooseType = async (label: string) => {
          const option = Array.from(
            root().querySelectorAll<HTMLElement>('app-segmented-control [role="radio"]'),
          ).find((item) => item.textContent?.trim() === label)!;
          option.click();
          await respond(page(['a'], 1));
        };
        await chooseType('All');
        expect(trigger.disabled).toBe(true);
        expect(trigger.getAttribute('aria-expanded')).toBe('false');

        await chooseType('Hardware');
        expect(trigger.disabled).toBe(false);
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        expect(root().querySelectorAll('[data-row]')).toHaveLength(0);
      });

      it('clears a category from the other type and its attributes while preserving query and price', async () => {
        await openWithFilters('/?q=gpu&type=HARDWARE&cat=gpus&min=20&attr.interface=PCIe');
        const software = Array.from(
          root().querySelectorAll<HTMLElement>('app-segmented-control [role="radio"]'),
        ).find((chip) => chip.textContent?.trim() === 'Software')!;
        software.click();
        const request = await respond(page(['s1'], 1));

        expect(url()).toBe('/?q=gpu&type=SOFTWARE&min=20');
        expect(request.params.get('q')).toBe('gpu');
        expect(request.params.get('listingType')).toBe('SOFTWARE');
        expect(request.params.get('minPrice')).toBe('20');
        expect(request.params.has('categoryId')).toBe(false);
        expect(request.params.has('attr.interface')).toBe(false);
      });

      it('keeps a selected category and its attributes when choosing its matching type', async () => {
        await open('/?cat=gpus&min=20&attr.interface=PCIe');
        const original = await vi.waitFor(() =>
          http.expectOne((candidate) => candidate.url === `${API}/search`),
        );
        original.flush(page(['a'], 1));
        await settle();
        const hardware = Array.from(
          root().querySelectorAll<HTMLElement>('app-segmented-control [role="radio"]'),
        ).find((chip) => chip.textContent?.trim() === 'Hardware')!;
        hardware.click();
        const request = await respond(page(['a'], 1));
        flushCategories(tree);
        await settle();
        (await waitForOne(`${API}/categories/gpus/schema`)).flush(schema);
        await settle();

        expect(url()).toBe('/?cat=gpus&type=HARDWARE&min=20&attr.interface=PCIe');
        expect(request.params.get('categoryId')).toBe('gpus');
        expect(request.params.get('listingType')).toBe('HARDWARE');
        expect(request.params.get('attr.interface')).toBe('PCIe');
      });

      it('clears a known incompatible category from a loaded URL after the tree arrives', async () => {
        await open('/?q=gpu&type=SOFTWARE&cat=gpus&min=20');
        const initial = await vi.waitFor(() =>
          http.expectOne((request) => request.url === `${API}/search`),
        );
        initial.flush(page(['a'], 1));
        await settle();
        flushCategories(tree);
        await settle();
        for (const request of http.match((item) => item.url === `${API}/categories/gpus/schema`)) {
          if (!request.cancelled) request.flush(schema);
        }

        await vi.waitFor(() => expect(url()).toBe('/?q=gpu&type=SOFTWARE&min=20'));
        const reset = await respond(page(['s1'], 1));
        expect(reset.params.get('categoryId')).toBe(null);
        expect(reset.params.get('listingType')).toBe('SOFTWARE');
        expect(reset.params.get('minPrice')).toBe('20');
      });

      it('infers the authoritative type for a legacy category URL after loading the tree', async () => {
        await open('/?q=gpu&cat=gpus&min=20&attr.interface=PCIe');
        const initial = await vi.waitFor(() =>
          http.expectOne((candidate) => candidate.url === `${API}/search`),
        );
        initial.flush(page(['a'], 1));
        await settle();
        flushCategories(tree);
        await settle();
        (await waitForOne(`${API}/categories/gpus/schema`)).flush(schema);
        await settle();

        const inferred = await respond(page(['a'], 1));
        expect(url()).toBe('/?q=gpu&cat=gpus&type=HARDWARE&min=20&attr.interface=PCIe');
        expect(inferred.params.get('listingType')).toBe('HARDWARE');
        expect(inferred.params.get('categoryId')).toBe('gpus');
        expect(inferred.params.get('minPrice')).toBe('20');
        expect(inferred.params.get('attr.interface')).toBe('PCIe');
      });

      it('reopens with the selected row and clears incompatible categories when type changes', async () => {
        await openWithFilters('/?type=HARDWARE');
        const trigger = root().querySelector<HTMLButtonElement>('app-category-picker > button')!;
        const rows = () => Array.from(root().querySelectorAll<HTMLButtonElement>('[data-row]'));
        trigger.click();
        harness.detectChanges();
        rows()[2].click();
        const schemaRequest = await waitForOne(`${API}/categories/gpus/schema`);
        schemaRequest.flush(schema);
        await respond(page(['a'], 1));

        trigger.click();
        harness.detectChanges();
        expect(root().querySelector('.crumbs')).toBeNull();
        expect(rows().map((row) => row.textContent?.trim())).toEqual([
          'All categories',
          'Components',
          'Graphics cards',
          'Processors',
          'Monitors',
        ]);
        expect(
          rows().find((row) => row.textContent?.trim() === 'Graphics cards')?.className,
        ).toContain('selected');

        const software = Array.from(
          root().querySelectorAll<HTMLElement>('app-segmented-control [role="radio"]'),
        ).find((chip) => chip.textContent?.trim() === 'Software')!;
        software.click();
        await respond(page(['s1'], 1));
        trigger.click();
        harness.detectChanges();
        expect(rows().map((row) => row.textContent?.trim())).toEqual([
          'All categories',
          'Developer tools',
          'Games',
        ]);
      });

      it('moves focus with the arrow keys and closes on Escape', async () => {
        await openWithFilters('/?type=HARDWARE');
        const trigger = root().querySelector<HTMLButtonElement>('app-category-picker > button')!;
        trigger.focus();
        trigger.click();
        harness.detectChanges();
        await settle();
        const rows = Array.from(root().querySelectorAll<HTMLButtonElement>('[data-row]'));
        rows[0].focus();
        press(rows[0], 'ArrowDown');
        expect(document.activeElement).toBe(rows[1]);
        press(rows[1], 'ArrowUp');
        expect(document.activeElement).toBe(rows[0]);
        press(rows[0], 'Escape');
        harness.detectChanges();
        expect(root().querySelectorAll('[data-row]')).toHaveLength(0);
        expect(document.activeElement).toBe(trigger);
      });

      it('writes the type to the URL by replacing history and Clear keeps the text query', async () => {
        await openWithFilters('/?q=gpu');
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
        const hardware = Array.from(
          root().querySelectorAll<HTMLElement>('app-segmented-control [role="radio"]'),
        ).find((chip) => chip.textContent?.trim() === 'Hardware')!;
        hardware.click();
        const request = await respond(page(['a'], 1));
        expect(request.params.get('listingType')).toBe('HARDWARE');
        expect(url()).toBe('/?q=gpu&type=HARDWARE');
        expect(navigate.mock.calls.at(-1)?.[1]?.replaceUrl).toBe(true);

        openPricePopover();
        type(field('Minimum price in euros'), '10');
        await respond(page(['a'], 1));
        const clear = Array.from(root().querySelectorAll<HTMLButtonElement>('button')).find(
          (button) => button.textContent?.trim() === 'Clear',
        )!;
        clear.click();
        const cleared = await respond(page(['a'], 1));
        expect(url()).toBe('/?q=gpu');
        expect(cleared.params.get('q')).toBe('gpu');
        expect(cleared.params.has('listingType')).toBe(false);
      });
    });

    describe('phone', () => {
      it('keeps Category visible in the sheet but disables it until a type is selected', async () => {
        phone(true);
        await openWithFilters('/');
        root().querySelector<HTMLButtonElement>('button.filters-button')!.click();
        harness.detectChanges();
        await settle();

        const category = root().querySelector('app-category-picker')!;
        const buttons = category.querySelectorAll('button');
        expect(buttons).toHaveLength(1);
        expect(buttons[0].disabled).toBe(true);
        expect(buttons[0].textContent).toContain('Choose a type first');
        expect(buttons[0].getAttribute('aria-expanded')).toBe('false');
        expect(category.querySelectorAll('[data-row]')).toHaveLength(0);
        expect(root().querySelectorAll('.phone-row app-segmented-control')).toHaveLength(0);
        expect(root().querySelectorAll('[role="dialog"] app-segmented-control')).toHaveLength(1);
      });

      it('collapses Category to a row that expands, filters, selects a parent and collapses again', async () => {
        phone(true, false);
        await openWithFilters('/?type=HARDWARE');
        root().querySelector<HTMLButtonElement>('button.filters-button')!.click();
        harness.detectChanges();
        await settle();

        const picker = root().querySelector('app-category-picker')!;
        const trigger = picker.querySelector<HTMLButtonElement>('button')!;
        const rows = () => Array.from(picker.querySelectorAll<HTMLElement>('[data-row]'));
        const names = () => rows().map((row) => row.textContent?.trim().split('·')[0].trim());
        expect(trigger.textContent).toContain('All categories');
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        expect(trigger.hasAttribute('aria-haspopup')).toBe(false);
        expect(rows()).toHaveLength(0);

        trigger.click();
        harness.detectChanges();
        await settle();
        expect(trigger.getAttribute('aria-expanded')).toBe('true');
        expect(names()).toEqual(['All categories', 'Components', 'Graphics cards', 'Processors', 'Monitors']);
        const filter = picker.querySelector<HTMLInputElement>('input.filter')!;
        expect(document.activeElement).toBe(rows()[0]);
        expect(document.activeElement).not.toBe(filter);

        type(filter, 'comp');
        expect(rows()).toHaveLength(3);
        expect(rows()[0].textContent?.trim().startsWith('Components')).toBe(true);
        expect(names()).not.toContain('Monitors');
        press(filter, 'ArrowDown');
        expect(document.activeElement).toBe(rows()[0]);

        press(rows()[0], 'Escape');
        harness.detectChanges();
        expect(rows()).toHaveLength(0);
        expect(root().querySelector('[role="dialog"]')).not.toBeNull();
        expect(document.activeElement).toBe(trigger);

        trigger.click();
        harness.detectChanges();
        await settle();
        type(picker.querySelector<HTMLInputElement>('input.filter')!, 'comp');
        rows()[0].click();
        await respond(page(['a'], 1));
        expect(url()).toBe('/?cat=components&type=HARDWARE');
        expect(trigger.textContent).toContain('Components');
        expect(rows()).toHaveLength(0);
        expect(root().querySelector('[role="dialog"]')).not.toBeNull();
      });

      it('sorts from the pill in the phone row and keeps Sort by out of the sheet', async () => {
        phone(true);
        await openWithFilters('/?q=gpu');
        const pill = root().querySelector<HTMLButtonElement>('.phone-row app-menu-select > button')!;
        expect(pill.textContent).toContain('Sort by');
        expect(pill.textContent).toContain('Relevance');
        expect(root().querySelector('.filters-button [data-ui="count-badge"]')?.textContent?.trim()).toBe('');

        pill.click();
        harness.detectChanges();
        Array.from(root().querySelectorAll<HTMLButtonElement>('.phone-row app-menu-select [data-row]'))
          .find((row) => row.textContent?.trim() === 'Oldest')!
          .click();
        const oldest = await respond(page(['a'], 1));
        expect(url()).toBe('/?q=gpu&sort=oldest');
        expect(oldest.params.get('sort')).toBe('oldest');
        expect(oldest.params.get('offset')).toBe('0');

        root().querySelector<HTMLButtonElement>('button.filters-button')!.click();
        harness.detectChanges();
        await settle();
        expect(root().querySelector('[role="dialog"] .sheet-body h3')?.textContent?.trim()).not.toBe('Sort by');
        expect(root().querySelector('.sort-options')).toBeNull();
      });

      describe('active filter chips', () => {
        const FULL =
          '/?type=HARDWARE&cat=gpus&min=50&max=500&attr.interface=PCIe&attr.vram_gb.min=8&attr.vram_gb.max=16&attr.ecc=true&sort=newest';

        function chips() {
          return Array.from(root().querySelectorAll<HTMLButtonElement>('.chips .chip'));
        }

        function chipNamed(label: string) {
          return chips().find((chip) => chip.getAttribute('aria-label') === label)!;
        }

        it('renders one chip per active filter, matching the Filters badge, and none for sort', async () => {
          phone(true, false);
          await openWithFilters(FULL);
          expect(chips().map((chip) => chip.textContent?.trim())).toEqual([
            'Hardware',
            'Graphics cards',
            '€50–€500',
            'Interface: PCIe',
            'Vram gb: 8–16 GB',
            'Ecc: Yes',
          ]);
          expect(chips().map((chip) => chip.getAttribute('aria-label'))).toEqual([
            'Remove Hardware filter',
            'Remove Graphics cards filter',
            'Remove €50–€500 filter',
            'Remove Interface: PCIe filter',
            'Remove Vram gb: 8–16 GB filter',
            'Remove Ecc: Yes filter',
          ]);
          expect(
            root().querySelector('.filters-button [data-ui="count-badge"]')?.textContent?.trim(),
          ).toBe('6');
          expect(root().querySelector('.chips')?.getAttribute('aria-label')).toBe('Active filters');
          expect(chips().some((chip) => /recent|oldest/i.test(chip.textContent ?? ''))).toBe(false);
        });

        it('labels single values and open-ended ranges', async () => {
          phone(true, false);
          await openWithFilters('/?cat=gpus&type=HARDWARE&attr.cuda_cores=5000&attr.vram_gb.min=8');
          expect(chips().map((chip) => chip.textContent?.trim())).toEqual([
            'Hardware',
            'Graphics cards',
            'Cuda cores: 5000',
            'Vram gb: 8+ GB',
          ]);
        });

        it('removes the price, an enum attribute and a range attribute like their controls do', async () => {
          phone(true, false);
          await openWithFilters(FULL);

          chipNamed('Remove €50–€500 filter').click();
          const noPrice = await respond(page(['a'], 1, 0, { facets }));
          expect(url()).not.toMatch(/[?&]min=/);
          expect(url()).not.toMatch(/[?&]max=/);
          expect(noPrice.params.has('minPrice')).toBe(false);
          expect(noPrice.params.has('maxPrice')).toBe(false);

          chipNamed('Remove Interface: PCIe filter').click();
          const noInterface = await respond(page(['a'], 1, 0, { facets }));
          expect(url()).not.toContain('attr.interface');
          expect(noInterface.params.has('attr.interface')).toBe(false);

          chipNamed('Remove Vram gb: 8–16 GB filter').click();
          await respond(page(['a'], 1, 0, { facets }));
          expect(url()).not.toContain('attr.vram_gb');
          expect(url()).toContain('attr.ecc=true');
        });

        it('removing the category drops its attributes but keeps type, price and sort', async () => {
          phone(true, false);
          await openWithFilters(FULL);
          chipNamed('Remove Graphics cards filter').click();
          const request = await respond(page(['a'], 1));
          expect(url()).toBe('/?type=HARDWARE&min=50&max=500&sort=newest');
          expect(request.params.has('categoryId')).toBe(false);
        });

        it('removing the type drops category and attributes too', async () => {
          phone(true, false);
          await openWithFilters(FULL);
          chipNamed('Remove Hardware filter').click();
          const request = await respond(page(['a'], 1));
          expect(url()).toBe('/?min=50&max=500&sort=newest');
          expect(request.params.has('listingType')).toBe(false);
          expect(request.params.has('categoryId')).toBe(false);
        });

        it('moves focus to the next chip after a removal, then to Filters when none remain', async () => {
          phone(true);
          await openWithFilters('/?type=HARDWARE&min=50');
          expect(chips().map((chip) => chip.textContent?.trim())).toEqual(['Hardware', '€50+']);

          chipNamed('Remove Hardware filter').click();
          await respond(page(['a'], 1));
          expect(document.activeElement).toBe(chipNamed('Remove €50+ filter'));

          chipNamed('Remove €50+ filter').click();
          await respond(page(['a'], 1));
          expect(document.activeElement).toBe(
            root().querySelector<HTMLButtonElement>('button.filters-button'),
          );
        });

        it('renders no chip list for a sort-only URL', async () => {
          phone(true);
          await openWithFilters('/?sort=newest');
          expect(root().querySelector('.chips')).toBeNull();
        });

        it('renders no chips on desktop', async () => {
          phone(false);
          await openWithFilters(FULL);
          expect(root().querySelector('.chips')).toBeNull();
          expect(root().querySelector('button[aria-label^="Remove"]')).toBeNull();
        });
      });

      it('shows only Filters on compact phones and moves type controls as the viewport resizes', async () => {
        const resize = phone(true, true);
        await openWithFilters('/?type=HARDWARE');
        const trigger = root().querySelector<HTMLButtonElement>('button.filters-button')!;
        expect(
          Array.from(root().querySelector('.phone-row')!.children).map((child) =>
            child.tagName.toLowerCase(),
          ),
        ).toEqual(['app-menu-select', 'button']);
        expect(root().querySelector('.phone-row app-segmented-control')).toBeNull();

        trigger.click();
        harness.detectChanges();
        await settle();
        const sheet = () => root().querySelector<HTMLElement>('[role="dialog"]')!;
        expect(sheet().querySelectorAll('app-segmented-control')).toHaveLength(1);

        resize(true, false);
        harness.detectChanges();
        expect(sheet()).not.toBeNull();
        expect(root().querySelector('.phone-row app-segmented-control')).not.toBeNull();
        expect(sheet().querySelector('app-segmented-control')).toBeNull();

        resize(true, true);
        harness.detectChanges();
        expect(sheet().querySelector('app-segmented-control')).not.toBeNull();
        expect(root().querySelector('.phone-row app-segmented-control')).toBeNull();
      });

      it('shows a Filters button with the active count and a sticky results footer in a full sheet', async () => {
        phone(true, false);
        await openWithFilters('/?cat=gpus&type=HARDWARE&min=10&attr.interface=PCIe', {
          ...page(['a', 'b'], 12),
          facets,
        });
        expect(root().querySelector('.bar')).toBeNull();
        const trigger = root().querySelector<HTMLButtonElement>('button.filters-button')!;
        expect(trigger.textContent).toContain('Filters');
        expect(trigger.querySelector('[data-ui="count-badge"]')?.textContent?.trim()).toBe('4');
        expect(root().querySelector('app-segmented-control')).not.toBeNull();
        expect(root().querySelector('[role="dialog"]')).toBeNull();
        expect(
          Array.from(root().querySelector('.phone-row')!.children).map((child) =>
            child.tagName.toLowerCase(),
          ),
        ).toEqual(['app-segmented-control', 'app-menu-select', 'button']);

        trigger.click();
        harness.detectChanges();
        await settle();
        const sheet = root().querySelector<HTMLElement>('[role="dialog"]')!;
        expect(sheet.getAttribute('aria-modal')).toBe('true');
        expect(sheet.querySelector('h2')?.textContent).toBe('Filters');
        expect(sheet.querySelector('app-category-picker')).not.toBeNull();
        expect(sheet.querySelectorAll('app-category-picker [data-row]')).toHaveLength(0);
        expect(
          Array.from(sheet.querySelectorAll('.sheet-body h3')).map((heading) =>
            heading.textContent?.trim(),
          ),
        ).toEqual(['Category', 'Price']);
        expect(sheet.querySelector('[aria-label="Listing type"]')).toBeNull();
        expect(sheet.querySelectorAll('[role="slider"]')).toHaveLength(0);
        expect(sheet.querySelectorAll('input[aria-label$="price in euros"]')).toHaveLength(2);
        expect(sheet.querySelector('input[aria-label="Minimum price in euros"]')).not.toBeNull();
        expect(sheet.querySelector('input[aria-label="Maximum price in euros"]')).not.toBeNull();
        expect(sheet.querySelector('app-attribute-filters')).not.toBeNull();
        const footer = Array.from(sheet.querySelectorAll('footer button')).map((button) =>
          button.textContent?.trim(),
        );
        expect(footer).toEqual(['Show 12 results', 'Clear']);
        for (const control of sheet.querySelectorAll<HTMLElement>('button, input, select')) {
          expect(control.getAttribute('aria-label') ?? control.textContent?.trim()).not.toBe('');
        }
      });

      it('closes on Escape and the footer, returns focus, and Clear empties the filters', async () => {
        phone(true);
        await openWithFilters('/?type=SOFTWARE');
        const trigger = root().querySelector<HTMLButtonElement>('button.filters-button')!;
        trigger.focus();
        trigger.click();
        harness.detectChanges();
        await settle();
        const sheet = () => root().querySelector<HTMLElement>('[role="dialog"]');
        sheet()!.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
        );
        harness.detectChanges();
        expect(sheet()).toBeNull();
        expect(document.activeElement).toBe(trigger);

        trigger.click();
        harness.detectChanges();
        const buttons = Array.from(sheet()!.querySelectorAll<HTMLButtonElement>('footer button'));
        buttons[1].click();
        const cleared = await respond(page(['a'], 1));
        expect(url()).toBe('/');
        expect(cleared.params.has('listingType')).toBe(false);
        harness.detectChanges();
        root().querySelector<HTMLButtonElement>('[role="dialog"] footer button')!.click();
        harness.detectChanges();
        expect(sheet()).toBeNull();
      });
    });
  });
});
