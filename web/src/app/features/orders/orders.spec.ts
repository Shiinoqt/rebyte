import { afterEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { OrderView, OfferView, OfferStatus } from '../../core/api/api-models';
import { OrdersPage } from './orders.page';
import { OffersPage, eligibleOfferActions } from '../offers/offers.page';
import { ORDERS_ROUTES } from './orders.routes';
import { OFFERS_ROUTES } from '../offers/offers.routes';

describe('standalone account workflows', () => {
  it('routes orders to its working account page', () => {
    expect(ORDERS_ROUTES[0].children?.[0].component?.name).toContain('OrdersPage');
  });
  it('routes offers to its working account page', () => {
    expect(OFFERS_ROUTES[0].children?.[0].component?.name).toContain('OffersPage');
  });
});

const order = (id: string, overrides: Partial<OrderView> = {}): OrderView => ({
  id,
  listingId: 'listing',
  listingTitle: 'Laptop',
  listingType: 'HARDWARE',
  buyerSub: 'buyer',
  sellerSub: 'seller',
  pricePaid: '20.00',
  itemPrice: '20.00',
  shippingCost: '0.00',
  paymentMethod: 'PAYPAL',
  status: 'PAID',
  createdAt: '2026-10-01T10:00:00Z',
  expiryAt: '2026-10-02T10:00:00Z',
  ...overrides,
});
const offer = (status: OfferStatus = 'PENDING'): OfferView => ({
  id: 'offer',
  listingId: 'listing',
  listingTitle: 'Laptop',
  buyerSub: 'buyer',
  sellerSub: 'seller',
  amount: '20.00',
  askingPrice: '25.00',
  status,
  rejectionReason: null,
  expiresAt: null,
  orderId: null,
  createdAt: '2026-10-01T10:00:00Z',
});

describe('account order and offer workflows', () => {
  afterEach(() => TestBed.resetTestingModule());
  function setup(api: object, sub = 'buyer') {
    let cleanup = () => {};
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: OrderApi, useValue: api },
        {
          provide: AuthStore,
          useValue: {
            identity: signal({ sub }),
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
  it('shows purchase and sales empty states and quieter filtered copy', () => {
    setup({
      purchases: () => of({ items: [], nextCursor: null }),
      sales: () => of({ items: [], nextCursor: null }),
    });
    const fixture = TestBed.createComponent(OrdersPage);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('app-empty-state h2')?.textContent).toBe('No purchases yet');
    expect(root.querySelector('app-empty-state p')?.textContent).toBe(
      'Items you buy will appear here.',
    );
    fixture.componentInstance.selectRole('sales');
    fixture.detectChanges();
    expect(root.querySelector('app-empty-state h2')?.textContent).toBe('No sales yet');
    expect(root.querySelector('app-empty-state p')?.textContent).toBe(
      'When someone buys one of your listings it will appear here.',
    );
    fixture.componentInstance.status.set('REFUNDED');
    fixture.detectChanges();
    expect(root.querySelector('app-empty-state')).toBeNull();
    expect(root.querySelector('p.muted')?.textContent).toBe('No matching loaded orders');
  });
  it('shows an offers empty state and quieter filtered copy', () => {
    setup({ receivedOffers: () => of({ items: [], nextCursor: null }) });
    const fixture = TestBed.createComponent(OffersPage);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('app-empty-state h2')?.textContent).toBe('No offers yet');
    expect(root.querySelector('app-empty-state p')?.textContent).toBe(
      'Offers you make or receive on listings will appear here.',
    );
    fixture.componentInstance.status.set('WITHDRAWN');
    fixture.detectChanges();
    expect(root.querySelector('app-empty-state')).toBeNull();
    expect(root.querySelector('p.muted')?.textContent).toBe('No matching loaded offers');
  });
  it.each([
    ['sent', 1, 0],
    ['other', 0, 1],
  ])('opens the offers page on the role from ?role=%s', (role, sent, received) => {
    const receivedOffers = vi.fn(() => of({ items: [], nextCursor: null }));
    const sentOffers = vi.fn(() => of({ items: [], nextCursor: null }));
    setup({ receivedOffers, sentOffers });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { queryParamMap: convertToParamMap({ role }) } },
    });
    const page = TestBed.createComponent(OffersPage).componentInstance;
    expect(page.role()).toBe(sent ? 'sent' : 'received');
    expect(sentOffers).toHaveBeenCalledTimes(sent);
    expect(receivedOffers).toHaveBeenCalledTimes(received);
  });
  it('retains independent role cursors and follows empty purchase continuation', () => {
    const purchases = vi
      .fn()
      .mockReturnValueOnce(of({ items: [order('one')], nextCursor: 'p1' }))
      .mockReturnValueOnce(of({ items: [], nextCursor: 'p2' }))
      .mockReturnValueOnce(of({ items: [order('one'), order('two')], nextCursor: null }));
    const sales = vi.fn(() => of({ items: [order('sale')], nextCursor: 's1' }));
    setup({ purchases, sales });
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    page.selectRole('sales');
    expect(page.state().nextCursor).toBe('s1');
    page.selectRole('purchases');
    expect(purchases).toHaveBeenCalledTimes(1);
    page.load(true);
    expect(page.state().nextCursor).toBe('p2');
    page.load(true);
    expect(page.state().items.map((row) => row.id)).toEqual(['one', 'two']);
    expect(purchases.mock.calls[2][0]).toEqual({ size: 20, cursor: 'p2' });
    expect(sales).toHaveBeenCalledTimes(1);
  });
  it('retries failed append at the same cursor and never sends status filters', () => {
    const purchases = vi
      .fn()
      .mockReturnValueOnce(of({ items: [order('one')], nextCursor: 'p1' }))
      .mockReturnValueOnce(throwError(() => new Error('offline')))
      .mockReturnValueOnce(of({ items: [], nextCursor: 'p2' }));
    setup({ purchases });
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    page.status.set('REFUNDED');
    page.load(true);
    expect(page.state().items).toHaveLength(1);
    page.retry();
    expect(purchases.mock.calls[1][0]).toEqual({ size: 20, cursor: 'p1' });
    expect(purchases.mock.calls[2][0]).toEqual({ size: 20, cursor: 'p1' });
    expect(page.visible()).toEqual([]);
    expect(page.state().nextCursor).toBe('p2');
  });
  it('clears role data and cancels in-flight reads on session cleanup', () => {
    const source = new Subject<{ items: OrderView[]; nextCursor: string | null }>();
    const clear = setup({ purchases: () => source });
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    clear();
    source.next({ items: [order('secret')], nextCursor: null });
    expect(page.state().items).toEqual([]);
    expect(source.observed).toBe(false);
  });
  it('uses contextual detail actions without assuming omitted reviews are absent', () => {
    setup({ purchases: () => of({ items: [], nextCursor: null }) }, 'seller');
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    expect(page.actionLabel(order('one'))).toBe('Mark as shipped');
    expect(page.actionLabel(order('one', { listingType: 'SOFTWARE' }))).toBe('View order');
    expect(page.actionLabel(order('one', { status: 'DELIVERED' }))).toBe('View order');
    expect(page.actionLabel(order('one', { status: 'SHIPPED' }))).toBe('View order');
  });
  it('refreshes every loaded order page atomically while retaining the status filter', () => {
    const tail = new Subject<{ items: OrderView[]; nextCursor: string | null }>();
    const purchases = vi
      .fn()
      .mockReturnValueOnce(of({ items: [order('one')], nextCursor: 'old' }))
      .mockReturnValueOnce(of({ items: [order('two')], nextCursor: null }))
      .mockReturnValueOnce(of({ items: [order('new'), order('one')], nextCursor: 'fresh' }))
      .mockReturnValueOnce(tail);
    setup({ purchases });
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    page.load(true);
    page.status.set('REFUNDED');
    expect(page.statuses()).toContain('REFUNDED');
    expect(page.autoReload()).toBe(true);
    expect(page.autoReload()).toBe(false);
    expect(page.state().items.map((row) => row.id)).toEqual(['one', 'two']);
    expect(page.state().loading).toBe(false);
    tail.next({ items: [order('two', { status: 'REFUNDED' })], nextCursor: 'remaining' });
    tail.complete();
    expect(page.state().items.map((row) => row.id)).toEqual(['new', 'one', 'two']);
    expect(page.visible().map((row) => row.id)).toEqual(['two']);
    expect(page.state().nextCursor).toBe('remaining');
    expect(page.refreshing()).toBe(false);
    expect(purchases.mock.calls[3][0]).toEqual({ size: 20, cursor: 'fresh' });
  });
  it('cancels background orders on role change and logout and retains rows on refresh errors', () => {
    const source = new Subject<{ items: OrderView[]; nextCursor: string | null }>();
    const purchases = vi
      .fn()
      .mockReturnValueOnce(of({ items: [order('one')], nextCursor: null }))
      .mockReturnValueOnce(throwError(() => new Error('offline')))
      .mockReturnValue(source);
    const clear = setup({
      purchases,
      sales: () => of({ items: [order('sale')], nextCursor: null }),
    });
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    page.autoReload();
    expect(page.state().items[0].id).toBe('one');
    expect(page.state().error).toBe(false);
    page.autoReload();
    page.selectRole('sales');
    expect(source.observed).toBe(false);
    expect(page.state().items[0].id).toBe('sale');
    page.selectRole('purchases');
    page.autoReload();
    clear();
    expect(source.observed).toBe(false);
    expect(page.autoReload()).toBe(false);
    expect(page.state().items).toEqual([]);
  });
  it('defers offer updates during decisions and cancels an update before opening a dialog', () => {
    const source = new Subject<{ items: OfferView[]; nextCursor: string | null }>();
    const receivedOffers = vi
      .fn()
      .mockReturnValueOnce(of({ items: [offer()], nextCursor: null }))
      .mockReturnValueOnce(source)
      .mockReturnValue(of({ items: [offer('WITHDRAWN')], nextCursor: null }));
    setup({ receivedOffers }, 'seller');
    const page = TestBed.createComponent(OffersPage).componentInstance;
    page.autoReload();
    expect(source.observed).toBe(true);
    page.choose(offer(), 'accept');
    expect(source.observed).toBe(false);
    expect(page.autoReload()).toBe(false);
    expect(receivedOffers).toHaveBeenCalledTimes(2);
    page.close();
    page.busy.set(true);
    expect(page.autoReload()).toBe(false);
    page.busy.set(false);
    page.status.set('WITHDRAWN');
    expect(page.autoReload()).toBe(true);
    expect(page.visible()[0].status).toBe('WITHDRAWN');
  });
  it('links only the shipped hardware buyer to receipt confirmation', () => {
    setup({ purchases: () => of({ items: [], nextCursor: null }) }, 'buyer');
    const page = TestBed.createComponent(OrdersPage).componentInstance;
    expect(page.actionLabel(order('one', { status: 'SHIPPED' }))).toBe('Confirm receipt');
    expect(page.actionLabel(order('one', { status: 'PAID' }))).toBe('View order');
    expect(page.actionLabel(order('one', { status: 'SHIPPED', listingType: 'SOFTWARE' }))).toBe(
      'View order',
    );
    expect(
      page.actionLabel(
        order('one', { status: 'SHIPPED', refundRequestedAt: '2026-10-09T10:00:00Z' }),
      ),
    ).toBe('View order');
  });
  it.each(['PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'WITHDRAWN'] as OfferStatus[])(
    'enforces the complete %s offer action matrix',
    (status) => {
      expect(eligibleOfferActions(offer(status), 'seller', 'received')).toEqual(
        status === 'PENDING' ? ['accept', 'reject'] : [],
      );
      expect(eligibleOfferActions(offer(status), 'buyer', 'sent')).toEqual(
        status === 'PENDING' ? ['withdraw'] : [],
      );
      expect(eligibleOfferActions(offer(status), 'stranger', 'received')).toEqual([]);
    },
  );
  it('confirms commands, blocks duplicate clicks, refetches conflicts and retains sent state', () => {
    const command = new Subject<OfferView>();
    const acceptOffer = vi.fn(() => command);
    const receivedOffers = vi.fn(() => of({ items: [offer()], nextCursor: 'r1' }));
    const sentOffers = vi.fn(() => of({ items: [offer('EXPIRED')], nextCursor: 's1' }));
    setup({ receivedOffers, sentOffers, acceptOffer }, 'seller');
    const page = TestBed.createComponent(OffersPage).componentInstance;
    page.selectRole('sent');
    page.selectRole('received');
    expect(receivedOffers).toHaveBeenCalledTimes(1);
    page.choose(offer(), 'accept');
    expect(acceptOffer).not.toHaveBeenCalled();
    page.confirm();
    page.confirm();
    expect(acceptOffer).toHaveBeenCalledTimes(1);
    command.error({
      status: 409,
      code: 'offer-not-pending',
      type: '',
      extras: {},
      fieldErrors: {},
    });
    expect(page.message()).toBe('This offer was already answered.');
    expect(receivedOffers).toHaveBeenCalledTimes(2);
    expect(page.busy()).toBe(false);
    page.selectRole('sent');
    expect(page.state().nextCursor).toBe('s1');
    expect(sentOffers).toHaveBeenCalledTimes(1);
  });
  it('permits checkout only for the accepted buyer with a live hold and no existing order', () => {
    setup({
      receivedOffers: () => of({ items: [], nextCursor: null }),
      sentOffers: () => of({ items: [], nextCursor: null }),
    });
    const page = TestBed.createComponent(OffersPage).componentInstance;
    page.selectRole('sent');
    const accepted = {
      ...offer('ACCEPTED'),
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    };
    expect(page.canCheckout(accepted)).toBe(true);
    expect(page.canCheckout({ ...accepted, buyerSub: 'other' })).toBe(false);
    expect(page.canCheckout({ ...accepted, orderId: 'order' })).toBe(false);
    expect(page.canCheckout({ ...accepted, expiresAt: null })).toBe(false);
    page.now.set(Date.now() + 120000);
    expect(page.canCheckout(accepted)).toBe(false);
    expect(page.countdown(accepted)).toBe('Payment hold expired');
  });
});
