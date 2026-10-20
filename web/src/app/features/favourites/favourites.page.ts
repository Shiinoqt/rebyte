import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CatalogApi } from '../../core/api/catalog-api';
import { FavouriteEntry } from '../../core/api/api-models';
import { ListingCard, ListingCardData, StatusChip } from '../../shared/ui/content';
import { EmptyState, ErrorState, Skeleton, ToastService } from '../../shared/ui/feedback';
import { HomeFavourites } from '../home-search/home-favourites.service';
import { Subscription } from 'rxjs';
import { AccountSummary } from '../profile/account-summary';

@Component({
  selector: 'app-favourites-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ListingCard, StatusChip, EmptyState, ErrorState, Skeleton],
  styleUrl: '../profile/me.page.css',
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-lg);
    }
  `,
  template: `
    <h1 tabindex="-1">Favourites</h1>
    @if (loading() && !entries().length) {
      <app-skeleton label="Loading favourites" />
    }
    @if (error()) {
      <app-error-state (retry)="load()" />
    }
    @if (!loading() && !error() && !entries().length) {
      <app-empty-state title="No favourites yet" text="Save items you like to find them here." />
    }
    <div class="listing-grid">
      @for (entry of entries(); track entry.listingId) {
        <div
          class="managed-card"
          [attr.data-favourite-entry]="entry.listingId"
          [attr.aria-busy]="pending().has(entry.listingId)"
        >
          <app-listing-card
            [listing]="card(entry)"
            [favourite]="true"
            (favouriteChange)="remove(entry.listingId)"
          />
          @if (entry.status !== 'AVAILABLE') {
            <app-status-chip [text]="entry.status === 'SOLD' ? 'Sold' : 'Unavailable'" />
          }
          @if (failed().has(entry.listingId)) {
            <p role="alert">
              Could not remove this favourite.
              <button class="action" type="button" (click)="remove(entry.listingId)">
                Try again
              </button>
            </p>
          }
        </div>
      }
    </div>
    @if (hasMore()) {
      <button
        type="button"
        class="action show-more"
        data-load-favourites
        [disabled]="loading()"
        (click)="load()"
      >
        {{ loading() ? 'Loading…' : 'Show more' }}
      </button>
    }
  `,
})
export class FavouritesPage {
  private readonly api = inject(CatalogApi);
  private readonly favourites = inject(HomeFavourites);
  private readonly summary = inject(AccountSummary);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  readonly entries = signal<FavouriteEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly pending = signal<ReadonlySet<string>>(new Set());
  readonly failed = signal<ReadonlySet<string>>(new Set());
  private readonly page = signal(-1);
  private readonly totalPages = signal(0);
  private readonly removed = new Set<string>();
  private pageRequest?: Subscription;
  readonly hasMore = computed(() => this.page() + 1 < this.totalPages());
  constructor() {
    this.load();
  }
  card(entry: FavouriteEntry): ListingCardData {
    return {
      id: entry.listingId,
      title: entry.title,
      price: entry.price,
      coverUrl: entry.coverUrl,
    };
  }
  load(): void {
    if (this.loading() || (this.page() >= 0 && !this.hasMore())) return;
    this.loading.set(true);
    this.error.set(false);
    this.pageRequest = this.api
      .favourites({ page: this.page() + 1, size: 20 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          const entries = new Map(this.entries().map((entry) => [entry.listingId, entry]));
          for (const entry of response.favourites)
            if (!entries.has(entry.listingId) && !this.removed.has(entry.listingId))
              entries.set(entry.listingId, entry);
          this.entries.set([...entries.values()]);
          this.page.set(response.page);
          this.totalPages.set(response.totalPages);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set(true);
        },
      });
  }
  remove(id: string): void {
    if (this.pending().has(id)) return;
    this.pending.update((ids) => new Set([...ids, id]));
    this.failed.update((ids) => new Set([...ids].filter((item) => item !== id)));
    this.favourites
      .toggle(id, false)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((success) => {
        this.pending.update((ids) => new Set([...ids].filter((item) => item !== id)));
        if (success) {
          this.pageRequest?.unsubscribe();
          this.loading.set(false);
          this.removed.add(id);
          this.entries.set([]);
          this.page.set(-1);
          this.totalPages.set(0);
          this.load();
          this.summary.refresh();
          this.toasts.show({ text: 'Removed from favourites', kind: 'success' });
        } else this.failed.update((ids) => new Set([...ids, id]));
      });
  }
}
