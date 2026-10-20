import { BreakpointObserver } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, Observable, of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, Mock, vi } from 'vitest';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import { AppConfigService } from '../../core/config/app-config';
import {
  CheckoutRequest,
  CheckoutResponse,
  ListingDetailResponse,
  OfferView,
  OrderView,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { OrderApi } from '../../core/api/order-api';
import { AuthAdapter, AuthEvent, AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { Router } from '@angular/router';
import { AppError } from '../../core/http/app-error';
import {
  addMoney,
  AddressForm,
  ADDRESS_LIMITS,
  approvalTarget,
  buildCheckoutRequest,
  CHECKOUT_FALLBACK_DELAY_MS,
  CHECKOUT_NAVIGATOR,
  checkoutFingerprint,
  CheckoutService,
  EU_COUNTRIES,
  validateAddress,
} from './checkout.service';
import { CheckoutPage } from './checkout.page';
import { CHECKOUT_ROUTES } from './checkout.routes';
import { PENDING_CHECKOUT_KEY, PendingCheckoutStore } from './pending-checkout.store';

const BUYER = 'buyer-sub';
const SELLER = 'seller-sub';

const EXPECTED_COUNTRIES = [
  'AT',
  'BE',
  'BG',
  'HR',
  'CY',
  'CZ',
  'DK',
  'EE',
  'FI',
  'FR',
  'DE',
  'GR',
  'HU',
  'IE',
  'IT',
  'LV',
  'LT',
  'LU',
  'MT',
  'NL',
  'PL',
  'PT',
  'RO',
  'SK',
  'SI',
  'ES',
  'SE',
];

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

function appError(
  status: number,
  code: string,
  extras: Record<string, string | number> = {},
): AppError {
  return { status, code, type: 'urn:rebyte:problem:' + code, fieldErrors: {}, extras };
}

function listing(overrides: Partial<ListingDetailResponse> = {}): ListingDetailResponse {
  return {
    id: 'l1',
    title: 'ThinkPad X1',
    description: 'Works well.',
    price: '499.00',
    status: 'ACTIVE',
    type: 'HARDWARE',
    publication: '2026-10-01T10:00:00Z',
    sellerSub: SELLER,
    categoryId: 'hw-laptops',
    photos: [],
    photoKeys: [],
    paymentMethods: ['PAYPAL'],
    condition: 'GOOD',
    shippingLocation: 'Turin',
    shippingCost: '5.00',
    software: null,
    category: { id: 'hw-laptops', name: 'Laptops', ancestors: ['Hardware', 'Laptops'] },
    priceHistory: [],
    attributes: [],
    seller: { sub: SELLER, username: 'seller', avatar: null, ratingAvg: null, ratingCount: 0 },
    ...overrides,
  };
}

function software(overrides: Partial<ListingDetailResponse> = {}): ListingDetailResponse {
  return listing({
    id: 'l2',
    title: 'Photo Editor',
    price: '29.90',
    type: 'SOFTWARE',
    condition: null,
    shippingLocation: null,
    shippingCost: null,
    ...overrides,
  });
}

function offer(overrides: Partial<OfferView> = {}): OfferView {
  return {
    id: 'o1',
    listingId: 'l1',
    listingTitle: 'ThinkPad X1',
    buyerSub: BUYER,
    sellerSub: SELLER,
    amount: '420.00',
    askingPrice: '499.00',
    status: 'ACCEPTED',
    rejectionReason: null,
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    orderId: null,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  };
}

function order(overrides: Partial<OrderView> = {}): OrderView {
  return {
    id: 'ord0',
    listingId: 'l0',
    listingTitle: 'Old thing',
    listingType: 'HARDWARE',
    buyerSub: BUYER,
    sellerSub: SELLER,
    itemPrice: '10.00',
    shippingCost: '2.00',
    pricePaid: '12.00',
    paymentMethod: 'PAYPAL',
    status: 'DELIVERED',
    createdAt: '2026-09-01T10:00:00Z',
    expiryAt: '2026-09-01T10:30:00Z',
    ...overrides,
  };
}

const ADDRESS: AddressForm = {
  recipient: 'Alex Rossi',
  street: 'Via Giuseppe Verdi 12',
  city: 'Milan',
  postalCode: '20121',
  country: 'IT',
};

type CheckoutFn = (request: CheckoutRequest, key: string) => Observable<CheckoutResponse>;

const CREATED: CheckoutResponse = {
  orderId: 'ord1',
  approvalUrl: 'https://www.sandbox.paypal.com/checkoutnow?token=T1',
  status: 'AWAITING_PAYMENT',
};

describe('checkout request building', () => {
  it('adds decimal strings exactly, including 0.01 and the largest two-decimal values', () => {
    expect(addMoney('0.01', '0.00')).toBe('0.01');
    expect(addMoney('0.10', '0.20')).toBe('0.30');
    expect(addMoney('499.00', '5')).toBe('504.00');
    expect(addMoney('12.5', '0.05')).toBe('12.55');
    expect(addMoney('99999999.99', '0.01')).toBe('100000000.00');
    expect(addMoney('12345678.90', '87654321.09')).toBe('99999999.99');
  });

  it('sends exactly the five address properties for hardware', () => {
    const request = buildCheckoutRequest({ listing: listing(), offer: null, address: ADDRESS });
    expect(request).toEqual({
      listingId: 'l1',
      method: 'PAYPAL',
      expectedTotal: '504.00',
      address: ADDRESS,
    });
    expect(Object.keys(request.address!).sort()).toEqual(
      ['city', 'country', 'postalCode', 'recipient', 'street'].sort(),
    );
    expect('offerId' in request).toBe(false);
  });

  it('omits the address key entirely for software, even when a form value is supplied', () => {
    const request = buildCheckoutRequest({ listing: software(), offer: null, address: ADDRESS });
    expect(request).toEqual({ listingId: 'l2', method: 'PAYPAL', expectedTotal: '29.90' });
    expect('address' in request).toBe(false);
    const body = JSON.stringify(request);
    for (const forbidden of ['recipientName', 'addressLine', 'countryCode', 'shippingAddress']) {
      expect(body).not.toContain(forbidden);
    }
  });

  it('passes an accepted offer as offerId and prices the item at the offer amount', () => {
    const request = buildCheckoutRequest({ listing: listing(), offer: offer(), address: ADDRESS });
    expect(request.offerId).toBe('o1');
    expect(request.expectedTotal).toBe('425.00');
    expect(JSON.stringify(request)).not.toMatch(/note|contact|platform/i);
  });

  it('trims fields and upper-cases the country code', () => {
    const request = buildCheckoutRequest({
      listing: listing(),
      offer: null,
      address: { ...ADDRESS, recipient: '  Alex Rossi ', country: 'it', postalCode: ' 20121 ' },
    });
    expect(request.address).toEqual(ADDRESS);
  });

  it('fingerprints semantically identical requests identically and any change differently', () => {
    const base = buildCheckoutRequest({ listing: listing(), offer: null, address: ADDRESS });
    const same = buildCheckoutRequest({ listing: listing(), offer: null, address: { ...ADDRESS } });
    expect(checkoutFingerprint(same)).toBe(checkoutFingerprint(base));
    const variants = [
      buildCheckoutRequest({
        listing: listing(),
        offer: null,
        address: { ...ADDRESS, city: 'Rome' },
      }),
      buildCheckoutRequest({
        listing: listing({ price: '450.00' }),
        offer: null,
        address: ADDRESS,
      }),
      buildCheckoutRequest({ listing: listing(), offer: offer(), address: ADDRESS }),
      buildCheckoutRequest({ listing: listing({ id: 'l9' }), offer: null, address: ADDRESS }),
    ];
    for (const variant of variants) {
      expect(checkoutFingerprint(variant)).not.toBe(checkoutFingerprint(base));
    }
  });
});

describe('address rules', () => {
  it('allows exactly the 27 EU country codes the backend accepts, alphabetically by name', () => {
    expect(EU_COUNTRIES.map((country) => country.code).sort()).toEqual(
      [...EXPECTED_COUNTRIES].sort(),
    );
    const names = EU_COUNTRIES.map((country) => country.name);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'en')));
  });

  it('accepts a postal code example for every country and rejects another country format', () => {
    for (const country of EU_COUNTRIES) {
      expect(
        validateAddress({ ...ADDRESS, country: country.code, postalCode: country.example }),
      ).toEqual({});
    }
    expect(validateAddress({ ...ADDRESS, country: 'IT', postalCode: '2012' }).postalCode).toBe(
      'Enter a valid postal code, for example 00100.',
    );
    expect(validateAddress({ ...ADDRESS, country: 'NL', postalCode: '20121' }).postalCode).toMatch(
      /^Enter a valid postal code, for example /,
    );
  });

  it('rejects a country outside the allowlist and a missing country', () => {
    expect(validateAddress({ ...ADDRESS, country: 'US' }).country).toBe(
      'Choose a country from the list.',
    );
    expect(validateAddress({ ...ADDRESS, country: '' }).country).toBe('Choose a country.');
  });

  it('enforces each backend length limit at the boundary', () => {
    expect(ADDRESS_LIMITS).toEqual({ recipient: 150, street: 200, city: 100, postalCode: 20 });
    expect(validateAddress({ ...ADDRESS, recipient: 'r'.repeat(150) }).recipient).toBeUndefined();
    expect(validateAddress({ ...ADDRESS, recipient: 'r'.repeat(151) }).recipient).toBe(
      'Use at most 150 characters.',
    );
    expect(validateAddress({ ...ADDRESS, street: 's'.repeat(200) }).street).toBeUndefined();
    expect(validateAddress({ ...ADDRESS, street: 's'.repeat(201) }).street).toBe(
      'Use at most 200 characters.',
    );
    expect(validateAddress({ ...ADDRESS, city: 'c'.repeat(100) }).city).toBeUndefined();
    expect(validateAddress({ ...ADDRESS, city: 'c'.repeat(101) }).city).toBe(
      'Use at most 100 characters.',
    );
  });

  it('reports 21 postal characters as too long while 20 fail only the country pattern', () => {
    expect(validateAddress({ ...ADDRESS, postalCode: '1'.repeat(21) }).postalCode).toBe(
      'Use at most 20 characters.',
    );
    expect(validateAddress({ ...ADDRESS, postalCode: '1'.repeat(20) }).postalCode).toMatch(
      /^Enter a valid postal code/,
    );
  });

  it('requires every field and trims whitespace-only input to empty', () => {
    expect(
      validateAddress({ recipient: ' ', street: '', city: '  ', postalCode: '', country: '' }),
    ).toEqual({
      recipient: 'Enter the recipient name.',
      street: 'Enter the street address.',
      city: 'Enter the city.',
      postalCode: 'Enter the postal code.',
      country: 'Choose a country.',
    });
  });
});

