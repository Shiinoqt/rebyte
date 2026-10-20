import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../testing/test-config';
import { App } from './app';
import { routes } from './app.routes';
import { AuthAdapter, AuthEvent, AuthIdentity } from './core/auth/auth.adapter';
import { AuthStore } from './core/auth/auth.store';
import { RealtimeStore } from './core/realtime/realtime.store';
import { AppShell } from './core/shell/app-shell';
import { RouteStubPage } from './core/shell/route-stub.page';
import { CurrentProfile } from './core/shell/current-profile';
import { HomeFavourites } from './features/home-search/home-favourites.service';
import { MessagingStore } from './features/messages/messaging.store';
import { ToastService } from './shared/ui/feedback';
import { Avatar, ListingCard } from './shared/ui/content';
import { ModalDialog } from './shared/ui/dialog';

const routeMatrix = [
  ['/', 'app-home-page', false],
  ['/login', 'app-auth-page', false], ['/signup', 'app-auth-page', false],
  ['/confirm', 'app-auth-page', false], ['/forgot', 'app-auth-page', false],
  ['/listings/item', 'app-listing-detail-page', false], ['/u/seller', 'app-seller-profile', false],
  ['/sell', 'app-sell-edit-page', true], ['/listings/item/edit', 'app-sell-edit-page', true],
  ['/listings/item/releases', 'app-release-page', true],
  ['/checkout/return?token=T&PayerID=P', 'app-payment-return-page', true],
  ['/checkout/cancel?token=T', 'app-payment-return-page', true],
  ['/checkout/item', 'app-checkout-page', true],
  ['/favourites', 'app-favourites-page', true], ['/orders', 'app-orders-page', true],
  ['/orders/order', 'app-order-detail-page', true], ['/offers', 'app-offers-page', true],
  ['/conversations', 'app-messages-page', true], ['/conversations/c1', 'app-messages-page', true],
  ['/notifications', 'app-notifications-page', true], ['/me', 'app-me-page', true],
  ['/settings', 'app-settings-page', true],
  ['/admin/problems', 'app-admin-problems-page', true],
  ['/admin/categories', 'app-admin-categories-page', true],
  ['/admin/operations', 'app-admin-operations-page', true],
  ['/missing/deep/link', 'app-not-found', false],
] as const;

