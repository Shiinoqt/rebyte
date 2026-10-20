import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { CatalogApi } from '../../core/api/catalog-api';
import { ListingCard } from '../../core/api/api-models';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { ListingCard as ListingCardComponent, StatusChip } from '../../shared/ui/content';
import { ModalDialog } from '../../shared/ui/dialog';
import { ErrorState, Skeleton, ToastService } from '../../shared/ui/feedback';
import { MeTabsStore } from './me-tabs.store';

@Component({
  selector: 'app-me-listings-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ListingCardComponent, StatusChip, ModalDialog, ErrorState, Skeleton],
  styleUrl: './me.page.css',
  template: `
    @if (tabs.listingsLoading() && !tabs.listings().length) {
      <app-skeleton label="Loading your listings" />
    }
    @if (tabs.listingsError()) {
      <app-error-state (retry)="tabs.retry()" />
    }
    @if (!tabs.listingsLoading() && !tabs.listingsError() && !tabs.listings().length) {
      <div class="empty">
        <h2>You haven't listed anything yet</h2>
        <a class="action" routerLink="/sell">Sell an item</a>
      </div>
    }
    <div class="listing-grid">
      @for (listing of tabs.listings(); track listing.id) {
        <div class="managed-card" [attr.data-owner-listing]="listing.id">
          <app-listing-card [listing]="listing" [showFavourite]="false" />
          <app-status-chip
            [text]="statusLabel(listing.status)"
            [tone]="listing.status === 'ACTIVE' ? 'accent' : 'neutral'"
          />
          <div class="actions">
            @if (listing.status === 'ACTIVE') {
              <a class="action" [routerLink]="['/listings', listing.id, 'edit']">Edit</a>
              <button
                type="button"
                class="action danger"
                data-remove-listing
                [disabled]="tabs.listingsLoading()"
                (click)="selected.set(listing)"
              >
                Remove
              </button>
              @if (listing.type === 'SOFTWARE') {
                <a class="action release" [routerLink]="['/listings', listing.id, 'releases']"
                  >Release a new version</a
                >
              }
            } @else {
              <a class="action" [routerLink]="['/listings', listing.id]">View</a>
            }
          </div>
        </div>
      }
    </div>
    @if (tabs.nextCursor()) {
      <button
        type="button"
        class="action show-more"
        data-load-listings
        [disabled]="tabs.listingsLoading() || removing()"
        (click)="tabs.loadListings(true)"
      >
        {{ tabs.listingsLoading() ? 'Loading…' : 'Show more' }}
      </button>
    }
    @if (selected(); as listing) {
      <app-modal-dialog heading="Remove this listing?" size="confirm" (closed)="selected.set(null)">
        <p>'{{ listing.title }}' will be taken down from the marketplace. This can't be undone.</p>
        <div class="actions">
          <button type="button" class="action" (click)="selected.set(null)">Keep it</button>
          <button
            type="button"
            class="action danger filled"
            data-confirm-remove
            [disabled]="removing() || tabs.listingsLoading()"
            (click)="remove()"
          >
            {{ removing() ? 'Removing…' : 'Remove' }}
          </button>
        </div>
      </app-modal-dialog>
    }
  `,
})
export class MeListingsTab {
  readonly tabs = inject(MeTabsStore);
  private readonly api = inject(CatalogApi);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  readonly selected = signal<ListingCard | null>(null);
  readonly removing = signal(false);
  statusLabel(status: ListingCard['status']): string {
    return { ACTIVE: 'Active', SOLD: 'Sold', REMOVED: 'Removed' }[status];
  }
  remove(): void {
    const listing = this.selected();
    if (!listing || this.removing() || this.tabs.listingsLoading()) return;
    this.removing.set(true);
    this.api
      .deleteListing(listing.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.removing.set(false);
          this.selected.set(null);
          this.toasts.show({ text: 'Listing removed', kind: 'success' });
          this.tabs.loadListings();
        },
        error: (error: AppError) => {
          this.removing.set(false);
          this.selected.set(null);
          const conflict =
            error.code === 'listing-already-removed' || error.code === 'listing-not-owned';
          this.toasts.show({
            text: conflict
              ? 'This listing has changed. Your listings have been refreshed.'
              : errorCopy(error),
            kind: 'error',
          });
          if (conflict) this.tabs.loadListings();
        },
      });
  }
}
