import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  AttributeType,
  Condition,
  ListingDetailResponse,
  Platform,
  PriceChangeResponse,
  PublicProfileResponse,
  ReviewView,
  SearchHit,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { OrderApi } from '../../core/api/order-api';
import { UserApi } from '../../core/api/user-api';
import { AuthStore } from '../../core/auth/auth.store';
import { MotionPreference } from '../../core/shell/motion-preference';
import { Avatar, EurPipe, StatusChip } from '../../shared/ui/content';
import { ErrorState, Skeleton } from '../../shared/ui/feedback';
import { StarRating } from '../../shared/ui/forms';
import { LoadMore } from '../../shared/ui/navigation';
import { ModalDialog } from '../../shared/ui/dialog';
import { FollowButton, ListingActions } from './listing-actions';

type PageState = 'loading' | 'ready' | 'missing' | 'error';

const CONDITION_LABELS: Record<Condition, string> = {
  LIKE_NEW: 'Like new',
  VERY_GOOD: 'Very good',
  GOOD: 'Good',
  ACCEPTABLE: 'Acceptable',
};
const PLATFORM_LABELS: Record<Platform, string> = {
  WINDOWS: 'Windows',
  MACOS: 'macOS',
  LINUX: 'Linux',
  WEB: 'Web',
  ANDROID: 'Android',
  IOS: 'iOS',
};
const REVIEW_PAGE_SIZE = 5;
const SIMILAR_LIMIT = 4;
const DAY_FORMAT = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
});
const RELATIVE_FORMAT = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
const RELATIVE_UNITS: readonly [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 86_400_000],
  ['month', 30 * 86_400_000],
  ['week', 7 * 86_400_000],
  ['day', 86_400_000],
  ['hour', 3_600_000],
  ['minute', 60_000],
];
const eur = new EurPipe();

interface AttributeRow {
  label: string;
  value: string;
}

function formatDay(instant: string): string {
  const time = Date.parse(instant);
  return Number.isNaN(time) ? '' : DAY_FORMAT.format(time);
}

function relativeTime(instant: string): string {
  const time = Date.parse(instant);
  if (Number.isNaN(time)) return '';
  const elapsed = time - Date.now();
  for (const [unit, size] of RELATIVE_UNITS) {
    if (Math.abs(elapsed) >= size) return RELATIVE_FORMAT.format(Math.round(elapsed / size), unit);
  }
  return 'just now';
}

function attributeValue(type: AttributeType, value: unknown, unit: string | null): string | null {
  if (value === null || value === undefined || value === '') return null;
  let text: string;
  if (type === 'BOOLEAN') {
    text = value === true || value === 'true' ? 'Yes' : 'No';
  } else if (Array.isArray(value)) {
    text = value.join(', ');
  } else if (typeof value === 'object') {
    return null;
  } else {
    text = String(value);
  }
  return unit && type !== 'BOOLEAN' ? `${text} ${unit}` : text;
}