describe('assembled SPA with real routes and session stores', () => {
  let fixture: ComponentFixture<App>;
  let router: Router;
  let http: HttpTestingController;
  let auth: AuthStore;
  let currentIdentity: AuthIdentity | null;
  let event: (event: AuthEvent) => void;
  const profile: { id: string; username: string; registration: string; location: null; bio: null; avatar: null } = { id: 'alice', username: 'alice', registration: '2026-01-01T00:00:00Z', location: null, bio: null, avatar: null };

  beforeEach(async () => {
    currentIdentity = null;
    TestBed.configureTestingModule({ imports: [App], providers: [
      provideTestAppConfig(), provideRouter(routes, withComponentInputBinding()),
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthAdapter, useValue: {
        configure: vi.fn(), fetchSession: async () => currentIdentity,
        onAuthEvent: (listener: typeof event) => { event = listener; return () => undefined; },
        signOut: async () => { currentIdentity = null; },
      } },
    ] });
    auth = TestBed.inject(AuthStore); await auth.init();
    router = TestBed.inject(Router); http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(App);
    fixture.detectChanges();
  });

  afterEach(() => { TestBed.resetTestingModule(); vi.restoreAllMocks(); });

  async function settle() {
    for (let pass = 0; pass < 12; ++pass) {
      await Promise.resolve(); TestBed.tick();
      for (const request of http.match(() => true)) {
        if (request.cancelled) continue;
        const url = new URL(request.request.url);
        if (url.pathname.endsWith('/users/me')) request.flush({ ...profile, id: currentIdentity?.sub ?? 'alice' });
        else if (url.pathname.endsWith('/favourites')) request.flush({ favourites: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });
        else if (url.pathname.endsWith('/categories')) request.flush([]);
        else if (url.pathname.endsWith('/favourites/item')) request.flush({ listingId: 'item', sellerSub: 'seller', createdAt: '2026-10-13T00:00:00Z' });
        else request.flush({ code: 'NOT_FOUND', detail: 'Fixture unavailable' }, { status: 404, statusText: 'Not Found' });
      }
      fixture.detectChanges();
    }
  }

  async function signIn(sub = 'alice', groups: string[] = []) {
    currentIdentity = { sub, groups }; event('signedIn'); await settle();
  }

  async function go(url: string) { await router.navigateByUrl(url); await settle(); }
  const root = () => fixture.nativeElement as HTMLElement;

  it.each(routeMatrix)('resolves %s to its real lazy page %s', async (url, selector, signedIn) => {
    if (signedIn) await signIn('alice', ['ADMIN']);
    await go(url);
    expect(router.url).toBe(url);
    expect(root().querySelector(selector)).not.toBeNull();
    expect(fixture.debugElement.query(By.directive(RouteStubPage))).toBeNull();
  });

  it('preserves a protected deep link through login and rejects a non-admin admin destination', async () => {
    await go('/orders/order?view=receipt#total');
    expect(router.parseUrl(router.url).queryParams['returnUrl']).toBe('/orders/order?view=receipt#total');
    expect(router.url).toMatch(/^\/login\?/);
    await signIn(); await go('/orders/order?view=receipt#total');
    expect(root().querySelector('app-order-detail-page')).not.toBeNull();
    await go('/admin/operations'); expect(router.url).toBe('/');
    await signIn('alice', ['ADMIN']); await go('/admin/operations');
    expect(root().querySelector('app-admin-operations-page')).not.toBeNull();
  });

  it('keeps one messaging cache across navigation and clears it with unread, favourites and queued toasts on logout', async () => {
    await signIn(); await go('/conversations');
    const messaging = TestBed.inject(MessagingStore);
    expect(fixture.debugElement.query(By.css('app-messages-page')).injector.get(MessagingStore)).toBe(messaging);
    messaging.setDraft('private draft');
    const realtime = TestBed.inject(RealtimeStore);
    realtime.receive('unread', { notifications: 3, messages: 2 });
    await go('/');
    expect(root().querySelector('.member-nav')?.textContent).toContain('2');
    const favourites = TestBed.inject(HomeFavourites);
    favourites.toggle('item', true).subscribe(); await settle();
    const toasts = TestBed.inject(ToastService);
    toasts.show({ text: 'Private account feedback', kind: 'info' });
    auth.clearSession(); realtime.reset(); await settle();
    expect(messaging.draft()).toBe(''); expect(messaging.conversations()).toEqual([]);
    expect(favourites.ids().size).toBe(0); expect(realtime.unread()).toEqual({ notifications: 0, messages: 0 });
    expect(toasts.current()).toBeNull(); expect(root().querySelector('.member-nav')).toBeNull();
    await go('/conversations/c1'); expect(router.url).toMatch(/^\/login\?/);
  });

  it('clears the previous header profile while the next account profile is loading', async () => {
    await signIn();
    const profiles = TestBed.inject(CurrentProfile);
    expect(profiles.profile()?.username).toBe('alice');
    currentIdentity = { sub: 'bob', groups: [] }; event('signedIn');
    await Promise.resolve(); TestBed.tick();
    expect(profiles.profile()).toBeNull();
    await settle(); expect(auth.identity()?.sub).toBe('bob');
  });

  it('exposes the skip target, focuses changed routes and restores account-menu focus on Escape', async () => {
    await signIn(); await go('/'); await go('/orders');
    const heading = root().querySelector('main h1') as HTMLElement;
    expect(heading.getAttribute('tabindex')).toBe('-1');
    expect(root().querySelector('.skip-link')?.getAttribute('href')).toBe('#main');
    expect(root().querySelector('#main')?.getAttribute('tabindex')).toBe('-1');
    const shell = fixture.debugElement.query(By.directive(AppShell)).componentInstance as AppShell;
    const trigger = root().querySelector('.account-trigger') as HTMLButtonElement;
    trigger.click(); await settle();
    expect(root().querySelector('#account-menu')).not.toBeNull();
    const focus = vi.spyOn(trigger, 'focus'); shell.onEscape(); await settle();
    expect(root().querySelector('#account-menu')).toBeNull(); expect(focus).toHaveBeenCalledTimes(1);
  });

  it('keeps a very long account handle complete in its title and clips it only inside the menu heading', async () => {
    const handle = 'h'.repeat(300);
    profile.username = handle; await signIn(); await go('/');
    const shell = fixture.debugElement.query(By.directive(AppShell)).componentInstance as AppShell;
    shell.menuOpen.set(true); await settle();
    expect(root().querySelector('.account-name')?.getAttribute('title')).toBe(`@${handle}`);
    profile.username = 'alice';
  });

  it('declares reduced-motion handling and no document-level overflow clipping in loaded stylesheets', async () => {
    await signIn(); await go('/');
    const css = [...document.querySelectorAll('style')].map((style) => style.textContent ?? '').join(' ');
    expect(css).toMatch(/prefers-reduced-motion:\s*reduce\)\s*\{[^}]*#account-menu[^}]*animation:\s*none/);
    expect(css).not.toMatch(/overflow-x\s*:\s*hidden/);
    expect(css).not.toMatch(/(?<![\w&-])#[\da-f]{3,8}\b(?=\s*[;,)])/i);
  });

  it('keeps desktop and phone navigation targets consistent', async () => {
    await signIn('alice', ['ADMIN']); await go('/');
    const shell = fixture.debugElement.query(By.directive(AppShell)).componentInstance as AppShell;
    shell.menuOpen.set(true); await settle();
    for (const url of ['/conversations', '/notifications', '/sell']) {
      expect(root().querySelector(`.member-nav a[href="${url}"]`)).not.toBeNull();
      expect(root().querySelector(`.bottom-tabs a[href="${url}"]`)).not.toBeNull();
    }
    for (const url of ['/me', '/favourites', '/orders', '/offers', '/settings', '/admin/problems', '/admin/categories', '/admin/operations']) {
      expect(root().querySelector(`#account-menu a[href="${url}"]`)).not.toBeNull();
    }
  });
});