describe('idempotency key lifecycle', () => {
  let orders: { checkout: Mock<CheckoutFn> };

  beforeEach(() => {
    sessionStorage.clear();
    orders = { checkout: vi.fn<CheckoutFn>(() => of(CREATED)) };
    TestBed.configureTestingModule({ providers: [{ provide: OrderApi, useValue: orders }] });
  });
  afterEach(() => TestBed.resetTestingModule());

  const request = (address: Partial<AddressForm> = {}) =>
    buildCheckoutRequest({ listing: listing(), offer: null, address: { ...ADDRESS, ...address } });
  const keyOf = (call: number) => orders.checkout.mock.calls[call][1] as string;

  it('posts the request with a generated Idempotency-Key', async () => {
    await TestBed.inject(CheckoutService).submit(request());
    expect(orders.checkout).toHaveBeenCalledTimes(1);
    expect(orders.checkout.mock.calls[0][0]).toEqual(request());
    expect(keyOf(0)).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('reuses the key when the identical request is retried after a failure without a response', async () => {
    orders.checkout.mockImplementationOnce(() => throwError(() => appError(0, 'offline')));
    const service = TestBed.inject(CheckoutService);
    await expect(service.submit(request())).rejects.toMatchObject({ status: 0 });
    await service.submit(request());
    expect(keyOf(1)).toBe(keyOf(0));
  });

  it('keeps the key across a reload because it lives in sessionStorage', async () => {
    orders.checkout.mockImplementationOnce(() =>
      throwError(() => appError(503, 'checkout-outcome-unknown')),
    );
    await expect(TestBed.inject(CheckoutService).submit(request())).rejects.toBeDefined();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: OrderApi, useValue: orders }] });
    await TestBed.inject(CheckoutService).submit(request());
    expect(keyOf(1)).toBe(keyOf(0));
  });

  it('generates a new key when the address, price or offer changes', async () => {
    orders.checkout.mockImplementation(() => throwError(() => appError(0, 'offline')));
    const service = TestBed.inject(CheckoutService);
    const attempts = [
      request(),
      request({ city: 'Rome' }),
      buildCheckoutRequest({
        listing: listing({ price: '450.00' }),
        offer: null,
        address: ADDRESS,
      }),
      buildCheckoutRequest({ listing: listing(), offer: offer(), address: ADDRESS }),
    ];
    for (const attempt of attempts) await service.submit(attempt).catch(() => undefined);
    const keys = new Set(attempts.map((_, index) => keyOf(index)));
    expect(keys.size).toBe(attempts.length);
  });

  it('rotates the key once a definite response arrives', async () => {
    const service = TestBed.inject(CheckoutService);
    await service.submit(request());
    await service.submit(request());
    expect(keyOf(1)).not.toBe(keyOf(0));
  });

  it('persists the order correlation before returning to the caller', async () => {
    await TestBed.inject(CheckoutService).submit(
      buildCheckoutRequest({ listing: listing(), offer: offer(), address: ADDRESS }),
    );
    const saved = JSON.parse(sessionStorage.getItem(PENDING_CHECKOUT_KEY) ?? 'null');
    expect(saved).toMatchObject({ orderId: 'ord1', listingId: 'l1', offerId: 'o1' });
    expect(typeof saved.startedAt).toBe('string');
    expect(TestBed.inject(PendingCheckoutStore).read()?.orderId).toBe('ord1');
  });
});

