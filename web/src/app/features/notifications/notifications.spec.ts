import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationView } from '../../core/api/api-models';
import { MessagingApi } from '../../core/api/messaging-api';
import { AuthStore } from '../../core/auth/auth.store';
import { RealtimeStore } from '../../core/realtime/realtime.store';
import { NOTIFICATIONS_ROUTES } from './notifications.routes';
import { NotificationsPage } from './notifications.page';

const now = '2026-10-09T12:00:00Z';
function notice(type: NotificationView['type'], target: Record<string, string>, id: string = type, read = false): NotificationView {
  return { id, type, target, read, readAt: read ? now : null, createdAt: now, snapshot: { listingTitle: 'ThinkPad X1', actorUsername: 'alex', amount: '400' } };
}
const targets: [NotificationView['type'], Record<string, string>, string][] = [
  ['MESSAGE', { conversationId: 'c1' }, '/conversations/c1'],
  ['FAVOURITE', { listingId: 'l1' }, '/listings/l1'],
  ['PRICE_DROP', { listingId: 'l1' }, '/listings/l1'],
  ['RELEASE', { listingId: 'l1' }, '/listings/l1'],
  ['REVIEW', { reviewId: 'r1' }, '/me?tab=reviews'],
  ['FOLLOW', { followerSub: 's1' }, '/u/s1'],
  ['OFFER_RECEIVED', { offerId: 'o1' }, '/offers'],
  ['OFFER_ACCEPTED', { offerId: 'o1' }, '/offers?role=sent'],
  ['ORDER', { orderId: 'or1' }, '/orders/or1'],
];

