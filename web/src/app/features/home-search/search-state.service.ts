import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationStart, ParamMap, Params, Router } from '@angular/router';
import {
  EMPTY,
  Subject,
  Subscription,
  catchError,
  distinctUntilChanged,
  filter,
  map,
  merge,
  switchMap,
  take,
  tap,
} from 'rxjs';
import {
  FacetDistribution,
  ListingType,
  SearchHit,
  SearchQuery,
  SearchResultPage,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { ToastService } from '../../shared/ui/feedback';

export const PAGE_SIZE = 24;
export const PRICE_MAX = 2000;

const PRICE = /^\d{1,7}(?:\.\d{1,2})?$/;
const CATEGORY_ID = /^[\w.-]{1,100}$/;
const ATTRIBUTE_KEY = /^attr\.([A-Za-z0-9_]{1,64})(\.(?:min|max))?$/;
const MAX_QUERY_LENGTH = 200;
const MAX_ATTRIBUTES = 20;

export interface SearchFilterState {
  readonly q: string;
  readonly cat: string | null;
  readonly type: ListingType | null;
  readonly min: string | null;
  readonly max: string | null;
  // Keys are the attribute name, optionally suffixed with .min or .max, without the attr. prefix.
  readonly attrs: Readonly<Record<string, string>>;
}

export const EMPTY_STATE: SearchFilterState = {
  q: '',
  cat: null,
  type: null,
  min: null,
  max: null,
  attrs: {},
};

function validAttributeValue(value: string): boolean {
  return value.length > 0 && value.length <= 100 && !/[\u0000-\u001f\u007f]/.test(value);
}

// Only allowlisted keys with well-formed values survive; everything else in the URL is ignored.
export function parseSearchState(params: ParamMap): SearchFilterState {
  const q = (params.get('q') ?? '').trim().slice(0, MAX_QUERY_LENGTH);
  const cat = params.get('cat');
  const type = params.get('type');
  const min = params.get('min');
  const max = params.get('max');
  const attrs: Record<string, string> = {};
  for (const key of params.keys) {
    const match = ATTRIBUTE_KEY.exec(key);
    const value = params.get(key);
    if (
      match &&
      value !== null &&
      validAttributeValue(value) &&
      Object.keys(attrs).length < MAX_ATTRIBUTES
    ) {
      attrs[key.slice('attr.'.length)] = value;
    }
  }
  return normalisePrice({
    q,
    cat: cat !== null && CATEGORY_ID.test(cat) ? cat : null,
    type: type === 'HARDWARE' || type === 'SOFTWARE' ? type : null,
    min: min !== null && PRICE.test(min) ? min : null,
    max: max !== null && PRICE.test(max) ? max : null,
    attrs,
  });
}

export function toQueryParams(state: SearchFilterState): Params {
  const params: Params = {};
  if (state.q) params['q'] = state.q;
  if (state.cat) params['cat'] = state.cat;
  if (state.type) params['type'] = state.type;
  if (state.min) params['min'] = state.min;
  if (state.max) params['max'] = state.max;
  for (const [key, value] of Object.entries(state.attrs)) params['attr.' + key] = value;
  return params;
}

function sameState(a: SearchFilterState, b: SearchFilterState): boolean {
  return JSON.stringify(toQueryParams(a)) === JSON.stringify(toQueryParams(b));
}

// A lower bound of 0 and an upper bound at the slider end mean "no bound".
export function normalisePrice(state: SearchFilterState): SearchFilterState {
  const min = state.min !== null && Number(state.min) === 0 ? null : state.min;
  const max = state.max !== null && Number(state.max) === PRICE_MAX ? null : state.max;
  return min === state.min && max === state.max ? state : { ...state, min, max };
}

export function priceRangeInverted(state: Pick<SearchFilterState, 'min' | 'max'>): boolean {
  return state.min !== null && state.max !== null && Number(state.min) > Number(state.max);
}

export function isRangeKey(key: string): boolean {
  return key.endsWith('.min') || key.endsWith('.max');
}

export function toSearchQuery(state: SearchFilterState, offset: number): SearchQuery {
  const price = normalisePrice(state);
  const sendPrice = !priceRangeInverted(price);
  const attributes: Record<`attr.${string}`, string> = {};
  for (const [key, value] of Object.entries(state.attrs)) attributes[`attr.${key}`] = value;
  return {
    q: state.q || null,
    categoryId: state.cat,
    listingType: state.type,
    minPrice: sendPrice ? price.min : null,
    maxPrice: sendPrice ? price.max : null,
    offset,
    size: PAGE_SIZE,
    attributes,
  };
}

export function filterCount(state: SearchFilterState): number {
  return (
    (state.cat ? 1 : 0) +
    (state.type ? 1 : 0) +
    (state.min || state.max ? 1 : 0) +
    new Set(Object.keys(state.attrs).map((key) => key.replace(/\.(?:min|max)$/, ''))).size
  );
}

export type SearchStatus = 'loading' | 'ready' | 'error';

@Injectable()
export class SearchStateService {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogApi);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly retryRequests = new Subject<void>();
  private moreRequest?: Subscription;
  private pendingState?: SearchFilterState;
  private navigationVersion = 0;
  private pageOffset = 0;

  private readonly states = this.route.queryParamMap.pipe(
    map(parseSearchState),
    distinctUntilChanged(sameState),
  );

  readonly state = toSignal(this.states, { requireSync: true });
  readonly hits = signal<readonly SearchHit[]>([]);
  readonly facets = signal<readonly FacetDistribution[]>([]);
  readonly total = signal(0);
  private readonly loaded = signal(0);
  readonly status = signal<SearchStatus>('loading');
  readonly loadingMore = signal(false);
  readonly filterRefused = signal(false);
  readonly filters = computed(() => filterCount(this.state()));
  readonly hasFilters = computed(() => this.filters() > 0);
  readonly isActive = computed(() => this.state().q !== '' || this.hasFilters());
  readonly canLoadMore = computed(() => this.status() === 'ready' && this.loaded() < this.total());

  constructor() {
    this.route.queryParamMap.pipe(take(1)).subscribe((params) => {
      const state = parseSearchState(params);
      const queryParams: Params = {};
      if (params.get('min') !== null && state.min === null && Number(params.get('min')) === 0) {
        queryParams['min'] = null;
      }
      if (
        params.get('max') !== null &&
        state.max === null &&
        Number(params.get('max')) === PRICE_MAX
      ) {
        queryParams['max'] = null;
      }
      if (Object.keys(queryParams).length) {
        queueMicrotask(() => {
          void this.router.navigate(['/'], {
            queryParams,
            queryParamsHandling: 'merge',
            replaceUrl: true,
          });
        });
      }
    });

    this.router.events
      .pipe(
        filter(
          (event): event is NavigationStart =>
            event instanceof NavigationStart && event.navigationTrigger === 'popstate',
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.navigationVersion += 1;
        this.pendingState = undefined;
      });

    merge(this.states, this.retryRequests.pipe(map(() => this.state())))
      .pipe(
        tap(() => this.reset()),
        switchMap((state) =>
          this.catalog.search(toSearchQuery(state, 0)).pipe(
            catchError((error: AppError) => {
              this.fail(state, error);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => this.firstPage(page));
  }

  update(patch: Partial<SearchFilterState>, options: { history?: boolean } = {}): void {
    const current = this.pendingState ?? this.state();
    let next: SearchFilterState = { ...current, ...patch };
    if (patch.cat !== undefined && patch.cat !== current.cat && patch.attrs === undefined) {
      next = { ...next, attrs: {} };
    }
    this.filterRefused.set(false);
    this.navigate(normalisePrice(next), options.history === true);
  }

  clearFilters(): void {
    this.filterRefused.set(false);
    this.navigate({ ...EMPTY_STATE, q: (this.pendingState ?? this.state()).q }, false);
  }

  clearAll(): void {
    this.filterRefused.set(false);
    this.navigate(EMPTY_STATE, false);
  }

  retry(): void {
    this.retryRequests.next();
  }

  loadMore(): void {
    if (!this.canLoadMore() || this.loadingMore()) return;
    const state = this.state();
    this.loadingMore.set(true);
    this.moreRequest = this.catalog
      .search(toSearchQuery(state, this.pageOffset + PAGE_SIZE))
      .subscribe({
        next: (page) => {
          const known = new Set(this.hits().map((hit) => hit.id));
          this.hits.update((hits) => [...hits, ...page.hits.filter((hit) => !known.has(hit.id))]);
          this.total.set(page.total);
          this.loaded.set(page.offset + page.hits.length);
          this.pageOffset = page.offset;
          this.loadingMore.set(false);
        },
        error: (error: AppError) => {
          this.loadingMore.set(false);
          this.toasts.show({ text: errorCopy(error), kind: 'error' });
        },
      });
  }

  private navigate(state: SearchFilterState, history: boolean): void {
    const next = normalisePrice(state);
    const version = ++this.navigationVersion;
    this.pendingState = next;
    void this.router
      .navigate(['/'], { queryParams: toQueryParams(next), replaceUrl: !history })
      .then(
        () => {
          if (this.navigationVersion === version) this.pendingState = undefined;
        },
        () => {
          if (this.navigationVersion === version) this.pendingState = undefined;
        },
      );
  }

  private reset(): void {
    this.moreRequest?.unsubscribe();
    this.loadingMore.set(false);
    this.hits.set([]);
    this.facets.set([]);
    this.total.set(0);
    this.loaded.set(0);
    this.pageOffset = 0;
    this.status.set('loading');
  }

  private firstPage(page: SearchResultPage): void {
    this.hits.set(page.hits);
    this.facets.set(page.facets);
    this.total.set(page.total);
    this.loaded.set(page.offset + page.hits.length);
    this.pageOffset = page.offset;
    this.status.set('ready');
  }

  private fail(state: SearchFilterState, error: AppError): void {
    if (error.code === 'attribute-filter-not-allowed') {
      // Drop range filters first, since only the allowlisted vram_gb range is indexed; fall back to all attributes.
      const kept = Object.fromEntries(
        Object.entries(state.attrs).filter(([key]) => !isRangeKey(key)),
      );
      const attrs = Object.keys(kept).length < Object.keys(state.attrs).length ? kept : {};
      this.navigate({ ...state, attrs }, false);
      this.filterRefused.set(true);
      return;
    }
    this.status.set('error');
  }
}