function isMissing(error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status;
  return status === 404 || status === 410;
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

@Component({
  selector: 'app-listing-detail-page',
  standalone: true,
  imports: [
    RouterLink,
    Avatar,
    EurPipe,
    StatusChip,
    StarRating,
    Skeleton,
    ErrorState,
    LoadMore,
    FollowButton,
    ListingActions,
    ModalDialog,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './listing-detail.page.css',
  template: `
    @switch (state()) {
      @case ('loading') {
        <div class="layout">
          <app-skeleton class="gallery-skeleton" label="Loading listing photos" />
          <div class="details-skeleton">
            <app-skeleton shape="line" label="Loading listing title" />
            <app-skeleton shape="line" label="Loading listing price" />
            <app-skeleton shape="line" label="Loading listing description" />
          </div>
        </div>
      }
      @case ('missing') {
        <h1 class="visually-hidden">Listing unavailable</h1>
        <app-error-state
          title="This listing isn't available"
          text="It may have been sold or removed."
          actionLabel="Browse listings"
          (retry)="browse()"
        />
        @if (listing()?.moderation; as moderation) {
          <p class="moderation">Removed by moderation: {{ moderation.reason }}</p>
        }
      }
      @case ('error') {
        <h1 class="visually-hidden">Listing unavailable</h1>
        <app-error-state (retry)="reload()" />
      }
      @case ('ready') {
        @if (listing(); as l) {
          <a class="back" routerLink="/" aria-label="Back to listings" title="Back to listings">
            <svg
              aria-hidden="true"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.75"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="m12 5-7 7 7 7M5 12h14" />
            </svg>
          </a>
          <div class="layout">
            <div class="gallery">
              <div
                #slides
                class="slides"
                role="group"
                [attr.aria-label]="'Photos of ' + l.title"
                [attr.tabindex]="photos().length > 1 ? 0 : null"
                (scroll)="onScroll(slides)"
              >
                @for (photo of photos(); track $index) {
                  <div class="slide">
                    @if (!failedPhotos().has(photo)) {
                      <button
                        type="button"
                        class="zoom"
                        [attr.aria-label]="'View photo ' + ($index + 1) + ' full screen'"
                        (click)="viewer.set($index)"
                      >
                        <img
                          [src]="photo"
                          alt=""
                          [attr.loading]="$index === 0 ? 'eager' : 'lazy'"
                          (error)="markFailed(photo)"
                        />
                      </button>
                    } @else {
                      <div class="placeholder" aria-hidden="true">
                        <span>photo unavailable</span>
                      </div>
                    }
                  </div>
                } @empty {
                  <div class="slide">
                    <div class="placeholder" aria-hidden="true"><span>no photo</span></div>
                  </div>
                }
              </div>
              @if (photos().length > 1) {
                <div class="dots" aria-hidden="true">
                  @for (photo of photos(); track $index) {
                    <span [class.active]="$index === selectedPhoto()"></span>
                  }
                </div>
                <ul class="thumbs">
                  @for (photo of photos(); track $index) {
                    <li>
                      <button
                        type="button"
                        [attr.aria-label]="'Show photo ' + ($index + 1) + ' of ' + photos().length"
                        [attr.aria-current]="$index === selectedPhoto() ? 'true' : null"
                        (click)="selectPhoto($index, slides)"
                      >
                        @if (!failedPhotos().has(photo)) {
                          <img [src]="photo" alt="" loading="lazy" (error)="markFailed(photo)" />
                        }
                      </button>
                    </li>
                  }
                </ul>
              }
            </div>

            <div class="details">
              <div class="chips">
                <app-status-chip [text]="categoryPath()" />
                @if (l.type === 'HARDWARE' && l.condition) {
                  <app-status-chip [text]="conditionLabel()" tone="accent" />
                }
                @if (l.type === 'SOFTWARE') {
                  <app-status-chip text="LICENCE KEY" tone="accent" />
                }
              </div>
              <h1 class="title">{{ l.title }}</h1>
              <div class="price">
                <span class="amount">{{ l.price | eur }}</span>
                @if (l.status === 'SOLD') {
                  <app-status-chip text="Sold" />
                }
              </div>
              @if (shippingLine(); as line) {
                <p class="shipping">{{ line }}</p>
              }
              <p class="description">{{ l.description }}</p>

              @if (attributeRows().length) {
                <dl class="attributes">
                  @for (row of attributeRows(); track $index) {
                    <div>
                      <dt>{{ row.label }}</dt>
                      <dd>{{ row.value }}</dd>
                    </div>
                  }
                </dl>
              }

              @if (l.software; as software) {
                <section class="software" aria-labelledby="software-heading">
                  <h2 id="software-heading" class="visually-hidden">Software</h2>
                  <div class="software-summary">
                    @if (software.platforms.length) {
                      <ul class="platforms" aria-label="Platforms">
                        @for (platform of software.platforms; track platform) {
                          <li>
                            <img
                              [src]="platformIcons[platform]"
                              alt=""
                              aria-hidden="true"
                              width="20"
                              height="20"
                            />
                            {{ platformLabel(platform) }}
                          </li>
                        }
                      </ul>
                    }
                    @if (currentVersion(); as version) {
                      <span class="current-version">Version {{ version.number }}</span>
                    }
                  </div>
                  @if (software.versions.length) {
                    <details class="version-history">
                      <summary>Version history</summary>
                      <ul class="versions" aria-label="Versions">
                        @for (version of software.versions; track version.number) {
                          <li>
                            <span class="version-number">{{ version.number }}</span>
                            @if (version.current) {
                              <app-status-chip text="CURRENT" tone="accent" />
                            }
                            <time [attr.datetime]="version.release">{{
                              day(version.release)
                            }}</time>
                            @if (version.notes) {
                              <span class="version-notes">{{ version.notes }}</span>
                            }
                          </li>
                        }
                      </ul>
                    </details>
                  }
                </section>
              }

              @if (priceHistory().length) {
                <details class="price-history">
                  <summary>{{ priceSummary() }}</summary>
                  <ul>
                    @for (change of priceHistory(); track change.instant) {
                      <li>
                        <span class="change"
                          >{{ change.oldPrice | eur }} to {{ change.newPrice | eur }}</span
                        >
                        <time [attr.datetime]="change.instant">{{ day(change.instant) }}</time>
                        @if (change.drop) {
                          <span class="drop">Price drop</span>
                        }
                      </li>
                    }
                  </ul>
                </details>
              }

              <div class="seller-card">
                <div class="seller-top">
                  @if (l.seller.username; as username) {
                    <a class="seller-link" [routerLink]="['/u', l.sellerSub]">
                      <app-avatar [username]="username" [url]="l.seller.avatar" />
                      <span class="seller-name">@{{ username }}</span>
                    </a>
                  } @else {
                    <div class="seller-link">
                      <span class="avatar-fallback" aria-hidden="true">?</span>
                      <span class="seller-name">Seller unavailable</span>
                    </div>
                  }
                  @if (profile(); as seller) {
                    @if (!isOwn()) {
                      <app-follow-button [profile]="seller" (profileChange)="profile.set($event)" />
                    }
                  }
                </div>
                <p class="rating">{{ ratingLine() }}</p>
                @if (profile(); as seller) {
                  <p class="followers">
                    {{ plural(seller.followerCount, 'follower') }} · {{ seller.followingCount }}
                    following
                  </p>
                }
              </div>
              <app-listing-actions [listing]="l" (changed)="refresh()" />
            </div>
          </div>

          @if (viewer() !== null) {
            <app-modal-dialog [heading]="l.title" size="wide" (closed)="viewer.set(null)">
              <img class="viewer-image" [src]="photos()[viewer() ?? 0]" [alt]="l.title" />
            </app-modal-dialog>
          }

          <div class="below">
            <section class="reviews" aria-labelledby="reviews-heading">
              <div class="section-head">
                <h2 id="reviews-heading">Reviews</h2>
              </div>
              @if (reviews().length) {
                <ul class="review-list">
                  @for (item of reviews(); track item.id) {
                    <li class="review">
                      <div class="review-head">
                        <span class="review-author"
                          >{{ item.authorUsername ? '@' + item.authorUsername : 'Unknown buyer' }}
                        </span>
                        <app-star-rating [value]="item.rating" />
                        <time class="review-time" [attr.datetime]="item.createdAt">{{
                          relative(item.createdAt)
                        }}</time>
                      </div>
                      @if (item.comment) {
                        <p class="review-comment">{{ item.comment }}</p>
                      }
                    </li>
                  }
                </ul>
                <app-load-more
                  [count]="reviews().length"
                  [noun]="reviews().length === 1 ? 'review' : 'reviews'"
                  [hasMore]="reviewsCursor() !== null"
                  [loading]="reviewsLoading()"
                  (load)="moreReviews()"
                />
              } @else if (!reviewsFailed()) {
                <p class="empty">No reviews for this listing yet.</p>
              }
              @if (reviewsFailed()) {
                <p class="empty">Reviews are unavailable right now.</p>
              }
            </section>
            @if (!isOwn() && reviewableOrderId(); as orderId) {
              <aside class="review-gate">
                <h2>Your purchase</h2>
                <a [routerLink]="['/orders', orderId]">Review your order</a>
              </aside>
            }
          </div>

          @if (similar().length) {
            <section class="similar" aria-labelledby="similar-heading">
              <h2 id="similar-heading">Similar items</h2>
              <ul>
                @for (item of similar(); track item.id) {
                  <li>
                    <a [routerLink]="['/listings', item.id]">
                      <div class="similar-photo">
                        @if (item.coverUrl) {
                          <img [src]="item.coverUrl" alt="" loading="lazy" />
                        } @else {
                          <div class="placeholder" aria-hidden="true"><span>no photo</span></div>
                        }
                      </div>
                      <span class="similar-title">{{ item.title }}</span>
                      <span class="similar-price">{{ item.price | eur }}</span>
                    </a>
                  </li>
                }
              </ul>
            </section>
          }
        }
      }
    }
  `,
})
export class ListingDetailPage {
  private readonly catalog = inject(CatalogApi);
  private readonly users = inject(UserApi);
  private readonly orders = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly motion = inject(MotionPreference);
  private readonly router = inject(Router);

  readonly id = input.required<string>();
  readonly state = signal<PageState>('loading');
  readonly listing = signal<ListingDetailResponse | null>(null);
  readonly profile = signal<PublicProfileResponse | null>(null);
  readonly reviews = signal<readonly ReviewView[]>([]);
  readonly reviewsCursor = signal<string | null>(null);
  readonly reviewsLoading = signal(false);
  readonly reviewsFailed = signal(false);
  readonly similar = signal<readonly SearchHit[]>([]);
  readonly reviewableOrderId = signal<string | null>(null);
  readonly selectedPhoto = signal(0);
  readonly viewer = signal<number | null>(null);
  readonly failedPhotos = signal<ReadonlySet<string>>(new Set());

  readonly isOwn = computed(() => {
    const sub = this.auth.identity()?.sub;
    return !!sub && sub === this.listing()?.sellerSub;
  });
  readonly photos = computed(() => this.listing()?.photos ?? []);
  readonly currentVersion = computed(() =>
    this.listing()?.software?.versions.find((version) => version.current),
  );
  readonly categoryPath = computed(() => {
    const category = this.listing()?.category;
    if (!category) return '';
    // The catalog's ancestor chain already ends with the category itself.
    const path = category.ancestors?.length ? category.ancestors : [category.name];
    return path.join(' / ');
  });
  readonly conditionLabel = computed(() => {
    const condition = this.listing()?.condition;
    return condition ? CONDITION_LABELS[condition] : '';
  });
  readonly shippingLine = computed(() => {
    const listing = this.listing();
    if (!listing || listing.type !== 'HARDWARE') return '';
    const cost = listing.shippingCost ? `Shipping ${eur.transform(listing.shippingCost)}` : '';
    const from = listing.shippingLocation
      ? `${cost ? 'from' : 'Ships from'} ${listing.shippingLocation}`
      : '';
    return [cost, from].filter(Boolean).join(' ');
  });
  readonly attributeRows = computed<AttributeRow[]>(() =>
    (this.listing()?.attributes ?? []).flatMap((attribute) => {
      const value = attributeValue(attribute.type, attribute.value, attribute.unit);
      return value === null ? [] : [{ label: attribute.name.replace(/[_-]+/g, ' ').trim(), value }];
    }),
  );
  readonly priceHistory = computed<PriceChangeResponse[]>(() =>
    [...(this.listing()?.priceHistory ?? [])].sort(
      (a, b) => Date.parse(b.instant) - Date.parse(a.instant),
    ),
  );
  readonly priceSummary = computed(() => {
    const last = this.priceHistory()[0];
    if (!last) return '';
    const verb = last.drop ? 'Dropped' : 'Raised';
    const day = formatDay(last.instant);
    return `Price history: ${verb} from ${eur.transform(last.oldPrice)} to ${eur.transform(last.newPrice)}${day ? ` on ${day}` : ''}`;
  });
  readonly ratingLine = computed(() => {
    const seller = this.listing()?.seller;
    return seller && seller.ratingAvg !== null && seller.ratingCount > 0
      ? `${seller.ratingAvg.toFixed(1)} stars · ${plural(seller.ratingCount, 'review')}`
      : 'No reviews yet';
  });

  protected readonly plural = plural;
  protected readonly platformIcons: Record<Platform, string> = {
    WINDOWS: '/icons/platforms/windows.svg',
    MACOS: '/icons/platforms/apple.svg',
    LINUX: '/icons/platforms/linux.svg',
    WEB: '/icons/platforms/web.svg',
    ANDROID: '/icons/platforms/android.svg',
    IOS: '/icons/platforms/apple.svg',
  };
  private requests = new Subscription();

  constructor() {
    effect((onCleanup) => {
      const id = this.id();
      untracked(() => this.start(id));
      onCleanup(() => this.requests.unsubscribe());
    });
  }

  reload(): void {
    this.requests.unsubscribe();
    this.start(this.id());
  }

  refresh(): void {
    this.fetch(this.id(), true);
  }

  browse(): void {
    void this.router.navigateByUrl('/');
  }

  day(instant: string): string {
    return formatDay(instant);
  }

  relative(instant: string): string {
    return relativeTime(instant);
  }

  platformLabel(platform: Platform): string {
    return PLATFORM_LABELS[platform] ?? platform;
  }

  markFailed(photo: string): void {
    this.failedPhotos.update((failed) => new Set(failed).add(photo));
  }

  onScroll(slides: HTMLElement): void {
    if (slides.clientWidth > 0) {
      this.selectedPhoto.set(Math.round(slides.scrollLeft / slides.clientWidth));
    }
  }

  selectPhoto(index: number, slides: HTMLElement): void {
    this.selectedPhoto.set(index);
    if (typeof slides.scrollTo === 'function') {
      slides.scrollTo({
        left: index * slides.clientWidth,
        behavior: this.motion.enabled() ? 'auto' : 'smooth',
      });
    }
  }

  moreReviews(): void {
    const listing = this.listing();
    const cursor = this.reviewsCursor();
    if (listing && cursor && !this.reviewsLoading()) {
      this.loadReviews(listing, cursor);
    }
  }

  private start(id: string): void {
    this.requests = new Subscription();
    this.state.set('loading');
    this.listing.set(null);
    this.profile.set(null);
    this.reviews.set([]);
    this.reviewsCursor.set(null);
    this.reviewsLoading.set(false);
    this.reviewsFailed.set(false);
    this.similar.set([]);
    this.reviewableOrderId.set(null);
    this.selectedPhoto.set(0);
    this.viewer.set(null);
    this.failedPhotos.set(new Set());
    this.fetch(id, false);
  }

  private fetch(id: string, silent: boolean): void {
    this.requests.add(
      this.catalog.listing(id).subscribe({
        next: (listing) => {
          this.listing.set(listing);
          if (listing.status === 'REMOVED') {
            this.state.set('missing');
            return;
          }
          this.state.set('ready');
          if (!silent) this.loadAround(listing);
        },
        error: (error: unknown) => {
          if (isMissing(error)) this.state.set('missing');
          else if (!silent) this.state.set('error');
        },
      }),
    );
  }

  // Seller card, reviews and similar items are optional enrichment: a failure leaves the listing itself intact.
  private loadAround(listing: ListingDetailResponse): void {
    this.requests.add(
      this.users.getPublicProfile(listing.sellerSub).subscribe({
        next: (profile) => this.profile.set(profile),
        error: () => this.profile.set(null),
      }),
    );
    this.loadReviews(listing, null);
    this.requests.add(
      this.catalog.search({ categoryId: listing.categoryId, size: SIMILAR_LIMIT + 1 }).subscribe({
        next: (page) =>
          this.similar.set(
            (page?.hits ?? []).filter((hit) => hit.id !== listing.id).slice(0, SIMILAR_LIMIT),
          ),
        error: () => this.similar.set([]),
      }),
    );
    if (this.auth.isAuthenticated() && !this.isOwn()) {
      this.requests.add(
        this.orders.purchases({ size: 50 }).subscribe({
          next: (page) => {
            const order = (page?.items ?? []).find(
              (item) =>
                item.status === 'DELIVERED' &&
                item.listingId === listing.id &&
                item.sellerSub === listing.sellerSub &&
                !item.review,
            );
            this.reviewableOrderId.set(order?.id ?? null);
          },
          error: () => this.reviewableOrderId.set(null),
        }),
      );
    }
  }

  private loadReviews(listing: ListingDetailResponse, cursor: string | null): void {
    this.reviewsLoading.set(true);
    const query = { listingId: listing.id, size: REVIEW_PAGE_SIZE, ...(cursor ? { cursor } : {}) };
    this.requests.add(
      this.orders.sellerReviews(listing.sellerSub, query).subscribe({
        next: (page) => {
          const items = page?.items ?? [];
          this.reviews.update((current) => (cursor ? [...current, ...items] : items));
          this.reviewsCursor.set(page?.nextCursor ?? null);
          this.reviewsFailed.set(false);
          this.reviewsLoading.set(false);
        },
        error: () => {
          this.reviewsFailed.set(true);
          this.reviewsLoading.set(false);
        },
      }),
    );
  }
}
