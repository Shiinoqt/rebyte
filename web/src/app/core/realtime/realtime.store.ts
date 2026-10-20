import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Subject } from 'rxjs';

export type RealtimeConnection = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';
export interface UnreadSummary {
  readonly notifications: number;
  readonly messages: number;
}

export interface MessageView {
  readonly id: string;
  readonly conversationId: string;
  readonly seq: number;
  readonly senderSub: string;
  readonly kind: 'CHAT' | 'OFFER';
  readonly text: string | null;
  readonly offer: {
    readonly offerId: string;
    readonly event: 'CREATED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED';
    readonly status: string;
    readonly amount: number;
    readonly askingPrice: number;
    readonly expiresAt: string | null;
    readonly rejectionReason: string | null;
  } | null;
  readonly images: readonly { readonly url: string; readonly expiresAt: string }[];
  readonly createdAt: string;
}

export interface MessageEvent {
  readonly type: 'message';
  readonly conversationId: string;
  readonly message: MessageView;
}

export type NotificationType =
  | 'MESSAGE'
  | 'FAVOURITE'
  | 'PRICE_DROP'
  | 'RELEASE'
  | 'REVIEW'
  | 'FOLLOW'
  | 'OFFER_RECEIVED'
  | 'OFFER_ACCEPTED'
  | 'ORDER';

export interface NotificationView {
  readonly id: string;
  readonly type: NotificationType;
  readonly createdAt: string;
  readonly read: boolean;
  readonly readAt: string | null;
  readonly target: Readonly<Record<string, string>>;
  readonly snapshot: Readonly<Record<string, string>>;
}

export interface PresenceEvent {
  readonly userSub: string;
  readonly online: boolean;
}

export interface TypingEvent {
  readonly conversationId: string;
  readonly userSub: string;
  readonly ttlMs: number;
}

