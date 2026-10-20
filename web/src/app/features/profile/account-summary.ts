import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { catchError, forkJoin, map, of, Subscription } from 'rxjs';
import { PublicProfileResponse } from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { UserApi } from '../../core/api/user-api';
import { AuthStore } from '../../core/auth/auth.store';
import { CurrentProfile } from '../../core/shell/current-profile';

export type SummaryStatus = 'loading' | 'ready' | 'error';

const FRESH_FOR_MS = 10_000;

export const GENERATED_HANDLE = /^user_[0-9a-f]{8}$/;
const memberSince = new Intl.DateTimeFormat('en-GB', {
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
});

// Shared by every account page so moving between them keeps the sidebar summary on screen.
@Injectable({ providedIn: 'root' })
export class AccountSummary {
  private readonly auth = inject(AuthStore);
  private readonly users = inject(UserApi);
  private readonly catalog = inject(CatalogApi);
  private readonly current = inject(CurrentProfile);
  private readonly attempt = signal(0);
  private request: Subscription | null = null;
  private loadedAt = 0;

  readonly status = signal<SummaryStatus>('loading');
  readonly aggregates = signal<PublicProfileResponse | null>(null);
  readonly favouriteCount = signal<number | null>(null);
  // CurrentProfile follows edits made in settings; the public profile covers a failed owner read.
  readonly owner = computed(() => this.current.profile() ?? this.aggregates());
  readonly username = computed(() => this.owner()?.username ?? '');
  readonly generatedHandle = computed(() => GENERATED_HANDLE.test(this.username()));
  readonly meta = computed(() => {
    const profile = this.owner();
    if (!profile) return '';
    const registration = profile.registration ? new Date(profile.registration) : null;
    return [
      profile.location,
      registration && !Number.isNaN(registration.getTime())
        ? `Member since ${memberSince.format(registration)}`
        : null,
    ]
      .filter(Boolean)
      .join(' · ');
  });
  readonly stats = computed(() => {
    const profile = this.aggregates();
    if (!profile) return '';
    const rating =
      profile.ratingAvg === null || profile.ratingCount === 0
        ? 'No reviews yet'
        : `${profile.ratingAvg.toFixed(1)} stars (${profile.ratingCount} ${
            profile.ratingCount === 1 ? 'review' : 'reviews'
          })`;
    const followers = `${profile.followerCount} ${
      profile.followerCount === 1 ? 'follower' : 'followers'
    }`;
    return `${rating} · ${profile.completedSales} sold · ${followers}`;
  });

  constructor() {
    effect(() => {
      const sub = this.auth.identity()?.sub;
      this.attempt();
      untracked(() => {
        this.request?.unsubscribe();
        this.request = null;
        if (!sub) {
          this.aggregates.set(null);
          this.favouriteCount.set(null);
          this.status.set('loading');
          return;
        }
        if (this.status() !== 'ready') this.status.set('loading');
        this.request = forkJoin({
          profile: this.users.getPublicProfile(sub),
          favourites: this.catalog.favourites({ page: 0, size: 1 }).pipe(
            map((page) => page.totalElements),
            catchError(() => of(null)),
          ),
        }).subscribe({
          next: ({ profile, favourites }) => {
            this.aggregates.set(profile);
            this.favouriteCount.set(favourites);
            this.loadedAt = Date.now();
            this.status.set('ready');
          },
          error: () => this.status.set('error'),
        });
      });
    });
  }

  // After a change made here (profile, avatar, developer) the summary is reloaded at once;
  // a failed load waits for the explicit retry.
  refresh(): void {
    if (this.status() !== 'ready') return;
    this.attempt.update((count) => count + 1);
  }

  // Counts also change elsewhere (favourites, sales), so arriving on an account page reloads a
  // summary that is no longer fresh, without repeating a load that has only just finished.
  refreshIfStale(): void {
    if (Date.now() - this.loadedAt < FRESH_FOR_MS) return;
    this.refresh();
  }

  reload(): void {
    this.current.reload();
    this.status.set('loading');
    this.attempt.update((count) => count + 1);
  }
}