describe('approval URL validation', () => {
  it.each([
    'https://www.paypal.com/checkoutnow?token=T1',
    'https://www.sandbox.paypal.com/checkoutnow?token=T1',
    'https://sandbox.paypal.com/checkoutnow?token=T1',
    'https://paypal.com/checkoutnow?token=T1',
    'HTTPS://WWW.PAYPAL.COM/checkoutnow?token=T1',
  ])('accepts %s', (url) => {
    expect(approvalTarget(url)).toBe(new URL(url).href);
  });

  it.each([
    ['plain http', 'http://www.paypal.com/checkoutnow?token=T1'],
    ['suffix lookalike', 'https://paypal.com.evil.test/checkoutnow'],
    ['prefix lookalike', 'https://evilpaypal.com/checkoutnow'],
    ['dash lookalike', 'https://www.paypal.com-secure.test/checkoutnow'],
    ['userinfo trick', 'https://www.paypal.com@evil.test/checkoutnow'],
    ['credentials on the real host', 'https://user:secret@www.paypal.com/checkoutnow'],
    ['custom port', 'https://www.paypal.com:8443/checkoutnow'],
    ['trailing dot host', 'https://www.paypal.com./checkoutnow'],
    ['script scheme', 'javascript:alert(1)'],
    ['data scheme', 'data:text/html,<script>alert(1)</script>'],
    ['protocol relative', '//www.paypal.com/checkoutnow'],
    ['relative path', '/checkoutnow'],
    ['garbage', 'not a url'],
    ['empty', ''],
  ])('rejects %s', (_label, url) => {
    expect(approvalTarget(url)).toBeNull();
  });

  it('rejects null and undefined', () => {
    expect(approvalTarget(null)).toBeNull();
    expect(approvalTarget(undefined)).toBeNull();
  });
});

