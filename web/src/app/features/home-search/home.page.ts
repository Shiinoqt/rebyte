import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DOCUMENT } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of, switchMap } from 'rxjs';
import {
  AttributeDefinitionResponse,
  CategoryTreeNode,
  SearchHit,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { EmptyState, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { ListingCard, ListingCardData } from '../../shared/ui/content';
import { LoadMore } from '../../shared/ui/navigation';
import { AuthStore } from '../../core/auth/auth.store';
import { HomeFavourites } from './home-favourites.service';
import { SearchFilters } from './search-filters';
import { SearchStateService } from './search-state.service';

const SKELETONS = [0, 1, 2, 3, 4, 5, 6, 7];

function findCategory(nodes: readonly CategoryTreeNode[], id: string): CategoryTreeNode | null {
  for (const node of nodes) {
    const found = node.id === id ? node : findCategory(node.children, id);
    if (found) return found;
  }
  return null;
}

function toCard(hit: SearchHit): ListingCardData {
  return {
    id: hit.id,
    title: hit.title,
    price: hit.price,
    type: hit.listingType,
    status: hit.status,
    coverUrl: hit.coverUrl,
    condition: hit.condition,
    shippingLocation: hit.shippingLocation,
    sellerUsername: hit.sellerUsername,
    platforms: hit.platforms,
    currentVersion: hit.currentVersion,
  };
}

@Component({
  selector: 'app-home-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, ErrorState, Skeleton, ListingCard, LoadMore, SearchFilters, RouterLink],
  providers: [SearchStateService],
  styleUrl: './home.page.css',
  template: `
    <h1 class="visually-hidden" tabindex="-1">{{ title() }}</h1>
    <p class="result-count visually-hidden" role="status" aria-live="polite" aria-atomic="true">
      {{ search.status() === 'ready' ? countText() : '' }}
    </p>

    <app-search-filters [tree]="categories()" [attributes]="schema()" />

    @switch (search.status()) {
      @case ('loading') {
        <div class="grid" aria-busy="true">
          @for (slot of skeletons; track slot) {
            <app-skeleton label="Loading listings" />
          }
        </div>
      }
      @case ('error') {
        <app-error-state (retry)="search.retry()" />
      }
      @default {
        @if (search.hits().length === 0) {
          @if (search.isActive()) {
            <app-empty-state
              title="Nothing matches"
              text="Try a different search, or clear a filter."
              actionLabel="Clear filters"
              (action)="search.clearAll()"
            />
          } @else {
            <app-empty-state
              title="No listings yet"
              text="Be the first to sell something."
              actionLabel="Sell an item"
              (action)="sell()"
            />
          }
        } @else {
          <div class="grid">
            @for (hit of search.hits(); track hit.id) {
              <app-listing-card
                [listing]="card(hit)"
                [favourite]="favourites.ids().has(hit.id)"
                (favouriteChange)="toggleFavourite(hit.id, $event)"
              />
            }
          </div>
          <app-load-more
            [count]="search.hits().length"
            [noun]="search.hits().length === 1 ? 'listing' : 'listings'"
            [hasMore]="search.canLoadMore()"
            [loading]="search.loadingMore()"
            (load)="search.loadMore()"
          />
        }
      }
    }

    <dialog
      #favouriteLoginDialog
      class="favourite-login-dialog"
      aria-labelledby="favourite-login-title"
      (close)="restoreFavouriteFocus()"
      (keydown.escape)="dismissFavouriteLogin()"
    >
      <h2 id="favourite-login-title">Log in to save favourites</h2>
      <p>Favourites sync across your devices.</p>
      <div class="dialog-actions">
        <a
          class="action primary"
          routerLink="/login"
          [queryParams]="{ returnUrl: loginReturnUrl() }"
          >Log in</a
        >
        <button type="button" class="action" (click)="dismissFavouriteLogin()">Not now</button>
      </div>
    </dialog>
  `,
})
export class HomePage {
  protected readonly search = inject(SearchStateService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthStore);
  private readonly catalog = inject(CatalogApi);
  protected readonly favourites = inject(HomeFavourites);
  private readonly document = inject(DOCUMENT);
  private readonly favouriteLoginDialog =
    viewChild<ElementRef<HTMLDialogElement>>('favouriteLoginDialog');
  private favouriteDialogOpener: HTMLElement | null = null;
  protected readonly loginReturnUrl = signal('/');
  protected readonly categories = signal<readonly CategoryTreeNode[]>([]);
  protected readonly skeletons = SKELETONS;

  protected readonly title = computed(() => {
    const state = this.search.state();
    if (state.q) return `Results for "${state.q}"`;
    const category = state.cat ? findCategory(this.categories(), state.cat) : null;
    return category?.name ?? 'All listings';
  });
  protected readonly countText = computed(() => {
    const total = this.search.total();
    return total === 1 ? '1 listing' : `${total} listings`;
  });

  // Attribute filters exist only for a leaf category: the backend refuses them otherwise.
  private readonly leafCategory = computed(() => {
    const cat = this.search.state().cat;
    const node = cat ? findCategory(this.categories(), cat) : null;
    return node?.leaf ? node.id : null;
  });
  protected readonly schema = toSignal(
    toObservable(this.leafCategory).pipe(
      switchMap((id) =>
        id
          ? this.catalog.categorySchema(id).pipe(
              map((response) => response.attributes),
              catchError(() => of<AttributeDefinitionResponse[]>([])),
            )
          : of<AttributeDefinitionResponse[]>([]),
      ),
    ),
    { initialValue: [] },
  );

  constructor() {
    this.catalog
      .categories()
      .subscribe({ next: (tree) => this.categories.set(tree), error: () => undefined });
  }

  protected card(hit: SearchHit): ListingCardData {
    return toCard(hit);
  }

  protected sell(): void {
    void this.router.navigateByUrl('/sell');
  }

  protected toggleFavourite(listingId: string, favourite: boolean): void {
    if (!this.auth.isAuthenticated()) {
      const dialog = this.favouriteLoginDialog()?.nativeElement;
      if (!dialog || dialog.open) return;
      this.favouriteDialogOpener = this.document.activeElement as HTMLElement | null;
      this.loginReturnUrl.set(this.router.url);
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
      return;
    }
    this.favourites.toggle(listingId, favourite);
  }

  protected dismissFavouriteLogin(): void {
    const dialog = this.favouriteLoginDialog()?.nativeElement;
    if (dialog?.open && typeof dialog.close === 'function') dialog.close();
    else {
      dialog?.removeAttribute('open');
      this.restoreFavouriteFocus();
    }
  }

  protected restoreFavouriteFocus(): void {
    if (this.favouriteDialogOpener?.isConnected) this.favouriteDialogOpener.focus();
    this.favouriteDialogOpener = null;
  }
}
