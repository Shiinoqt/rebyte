import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, map, Observable, of, Subscription, switchMap } from 'rxjs';
import {
  CursorPage,
  ListingCard as ListingCardModel,
  PublicProfileResponse,
  ReviewView,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { OrderApi } from '../../core/api/order-api';
import { UserApi } from '../../core/api/user-api';
import { validateReturnUrl } from '../../core/auth/auth.guards';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { Avatar, ListingCard, StatusChip } from '../../shared/ui/content';
import { Banner, ErrorState, Skeleton, ToastService } from '../../shared/ui/feedback';
import { LoadMore } from '../../shared/ui/navigation';
import { GENERATED_HANDLE } from './account-summary';

const PAGE_SIZE = 20;
const REVIEW_PAGE_SIZE = 5;

type ColumnStatus = 'loading' | 'ready' | 'error';
type ProfileStatus = 'loading' | 'ready' | 'missing' | 'error';

// One independently paged list; its request never touches the other column.
class PagedColumn<T> {
  readonly items = signal<readonly T[]>([]);
  readonly status = signal<ColumnStatus>('loading');
  readonly cursor = signal<string | null>(null);
  readonly loadingMore = signal(false);
  readonly moreFailed = signal(false);
  private subscription?: Subscription;

  constructor(private readonly fetch: (cursor: string | null) => Observable<CursorPage<T>>) {}

  reset(): void {
    this.subscription?.unsubscribe();
    this.items.set([]);
    this.cursor.set(null);
    this.status.set('loading');
    this.loadingMore.set(false);
    this.moreFailed.set(false);
  }

  load(): void {
    this.reset();
    this.subscription = this.fetch(null).subscribe({
      next: (page) => {
        this.items.set(page.items);
        this.cursor.set(page.nextCursor);
        this.status.set('ready');
      },
      error: () => this.status.set('error'),
    });
  }

  more(): void {
    const cursor = this.cursor();
    if (cursor === null || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.moreFailed.set(false);
    this.subscription = this.fetch(cursor).subscribe({
      next: (page) => {
        this.items.update((current) => [...current, ...page.items]);
        this.cursor.set(page.nextCursor);
        this.loadingMore.set(false);
      },
      error: () => {
        this.loadingMore.set(false);
        this.moreFailed.set(true);
      },
    });
  }

  stop(): void {
    this.subscription?.unsubscribe();
  }
}

@Component({
  selector: 'app-seller-profile',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    Avatar,
    Banner,
    DatePipe,
    ErrorState,
    ListingCard,
    LoadMore,
    RouterLink,
    Skeleton,
    StatusChip,
  ],
  styleUrl: './seller-profile.page.css',
  template: `
    <div class="page">
      @switch (profileStatus()) {
        @case ('ready') {
          @let p = profile()!;
          @if (generatedHandle()) {
            <app-banner
              style="margin-bottom: var(--space-lg)"
              text="Your username was generated for you."
              actionLabel="Choose a username"
              (action)="chooseUsername()"
            />
          }
          <section class="header" data-section="header">
            <app-avatar class="avatar" [username]="p.username" [url]="p.avatar" />
            <div class="identity">
              <h1 tabindex="-1">@{{ p.username }}</h1>
              @if (meta(); as line) {
                <p class="meta" data-section="meta">{{ line }}</p>
              }
              <ul class="stats">
                <li>
                  @if (p.ratingAvg !== null && p.ratingCount > 0) {
                    <span class="star" aria-hidden="true">★</span>
                    <b>{{ p.ratingAvg.toFixed(1) }}</b> stars
                    <span class="muted"
                      >({{ p.ratingCount }} {{ p.ratingCount === 1 ? 'review' : 'reviews' }})</span
                    >
                  } @else {
                    <span class="muted">No reviews yet</span>
                  }
                </li>
                <li>
                  <b>{{ p.completedSales }}</b> {{ p.completedSales === 1 ? 'sale' : 'sales' }}
                </li>
                <li>
                  <b>{{ p.followerCount }}</b>
                  {{ p.followerCount === 1 ? 'follower' : 'followers' }}
                </li>
                <li>
                  <b>{{ p.followingCount }}</b> following
                </li>
                @if (p.isDeveloper) {
                  <li><app-status-chip text="DEVELOPER" tone="accent" /></li>
                }
              </ul>
              @if (p.bio) {
                <p class="bio" data-section="bio">{{ p.bio }}</p>
              }
            </div>
            <div class="actions">
              @if (isOwn()) {
                <a class="action" routerLink="/settings">Edit profile</a>
              } @else {
                <button
                  type="button"
                  class="action follow"
                  data-follow
                  [class.following]="p.followedByMe"
                  [disabled]="followPending()"
                  [attr.aria-label]="(p.followedByMe ? 'Following @' : 'Follow @') + p.username"
                  (click)="toggleFollow()"
                >
                  {{ p.followedByMe ? 'Following' : 'Follow' }}
                </button>
              }
            </div>
          </section>
        }
        @case ('loading') {
          <h1 class="visually-hidden" tabindex="-1">Seller profile</h1>
          <section class="header" aria-hidden="false">
            <app-skeleton label="Loading profile" shape="avatar" />
          </section>
        }
        @case ('missing') {
          <h1 class="visually-hidden" tabindex="-1">Seller profile</h1>
          <app-error-state
            title="This profile doesn't exist"
            text="The seller may have been removed, or the link is wrong."
            actionLabel="Back to home"
            (retry)="goHome()"
          />
        }
        @case ('error') {
          <h1 class="visually-hidden" tabindex="-1">Seller profile</h1>
          <app-error-state (retry)="loadProfile()" />
        }
      }

      @if (profileStatus() === 'ready' || profileStatus() === 'loading') {
        <div class="body">
          <section class="column listings" data-column="listings">
            <h2>{{ listingsTitle() }}</h2>
            @switch (listings.status()) {
              @case ('loading') {
                <div class="grid">
                  @for (n of skeletonCards; track n) {
                    <app-skeleton label="Loading listing" />
                  }
                </div>
              }
              @case ('error') {
                <app-error-state title="We couldn't load the listings." (retry)="listings.load()" />
              }
              @default {
                @if (listings.items().length === 0) {
                  <p class="empty">No active listings right now.</p>
                } @else {
                  <div class="grid">
                    @for (item of listings.items(); track item.id) {
                      <app-listing-card
                        [listing]="item"
                        [favourite]="favourites().has(item.id)"
                        (favouriteChange)="toggleFavourite(item.id, $event)"
                      />
                    }
                  </div>
                  <app-load-more
                    noun="listings"
                    [count]="listings.items().length"
                    [hasMore]="listings.cursor() !== null"
                    [loading]="listings.loadingMore()"
                    (load)="listings.more()"
                  />
                  @if (listings.moreFailed()) {
                    <p class="more-error" role="alert">
                      We couldn't load more listings. Try again.
                    </p>
                  }
                }
              }
            }
          </section>
          <section class="column reviews" data-column="reviews">
            <h2>Reviews</h2>
            @switch (reviews.status()) {
              @case ('loading') {
                <app-skeleton label="Loading reviews" shape="line" />
              }
              @case ('error') {
                <app-error-state title="We couldn't load the reviews." (retry)="reviews.load()" />
              }
              @default {
                @if (reviews.items().length === 0) {
                  <p class="empty">No reviews yet.</p>
                } @else {
                  <ul class="review-list">
                    @for (r of reviews.items(); track r.id) {
                      <li class="review" data-review>
                        <div class="review-head">
                          <span class="author">{{
                            r.authorUsername ? '@' + r.authorUsername : 'Deleted user'
                          }}</span>
                          <span
                            class="stars"
                            role="img"
                            [attr.aria-label]="r.rating + ' out of 5 stars'"
                            >{{ stars(r.rating) }}</span
                          >
                          <time class="when" [attr.datetime]="r.createdAt">{{
                            r.createdAt | date: 'd MMM y' : 'UTC'
                          }}</time>
                        </div>
                        @if (r.comment) {
                          <p class="comment">{{ r.comment }}</p>
                        }
                        <p class="about">{{ r.listingTitle }}</p>
                      </li>
                    }
                  </ul>
                  <app-load-more
                    noun="reviews"
                    [count]="reviews.items().length"
                    [hasMore]="reviews.cursor() !== null"
                    [loading]="reviews.loadingMore()"
                    (load)="reviews.more()"
                  />
                  @if (reviews.moreFailed()) {
                    <p class="more-error" role="alert">We couldn't load more reviews. Try again.</p>
                  }
                }
              }
            }
          </section>
        </div>
      }

      <dialog
        #loginDialog
        class="login-dialog"
        aria-labelledby="login-dialog-title"
        (close)="loginPrompt.set(null)"
      >
        @let prompt = loginKind();
        <h2 id="login-dialog-title">
          {{ prompt === 'favourite' ? 'Log in to save favourites' : 'Log in to continue' }}
        </h2>
        <p>
          {{
            prompt === 'favourite'
              ? 'Favourites sync across your devices.'
              : 'Log in to follow sellers and see their new listings.'
          }}
        </p>
        <div class="dialog-actions">
          <button type="button" class="action" (click)="loginPrompt.set(null)">Not now</button>
          <a class="action primary" routerLink="/login" [queryParams]="{ returnUrl: returnUrl() }"
            >Log in</a
          >
        </div>
      </dialog>
    </div>
  `,
})
export class SellerProfilePage {
  private readonly users = inject(UserApi);
  private readonly catalog = inject(CatalogApi);
  private readonly orders = inject(OrderApi);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthStore);
  private readonly toasts = inject(ToastService);

  private readonly sub = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('sub'))),
    { initialValue: null },
  );

  private readonly viewerSub = computed(() => this.auth.identity()?.sub ?? null);
  private readonly loginDialog = viewChild<ElementRef<HTMLDialogElement>>('loginDialog');

  protected readonly skeletonCards = [0, 1, 2];
  protected readonly followPending = signal(false);
  protected readonly loginPrompt = signal<'follow' | 'favourite' | null>(null);
  protected readonly loginKind = signal<'follow' | 'favourite'>('follow');
  // Listing cards carry no favourite state, so only toggles made on this page are reflected.
  protected readonly favourites = signal<ReadonlySet<string>>(new Set());
  private readonly favouritePending = new Set<string>();
  private followRequest?: Subscription;
  protected readonly profile = signal<PublicProfileResponse | null>(null);
  protected readonly profileStatus = signal<ProfileStatus>('loading');
  private profileRequest?: Subscription;

  protected readonly listings = new PagedColumn<ListingCardModel>((cursor) =>
    this.catalog.sellerListings(this.sub() ?? '', { size: PAGE_SIZE, cursor }),
  );
  protected readonly reviews = new PagedColumn<ReviewView>((cursor) =>
    this.orders.sellerReviews(this.sub() ?? '', { size: REVIEW_PAGE_SIZE, cursor }),
  );

  protected readonly isOwn = computed(() => {
    const viewer = this.viewerSub();
    return viewer !== null && viewer === this.profile()?.sub;
  });

  protected readonly generatedHandle = computed(
    () => this.isOwn() && GENERATED_HANDLE.test(this.profile()?.username ?? ''),
  );

  protected chooseUsername(): void {
    void this.router.navigateByUrl('/settings#settings-username');
  }

  protected readonly listingsTitle = computed(() => {
    const p = this.profile();
    return p ? `Listings by @${p.username}` : 'Listings';
  });

  protected readonly meta = computed(() => {
    const p = this.profile();
    if (!p) return '';
    const parts: string[] = [];
    if (p.location) parts.push(p.location);
    if (p.registration) {
      parts.push(
        `Member since ${new Date(p.registration).toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' })}`,
      );
    }
    return parts.join(' · ');
  });

  constructor() {
    effect(() => {
      const sub = this.sub();
      // The caller's follow state is part of the profile, so a sign-in or sign-out reloads it.
      this.viewerSub();
      untracked(() => this.open(sub));
    });
    effect(() => {
      const dialog = this.loginDialog()?.nativeElement;
      if (!dialog) return;
      if (this.loginPrompt() !== null && !dialog.open) {
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
      } else if (this.loginPrompt() === null && dialog.open) {
        if (typeof dialog.close === 'function') dialog.close();
        else dialog.removeAttribute('open');
      }
    });
    inject(DestroyRef).onDestroy(() => {
      this.profileRequest?.unsubscribe();
      this.followRequest?.unsubscribe();
      this.listings.stop();
      this.reviews.stop();
    });
  }

  protected loadProfile(): void {
    const sub = this.sub();
    if (!sub) return;
    this.profileRequest?.unsubscribe();
    this.profileStatus.set('loading');
    this.profileRequest = this.users.getPublicProfile(sub).subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.profileStatus.set(profile.registration === null ? 'missing' : 'ready');
      },
      error: (error: AppError) => {
        this.profile.set(null);
        this.profileStatus.set(error?.status === 404 ? 'missing' : 'error');
      },
    });
  }

  protected returnUrl(): string {
    return validateReturnUrl(this.router.url);
  }

  private openLogin(kind: 'follow' | 'favourite'): void {
    this.loginKind.set(kind);
    this.loginPrompt.set(kind);
  }

  protected toggleFollow(): void {
    const before = this.profile();
    if (!before || this.followPending()) return;
    if (!this.auth.isAuthenticated()) {
      this.openLogin('follow');
      return;
    }
    this.followPending.set(true);
    this.profile.set({
      ...before,
      followedByMe: !before.followedByMe,
      followerCount: Math.max(0, before.followerCount + (before.followedByMe ? -1 : 1)),
    });
    const change = before.followedByMe
      ? this.users.unfollow(before.sub)
      : this.users.follow(before.sub);
    this.followRequest = change
      .pipe(
        switchMap(() => this.users.getPublicProfile(before.sub).pipe(catchError(() => of(null)))),
      )
      .subscribe({
        next: (fresh) => {
          // A failed refetch keeps the optimistic values rather than undoing a change the server accepted.
          if (fresh && fresh.registration !== null) this.profile.set(fresh);
          this.followPending.set(false);
        },
        error: (error: AppError) => {
          this.profile.update((current) =>
            current
              ? {
                  ...current,
                  followedByMe: before.followedByMe,
                  followerCount: before.followerCount,
                }
              : current,
          );
          this.followPending.set(false);
          this.toasts.show({ text: errorCopy(error), kind: 'error' });
        },
      });
  }

  protected toggleFavourite(id: string, on: boolean): void {
    if (!this.auth.isAuthenticated()) {
      this.openLogin('favourite');
      return;
    }
    if (this.favouritePending.has(id)) return;
    this.favouritePending.add(id);
    this.setFavourite(id, on);
    const change: Observable<unknown> = on
      ? this.catalog.addFavourite(id)
      : this.catalog.removeFavourite(id);
    change.subscribe({
      next: () => this.favouritePending.delete(id),
      error: (error: AppError) => {
        this.favouritePending.delete(id);
        this.setFavourite(id, !on);
        this.toasts.show({ text: errorCopy(error), kind: 'error' });
      },
    });
  }

  private setFavourite(id: string, on: boolean): void {
    this.favourites.update((current) => {
      const next = new Set(current);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected goHome(): void {
    void this.router.navigateByUrl('/');
  }

  protected stars(rating: number): string {
    return '★'.repeat(rating) + '☆'.repeat(Math.max(0, 5 - rating));
  }

  private open(sub: string | null): void {
    this.followRequest?.unsubscribe();
    this.followPending.set(false);
    this.profile.set(null);
    if (!sub) return;
    this.loadProfile();
    this.listings.load();
    this.reviews.load();
  }
}
