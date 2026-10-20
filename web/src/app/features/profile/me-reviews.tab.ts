import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injectable,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, Subject, firstValueFrom, takeUntil } from 'rxjs';
import { OrderApi } from '../../core/api/order-api';
import { OrderView, ReviewView } from '../../core/api/api-models';
import { AuthStore } from '../../core/auth/auth.store';
import { EmptyState, ErrorState } from '../../shared/ui/feedback';
import { SegmentedControl } from '../../shared/ui/navigation';

interface AuthoredReview {
  review: ReviewView;
  orderId: string;
}

@Injectable()
export class MeReviewsStore {
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stop = new Subject<void>();
  private generation = 0;
  private active = false;
  private pending: OrderView[] = [];
  private cursor: string | null = null;
  private started = false;
  private batchTarget: number | null = null;
  private readonly seenOrders = new Set<string>();
  private readonly seenReviews = new Set<string>();
  private readonly cache = new Map<string, OrderView>();
  readonly mode = signal('received');
  readonly reviews = signal<AuthoredReview[]>([]);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly exhausted = signal(false);
  readonly scanned = signal(0);
  readonly received = signal<ReviewView[]>([]);
  readonly receivedCursor = signal<string | null>(null);
  readonly receivedLoading = signal(false);
  readonly receivedError = signal(false);
  readonly receivedLoaded = signal(false);
  private receivedAppendFailed = false;
  constructor() {
    const unregister = this.auth.registerCleanup(() => this.reset());
    this.destroyRef.onDestroy(() => {
      unregister();
      this.pause();
      this.stop.complete();
    });
  }
  activate(): void {
    this.active = true;
    this.select(this.mode());
  }
  pause(): void {
    this.active = false;
    ++this.generation;
    this.stop.next();
    this.loading.set(false);
    this.receivedLoading.set(false);
  }
  select(mode: string): void {
    const next = mode === 'authored' ? 'authored' : 'received';
    if (this.mode() !== next) {
      this.pause();
      this.active = true;
      this.mode.set(next);
    }
    if (next === 'authored' && (!this.started || this.batchTarget !== null) && !this.error())
      void this.scanMore();
    if (next === 'received' && !this.receivedLoaded() && !this.receivedError())
      void this.loadReceived();
  }
  private read<T>(source: Observable<T>): Promise<T> {
    return firstValueFrom(source.pipe(takeUntil(this.stop)));
  }
  async loadReceived(append = false): Promise<void> {
    const caller = this.auth.identity()?.sub;
    if (!this.active || !caller || this.receivedLoading() || (append && !this.receivedCursor()))
      return;
    const generation = this.generation;
    this.receivedLoading.set(true);
    this.receivedError.set(false);
    this.receivedAppendFailed = append;
    try {
      const page = await this.read(
        this.api.sellerReviews(caller, {
          size: 20,
          ...(append ? { cursor: this.receivedCursor() } : {}),
        }),
      );
      if (generation !== this.generation) return;
      this.received.set([
        ...new Map(
          (append ? [...this.received(), ...page.items] : page.items).map((row) => [row.id, row]),
        ).values(),
      ]);
      this.receivedCursor.set(page.nextCursor);
      this.receivedLoaded.set(true);
    } catch {
      if (generation === this.generation) this.receivedError.set(true);
    } finally {
      if (generation === this.generation) this.receivedLoading.set(false);
    }
  }
  retryReceived(): void {
    void this.loadReceived(this.receivedAppendFailed);
  }
  async scanMore(): Promise<void> {
    if (!this.active || this.loading() || this.exhausted()) return;
    const generation = this.generation;
    const caller = this.auth.identity()?.sub;
    if (!caller) return;
    const target = this.batchTarget ?? this.reviews().length + 20;
    this.batchTarget = target;
    this.loading.set(true);
    this.error.set(false);
    try {
      while (this.reviews().length < target) {
        if (!this.pending.length) {
          if (this.started && !this.cursor) {
            this.exhausted.set(true);
            break;
          }
          const oldCursor = this.cursor;
          const page = await this.read(
            this.api.purchases({ size: 20, ...(this.started ? { cursor: this.cursor } : {}) }),
          );
          if (generation !== this.generation) return;
          if (this.started && oldCursor && page.nextCursor === oldCursor)
            throw new Error('Repeated cursor');
          this.started = true;
          this.cursor = page.nextCursor;
          this.pending = [
            ...new Map(
              page.items.filter((row) => !this.seenOrders.has(row.id)).map((row) => [row.id, row]),
            ).values(),
          ];
          continue;
        }
        const batch = this.pending.slice(0, 4);
        const results = await Promise.allSettled(
          batch.map((row) =>
            this.cache.has(row.id)
              ? Promise.resolve(this.cache.get(row.id)!)
              : this.read(this.api.order(row.id)),
          ),
        );
        if (generation !== this.generation) return;
        results.forEach((result, index) => {
          if (result.status === 'fulfilled') this.cache.set(batch[index].id, result.value);
        });
        for (let index = 0; index < results.length; index++) {
          if (this.reviews().length >= target) break;
          const result = results[index];
          if (result.status === 'rejected') throw result.reason;
          const detail = result.value;
          this.pending.shift();
          this.seenOrders.add(batch[index].id);
          this.scanned.update((count) => count + 1);
          // ReviewView has no author subject; the enclosing purchase's buyer is authoritative.
          if (
            detail.buyerSub === caller &&
            detail.review &&
            !this.seenReviews.has(detail.review.id)
          ) {
            const review = detail.review;
            this.seenReviews.add(review.id);
            this.reviews.update((rows) => [...rows, { review, orderId: detail.id }]);
          }
        }
      }
      if (!this.pending.length && this.started && !this.cursor) this.exhausted.set(true);
      this.batchTarget = null;
    } catch {
      if (generation === this.generation) this.error.set(true);
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  private reset(): void {
    this.pause();
    this.pending = [];
    this.cursor = null;
    this.started = false;
    this.batchTarget = null;
    this.seenOrders.clear();
    this.seenReviews.clear();
    this.cache.clear();
    this.reviews.set([]);
    this.error.set(false);
    this.exhausted.set(false);
    this.scanned.set(0);
    this.received.set([]);
    this.receivedCursor.set(null);
    this.receivedError.set(false);
    this.receivedLoaded.set(false);
  }
}

@Component({
  selector: 'app-me-reviews-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DatePipe, EmptyState, ErrorState, SegmentedControl],
  styleUrl: './me.page.css',
  template: `
    <div class="actions account-toolbar">
      <app-segmented-control
        label="Reviews"
        [options]="options"
        [selected]="store.mode()"
        (selectedChange)="store.select($event)"
      />
    </div>
    @if (store.mode() === 'authored') {
      <p class="muted">In purchase order. Reviews appear as your purchases are checked.</p>
      @if (store.loading()) {
        <p role="status">Checking purchases... {{ store.scanned() }} checked</p>
      }
      @if (store.error()) {
        <app-error-state (retry)="store.scanMore()" />
      }
      @for (row of store.reviews(); track row.review.id) {
        <article class="account-row">
          <h2>
            <a [routerLink]="['/listings', row.review.listingId]">{{ row.review.listingTitle }}</a>
          </h2>
          <p>{{ row.review.rating }} out of 5</p>
          <p>{{ row.review.comment }}</p>
          <time>{{ row.review.createdAt | date: 'mediumDate' }}</time>
          <a [routerLink]="['/u', row.review.sellerSub]">View seller</a
          ><a [routerLink]="['/orders', row.orderId]">View order</a>
        </article>
      }
      @if (store.exhausted() && !store.reviews().length && !store.error()) {
        <app-empty-state
          title="No reviews written yet"
          text="After a purchase is delivered you can review it from the order page."
        />
      }
      @if (!store.exhausted() && !store.loading() && !store.error()) {
        <button class="action show-more" (click)="store.scanMore()">Show more</button>
      }
    } @else {
      @if (store.receivedLoading()) {
        <p role="status">Loading reviews...</p>
      }
      @if (store.receivedError()) {
        <app-error-state (retry)="store.retryReceived()" />
      }
      @for (review of store.received(); track review.id) {
        <article class="account-row">
          <h2>
            <a [routerLink]="['/listings', review.listingId]">{{ review.listingTitle }}</a>
          </h2>
          <p>{{ review.rating }} out of 5 · {{ review.authorUsername || 'Member' }}</p>
          <p>{{ review.comment }}</p>
          <time>{{ review.createdAt | date: 'mediumDate' }}</time>
        </article>
      }
      @if (
        store.receivedLoaded() &&
        !store.received().length &&
        !store.receivedLoading() &&
        !store.receivedError()
      ) {
        <app-empty-state
          title="No reviews yet"
          text="Reviews from buyers of your listings will appear here."
        />
      }
      @if (store.receivedCursor()) {
        <button
          class="action show-more"
          [disabled]="store.receivedLoading()"
          (click)="store.loadReceived(true)"
        >
          Show more
        </button>
      }
    }
  `,
})
export class MeReviewsTab {
  readonly store = inject(MeReviewsStore);
  readonly options = [
    { value: 'received', label: 'Received' },
    { value: 'authored', label: 'Written by you' },
  ];
  constructor() {
    this.store.activate();
    inject(DestroyRef).onDestroy(() => this.store.pause());
  }
}
