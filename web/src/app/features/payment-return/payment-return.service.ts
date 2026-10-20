import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { Observable, Subscription, take } from 'rxjs';
import { OrderView } from '../../core/api/api-models';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { PendingCheckoutStore } from '../checkout/pending-checkout.store';

export type PaymentOutcome =
  'processing' | 'success' | 'cancelled' | 'refunded' | 'awaiting' | 'failed' | 'missing';
const ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/;

@Injectable()
export class PaymentReturnService {
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly pending = inject(PendingCheckoutStore);
  private readonly destroy = inject(DestroyRef);
  readonly state = signal<PaymentOutcome>('missing');
  readonly order = signal<OrderView | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly accepted = signal(false);
  private requests = new Subscription();
  private interval?: ReturnType<typeof setInterval>;
  private deadline?: ReturnType<typeof setTimeout>;
  private generation = 0;
  private sessionSub: string | null = null;
  private mode: 'return' | 'cancel' = 'return';
  private fromProvider = false;
  private reading = false;

  constructor() {
    const unregister = this.auth.registerCleanup(() => {
      this.stop();
      this.order.set(null);
      this.accepted.set(false);
      this.state.set('missing');
      this.pending.clear();
    });
    this.destroy.onDestroy(() => {
      unregister();
      this.stop();
    });
    effect(() => {
      const sub = this.auth.status() === 'authenticated' ? this.auth.identity()?.sub : null;
      if (this.sessionSub && sub !== this.sessionSub)
        untracked(() => {
          this.stop();
          this.order.set(null);
          this.accepted.set(false);
          this.state.set('missing');
          this.sessionSub = null;
        });
    });
  }

  start(mode: 'return' | 'cancel', fromProvider: boolean): void {
    if (this.busy() || this.destroy.destroyed) return;
    this.mode = mode;
    this.fromProvider = fromProvider;
    if (!this.begin()) return;
    const stored = this.pending.read();
    if (stored) {
      const age = Date.now() - Date.parse(stored.startedAt);
      if (
        !ID.test(stored.orderId) ||
        !ID.test(stored.listingId) ||
        !Number.isFinite(age) ||
        age < 0 ||
        age > 86400000
      ) {
        this.pending.clear();
        this.finish('missing');
        return;
      }
      this.readOrder(stored.orderId, true);
    } else this.finish('missing');
  }

  retry(): void {
    this.start(this.mode, this.fromProvider);
  }

  checkStatus(): void {
    if (this.busy() || !this.order() || this.destroy.destroyed) return;
    const id = this.order()!.id;
    if (this.begin(true)) this.readOrder(id, false);
  }

  stop(): void {
    ++this.generation;
    clearInterval(this.interval);
    clearTimeout(this.deadline);
    this.interval = undefined;
    this.deadline = undefined;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.reading = false;
    this.busy.set(false);
  }

  private begin(preserveOrder = false): boolean {
    this.stop();
    this.sessionSub =
      this.auth.status() === 'authenticated' ? (this.auth.identity()?.sub ?? null) : null;
    if (!this.sessionSub) {
      this.order.set(null);
      this.state.set('missing');
      return false;
    }
    if (!preserveOrder) {
      this.order.set(null);
      this.accepted.set(false);
    }
    this.error.set('');
    this.busy.set(true);
    this.state.set('processing');
    const generation = this.generation;
    this.deadline = setTimeout(() => {
      if (!this.isCurrent(generation)) return;
      if (this.order()) this.finish('awaiting');
      else this.fail({ status: 0 } as AppError);
    }, 30000);
    return true;
  }

  private isCurrent(generation: number): boolean {
    return (
      !this.destroy.destroyed &&
      generation === this.generation &&
      this.auth.status() === 'authenticated' &&
      this.auth.identity()?.sub === this.sessionSub
    );
  }

  private request<T>(
    source: Observable<T>,
    next: (value: T) => void,
    error?: (failure: AppError) => void,
  ): void {
    const generation = this.generation;
    this.requests.add(
      source.pipe(take(1)).subscribe({
        next: (value) => {
          if (this.isCurrent(generation)) next(value);
        },
        error: (failure) => {
          if (this.isCurrent(generation)) (error ?? ((failure) => this.fail(failure)))(failure);
        },
      }),
    );
  }

  private readOrder(id: string, mutate: boolean, polling = false): void {
    if (this.reading) return;
    this.reading = true;
    this.request(this.api.order(id), (order) => {
      this.reading = false;
      if (order.id !== id || order.buyerSub !== this.sessionSub || !ID.test(order.listingId)) {
        this.order.set(null);
        this.pending.clear();
        this.finish('missing');
        return;
      }
      this.order.set(order);
      if (this.classify(order)) return;
      if (order.status !== 'AWAITING_PAYMENT') {
        this.fail({ status: 0 } as AppError);
        return;
      }
      if (polling) return;
      if (!mutate || !this.fromProvider) {
        this.finish('awaiting');
        return;
      }
      if (this.mode === 'cancel') this.cancel(order.id);
      else this.capture(order.id);
    });
  }

  private classify(order: OrderView): boolean {
    const outcome =
      order.status === 'PAID' || order.status === 'SHIPPED' || order.status === 'DELIVERED'
        ? 'success'
        : order.status === 'CANCELLED'
          ? 'cancelled'
          : order.status === 'REFUNDED'
            ? 'refunded'
            : null;
    if (!outcome) return false;
    this.pending.clear();
    this.finish(outcome);
    return true;
  }

  private capture(id: string): void {
    this.request(this.api.capturePayment(id), (result) => {
      if (result.orderId !== id || result.status !== 'ACCEPTED_AWAITING_CONFIRMATION') {
        this.fail({ status: 0 } as AppError);
        return;
      }
      this.accepted.set(true);
      const generation = this.generation;
      this.interval = setInterval(() => {
        if (this.isCurrent(generation) && !this.reading) this.readOrder(id, false, true);
      }, 2000);
    });
  }

  private cancel(id: string): void {
    this.request(
      this.api.cancelOrder(id),
      () => this.readOrder(id, false),
      (failure) => {
        if (failure.status === 409) this.readOrder(id, false);
        else this.fail(failure);
      },
    );
  }

  private finish(state: PaymentOutcome): void {
    this.stop();
    this.state.set(state);
  }

  private fail(failure: AppError): void {
    const copy: Readonly<Record<string, string>> = {
      'capture-denied':
        'PayPal did not approve this payment. Check your order before trying again.',
      'payment-closed': 'This payment attempt is closed. Check your order for its latest status.',
      'payment-not-found': 'This payment attempt could not be found. Check your orders.',
      'wrong-buyer': 'This checkout is not available to this account.',
    };
    this.error.set(
      copy[failure?.code] ??
        'We could not confirm the payment status. Check your order before trying again.',
    );
    this.finish('failed');
  }
}
