import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OrderView } from '../../core/api/api-models';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { PendingCheckoutStore } from '../checkout/pending-checkout.store';
import { PAYMENT_CANCEL_ROUTES, PAYMENT_RETURN_ROUTES } from './payment-return.routes';
import { PaymentReturnPage } from './payment-return.page';
import { PaymentReturnService } from './payment-return.service';

function order(
  status: OrderView['status'] = 'AWAITING_PAYMENT',
  changes: Partial<OrderView> = {},
): OrderView {
  return {
    id: 'order-1',
    listingId: 'listing-1',
    listingTitle: 'ThinkPad',
    listingType: 'HARDWARE',
    buyerSub: 'buyer',
    sellerSub: 'seller',
    itemPrice: '100.00',
    shippingCost: '5.00',
    pricePaid: '105.00',
    paymentMethod: 'PAYPAL',
    status,
    createdAt: '2026-10-13T08:00:00Z',
    expiryAt: '2026-10-13T08:30:00Z',
    ...changes,
  };
}

describe('payment return reconciliation', () => {
  const status = signal<'authenticated' | 'guest'>('authenticated');
  const identity = signal<{ sub: string; groups: string[] } | null>({ sub: 'buyer', groups: [] });
  let cleanup: () => void;
  let service: PaymentReturnService;
  let pending: PendingCheckoutStore;
  let api: {
    order: ReturnType<typeof vi.fn>;
    purchases: ReturnType<typeof vi.fn>;
    capturePayment: ReturnType<typeof vi.fn>;
    cancelOrder: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-13T08:05:00Z'));
    status.set('authenticated');
    identity.set({ sub: 'buyer', groups: [] });
    sessionStorage.clear();
    api = {
      order: vi.fn(() => of(order())),
      purchases: vi.fn(() => of({ items: [], nextCursor: null })),
      capturePayment: vi.fn(() =>
        of({ orderId: 'order-1', status: 'ACCEPTED_AWAITING_CONFIRMATION' }),
      ),
      cancelOrder: vi.fn(() => of(undefined)),
    };
    TestBed.configureTestingModule({
      providers: [
        PaymentReturnService,
        provideRouter([], withComponentInputBinding()),
        { provide: OrderApi, useValue: api },
        {
          provide: AuthStore,
          useValue: {
            status,
            identity,
            registerCleanup: (hook: () => void) => {
              cleanup = hook;
              return vi.fn();
            },
          },
        },
      ],
    });
    pending = TestBed.inject(PendingCheckoutStore);
    pending.save({ orderId: 'order-1', listingId: 'listing-1', startedAt: '2026-10-13T08:00:00Z' });
    service = TestBed.inject(PaymentReturnService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    sessionStorage.clear();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('reads the order before one capture and polls at exactly two-second intervals', () => {
    service.start('return', true);
    expect(api.order).toHaveBeenCalledExactlyOnceWith('order-1');
    expect(api.capturePayment).toHaveBeenCalledExactlyOnceWith('order-1');
    expect(service.state()).toBe('processing');
    vi.advanceTimersByTime(1999);
    expect(api.order).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1);
    expect(api.order).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(2000);
    expect(api.order).toHaveBeenCalledTimes(3);
    expect(api.capturePayment).toHaveBeenCalledTimes(1);
  });

  it.each(['PAID', 'SHIPPED', 'DELIVERED'] as const)(
    'recognizes already %s before capture',
    (state) => {
      api.order.mockReturnValue(of(order(state)));
      service.start('return', true);
      expect(service.state()).toBe('success');
      expect(api.capturePayment).not.toHaveBeenCalled();
      expect(pending.read()).toBeNull();
      vi.advanceTimersByTime(40000);
      expect(api.order).toHaveBeenCalledTimes(1);
    },
  );

  it.each(['PAID', 'SHIPPED', 'DELIVERED'] as const)(
    'converges directly to %s after capture, including software delivery',
    (state) => {
      api.order
        .mockReturnValueOnce(of(order()))
        .mockReturnValue(of(order(state, { listingType: 'SOFTWARE' })));
      service.start('return', true);
      vi.advanceTimersByTime(2000);
      expect(service.state()).toBe('success');
      expect(service.order()?.listingType).toBe('SOFTWARE');
      expect(pending.read()).toBeNull();
      expect(api.capturePayment).toHaveBeenCalledTimes(1);
    },
  );

  it.each([
    ['CANCELLED', 'cancelled'],
    ['REFUNDED', 'refunded'],
  ] as const)('keeps %s distinct and clears pending correlation', (state, result) => {
    api.order.mockReturnValue(of(order(state)));
    service.start('return', true);
    expect(service.state()).toBe(result);
    expect(pending.read()).toBeNull();
    expect(api.capturePayment).not.toHaveBeenCalled();
  });

  it('times out at thirty seconds and cancels an in-flight poll without clearing recovery correlation', () => {
    const poll = new Subject<OrderView>();
    api.order.mockReturnValueOnce(of(order())).mockReturnValue(poll);
    service.start('return', true);
    vi.advanceTimersByTime(2000);
    expect(poll.observed).toBe(true);
    vi.advanceTimersByTime(27999);
    expect(service.state()).toBe('processing');
    vi.advanceTimersByTime(1);
    expect(service.state()).toBe('awaiting');
    expect(poll.observed).toBe(false);
    expect(service.order()?.id).toBe('order-1');
    expect(pending.read()?.orderId).toBe('order-1');
    vi.advanceTimersByTime(30000);
    expect(api.order).toHaveBeenCalledTimes(2);
  });

  it('never overlaps slow status reads', () => {
    const poll = new Subject<OrderView>();
    api.order.mockReturnValueOnce(of(order())).mockReturnValue(poll);
    service.start('return', true);
    vi.advanceTimersByTime(10000);
    expect(api.order).toHaveBeenCalledTimes(2);
  });

  it('bounds a slow capture and never treats an unfinished response as accepted', () => {
    const capture = new Subject();
    api.capturePayment.mockReturnValue(capture);
    service.start('return', true);
    vi.advanceTimersByTime(30000);
    expect(capture.observed).toBe(false);
    expect(service.state()).toBe('awaiting');
    expect(service.accepted()).toBe(false);
    expect(api.order).toHaveBeenCalledTimes(1);
  });

  it('bounds a slow initial order lookup without inventing payment acceptance', () => {
    const lookup = new Subject();
    api.order.mockReturnValue(lookup);
    service.start('return', true);
    vi.advanceTimersByTime(30000);
    expect(lookup.observed).toBe(false);
    expect(service.state()).toBe('failed');
    expect(service.accepted()).toBe(false);
    expect(api.capturePayment).not.toHaveBeenCalled();
  });

  it('does not overlap duplicate start or retry calls', () => {
    const capture = new Subject<{ orderId: string; status: 'ACCEPTED_AWAITING_CONFIRMATION' }>();
    api.capturePayment.mockReturnValue(capture);
    service.start('return', true);
    service.start('return', true);
    service.retry();
    expect(api.order).toHaveBeenCalledTimes(1);
    expect(api.capturePayment).toHaveBeenCalledTimes(1);
  });

  it('rechecks authoritative state before retrying a failed capture', () => {
    api.capturePayment
      .mockReturnValueOnce(
        throwError(() => ({ status: 503, code: 'provider-error', detail: 'sensitive raw token' })),
      )
      .mockReturnValue(of({ orderId: 'order-1', status: 'ACCEPTED_AWAITING_CONFIRMATION' }));
    service.start('return', true);
    expect(service.state()).toBe('failed');
    expect(service.error()).not.toContain('sensitive');
    service.retry();
    expect(api.order).toHaveBeenCalledTimes(2);
    expect(api.capturePayment).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(2000);
    expect(api.capturePayment).toHaveBeenCalledTimes(2);
  });

  it('offers a status-only retry that cannot capture again', () => {
    service.start('return', true);
    vi.advanceTimersByTime(30000);
    api.order.mockReturnValue(of(order('PAID')));
    service.checkStatus();
    expect(service.state()).toBe('success');
    expect(api.capturePayment).toHaveBeenCalledTimes(1);
  });

  it('cancels an awaiting order through the cancellation endpoint and never captures', () => {
    api.order.mockReturnValueOnce(of(order())).mockReturnValue(of(order('CANCELLED')));
    service.start('cancel', true);
    expect(api.cancelOrder).toHaveBeenCalledExactlyOnceWith('order-1');
    expect(api.capturePayment).not.toHaveBeenCalled();
    expect(service.state()).toBe('cancelled');
    expect(pending.read()).toBeNull();
  });

  it.each(['PAID', 'SHIPPED', 'DELIVERED', 'REFUNDED'] as const)(
    'refetches cancellation conflict and displays actual %s',
    (state) => {
      api.order.mockReturnValueOnce(of(order())).mockReturnValue(of(order(state)));
      api.cancelOrder.mockReturnValue(
        throwError(() => ({ status: 409, code: 'order-cancellation-conflict' })),
      );
      service.start('cancel', true);
      expect(service.state()).toBe(state === 'REFUNDED' ? 'refunded' : 'success');
      expect(api.capturePayment).not.toHaveBeenCalled();
      expect(pending.read()).toBeNull();
    },
  );

  it('keeps cancellation failure recoverable without asserting that the payment was not charged', () => {
    api.cancelOrder.mockReturnValue(
      throwError(() => ({ status: 503, code: 'service-unavailable' })),
    );
    service.start('cancel', true);
    expect(service.state()).toBe('failed');
    expect(service.error()).not.toMatch(/not charged|nothing was charged|uncharged/i);
    expect(service.order()?.id).toBe('order-1');
    expect(api.capturePayment).not.toHaveBeenCalled();
  });

  it.each(['return', 'cancel'] as const)(
    'never mutates either pending purchase on %s when checkout correlation was lost',
    (mode) => {
      pending.clear();
      api.purchases.mockReturnValue(
        of({
          items: [
            order('AWAITING_PAYMENT', { id: 'checkout-a' }),
            order('AWAITING_PAYMENT', { id: 'checkout-b', createdAt: '2026-10-13T08:01:00Z' }),
          ],
          nextCursor: 'older',
        }),
      );
      service.start(mode, true);
      service.retry();
      vi.advanceTimersByTime(40000);
      expect(service.state()).toBe('missing');
      expect(service.order()).toBeNull();
      expect(api.purchases).not.toHaveBeenCalled();
      expect(api.order).not.toHaveBeenCalled();
      expect(api.capturePayment).not.toHaveBeenCalled();
      expect(api.cancelOrder).not.toHaveBeenCalled();
    },
  );
  it.each(['return', 'cancel'] as const)(
    'only reads the order on a %s visit without a PayPal redirect',
    (mode) => {
      service.start(mode, false);
      service.retry();
      vi.advanceTimersByTime(40000);
      expect(api.order).toHaveBeenCalled();
      expect(api.cancelOrder).not.toHaveBeenCalled();
      expect(api.capturePayment).not.toHaveBeenCalled();
      expect(service.state()).toBe('awaiting');
      expect(service.order()?.id).toBe('order-1');
    },
  );
  it.each([false, true])(
    'renders missing context safely when provider arrival is %s and no correlation exists',
    (fromProvider) => {
      pending.clear();
      service.start('return', fromProvider);
      expect(service.state()).toBe('missing');
      expect(api.capturePayment).not.toHaveBeenCalled();
      expect(api.purchases).not.toHaveBeenCalled();
    },
  );

  it.each([
    { orderId: '../foreign', listingId: 'listing-1', startedAt: '2026-10-13T08:00:00Z' },
    { orderId: 'order-1', listingId: 'listing-1', startedAt: 'invalid' },
    { orderId: 'order-1', listingId: 'listing-1', startedAt: '2025-01-01T00:00:00Z' },
  ])('rejects invalid stored correlation without a fallback mutation: %j', (stored) => {
    pending.save(stored);
    service.start('return', true);
    expect(service.state()).toBe('missing');
    expect(api.order).not.toHaveBeenCalled();
    expect(api.capturePayment).not.toHaveBeenCalled();
  });

  it('rejects a correlated order owned by another buyer', () => {
    api.order.mockReturnValue(of(order('AWAITING_PAYMENT', { buyerSub: 'other' })));
    service.start('return', true);
    expect(service.state()).toBe('missing');
    expect(service.order()).toBeNull();
    expect(api.capturePayment).not.toHaveBeenCalled();
  });

  it('invalidates all callbacks, requests and timers on logout', () => {
    const poll = new Subject<OrderView>();
    api.order.mockReturnValueOnce(of(order())).mockReturnValue(poll);
    service.start('return', true);
    vi.advanceTimersByTime(2000);
    status.set('guest');
    identity.set(null);
    cleanup();
    expect(poll.observed).toBe(false);
    poll.next(order('PAID'));
    expect(service.order()).toBeNull();
    vi.advanceTimersByTime(30000);
    expect(api.order).toHaveBeenCalledTimes(2);
  });

  it('rejects a late lookup before cleanup runs after a subject change', () => {
    const lookup = new Subject<OrderView>();
    api.order.mockReturnValue(lookup);
    service.start('return', true);
    identity.set({ sub: 'another', groups: [] });
    lookup.next(order());
    expect(api.capturePayment).not.toHaveBeenCalled();
    expect(service.order()).toBeNull();
  });

  it('tears down capture and deadline on destroy', () => {
    const capture = new Subject();
    api.capturePayment.mockReturnValue(capture);
    service.start('return', true);
    TestBed.resetTestingModule();
    expect(capture.observed).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('payment outcome routes', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });
  it.each(['return', 'cancel'] as const)(
    'renders %s before the listing parameter and replaces sensitive history',
    async (mode) => {
      const start = vi.fn();
      const state = signal('missing');
      TestBed.configureTestingModule({
        providers: [
          provideRouter([
            { path: 'checkout/return', children: PAYMENT_RETURN_ROUTES },
            { path: 'checkout/cancel', children: PAYMENT_CANCEL_ROUTES },
          ]),
        ],
      });
      TestBed.overrideComponent(PaymentReturnPage, {
        set: {
          providers: [
            {
              provide: PaymentReturnService,
              useValue: {
                state,
                order: signal(null),
                error: signal(''),
                busy: signal(false),
                start,
                retry: vi.fn(),
                checkStatus: vi.fn(),
              },
            },
          ],
        },
      });
      const harness = await RouterTestingHarness.create();
      const router = TestBed.inject(Router);
      const navigate = vi.spyOn(router, 'navigate');
      await harness.navigateByUrl(
        `/checkout/${mode}?token=PROVIDER_SECRET&PayerID=PAYER_SECRET#private`,
        PaymentReturnPage,
      );
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(start).toHaveBeenCalledExactlyOnceWith(mode, true);
      expect(router.url).toBe(`/checkout/${mode}`);
      expect(navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({ replaceUrl: true, queryParams: {}, fragment: undefined }),
      );
      expect(harness.routeNativeElement?.textContent).not.toMatch(/PROVIDER_SECRET|PAYER_SECRET/);
      expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe(
        "We can't find this checkout",
      );
    },
  );
});
