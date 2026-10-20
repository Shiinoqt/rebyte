import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthAdapter } from '../auth/auth.adapter';
import { AuthStore } from '../auth/auth.store';
import { RealtimeStore } from '../realtime/realtime.store';
import { MessageToastListener } from '../realtime/message-toast.listener';
import { Skeleton, ToastHost, ToastService } from '../../shared/ui/feedback';
import { CountBadge } from '../../shared/ui/navigation';
import { CurrentProfile } from './current-profile';
import { MotionPreference } from './motion-preference';
import { RouteFocusService } from './route-focus.service';

const AUTH_PATHS = new Set(['/login', '/signup', '/confirm', '/forgot']);

function isAuthPath(url: string): boolean {
  return AUTH_PATHS.has(url.split(/[?#]/, 1)[0]);
}

function isThreadPath(url: string): boolean {
  return /^\/conversations\/[^/]+$/.test(url.split(/[?#]/, 1)[0]);
}

@Component({
  selector: 'app-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, Skeleton, ToastHost, CountBadge],
  styleUrl: './app-shell.css',
  host: {
    '[class.signed-in]': 'auth.isAuthenticated()',
    '[class.auth-route]': 'authRoute()',
    '[class.thread-route]': 'threadRoute()',
    '(document:click)': 'onOutsideClick($event)',
    '(document:keydown.escape)': 'onEscape()',
  },
  template: `
    <a class="skip-link" href="#main">Skip to content</a>
    <p class="demo-banner" role="note">
      <span class="demo-full">Demo project. Not a real marketplace: payments run on PayPal sandbox and nothing is sold.</span>
      <span class="demo-short">Demo: sandbox payments, nothing is sold.</span>
    </p>
    <header [hidden]="authRoute()">
      <a class="wordmark" routerLink="/" aria-label="rebyte home">re<span>byte</span></a>
      <form id="shell-search" role="search" (submit)="search($event)">
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 5 5" />
        </svg>
        <input
          #searchInput
          type="search"
          name="q"
          aria-label="Search listings"
          placeholder="Search"
          [value]="query()"
          (input)="query.set(searchInput.value)"
        />
      </form>
      <div class="header-end">
        <button
          class="theme-toggle"
          type="button"
          aria-label="Dark mode"
          [attr.aria-pressed]="darkMode()"
          [title]="darkMode() ? 'Switch to light mode' : 'Switch to dark mode'"
          (click)="toggleTheme()"
        >
          @if (darkMode()) {
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <circle cx="12" cy="12" r="4" />
              <path
                d="M12 2v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42"
              />
            </svg>
          } @else {
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <path d="M20.9 13A8 8 0 0 1 11 3.1 8 8 0 1 0 20.9 13Z" />
            </svg>
          }
        </button>
        @if (auth.status() === 'resolving') {
          <div class="session-loading">
            <app-skeleton shape="avatar" label="Resolving session" />
          </div>
        } @else if (auth.isAuthenticated()) {
          <nav class="member-nav" aria-label="Primary">
            <a
              routerLink="/conversations"
              title="Messages"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              [attr.aria-label]="
                realtime.unread().messages > 0
                  ? 'Messages, ' + realtime.unread().messages + ' unread messages'
                  : 'Messages'
              "
            >
              <svg aria-hidden="true" viewBox="0 0 24 24">
                <path d="M21 15a3 3 0 0 1-3 3H8l-5 4V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3Z" />
              </svg>
              <app-count-badge [count]="realtime.unread().messages" label="unread messages" />
            </a>
            <a
              routerLink="/notifications"
              title="Notifications"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              [attr.aria-label]="
                realtime.unread().notifications > 0
                  ? 'Notifications, ' + realtime.unread().notifications + ' unread notifications'
                  : 'Notifications'
              "
            >
              <svg aria-hidden="true" viewBox="0 0 24 24">
                <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
              </svg>
              <app-count-badge
                [count]="realtime.unread().notifications"
                label="unread notifications"
              />
            </a>
            <a
              class="sell-link"
              routerLink="/sell"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              >Sell</a
            >
          </nav>
          <div class="account" data-user-menu>
            <button
              #accountTrigger
              class="account-trigger"
              type="button"
              aria-label="Your account"
              aria-controls="account-menu"
              [attr.aria-expanded]="menuOpen()"
              (click)="menuOpen.set(!menuOpen())"
            >
              <span class="account-initial" aria-hidden="true">{{ initial() }}</span>
              <svg class="chevron" aria-hidden="true" viewBox="0 0 24 24">
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>
            @if (menuOpen()) {
              <nav id="account-menu" aria-label="Your account">
                <div class="account-heading">
                  <div class="account-name" [title]="accountLabel()">{{ accountLabel() }}</div>
                  @if (auth.identity()?.email; as email) {
                    <div class="account-email" [title]="email">{{ email }}</div>
                  }
                </div>
                <a routerLink="/me">Your account</a>
                <a routerLink="/favourites">Favourites</a>
                <a routerLink="/orders">Orders</a>
                <a routerLink="/offers">Offers</a>
                <a routerLink="/settings">Account settings</a>
                @if (auth.isAdmin()) {
                  <div class="admin-label">Admin</div>
                  <a routerLink="/admin/problems">Problems</a>
                  <a routerLink="/admin/categories">Categories</a>
                  <a routerLink="/admin/operations">Operations</a>
                }
                <button
                  type="button"
                  class="logout"
                  data-logout
                  [disabled]="loggingOut()"
                  (click)="logout()"
                >
                  {{ loggingOut() ? 'Logging out...' : 'Log out' }}
                </button>
              </nav>
            }
          </div>
        } @else {
          <nav class="visitor-nav" aria-label="Primary">
            <a routerLink="/login">Log in</a>
            <a class="signup-link" routerLink="/signup">Sign up</a>
            <a
              class="sell-link visitor-sell"
              routerLink="/login"
              [queryParams]="{ returnUrl: '/sell' }"
              >Sell</a
            >
          </nav>
        }
      </div>
    </header>
    <main id="main" tabindex="-1" [class.auth-route]="authRoute()"><router-outlet /></main>
    <app-toast-host />
    @if (auth.isAuthenticated() && !authRoute() && !threadRoute()) {
      <nav class="bottom-tabs" aria-label="Bottom navigation">
        <a
          routerLink="/"
          routerLinkActive="active"
          [routerLinkActiveOptions]="{ exact: true }"
          ariaCurrentWhenActive="page"
        >
          <span class="tab-icon">
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <path d="m3 10 9-7 9 7v11h-7v-7h-4v7H3Z" />
            </svg>
          </span>
          <span class="tab-label">Home</span>
        </a>
        <a routerLink="/conversations" routerLinkActive="active" ariaCurrentWhenActive="page">
          <span class="tab-icon">
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <path d="M21 15a3 3 0 0 1-3 3H8l-5 4V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3Z" />
            </svg>
            <app-count-badge [count]="realtime.unread().messages" label="unread messages" />
          </span>
          <span class="tab-label">Messages</span>
        </a>
        <a
          class="sell-tab"
          routerLink="/sell"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
        >
          <span class="tab-icon">
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <rect x="3" y="3" width="18" height="18" rx="5" />
              <path d="M12 8v8M8 12h8" />
            </svg>
          </span>
          <span class="tab-label">Sell</span>
        </a>
        <a routerLink="/notifications" routerLinkActive="active" ariaCurrentWhenActive="page">
          <span class="tab-icon">
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
            </svg>
            <app-count-badge
              [count]="realtime.unread().notifications"
              label="unread notifications"
            />
          </span>
          <span class="tab-label">Alerts</span>
        </a>
        <a routerLink="/me" routerLinkActive="active" ariaCurrentWhenActive="page">
          <span class="tab-icon">
            <span class="tab-avatar" aria-hidden="true">{{ initial() }}</span>
          </span>
          <span class="tab-label">Me</span>
        </a>
      </nav>
    }
  `,
})
export class AppShell {
  private readonly document = inject(DOCUMENT);
  private readonly motion = inject(MotionPreference);
  readonly darkMode = signal(this.readTheme() === 'dark');
  readonly auth = inject(AuthStore);
  readonly realtime = inject(RealtimeStore);
  private readonly adapter = inject(AuthAdapter);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly accountTrigger = viewChild<ElementRef<HTMLButtonElement>>('accountTrigger');
  readonly menuOpen = signal(false);
  readonly loggingOut = signal(false);
  readonly query = signal(this.router.parseUrl(this.router.url).queryParams['q'] ?? '');
  readonly authRoute = signal(isAuthPath(this.router.url));
  readonly threadRoute = signal(isThreadPath(this.router.url));
  private readonly profile = inject(CurrentProfile).profile;
  readonly accountLabel = computed(() => {
    const username = this.profile()?.username;
    return username ? `@${username}` : 'Your account';
  });
  readonly initial = computed(
    () =>
      Array.from(this.profile()?.username ?? this.auth.identity()?.email ?? '')[0]?.toUpperCase() ??
      '?',
  );

  constructor() {
    this.applyTheme(this.darkMode());
    inject(RouteFocusService).start();
    inject(MessageToastListener).start();
    this.reserveSearchCentre();
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe(() => {
        this.query.set(this.router.parseUrl(this.router.url).queryParams['q'] ?? '');
        this.authRoute.set(isAuthPath(this.router.url));
        this.threadRoute.set(isThreadPath(this.router.url));
        this.menuOpen.set(false);
      });
    effect(() => {
      if (!this.auth.isAuthenticated()) this.menuOpen.set(false);
    });
  }

  toggleTheme(): void {
    this.darkMode.update((dark) => !dark);
    this.applyThemeWithTransition();
    try {
      this.document.defaultView?.localStorage.setItem(
        'rebyte.theme',
        this.darkMode() ? 'dark' : 'light',
      );
    } catch {
      // Keep the active theme when browser storage is unavailable.
    }
  }

  private readTheme(): 'light' | 'dark' {
    try {
      const saved = this.document.defaultView?.localStorage.getItem('rebyte.theme');
      if (saved === 'dark' || saved === 'light') return saved;
    } catch {
      // Use the early document attribute when browser storage is unavailable.
    }
    return this.document.documentElement.dataset['theme'] === 'dark' ? 'dark' : 'light';
  }

  private applyTheme(dark: boolean): void {
    if (dark) this.document.documentElement.dataset['theme'] = 'dark';
    else delete this.document.documentElement.dataset['theme'];
  }

  private applyThemeWithTransition(): void {
    const apply = () => this.applyTheme(this.darkMode());
    if (this.motion.enabled() || typeof this.document.startViewTransition !== 'function') {
      apply();
      return;
    }
    try {
      const transition = this.document.startViewTransition(apply);
      void transition.updateCallbackDone.catch(apply);
      void transition.finished.catch(() => undefined);
    } catch {
      apply();
    }
  }

  // The search can only sit in the true centre if both side columns are as wide as the wider side group.
  private reserveSearchCentre(): void {
    const destroyRef = inject(DestroyRef);
    afterNextRender(
      () => {
        const host = this.element.nativeElement;
        const header = host.querySelector('header');
        if (!header || typeof ResizeObserver === 'undefined') return;
        const observer = new ResizeObserver(() => {
          const width = (selector: string) =>
            header.querySelector(selector)?.getBoundingClientRect().width ?? 0;
          header.style.setProperty(
            '--header-side-width',
            `${Math.max(width('.wordmark'), width('.header-end'))}px`,
          );
        });
        observer.observe(header);
        const watchEnd = () => {
          const end = header.querySelector('.header-end');
          if (end) observer.observe(end);
        };
        watchEnd();
        const mutations = new MutationObserver(watchEnd);
        mutations.observe(header, { childList: true });
        destroyRef.onDestroy(() => {
          observer.disconnect();
          mutations.disconnect();
        });
      },
      { injector: this.injector },
    );
  }

  search(event: Event): void {
    event.preventDefault();
    void this.router.navigate(['/'], {
      queryParams: { q: this.query().trim() || null },
      queryParamsHandling: 'merge',
    });
  }

  onOutsideClick(event: MouseEvent): void {
    const menu = this.element.nativeElement.querySelector('[data-user-menu]');
    if (event.target instanceof Node && !menu?.contains(event.target)) this.menuOpen.set(false);
  }

  onEscape(): void {
    if (this.menuOpen()) {
      this.menuOpen.set(false);
      this.accountTrigger()?.nativeElement.focus();
    }
  }

  async logout(): Promise<void> {
    if (this.loggingOut()) return;
    this.loggingOut.set(true);
    try {
      await this.adapter.signOut();
      this.auth.clearSession();
      this.menuOpen.set(false);
      await this.router.navigate(['/']);
    } catch {
      this.toasts.show({ text: 'Could not log out. Try again.', kind: 'error' });
    } finally {
      this.loggingOut.set(false);
    }
  }
}
