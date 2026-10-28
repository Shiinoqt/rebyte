import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OrderApi } from '../../core/api/order-api';
import { OrderStatus, OrderView } from '../../core/api/api-models';
import { AuthStore } from '../../core/auth/auth.store';
import { EmptyState, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { MenuSelect, SegmentedControl, SelectionOption } from '../../shared/ui/navigation';
import { reloadThroughAnchor, startAccountAutoReload } from '../profile/account-auto-reload';
import { EurPipe, StatusChip } from '../../shared/ui/content';
import { STATUS_LABEL, lifecycleActions } from '../order-detail/order-detail.service';

type Role = 'purchases' | 'sales';
interface State {
  items: OrderView[];
  nextCursor: string | null;
  loading: boolean;
  error: boolean;
  loaded: boolean;
  failedAppend: boolean;
}
const emptyState = (): State => ({
  items: [],
  nextCursor: null,
  loading: false,
  error: false,
  loaded: false,
  failedAppend: false,
});

@Component({
  selector: 'app-orders-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    DatePipe,
    EmptyState,
    ErrorState,
    Skeleton,
    SegmentedControl,
    MenuSelect,
    EurPipe,
    StatusChip,
  ],
  styleUrl: '../profile/me.page.css',
  template: `
    <h1 tabindex="-1">Your orders</h1>
    <div class="actions account-toolbar" aria-label="Order role">
      <app-segmented-control
        label="Order role"
        [options]="roles"
        [selected]="role()"
        (selectedChange)="selectRole($event)"
      />
    </div>
    <app-menu-select
      label="Status"
      name="Filter loaded orders by status"
      [options]="statusOptions()"
      [selected]="status()"
      (selectedChange)="status.set($any($event))"
    />
    @if (state().loading) {
      <app-skeleton label="Loading orders" />
    }
    @if (state().error) {
      <app-error-state (retry)="retry()" />
    }
    @for (order of visible(); track order.id) {
      <article class="account-row" [attr.data-order]="order.id">
        <div class="row-heading">
          <h2>
            <a [routerLink]="['/orders', order.id]">{{ order.listingTitle }}</a>
          </h2>
          <app-status-chip [text]="labels[order.status]" /><span class="account-money">{{
            order.pricePaid | eur
          }}</span>
        </div>
        <div class="row-meta">
          <span>{{ order.listingType === 'HARDWARE' ? 'Hardware' : 'Software' }}</span>
          <span
            >{{ role() === 'purchases' ? 'Seller' : 'Buyer' }}:
            <a [routerLink]="['/u', role() === 'purchases' ? order.sellerSub : order.buyerSub]">{{
              role() === 'purchases' ? order.sellerSub : order.buyerSub
            }}</a></span
          >
          <time>{{ order.createdAt | date: 'mediumDate' }}</time>
        </div>
        <a class="action" [routerLink]="['/orders', order.id]">{{ actionLabel(order) }}</a>
      </article>
    }
    @if (state().loaded && !state().loading && !state().error && !visible().length) {
      @if (status()) {
        <p class="muted">No matching loaded orders</p>
      } @else {
        <app-empty-state
          [title]="role() === 'purchases' ? 'No purchases yet' : 'No sales yet'"
          [text]="
            role() === 'purchases'
              ? 'Items you buy will appear here.'
              : 'When someone buys one of your listings it will appear here.'
          "
        />
      }
    }
    @if (state().nextCursor) {
      <button
        class="action show-more"
        [disabled]="state().loading || refreshing()"
        (click)="load(true)"
      >
        Show more
      </button>
    }
  `,
})
export class OrdersPage {
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requests = new Subscription();
  private background?: Subscription;
  readonly refreshing = signal(false);
  readonly role = signal<Role>('purchases');
  readonly roles = [
    { value: 'purchases', label: 'Purchases' },
    { value: 'sales', label: 'Sales' },
  ];
  readonly labels = STATUS_LABEL;
  readonly status = signal<OrderStatus | ''>('');
  private readonly states = signal<Record<Role, State>>({
    purchases: emptyState(),
    sales: emptyState(),
  });
  readonly state = computed(() => this.states()[this.role()]);
  readonly visible = computed(() =>
    this.state().items.filter((item) => !this.status() || item.status === this.status()),
  );
  readonly statuses = computed(() => {
    const selected = this.status();
    return [
      ...new Set([
        ...(selected ? [selected] : []),
        ...this.state().items.map((item) => item.status),
      ]),
    ];
  });
  readonly statusOptions = computed<readonly SelectionOption[]>(() => [
    { value: '', label: 'All' },
    ...this.statuses().map((value) => ({ value, label: STATUS_LABEL[value] })),
  ]);
  constructor() {
    const stopReload = startAccountAutoReload(this.destroyRef, () => this.autoReload());
    const unregister = this.auth.registerCleanup(() => {
      stopReload();
      this.cancelReload();
      this.requests.unsubscribe();
      this.states.set({ purchases: emptyState(), sales: emptyState() });
    });
    this.destroyRef.onDestroy(() => {
      unregister();
      this.requests.unsubscribe();
    });
    this.load();
  }
  selectRole(value: string): void {
    this.cancelReload();
    this.role.set(value === 'sales' ? 'sales' : 'purchases');
    if (!this.state().loaded && !this.state().loading) this.load();
  }
  load(append = false): void {
    this.cancelReload();
    const role = this.role();
    const state = this.states()[role];
    if (this.requests.closed || state.loading || (append && !state.nextCursor)) return;
    this.states.update((states) => ({
      ...states,
      [role]: { ...state, loading: true, error: false },
    }));
    const query = { size: 20, ...(append ? { cursor: state.nextCursor } : {}) };
    this.requests.add(
      (role === 'purchases' ? this.api.purchases(query) : this.api.sales(query))
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (page) =>
            this.states.update((states) => ({
              ...states,
              [role]: {
                ...state,
                items: [
                  ...new Map(
                    (append ? [...state.items, ...page.items] : page.items).map((item) => [
                      item.id,
                      item,
                    ]),
                  ).values(),
                ],
                nextCursor: page.nextCursor,
                loading: false,
                error: false,
                loaded: true,
                failedAppend: false,
              },
            })),
          error: () =>
            this.states.update((states) => ({
              ...states,
              [role]: { ...state, loading: false, error: true, failedAppend: append },
            })),
        }),
    );
  }
  retry(): void {
    this.load(this.state().failedAppend);
  }
  autoReload(): boolean {
    const state = this.state();
    const sub = this.auth.identity()?.sub;
    if (!sub || this.requests.closed || state.loading || this.refreshing()) return false;
    const role = this.role();
    this.refreshing.set(true);
    this.background = reloadThroughAnchor(
      (cursor) =>
        role === 'purchases'
          ? this.api.purchases({ size: 20, ...(cursor === null ? {} : { cursor }) })
          : this.api.sales({ size: 20, ...(cursor === null ? {} : { cursor }) }),
      (item) => item.id,
      state.items.at(-1)?.id ?? null,
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.refreshing.set(false);
          if (sub !== this.auth.identity()?.sub || this.requests.closed) return;
          this.states.update((states) => ({
            ...states,
            [role]: {
              ...states[role],
              items: page.items,
              nextCursor: page.nextCursor,
              loaded: true,
              error: false,
              failedAppend: false,
            },
          }));
        },
        error: () => this.refreshing.set(false),
      });
    this.requests.add(this.background);
    return true;
  }
  private cancelReload(): void {
    this.background?.unsubscribe();
    this.refreshing.set(false);
  }
  actionLabel(order: OrderView): string {
    const sub = this.auth.identity()?.sub ?? null;
    const actions = lifecycleActions(order, sub);
    if (actions.ship) return 'Mark as shipped';
    if (actions.deliver) return 'Confirm receipt';
    if (sub === order.buyerSub && order.status === 'AWAITING_PAYMENT') return 'Continue payment';
    return 'View order';
  }
}
