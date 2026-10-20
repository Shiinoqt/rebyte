import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpHeaders, HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CatalogApi } from '../../../core/api/catalog-api';
import { OrderApi } from '../../../core/api/order-api';
import { MessagingApi } from '../../../core/api/messaging-api';
import { AuthStore } from '../../../core/auth/auth.store';
import { AppConfigService } from '../../../core/config/app-config';
import { DriftReport, RebuildStatusResponse, StuckOrderItem } from '../../../core/api/api-models';
import { AdminOperationsService, REPLAY_QUEUES, POLL_LIMIT_MS } from './admin-operations.service';
import { AdminOperationsPage } from './admin-operations.page';
import { ADMIN_OPERATIONS_ROUTES } from '../admin-operations.routes';

const idle: RebuildStatusResponse = {
  running: false,
  startedAt: null,
  completedAt: null,
  error: null,
};
const running: RebuildStatusResponse = {
  ...idle,
  running: true,
  startedAt: '2026-10-13T10:00:00Z',
};
const completed = { ...running, running: false, completedAt: '2026-10-13T10:01:00Z' };
const item = (category: StuckOrderItem['category'] = 'failed_create'): StuckOrderItem => ({
  orderId: 'order/123',
  category,
  createdAt: '2026-10-12T10:00:00Z',
  expiryAt: '2026-10-12T11:00:00Z',
  cancellationReason: null,
  lastPayPalStatus: null,
});
const failure = (status: number, code = 'unknown') => ({
  status,
  code,
  fieldErrors: {},
  extras: {},
});
function setup(admin = true) {
  const identity = signal({ sub: 'admin-a', groups: admin ? ['ADMIN'] : [] });
  const hooks = new Set<() => void>();
  const auth = {
    identity,
    isAdmin: computed(() => identity().groups.includes('ADMIN')),
    registerCleanup: (fn: () => void) => {
      hooks.add(fn);
      return () => hooks.delete(fn);
    },
  };
  const catalog = {
    searchRebuildStatus: vi.fn(() => of(idle)),
    rebuildSearch: vi.fn(() => of(running)),
    checkSearchDrift: vi.fn(() =>
      of<DriftReport>({ missingFromIndex: [], missingFromSource: [], staleDocuments: {} }),
    ),
  };
  const order = {
    stuckOrders: vi.fn(() => of({ items: [item()], nextCursor: null as string | null })),
    adminRefundOrder: vi.fn(() => of({ orderId: 'order/123', status: 'PENDING' })),
    replayDeadLetter: vi.fn(() => of(new HttpResponse<void>({ status: 204 }))),
  };
  const messaging = { replayDeadLetter: vi.fn(() => of(new HttpResponse<void>({ status: 204 }))) };
  TestBed.configureTestingModule({
    providers: [
      AdminOperationsService,
      provideRouter([]),
      { provide: AuthStore, useValue: auth },
      { provide: CatalogApi, useValue: catalog },
      { provide: OrderApi, useValue: order },
      { provide: MessagingApi, useValue: messaging },
    ],
  });
  const store = TestBed.inject(AdminOperationsService);
  TestBed.tick();
  return { store, catalog, order, messaging, identity, hooks };
}
afterEach(() => {
  TestBed.resetTestingModule();
  vi.useRealTimers();
});

