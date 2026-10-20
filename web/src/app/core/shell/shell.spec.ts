import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../app';
import { AuthAdapter, AuthIdentity } from '../auth/auth.adapter';
import { AuthStatus, AuthStore } from '../auth/auth.store';
import { RealtimeStore } from '../realtime/realtime.store';
import { MessageToastListener } from '../realtime/message-toast.listener';
import { ToastHost, ToastService } from '../../shared/ui/feedback';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { UserApi } from '../api/user-api';

@Component({ template: '<h1 tabindex="-1">Test page</h1>' })
class TestPage {}

describe('application shell', () => {
  const status = signal<AuthStatus>('guest');
  const identity = signal<AuthIdentity | null>(null);
  const auth = {
    status,
    identity,
    isAuthenticated: computed(() => status() === 'authenticated'),
    isAdmin: computed(() => identity()?.groups.includes('ADMIN') ?? false),
    clearSession: vi.fn(() => {
      identity.set(null);
      status.set('guest');
    }),
  };
  const adapter = { signOut: vi.fn(async () => undefined) };
  const users = {
    getMe: vi.fn(() =>
      of({
        id: 'member',
        username: 'alice',
        registration: '2026-01-01T00:00:00Z',
        location: null,
        bio: null,
        avatar: null,
      }),
    ),
  };

  beforeEach(() => {
    status.set('guest');
    identity.set(null);
    localStorage.removeItem('rebyte.theme');
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['theme'];
    delete document.documentElement.dataset['reducedMotion'];
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(
          [
            { path: '', component: TestPage },
            { path: 'second', component: TestPage },
            { path: 'conversations', component: TestPage },
            { path: 'conversations/:id', component: TestPage },
            { path: 'login', component: TestPage },
            { path: 'signup', component: TestPage },
            { path: 'confirm', component: TestPage },
            { path: 'forgot', component: TestPage },
          ],
          withComponentInputBinding(),
        ),
        { provide: AuthStore, useValue: auth },
        { provide: MessageToastListener, useValue: { start: vi.fn() } },
        { provide: AuthAdapter, useValue: adapter },
        { provide: UserApi, useValue: users },
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('rebyte.theme');
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['theme'];
    delete document.documentElement.dataset['reducedMotion'];
    vi.restoreAllMocks();
  });

  function signIn(groups: string[] = [], email: string | null = 'member@example.test') {
    identity.set({ sub: 'member', groups, ...(email ? { email } : {}) });
    status.set('authenticated');
  }

  async function render() {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  it('starts in light mode, toggles, and restores the saved theme after shell recreation', async () => {
    const { fixture, root } = await render();
    const toggle = root.querySelector<HTMLButtonElement>('.theme-toggle')!;
    expect(toggle.getAttribute('aria-label')).toBe('Dark mode');
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    expect(document.documentElement.dataset['theme']).toBeUndefined();

    toggle.click();
    await fixture.whenStable();
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(localStorage.getItem('rebyte.theme')).toBe('dark');
    fixture.destroy();

    const restored = await render();
    expect(restored.root.querySelector('.theme-toggle')?.getAttribute('title')).toBe(
      'Switch to light mode',
    );
    expect(restored.root.querySelector('.theme-toggle')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('keeps the toggle usable when local storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Storage is unavailable', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage is unavailable', 'SecurityError');
    });
    const { fixture, root } = await render();
    root.querySelector<HTMLButtonElement>('.theme-toggle')!.click();
    await fixture.whenStable();
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(root.querySelector('.theme-toggle')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('uses the browser view transition when motion is enabled', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(document, 'startViewTransition');
    const startViewTransition = vi.fn((callback?: ViewTransitionUpdateCallback) => {
      void callback?.();
      const done = Promise.resolve();
      return { updateCallbackDone: done, finished: done } as ViewTransition;
    });
    Object.defineProperty(document, 'startViewTransition', {
      configurable: true,
      value: startViewTransition,
    });
    try {
      const { fixture, root } = await render();
      root.querySelector<HTMLButtonElement>('.theme-toggle')!.click();
      await fixture.whenStable();
      expect(startViewTransition).toHaveBeenCalledOnce();
      expect(document.documentElement.dataset['theme']).toBe('dark');
    } finally {
      if (descriptor) Object.defineProperty(document, 'startViewTransition', descriptor);
      else delete (document as Partial<Document>).startViewTransition;
    }
  });

  it('applies the latest theme if a view transition promise rejects', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(document, 'startViewTransition');
    const startViewTransition = vi.fn(() => ({
      updateCallbackDone: Promise.reject(new Error('transition update failed')),
      finished: Promise.reject(new Error('transition failed')),
    }) as ViewTransition);
    Object.defineProperty(document, 'startViewTransition', {
      configurable: true,
      value: startViewTransition,
    });
    try {
      const { fixture, root } = await render();
      root.querySelector<HTMLButtonElement>('.theme-toggle')!.click();
      await fixture.whenStable();
      await Promise.resolve();
      expect(document.documentElement.dataset['theme']).toBe('dark');
    } finally {
      if (descriptor) Object.defineProperty(document, 'startViewTransition', descriptor);
      else delete (document as Partial<Document>).startViewTransition;
    }
  });

  it('skips view transitions when reduced motion is enabled', async () => {
    localStorage.setItem('rebyte.reducedMotion', 'true');
    const descriptor = Object.getOwnPropertyDescriptor(document, 'startViewTransition');
    const startViewTransition = vi.fn(() => {
      const done = Promise.resolve();
      return { updateCallbackDone: done, finished: done } as ViewTransition;
    });
    Object.defineProperty(document, 'startViewTransition', {
      configurable: true,
      value: startViewTransition,
    });
    try {
      const { fixture, root } = await render();
      root.querySelector<HTMLButtonElement>('.theme-toggle')!.click();
      await fixture.whenStable();
      expect(startViewTransition).not.toHaveBeenCalled();
      expect(document.documentElement.dataset['theme']).toBe('dark');
    } finally {
      if (descriptor) Object.defineProperty(document, 'startViewTransition', descriptor);
      else delete (document as Partial<Document>).startViewTransition;
    }
  });

  it('applies the theme directly when the browser has no view transition API', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(document, 'startViewTransition');
    Object.defineProperty(document, 'startViewTransition', { configurable: true, value: undefined });
    try {
      const { fixture, root } = await render();
      root.querySelector<HTMLButtonElement>('.theme-toggle')!.click();
      await fixture.whenStable();
      expect(document.documentElement.dataset['theme']).toBe('dark');
    } finally {
      if (descriptor) Object.defineProperty(document, 'startViewTransition', descriptor);
      else delete (document as Partial<Document>).startViewTransition;
    }
  });

  it('renders visitor navigation and the wordmark', async () => {
    const { root } = await render();
    expect(root.querySelector('.wordmark')?.textContent).toBe('rebyte');
    expect(root.querySelector('a[href="/login"]')?.textContent?.trim()).toBe('Log in');
    expect(root.querySelector('a[href="/signup"]')?.textContent?.trim()).toBe('Sign up');
    expect(root.querySelector('.visitor-sell')?.getAttribute('href')).toBe(
      '/login?returnUrl=%2Fsell',
    );
    expect(root.querySelector('input[type="search"]')?.getAttribute('aria-label')).toBe(
      'Search listings',
    );
    expect(root.querySelector('.bottom-tabs')).toBeNull();
    expect(root.querySelector('.account-trigger')).toBeNull();
  });

  it('shows the resolving skeleton without visitor or member navigation', async () => {
    status.set('resolving');
    const { root } = await render();
    expect(root.querySelector('app-skeleton[aria-busy="true"]')).not.toBeNull();
    expect(root.querySelector('.visitor-nav')).toBeNull();
    expect(root.querySelector('.member-nav')).toBeNull();
    expect(root.querySelector('.bottom-tabs')).toBeNull();
  });

  it('hides bottom navigation in a conversation and restores it in the inbox', async () => {
    signIn();
    const { fixture, root } = await render();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/conversations');
    await fixture.whenStable();
    expect(root.querySelector('.bottom-tabs')).not.toBeNull();
    await router.navigateByUrl('/conversations/thread-1?from=inbox#latest');
    await fixture.whenStable();
    expect(root.querySelector('.bottom-tabs')).toBeNull();
    expect(root.querySelector('app-shell')?.classList.contains('thread-route')).toBe(true);
    await router.navigateByUrl('/conversations');
    await fixture.whenStable();
    expect(root.querySelector('.bottom-tabs')).not.toBeNull();
    expect(root.querySelector('app-shell')?.classList.contains('thread-route')).toBe(false);
  });

  it('renders signed-in desktop links and the account dropdown', async () => {
    signIn();
    const { fixture, root } = await render();
    const nav = root.querySelector('.member-nav')!;
    expect(nav.querySelector('a[href="/conversations"]')?.getAttribute('aria-label')).toBe('Messages');
    expect(nav.querySelector('a[href="/notifications"]')?.getAttribute('aria-label')).toBe('Notifications');
    expect(nav.querySelector('a[href="/sell"]')?.textContent?.trim()).toBe('Sell');
    const trigger = root.querySelector<HTMLButtonElement>('.account-trigger')!;
    trigger.click();
    await fixture.whenStable();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const menu = root.querySelector('#account-menu')!;
    expect(
      [...menu.querySelectorAll('a, button')].map((item) => item.textContent?.trim()),
    ).toEqual(['Your account', 'Favourites', 'Orders', 'Offers', 'Account settings', 'Log out']);
    expect(menu.querySelector('.account-email')?.getAttribute('title')).toBe('member@example.test');
    for (const path of ['/favourites', '/orders', '/offers']) {
      expect(menu.querySelector(`a[href="${path}"]`)).not.toBeNull();
    }
    expect(menu.textContent).not.toContain('Admin problems');
  });

  it('shows all three ADMIN destinations only to admins', async () => {
    signIn(['ADMIN']);
    const { fixture, root } = await render();
    root.querySelector<HTMLButtonElement>('.account-trigger')!.click();
    await fixture.whenStable();
    for (const path of ['/admin/problems', '/admin/categories', '/admin/operations']) {
      expect(root.querySelector(`#account-menu a[href="${path}"]`)).not.toBeNull();
    }
  });

  it('has five phone tabs in the binding order with Me linking to /me', async () => {
    signIn();
    const { root } = await render();
    const tabs = [...root.querySelectorAll<HTMLAnchorElement>('.bottom-tabs > a')];
    expect(tabs.map((tab) => tab.getAttribute('href'))).toEqual([
      '/',
      '/conversations',
      '/sell',
      '/notifications',
      '/me',
    ]);
    expect(tabs.map((tab) => tab.querySelector('.tab-label')?.textContent)).toEqual([
      'Home',
      'Messages',
      'Sell',
      'Alerts',
      'Me',
    ]);
    for (const tab of tabs) {
      expect(tab.firstElementChild?.classList.contains('tab-icon')).toBe(true);
    }
    expect(tabs[2].querySelector('.tab-icon svg rect')).not.toBeNull();
    expect(root.querySelector('app-shell')?.classList.contains('signed-in')).toBe(true);
  });

  it('puts the skip link before every other focusable element and has all landmarks', async () => {
    signIn();
    const { root } = await render();
    const first = root.querySelector('a, button, input, [tabindex]');
    expect(first?.classList.contains('skip-link')).toBe(true);
    expect(first?.getAttribute('href')).toBe('#main');
    for (const selector of ['header', 'nav[aria-label="Primary"]', 'main#main[tabindex="-1"]']) {
      expect(root.querySelector(selector)).not.toBeNull();
    }
    // The header wordmark is the only brand mark; a footer would only repeat it.
    expect(root.querySelector('footer')).toBeNull();
  });

  it('mounts one ToastHost consuming the app-wide ToastService', async () => {
    const { fixture, root } = await render();
    TestBed.inject(ToastService).show({ text: 'Saved', kind: 'success' });
    await fixture.whenStable();
    expect(root.querySelectorAll('app-toast-host')).toHaveLength(1);
    expect(fixture.debugElement.query(By.directive(ToastHost)).componentInstance.toasts).toBe(
      TestBed.inject(ToastService),
    );
    expect(root.querySelector('app-toast-host [role="status"]')?.textContent).toContain('Saved');
  });

  it('hides zero badges and renders live full counts with a 99+ visual cap', async () => {
    signIn();
    const { fixture, root } = await render();
    expect(root.querySelector('app-count-badge .count')).toBeNull();
    TestBed.inject(RealtimeStore).receive('unread', { messages: 123, notifications: 4 });
    await fixture.whenStable();
    expect(
      root.querySelector('.member-nav a[href="/conversations"]')?.getAttribute('aria-label'),
    ).toBe('Messages, 123 unread messages');
    expect(
      root.querySelector('.member-nav a[href="/notifications"]')?.getAttribute('aria-label'),
    ).toBe('Notifications, 4 unread notifications');
    expect(root.querySelector('.member-nav a[href="/conversations"] .count')?.textContent).toBe(
      '99+',
    );
    expect(
      root.querySelector('.member-nav a[href="/conversations"] .count')?.getAttribute('aria-label'),
    ).toBe('123 unread messages');
    expect(
      root
        .querySelector('.bottom-tabs a[href="/notifications"] .count')
        ?.getAttribute('aria-label'),
    ).toBe('4 unread notifications');
    TestBed.inject(RealtimeStore).reset();
    await fixture.whenStable();
    expect(root.querySelector('app-count-badge .count')).toBeNull();
  });

  it('omits a missing email and takes the initial from the username', async () => {
    signIn([], null);
    const { fixture, root } = await render();
    expect(root.querySelector('.account-initial')?.textContent).toBe('A');
    root.querySelector<HTMLButtonElement>('.account-trigger')!.click();
    await fixture.whenStable();
    expect(root.querySelector('.account-email')).toBeNull();
  });

  it('closes the dropdown on Escape and outside click, returning Escape focus', async () => {
    signIn();
    const { fixture, root } = await render();
    const trigger = root.querySelector<HTMLButtonElement>('.account-trigger')!;
    trigger.click();
    await fixture.whenStable();
    root.querySelector<HTMLElement>('#account-menu a')!.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(root.querySelector('#account-menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    trigger.click();
    await fixture.whenStable();
    root.querySelector('main')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(root.querySelector('#account-menu')).toBeNull();
  });

  it('submits search to / while preserving current filter query parameters', async () => {
    const { fixture, root } = await render();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/?type=HARDWARE&min=10&q=old');
    await fixture.whenStable();
    const input = root.querySelector<HTMLInputElement>('input[type="search"]')!;
    expect(input.value).toBe('old');
    input.value = 'new search';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    root
      .querySelector('form[role="search"]')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(router.parseUrl(router.url).queryParams).toEqual({
      type: 'HARDWARE',
      min: '10',
      q: 'new search',
    });
  });

  it('renders the search field directly in the header with no toggle', async () => {
    const { root } = await render();
    expect(root.querySelector('header form[role="search"] input[type="search"]')).not.toBeNull();
    expect(root.querySelector('.search-toggle')).toBeNull();
  });

  it('uses a full-width auth shell on guest auth routes and restores the marketplace shell on return', async () => {
    const { fixture, root } = await render();
    const router = TestBed.inject(Router);
    const header = root.querySelector('header')!;
    const main = root.querySelector('main#main')!;

    for (const route of ['/login', '/signup', '/confirm', '/forgot']) {
      await router.navigateByUrl(route);
      await fixture.whenStable();
      expect(header.hasAttribute('hidden')).toBe(true);
      expect(main.classList.contains('auth-route')).toBe(true);
      expect(root.querySelector('.bottom-tabs')).toBeNull();
      expect(root.querySelector('.skip-link')).not.toBeNull();
      expect(root.querySelector('app-toast-host')).not.toBeNull();
    }

    await router.navigateByUrl('/');
    await fixture.whenStable();
    expect(header.hasAttribute('hidden')).toBe(false);
    expect(main.classList.contains('auth-route')).toBe(false);
    expect(header.querySelector('.wordmark')?.textContent).toBe('rebyte');
  });

  it('shows the demo banner after the skip link on every route, including auth routes', async () => {
    const { fixture, root } = await render();
    const router = TestBed.inject(Router);
    const full = 'Demo project. Not a real marketplace: payments run on PayPal sandbox and nothing is sold.';

    for (const route of ['/', '/login']) {
      await router.navigateByUrl(route);
      await fixture.whenStable();
      const banners = root.querySelectorAll<HTMLElement>('.demo-banner');
      expect(banners).toHaveLength(1);
      expect(banners[0].hasAttribute('hidden')).toBe(false);
      expect(banners[0].closest('header')).toBeNull();
      expect(banners[0].querySelector('.demo-full')?.textContent?.trim()).toBe(full);
      expect(banners[0].querySelector('.demo-short')?.textContent?.trim()).toBe(
        'Demo: sandbox payments, nothing is sold.',
      );
    }
    expect(root.querySelector('.skip-link')?.nextElementSibling).toBe(root.querySelector('.demo-banner'));
  });

  it('leaves initial page-load focus alone and focuses the unique h1 after later navigation', async () => {
    const { fixture, root } = await render();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    await fixture.whenStable();
    expect(root.querySelectorAll('main h1')).toHaveLength(1);
    expect(document.activeElement).not.toBe(root.querySelector('main h1'));
    await router.navigateByUrl('/second');
    await fixture.whenStable();
    expect(document.activeElement).toBe(root.querySelector('main h1'));
  });

  it('keeps interactive focus when search navigation changes only query parameters or fragment', async () => {
    const { fixture, root } = await render();
    const router = TestBed.inject(Router);
    const search = root.querySelector<HTMLInputElement>('input[type="search"]')!;
    search.focus();

    await router.navigateByUrl('/?type=HARDWARE');
    await fixture.whenStable();
    expect(document.activeElement).toBe(search);

    await router.navigateByUrl('/?type=SOFTWARE#filters');
    await fixture.whenStable();
    expect(document.activeElement).toBe(search);
  });

  it('does not focus a route during unresolved authentication or on resolution alone', async () => {
    status.set('resolving');
    const { fixture, root } = await render();
    const search = root.querySelector<HTMLInputElement>('input')!;
    search.focus();
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    expect(document.activeElement).toBe(search);
    status.set('guest');
    await fixture.whenStable();
    expect(document.activeElement).toBe(search);
  });

  it('logs out through the existing adapter and clears the session', async () => {
    signIn();
    const { fixture, root } = await render();
    root.querySelector<HTMLButtonElement>('.account-trigger')!.click();
    await fixture.whenStable();
    root.querySelector<HTMLButtonElement>('[data-logout]')!.click();
    await fixture.whenStable();
    expect(adapter.signOut).toHaveBeenCalledOnce();
    expect(auth.clearSession).toHaveBeenCalledOnce();
    expect(root.querySelector('.visitor-nav')).not.toBeNull();
  });
  it('labels the account menu with the marketplace username, never the Cognito subject', async () => {
    signIn();
    const { fixture, root } = await render();
    root.querySelector<HTMLButtonElement>('.account-trigger')!.click();
    await fixture.whenStable();
    expect(root.querySelector('.account-name')?.textContent?.trim()).toBe('@alice');
    expect(root.querySelector('.account-initial')?.textContent?.trim()).toBe('A');
    expect(users.getMe).toHaveBeenCalledTimes(1);
  });
});