describe('notification center', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let notificationEvents: Subject<NotificationView>;
  let unread = signal({ notifications: 0, messages: 0 });
  let refetchGeneration = signal(0);
  let authenticated = signal(true);
  let cleanup: () => void;

  beforeEach(() => {
    notificationEvents = new Subject();
    unread = signal({ notifications: 0, messages: 0 });
    refetchGeneration = signal(0);
    authenticated = signal(true);
    api = {
      notifications: vi.fn(() => of({ items: [], nextCursor: null })),
      unreadSummary: vi.fn(() => of({ notifications: 0, messages: 0 })),
      readNotification: vi.fn((id: string) => of(notice('MESSAGE', { conversationId: 'c1' }, id, true))),
      readAllNotifications: vi.fn(() => of({ updated: 0 })),
      deleteNotification: vi.fn(() => of(undefined)),
      deleteReadNotifications: vi.fn(() => of(undefined)),
      unsubscribe: vi.fn(),
      unsubscribeConfirmation: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [
      provideRouter([{ path: 'notifications', children: NOTIFICATIONS_ROUTES }, { path: 'settings', component: class {} }], withComponentInputBinding()),
      { provide: MessagingApi, useValue: api },
      { provide: RealtimeStore, useValue: { notificationEvents, unread, refetchGeneration, receive: vi.fn((_queue: string, value: { notifications: number; messages: number }) => unread.set(value)) } },
      { provide: AuthStore, useValue: { isAuthenticated: authenticated, registerCleanup: vi.fn((hook: () => void) => { cleanup = () => { authenticated.set(false); hook(); }; return vi.fn(); }) } },
    ] });
  });
  afterEach(() => TestBed.resetTestingModule());

  it('resolves the notification center instead of the route stub', async () => {
    const harness = await RouterTestingHarness.create('/notifications');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Notifications');
  });

  it('provides a valid target for every notification type and leaves missing identifiers inert', async () => {
    api['notifications'].mockReturnValue(of({ items: [...targets.map(([type, target]) => notice(type, target)), notice('MESSAGE', {}, 'missing')], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/notifications');
    await harness.fixture.whenStable(); harness.detectChanges();
    for (const [type, , href] of targets) {
      expect(harness.routeNativeElement?.querySelector(`[data-notification-id="${type}"] a`)?.getAttribute('href')).toBe(href);
    }
    expect(harness.routeNativeElement?.querySelector('[data-notification-id="missing"] a')).toBeNull();
  });

  it.each(targets)('%s requires its documented identifier', async (type, target) => {
    const key = Object.keys(target)[0]!;
    api['notifications'].mockReturnValue(of({ items: [notice(type, { [key]: '  ' }, 'missing')], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/notifications');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[data-notification-id="missing"] a')).toBeNull();
    expect(harness.routeNativeElement?.textContent).toContain('The linked item is unavailable.');
  });

  it('continues after an empty page and deduplicates the next result', async () => {
    api['notifications'].mockReturnValueOnce(of({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'n1')], nextCursor: 'second' }))
      .mockReturnValueOnce(of({ items: [], nextCursor: 'third' }))
      .mockReturnValueOnce(of({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'n1'), notice('FOLLOW', { followerSub: 's1' }, 'n2')], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable();
    await page.store.load(true);
    expect(page.store.cursor()).toBe('third');
    await page.store.load(true);
    expect(page.store.items().map((row) => row.id)).toEqual(['n2', 'n1']);
    expect(api['notifications']).toHaveBeenLastCalledWith({ size: 20, cursor: 'third' });
  });

  it('merges realtime arrivals by id and keeps arrivals during an in-flight first page', async () => {
    const response = new Subject<{ items: NotificationView[]; nextCursor: null }>();
    api['notifications'].mockReturnValue(response);
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    notificationEvents.next(notice('FOLLOW', { followerSub: 's1' }, 'live'));
    notificationEvents.next(notice('FOLLOW', { followerSub: 's1' }, 'live'));
    response.next({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'http')], nextCursor: null }); response.complete();
    await harness.fixture.whenStable();
    expect(page.store.items().map((row) => row.id)).toEqual(['live', 'http']);
    expect(page.store.unreadCount()).toBe(1);
  });

  it('prevents deletion while unread, then marks and deletes a read row', async () => {
    api['notifications'].mockReturnValue(of({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'n1')], nextCursor: null }));
    api['unreadSummary'].mockReturnValue(of({ notifications: 1, messages: 2 }));
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[data-notification-id="n1"] button')?.textContent).toContain('Mark read');
    await page.store.remove('n1');
    expect(api['deleteNotification']).not.toHaveBeenCalled();
    await page.store.markRead('n1');
    expect(api['readNotification']).toHaveBeenCalledWith('n1');
    expect(page.store.items()[0]?.read).toBe(true);
    await page.store.remove('n1');
    expect(api['deleteNotification']).toHaveBeenCalledWith('n1');
  });

  it('leaves a row unread and reports a retryable command error when mark-read fails', async () => {
    api['notifications'].mockReturnValue(of({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'n1')], nextCursor: null }));
    api['readNotification'].mockReturnValue(throwError(() => new Error('network')));
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable();
    expect(await page.store.markRead('n1')).toBe(false);
    expect(page.store.items()[0]?.read).toBe(false);
    expect(page.store.actionError()).toContain('Try again');
    api['readNotification'].mockReturnValue(of(notice('MESSAGE', { conversationId: 'c1' }, 'n1', true)));
    expect(await page.store.markRead('n1')).toBe(true);
    expect(page.store.actionError()).toBe('');
  });

  it('bulk deletes only read rows and keeps unread rows visible', async () => {
    api['notifications'].mockReturnValue(of({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'new'), notice('FOLLOW', { followerSub: 's1' }, 'read', true)], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable();
    await page.store.removeRead();
    expect(api['deleteReadNotifications']).toHaveBeenCalledOnce();
    expect(page.store.items().map((row) => row.id)).toEqual(['new']);
  });

  it('does not restore a deleted row from an older paging response', async () => {
    const delayed = new Subject<{ items: NotificationView[]; nextCursor: null }>();
    const read = notice('FOLLOW', { followerSub: 's1' }, 'read', true);
    api['notifications'].mockReturnValueOnce(of({ items: [read], nextCursor: 'more' })).mockReturnValueOnce(delayed);
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable();
    const pending = page.store.load(true);
    await page.store.remove('read');
    delayed.next({ items: [read], nextCursor: null }); delayed.complete();
    await pending;
    expect(page.store.items()).toEqual([]);
  });

  it('ignores an older unread summary after the latest refresh completes', async () => {
    const slow = new Subject<{ notifications: number; messages: number }>();
    api['unreadSummary'].mockReturnValueOnce(slow).mockReturnValueOnce(of({ notifications: 2, messages: 1 }));
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await page.store.refreshSummary();
    slow.next({ notifications: 9, messages: 1 }); slow.complete();
    await harness.fixture.whenStable();
    expect(page.store.unreadCount()).toBe(2);
  });

  it('keeps realtime arrivals unread while marking the existing list read', async () => {
    api['notifications'].mockReturnValue(of({ items: [notice('FOLLOW', { followerSub: 's1' }, 'old')], nextCursor: null }));
    api['unreadSummary'].mockReturnValue(of({ notifications: 1, messages: 0 }));
    const response = new Subject<{ updated: number }>();
    api['readAllNotifications'].mockReturnValue(response);
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable();
    const pending = page.store.markAllRead();
    notificationEvents.next(notice('FOLLOW', { followerSub: 's2' }, 'new'));
    response.next({ updated: 1 }); response.complete();
    await pending;
    expect(page.store.items().find((row) => row.id === 'new')?.read).toBe(false);
    expect(page.store.items().find((row) => row.id === 'old')?.read).toBe(true);
  });

  it('clears on auth cleanup and ignores stale page and summary responses', async () => {
    const pageResponse = new Subject<{ items: NotificationView[]; nextCursor: null }>();
    const summaryResponse = new Subject<{ notifications: number; messages: number }>();
    api['notifications'].mockReturnValue(pageResponse);
    api['unreadSummary'].mockReturnValue(summaryResponse);
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    cleanup();
    pageResponse.next({ items: [notice('FOLLOW', { followerSub: 's1' })], nextCursor: null }); pageResponse.complete();
    summaryResponse.next({ notifications: 9, messages: 0 }); summaryResponse.complete();
    await harness.fixture.whenStable();
    expect(page.store.items()).toEqual([]);
    expect(page.store.unreadCount()).toBe(0);
    notificationEvents.next(notice('FOLLOW', { followerSub: 's2' }, 'later'));
    expect(page.store.items()).toEqual([]);
  });

  it('replaces stale pages and refetches the unread summary after a reconnect', async () => {
    api['notifications'].mockReturnValueOnce(of({ items: [notice('FOLLOW', { followerSub: 's1' }, 'old')], nextCursor: 'more' }))
      .mockReturnValueOnce(of({ items: [notice('MESSAGE', { conversationId: 'c1' }, 'fresh')], nextCursor: null }));
    api['unreadSummary'].mockReturnValueOnce(of({ notifications: 1, messages: 0 }))
      .mockReturnValueOnce(of({ notifications: 3, messages: 2 }));
    const harness = await RouterTestingHarness.create('/notifications');
    const page = await harness.navigateByUrl('/notifications', NotificationsPage);
    await harness.fixture.whenStable();
    refetchGeneration.set(1);
    harness.detectChanges(); await harness.fixture.whenStable();
    expect(page.store.items().map((row) => row.id)).toEqual(['fresh']);
    expect(page.store.unreadCount()).toBe(3);
    expect(api['unreadSummary']).toHaveBeenCalledTimes(2);
  });

  it('links optional email preferences to Settings without a SPA unsubscribe request', async () => {
    const harness = await RouterTestingHarness.create('/notifications');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(NOTIFICATIONS_ROUTES.map((route) => route.path)).toEqual(['']);
    const preferences = harness.routeNativeElement?.querySelector('.notification-heading a[href="/settings"]');
    expect(preferences?.textContent).toContain('Email preferences');
    expect(preferences?.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('footer')).toBeNull();
    expect(harness.routeNativeElement?.textContent).not.toContain('Order emails continue.');
    expect(api['unsubscribe']).not.toHaveBeenCalled();
    expect(api['unsubscribeConfirmation']).not.toHaveBeenCalled();
  });

});
