import { DestroyRef, effect, inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { NotificationView } from '../../core/api/api-models';
import { MessagingApi } from '../../core/api/messaging-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { RealtimeStore } from '../../core/realtime/realtime.store';

export function notificationTarget(row: NotificationView): string | null {
  const field = (key: string) => row.target[key]?.trim() || null;
  const segment = (value: string) => encodeURIComponent(value);
  switch (row.type) {
    case 'MESSAGE': return field('conversationId') ? `/conversations/${segment(field('conversationId')!)}` : null;
    case 'FAVOURITE':
    case 'PRICE_DROP':
    case 'RELEASE': return field('listingId') ? `/listings/${segment(field('listingId')!)}` : null;
    case 'REVIEW': return field('reviewId') ? '/me?tab=reviews' : null;
    case 'FOLLOW': return field('followerSub') ? `/u/${segment(field('followerSub')!)}` : null;
    case 'OFFER_RECEIVED': return field('offerId') ? '/offers' : null;
    case 'OFFER_ACCEPTED': return field('offerId') ? '/offers?role=sent' : null;
    case 'ORDER': return field('orderId') ? `/orders/${segment(field('orderId')!)}` : null;
  }
}

export function notificationText(row: NotificationView): string {
  const value = (key: string, fallback: string) => row.snapshot[key]?.trim() || fallback;
  const username = row.snapshot['actorUsername']?.trim();
  const actor = username ? `@${username.replace(/^@/, '')}` : 'Someone';
  const title = value('listingTitle', 'a listing');
  const amount = value('amount', 'an amount');
  switch (row.type) {
    case 'MESSAGE': return `${actor} sent you a message about ${title}.`;
    case 'FAVOURITE': return `${actor} added ${title} to their favourites.`;
    case 'PRICE_DROP': return `Price drop: ${title} is now ${value('newPrice', 'a lower price')} (was ${value('oldPrice', 'the previous price')}).`;
    case 'RELEASE': return `${title} has a new version: ${value('versionNumber', 'a new release')}.`;
    case 'REVIEW': return row.snapshot['rating']?.trim() ? `You received a ${row.snapshot['rating']}-star review.` : 'You received a review.';
    case 'FOLLOW': return `${actor} started following you.`;
    case 'OFFER_RECEIVED': return `${actor} offered ${amount} for ${title}.`;
    case 'OFFER_ACCEPTED': return `${actor} accepted your offer of ${amount} for ${title}.`;
    case 'ORDER': {
      switch (row.target['event']) {
        case 'PAID': return row.snapshot['listingType'] === 'SOFTWARE' ? `${title} was paid. A licence was issued.` : `${title} was paid. Ship it as soon as you can.`;
        case 'SHIPPED': return `${title} is on its way${row.snapshot['carrier'] ? ` with ${row.snapshot['carrier']}` : ''}.`;
        case 'DELIVERED': return row.snapshot['readyToDownload'] === 'true' ? `${title} is ready to download.` : `${title} was delivered. How did it go? Leave a review.`;
        case 'REFUNDED': return `The order for ${title} was refunded.`;
        case 'PROBLEM_OPENED': return `A problem was reported on ${title}.`;
        case 'PROBLEM_RESOLVED': return row.snapshot['outcome'] === 'REFUND' ? `Your report on ${title} was resolved with a refund.` : row.snapshot['outcome'] === 'DISMISS' ? `Your report on ${title} was dismissed.` : `A problem on ${title} was resolved.`;
        default: return `Update on your order for ${title}.`;
      }
    }
  }
}

function message(error: unknown): string {
  const appError = error as AppError;
  return appError?.code ? errorCopy(appError) : 'Something went wrong. Try again in a minute.';
}

const initialState = {
  items: [] as NotificationView[], cursor: null as string | null, loaded: false,
  loading: false, listError: '', unreadCount: 0, summaryLoaded: false, summaryError: '',
  busy: '', actionError: '',
};

export const NotificationStore = signalStore(
  withState(initialState),
  withMethods((store, api = inject(MessagingApi), realtime = inject(RealtimeStore), auth = inject(AuthStore)) => {
    let generation = 0;
    let request = 0;
    let arrivalVersion = 0;
    let summaryVersion = 0;
    let summaryRequest = 0;
    const arrivalIds = new Map<string, number>();
    const readOverrides = new Set<string>();
    const deletedIds = new Set<string>();
    const destroy = inject(DestroyRef);
    const clear = () => { ++generation; ++request; ++summaryRequest; arrivalIds.clear(); readOverrides.clear(); deletedIds.clear(); arrivalVersion = 0; ++summaryVersion; patchState(store, initialState); };
    const unregister = auth.registerCleanup(clear);
    const subscription = realtime.notificationEvents.subscribe((row) => {
      if (!auth.isAuthenticated()) return;
      if (deletedIds.has(row.id)) return;
      arrivalIds.set(row.id, ++arrivalVersion);
      ++summaryVersion;
      const existing = store.items().find((item) => item.id === row.id);
      patchState(store, {
        items: [row, ...store.items().filter((item) => item.id !== row.id)],
        unreadCount: Math.max(0, store.unreadCount() + (!row.read && !existing ? 1 : 0)),
      });
    });
    let lastUnread = realtime.unread();
    effect(() => {
      const frame = realtime.unread();
      if (frame !== lastUnread) {
        lastUnread = frame;
        ++summaryVersion;
        patchState(store, { unreadCount: frame.notifications, summaryLoaded: true });
      }
    });
    let lastRefetch = realtime.refetchGeneration();
    effect(() => {
      const epoch = realtime.refetchGeneration();
      if (epoch !== lastRefetch) {
        lastRefetch = epoch;
        void load(false, true);
        void refreshSummary();
      }
    });
    destroy.onDestroy(() => { subscription.unsubscribe(); unregister(); clear(); });

    async function load(append = false, force = false, retainUnconfirmed = false): Promise<void> {
      const cursor = append ? store.cursor() : null;
      if ((store.loading() && !force) || (append && !cursor)) return;
      const token = ++request, session = generation, arrivalsAtStart = arrivalVersion;
      patchState(store, { loading: true, listError: '' });
      try {
        const page = await firstValueFrom(api.notifications({ size: 20, ...(cursor ? { cursor } : {}) }));
        if (token !== request || session !== generation) return;
        if (force && !retainUnconfirmed) readOverrides.clear();
        const preserved = append ? store.items() : store.items().filter((row) =>
          (arrivalIds.get(row.id) ?? 0) > arrivalsAtStart || (retainUnconfirmed && arrivalIds.has(row.id) && !page.items.some((item) => item.id === row.id)));
        const rows = new Map<string, NotificationView>();
        for (const row of [...preserved, ...page.items].filter((item) => !deletedIds.has(item.id))) {
          if (row.read) readOverrides.delete(row.id);
          rows.set(row.id, readOverrides.has(row.id) ? { ...row, read: true } : row);
        }
        for (const row of preserved) if ((arrivalIds.get(row.id) ?? 0) > arrivalsAtStart) rows.set(row.id, row);
        patchState(store, {
          items: [...rows.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id)),
          cursor: page.nextCursor?.trim() && page.nextCursor !== cursor ? page.nextCursor : null,
          loaded: true,
        });
      } catch (error) {
        if (token === request && session === generation) patchState(store, { listError: message(error) });
      } finally {
        if (token === request && session === generation) patchState(store, { loading: false });
      }
    }

    async function refreshSummary(): Promise<void> {
      const session = generation, version = summaryVersion, summaryToken = ++summaryRequest;
      try {
        const summary = await firstValueFrom(api.unreadSummary());
        if (session === generation && version === summaryVersion && summaryToken === summaryRequest) {
          realtime.receive('unread', summary);
          patchState(store, { unreadCount: summary.notifications, summaryLoaded: true, summaryError: '' });
        }
      } catch (error) {
        if (session === generation && summaryToken === summaryRequest) patchState(store, { summaryError: message(error) });
      }
    }

    async function markRead(id: string): Promise<boolean> {
      const current = store.items().find((row) => row.id === id);
      if (!current || store.busy()) return false;
      if (current.read) return true;
      const session = generation;
      ++summaryVersion;
      patchState(store, { busy: id, actionError: '' });
      try {
        const updated = await firstValueFrom(api.readNotification(id));
        if (session !== generation) return false;
        readOverrides.add(id);
        patchState(store, {
          items: store.items().map((row) => row.id === id ? updated : row),
          unreadCount: Math.max(0, store.unreadCount() - 1),
        });
        ++summaryVersion;
        void refreshSummary();
        return true;
      } catch (error) {
        if (session === generation) patchState(store, { actionError: message(error) });
        return false;
      } finally {
        if (session === generation) patchState(store, { busy: '' });
      }
    }

    async function markAllRead(): Promise<void> {
      if (!store.unreadCount() || store.busy()) return;
      const session = generation;
      const arrivedAtStart = arrivalVersion;
      ++summaryVersion;
      patchState(store, { busy: 'all', actionError: '' });
      try {
        await firstValueFrom(api.readAllNotifications());
        if (session !== generation) return;
        for (const row of store.items()) if ((arrivalIds.get(row.id) ?? 0) <= arrivedAtStart) readOverrides.add(row.id);
        const newUnread = store.items().filter((row) => !row.read && (arrivalIds.get(row.id) ?? 0) > arrivedAtStart).length;
        patchState(store, {
          items: store.items().map((row) => (arrivalIds.get(row.id) ?? 0) > arrivedAtStart ? row : { ...row, read: true }),
          unreadCount: newUnread,
        });
        ++summaryVersion;
        void refreshSummary();
        void load(false, true, true);
      } catch (error) {
        if (session === generation) patchState(store, { actionError: message(error) });
      } finally {
        if (session === generation) patchState(store, { busy: '' });
      }
    }

    async function remove(id: string): Promise<void> {
      const current = store.items().find((row) => row.id === id);
      if (!current?.read || store.busy()) return;
      const session = generation;
      ++summaryVersion;
      patchState(store, { busy: id, actionError: '' });
      try {
        await firstValueFrom(api.deleteNotification(id));
        if (session !== generation) return;
        deletedIds.add(id);
        patchState(store, { items: store.items().filter((row) => row.id !== id) });
        ++summaryVersion;
        void refreshSummary();
      } catch (error) {
        if (session === generation) patchState(store, { actionError: message(error) });
      } finally {
        if (session === generation) patchState(store, { busy: '' });
      }
    }

    async function removeRead(): Promise<void> {
      if (!store.items().some((row) => row.read) || store.busy()) return;
      const session = generation;
      ++summaryVersion;
      patchState(store, { busy: 'read', actionError: '' });
      try {
        await firstValueFrom(api.deleteReadNotifications());
        if (session !== generation) return;
        for (const row of store.items()) if (row.read) deletedIds.add(row.id);
        patchState(store, { items: store.items().filter((row) => !row.read) });
        ++summaryVersion;
        void refreshSummary();
      } catch (error) {
        if (session === generation) patchState(store, { actionError: message(error) });
      } finally {
        if (session === generation) patchState(store, { busy: '' });
      }
    }

    return { load, refreshSummary, markRead, markAllRead, remove, removeRead };
  }),
);