const NOTIFICATION_TYPES: readonly string[] = [
  'MESSAGE',
  'FAVOURITE',
  'PRICE_DROP',
  'RELEASE',
  'REVIEW',
  'FOLLOW',
  'OFFER_RECEIVED',
  'OFFER_ACCEPTED',
  'ORDER',
];
const OFFER_EVENTS: readonly string[] = ['CREATED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'];
const DEDUP_LIMIT = 1000;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonempty(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function count(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function instant(value: unknown): value is string {
  return nonempty(value) && Number.isFinite(Date.parse(value));
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function stringMap(value: unknown): value is Record<string, string> {
  return record(value) && Object.values(value).every((entry) => typeof entry === 'string');
}

function validOffer(value: unknown): boolean {
  if (value === null) return true;
  return (
    record(value) &&
    nonempty(value['offerId']) &&
    nonempty(value['event']) &&
    OFFER_EVENTS.includes(value['event']) &&
    nonempty(value['status']) &&
    typeof value['amount'] === 'number' &&
    Number.isFinite(value['amount']) &&
    typeof value['askingPrice'] === 'number' &&
    Number.isFinite(value['askingPrice']) &&
    (value['expiresAt'] === null || instant(value['expiresAt'])) &&
    nullableString(value['rejectionReason'])
  );
}

function messageEvent(value: unknown): value is MessageEvent {
  if (!record(value) || value['type'] !== 'message' || !nonempty(value['conversationId']))
    return false;
  const message = value['message'];
  return (
    record(message) &&
    nonempty(message['id']) &&
    message['conversationId'] === value['conversationId'] &&
    count(message['seq']) &&
    message['seq'] > 0 &&
    nonempty(message['senderSub']) &&
    (message['kind'] === 'CHAT' || message['kind'] === 'OFFER') &&
    nullableString(message['text']) &&
    validOffer(message['offer']) &&
    Array.isArray(message['images']) &&
    message['images'].every(
      (image: unknown) => record(image) && nonempty(image['url']) && instant(image['expiresAt']),
    ) &&
    instant(message['createdAt'])
  );
}

function notificationView(value: unknown): value is NotificationView {
  return (
    record(value) &&
    nonempty(value['id']) &&
    nonempty(value['type']) &&
    NOTIFICATION_TYPES.includes(value['type']) &&
    instant(value['createdAt']) &&
    typeof value['read'] === 'boolean' &&
    (value['readAt'] === null || instant(value['readAt'])) &&
    stringMap(value['target']) &&
    stringMap(value['snapshot'])
  );
}

@Injectable({ providedIn: 'root' })
export class RealtimeStore {
  private readonly currentConnection = signal<RealtimeConnection>('disconnected');
  private readonly currentUnread = signal<UnreadSummary>({ notifications: 0, messages: 0 });
  private readonly currentMessage = signal<MessageEvent | null>(null);
  private readonly currentNotification = signal<NotificationView | null>(null);
  private readonly currentPresence = signal<ReadonlyMap<string, boolean>>(new Map());
  private readonly currentTyping = signal<ReadonlyMap<string, ReadonlyMap<string, TypingEvent>>>(
    new Map(),
  );
  private readonly generation = signal(0);
  private readonly sessionEpoch = signal(0);
  private readonly messages = new Subject<MessageEvent>();
  private readonly notifications = new Subject<NotificationView>();
  private readonly presenceFrames = new Subject<PresenceEvent>();
  private readonly typingFrames = new Subject<TypingEvent>();
  private readonly messageIds = new Set<string>();
  private readonly notificationIds = new Set<string>();
  private readonly typingTimers = new Map<string, Map<string, ReturnType<typeof setTimeout>>>();
  readonly connection = this.currentConnection.asReadonly();
  readonly unread = this.currentUnread.asReadonly();
  readonly latestMessage = this.currentMessage.asReadonly();
  readonly latestNotification = this.currentNotification.asReadonly();
  readonly presence = this.currentPresence.asReadonly();
  readonly typing = this.currentTyping.asReadonly();
  readonly refetchGeneration = this.generation.asReadonly();
  readonly connectionEpoch = this.sessionEpoch.asReadonly();
  readonly messageEvents = this.messages.asObservable();
  readonly notificationEvents = this.notifications.asObservable();
  readonly presenceEvents = this.presenceFrames.asObservable();
  readonly typingEvents = this.typingFrames.asObservable();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.reset();
      this.messages.complete();
      this.notifications.complete();
      this.presenceFrames.complete();
      this.typingFrames.complete();
    });
  }

  seedPresence(userSub: string, online: boolean): void {
    if (!nonempty(userSub) || typeof online !== 'boolean') return;
    this.currentPresence.update((current) => new Map(current).set(userSub, online));
  }

  refetchAfterReconnect(): void {
    this.generation.update((current) => current + 1);
  }

  setConnection(connection: RealtimeConnection): void {
    if (connection !== this.currentConnection()) this.sessionEpoch.update((value) => value + 1);
    if (connection !== 'connected') this.clearCues();
    this.currentConnection.set(connection);
  }

  clearTyping(conversationId: string, userSub: string): void {
    const timers = this.typingTimers.get(conversationId);
    const timer = timers?.get(userSub);
    if (timer !== undefined) clearTimeout(timer);
    timers?.delete(userSub);
    if (timers?.size === 0) this.typingTimers.delete(conversationId);
    this.currentTyping.update((current) => {
      const next = new Map(current);
      const partners = new Map(next.get(conversationId));
      partners.delete(userSub);
      if (partners.size) next.set(conversationId, partners);
      else next.delete(conversationId);
      return next;
    });
  }

  receive(queue: string, payload: unknown): void {
    switch (queue) {
      case 'unread':
        if (record(payload) && count(payload['notifications']) && count(payload['messages'])) {
          this.currentUnread.set({
            notifications: payload['notifications'],
            messages: payload['messages'],
          });
        }
        break;
      case 'messages':
        if (messageEvent(payload) && this.remember(this.messageIds, payload.message.id)) {
          this.clearTyping(payload.conversationId, payload.message.senderSub);
          this.currentMessage.set(payload);
          this.messages.next(payload);
        }
        break;
      case 'notifications':
        if (notificationView(payload) && this.remember(this.notificationIds, payload.id)) {
          this.currentNotification.set(payload);
          this.notifications.next(payload);
        }
        break;
      case 'presence':
        if (
          record(payload) &&
          nonempty(payload['userSub']) &&
          typeof payload['online'] === 'boolean'
        ) {
          const event: PresenceEvent = { userSub: payload['userSub'], online: payload['online'] };
          this.seedPresence(event.userSub, event.online);
          this.presenceFrames.next(event);
        }
        break;
      case 'typing':
        if (
          record(payload) &&
          nonempty(payload['conversationId']) &&
          nonempty(payload['userSub'])
        ) {
          const ttl = payload['ttlMs'];
            if (!count(ttl) || ttl <= 0 || ttl > 2147483647) return;
            const ttlMs = ttl;
          const event: TypingEvent = {
            conversationId: payload['conversationId'],
            userSub: payload['userSub'],
            ttlMs,
          };
          this.receiveTyping(event);
          this.typingFrames.next(event);
        }
        break;
    }
  }

  reset(): void {
    this.clearCues();
    this.sessionEpoch.update((value) => value + 1);
    this.messageIds.clear();
    this.notificationIds.clear();
    this.currentConnection.set('disconnected');
    this.currentUnread.set({ notifications: 0, messages: 0 });
    this.currentMessage.set(null);
    this.currentNotification.set(null);
    this.generation.set(0);
  }

  private clearCues(): void {
    for (const timers of this.typingTimers.values()) {
      for (const timer of timers.values()) clearTimeout(timer);
    }
    this.typingTimers.clear();
    this.currentPresence.set(new Map());
    this.currentTyping.set(new Map());
  }

  private receiveTyping(event: TypingEvent): void {
    const { conversationId, userSub, ttlMs } = event;
    let timers = this.typingTimers.get(conversationId);
    if (!timers) {
      timers = new Map();
      this.typingTimers.set(conversationId, timers);
    }
    const previous = timers.get(userSub);
    if (previous !== undefined) clearTimeout(previous);
    this.currentTyping.update((current) => {
      const partners = new Map(current.get(conversationId));
      partners.set(userSub, event);
      return new Map(current).set(conversationId, partners);
    });
    timers.set(
      userSub,
      setTimeout(() => {
        const activeTimers = this.typingTimers.get(conversationId);
        activeTimers?.delete(userSub);
        if (activeTimers?.size === 0) this.typingTimers.delete(conversationId);
        this.currentTyping.update((current) => {
          const conversations = new Map(current);
          const partners = new Map(conversations.get(conversationId));
          partners.delete(userSub);
          if (partners.size > 0) conversations.set(conversationId, partners);
          else conversations.delete(conversationId);
          return conversations;
        });
      }, ttlMs),
    );
  }

  private remember(ids: Set<string>, id: string): boolean {
    if (ids.has(id)) return false;
    ids.add(id);
    if (ids.size > DEDUP_LIMIT) ids.delete(ids.values().next().value!);
    return true;
  }
}
