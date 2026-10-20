import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog-api';
import { ListingCard } from '../../core/api/api-models';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';

export const ME_TABS = [
  { value: 'listings', label: 'Your listings' },
  { value: 'library', label: 'Library' },
  { value: 'reviews', label: 'Reviews' },
] as const;

export type MeTab = (typeof ME_TABS)[number]['value'];
export type MeTabStatus = 'idle' | 'loading' | 'ready' | 'error';

const DEFAULT_TAB: MeTab = 'listings';

export function normalizeMeTab(raw: string | null): MeTab {
  return ME_TABS.find((tab) => tab.value === raw)?.value ?? DEFAULT_TAB;
}

// Provided by MePage so tab content components can report their load state without owning the URL.
@Injectable()
export class MeTabsStore {
  private readonly catalog = inject(CatalogApi);
  private readonly destroyRef = inject(DestroyRef);
  readonly listings = signal<ListingCard[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly listingsLoading = signal(false);
  readonly listingsError = signal(false);
  private listingsLoaded = false;
  private failedAppend = false;
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly statuses = signal<Partial<Record<MeTab, MeTabStatus>>>({});
  private readonly retries = signal(0);

  readonly rawTab = toSignal(this.route.queryParamMap.pipe(map((params) => params.get('tab'))), {
    initialValue: this.route.snapshot.queryParamMap.get('tab'),
  });
  readonly tab = computed(() => normalizeMeTab(this.rawTab()));
  readonly activeStatus = computed(() => this.statuses()[this.tab()] ?? 'idle');
  readonly retryCount = this.retries.asReadonly();

  constructor() {
    effect(() => {
      if (this.tab() === 'listings')
        untracked(() => {
          if (!this.listingsLoaded && !this.listingsLoading()) this.loadListings();
        });
    });
    // A present but unknown ?tab= is rewritten so the URL never names a tab that does not exist.
    effect(() => {
      const raw = this.rawTab();
      const tab = this.tab();
      if (raw !== null && raw !== tab) {
        untracked(
          () =>
            void this.router.navigate([], {
              relativeTo: this.route,
              queryParams: { tab },
              queryParamsHandling: 'merge',
              replaceUrl: true,
            }),
        );
      }
    });
  }

  select(tab: string): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: normalizeMeTab(tab) },
      queryParamsHandling: 'merge',
    });
  }

  report(tab: MeTab, status: MeTabStatus): void {
    this.statuses.update((current) => ({ ...current, [tab]: status }));
  }

  retry(): void {
    if (this.tab() === 'listings') {
      this.loadListings(this.failedAppend);
      return;
    }
    this.retries.update((count) => count + 1);
  }

  loadListings(append = false): void {
    if (this.listingsLoading() || (append && !this.nextCursor())) return;
    this.listingsLoading.set(true);
    this.listingsError.set(false);
    this.catalog
      .myListings({ size: 20, ...(append ? { cursor: this.nextCursor() } : {}) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          const items = append ? [...this.listings(), ...page.items] : page.items;
          this.listings.set([...new Map(items.map((item) => [item.id, item])).values()]);
          this.nextCursor.set(page.nextCursor);
          this.listingsLoaded = true;
          this.listingsLoading.set(false);
        },
        error: () => {
          this.failedAppend = append;
          this.listingsLoading.set(false);
          this.listingsError.set(true);
        },
      });
  }
}