describe('search maintenance', () => {
  it('requires a fresh successful status read before rebuilding', () => {
    const { store, catalog } = setup();
    catalog.searchRebuildStatus.mockReturnValue(throwError(() => failure(503)));
    store.refreshSearch();
    store.rebuild(true);
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
  });
  it('routes the existing lazy child to the implemented page', () => {
    expect(ADMIN_OPERATIONS_ROUTES[0].component).toBe(AdminOperationsPage);
  });
  it('loads authoritative status and orders only for admins', () => {
    const { store, catalog, order } = setup(false);
    store.refreshSearch();
    store.refreshOrders();
    store.rebuild(true);
    store.checkDrift();
    store.refund('order/123', 'reason', true);
    store.replay('order', REPLAY_QUEUES.order[0], true);
    expect(catalog.searchRebuildStatus).not.toHaveBeenCalled();
    expect(order.stuckOrders).not.toHaveBeenCalled();
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
    expect(catalog.checkSearchDrift).not.toHaveBeenCalled();
    expect(order.adminRefundOrder).not.toHaveBeenCalled();
    expect(order.replayDeadLetter).not.toHaveBeenCalled();
  });
  it('requires confirmation and prevents overlapping writes', () => {
    const { store, catalog, order } = setup();
    store.rebuild(false);
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
    const pending = new Subject<RebuildStatusResponse>();
    catalog.rebuildSearch.mockReturnValue(pending);
    store.rebuild(true);
    store.rebuild(true);
    store.checkDrift();
    store.refund('order/123', 'reason', true);
    store.replay('order', REPLAY_QUEUES.order[0], true);
    expect(catalog.rebuildSearch).toHaveBeenCalledOnce();
    expect(catalog.checkSearchDrift).not.toHaveBeenCalled();
    expect(order.adminRefundOrder).not.toHaveBeenCalled();
    expect(order.replayDeadLetter).not.toHaveBeenCalled();
  });
  it.each([completed, { ...running, running: false, error: 'private internal details' }])(
    'polls at three seconds then stops on a terminal result',
    (terminal) => {
      vi.useFakeTimers();
      const { store, catalog } = setup();
      catalog.searchRebuildStatus.mockReturnValue(of(terminal));
      store.rebuild(true);
      vi.advanceTimersByTime(2999);
      expect(catalog.searchRebuildStatus).toHaveBeenCalledOnce();
      vi.advanceTimersByTime(1);
      expect(store.search()).toEqual(terminal);
      vi.advanceTimersByTime(9000);
      expect(catalog.searchRebuildStatus).toHaveBeenCalledTimes(2);
    },
  );
  it('bounds polling and cancels an in-flight status request at the deadline', () => {
    vi.useFakeTimers();
    const { store, catalog } = setup();
    const pending = new Subject<RebuildStatusResponse>();
    catalog.searchRebuildStatus.mockReturnValue(pending);
    store.rebuild(true);
    vi.advanceTimersByTime(3000);
    expect(pending.observed).toBe(true);
    vi.advanceTimersByTime(POLL_LIMIT_MS);
    expect(pending.observed).toBe(false);
    expect(store.pollExpired()).toBe(true);
    const count = catalog.searchRebuildStatus.mock.calls.length;
    vi.advanceTimersByTime(POLL_LIMIT_MS);
    expect(catalog.searchRebuildStatus).toHaveBeenCalledTimes(count);
  });
  it('does not overlap slow polling reads or continue after a read failure', () => {
    vi.useFakeTimers();
    const { store, catalog } = setup();
    const pending = new Subject<RebuildStatusResponse>();
    catalog.searchRebuildStatus.mockReturnValue(pending);
    store.rebuild(true);
    vi.advanceTimersByTime(12000);
    expect(catalog.searchRebuildStatus).toHaveBeenCalledTimes(2);
    pending.error(failure(503));
    vi.advanceTimersByTime(12000);
    expect(catalog.searchRebuildStatus).toHaveBeenCalledTimes(2);
    expect(store.searchError()).not.toBe('');
  });
  it('reconciles a conflicting rebuild with a read without retrying the mutation', () => {
    const { store, catalog } = setup();
    catalog.rebuildSearch.mockReturnValue(throwError(() => failure(409)));
    catalog.searchRebuildStatus.mockReturnValue(of(running));
    store.rebuild(true);
    expect(catalog.rebuildSearch).toHaveBeenCalledOnce();
    expect(store.search()?.running).toBe(true);
    expect(store.message()).toMatch(/already running/i);
  });
  it('retains full drift diagnostics and never starts repair', () => {
    const { store, catalog } = setup();
    const report = {
      missingFromIndex: ['a'],
      missingFromSource: ['b'],
      staleDocuments: { c: { sourceSequence: 8, indexedSequence: 3 } },
    };
    catalog.checkSearchDrift.mockReturnValue(of(report));
    store.checkDrift();
    expect(store.drift()).toEqual(report);
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
  });
  it('cancels polling and reads on destruction', () => {
    vi.useFakeTimers();
    const { store, catalog, order } = setup();
    const pending = new Subject<{ items: StuckOrderItem[]; nextCursor: string | null }>();
    order.stuckOrders.mockReturnValue(pending);
    store.refreshOrders();
    store.rebuild(true);
    TestBed.resetTestingModule();
    expect(pending.observed).toBe(false);
    vi.advanceTimersByTime(9000);
    expect(catalog.searchRebuildStatus).toHaveBeenCalledOnce();
  });
});