@Component({
  selector: 'app-dialog-host', standalone: true, imports: [ModalDialog],
  template: `<button type="button" id="opener" (click)="open.set(true)">Open</button>
    @if (open()) { <app-modal-dialog heading="Confirm" size="confirm" (closed)="open.set(false)"><p>Body</p></app-modal-dialog> }`,
})
class DialogHost { readonly open = signal(false); }

describe('shared content layout contracts', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('returns focus to the opener when a modal dialog closes', async () => {
    const fixture = TestBed.createComponent(DialogHost); fixture.detectChanges();
    const opener = (fixture.nativeElement as HTMLElement).querySelector('#opener') as HTMLButtonElement;
    document.body.append(fixture.nativeElement); opener.focus(); opener.click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-modal-dialog')).not.toBeNull();
    fixture.componentInstance.open.set(false); fixture.detectChanges();
    expect(document.activeElement).toBe(opener); fixture.nativeElement.remove();
  });

  afterEach(() => TestBed.resetTestingModule());
  it('reserves image dimensions and retains complete long listing text for accessible names', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(ListingCard);
    const title = 'A'.repeat(350);
    fixture.componentRef.setInput('listing', { id: 'long', title, price: '123456789.12', coverUrl: '/fixture.jpg', sellerUsername: 'handle'.repeat(50) });
    fixture.detectChanges(); const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.title')?.getAttribute('title')).toBe(title);
    expect(root.querySelector('button')?.getAttribute('aria-label')).toContain(title);
    expect(root.querySelector('img')?.getAttribute('width')).toBe('500');
    expect(root.querySelector('img')?.getAttribute('height')).toBe('400');
  });
  it('reserves a square avatar footprint', () => {
    const fixture = TestBed.createComponent(Avatar);
    fixture.componentRef.setInput('username', 'sam'); fixture.componentRef.setInput('url', '/avatar.jpg'); fixture.detectChanges();
    const image = (fixture.nativeElement as HTMLElement).querySelector('img');
    expect(image?.getAttribute('width')).toBe('40'); expect(image?.getAttribute('height')).toBe('40');
  });
});
