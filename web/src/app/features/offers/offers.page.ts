import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DOCUMENT } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OrderApi } from '../../core/api/order-api';
import { OfferStatus, OfferView } from '../../core/api/api-models';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { EmptyState, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { ModalDialog } from '../../shared/ui/dialog';
import { SegmentedControl } from '../../shared/ui/navigation';
import { reloadThroughAnchor, startAccountAutoReload } from '../profile/account-auto-reload';
import { EurPipe, StatusChip } from '../../shared/ui/content';

type Role = 'received' | 'sent';
type Action = 'accept' | 'reject' | 'withdraw';
interface State {
  items: OfferView[];
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
  selector: 'app-offers-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    DatePipe,
    EmptyState,
    ErrorState,
    Skeleton,
    ModalDialog,
    SegmentedControl,
    EurPipe,
    StatusChip,
  ],
  styleUrl: '../profile/me.page.css',
  template: `
    <h1 tabindex="-1">Your offers</h1>
    <div class="actions account-toolbar">
      <app-segmented-control
        label="Offer role"
        [options]="roles"
        [selected]="role()"
        (selectedChange)="selectRole($event)"
      />
    </div>
    <span class="status-filter">
      <select
        aria-label="Filter loaded offers by status"
        [value]="status()"
        (change)="status.set($any($event.target).value)"
      >
        <option value="">All</option>
        @for (value of statuses; track value) {
          <option [value]="value">{{ label(value) }}</option>
        }
      </select>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
    </span>
    @if (message()) {
      <p role="alert">{{ message() }}</p>
    }
    @if (state().loading) {
      <app-skeleton label="Loading offers" />
    }
    @if (state().error) {
      <app-error-state (retry)="retry()" />
    }
    @for (offer of visible(); track offer.id) {
      <article class="account-row" [attr.data-offer]="offer.id">
        <div class="row-heading">
          <h2>
            <a [routerLink]="['/listings', offer.listingId]">{{ offer.listingTitle }}</a>
          </h2>
          <app-status-chip [text]="label(offer.status)" /><span class="account-money">{{
            offer.amount | eur
          }}</span>
        </div>
        <div class="row-meta">
          <span>Asking {{ offer.askingPrice | eur }}</span>
          <span
            >{{ role() === 'sent' ? 'Seller' : 'Buyer' }}:
            <a [routerLink]="['/u', role() === 'sent' ? offer.sellerSub : offer.buyerSub]">{{
              role() === 'sent' ? offer.sellerSub : offer.buyerSub
            }}</a></span
          >
          <time>{{ offer.createdAt | date: 'mediumDate' }}</time>
        </div>
        @if (offer.status === 'ACCEPTED' && offer.expiresAt) {
          <p>{{ countdown(offer) }} · Deadline {{ offer.expiresAt | date: 'medium' }}</p>
        }
        @if (offer.rejectionReason) {
          <p>{{ rejection(offer.rejectionReason) }}</p>
        }
        <div class="actions">
          @for (action of actions(offer); track action) {
            <button
              class="action"
              [disabled]="busy() || state().loading"
              (click)="choose(offer, action)"
            >
              {{ actionLabel(action) }}
            </button>
          }
          @if (offer.orderId) {
            <a class="action" [routerLink]="['/orders', offer.orderId]">View order</a>
          } @else if (canCheckout(offer)) {
            <a
              class="action"
              [routerLink]="['/checkout', offer.listingId]"
              [queryParams]="{ offer: offer.id }"
              >Checkout at {{ offer.amount | eur }}</a
            >
          }
        </div>
      </article>
    }
    @if (state().loaded && !state().loading && !state().error && !visible().length) {
      @if (status()) {
        <p class="muted">No matching loaded offers</p>
      } @else {
        <app-empty-state
          title="No offers yet"
          text="Offers you make or receive on listings will appear here."
        />
      }
    }
    @if (state().nextCursor) {
      <button
        class="action show-more"
        [disabled]="state().loading || refreshing() || busy() || !!selected()"
        (click)="load(true)"
      >
        Show more
      </button>
    }
    @if (selected(); as decision) {
      <app-modal-dialog
        [heading]="actionLabel(decision.action) + '?'"
        size="confirm"
        (closed)="close()"
      >
        <p>{{ decision.offer.listingTitle }} · {{ decision.offer.amount | eur }}</p>
        @if (decision.action === 'accept') {
          <p>Accepting reserves this item for this buyer and closes other pending offers.</p>
        }
        <div class="actions">
          <button class="action" [disabled]="busy()" (click)="close()">Keep offer</button>
          <button class="action" [disabled]="busy()" (click)="confirm()">
            {{ busy() ? 'Saving...' : actionLabel(decision.action) }}
          </button>
        </div>
      </app-modal-dialog>
    }
  `,
})
export class OffersPage {
  private readonly document = inject(DOCUMENT);
  private background?: Subscription;
  readonly refreshing = signal(false);
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requests = new Subscription();
  readonly role = signal<Role>(
    inject(ActivatedRoute).snapshot.queryParamMap.get('role') === 'sent' ? 'sent' : 'received',
  );
  readonly roles = [
    { value: 'received', label: 'Received' },
    { value: 'sent', label: 'Sent' },
  ];
  readonly statuses: OfferStatus[] = ['PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'WITHDRAWN'];
  readonly status = signal('');
  private readonly states = signal<Record<Role, State>>({
    received: emptyState(),
    sent: emptyState(),
  });
  readonly state = computed(() => this.states()[this.role()]);
  readonly visible = computed(() =>
    this.state().items.filter((item) => !this.status() || item.status === this.status()),
  );
  readonly busy = signal(false);
  readonly message = signal('');
  readonly selected = signal<{ offer: OfferView; action: Action; role: Role } | null>(null);
  readonly now = signal(Date.now());
  private readonly expiredRefetched = new Set<string>();
  constructor() {
    const stopReload = startAccountAutoReload(this.destroyRef, () => this.autoReload());
    const unregister = this.auth.registerCleanup(() => {
      stopReload();
      this.cancelReload();
      this.requests.unsubscribe();
      this.states.set({ received: emptyState(), sent: emptyState() });
      this.selected.set(null);
      this.busy.set(false);
      this.message.set('');
    });
    const timer = setInterval(() => {
      this.now.set(Date.now());
      if (
        this.document.visibilityState !== 'visible' ||
        this.busy() ||
        this.selected() ||
        this.state().loading ||
        this.refreshing()
      )
        return;
      const expired = this.state().items.filter(
        (offer) =>
          offer.status === 'ACCEPTED' &&
          offer.expiresAt &&
          Date.parse(offer.expiresAt) <= this.now() &&
          !this.expiredRefetched.has(offer.id),
      );
      if (expired.length) {
        expired.forEach((offer) => this.expiredRefetched.add(offer.id));
        this.autoReload();
      }
    }, 1000);
    this.destroyRef.onDestroy(() => {
      unregister();
      this.requests.unsubscribe();
      clearInterval(timer);
    });
    this.load();
  }
  selectRole(value: string): void {
    if (this.busy() || this.selected()) return;
    this.cancelReload();
    this.role.set(value === 'sent' ? 'sent' : 'received');
    this.selected.set(null);
    if (!this.state().loaded && !this.state().loading) this.load();
  }
  load(append = false, role = this.role()): void {
    this.cancelReload();
    const state = this.states()[role];
    if (this.requests.closed || this.selected() || state.loading || (append && !state.nextCursor))
      return;
    this.states.update((states) => ({
      ...states,
      [role]: { ...state, loading: true, error: false },
    }));
    const query = { size: 20, ...(append ? { cursor: state.nextCursor } : {}) };
    this.requests.add(
      (role === 'sent' ? this.api.sentOffers(query) : this.api.receivedOffers(query))
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
                loaded: true,
                loading: false,
                error: false,
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
    if (
      !sub ||
      this.requests.closed ||
      this.busy() ||
      this.selected() ||
      state.loading ||
      this.refreshing()
    )
      return false;
    const role = this.role();
    this.refreshing.set(true);
    this.background = reloadThroughAnchor(
      (cursor) =>
        role === 'sent'
          ? this.api.sentOffers({ size: 20, ...(cursor === null ? {} : { cursor }) })
          : this.api.receivedOffers({ size: 20, ...(cursor === null ? {} : { cursor }) }),
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
  actions(offer: OfferView): Action[] {
    return eligibleOfferActions(offer, this.auth.identity()?.sub ?? '', this.role());
  }
  choose(offer: OfferView, action: Action): void {
    this.cancelReload();
    if (!this.busy() && this.actions(offer).includes(action))
      this.selected.set({ offer, action, role: this.role() });
  }
  close(): void {
    if (!this.busy()) this.selected.set(null);
  }
  confirm(): void {
    const decision = this.selected();
    if (!decision || this.busy() || !this.actions(decision.offer).includes(decision.action)) return;
    this.busy.set(true);
    this.message.set('');
    const request =
      decision.action === 'accept'
        ? this.api.acceptOffer(decision.offer.id)
        : decision.action === 'reject'
          ? this.api.rejectOffer(decision.offer.id)
          : this.api.withdrawOffer(decision.offer.id);
    this.requests.add(
      request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          this.busy.set(false);
          this.selected.set(null);
          this.load(false, decision.role);
        },
        error: (error: AppError) => {
          this.busy.set(false);
          this.selected.set(null);
          this.message.set(errorCopy(error));
          this.load(false, decision.role);
        },
      }),
    );
  }
  label(status: OfferStatus): string {
    return {
      PENDING: 'Pending',
      ACCEPTED: 'Accepted',
      REJECTED: 'Declined',
      EXPIRED: 'Expired',
      WITHDRAWN: 'Withdrawn',
    }[status];
  }
  actionLabel(action: Action): string {
    return { accept: 'Accept offer', reject: 'Decline offer', withdraw: 'Withdraw offer' }[action];
  }
  rejection(reason: string): string {
    return (
      (
        {
          SELLER: 'The seller declined your offer.',
          OTHER_ACCEPTED: 'The seller accepted another offer.',
          LISTING_UNAVAILABLE: 'The item is no longer available.',
          PRICE_CHANGED: 'The price changed, so your offer was closed.',
        } as Record<string, string>
      )[reason] ?? ''
    );
  }
  canCheckout(offer: OfferView): boolean {
    return (
      this.role() === 'sent' &&
      offer.buyerSub === this.auth.identity()?.sub &&
      offer.status === 'ACCEPTED' &&
      !offer.orderId &&
      !!offer.expiresAt &&
      Date.parse(offer.expiresAt) > this.now()
    );
  }
  countdown(offer: OfferView): string {
    const seconds = Math.max(
      0,
      Math.floor((Date.parse(offer.expiresAt ?? '') - this.now()) / 1000),
    );
    if (!Number.isFinite(seconds) || !seconds) return 'Payment hold expired';
    const minutes = Math.floor(seconds / 60);
    return `${Math.floor(minutes / 60)}h ${minutes % 60}m ${seconds % 60}s left to pay`;
  }
}

export function eligibleOfferActions(offer: OfferView, caller: string, role: Role): Action[] {
  if (offer.status !== 'PENDING') return [];
  if (role === 'received' && caller === offer.sellerSub) return ['accept', 'reject'];
  if (role === 'sent' && caller === offer.buyerSub) return ['withdraw'];
  return [];
}
