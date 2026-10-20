import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Observable, Subscription, take } from 'rxjs';
import { CatalogApi } from '../../../core/api/catalog-api';
import { OrderApi } from '../../../core/api/order-api';
import { MessagingApi } from '../../../core/api/messaging-api';
import {
  DriftReport,
  RebuildStatusResponse,
  RefundResponse,
  StuckOrderItem,
} from '../../../core/api/api-models';
import { AuthStore } from '../../../core/auth/auth.store';
import { AppError } from '../../../core/http/app-error';
import { errorCopy } from '../../../core/http/error-copy';

export const POLL_LIMIT_MS = 5 * 60_000;
export const REPLAY_QUEUES = {
  order: ['order.payment-result.dlq', 'order.listing-lifecycle.dlq'],
  messaging: [
    'notify.favourite.dlq',
    'notify.follow.dlq',
    'notify.price-drop.dlq',
    'notify.version-released.dlq',
    'notify.review.dlq',
    'notify.offer.dlq',
    'chat.offer-card.dlq',
    'notify.order.dlq',
    'messaging.email-send.dlq',
  ],
} as const;
export type ReplayService = keyof typeof REPLAY_QUEUES;
interface ReplayResult {
  queue: string;
  outcome: 'empty' | 'replayed';
  auditId: string | null;
}

