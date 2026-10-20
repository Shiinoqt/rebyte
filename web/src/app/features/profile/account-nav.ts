import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { AuthAdapter } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { Avatar } from '../../shared/ui/content';
import { ErrorState, Skeleton, ToastService } from '../../shared/ui/feedback';
import { AccountSummary } from './account-summary';

// Account pages switch between the phone list and the desktop sidebar at the two-column collapse.
export const NARROW_QUERY = '(max-width: 900px)';

export function narrowViewport() {
  return toSignal(
    inject(BreakpointObserver)
      .observe(NARROW_QUERY)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
}

export type AccountSection =
  'favourites' | 'orders' | 'offers' | 'library' | 'listings' | 'reviews' | 'settings';

interface AccountRow {
  key: AccountSection;
  label: string;
  link: string;
  tab?: string;
  icon: readonly string[];
  countsFavourites?: boolean;
}

// Path data from Lucide (ISC), drawn on the shared 24px stroke grid.
const ICONS = {
  heart: [
    'M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z',
  ],
  package: [
    'M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z',
    'm3.3 7 8.7 5 8.7-5',
    'M12 22V12',
  ],
  tag: [
    'M12.59 2.59A2 2 0 0 0 11.17 2H4a2 2 0 0 0-2 2v7.17a2 2 0 0 0 .59 1.42l8.7 8.7a2.43 2.43 0 0 0 3.42 0l6.58-6.58a2.43 2.43 0 0 0 0-3.42Z',
    'M7.5 7.5h.01',
  ],
  download: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'm7 10 5 5 5-5', 'M12 15V3'],
  grid: ['M3 3h7v7H3Z', 'M14 3h7v7h-7Z', 'M14 14h7v7h-7Z', 'M3 14h7v7H3Z'],
  star: [
    'M11.53 2.3a.53.53 0 0 1 .95 0l2.31 4.68a2.12 2.12 0 0 0 1.6 1.16l5.16.76a.53.53 0 0 1 .3.9l-3.74 3.64a2.12 2.12 0 0 0-.61 1.88l.88 5.14a.53.53 0 0 1-.77.56l-4.62-2.43a2.12 2.12 0 0 0-1.97 0L6.4 21.01a.53.53 0 0 1-.77-.56l.88-5.14a2.12 2.12 0 0 0-.61-1.88L2.16 9.8a.53.53 0 0 1 .3-.91l5.16-.75a2.12 2.12 0 0 0 1.6-1.16Z',
  ],
  settings: [
    'M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z',
    'M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  ],
} as const;

const ACCOUNT_GROUPS: readonly { title: string; rows: readonly AccountRow[] }[] = [
  {
    title: 'Buying',
    rows: [
      {
        key: 'favourites',
        label: 'Favourites',
        link: '/favourites',
        icon: ICONS.heart,
        countsFavourites: true,
      },
      { key: 'orders', label: 'Orders', link: '/orders', icon: ICONS.package },
      { key: 'offers', label: 'Offers', link: '/offers', icon: ICONS.tag },
      { key: 'library', label: 'Library', link: '/me', tab: 'library', icon: ICONS.download },
    ],
  },
  {
    title: 'Selling',
    rows: [
      { key: 'listings', label: 'Your listings', link: '/me', tab: 'listings', icon: ICONS.grid },
      { key: 'reviews', label: 'Reviews', link: '/me', tab: 'reviews', icon: ICONS.star },
    ],
  },
  {
    title: 'Account',
    rows: [
      { key: 'settings', label: 'Settings and profile', link: '/settings', icon: ICONS.settings },
    ],
  },
];

@Component({
  selector: 'app-account-nav',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Avatar, ErrorState, Skeleton],
  styleUrl: './account-nav.css',
  host: { '[class.sidebar]': "variant() === 'sidebar'" },
  template: `
    @if (summary.aggregates(); as profile) {
      <a class="summary" data-account-summary [routerLink]="['/u', profile.sub]">
        <app-avatar
          [username]="summary.username()"
          [url]="summary.owner()?.avatar ?? null"
          [own]="true"
        />
        <span class="identity">
          <span class="name">@{{ summary.username() }}</span>
          <span class="profile-link">View public profile</span>
          @if (summary.meta()) {
            <span class="meta">{{ summary.meta() }}</span>
          }
          <span class="stats">{{ summary.stats() }}</span>
        </span>
        <svg class="chevron" aria-hidden="true" viewBox="0 0 24 24">
          <path d="m9 18 6-6-6-6" />
        </svg>
      </a>
    } @else if (summary.status() === 'error') {
      <app-error-state (retry)="summary.reload()" />
    } @else {
      <div class="summary">
        <app-skeleton label="Loading your profile" shape="avatar" />
      </div>
    }
    <nav aria-label="Your account">
      @for (group of groups; track group.title) {
        <h2>{{ group.title }}</h2>
        <ul>
          @for (row of group.rows; track row.key) {
            <li>
              <a
                class="row"
                [routerLink]="row.link"
                [queryParams]="row.tab ? { tab: row.tab } : null"
                [attr.aria-current]="row.key === active() ? 'page' : null"
              >
                <svg class="row-icon" aria-hidden="true" viewBox="0 0 24 24">
                  @for (d of row.icon; track $index) {
                    <path [attr.d]="d" />
                  }
                </svg>
                <span class="row-label">{{ row.label }}</span>
                @if (row.countsFavourites && summary.favouriteCount()) {
                  <span class="row-count">{{ summary.favouriteCount() }}</span>
                }
                <svg class="chevron" aria-hidden="true" viewBox="0 0 24 24">
                  <path d="m9 18 6-6-6-6" />
                </svg>
              </a>
            </li>
          }
        </ul>
      }
    </nav>
    @if (variant() === 'list') {
      <button type="button" class="logout" [disabled]="loggingOut()" (click)="logout()">
        {{ loggingOut() ? 'Logging out...' : 'Log out' }}
      </button>
    }
  `,
})
export class AccountNav {
  private readonly auth = inject(AuthStore);
  private readonly adapter = inject(AuthAdapter);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  readonly summary = inject(AccountSummary);
  readonly variant = input<'list' | 'sidebar'>('list');
  readonly active = input<AccountSection | null>(null);
  readonly groups = ACCOUNT_GROUPS;
  readonly loggingOut = signal(false);

  constructor() {
    this.summary.refreshIfStale();
  }

  // clearSession runs the registered cleanup hooks, which is what stops the realtime connection.
  async logout(): Promise<void> {
    if (this.loggingOut()) return;
    this.loggingOut.set(true);
    try {
      await this.adapter.signOut();
      this.auth.clearSession();
      await this.router.navigate(['/']);
    } catch {
      this.toasts.show({ text: 'Could not log out. Try again.', kind: 'error' });
    } finally {
      this.loggingOut.set(false);
    }
  }
}