describe('order reconciliation and replay', () => {
  it('continues across empty pages using nextCursor', () => {
    const { store, order } = setup();
    order.stuckOrders.mockReturnValueOnce(of({ items: [], nextCursor: 'opaque cursor' }));
    store.refreshOrders();
    expect(store.orders()).toEqual([]);
    expect(store.nextCursor()).toBe('opaque cursor');
    order.stuckOrders.mockReturnValueOnce(of({ items: [item()], nextCursor: null }));
    store.loadMore();
    expect(order.stuckOrders).toHaveBeenLastCalledWith({ size: 20, cursor: 'opaque cursor' });
    expect(store.orders()).toHaveLength(1);
    expect(store.nextCursor()).toBeNull();
  });
  it.each(['failed_create', 'captured_awaiting_webhook', 'awaiting_timeout'] as const)(
    'preserves the %s classification',
    (category) => {
      const { store, order } = setup();
      order.stuckOrders.mockReturnValue(of({ items: [item(category)], nextCursor: null }));
      store.refreshOrders();
      expect(store.orders()[0].category).toBe(category);
    },
  );
  it('preserves current rows and cursor when a continuation fails', () => {
    const { store, order } = setup();
    order.stuckOrders.mockReturnValueOnce(of({ items: [item()], nextCursor: 'next' }));
    store.refreshOrders();
    order.stuckOrders.mockReturnValueOnce(throwError(() => failure(503)));
    store.loadMore();
    expect(store.orders()).toHaveLength(1);
    expect(store.nextCursor()).toBe('next');
    expect(store.ordersError()).not.toBe('');
  });
  it('requires a nonblank reason and confirmation and sends only the reason DTO', () => {
    const { store, order } = setup();
    store.refund('order/123', '  ', true);
    expect(store.reasonError()).not.toBe('');
    store.refund('order/123', 'reason', false);
    expect(order.adminRefundOrder).not.toHaveBeenCalled();
    store.refund('order/123', '  Approved by support  ', true);
    expect(order.adminRefundOrder).toHaveBeenCalledWith('order/123', {
      reason: 'Approved by support',
    });
    expect(store.refundResult()).toEqual({ orderId: 'order/123', status: 'PENDING' });
  });
  it('maps refund conflicts and unknown outcomes without retrying', () => {
    const { store, order } = setup();
    order.adminRefundOrder.mockReturnValueOnce(
      throwError(() => failure(409, 'refund-not-eligible')),
    );
    store.refund('order/123', 'reason', true);
    expect(store.message()).toMatch(/eligible|changed/i);
    order.adminRefundOrder.mockReturnValueOnce(
      throwError(() => failure(503, 'refund-unavailable')),
    );
    store.refund('order/123', 'reason', true);
    expect(store.message()).toMatch(/could not confirm|unknown/i);
    expect(order.adminRefundOrder).toHaveBeenCalledTimes(2);
  });
  for (const service of ['order', 'messaging'] as const) {
    it.each(REPLAY_QUEUES[service])(`replays the allowlisted ${service} queue %s`, (queue) => {
      const { store, order, messaging } = setup();
      store.replay(service, queue, true);
      expect((service === 'order' ? order : messaging).replayDeadLetter).toHaveBeenCalledWith(
        queue,
      );
    });
    it(`distinguishes empty and replayed ${service} results with audit headers`, () => {
      const { store, order, messaging } = setup();
      const api = service === 'order' ? order : messaging;
      store.replay(service, REPLAY_QUEUES[service][0], true);
      expect(store.replayResult()?.outcome).toBe('empty');
      api.replayDeadLetter.mockReturnValue(
        of(
          new HttpResponse<void>({
            status: 200,
            headers: new HttpHeaders({ 'X-Audit-Id': 'audit-123' }),
          }),
        ),
      );
      store.replay(service, REPLAY_QUEUES[service][0], true);
      expect(store.replayResult()?.outcome).toBe('replayed');
      expect(store.replayResult()?.auditId).toBe('audit-123');
    });
  }
  it('rejects arbitrary, cross-service and unconfirmed queues', () => {
    const { store, order, messaging } = setup();
    store.replay('order', '../secret', true);
    store.replay('order', REPLAY_QUEUES.messaging[0], true);
    store.replay('messaging', REPLAY_QUEUES.order[0], true);
    store.replay('order', REPLAY_QUEUES.order[0], false);
    expect(order.replayDeadLetter).not.toHaveBeenCalled();
    expect(messaging.replayDeadLetter).not.toHaveBeenCalled();
  });
  it.each([404, 409])('maps replay %s without claiming success', (status) => {
    const { store, order } = setup();
    order.replayDeadLetter.mockReturnValue(throwError(() => failure(status)));
    store.replay('order', REPLAY_QUEUES.order[0], true);
    expect(store.replayResult()).toBeNull();
    expect(store.message()).toMatch(status === 404 ? /queue.*available/i : /routing key|parked/i);
  });
  it('keeps all mutations single-flight during a refund', () => {
    const { store, order, catalog } = setup();
    const pending = new Subject<{ orderId: string; status: string }>();
    order.adminRefundOrder.mockReturnValue(pending);
    store.refund('order/123', 'reason', true);
    store.refund('order/123', 'reason', true);
    store.rebuild(true);
    store.replay('order', REPLAY_QUEUES.order[0], true);
    expect(order.adminRefundOrder).toHaveBeenCalledOnce();
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
    expect(order.replayDeadLetter).not.toHaveBeenCalled();
  });
});

