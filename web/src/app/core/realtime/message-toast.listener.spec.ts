import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PRIMARY_OUTLET, Router, provideRouter } from '@angular/router';
import { Observable } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConversationView } from '../api/api-models';
import { AuthIdentity } from '../auth/auth.adapter';
import { AuthStatus, AuthStore } from '../auth/auth.store';
import { MessagingStore } from '../../features/messages/messaging.store';
import { ToastService } from '../../shared/ui/feedback';
import { MessageEvent, RealtimeStore } from './realtime.store';
import { MessageToastListener } from './message-toast.listener';

describe('app-wide message toasts', () => {
  const status = signal<AuthStatus>('authenticated');
  const identity = signal<AuthIdentity | null>({ sub: 'alice', groups: [] });
  const conversations = signal<ConversationView[]>([]);
  const conversation = signal<ConversationView | null>(null);
  let cleanup: () => void;
  let callbacks: Array<(event: MessageEvent) => void>;
  let subscriptions: number;
  let unsubscribes: number;
  let listener: MessageToastListener;
  let toasts: ToastService;
  let router: Router;
  let url: string;
  const registerCleanup = vi.fn((hook: () => void) => { cleanup = hook; return vi.fn(); });

  const frame = (id = 'message-1', thread = 'c1', sender = 'partner'): MessageEvent => ({
    type: 'message', conversationId: thread,
    message: { id, conversationId: thread, seq: 1, senderSub: sender, kind: 'CHAT',
      text: 'Private message must never appear in a toast', offer: null, images: [], createdAt: '2026-10-13T00:00:00Z' },
  });

  beforeEach(() => {
    status.set('authenticated'); identity.set({ sub: 'alice', groups: [] });
    conversations.set([]); conversation.set(null); callbacks = []; subscriptions = 0; unsubscribes = 0; url = '/';
    registerCleanup.mockClear();
    TestBed.configureTestingModule({ providers: [
      provideRouter([]),
      { provide: AuthStore, useValue: { status, identity, registerCleanup } },
      { provide: MessagingStore, useValue: { conversations, conversation } },
      { provide: RealtimeStore, useValue: { messageEvents: new Observable<MessageEvent>(subscriber => {
        ++subscriptions;
        // Retain the callback to exercise a queued callback from an unsubscribed session.
        callbacks.push(event => subscriber.next(event));
        return () => ++unsubscribes;
      }) } },
    ] });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockImplementation(() => url);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    toasts = TestBed.inject(ToastService);
    vi.spyOn(toasts, 'show');
    listener = TestBed.inject(MessageToastListener);
    listener.start(); TestBed.tick();
  });

  afterEach(() => { TestBed.resetTestingModule(); vi.restoreAllMocks(); });

  it.each(['/', '/conversations', '/conversations/c2', '/conversations/c1/extra'])('notifies outside the exact thread at %s', path => {
    url = path; callbacks.at(-1)!(frame());
    expect(toasts.current()?.text).toBe('New message');
    expect(toasts.current()?.kind).toBe('info');
    expect(toasts.current()?.text).not.toContain('Private');
    toasts.current()?.action?.run();
    expect(router.navigate).toHaveBeenCalledExactlyOnceWith(['/conversations', 'c1']);
  });

  it.each(['/conversations/c1', '/conversations/c1?from=inbox#latest', '/conversations/c%31', '/conversations/c1;view=latest'])('suppresses a frame in the active thread %s', path => {
    url = path; callbacks.at(-1)!(frame()); expect(toasts.show).not.toHaveBeenCalled();
  });

  it('compares decoded segments, including ids that contain reserved characters', () => {
    url = '/conversations/c%2F1';
    expect(router.parseUrl(url).root.children[PRIMARY_OUTLET].segments[1].path).toBe('c/1');
    callbacks.at(-1)!(frame('one', 'c/1')); expect(toasts.show).not.toHaveBeenCalled();
  });

  it('deduplicates frames even when the first one was suppressed in its thread', () => {
    url = '/conversations/c1'; callbacks.at(-1)!(frame()); url = '/'; callbacks.at(-1)!(frame());
    expect(toasts.show).not.toHaveBeenCalled();
    callbacks.at(-1)!(frame('two')); callbacks.at(-1)!(frame('two'));
    expect(toasts.show).toHaveBeenCalledTimes(1);
  });

  it('ignores messages sent by the current member', () => {
    callbacks.at(-1)!(frame('own', 'c1', 'alice')); expect(toasts.show).not.toHaveBeenCalled();
  });

  it.each(['inbox', 'thread'])('uses a handle already cached in the %s without a request', source => {
    const row = { id: 'c1', otherParticipantUsername: 'sam' } as ConversationView;
    if (source === 'inbox') conversations.set([row]); else conversation.set(row);
    callbacks.at(-1)!(frame()); expect(toasts.current()?.text).toBe('New message from @sam');
  });

  it('stops immediately on logout, clears queued private toasts, and resumes with fresh ids without a remount', () => {
    callbacks.at(-1)!(frame()); callbacks.at(-1)!(frame('two'));
    const action = toasts.current()?.action;
    status.set('guest'); identity.set(null); cleanup();
    expect(toasts.current()).toBeNull();
    callbacks[0](frame('late')); action?.run(); expect(router.navigate).not.toHaveBeenCalled();
    TestBed.tick(); expect(unsubscribes).toBe(1);
    identity.set({ sub: 'alice', groups: [] }); status.set('authenticated'); TestBed.tick();
    callbacks.at(-1)!(frame()); expect(toasts.show).toHaveBeenCalledTimes(3);
    expect(subscriptions).toBe(2); expect(registerCleanup).toHaveBeenCalledTimes(1);
  });

  it('rejects old-session frames and actions before the identity effect runs', () => {
    callbacks[0](frame()); const action = toasts.current()?.action;
    identity.set({ sub: 'bob', groups: [] });
    callbacks[0](frame('late-before-effect')); action?.run();
    expect(toasts.show).toHaveBeenCalledTimes(1); expect(router.navigate).not.toHaveBeenCalled();
    TestBed.tick(); callbacks[0](frame('late-after-effect')); callbacks.at(-1)!(frame('bob-message'));
    expect(toasts.show).toHaveBeenCalledTimes(2); expect(toasts.current()?.text).toBe('New message');
  });

  it('starts once and does not resubscribe for token or role refreshes of the same subject', () => {
    listener.start(); listener.start(); identity.set({ sub: 'alice', groups: ['ADMIN'] }); TestBed.tick();
    expect(registerCleanup).toHaveBeenCalledTimes(1); expect(subscriptions).toBe(1);
    callbacks[0](frame()); expect(toasts.show).toHaveBeenCalledTimes(1);
  });

  it('does not deliver during resolving and restarts when authentication settles', () => {
    status.set('resolving'); callbacks[0](frame()); TestBed.tick();
    expect(toasts.show).not.toHaveBeenCalled();
    status.set('authenticated'); TestBed.tick(); callbacks.at(-1)!(frame());
    expect(toasts.show).toHaveBeenCalledTimes(1);
  });

  it('bounds deduplication while retaining the most recent thousand message ids', () => {
    url = '/conversations/c1';
    for (let index = 0; index <= 1000; ++index) callbacks[0](frame(String(index)));
    url = '/'; callbacks[0](frame('1000')); expect(toasts.show).not.toHaveBeenCalled();
    callbacks[0](frame('0')); expect(toasts.show).toHaveBeenCalledTimes(1);
  });
});
