import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER, Observable, of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import { OrderStatus, OrderView, ShipOrderRequest } from '../../core/api/api-models';
import { OrderApi } from '../../core/api/order-api';
import { CatalogApi } from '../../core/api/catalog-api';
import { LicenceCard } from './licence-card';
import {
  OrderExtras,
  ProblemCard,
  validateReport,
  validateReview,
  validateRefund,
} from './problem-card';
import { AuthAdapter, AuthEvent, AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { RouteStubPage } from '../../core/shell/route-stub.page';
import { ToastService } from '../../shared/ui/feedback';
import { CHECKOUT_NAVIGATOR } from '../checkout/checkout.service';
import { PENDING_CHECKOUT_KEY } from '../checkout/pending-checkout.store';
import { OrderDetailPage } from './order-detail.page';
import { ORDER_DETAIL_ROUTES } from './order-detail.routes';
import {
  buildShipRequest,
  lifecycleActions,
  OrderDetailService,
  validateShipping,
} from './order-detail.service';

const BUYER = 'buyer-sub';
const SELLER = 'seller-sub';
const ADMIN = 'admin-sub';

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

function appError(status: number, code: string): AppError {
  return { status, code, type: 'urn:rebyte:problem:' + code, fieldErrors: {}, extras: {} };
}

const ADDRESS = {
  recipient: 'Alex Rossi',
  street: 'Via Giuseppe Verdi 12',
  city: 'Milan',
  postalCode: '20121',
  country: 'IT',
};

function order(overrides: Partial<OrderView> = {}): OrderView {
  return {
    id: 'o1',
    listingId: 'l1',
    listingTitle: 'ThinkPad X1',
    listingType: 'HARDWARE',
    buyerSub: BUYER,
    sellerSub: SELLER,
    itemPrice: '499.00',
    shippingCost: '5.00',
    pricePaid: '504.00',
    paymentMethod: 'PAYPAL',
    status: 'PAID',
    createdAt: '2026-10-01T10:00:00Z',
    expiryAt: '2026-10-01T10:30:00Z',
    shippingAddress: ADDRESS,
    paidAt: '2026-10-01T10:05:00Z',
    ...overrides,
  };
}

function shipped(overrides: Partial<OrderView> = {}): OrderView {
  return order({
    status: 'SHIPPED',
    shippedAt: '2026-10-02T09:00:00Z',
    carrier: 'DPD',
    trackingCode: 'DPD 1234/AB-9',
    ...overrides,
  });
}

function software(overrides: Partial<OrderView> = {}): OrderView {
  const { shippingAddress: _address, ...rest } = order();
  return {
    ...rest,
    id: 'o2',
    listingId: 'l2',
    listingTitle: 'Photo Editor',
    listingType: 'SOFTWARE',
    itemPrice: '29.90',
    shippingCost: '0.00',
    pricePaid: '29.90',
    status: 'DELIVERED',
    deliveredAt: '2026-10-01T10:05:01Z',
    ...overrides,
  };
}

interface Options {
  identity?: AuthIdentity;
  responses?: Record<string, Observable<OrderView>>;
  order?: OrderView | Observable<OrderView>;
  sequence?: Observable<OrderView>[];
  ship?: Observable<OrderView>;
  deliver?: Observable<OrderView>;
  cancel?: Observable<void>;
  versions?: Observable<any[]>;
  download?: Observable<any>;
  report?: Observable<any>;
  withdraw?: Observable<any>;
  refund?: Observable<any>;
  review?: Observable<any>;
}

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

async function open(url: string, options: Options = {}) {
  const adapter = new FakeAuthAdapter(
    options.identity ?? { sub: BUYER, email: 'buyer@example.test', groups: [] },
  );
  let calls = 0;
  const orders = {
    order: vi.fn((id: string): Observable<OrderView> => {
      if (options.sequence) return options.sequence[Math.min(calls++, options.sequence.length - 1)];
      const response = options.responses?.[id];
      if (response) return response;
      const single = options.order ?? order({ id });
      return single instanceof Observable ? single : of(single);
    }),
    shipOrder: vi.fn(
      (_id: string, _request: ShipOrderRequest): Observable<OrderView> =>
        options.ship ?? of(shipped()),
    ),
    deliverOrder: vi.fn(
      (_id: string): Observable<OrderView> =>
        options.deliver ?? of(shipped({ status: 'DELIVERED' })),
    ),
    cancelOrder: vi.fn((_id: string): Observable<void> => options.cancel ?? of(undefined)),
    reportProblem: vi.fn(() => options.report ?? of({})),
    withdrawProblem: vi.fn(() => options.withdraw ?? of({})),
    refundProblem: vi.fn(() => options.refund ?? of({})),
    createReview: vi.fn(() => options.review ?? of({})),
  };
  const catalog = {
    versions: vi.fn(
      () =>
        options.versions ??
        of([
          {
            number: '1.0',
            release: '2026-01-01T00:00:00Z',
            notes: null,
            artifactAvailable: true,
            current: false,
          },
          {
            number: '2.0',
            release: '2026-02-01T00:00:00Z',
            notes: null,
            artifactAvailable: true,
            current: true,
          },
        ]),
    ),
    artifactDownload: vi.fn(
      () =>
        options.download ??
        of({ url: 'https://downloads.example.test/app.zip', expiresAt: '2099-01-01T00:00:00Z' }),
    ),
  };
  const navigate = vi.fn<(url: string) => void>();
  TestBed.configureTestingModule({
    providers: [
      provideTestAppConfig(),
      provideRouter([
        { path: 'orders/:id', loadChildren: () => ORDER_DETAIL_ROUTES },
        { path: 'orders', component: ElsewherePage },
        { path: 'listings/:id', component: ElsewherePage },
        { path: 'u/:sub', component: ElsewherePage },
        { path: '', component: ElsewherePage },
      ]),
      { provide: AuthAdapter, useValue: adapter },
      { provide: OrderApi, useValue: orders },
      { provide: CatalogApi, useValue: catalog },
      { provide: CHECKOUT_NAVIGATOR, useValue: navigate },
    ],
  });
  await TestBed.inject(AuthStore).init();
  const toast = vi.spyOn(TestBed.inject(ToastService), 'show');
  const harness = await RouterTestingHarness.create();
  const page = (await harness.navigateByUrl(url)) as OrderDetailPage;
  await settle(harness);
  return {
    harness,
    root: harness.routeNativeElement as HTMLElement,
    page,
    orders,
    catalog,
    toast,
    navigate,
  };
}

async function settle(harness: RouterTestingHarness) {
  for (let attempt = 0; attempt < 6; attempt++) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }
}

const moneyRows = (root: HTMLElement) =>
  Array.from(root.querySelectorAll('app-money-rows .money-row')).map((row) => [
    text(row.querySelector('dt')),
    text(row.querySelector('dd')),
  ]);

const steps = (root: HTMLElement) =>
  Array.from(root.querySelectorAll<HTMLElement>('ol.stepper > li')).map((item) => [
    text(item.querySelector('.step-label')),
    item.dataset['state'],
  ]);