describe('checkout outcomes', () => {
  let orders: { checkout: Mock<CheckoutFn> };

  beforeEach(() => {
    sessionStorage.clear();
    orders = { checkout: vi.fn<CheckoutFn>(() => of(CREATED)) };
    TestBed.configureTestingModule({ providers: [{ provide: OrderApi, useValue: orders }] });
  });
  afterEach(() => TestBed.resetTestingModule());

  const request = () => buildCheckoutRequest({ listing: listing(), offer: null, address: ADDRESS });
  const submitWith = (response: CheckoutResponse) => {
    orders.checkout.mockReturnValue(of(response));
    return TestBed.inject(CheckoutService).submit(request());
  };
  const saved = () => JSON.parse(sessionStorage.getItem(PENDING_CHECKOUT_KEY) ?? 'null');

  it('redirects a new awaiting order to its validated approval URL and stores it', async () => {
    const outcome = await submitWith(CREATED);
    expect(outcome).toEqual({
      kind: 'redirect',
      orderId: 'ord1',
      approvalUrl: CREATED.approvalUrl,
    });
    expect(saved()).toMatchObject({ orderId: 'ord1', approvalUrl: CREATED.approvalUrl });
  });

  it('blocks an awaiting order whose approval URL is on an untrusted host but keeps the order', async () => {
    const outcome = await submitWith({
      ...CREATED,
      approvalUrl: 'https://paypal.com.evil.test/checkoutnow?token=T1',
    });
    expect(outcome).toEqual({ kind: 'blocked', orderId: 'ord1' });
    expect(saved()).toMatchObject({ orderId: 'ord1', listingId: 'l1' });
    expect(saved().approvalUrl).toBeUndefined();
  });

  it('blocks an awaiting order with no approval URL', async () => {
    expect(await submitWith({ ...CREATED, approvalUrl: null })).toEqual({
      kind: 'blocked',
      orderId: 'ord1',
    });
    expect(saved().approvalUrl).toBeUndefined();
  });

  it.each(['CANCELLED', 'PAID', 'SHIPPED', 'DELIVERED', 'REFUNDED'] as const)(
    'treats an idempotent replay with status %s as authoritative and never redirects',
    async (status) => {
      const outcome = await submitWith({
        orderId: 'ord1',
        approvalUrl: CREATED.approvalUrl,
        status,
      });
      expect(outcome).toEqual({ kind: 'order', orderId: 'ord1', status });
      expect(saved()).toBeNull();
    },
  );

  it('clears a stale correlation for the replayed order', async () => {
    TestBed.inject(PendingCheckoutStore).save({
      orderId: 'ord1',
      listingId: 'l1',
      startedAt: '2026-10-08T10:00:00Z',
    });
    await submitWith({ orderId: 'ord1', approvalUrl: null, status: 'CANCELLED' });
    expect(saved()).toBeNull();
  });
});