describe('session isolation', () => {
  it.each([401, 403])('clears all state and cancels pending work on backend %s', (status) => {
    vi.useFakeTimers();
    const { store, order, catalog } = setup();
    store.checkDrift();
    store.rebuild(true);
    order.stuckOrders.mockReturnValue(throwError(() => failure(status)));
    store.refreshOrders();
    expect(store.allowed()).toBe(false);
    expect(store.orders()).toEqual([]);
    expect(store.search()).toBeNull();
    expect(store.drift()).toBeNull();
    vi.advanceTimersByTime(9000);
    expect(catalog.searchRebuildStatus).toHaveBeenCalledOnce();
  });
  it('blocks operations synchronously after role loss and clears after the effect', () => {
    const { store, identity, catalog, order } = setup();
    identity.set({ sub: 'admin-a', groups: [] });
    store.refund('order/123', 'reason', true);
    store.rebuild(true);
    store.loadMore();
    expect(order.adminRefundOrder).not.toHaveBeenCalled();
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
    expect(store.orders()).toEqual([]);
    TestBed.tick();
    expect(store.search()).toBeNull();
  });
  it('cancels pending old-account data and reloads for a new admin', () => {
    const { store, identity, catalog, order } = setup();
    const pending = new Subject<{ items: StuckOrderItem[]; nextCursor: string | null }>();
    order.stuckOrders.mockReturnValueOnce(pending);
    store.refreshOrders();
    identity.set({ sub: 'admin-b', groups: ['ADMIN'] });
    expect(store.orders()).toEqual([]);
    TestBed.tick();
    expect(pending.observed).toBe(false);
    expect(catalog.searchRebuildStatus).toHaveBeenCalledTimes(2);
  });
  it('clears on registered auth cleanup and unregisters on destroy', () => {
    const { store, hooks } = setup();
    for (const hook of hooks) hook();
    expect(store.allowed()).toBe(false);
    expect(store.orders()).toEqual([]);
    TestBed.resetTestingModule();
    expect(hooks.size).toBe(0);
  });
});

