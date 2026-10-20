import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { BusyButton } from '../../shared/ui/feedback';
import { PaymentReturnService } from './payment-return.service';

@Component({
  selector: 'app-payment-return-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, BusyButton],
  providers: [PaymentReturnService],
  styleUrl: './payment-return.page.css',
  template: `<section class="outcome" [attr.aria-busy]="service.busy()">
    <div
      class="status-icon"
      [class.success]="service.state() === 'success'"
      [class.failed]="service.state() === 'failed'"
      aria-hidden="true"
    >
      @if (service.state() === 'processing') {
        <span class="spinner"></span>
      } @else {
        <svg viewBox="0 0 24 24">
          @switch (service.state()) {
            @case ('success') {
              <path d="m5 12 4 4L19 6" />
            }
            @case ('cancelled') {
              <path d="m6 6 12 12M18 6 6 18" />
            }
            @case ('failed') {
              <path d="M12 8v5M12 17h.01" />
            }
            @case ('refunded') {
              <path d="M3 10h13a5 5 0 0 1 0 10M3 10l5-5M3 10l5 5" />
            }
            @default {
              <circle cx="12" cy="12" r="9" />
              <path d="M12 7v5l3 2" />
            }
          }
        </svg>
      }
    </div>
    <div role="status" aria-live="polite" aria-atomic="true">
      <h1 tabindex="-1">{{ title() }}</h1>
      <p>{{ description() }}</p>
    </div>
    <div class="actions">
      @if (service.state() === 'processing') {
        @if (service.order(); as order) {
          <a class="primary" [routerLink]="['/orders', order.id]">View order</a>
        } @else {
          <a class="primary" routerLink="/orders">Your orders</a>
        }
      } @else if (service.state() === 'missing') {
        <a class="primary" routerLink="/orders">Your orders</a>
      } @else if (service.state() === 'cancelled' && service.order(); as order) {
        <a class="primary" [routerLink]="['/listings', order.listingId]">Back to the listing</a>
        <a class="secondary" [routerLink]="['/orders', order.id]">View order</a>
      } @else if (service.order(); as order) {
        <a class="primary" [routerLink]="['/orders', order.id]">View order</a>
      } @else {
        <a class="primary" routerLink="/orders">Your orders</a>
      }
      @if (service.state() === 'failed') {
        <app-busy-button
          label="Try again"
          variant="neutral"
          [busy]="service.busy()"
          (pressed)="service.retry()"
        />
      } @else if (service.state() === 'awaiting') {
        <app-busy-button
          label="Check status"
          variant="neutral"
          [busy]="service.busy()"
          (pressed)="service.checkStatus()"
        />
      }
      @if (service.state() !== 'cancelled') {
        <a class="secondary" routerLink="/">Keep browsing</a>
      }
    </div>
  </section>`,
})
export class PaymentReturnPage {
  readonly service = inject(PaymentReturnService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  readonly title = computed(() => {
    switch (this.service.state()) {
      case 'processing':
        return 'Confirming your payment';
      case 'success':
        return 'Payment received';
      case 'cancelled':
        return 'Payment cancelled';
      case 'refunded':
        return 'Payment refunded';
      case 'awaiting':
        return this.service.accepted()
          ? 'PayPal accepted your payment'
          : 'Payment confirmation is pending';
      case 'failed':
        return "We couldn't confirm your payment";
      case 'missing':
        return "We can't find this checkout";
    }
  });
  readonly description = computed(() => {
    switch (this.service.state()) {
      case 'processing':
        return 'This takes a few seconds. Keep this page open.';
      case 'success':
        return this.service.order()?.listingType === 'SOFTWARE'
          ? 'Your licence key is on your order page.'
          : 'The seller has been notified. View your order for the latest delivery status.';
      case 'cancelled':
        return 'Your order is cancelled. View the order for its final details.';
      case 'refunded':
        return 'Your order has been refunded. View the order for its details.';
      case 'awaiting':
        return 'We are still waiting for confirmation. Check your order before trying to pay again.';
      case 'failed':
        return this.service.error();
      case 'missing':
        return 'Check your orders to see where it stands.';
    }
  });

  constructor() {
    const route = inject(ActivatedRoute),
      router = inject(Router);
    const mode = route.snapshot.data['mode'] === 'cancel' ? 'cancel' : 'return';
    const params = route.snapshot.queryParamMap;
    const valid = (key: string) => {
      const values = params.getAll(key);
      return values.length === 1 && /^[A-Za-z0-9_-]{1,256}$/.test(values[0]);
    };
    const fromProvider = valid('token') && (mode === 'cancel' || valid('PayerID'));
    if (params.keys.length || route.snapshot.fragment) {
      void router.navigate([], {
        relativeTo: route,
        queryParams: {},
        fragment: undefined,
        replaceUrl: true,
      });
    }
    this.service.start(mode, fromProvider);
    effect(() => {
      this.service.state();
      afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('h1')?.focus(), {
        injector: this.injector,
      });
    });
  }
}