describe('checkout page', () => {
  let narrow: BehaviorSubject<{ matches: boolean; breakpoints: Record<string, boolean> }>;

  beforeEach(() => {
    sessionStorage.clear();
    narrow = new BehaviorSubject<{ matches: boolean; breakpoints: Record<string, boolean> }>({
      matches: false,
      breakpoints: {},
    });
  });
  afterEach(() => TestBed.resetTestingModule());

  interface Options {
    listing?: ListingDetailResponse | Error;
    offer?: OfferView | Error;
    purchases?: OrderView[];
    checkout?: Mock<CheckoutFn>;
    identity?: AuthIdentity;
    listings?: ListingDetailResponse[];
    awaiting?: OrderView;
    fallbackMs?: number;
    demoCheckoutEnabled?: boolean;
  }

  async function open(url: string, options: Options = {}) {
    const adapter = new FakeAuthAdapter(
      options.identity ?? { sub: BUYER, email: 'buyer@example.test', groups: [] },
    );
    let listingCalls = 0;
    const catalog = {
      listing: vi.fn(() => {
        if (options.listings) {
          return of(options.listings[Math.min(listingCalls++, options.listings.length - 1)]);
        }
        return options.listing instanceof Error
          ? throwError(() => options.listing)
          : of(options.listing ?? listing());
      }),
    };
    const navigate = vi.fn<(url: string) => void>();
    const orders = {
      checkout: options.checkout ?? vi.fn(() => of(CREATED)),
      offer: vi.fn(() =>
        options.offer instanceof Error
          ? throwError(() => options.offer)
          : of(options.offer ?? offer()),
      ),
      order: vi.fn(() => of(options.awaiting ?? order({ id: 'ord1', status: 'AWAITING_PAYMENT' }))),
      purchases: vi.fn(() => of({ items: options.purchases ?? [], nextCursor: null })),
    };
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideRouter([
          { path: 'checkout/:listingId', loadChildren: () => CHECKOUT_ROUTES },
          { path: 'listings/:id', component: ElsewherePage },
          { path: 'orders/:id', component: ElsewherePage },
          { path: 'orders', component: ElsewherePage },
          { path: '', component: ElsewherePage },
        ]),
        { provide: AuthAdapter, useValue: adapter },
        { provide: CatalogApi, useValue: catalog },
        { provide: OrderApi, useValue: orders },
        { provide: BreakpointObserver, useValue: { observe: () => narrow } },
        { provide: CHECKOUT_NAVIGATOR, useValue: navigate },
        { provide: CHECKOUT_FALLBACK_DELAY_MS, useValue: options.fallbackMs ?? 4000 },
      ],
    });
    TestBed.overrideProvider(AppConfigService, {
      useValue: {
        config: { ...TEST_APP_CONFIG, demoCheckoutEnabled: options.demoCheckoutEnabled ?? false },
      },
    });
    await TestBed.inject(AuthStore).init();
    const harness = await RouterTestingHarness.create();
    const page = (await harness.navigateByUrl(url)) as CheckoutPage;
    await settle(harness);
    const root = harness.routeNativeElement as HTMLElement;
    return { harness, root, page, catalog, orders, navigate };
  }

  async function settle(harness: RouterTestingHarness) {
    for (let attempt = 0; attempt < 6; attempt++) {
      await harness.fixture.whenStable();
      harness.detectChanges();
    }
  }

  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const byId = <T extends HTMLElement>(root: HTMLElement, id: string) =>
    root.querySelector<T>('#' + id);
  const payButton = (root: HTMLElement) =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((candidate) =>
      text(candidate).startsWith('Pay with PayPal'),
    );

  function type(harness: RouterTestingHarness, root: HTMLElement, id: string, value: string) {
    const input = byId<HTMLInputElement>(root, id);
    if (!input) throw new Error('missing field #' + id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
  }

  function choose(harness: RouterTestingHarness, root: HTMLElement, id: string, value: string) {
    const select = byId<HTMLSelectElement>(root, id);
    if (!select) throw new Error('missing select #' + id);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    harness.detectChanges();
  }

  function fill(harness: RouterTestingHarness, root: HTMLElement, address = ADDRESS) {
    type(harness, root, 'checkout-recipient', address.recipient);
    type(harness, root, 'checkout-street', address.street);
    type(harness, root, 'checkout-postal-code', address.postalCode);
    type(harness, root, 'checkout-city', address.city);
    choose(harness, root, 'checkout-country', address.country);
  }

  async function pay(harness: RouterTestingHarness, root: HTMLElement) {
    payButton(root)?.click();
    await settle(harness);
  }

  it('resolves /checkout/:listingId to CheckoutPage with the inherited listing id', async () => {
    const { page } = await open('/checkout/l1');
    expect(page).toBeInstanceOf(CheckoutPage);
    expect(page.listingId).toBe('l1');
  });

  it('shows safe sandbox card guidance only when the demo flag is enabled', async () => {
    const { root } = await open('/checkout/l1', { demoCheckoutEnabled: true });
    const note = root.querySelector('.demo-checkout-note');
    expect(text(note)).toContain('PayPal sandbox');
    expect(text(note)).toContain('no real money');
    expect(text(note)).toContain('Pay with a card');
    const link = note?.querySelector('a');
    expect(link?.getAttribute('href')).toBe(
      'https://developer.paypal.com/credit-card-number-generator',
    );
    expect(text(link ?? null)).toContain('PayPal sandbox card generator');
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('omits sandbox guidance when the demo flag is disabled', async () => {
    const { root } = await open('/checkout/l1', { demoCheckoutEnabled: false });
    expect(root.querySelector('.demo-checkout-note')).toBeNull();
  });

  it('shows the hardware address section, summary and total', async () => {
    const { root } = await open('/checkout/l1');
    for (const id of [
      'checkout-recipient',
      'checkout-street',
      'checkout-postal-code',
      'checkout-city',
      'checkout-country',
    ]) {
      expect(byId(root, id), id).not.toBeNull();
    }
    expect(root.querySelectorAll('select#checkout-country option').length).toBe(28);
    expect(text(root)).toContain('Ships from Turin');
    expect(text(root)).toContain('€504.00');
    expect(text(payButton(root)!)).toBe('Pay with PayPal €504.00');
  });

  it('posts a hardware order with the exact five-field address and a key', async () => {
    const checkout = vi.fn<CheckoutFn>(() => of(CREATED));
    const { harness, root } = await open('/checkout/l1', { checkout });
    fill(harness, root);
    await pay(harness, root);
    expect(checkout).toHaveBeenCalledTimes(1);
    const [body, key] = checkout.mock.calls[0] as [CheckoutRequest, string];
    expect(body).toEqual({
      listingId: 'l1',
      method: 'PAYPAL',
      expectedTotal: '504.00',
      address: ADDRESS,
    });
    expect(Object.keys(body.address!).sort()).toEqual(
      ['city', 'country', 'postalCode', 'recipient', 'street'].sort(),
    );
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('shows no address section for software and omits the address key', async () => {
    const checkout = vi.fn<CheckoutFn>(() => of({ ...CREATED, orderId: 'ord2' }));
    const { harness, root } = await open('/checkout/l2', { listing: software(), checkout });
    expect(byId(root, 'checkout-recipient')).toBeNull();
    expect(text(root)).toContain('Instant digital delivery');
    expect(text(payButton(root)!)).toBe('Pay with PayPal €29.90');
    await pay(harness, root);
    const body = checkout.mock.calls[0][0];
    expect(body).toEqual({ listingId: 'l2', method: 'PAYPAL', expectedTotal: '29.90' });
    expect('address' in body).toBe(false);
  });

  it('checks out an accepted offer with offerId and the offer amount in the total', async () => {
    const checkout = vi.fn<CheckoutFn>(() => of(CREATED));
    const { harness, root, orders } = await open('/checkout/l1?offer=o1', { checkout });
    expect(orders.offer).toHaveBeenCalledWith('o1');
    expect(text(root)).toContain('Offer accepted');
    expect(text(root)).toContain('Your offer of €420.00 was accepted');
    expect(text(root.querySelector('.countdown'))).toMatch(/^(59 min \d{2} s|1 h 00 min) left$/);
    expect(text(payButton(root)!)).toBe('Pay with PayPal €425.00');
    fill(harness, root);
    await pay(harness, root);
    const body = checkout.mock.calls[0][0];
    expect(body.offerId).toBe('o1');
    expect(body.expectedTotal).toBe('425.00');
  });

  it('formats 0.01 and a high two-decimal total from the strings', async () => {
    const cheap = await open('/checkout/l2', {
      listing: software({ price: '0.01' }),
    });
    expect(text(payButton(cheap.root)!)).toBe('Pay with PayPal €0.01');
    TestBed.resetTestingModule();
    const high = await open('/checkout/l1', {
      listing: listing({ price: '99999999.98', shippingCost: '0.01' }),
    });
    expect(text(payButton(high.root)!)).toBe('Pay with PayPal €99,999,999.99');
  });

  it('validates every field on submit, focuses the first invalid one and sends nothing', async () => {
    const checkout = vi.fn<CheckoutFn>(() => of(CREATED));
    const { harness, root } = await open('/checkout/l1', { checkout });
    await pay(harness, root);
    expect(checkout).not.toHaveBeenCalled();
    expect(text(root)).toContain('Fix 5 fields to continue');
    expect(text(byId(root, 'checkout-recipient-error'))).toBe('Enter the recipient name.');
    expect(document.activeElement).toBe(byId(root, 'checkout-recipient'));
  });

  it('keeps the attention count in step with the fields still invalid', async () => {
    const { harness, root } = await open('/checkout/l1');
    await pay(harness, root);
    type(harness, root, 'checkout-recipient', ADDRESS.recipient);
    type(harness, root, 'checkout-street', ADDRESS.street);
    type(harness, root, 'checkout-city', ADDRESS.city);
    expect(text(root)).toContain('Fix 2 fields to continue');
    type(harness, root, 'checkout-postal-code', ADDRESS.postalCode);
    choose(harness, root, 'checkout-country', ADDRESS.country);
    expect(text(root)).not.toContain('to continue');
  });

  it('ignores a second press while the first request is in flight', async () => {
    const pending = new Subject<CheckoutResponse>();
    const checkout = vi.fn<CheckoutFn>(() => pending);
    const { harness, root } = await open('/checkout/l1', { checkout });
    fill(harness, root);
    payButton(root)?.click();
    harness.detectChanges();
    payButton(root)?.click();
    payButton(root)?.click();
    await settle(harness);
    expect(checkout).toHaveBeenCalledTimes(1);
    pending.next(CREATED);
    pending.complete();
    await settle(harness);
  });

  it('retries with the same key after a network error and a new key after an address edit', async () => {
    const checkout = vi.fn<CheckoutFn>(() => throwError(() => appError(0, 'offline')));
    const { harness, root } = await open('/checkout/l1', { checkout });
    fill(harness, root);
    await pay(harness, root);
    await pay(harness, root);
    expect(checkout).toHaveBeenCalledTimes(2);
    expect(checkout.mock.calls[1][1]).toBe(checkout.mock.calls[0][1]);
    type(harness, root, 'checkout-city', 'Rome');
    await pay(harness, root);
    expect(checkout.mock.calls[2][1]).not.toBe(checkout.mock.calls[0][1]);
  });

  it('records the order correlation in sessionStorage after a successful checkout', async () => {
    const { harness, root } = await open('/checkout/l1?offer=o1');
    fill(harness, root);
    await pay(harness, root);
    expect(JSON.parse(sessionStorage.getItem(PENDING_CHECKOUT_KEY) ?? 'null')).toMatchObject({
      orderId: 'ord1',
      listingId: 'l1',
      offerId: 'o1',
    });
  });
  describe('PayPal hand-off', () => {
    const goTo = (navigate: ReturnType<typeof vi.fn>) => navigate.mock.calls.map((call) => call[0]);

    it('stores the correlation, then sends the browser to the validated PayPal URL', async () => {
      const { harness, root, navigate } = await open('/checkout/l1');
      fill(harness, root);
      await pay(harness, root);
      expect(goTo(navigate)).toEqual([CREATED.approvalUrl]);
      expect(JSON.parse(sessionStorage.getItem(PENDING_CHECKOUT_KEY) ?? 'null')).toMatchObject({
        orderId: 'ord1',
        approvalUrl: CREATED.approvalUrl,
      });
      expect(text(root)).toContain('Redirecting to PayPal...');
      expect(root.querySelector('#checkout-recipient')).toBeNull();
    });

    it('shows a visible Continue to PayPal link only after the delay', async () => {
      const { harness, root } = await open('/checkout/l1', { fallbackMs: 40 });
      fill(harness, root);
      await pay(harness, root);
      const link = () =>
        Array.from(root.querySelectorAll<HTMLAnchorElement>('a')).find(
          (candidate) => text(candidate) === 'Continue to PayPal',
        );
      expect(link()).toBeUndefined();
      await new Promise((resolve) => setTimeout(resolve, 90));
      await settle(harness);
      expect(link()?.href).toBe(CREATED.approvalUrl);
    });

    it('never assigns an untrusted approval URL and offers the order instead', async () => {
      const checkout = vi.fn<CheckoutFn>(() =>
        of({ ...CREATED, approvalUrl: 'https://www.paypal.com.evil.test/pay' }),
      );
      const { harness, root, navigate } = await open('/checkout/l1', { checkout });
      fill(harness, root);
      await pay(harness, root);
      expect(navigate).not.toHaveBeenCalled();
      expect(text(root)).toContain("We couldn't open PayPal safely");
      expect(root.querySelector('a[href="/orders/ord1"]')).not.toBeNull();
      expect(text(root)).not.toContain('Continue to PayPal');
    });

    it('treats a missing approval URL on an awaiting order as a blocking error', async () => {
      const checkout = vi.fn<CheckoutFn>(() => of({ ...CREATED, approvalUrl: null }));
      const { harness, root, navigate } = await open('/checkout/l1', { checkout });
      fill(harness, root);
      await pay(harness, root);
      expect(navigate).not.toHaveBeenCalled();
      expect(text(root)).toContain("We couldn't open PayPal safely");
      expect(root.querySelector('a[href="/orders/ord1"]')).not.toBeNull();
    });

    it('renders a cancelled replay as a status with an order link and does not redirect', async () => {
      const checkout = vi.fn<CheckoutFn>(() =>
        of({ orderId: 'ord1', approvalUrl: null, status: 'CANCELLED' as const }),
      );
      const { harness, root, navigate } = await open('/checkout/l1', { checkout });
      fill(harness, root);
      await pay(harness, root);
      expect(navigate).not.toHaveBeenCalled();
      expect(text(root)).toContain('This order was cancelled.');
      expect(root.querySelector('a[href="/orders/ord1"]')).not.toBeNull();
      expect(sessionStorage.getItem(PENDING_CHECKOUT_KEY)).toBeNull();
    });

    it('renders a paid replay as paid even though the response still carries a URL', async () => {
      const checkout = vi.fn<CheckoutFn>(() =>
        of({ orderId: 'ord1', approvalUrl: CREATED.approvalUrl, status: 'PAID' as const }),
      );
      const { harness, root, navigate } = await open('/checkout/l1', { checkout });
      fill(harness, root);
      await pay(harness, root);
      expect(navigate).not.toHaveBeenCalled();
      expect(text(root)).toContain('This order is already paid.');
      expect(root.querySelector('a[href="/orders/ord1"]')).not.toBeNull();
    });

    it('restores an awaiting order after a reload instead of showing the form', async () => {
      sessionStorage.setItem(
        PENDING_CHECKOUT_KEY,
        JSON.stringify({
          orderId: 'ord1',
          listingId: 'l1',
          startedAt: '2026-10-08T10:00:00Z',
          approvalUrl: CREATED.approvalUrl,
        }),
      );
      const { harness, root, navigate, orders } = await open('/checkout/l1');
      expect(orders.order).toHaveBeenCalledWith('ord1');
      expect(root.querySelector('#checkout-recipient')).toBeNull();
      expect(text(root)).toContain('You already started this checkout');
      expect(root.querySelector('a[href="/orders/ord1"]')).not.toBeNull();
      const resume = Array.from(root.querySelectorAll('button')).find(
        (candidate) => text(candidate) === 'Continue to PayPal',
      );
      resume?.click();
      await settle(harness);
      expect(goTo(navigate)).toEqual([CREATED.approvalUrl]);
    });

    it('does not resume to a stored approval URL that fails validation', async () => {
      sessionStorage.setItem(
        PENDING_CHECKOUT_KEY,
        JSON.stringify({
          orderId: 'ord1',
          listingId: 'l1',
          startedAt: '2026-10-08T10:00:00Z',
          approvalUrl: 'https://paypal.com.evil.test/pay',
        }),
      );
      const { root } = await open('/checkout/l1');
      expect(text(root)).toContain('You already started this checkout');
      expect(
        Array.from(root.querySelectorAll('button')).some(
          (candidate) => text(candidate) === 'Continue to PayPal',
        ),
      ).toBe(false);
      expect(root.querySelector('a[href="/orders/ord1"]')).not.toBeNull();
    });

    it('drops a stored correlation whose order was cancelled and shows the form again', async () => {
      sessionStorage.setItem(
        PENDING_CHECKOUT_KEY,
        JSON.stringify({ orderId: 'ord1', listingId: 'l1', startedAt: '2026-10-08T10:00:00Z' }),
      );
      const { root } = await open('/checkout/l1', {
        awaiting: order({ id: 'ord1', status: 'CANCELLED' }),
      });
      expect(root.querySelector('#checkout-recipient')).not.toBeNull();
      expect(sessionStorage.getItem(PENDING_CHECKOUT_KEY)).toBeNull();
    });

    it('shows a paid stored order as paid', async () => {
      sessionStorage.setItem(
        PENDING_CHECKOUT_KEY,
        JSON.stringify({ orderId: 'ord1', listingId: 'l1', startedAt: '2026-10-08T10:00:00Z' }),
      );
      const { root } = await open('/checkout/l1', {
        awaiting: order({ id: 'ord1', status: 'PAID' }),
      });
      expect(text(root)).toContain('This order is already paid.');
    });

    it('ignores a stored correlation that belongs to another listing or offer', async () => {
      sessionStorage.setItem(
        PENDING_CHECKOUT_KEY,
        JSON.stringify({ orderId: 'ordX', listingId: 'other', startedAt: '2026-10-08T10:00:00Z' }),
      );
      const other = await open('/checkout/l1');
      expect(other.root.querySelector('#checkout-recipient')).not.toBeNull();
      TestBed.resetTestingModule();
      sessionStorage.setItem(
        PENDING_CHECKOUT_KEY,
        JSON.stringify({ orderId: 'ord1', listingId: 'l1', startedAt: '2026-10-08T10:00:00Z' }),
      );
      const withOffer = await open('/checkout/l1?offer=o1');
      expect(withOffer.root.querySelector('#checkout-recipient')).not.toBeNull();
    });
  });

  describe('server errors', () => {
    async function failWith(error: AppError, options: Options = {}) {
      const checkout = vi.fn<CheckoutFn>(() => throwError(() => error));
      const view = await open('/checkout/l1', { checkout, ...options });
      fill(view.harness, view.root);
      await pay(view.harness, view.root);
      return { ...view, checkout };
    }

    it('refreshes the listing and the total after price-mismatch', async () => {
      const { root, catalog } = await failWith(
        appError(409, 'price-mismatch', { currentTotal: '510.00' }),
        { listings: [listing(), listing({ price: '505.00' })] },
      );
      expect(text(root)).toContain('The total changed to €510.00. Review it and try again.');
      expect(catalog.listing).toHaveBeenCalledTimes(2);
      expect(text(payButton(root)!)).toBe('Pay with PayPal €510.00');
      expect(byId<HTMLInputElement>(root, 'checkout-recipient')?.value).toBe('Alex Rossi');
    });

    it('sends the new total with a new key after price-mismatch', async () => {
      const checkout = vi
        .fn<CheckoutFn>()
        .mockReturnValueOnce(
          throwError(() => appError(409, 'price-mismatch', { currentTotal: '510.00' })),
        )
        .mockReturnValue(of(CREATED));
      const { harness, root } = await open('/checkout/l1', {
        checkout,
        listings: [listing(), listing({ price: '505.00' })],
      });
      fill(harness, root);
      await pay(harness, root);
      await pay(harness, root);
      expect(checkout.mock.calls[1][0].expectedTotal).toBe('510.00');
      expect(checkout.mock.calls[1][1]).not.toBe(checkout.mock.calls[0][1]);
    });

    it('shows the unavailable state for listing-unavailable', async () => {
      const { root } = await failWith(appError(409, 'listing-unavailable'));
      expect(text(root)).toContain('This item is no longer available.');
      expect(byId(root, 'checkout-recipient')).toBeNull();
    });

    it('maps the 503 codes to their own copy instead of the generic one', async () => {
      const unavailable = await failWith(appError(503, 'payment-unavailable'));
      expect(text(unavailable.root)).toContain(
        "PayPal is not responding. You haven't been charged. Try again in a minute.",
      );
      TestBed.resetTestingModule();
      const unknown = await failWith(appError(503, 'checkout-outcome-unknown'));
      expect(text(unknown.root)).toContain(
        "We couldn't confirm your order. Check your orders before trying again.",
      );
    });

    it('links checkout-outcome-unknown to the orders list and keeps the key for the retry', async () => {
      const { harness, root, checkout } = await failWith(appError(503, 'checkout-outcome-unknown'));
      await pay(harness, root);
      expect(checkout.mock.calls[1][1]).toBe(checkout.mock.calls[0][1]);
      Array.from(root.querySelectorAll('button'))
        .find((candidate) => text(candidate) === 'Your orders')
        ?.click();
      await settle(harness);
      expect(TestBed.inject(Router).url).toBe('/orders');
    });

    it('uses a fresh key on the next attempt after idempotency-key-conflict', async () => {
      const { harness, root, checkout } = await failWith(appError(409, 'idempotency-key-conflict'));
      expect(text(root)).toContain('Something changed since your last attempt.');
      await pay(harness, root);
      expect(checkout.mock.calls[1][1]).not.toBe(checkout.mock.calls[0][1]);
    });

    it('shows invalid-shipping-address on the postal code field and focuses it', async () => {
      const { root } = await failWith(appError(422, 'invalid-shipping-address'));
      expect(text(byId(root, 'checkout-postal-code-error'))).toBe(
        'Check the postal code for the selected country.',
      );
      expect(document.activeElement).toBe(byId(root, 'checkout-postal-code'));
    });

    it('maps validation-failed address fields onto their inputs', async () => {
      const error = {
        ...appError(400, 'validation-failed'),
        fieldErrors: { 'address.city': 'must not be blank' },
      };
      const { root } = await failWith(error);
      expect(text(byId(root, 'checkout-city-error'))).toBe('Check the city.');
    });

    it('explains self-purchase-forbidden', async () => {
      const { root } = await failWith(appError(403, 'self-purchase-forbidden'));
      expect(text(root)).toContain("You can't buy your own listing.");
    });
  });
});