describe('order optional workflows', () => {
  afterEach(() => TestBed.resetTestingModule());
  const license = {
    status: 'ACTIVE' as const,
    key: 'LOCAL-TEST-KEY',
    issuedAt: '2026-01-01T00:00:00Z',
    revokedAt: null,
  };
  const problem = (status: 'OPEN' | 'REFUND_PENDING' | 'WITHDRAWN' | 'RESOLVED' = 'OPEN') => ({
    id: 'p1',
    orderId: 'o1',
    reason: 'DAMAGED' as const,
    description: 'The case is damaged.',
    status,
    outcome: null,
    resolutionNote: null,
    createdAt: '2026-10-02T00:00:00Z',
    resolvedAt: null,
  });
  const extras = (h: RouterTestingHarness) => h.routeDebugElement!.injector.get(OrderExtras);
  const licence = (h: RouterTestingHarness) =>
    h.routeDebugElement!.query(By.directive(LicenceCard)).componentInstance as LicenceCard;

  it('hides the buyer key until requested and copies the returned value', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const { harness, root, toast } = await open('/orders/o2', { order: software({ license }) });
    expect(root.textContent).not.toContain(license.key);
    licence(harness).shown.set(true);
    await settle(harness);
    const copy = root.querySelector<HTMLButtonElement>('app-licence-card app-copy-field button')!;
    copy.click();
    await settle(harness);
    expect(writeText).toHaveBeenCalledWith(license.key);
    expect(toast).toHaveBeenCalledWith({ text: 'Licence key copied', kind: 'success' });
  });
  it.each([SELLER, ADMIN])('never displays a licence key or download for %s', async (sub) => {
    const { harness, root, catalog } = await open('/orders/o2', {
      order: software({ license }),
      identity: { sub, groups: sub === ADMIN ? ['ADMIN'] : [] },
    });
    expect(root.textContent).not.toContain(license.key);
    expect(root.querySelector('app-licence-card app-copy-field')).toBeNull();
    expect(catalog.versions).not.toHaveBeenCalled();
  });
  it('omits licence UI when the response omits it', async () => {
    const { root, catalog } = await open('/orders/o2', { order: software() });
    expect(root.querySelector('app-licence-card')).toBeNull();
    expect(catalog.versions).not.toHaveBeenCalled();
  });
  it('states a revoked licence once in the refund banner without a licence card', async () => {
    const { root, harness, catalog } = await open('/orders/o2', {
      order: software({
        status: 'REFUNDED',
        license: { ...license, status: 'REVOKED', revokedAt: '2026-10-03T00:00:00Z' },
      }),
    });
    expect(text(root)).toContain('This order was refunded and its licence was revoked.');
    expect(text(root)).not.toContain('once it is delivered');
    expect(root.textContent).not.toContain(license.key);
    expect(root.querySelector('app-licence-card section')).toBeNull();
    expect(catalog.versions).not.toHaveBeenCalled();
    licence(harness).download();
    await settle(harness);
    expect(catalog.artifactDownload).not.toHaveBeenCalled();
  });
  it('requests a fresh authorized link for the selected real version on each click', async () => {
    const { harness, catalog, navigate } = await open('/orders/o2', {
      order: software({ license }),
    });
    const card = licence(harness);
    card.version.set('1.0');
    card.download();
    await settle(harness);
    card.download();
    await settle(harness);
    expect(catalog.artifactDownload).toHaveBeenCalledTimes(2);
    expect(catalog.artifactDownload).toHaveBeenLastCalledWith('l2', '1.0');
    expect(navigate).toHaveBeenCalledWith('https://downloads.example.test/app.zip');
  });
  it.each([
    'http://example.test/a',
    'javascript:alert(1)',
    'https://u:p@example.test/a',
    '/relative',
  ])('rejects unsafe download URL %s', async (url) => {
    const { harness, navigate } = await open('/orders/o2', {
      order: software({ license }),
      download: of({ url, expiresAt: '2099-01-01T00:00:00Z' }),
    });
    licence(harness).download();
    await settle(harness);
    expect(navigate).not.toHaveBeenCalled();
  });
  it.each([
    'licence-required',
    'listing-moderated',
    'artifact-not-available',
    'order-service-unavailable',
  ])('maps download error %s safely', async (code) => {
    const { harness, root, navigate, orders } = await open('/orders/o2', {
      order: software({ license }),
      download: throwError(() => appError(403, code)),
    });
    licence(harness).download();
    await settle(harness);
    expect(root.querySelector('app-licence-card [role="alert"]')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
    expect(
      buttonsIn(root.querySelector('app-licence-card')).some(
        (button) => text(button) === 'Refresh versions',
      ),
    ).toBe(code === 'artifact-not-available');
    expect(orders.order).toHaveBeenCalledTimes(code === 'licence-required' ? 2 : 1);
  });
  it('cancels a download and removes licence data on logout', async () => {
    const response = new Subject<any>();
    const { harness, root, navigate } = await open('/orders/o2', {
      order: software({ license }),
      download: response,
    });
    licence(harness).download();
    expect(response.observed).toBe(true);
    TestBed.inject(AuthStore).clearSession();
    await settle(harness);
    expect(response.observed).toBe(false);
    response.next({ url: 'https://example.test/a', expiresAt: '2099-01-01T00:00:00Z' });
    expect(navigate).not.toHaveBeenCalled();
    expect(root.textContent).not.toContain(license.key);
  });
  it.each(['PAID', 'SHIPPED', 'DELIVERED'] as OrderStatus[])(
    'allows buyer reporting for %s',
    async (status) => {
      const { harness, orders, root } = await open('/orders/o1', { order: order({ status }) });
      expect(root.querySelector('.rail app-problem-card')?.textContent).toContain(
        'Report a problem',
      );
      extras(harness).report({ reason: 'DAMAGED', description: ' Damaged case ' });
      await settle(harness);
      expect(orders.reportProblem).toHaveBeenCalledWith('o1', {
        reason: 'DAMAGED',
        description: 'Damaged case',
      });
      expect(orders.order).toHaveBeenCalledTimes(2);
    },
  );
  it.each([SELLER, ADMIN])('denies substitute buyer actions for %s', async (sub) => {
    const { harness, root, orders } = await open('/orders/o1', {
      order: order({ status: 'DELIVERED' }),
      identity: { sub, groups: ['ADMIN'] },
    });
    extras(harness).report({ reason: 'DAMAGED', description: 'Damaged' });
    extras(harness).review({ rating: 5, comment: null });
    await settle(harness);
    expect(orders.reportProblem).not.toHaveBeenCalled();
    expect(orders.createReview).not.toHaveBeenCalled();
    expect(text(root)).not.toContain('Report a problem');
    expect(text(root)).not.toContain('Leave a review');
  });
  it.each(['OPEN', 'REFUND_PENDING', 'WITHDRAWN', 'RESOLVED'] as const)(
    'shows %s and limits withdraw/refund to supported transitions',
    async (status) => {
      const { harness, root, orders } = await open('/orders/o1', {
        order: order({ problem: problem(status) }),
      });
      expect(text(root)).not.toContain('Dismiss');
      expect(text(root)).not.toContain('Report a problem');
      extras(harness).withdraw();
      await settle(harness);
      expect(orders.withdrawProblem).toHaveBeenCalledTimes(status === 'OPEN' ? 1 : 0);
    },
  );
  it.each(['OPEN', 'REFUND_PENDING', 'WITHDRAWN', 'RESOLVED'] as const)(
    'seller refund availability for %s',
    async (status) => {
      const { harness, root, orders } = await open('/orders/o1', {
        order: order({ problem: problem(status) }),
        identity: { sub: SELLER, groups: [] },
      });
      extras(harness).refund({ note: ' Optional note ' });
      await settle(harness);
      expect(orders.refundProblem).toHaveBeenCalledTimes(
        ['OPEN', 'REFUND_PENDING'].includes(status) ? 1 : 0,
      );
      expect(text(root)).not.toContain('Dismiss');
    },
  );
  it.each(['PAID', 'SHIPPED', 'DELIVERED', 'REFUNDED'] as OrderStatus[])(
    'review eligibility follows %s',
    async (status) => {
      const { harness, orders } = await open('/orders/o1', { order: order({ status }) });
      extras(harness).review({ rating: 1, comment: null });
      await settle(harness);
      expect(orders.createReview).toHaveBeenCalledTimes(status === 'DELIVERED' ? 1 : 0);
    },
  );
  it.each([
    'problem-already-reported',
    'problem-not-reportable',
    'problem-not-open',
    'review-already-exists',
    'order-not-reviewable',
    'order-action-forbidden',
  ])('refetches after conflict %s', async (code) => {
    const { harness, orders } = await open('/orders/o1', {
      order: order(),
      report: throwError(() => appError(409, code)),
    });
    extras(harness).report({ reason: 'DAMAGED', description: 'Damaged' });
    await settle(harness);
    expect(orders.order).toHaveBeenCalledTimes(2);
    expect(extras(harness).error()).not.toBe('');
  });
  it('keeps all optional commands single flight and cancels on logout', async () => {
    const request = new Subject<any>();
    const { harness, orders } = await open('/orders/o1', {
      order: order({ status: 'DELIVERED' }),
      report: request,
    });
    const work = extras(harness);
    work.report({ reason: 'DAMAGED', description: 'Damaged' });
    work.review({ rating: 4, comment: null });
    expect(orders.reportProblem).toHaveBeenCalledTimes(1);
    expect(orders.createReview).not.toHaveBeenCalled();
    TestBed.inject(AuthStore).clearSession();
    await settle(harness);
    expect(request.observed).toBe(false);
    expect(work.busy()).toBeNull();
  });
  it('validates field bounds and forbidden controls without truncating ratings', () => {
    expect(validateReport({ reason: 'DAMAGED', description: ' x ' })).toEqual({});
    expect(
      validateReport({ reason: 'OTHER', description: ' '.repeat(10) }).description,
    ).toBeTruthy();
    expect(
      validateReport({ reason: 'OTHER', description: 'x'.repeat(2001) }).description,
    ).toBeTruthy();
    expect(validateReport({ reason: 'OTHER', description: 'x\u0001' }).description).toBeTruthy();
    for (const rating of [-1, 0, 6, 1.5, NaN])
      expect(validateReview({ rating, comment: null }).rating).toBeTruthy();
    for (const rating of [1, 5])
      expect(validateReview({ rating, comment: 'x'.repeat(2000) })).toEqual({});
    expect(validateReview({ rating: 3, comment: 'x'.repeat(2001) }).comment).toBeTruthy();
    expect(validateReview({ rating: 3, comment: '\u0000text' }).comment).toBeTruthy();
    expect(validateRefund({ note: 'x'.repeat(1000) })).toEqual({});
    expect(validateRefund({ note: 'x'.repeat(1001) }).note).toBeTruthy();
  });
  it('associates each optional field label with exactly one native control', async () => {
    const { harness, root } = await open('/orders/o2', { order: software({ license }) });
    harness.routeDebugElement!.query(By.directive(ProblemCard)).componentInstance.open('report');
    await settle(harness);
    for (const control of root.querySelectorAll<HTMLSelectElement | HTMLTextAreaElement>(
      'app-form-field select, app-form-field textarea',
    )) {
      expect(root.querySelectorAll(`[id="${control.id}"]`)).toHaveLength(1);
      expect(control.labels?.length).toBe(1);
    }
  });
  it('submits the report dialog with validated and trimmed fields', async () => {
    const { harness, root, orders } = await open('/orders/o1');
    const card = harness.routeDebugElement!.query(By.directive(ProblemCard))
      .componentInstance as ProblemCard;
    card.open('report');
    card.reason.set('DAMAGED');
    card.description.set('  The case is damaged.  ');
    await settle(harness);
    root
      .querySelector<HTMLFormElement>('app-problem-card form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(harness);
    expect(orders.reportProblem).toHaveBeenCalledWith('o1', {
      reason: 'DAMAGED',
      description: 'The case is damaged.',
    });
  });
  it('leaves an invalid report editable and sends no mutation', async () => {
    const { harness, root, orders } = await open('/orders/o1');
    const card = harness.routeDebugElement!.query(By.directive(ProblemCard))
      .componentInstance as ProblemCard;
    card.open('report');
    await settle(harness);
    root
      .querySelector<HTMLFormElement>('app-problem-card form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(harness);
    expect(orders.reportProblem).not.toHaveBeenCalled();
    expect(text(root)).toContain('Choose a reason.');
    expect(text(root)).toContain('Describe the problem.');
  });
  it('keeps accepted refunds pending until authoritative order state changes', async () => {
    const pending = order({
      problem: problem('REFUND_PENDING'),
      refundRequestedAt: '2026-10-13T00:00:00Z',
    });
    const { harness, root, orders } = await open('/orders/o1', {
      identity: { sub: SELLER, groups: [] },
      sequence: [of(order({ problem: problem() })), of(pending)],
      refund: of(problem('REFUND_PENDING')),
    });
    extras(harness).refund({ note: null });
    await settle(harness);
    expect(text(root)).toContain('Refund pending');
    expect(text(root)).toContain('Retry refund €504.00');
    expect(text(root)).not.toContain('A refund has been requested for this order.');
    expect(text(root)).not.toContain('This order was refunded.');
    expect(orders.refundProblem).toHaveBeenCalledWith('o1', { note: null });
  });
  it('renders an existing review without inventing an author or another form', async () => {
    const existing = {
      id: 'r1',
      listingId: 'l2',
      listingTitle: 'Photo Editor',
      authorUsername: null,
      sellerSub: SELLER,
      rating: 0,
      comment: 'Good setup.',
      createdAt: '2026-10-03T00:00:00Z',
    };
    const { harness, root, orders } = await open('/orders/o2', {
      order: software({ review: existing }),
    });
    extras(harness).review({ rating: 5, comment: null });
    await settle(harness);
    expect(orders.createReview).not.toHaveBeenCalled();
    expect(root.querySelector('app-review-form form')).toBeNull();
    expect(text(root)).toContain('0 out of 5');
    expect(text(root)).not.toContain('Unknown author');
  });
  it('requires a 1 to 5 star rating and offers no zero-rating control', async () => {
    const { harness, root, orders } = await open('/orders/o1', {
      order: order({ status: 'DELIVERED' }),
    });
    expect(text(root)).not.toContain('Rate 0');
    root
      .querySelector<HTMLFormElement>('app-review-form form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(harness);
    expect(orders.createReview).not.toHaveBeenCalled();
    expect(root.querySelector('app-review-form [role="alert"]')?.textContent).toContain('1 to 5');
  });
  it('does not send a download for an unreturned version or expired link', async () => {
    const { harness, catalog, navigate } = await open('/orders/o2', {
      order: software({ license }),
      download: of({ url: 'https://example.test/file', expiresAt: '2000-01-01T00:00:00Z' }),
    });
    const card = licence(harness);
    card.version.set('invented');
    card.download();
    expect(catalog.artifactDownload).not.toHaveBeenCalled();
    card.version.set('1.0');
    card.download();
    await settle(harness);
    expect(navigate).not.toHaveBeenCalled();
  });
  it('cancels an optional command when navigating to another order', async () => {
    const request = new Subject<any>();
    const { harness, orders } = await open('/orders/o1', { report: request });
    extras(harness).report({ reason: 'OTHER', description: 'A problem' });
    await harness.navigateByUrl('/orders/o2');
    await settle(harness);
    expect(request.observed).toBe(false);
    request.next(problem());
    expect(orders.order).toHaveBeenCalledTimes(2);
  });
});

describe('order detail page', () => {
  afterEach(() => TestBed.resetTestingModule());
  beforeEach(() => sessionStorage.clear());

  it('registers OrderDetailPage on the guarded child route instead of the stub', () => {
    expect(ORDER_DETAIL_ROUTES).toHaveLength(1);
    expect(ORDER_DETAIL_ROUTES[0].path).toBe('');
    expect(ORDER_DETAIL_ROUTES[0].component).toBe(OrderDetailPage);
    expect(ORDER_DETAIL_ROUTES[0].component).not.toBe(RouteStubPage);
  });

  it('resolves /orders/:id to OrderDetailPage with the inherited id and fetches that order', async () => {
    const { page, orders } = await open('/orders/o1');
    expect(page).toBeInstanceOf(OrderDetailPage);
    expect(page.orderId()).toBe('o1');
    expect(orders.order).toHaveBeenCalledTimes(1);
    expect(orders.order).toHaveBeenCalledWith('o1');
  });

  it('shows a skeleton and no order content while the order loads', async () => {
    const { root } = await open('/orders/o1', { order: NEVER });
    expect(root.querySelector('app-skeleton')).not.toBeNull();
    expect(root.querySelector('ol.stepper')).toBeNull();
    expect(text(root)).not.toContain('ThinkPad');
    const headings = root.querySelectorAll('h1');
    expect(headings).toHaveLength(1);
    expect(headings[0].getAttribute('tabindex')).toBe('-1');
  });

  it('renders the header, status, money rows and parties from the order only', async () => {
    const { root } = await open('/orders/o1');
    const headings = root.querySelectorAll('h1');
    expect(headings).toHaveLength(1);
    expect(headings[0].getAttribute('tabindex')).toBe('-1');
    const title = headings[0].querySelector('a');
    expect(text(title)).toBe('ThinkPad X1');
    expect(title?.getAttribute('href')).toBe('/listings/l1');
    expect(text(root.querySelector('app-copy-field'))).toContain('o1');
    expect(text(root.querySelector('app-status-chip'))).toBe('Paid');
    expect(text(root)).toContain('Placed 1 Oct 2026');
    expect(moneyRows(root)).toEqual([
      ['Item', '€499.00'],
      ['Shipping', '€5.00'],
      ['Total paid', '€504.00'],
    ]);
    expect(text(root)).not.toMatch(/undefined|null/);
  });

  it('omits the shipping row for software', async () => {
    const { root } = await open('/orders/o2', { order: software() });
    expect(moneyRows(root)).toEqual([
      ['Item', '€29.90'],
      ['Total paid', '€29.90'],
    ]);
  });

  it('labels the buyer and seller as "You" for the viewer and links the other party', async () => {
    const buyer = await open('/orders/o1');
    const buyerParties = buyer.root.querySelector('.parties');
    expect(text(buyerParties?.querySelector('[data-party="buyer"]'))).toBe('Buyer You');
    const sellerLink = buyerParties?.querySelector<HTMLAnchorElement>('[data-party="seller"] a');
    expect(sellerLink?.getAttribute('href')).toBe('/u/seller-sub');
    TestBed.resetTestingModule();

    const seller = await open('/orders/o1', {
      identity: { sub: SELLER, groups: [] },
    });
    const sellerParties = seller.root.querySelector('.parties');
    expect(text(sellerParties?.querySelector('[data-party="seller"]'))).toBe('Seller You');
    expect(sellerParties?.querySelector('[data-party="buyer"] a')?.getAttribute('href')).toBe(
      '/u/buyer-sub',
    );
    TestBed.resetTestingModule();

    const admin = await open('/orders/o1', {
      identity: { sub: ADMIN, groups: ['ADMIN'] },
    });
    const adminParties = admin.root.querySelector('.parties');
    expect(adminParties?.querySelectorAll('a')).toHaveLength(2);
    expect(text(adminParties)).not.toContain('You');
  });

  describe('stepper by status', () => {
    const hardware: [OrderStatus, Partial<OrderView>, string[][]][] = [
      [
        'PAID',
        {},
        [
          ['Paid', 'current'],
          ['Shipped', 'todo'],
          ['Delivered', 'todo'],
        ],
      ],
      [
        'SHIPPED',
        shipped(),
        [
          ['Paid', 'done'],
          ['Shipped', 'current'],
          ['Delivered', 'todo'],
        ],
      ],
      [
        'DELIVERED',
        shipped({ status: 'DELIVERED', deliveredAt: '2026-10-05T09:00:00Z' }),
        [
          ['Paid', 'done'],
          ['Shipped', 'done'],
          ['Delivered', 'current'],
        ],
      ],
    ];

    it.each(hardware)('shows the hardware stepper for %s', async (status, extra, expected) => {
      const { root } = await open('/orders/o1', { order: order({ ...extra, status }) });
      expect(steps(root)).toEqual(expected);
      expect(
        root.querySelector('ol.stepper > li[data-state="current"]')?.getAttribute('aria-current'),
      ).toBe('step');
      expect(root.querySelector('.closed')).toBeNull();
    });

    it('shows only Paid and Delivered for software', async () => {
      const { root } = await open('/orders/o2', { order: software() });
      expect(steps(root)).toEqual([
        ['Paid', 'done'],
        ['Delivered', 'current'],
      ]);
    });

    it('shows the timestamp of each reached step and none for later steps', async () => {
      const { root } = await open('/orders/o1', { order: shipped() });
      const items = Array.from(root.querySelectorAll('ol.stepper > li')).map((item) => text(item));
      expect(items[0]).toContain('1 Oct 2026');
      expect(items[1]).toContain('2 Oct 2026');
      expect(items[2]).toContain('Delivered');
      expect(items[2]).not.toMatch(/\d{4}/);
    });

    it.each([
      ['CANCELLED', 'This order was cancelled.'],
      ['REFUNDED', 'This order was refunded.'],
    ] as const)('replaces the stepper with a banner for %s', async (status, copy) => {
      const { root } = await open('/orders/o1', { order: order({ status }) });
      expect(root.querySelector('ol.stepper')).toBeNull();
      expect(text(root.querySelector('app-banner'))).toContain(copy);
    });

    it('shows the payment window and a countdown instead of the stepper while awaiting payment', async () => {
      const expiryAt = new Date(Date.now() + 25 * 60_000).toISOString();
      const { root } = await open('/orders/o1', {
        order: order({ status: 'AWAITING_PAYMENT', expiryAt, paidAt: undefined }),
      });
      expect(root.querySelector('ol.stepper')).toBeNull();
      expect(text(root.querySelector('app-status-chip'))).toBe('Awaiting payment');
      expect(text(root.querySelector('app-banner'))).toContain('Waiting for payment');
      expect(text(root.querySelector('[role="timer"]'))).toMatch(/^2[45] min \d{2} s left$/);
    });

    it('says the payment window closed once the expiry has passed', async () => {
      const { root } = await open('/orders/o1', {
        order: order({
          status: 'AWAITING_PAYMENT',
          expiryAt: new Date(Date.now() - 60_000).toISOString(),
        }),
      });
      expect(root.querySelector('[role="timer"]')).toBeNull();
      expect(text(root.querySelector('app-banner'))).toContain('payment window has closed');
    });

    it('warns on every viewer that a refund is pending when the order carries refundRequestedAt', async () => {
      const { root } = await open('/orders/o1', {
        order: order({ refundRequestedAt: '2026-10-03T10:00:00Z' }),
      });
      expect(text(root.querySelector('app-banner'))).toContain('A refund has been requested');
    });
  });

  describe('optional blocks', () => {
    it('renders the shipping address as plain text only when the API returned it', async () => {
      const { root } = await open('/orders/o1');
      const address = text(root.querySelector('.address'));
      expect(address).toContain('Shipping address');
      expect(address).toContain('Alex Rossi');
      expect(address).toContain('Via Giuseppe Verdi 12');
      expect(address).toContain('20121 Milan');
      expect(address).toContain('Italy');
      expect(root.querySelector('.address input, .address a')).toBeNull();
    });

    it('does not show or reconstruct an address the API omitted', async () => {
      const { shippingAddress: _omitted, ...withoutAddress } = order({
        status: 'AWAITING_PAYMENT',
      });
      const { root } = await open('/orders/o1', {
        identity: { sub: SELLER, groups: [] },
        order: withoutAddress,
      });
      expect(root.querySelector('.address')).toBeNull();
      expect(text(root)).not.toContain('Shipping address');
      expect(text(root)).not.toContain('Alex Rossi');
      expect(text(root)).not.toContain('Via Giuseppe Verdi');
    });

    it('does not show an address block for software', async () => {
      const { root } = await open('/orders/o2', { order: software() });
      expect(root.querySelector('.address')).toBeNull();
    });

    it('shows carrier and tracking code only when the order carries them', async () => {
      const withTracking = await open('/orders/o1', { order: shipped() });
      const shipment = withTracking.root.querySelector('.shipment');
      expect(text(shipment)).toContain('DPD');
      expect(text(shipment?.querySelector('app-copy-field'))).toContain('DPD 1234/AB-9');
      TestBed.resetTestingModule();

      const unshipped = await open('/orders/o1');
      expect(unshipped.root.querySelector('.shipment')).toBeNull();
    });

    it('labels each carrier with its display name', async () => {
      const { root } = await open('/orders/o1', { order: shipped({ carrier: 'POSTE_ITALIANE' }) });
      expect(text(root.querySelector('.shipment'))).toContain('Poste Italiane');
    });
  });

  describe('unavailable and failing orders', () => {
    it.each([
      [404, 'order-not-found'],
      [403, 'access-denied'],
    ])('treats %s as "not available" without echoing the order id', async (status, code) => {
      const { root } = await open('/orders/secret-id', {
        order: throwError(() => appError(status, code)),
      });
      expect(text(root)).toContain("This order isn't available.");
      expect(text(root)).not.toContain('secret-id');
      expect(root.querySelector('ol.stepper')).toBeNull();
      expect(root.querySelector('app-error-state')).toBeNull();
      const headings = root.querySelectorAll('h1');
      expect(headings).toHaveLength(1);
      expect(headings[0].getAttribute('tabindex')).toBe('-1');
      expect(root.querySelector<HTMLAnchorElement>('a[href="/orders"]')).not.toBeNull();
    });

    it('renders the same page for a missing and a forbidden order', async () => {
      const missing = await open('/orders/x', {
        order: throwError(() => appError(404, 'order-not-found')),
      });
      const missingHtml = missing.root.innerHTML.replace(/<!--[\s\S]*?-->/g, '');
      TestBed.resetTestingModule();
      const forbidden = await open('/orders/x', {
        order: throwError(() => appError(403, 'access-denied')),
      });
      expect(forbidden.root.innerHTML.replace(/<!--[\s\S]*?-->/g, '')).toBe(missingHtml);
    });

    it('shows an error state with a retry for other failures and recovers', async () => {
      const { root, harness, orders } = await open('/orders/o1', {
        sequence: [throwError(() => appError(500, 'unknown-error')), of(order())],
      });
      expect(root.querySelector('app-error-state')).not.toBeNull();
      expect(root.querySelector('ol.stepper')).toBeNull();
      root.querySelector<HTMLButtonElement>('app-error-state button')?.click();
      await settle(harness);
      expect(orders.order).toHaveBeenCalledTimes(2);
      expect(root.querySelector('app-error-state')).toBeNull();
      expect(text(root.querySelector('app-status-chip'))).toBe('Paid');
    });
  });

  describe('navigation between orders', () => {
    it('never shows the previous order while the next one loads', async () => {
      const next = new Subject<OrderView>();
      const { harness, root, orders } = await open('/orders/o1', {
        responses: { o2: next },
      });
      expect(text(root)).toContain('ThinkPad X1');
      await harness.navigateByUrl('/orders/o2');
      await settle(harness);
      expect(orders.order).toHaveBeenLastCalledWith('o2');
      expect(root.querySelector('app-skeleton')).not.toBeNull();
      expect(text(root)).not.toContain('ThinkPad X1');
      expect(root.querySelector('ol.stepper')).toBeNull();
      next.next(order({ id: 'o2', listingTitle: 'Keyboard' }));
      next.complete();
      await settle(harness);
      expect(text(root.querySelector('h1'))).toBe('Keyboard');
    });

    it('drops the previous order when the next one is unavailable', async () => {
      const { harness, root } = await open('/orders/o1', {
        responses: { o2: throwError(() => appError(404, 'order-not-found')) },
      });
      await harness.navigateByUrl('/orders/o2');
      await settle(harness);
      expect(text(root)).toContain("This order isn't available.");
      expect(text(root)).not.toContain('ThinkPad X1');
      expect(text(root)).not.toContain('Alex Rossi');
    });

    it('ignores a late response for an order the viewer already left', async () => {
      const slow = new Subject<OrderView>();
      const { harness, root } = await open('/orders/o1', {
        responses: { o1: slow, o2: of(order({ id: 'o2', listingTitle: 'Keyboard' })) },
      });
      await harness.navigateByUrl('/orders/o2');
      await settle(harness);
      expect(text(root.querySelector('h1'))).toBe('Keyboard');
      slow.next(order({ id: 'o1', listingTitle: 'Stale order' }));
      slow.complete();
      await settle(harness);
      expect(text(root)).not.toContain('Stale order');
      expect(text(root.querySelector('h1'))).toBe('Keyboard');
    });
  });
});

const SELLER_ID = { sub: SELLER, groups: [] };
const ADMIN_ID = { sub: ADMIN, groups: ['ADMIN'] };
const buttonsIn = (scope: ParentNode | null | undefined) =>
  Array.from(scope?.querySelectorAll<HTMLButtonElement>('button') ?? []);
const railButton = (root: HTMLElement, label: string) =>
  buttonsIn(root.querySelector('.rail')).find((button) => text(button) === label);
const dialogButton = (root: HTMLElement, label: string) =>
  buttonsIn(root.querySelector('app-modal-dialog')).find((button) => text(button) === label);
const byId = (root: HTMLElement, id: string) => root.querySelector<HTMLElement>('#' + id);

function type(harness: RouterTestingHarness, root: HTMLElement, id: string, value: string) {
  const input = byId(root, id) as HTMLInputElement | null;
  if (!input) throw new Error('missing field #' + id);
  input.value = value;
  input.dispatchEvent(new Event('input'));
  harness.detectChanges();
}

function choose(harness: RouterTestingHarness, root: HTMLElement, id: string, value: string) {
  const select = byId(root, id) as HTMLSelectElement | null;
  if (!select) throw new Error('missing select #' + id);
  select.value = value;
  select.dispatchEvent(new Event('change'));
  harness.detectChanges();
}

async function press(
  harness: RouterTestingHarness,
  button: HTMLButtonElement | null | undefined,
): Promise<void> {
  if (!button) throw new Error('button not found');
  button.click();
  await settle(harness);
}

describe('lifecycleActions', () => {
  const cases: [string, OrderView, string | null, Record<string, boolean>][] = [
    ['seller, hardware, paid', order(), SELLER, { ship: true, deliver: false, cancel: false }],
    [
      'seller, hardware, shipped',
      shipped(),
      SELLER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'seller, paid hardware with a pending refund',
      order({ refundRequestedAt: '2026-10-03T10:00:00Z' }),
      SELLER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'seller, shipped hardware with a pending refund',
      shipped({ refundRequestedAt: '2026-10-03T10:00:00Z' }),
      SELLER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'seller, paid software',
      software({ status: 'PAID', deliveredAt: undefined }),
      SELLER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'seller, delivered hardware',
      shipped({ status: 'DELIVERED' }),
      SELLER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'seller, awaiting payment',
      order({ status: 'AWAITING_PAYMENT' }),
      SELLER,
      { ship: false, deliver: false, cancel: false },
    ],
    ['buyer, paid', order(), BUYER, { ship: false, deliver: false, cancel: false }],
    ['buyer, shipped', shipped(), BUYER, { ship: false, deliver: true, cancel: false }],
    [
      'buyer, shipped with pending refund',
      shipped({ refundRequestedAt: '2026-10-03T10:00:00Z' }),
      BUYER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'buyer, shipped software',
      software({ status: 'SHIPPED' }),
      BUYER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'buyer, awaiting payment',
      order({ status: 'AWAITING_PAYMENT' }),
      BUYER,
      { ship: false, deliver: false, cancel: true },
    ],
    [
      'buyer, cancelled',
      order({ status: 'CANCELLED' }),
      BUYER,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'admin who is not a party, paid',
      order(),
      ADMIN,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'admin who is not a party, shipped',
      shipped(),
      ADMIN,
      { ship: false, deliver: false, cancel: false },
    ],
    [
      'admin who is not a party, awaiting payment',
      order({ status: 'AWAITING_PAYMENT' }),
      ADMIN,
      { ship: false, deliver: false, cancel: false },
    ],
    ['no signed-in sub', order(), null, { ship: false, deliver: false, cancel: false }],
  ];

  it.each(cases)('%s', (_name, subject, sub, expected) => {
    expect(lifecycleActions(subject, sub)).toEqual(expected);
  });
});

describe('shipping input', () => {
  const valid = { carrier: 'DPD', trackingCode: 'AB 12/3-4.5' };

  it('accepts a carrier from the list and a tracking code within the backend pattern', () => {
    expect(validateShipping(valid)).toEqual({});
    expect(validateShipping({ carrier: 'POSTE_ITALIANE', trackingCode: 'a' })).toEqual({});
    expect(validateShipping({ ...valid, trackingCode: 'A'.repeat(64) })).toEqual({});
  });

  it('requires a known carrier', () => {
    expect(validateShipping({ ...valid, carrier: '' }).carrier).toBe('Choose a carrier.');
    expect(validateShipping({ ...valid, carrier: 'TNT' }).carrier).toBe(
      'Choose a carrier from the list.',
    );
  });

  it('rejects an empty, too long or mis-patterned tracking code', () => {
    expect(validateShipping({ ...valid, trackingCode: '' }).trackingCode).toBe(
      'Enter the tracking code.',
    );
    expect(validateShipping({ ...valid, trackingCode: '   ' }).trackingCode).toBe(
      'Enter the tracking code.',
    );
    expect(validateShipping({ ...valid, trackingCode: 'A'.repeat(65) }).trackingCode).toBe(
      'Use at most 64 characters.',
    );
    for (const bad of ['-AB1', '.AB1', 'AB#1', 'AB_1', 'AB\t1', 'AB\n1', 'ÀB1']) {
      expect(validateShipping({ ...valid, trackingCode: bad }).trackingCode, bad).toBe(
        'Use letters, digits, spaces, dots, slashes and hyphens, starting with a letter or digit.',
      );
    }
  });

  it('builds exactly the two request properties and strips only leading and trailing spaces', () => {
    const request = buildShipRequest({ carrier: 'DHL', trackingCode: '  AB 12  ' });
    expect(request).toEqual({ carrier: 'DHL', trackingCode: 'AB 12' });
    expect(Object.keys(request).sort()).toEqual(['carrier', 'trackingCode']);
  });
});

describe('order fulfilment actions', () => {
  afterEach(() => TestBed.resetTestingModule());
  beforeEach(() => sessionStorage.clear());

  describe('who sees which control', () => {
    it('shows the seller "Mark as shipped" on a paid hardware order and no delivery control', async () => {
      const { root } = await open('/orders/o1', { identity: SELLER_ID });
      expect(railButton(root, 'Mark as shipped')).toBeDefined();
      expect(railButton(root, 'Confirm receipt')).toBeUndefined();
      expect(railButton(root, 'Cancel order')).toBeUndefined();
      expect(text(root.querySelector('.note'))).toContain('refunded');
    });

    it('shows the seller no manual delivery control on a shipped hardware order', async () => {
      const { root } = await open('/orders/o1', { order: shipped(), identity: SELLER_ID });
      expect(railButton(root, 'Confirm receipt')).toBeUndefined();
      expect(railButton(root, 'Mark as shipped')).toBeUndefined();
      expect(root.querySelector('.note')).toBeNull();
    });

    it('shows the buyer tracking and manual receipt confirmation after shipping', async () => {
      const { root } = await open('/orders/o1', { order: shipped() });
      expect(text(root.querySelector('.shipment'))).toContain('DPD 1234/AB-9');
      expect(railButton(root, 'Confirm receipt')).toBeDefined();
      expect(railButton(root, 'Mark as shipped')).toBeUndefined();
      expect(text(root.querySelector('.rail .note'))).toContain('automatically after a while');
    });

    it.each([
      ['paid', order()],
      ['shipped', shipped()],
    ])('gives an admin who is not the seller no control on a %s order', async (_name, subject) => {
      const { root } = await open('/orders/o1', { order: subject, identity: ADMIN_ID });
      expect(buttonsIn(root.querySelector('.rail'))).toHaveLength(0);
      expect(text(root)).not.toContain('Confirm receipt');
      expect(text(root)).not.toContain('Mark as shipped');
    });

    it('does not let an admin who is the seller confirm the buyer receipt', async () => {
      const { root } = await open('/orders/o1', {
        order: shipped({ sellerSub: ADMIN }),
        identity: ADMIN_ID,
      });
      expect(railButton(root, 'Confirm receipt')).toBeUndefined();
    });

    it('lets an admin who is the buyer confirm receipt as a buyer', async () => {
      const { root } = await open('/orders/o1', {
        order: shipped({ buyerSub: ADMIN }),
        identity: ADMIN_ID,
      });
      expect(railButton(root, 'Confirm receipt')).toBeDefined();
      expect(railButton(root, 'Mark as shipped')).toBeUndefined();
    });

    it.each([
      ['software', software({ status: 'PAID', deliveredAt: undefined })],
      ['delivered hardware', shipped({ status: 'DELIVERED' })],
      ['cancelled hardware', order({ status: 'CANCELLED' })],
      ['refunded hardware', order({ status: 'REFUNDED' })],
      ['paid hardware with a pending refund', order({ refundRequestedAt: '2026-10-03T10:00:00Z' })],
      [
        'shipped hardware with a pending refund',
        shipped({ refundRequestedAt: '2026-10-03T10:00:00Z' }),
      ],
    ])('suppresses the seller controls on %s', async (_name, subject) => {
      const { root } = await open('/orders/o1', { order: subject, identity: SELLER_ID });
      expect(buttonsIn(root.querySelector('.rail'))).toHaveLength(0);
      expect(root.querySelector('.note')).toBeNull();
    });

    it('shows the buyer "Cancel order" only while awaiting payment', async () => {
      const awaiting = order({ status: 'AWAITING_PAYMENT' });
      const buyer = await open('/orders/o1', { order: awaiting });
      expect(railButton(buyer.root, 'Cancel order')).toBeDefined();
      TestBed.resetTestingModule();

      const seller = await open('/orders/o1', { order: awaiting, identity: SELLER_ID });
      expect(railButton(seller.root, 'Cancel order')).toBeUndefined();
      TestBed.resetTestingModule();

      const admin = await open('/orders/o1', { order: awaiting, identity: ADMIN_ID });
      expect(railButton(admin.root, 'Cancel order')).toBeUndefined();
      TestBed.resetTestingModule();

      const paid = await open('/orders/o1');
      expect(railButton(paid.root, 'Cancel order')).toBeUndefined();
    });

    it('offers "Continue to PayPal" only when this tab holds a verified approval link', async () => {
      const awaiting = order({
        status: 'AWAITING_PAYMENT',
        expiryAt: new Date(Date.now() + 25 * 60_000).toISOString(),
      });
      const url = 'https://www.sandbox.paypal.com/checkoutnow?token=T1';
      const store = (approvalUrl?: string, orderId = 'o1') =>
        sessionStorage.setItem(
          PENDING_CHECKOUT_KEY,
          JSON.stringify({
            orderId,
            listingId: 'l1',
            startedAt: '2026-10-08T10:00:00Z',
            ...(approvalUrl ? { approvalUrl } : {}),
          }),
        );

      store(url);
      const known = await open('/orders/o1', { order: awaiting });
      expect(railButton(known.root, 'Continue to PayPal')).toBeDefined();
      await press(known.harness, railButton(known.root, 'Continue to PayPal'));
      expect(known.navigate).toHaveBeenCalledExactlyOnceWith(url);
      TestBed.resetTestingModule();

      store();
      expect(
        railButton((await open('/orders/o1', { order: awaiting })).root, 'Continue to PayPal'),
      ).toBeUndefined();
      TestBed.resetTestingModule();

      store('https://evil.example/paypal.com');
      expect(
        railButton((await open('/orders/o1', { order: awaiting })).root, 'Continue to PayPal'),
      ).toBeUndefined();
      TestBed.resetTestingModule();

      store(url, 'other-order');
      expect(
        railButton((await open('/orders/o1', { order: awaiting })).root, 'Continue to PayPal'),
      ).toBeUndefined();
      TestBed.resetTestingModule();

      store(url);
      const seller = await open('/orders/o1', { order: awaiting, identity: SELLER_ID });
      expect(railButton(seller.root, 'Continue to PayPal')).toBeUndefined();
      TestBed.resetTestingModule();

      store(url);
      const expired = await open('/orders/o1', {
        order: { ...awaiting, expiryAt: new Date(Date.now() - 60_000).toISOString() },
      });
      expect(railButton(expired.root, 'Continue to PayPal')).toBeUndefined();
      expect(railButton(expired.root, 'Cancel order')).toBeDefined();
    });
  });

  describe('direct handler attempts', () => {
    it('sends no command when a seller or non-party admin calls deliver', async () => {
      for (const identity of [SELLER_ID, ADMIN_ID]) {
        const { harness, page, orders, toast } = await open('/orders/o1', {
          order: shipped(),
          identity,
        });
        const service = harness.routeDebugElement!.injector.get(OrderDetailService);
        expect(await service.deliver()).toEqual({ kind: 'ignored' });
        await page.confirmDeliver();
        expect(orders.deliverOrder).not.toHaveBeenCalled();
        expect(orders.order).toHaveBeenCalledTimes(1);
        expect(toast).not.toHaveBeenCalled();
        TestBed.resetTestingModule();
      }
    });

    it('sends no ship, deliver or cancel for ineligible viewers and states', async () => {
      const seller = await open('/orders/o1', { order: shipped(), identity: SELLER_ID });
      const sellerService = seller.harness.routeDebugElement!.injector.get(OrderDetailService);
      expect(await sellerService.ship({ carrier: 'DPD', trackingCode: 'A1' })).toEqual({
        kind: 'ignored',
      });
      expect(await sellerService.cancel()).toEqual({ kind: 'ignored' });
      expect(seller.orders.shipOrder).not.toHaveBeenCalled();
      expect(seller.orders.cancelOrder).not.toHaveBeenCalled();
      TestBed.resetTestingModule();

      const buyer = await open('/orders/o1');
      const buyerService = buyer.harness.routeDebugElement!.injector.get(OrderDetailService);
      expect(await buyerService.ship({ carrier: 'DPD', trackingCode: 'A1' })).toEqual({
        kind: 'ignored',
      });
      expect(await buyerService.cancel()).toEqual({ kind: 'ignored' });
      expect(buyer.orders.shipOrder).not.toHaveBeenCalled();
      expect(buyer.orders.cancelOrder).not.toHaveBeenCalled();
    });
  });

  describe('shipping', () => {
    it('opens a dialog with the nine carriers and a tracking field', async () => {
      const { harness, root } = await open('/orders/o1', { identity: SELLER_ID });
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      await press(harness, railButton(root, 'Mark as shipped'));
      const dialog = root.querySelector('app-modal-dialog');
      expect(dialog).not.toBeNull();
      expect(text(dialog?.querySelector('h2'))).toBe('Mark as shipped');
      const labels = Array.from(root.querySelectorAll('#ship-carrier option'))
        .map((option) => text(option))
        .slice(1);
      expect(labels).toEqual([
        'Poste Italiane',
        'BRT',
        'GLS',
        'SDA',
        'DHL',
        'UPS',
        'FedEx',
        'DPD',
        'Other',
      ]);
      expect(byId(root, 'ship-tracking')).not.toBeNull();
    });

    it('validates before sending, focuses the first invalid field and sends nothing', async () => {
      const { harness, root, orders } = await open('/orders/o1', { identity: SELLER_ID });
      await press(harness, railButton(root, 'Mark as shipped'));
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(orders.shipOrder).not.toHaveBeenCalled();
      expect(text(byId(root, 'ship-carrier-error'))).toBe('Choose a carrier.');
      expect(text(byId(root, 'ship-tracking-error'))).toBe('Enter the tracking code.');
      expect(document.activeElement).toBe(byId(root, 'ship-carrier'));

      choose(harness, root, 'ship-carrier', 'DPD');
      type(harness, root, 'ship-tracking', '#bad');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(orders.shipOrder).not.toHaveBeenCalled();
      expect(text(byId(root, 'ship-tracking-error'))).toContain('starting with a letter or digit');
      expect(document.activeElement).toBe(byId(root, 'ship-tracking'));
    });

    it('sends the exact request, refetches, shows the shipped state and toasts once', async () => {
      const { harness, root, orders, toast, page } = await open('/orders/o1', {
        identity: SELLER_ID,
        sequence: [of(order()), of(shipped())],
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'DPD');
      type(harness, root, 'ship-tracking', '  DPD 1234/AB-9  ');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(orders.shipOrder).toHaveBeenCalledExactlyOnceWith('o1', {
        carrier: 'DPD',
        trackingCode: 'DPD 1234/AB-9',
      });
      expect(orders.order).toHaveBeenCalledTimes(2);
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(steps(root)).toEqual([
        ['Paid', 'done'],
        ['Shipped', 'current'],
        ['Delivered', 'todo'],
      ]);
      expect(text(root.querySelector('.shipment'))).toContain('DPD 1234/AB-9');
      expect(railButton(root, 'Mark as shipped')).toBeUndefined();
      expect(railButton(root, 'Confirm receipt')).toBeUndefined();
      expect(toast).toHaveBeenCalledExactlyOnceWith({
        text: 'Order marked as shipped',
        kind: 'success',
      });
      expect(document.activeElement).toBe(root.querySelector('h1'));
      expect(page.orderId()).toBe('o1');
      expect(root.querySelector('app-toast-host')).toBeNull();
    });

    it('does not advance the page until the refetch answers', async () => {
      const refetch = new Subject<OrderView>();
      const { harness, root, toast } = await open('/orders/o1', {
        identity: SELLER_ID,
        sequence: [of(order()), refetch],
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'DPD');
      type(harness, root, 'ship-tracking', 'AB1');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(steps(root)[0]).toEqual(['Paid', 'current']);
      expect(root.querySelector('.shipment')).toBeNull();
      expect(toast).not.toHaveBeenCalled();
      refetch.next(shipped());
      refetch.complete();
      await settle(harness);
      expect(steps(root)[1]).toEqual(['Shipped', 'current']);
      expect(toast).toHaveBeenCalledTimes(1);
    });

    it('keeps the dialog open on a server error without a refetch or toast', async () => {
      const { harness, root, orders, toast } = await open('/orders/o1', {
        identity: SELLER_ID,
        ship: throwError(() => appError(500, 'unknown-error')),
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'GLS');
      type(harness, root, 'ship-tracking', 'AB1');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(root.querySelector('app-modal-dialog')).not.toBeNull();
      expect(text(root.querySelector('app-modal-dialog app-banner'))).toContain(
        'Something went wrong',
      );
      expect(orders.order).toHaveBeenCalledTimes(1);
      expect(toast).not.toHaveBeenCalled();
      expect(dialogButton(root, 'Mark as shipped')?.disabled).toBe(false);
      expect(steps(root)[0]).toEqual(['Paid', 'current']);
    });

    it('maps a server validation error onto the tracking field', async () => {
      const failure: AppError = {
        ...appError(400, 'validation-failed'),
        fieldErrors: { trackingCode: 'must match pattern' },
      };
      const { harness, root, orders } = await open('/orders/o1', {
        identity: SELLER_ID,
        ship: throwError(() => failure),
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'GLS');
      type(harness, root, 'ship-tracking', 'AB1');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(text(byId(root, 'ship-tracking-error'))).toContain('Check the tracking code');
      expect(orders.order).toHaveBeenCalledTimes(1);
    });

    it('reconciles a transition conflict with the current order and explains it', async () => {
      const { harness, root, toast } = await open('/orders/o1', {
        identity: SELLER_ID,
        ship: throwError(() => appError(409, 'order-transition-conflict')),
        sequence: [of(order()), of(shipped())],
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'GLS');
      type(harness, root, 'ship-tracking', 'AB1');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(text(root.querySelector('.notice'))).toContain('changed');
      expect(steps(root)[1]).toEqual(['Shipped', 'current']);
      expect(railButton(root, 'Mark as shipped')).toBeUndefined();
      expect(toast).not.toHaveBeenCalled();
    });

    it('explains a pending refund and removes the seller controls', async () => {
      const refundPending = order({ refundRequestedAt: '2026-10-03T10:00:00Z' });
      const { harness, root, toast } = await open('/orders/o1', {
        identity: SELLER_ID,
        ship: throwError(() => appError(409, 'order-refund-pending')),
        sequence: [of(order()), of(refundPending)],
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'GLS');
      type(harness, root, 'ship-tracking', 'AB1');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(text(root.querySelector('.notice'))).toContain('refund');
      expect(buttonsIn(root.querySelector('.rail'))).toHaveLength(0);
      expect(toast).not.toHaveBeenCalled();
    });

    it('falls back to the response of the successful command when the refetch fails', async () => {
      const { harness, root, toast } = await open('/orders/o1', {
        identity: SELLER_ID,
        sequence: [of(order()), throwError(() => appError(0, 'offline'))],
      });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'DPD');
      type(harness, root, 'ship-tracking', 'AB1');
      await press(harness, dialogButton(root, 'Mark as shipped'));
      expect(steps(root)[1]).toEqual(['Shipped', 'current']);
      expect(toast).toHaveBeenCalledTimes(1);
    });

    it('closes the dialog with Escape and a reopened dialog starts empty', async () => {
      const { harness, root, orders } = await open('/orders/o1', { identity: SELLER_ID });
      await press(harness, railButton(root, 'Mark as shipped'));
      choose(harness, root, 'ship-carrier', 'DPD');
      type(harness, root, 'ship-tracking', 'AB1');
      root
        .querySelector('dialog')
        ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await settle(harness);
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(orders.shipOrder).not.toHaveBeenCalled();
      await press(harness, railButton(root, 'Mark as shipped'));
      expect((byId(root, 'ship-tracking') as HTMLInputElement).value).toBe('');
      expect((byId(root, 'ship-carrier') as HTMLSelectElement).value).toBe('');
      expect(byId(root, 'ship-tracking-error')).toBeNull();
    });
  });

  describe('delivery', () => {
    it('confirms, posts without a body, refetches and toasts once', async () => {
      const { harness, root, orders, toast } = await open('/orders/o1', {
        order: shipped(),
        identity: { sub: BUYER, groups: [] },
        sequence: [
          of(shipped()),
          of(shipped({ status: 'DELIVERED', deliveredAt: '2026-10-05T09:00:00Z' })),
        ],
      });
      await press(harness, railButton(root, 'Confirm receipt'));
      expect(orders.deliverOrder).not.toHaveBeenCalled();
      expect(text(root.querySelector('app-modal-dialog h2'))).toBe('Confirm receipt?');
      expect(text(root.querySelector('app-modal-dialog'))).toContain(
        'only if you have received the item',
      );
      expect(text(root.querySelector('app-modal-dialog'))).toContain('cannot be undone');
      await press(harness, dialogButton(root, 'Confirm receipt'));
      expect(orders.deliverOrder).toHaveBeenCalledExactlyOnceWith('o1');
      expect(orders.order).toHaveBeenCalledTimes(2);
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(steps(root)[2]).toEqual(['Delivered', 'current']);
      expect(root.querySelector('.rail .actions')).toBeNull();
      expect(railButton(root, 'Report a problem')).toBeDefined();
      expect(toast).toHaveBeenCalledExactlyOnceWith({
        text: 'Receipt confirmed',
        kind: 'success',
      });
    });

    it('lets the buyer back out without sending anything', async () => {
      const { harness, root, orders } = await open('/orders/o1', {
        order: shipped(),
        identity: { sub: BUYER, groups: [] },
      });
      await press(harness, railButton(root, 'Confirm receipt'));
      await press(harness, dialogButton(root, 'Not yet'));
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(orders.deliverOrder).not.toHaveBeenCalled();
    });

    it('sends one command for repeated presses and disables the controls while it runs', async () => {
      const pending = new Subject<OrderView>();
      const { harness, root, orders } = await open('/orders/o1', {
        order: shipped(),
        identity: { sub: BUYER, groups: [] },
        deliver: pending,
      });
      await press(harness, railButton(root, 'Confirm receipt'));
      const confirm = dialogButton(root, 'Confirm receipt');
      confirm?.click();
      harness.detectChanges();
      dialogButton(root, 'Confirm receipt')?.click();
      dialogButton(root, 'Confirm receipt')?.click();
      await settle(harness);
      expect(orders.deliverOrder).toHaveBeenCalledTimes(1);
      expect(dialogButton(root, 'Confirm receipt')?.disabled).toBe(true);
      expect(dialogButton(root, 'Confirm receipt')?.getAttribute('aria-busy')).toBe('true');
      expect(railButton(root, 'Confirm receipt')?.disabled).toBe(true);
      pending.next(shipped({ status: 'DELIVERED' }));
      pending.complete();
      await settle(harness);
      expect(orders.deliverOrder).toHaveBeenCalledTimes(1);
    });

    it('reconciles a delivery conflict with the current order, without a toast', async () => {
      const { harness, root, toast } = await open('/orders/o1', {
        order: shipped(),
        identity: { sub: BUYER, groups: [] },
        deliver: throwError(() => appError(409, 'order-transition-conflict')),
        sequence: [of(shipped()), of(shipped({ status: 'DELIVERED' }))],
      });
      await press(harness, railButton(root, 'Confirm receipt'));
      await press(harness, dialogButton(root, 'Confirm receipt'));
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(text(root.querySelector('.notice'))).toContain('changed');
      expect(steps(root)[2]).toEqual(['Delivered', 'current']);
      expect(toast).not.toHaveBeenCalled();
    });

    it('shows a forbidden delivery as a conflict and refetches instead of trusting the click', async () => {
      const { harness, root, orders, toast } = await open('/orders/o1', {
        order: shipped(),
        identity: { sub: BUYER, groups: [] },
        deliver: throwError(() => appError(403, 'order-action-forbidden')),
      });
      await press(harness, railButton(root, 'Confirm receipt'));
      await press(harness, dialogButton(root, 'Confirm receipt'));
      expect(orders.order).toHaveBeenCalledTimes(2);
      expect(text(root.querySelector('.notice'))).toContain('cannot perform');
      expect(toast).not.toHaveBeenCalled();
    });

    it('shows the unavailable page when the refetch after a conflict is a 404', async () => {
      const { harness, root } = await open('/orders/o1', {
        order: shipped(),
        identity: { sub: BUYER, groups: [] },
        deliver: throwError(() => appError(404, 'order-not-found')),
        sequence: [of(shipped()), throwError(() => appError(404, 'order-not-found'))],
      });
      await press(harness, railButton(root, 'Confirm receipt'));
      await press(harness, dialogButton(root, 'Confirm receipt'));
      expect(text(root)).toContain("This order isn't available.");
      expect(root.querySelector('ol.stepper')).toBeNull();
    });
  });

  describe('cancellation', () => {
    const awaiting = () =>
      order({
        status: 'AWAITING_PAYMENT',
        expiryAt: new Date(Date.now() + 25 * 60_000).toISOString(),
        paidAt: undefined,
      });

    it('confirms intent, posts the cancel, refetches and toasts once', async () => {
      const { harness, root, orders, toast } = await open('/orders/o1', {
        sequence: [of(awaiting()), of(order({ status: 'CANCELLED' }))],
      });
      await press(harness, railButton(root, 'Cancel order'));
      expect(text(root.querySelector('app-modal-dialog h2'))).toBe('Cancel this order?');
      expect(orders.cancelOrder).not.toHaveBeenCalled();
      await press(harness, dialogButton(root, 'Cancel order'));
      expect(orders.cancelOrder).toHaveBeenCalledExactlyOnceWith('o1');
      expect(orders.order).toHaveBeenCalledTimes(2);
      expect(text(root.querySelector('app-banner'))).toContain('This order was cancelled.');
      expect(buttonsIn(root.querySelector('.rail'))).toHaveLength(0);
      expect(toast).toHaveBeenCalledExactlyOnceWith({ text: 'Order cancelled', kind: 'success' });
    });

    it('keeps the order when the buyer chooses to keep it', async () => {
      const { harness, root, orders } = await open('/orders/o1', { order: awaiting() });
      await press(harness, railButton(root, 'Cancel order'));
      await press(harness, dialogButton(root, 'Keep order'));
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(orders.cancelOrder).not.toHaveBeenCalled();
    });

    it('reconciles a cancellation conflict with the paid order the server now reports', async () => {
      const { harness, root, toast } = await open('/orders/o1', {
        cancel: throwError(() => appError(409, 'order-cancellation-conflict')),
        sequence: [of(awaiting()), of(order())],
      });
      await press(harness, railButton(root, 'Cancel order'));
      await press(harness, dialogButton(root, 'Cancel order'));
      expect(root.querySelector('app-modal-dialog')).toBeNull();
      expect(text(root.querySelector('.notice'))).toContain('can no longer be cancelled');
      expect(text(root.querySelector('app-status-chip'))).toBe('Paid');
      expect(toast).not.toHaveBeenCalled();
    });

    it('shows an error state if the refetch after a successful cancel fails, and toasts once', async () => {
      const { harness, root, toast } = await open('/orders/o1', {
        sequence: [of(awaiting()), throwError(() => appError(0, 'offline'))],
      });
      await press(harness, railButton(root, 'Cancel order'));
      await press(harness, dialogButton(root, 'Cancel order'));
      expect(root.querySelector('app-error-state')).not.toBeNull();
      expect(root.querySelector('ol.stepper')).toBeNull();
      expect(toast).toHaveBeenCalledTimes(1);
    });
  });
});