@Injectable()
export class AdminOperationsService {
  private readonly catalog = inject(CatalogApi);
  private readonly order = inject(OrderApi);
  private readonly messaging = inject(MessagingApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly denied = signal(false);
  private readonly session = signal<string | null>(null);
  private readonly searchData = signal<RebuildStatusResponse | null>(null);
  private readonly driftData = signal<DriftReport | null>(null);
  private readonly orderData = signal<StuckOrderItem[]>([]);
  private readonly cursor = signal<string | null>(null);
  private readonly refundData = signal<RefundResponse | null>(null);
  private readonly replayData = signal<ReplayResult | null>(null);
  private active = new Subscription();
  private searchRead?: Subscription;
  private orderRead?: Subscription;
  private poll?: ReturnType<typeof setTimeout>;
  private deadline?: ReturnType<typeof setTimeout>;
  private generation = 0;
  private searchGeneration = 0;
  private orderGeneration = 0;
  readonly allowed = computed(
    () => this.auth.isAdmin() && !this.denied() && this.session() === this.auth.identity()?.sub,
  );
  readonly search = computed(() => (this.allowed() ? this.searchData() : null));
  readonly drift = computed(() => (this.allowed() ? this.driftData() : null));
  readonly orders = computed(() => (this.allowed() ? this.orderData() : []));
  readonly nextCursor = computed(() => (this.allowed() ? this.cursor() : null));
  readonly refundResult = computed(() => (this.allowed() ? this.refundData() : null));
  readonly replayResult = computed(() => (this.allowed() ? this.replayData() : null));
  readonly busy = signal<'rebuild' | 'drift' | 'refund' | 'replay' | null>(null);
  readonly statusLoading = signal(false);
  readonly searchError = signal('');
  readonly pollExpired = signal(false);
  readonly driftLoading = signal(false);
  readonly driftError = signal('');
  readonly ordersLoading = signal(false);
  readonly ordersError = signal('');
  readonly message = signal('');
  readonly reasonError = signal('');

  constructor() {
    effect(() => {
      const sub = this.auth.identity()?.sub ?? null,
        admin = this.auth.isAdmin();
      untracked(() => {
        this.clear();
        this.session.set(sub);
        this.denied.set(!admin);
        if (admin) {
          this.refreshSearch();
          this.refreshOrders();
        }
      });
    });
    const unregister = this.auth.registerCleanup(() => {
      this.clear();
      this.denied.set(true);
    });
    this.destroyRef.onDestroy(() => {
      unregister();
      this.clear();
    });
  }
  refreshSearch(): void {
    if (!this.allowed() || this.busy() === 'rebuild') return;
    this.stopPolling();
    this.pollExpired.set(false);
    this.searchError.set('');
    this.deadline = setTimeout(() => {
      this.stopPolling();
      this.pollExpired.set(true);
      this.statusLoading.set(false);
    }, POLL_LIMIT_MS);
    this.readSearch();
  }
  rebuild(confirmed: boolean, done: () => void = () => {}): void {
    if (
      !confirmed ||
      !this.allowed() ||
      this.busy() ||
      this.statusLoading() ||
      !this.search() ||
      this.search()!.running ||
      !!this.searchError()
    )
      return;
    this.command(
      'rebuild',
      this.catalog.rebuildSearch(),
      (result) => {
        this.stopPolling();
        this.searchData.set(result);
        this.pollExpired.set(false);
        this.searchError.set('');
        if (result.running) {
          this.deadline = setTimeout(() => {
            this.stopPolling();
            this.pollExpired.set(true);
            this.statusLoading.set(false);
          }, POLL_LIMIT_MS);
          this.schedulePoll();
        }
        done();
      },
      (error) => {
        if (error.status === 409) {
          this.message.set('A rebuild is already running. Its status has been refreshed.');
          this.refreshSearch();
        } else this.message.set(this.uncertain(error, 'rebuild'));
      },
    );
  }
  checkDrift(): void {
    if (!this.allowed() || this.busy()) return;
    this.driftLoading.set(true);
    this.driftError.set('');
    this.command(
      'drift',
      this.catalog.checkSearchDrift(),
      (report) => {
        this.driftData.set(report);
        this.driftLoading.set(false);
      },
      (error) => {
        this.driftLoading.set(false);
        this.driftError.set(this.readError(error));
      },
    );
  }
  refreshOrders(): void {
    if (!this.allowed() || this.busy() === 'refund') return;
    this.readOrders(null);
  }
  loadMore(): void {
    const cursor = this.nextCursor();
    if (!this.allowed() || !cursor || this.ordersLoading() || this.busy() === 'refund') return;
    this.readOrders(cursor);
  }
  refund(orderId: string, reason: string, confirmed: boolean, done: () => void = () => {}): void {
    if (!this.allowed() || this.busy() || !confirmed || !orderId.trim()) return;
    const clean = reason.trim();
    this.reasonError.set(clean ? '' : 'Enter a reason for the refund.');
    if (!clean) return;
    this.refundData.set(null);
    this.command(
      'refund',
      this.order.adminRefundOrder(orderId, { reason: clean }),
      (result) => {
        this.refundData.set(result);
        done();
        this.refreshOrders();
      },
      (error) => {
        this.reasonError.set(error.fieldErrors?.['reason'] ? 'Enter a reason for the refund.' : '');
        this.message.set(
          error.status === 409
            ? 'This order changed or is not eligible for a refund. Review its details before trying again.'
            : error.status === 404
              ? 'This order is no longer available.'
              : this.uncertain(error, 'refund'),
        );
      },
    );
  }
  replay(
    service: ReplayService,
    queue: string,
    confirmed: boolean,
    done: () => void = () => {},
  ): void {
    if (
      !this.allowed() ||
      this.busy() ||
      !confirmed ||
      !Object.hasOwn(REPLAY_QUEUES, service) ||
      !(REPLAY_QUEUES[service] as readonly string[]).includes(queue)
    )
      return;
    this.replayData.set(null);
    this.command(
      'replay',
      (service === 'order' ? this.order : this.messaging).replayDeadLetter(queue),
      (response) => {
        if (response.status === 204 || response.status === 200) {
          this.replayData.set({
            queue,
            outcome: response.status === 204 ? 'empty' : 'replayed',
            auditId: response.status === 200 ? response.headers.get('X-Audit-Id') : null,
          });
          done();
        } else
          this.message.set(
            'The replay outcome could not be confirmed. Check the queue before trying again.',
          );
      },
      (error) => {
        this.message.set(
          error.status === 404
            ? 'This queue is not available for replay.'
            : error.status === 409
              ? 'The message has no valid original routing key and remains parked. No replay was confirmed.'
              : this.uncertain(error, 'replay'),
        );
      },
    );
  }
  clearActionError(): void {
    this.message.set('');
    this.reasonError.set('');
  }

  private readSearch(): void {
    if (!this.allowed()) {
      this.stopPolling();
      return;
    }
    this.searchRead?.unsubscribe();
    const generation = ++this.searchGeneration;
    this.statusLoading.set(true);
    this.searchRead = this.listen(
      this.catalog.searchRebuildStatus(),
      (result) => {
        if (generation !== this.searchGeneration) return;
        this.statusLoading.set(false);
        this.searchError.set('');
        this.searchData.set(result);
        if (result.running) this.schedulePoll();
        else this.stopPolling();
      },
      (error) => {
        if (generation !== this.searchGeneration) return;
        this.stopPolling();
        this.statusLoading.set(false);
        this.searchError.set(this.readError(error));
      },
    );
  }
  private schedulePoll(): void {
    clearTimeout(this.poll);
    this.poll = setTimeout(() => this.readSearch(), 3000);
  }
  private stopPolling(): void {
    clearTimeout(this.poll);
    clearTimeout(this.deadline);
    this.searchRead?.unsubscribe();
    ++this.searchGeneration;
  }
  private readOrders(cursor: string | null): void {
    this.orderRead?.unsubscribe();
    const generation = ++this.orderGeneration;
    this.ordersLoading.set(true);
    this.ordersError.set('');
    this.orderRead = this.listen(
      this.order.stuckOrders({ size: 20, ...(cursor ? { cursor } : {}) }),
      (page) => {
        if (generation !== this.orderGeneration) return;
        this.orderData.update((rows) =>
          cursor
            ? [
                ...rows,
                ...page.items.filter((row) => !rows.some((old) => old.orderId === row.orderId)),
              ]
            : page.items,
        );
        this.cursor.set(page.nextCursor);
        this.ordersLoading.set(false);
      },
      (error) => {
        if (generation !== this.orderGeneration) return;
        this.ordersLoading.set(false);
        this.ordersError.set(
          error.code === 'invalid-order-cursor'
            ? 'This page has expired. Refresh stuck orders to start again.'
            : this.readError(error),
        );
      },
    );
  }
  private command<T>(
    kind: NonNullable<ReturnType<typeof this.busy>>,
    source: Observable<T>,
    done: (result: T) => void,
    failed: (error: AppError) => void,
  ): void {
    this.busy.set(kind);
    this.clearActionError();
    this.listen(
      source,
      (result) => {
        this.busy.set(null);
        done(result);
      },
      (error) => {
        this.busy.set(null);
        failed(error);
      },
    );
  }
  private listen<T>(
    source: Observable<T>,
    done: (result: T) => void,
    failed: (error: AppError) => void,
  ): Subscription {
    const generation = this.generation;
    const subscription = source.pipe(take(1)).subscribe({
      next: (result) => {
        if (generation === this.generation && this.allowed()) done(result);
      },
      error: (error: AppError) => {
        if (generation !== this.generation || !this.allowed()) return;
        if (error.status === 401 || error.status === 403) {
          this.clear();
          this.denied.set(true);
        } else failed(error);
      },
    });
    this.active.add(subscription);
    return subscription;
  }
  private readError(error: AppError): string {
    return error.status === 503
      ? 'This service is unavailable. Try again in a minute.'
      : errorCopy(error);
  }
  private uncertain(error: AppError, action: string): string {
    return error.status === 0 || error.status >= 500
      ? `We could not confirm the ${action} outcome. Check ${action === 'refund' ? 'the order' : action === 'replay' ? 'the queue' : 'the rebuild status'} before trying again.`
      : errorCopy(error);
  }
  private clear(): void {
    ++this.generation;
    ++this.orderGeneration;
    this.stopPolling();
    this.active.unsubscribe();
    this.active = new Subscription();
    this.searchData.set(null);
    this.driftData.set(null);
    this.orderData.set([]);
    this.cursor.set(null);
    this.refundData.set(null);
    this.replayData.set(null);
    this.busy.set(null);
    this.statusLoading.set(false);
    this.driftLoading.set(false);
    this.ordersLoading.set(false);
    this.pollExpired.set(false);
    this.searchError.set('');
    this.driftError.set('');
    this.ordersError.set('');
    this.clearActionError();
  }
}