describe('operations gateway contracts', () => {
  it('uses relative gateway paths and preserves replay response headers', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AppConfigService,
          useValue: { config: { apiBaseUrl: 'http://localhost:8080' } },
        },
      ],
    });
    const http = TestBed.inject(HttpTestingController),
      catalog = TestBed.inject(CatalogApi),
      order = TestBed.inject(OrderApi),
      messaging = TestBed.inject(MessagingApi);
    catalog.searchRebuildStatus().subscribe();
    http.expectOne('http://localhost:8080/admin/search/rebuild').flush(idle);
    catalog.rebuildSearch().subscribe();
    const rebuild = http.expectOne('http://localhost:8080/admin/search/rebuild');
    expect(rebuild.request.method).toBe('POST');
    rebuild.flush(running, { status: 202, statusText: 'Accepted' });
    catalog.checkSearchDrift().subscribe();
    http.expectOne('http://localhost:8080/admin/search/drift-check').flush({});
    order.adminRefundOrder('a/b', { reason: 'reason' }).subscribe();
    const refund = http.expectOne('http://localhost:8080/admin/orders/a%2Fb/refund');
    expect(refund.request.body).toEqual({ reason: 'reason' });
    refund.flush({});
    for (const [api, prefix] of [
      [order, 'orders'],
      [messaging, 'messaging'],
    ] as const) {
      api
        .replayDeadLetter('queue.name')
        .subscribe((response) => expect(response.headers.get('X-Audit-Id')).toBe('abc'));
      http
        .expectOne(`http://localhost:8080/admin/${prefix}/dead-letters/queue.name/replay`)
        .flush(null, { status: 200, statusText: 'OK', headers: { 'X-Audit-Id': 'abc' } });
    }
    http.verify();
  });
});

describe('operations screen confirmations', () => {
  it('requires a dialog confirmation before rebuilding and closes only on success', () => {
    const { catalog } = setup();
    const fixture = TestBed.createComponent(AdminOperationsPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRebuild();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog').textContent).toContain(
      'Rebuild the search index?',
    );
    expect(catalog.rebuildSearch).not.toHaveBeenCalled();
    const pending = new Subject<RebuildStatusResponse>();
    catalog.rebuildSearch.mockReturnValue(pending);
    page.confirm();
    expect(page.dialog()).not.toBeNull();
    pending.next(running);
    fixture.detectChanges();
    expect(page.dialog()).toBeNull();
  });
  it('requires a reason in the refund confirmation and preserves it on rejection', () => {
    const { order } = setup();
    const fixture = TestBed.createComponent(AdminOperationsPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRefund(item());
    page.confirm();
    fixture.detectChanges();
    expect(order.adminRefundOrder).not.toHaveBeenCalled();
    expect(page.store.reasonError()).not.toBe('');
    page.reason.setValue('Support-approved refund');
    order.adminRefundOrder.mockReturnValue(throwError(() => failure(409)));
    page.confirm();
    fixture.detectChanges();
    expect(page.reason.value).toBe('Support-approved refund');
    expect(page.dialog()).not.toBeNull();
    expect(fixture.nativeElement.querySelector('dialog').textContent).toMatch(/eligible|changed/i);
  });
  it('keeps sent actions running when a dialog is dismissed and displays the result on the page', () => {
    const { order } = setup();
    const fixture = TestBed.createComponent(AdminOperationsPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const pending = new Subject<{ orderId: string; status: string }>();
    order.adminRefundOrder.mockReturnValue(pending);
    page.openRefund(item());
    page.reason.setValue('reason');
    page.confirm();
    page.closeDialog();
    expect(pending.observed).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Submitting');
    pending.next({ orderId: 'order/123', status: 'PENDING' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('PENDING');
  });
  it('renders diagnostic details and classifications without exposing raw rebuild errors', () => {
    const { catalog, order } = setup();
    const fixture = TestBed.createComponent(AdminOperationsPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    catalog.searchRebuildStatus.mockReturnValue(of({ ...idle, error: 'private database address' }));
    page.store.refreshSearch();
    page.store.checkDrift();
    for (const category of [
      'failed_create',
      'captured_awaiting_webhook',
      'awaiting_timeout',
    ] as const) {
      order.stuckOrders.mockReturnValue(of({ items: [item(category)], nextCursor: null }));
      page.store.refreshOrders();
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(page.categoryLabel(category));
    }
    expect(fixture.nativeElement.textContent).toContain('Failed');
    expect(fixture.nativeElement.textContent).not.toContain('private database address');
  });
  it('clears an open decision and reason on account change before allowing confirmation', () => {
    const { identity, order } = setup();
    const fixture = TestBed.createComponent(AdminOperationsPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRefund(item());
    page.reason.setValue('sensitive note');
    identity.set({ sub: 'admin-b', groups: ['ADMIN'] });
    page.confirm();
    expect(order.adminRefundOrder).not.toHaveBeenCalled();
    fixture.detectChanges();
    expect(page.dialog()).toBeNull();
    expect(page.reason.value).toBe('');
  });
});
