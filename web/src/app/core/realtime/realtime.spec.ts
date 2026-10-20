import { TestBed } from '@angular/core/testing';
import {
  Client,
  IFrame,
  IMessage,
  ReconnectionTimeMode,
  StompConfig,
  TickerStrategy,
} from '@stomp/stompjs';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import { AuthAdapter, AuthEvent, AuthIdentity } from '../auth/auth.adapter';
import { AuthStore } from '../auth/auth.store';
import { StompAdapter } from './stomp.adapter';
import { RealtimeService } from './realtime.service';
import { MessageEvent, NotificationView, RealtimeStore } from './realtime.store';

const QUEUES = [
  '/user/queue/messages',
  '/user/queue/notifications',
  '/user/queue/unread',
  '/user/queue/presence',
  '/user/queue/typing',
];

it('replaces the socket on a direct account switch and rejects callbacks from the old connection', async () => {
  const { auth, service, store, clients, connect, callbacks } = setup();
  const session = TestBed.inject(AuthStore);
  await session.init(); service.connectToSession(); TestBed.tick(); connect();
  const oldUnread = callbacks.get('/user/queue/unread')!;
  auth.identity = { sub: 'another-member', groups: [] };
  auth.listener?.('signedIn'); await Promise.resolve(); TestBed.tick();
  await Promise.resolve(); await Promise.resolve(); TestBed.tick();
  expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
  expect(clients.length).toBe(2);
  connect(clients[1]);
  oldUnread({ body: JSON.stringify({ notifications: 99, messages: 99 }) } as IMessage);
  expect(store.unread()).toEqual({ notifications: 0, messages: 0 });
});

class FakeAuthAdapter {
  identity: AuthIdentity | null = { sub: 'member', groups: [] };
  configure = vi.fn();
  fetchSession = vi.fn(() => Promise.resolve(this.identity));
  accessToken = vi.fn((): Promise<string | null> => Promise.resolve('current-token'));
  listener?: (event: AuthEvent) => void;
  onAuthEvent(listener: (event: AuthEvent) => void) {
    this.listener = listener;
    return () => {
      this.listener = undefined;
    };
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function setup() {
  const auth = new FakeAuthAdapter();
  TestBed.configureTestingModule({
    providers: [provideTestAppConfig(), { provide: AuthAdapter, useValue: auth }],
  });
  const clients: Client[] = [];
  const callbacks = new Map<string, (message: IMessage) => void>();
  const unsubscribes: ReturnType<typeof vi.fn>[] = [];
  const create = vi
    .spyOn(TestBed.inject(StompAdapter), 'createClient')
    .mockImplementation((config: StompConfig) => {
      const client = new Client(config);
      vi.spyOn(client, 'activate').mockImplementation(() => undefined);
      vi.spyOn(client, 'deactivate').mockImplementation(() => Promise.resolve());
      vi.spyOn(client, 'connected', 'get').mockReturnValue(true);
      vi.spyOn(client, 'publish').mockImplementation(() => undefined);
      vi.spyOn(client, 'subscribe').mockImplementation((destination, callback) => {
        callbacks.set(destination, callback);
        const unsubscribe = vi.fn();
        unsubscribes.push(unsubscribe);
        return { id: destination, unsubscribe };
      });
      clients.push(client);
      return client;
    });
  const service = TestBed.inject(RealtimeService);
  const store = TestBed.inject(RealtimeStore);
  function connect(client = clients[0]) {
    client.onConnect({
      command: 'CONNECTED',
      headers: {},
      body: '',
      binaryBody: new Uint8Array(),
      isBinaryBody: false,
    } as IFrame);
  }
  function frame(queue: string, payload: unknown) {
    callbacks.get(`/user/queue/${queue}`)?.({ body: JSON.stringify(payload) } as IMessage);
  }
  return { auth, create, clients, callbacks, unsubscribes, service, store, connect, frame };
}

afterEach(() => {
  TestBed.resetTestingModule();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const MESSAGE: MessageEvent = {
  type: 'message',
  conversationId: 'conversation-1',
  message: {
    id: 'message-1',
    conversationId: 'conversation-1',
    seq: 1,
    senderSub: 'partner',
    kind: 'CHAT',
    text: 'Hello',
    offer: null,
    images: [],
    createdAt: '2026-10-08T08:00:00Z',
  },
};
const NOTIFICATION: NotificationView = {
  id: 'notification-1',
  type: 'MESSAGE',
  createdAt: '2026-10-08T08:00:00Z',
  read: false,
  readAt: null,
  target: { messageId: 'message-1', conversationId: 'conversation-1', seq: '1' },
  snapshot: { actorUsername: 'partner', listingTitle: 'Hardware' },
};

describe('typed realtime frames and recovery', () => {
  it('delivers each typed message and notification once without losing consecutive frames', () => {
    const { service, store, connect, frame } = setup();
    const messages: MessageEvent[] = [];
    const notifications: NotificationView[] = [];
    store.messageEvents.subscribe((value) => messages.push(value));
    store.notificationEvents.subscribe((value) => notifications.push(value));
    service.activate();
    connect();
    frame('messages', MESSAGE);
    frame('messages', MESSAGE);
    const next = { ...MESSAGE, message: { ...MESSAGE.message, id: 'message-2', seq: 2 } };
    frame('messages', next);
    frame('notifications', NOTIFICATION);
    frame('notifications', NOTIFICATION);
    expect(messages).toEqual([MESSAGE, next]);
    expect(notifications).toEqual([NOTIFICATION]);
    expect(store.latestMessage()).toEqual(next);
    expect(store.latestNotification()).toEqual(NOTIFICATION);
  });

  it('accepts an offer card and image-only message using the complete backend DTO', () => {
    const { service, store, connect, frame } = setup();
    service.activate();
    connect();
    const offer = {
      ...MESSAGE,
      message: {
        ...MESSAGE.message,
        kind: 'OFFER',
        text: null,
        offer: {
          offerId: 'offer-1',
          event: 'CREATED',
          status: 'PENDING',
          amount: 90,
          askingPrice: 100,
          expiresAt: '2026-10-10T08:00:00Z',
          rejectionReason: null,
        },
      },
    };
    frame('messages', offer);
    expect(store.latestMessage()).toEqual(offer);
    const image = {
      ...MESSAGE,
      message: {
        ...MESSAGE.message,
        id: 'image-message',
        seq: 2,
        text: null,
        images: [{ url: 'https://media.test/private-image', expiresAt: '2026-10-08T08:15:00Z' }],
      },
    };
    frame('messages', image);
    expect(store.latestMessage()).toEqual(image);
  });

  it('rejects malformed typed frames without poisoning valid frames with the same ID', () => {
    const { service, store, connect, frame } = setup();
    service.activate();
    connect();
    for (const payload of [
      null,
      [],
      {},
      { ...MESSAGE, type: 'other' },
      { ...MESSAGE, conversationId: 'mismatch' },
      { ...MESSAGE, message: { ...MESSAGE.message, seq: -1 } },
      { ...MESSAGE, message: { ...MESSAGE.message, images: ['image'] } },
    ])
      frame('messages', payload);
    for (const payload of [
      null,
      {},
      { ...NOTIFICATION, read: 'false' },
      { ...NOTIFICATION, type: 'UNKNOWN' },
      { ...NOTIFICATION, target: { seq: 1 } },
    ])
      frame('notifications', payload);
    frame('presence', { userSub: 'partner', online: 'yes' });
    frame('typing', { conversationId: 'conversation-1', userSub: '' });
    expect(store.latestMessage()).toBeNull();
    expect(store.latestNotification()).toBeNull();
    expect(store.presence().size).toBe(0);
    expect(store.typing().size).toBe(0);
    frame('messages', MESSAGE);
    frame('notifications', NOTIFICATION);
    expect(store.latestMessage()).toEqual(MESSAGE);
    expect(store.latestNotification()).toEqual(NOTIFICATION);
  });

  it('keeps partner presence until an explicit transition and accepts an HTTP seed', () => {
    vi.useFakeTimers();
    const { service, store, connect, frame } = setup();
    service.activate();
    connect();
    store.seedPresence('seeded-partner', true);
    frame('presence', { userSub: 'partner', online: true });
    expect(store.presence().get('partner')).toBe(true);
    vi.advanceTimersByTime(120000);
    expect(store.presence().get('partner')).toBe(true);
    expect(store.presence().get('seeded-partner')).toBe(true);
    frame('presence', { userSub: 'partner', online: false });
    expect(store.presence().get('partner')).toBe(false);
  });

  it('replaces typing expiry for the same conversation and partner at the frame ttlMs', () => {
    vi.useFakeTimers();
    const { service, store, connect, frame } = setup();
    service.activate();
    connect();
    frame('typing', { conversationId: 'conversation-1', userSub: 'partner', ttlMs: 2000 });
    expect(store.typing().get('conversation-1')?.has('partner')).toBe(true);
    vi.advanceTimersByTime(1500);
    frame('typing', { conversationId: 'conversation-1', userSub: 'partner', ttlMs: 3000 });
    vi.advanceTimersByTime(2999);
    expect(store.typing().get('conversation-1')?.get('partner')?.ttlMs).toBe(3000);
    vi.advanceTimersByTime(1);
    expect(store.typing().has('conversation-1')).toBe(false);
  });

  it('expires different conversations and partners independently', () => {
    vi.useFakeTimers();
    const { service, store, connect, frame } = setup();
    service.activate();
    connect();
    frame('typing', { conversationId: 'conversation-1', userSub: 'partner', ttlMs: 1000 });
    frame('typing', { conversationId: 'conversation-1', userSub: 'other', ttlMs: 2000 });
    frame('typing', { conversationId: 'conversation-2', userSub: 'partner', ttlMs: 3000 });
    vi.advanceTimersByTime(1000);
    expect(store.typing().get('conversation-1')?.has('partner')).toBe(false);
    expect(store.typing().get('conversation-1')?.has('other')).toBe(true);
    expect(store.typing().get('conversation-2')?.has('partner')).toBe(true);
    vi.advanceTimersByTime(2000);
    expect(store.typing().size).toBe(0);
  });

  it.each([undefined, null, -1, 0, 1.5, '2000', 2147483648])(
    'rejects malformed typing ttl %s',
    (ttlMs) => {
      vi.useFakeTimers();
      const { service, store, connect, frame } = setup();
      service.activate();
      connect();
      frame('typing', { conversationId: 'conversation-1', userSub: 'partner', ttlMs });
      vi.advanceTimersByTime(4999);
      expect(store.typing().size).toBe(0);
      vi.advanceTimersByTime(1);
      expect(store.typing().size).toBe(0);
    },
  );

  it('signals one refetch per reconnect, resubscribes once, and ignores callbacks from the old socket', async () => {
    const { service, store, clients, connect, frame, callbacks } = setup();
    const messages: MessageEvent[] = [];
    store.messageEvents.subscribe((event) => messages.push(event));
    service.activate();
    connect();
    expect(store.refetchGeneration()).toBe(0);
    frame('messages', MESSAGE);
    const stale = callbacks.get('/user/queue/unread')!;
    clients[0].onWebSocketClose({});
    expect(store.connection()).toBe('reconnecting');
    stale({ body: '{"notifications":99,"messages":99}' } as IMessage);
    expect(store.unread()).toEqual({ notifications: 0, messages: 0 });
    await clients[0].beforeConnect(clients[0]);
    connect();
    connect();
    expect(store.refetchGeneration()).toBe(1);
    expect(clients[0].subscribe).toHaveBeenCalledTimes(10);
    frame('messages', MESSAGE);
    expect(messages).toEqual([MESSAGE]);
    clients[0].onWebSocketClose({});
    connect();
    expect(store.refetchGeneration()).toBe(2);
    expect(clients[0].subscribe).toHaveBeenCalledTimes(15);
  });

  it('throttles bodyless outgoing typing globally at 1500 ms and only while connected', () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const { service, clients, connect } = setup();
    service.sendTyping('conversation-1');
    service.activate();
    service.sendTyping('conversation-1');
    connect();
    service.sendTyping('conversation-1');
    service.sendTyping('conversation-2');
    vi.advanceTimersByTime(1499);
    service.sendTyping('conversation-1');
    expect(clients[0].publish).toHaveBeenCalledExactlyOnceWith({
      destination: '/app/conversations/conversation-1/typing',
    });
    vi.advanceTimersByTime(1);
    service.sendTyping('conversation-2');
    expect(clients[0].publish).toHaveBeenCalledTimes(2);
    expect(vi.mocked(clients[0].publish).mock.calls[1][0]).toEqual({
      destination: '/app/conversations/conversation-2/typing',
    });
    clients[0].onWebSocketClose({});
    vi.advanceTimersByTime(1500);
    service.sendTyping('conversation-2');
    expect(clients[0].publish).toHaveBeenCalledTimes(2);
  });

  it('rejects invalid typing destinations without consuming the throttle', () => {
    const { service, clients, connect } = setup();
    service.activate();
    connect();
    for (const id of ['', ' ', 'bad/id', 'bad\nheader', 'bad\0id']) service.sendTyping(id);
    service.sendTyping('conversation-1');
    expect(clients[0].publish).toHaveBeenCalledExactlyOnceWith({
      destination: '/app/conversations/conversation-1/typing',
    });
  });

  it('clears timers, all user state, and deduplication on a new session', async () => {
    vi.useFakeTimers();
    const { service, store, connect, frame, clients } = setup();
    const messages: MessageEvent[] = [];
    store.messageEvents.subscribe((event) => messages.push(event));
    service.activate();
    connect();
    frame('messages', MESSAGE);
    frame('notifications', NOTIFICATION);
    frame('presence', { userSub: 'partner', online: true });
    frame('typing', { conversationId: 'conversation-1', userSub: 'partner', ttlMs: 5000 });
    expect(vi.getTimerCount()).toBe(1);
    await service.deactivate();
    expect(vi.getTimerCount()).toBe(0);
    expect(store.presence().size).toBe(0);
    expect(store.typing().size).toBe(0);
    expect(store.latestMessage()).toBeNull();
    expect(store.latestNotification()).toBeNull();
    expect(store.refetchGeneration()).toBe(0);
    service.activate();
    connect(clients[1]);
    frame('messages', MESSAGE);
    expect(messages).toEqual([MESSAGE, MESSAGE]);
    service.sendTyping('conversation-1');
    expect(clients[1].publish).toHaveBeenCalledTimes(1);
  });

  it('bounds deduplication to the latest 1000 message and notification IDs', () => {
    const { service, store, connect, frame } = setup();
    let messageCount = 0;
    let notificationCount = 0;
    store.messageEvents.subscribe(() => messageCount++);
    store.notificationEvents.subscribe(() => notificationCount++);
    service.activate();
    connect();
    for (let i = 0; i <= 1000; i++) {
      frame('messages', {
        ...MESSAGE,
        message: { ...MESSAGE.message, id: `message-${i}`, seq: i + 1 },
      });
      frame('notifications', { ...NOTIFICATION, id: `notification-${i}` });
    }
    frame('messages', {
      ...MESSAGE,
      message: { ...MESSAGE.message, id: 'message-1000', seq: 1001 },
    });
    frame('notifications', { ...NOTIFICATION, id: 'notification-1000' });
    expect(messageCount).toBe(1001);
    expect(notificationCount).toBe(1001);
    frame('messages', { ...MESSAGE, message: { ...MESSAGE.message, id: 'message-0' } });
    frame('notifications', { ...NOTIFICATION, id: 'notification-0' });
    expect(messageCount).toBe(1002);
    expect(notificationCount).toBe(1002);
  });
});

describe('one authenticated realtime connection', () => {
  it('constructs one native client and subscribes once per queue across repeated activation', () => {
    const { service, create, clients, connect, store } = setup();
    service.activate();
    service.activate();
    connect();
    service.activate();
    connect();
    expect(create).toHaveBeenCalledTimes(1);
    expect(clients[0].activate).toHaveBeenCalledTimes(1);
    expect(vi.mocked(clients[0].subscribe).mock.calls.map(([destination]) => destination)).toEqual(
      QUEUES,
    );
    expect(store.connection()).toBe('connected');
    expect(create.mock.calls[0][0]).toMatchObject({
      brokerURL: TEST_APP_CONFIG.wsUrl,
      reconnectDelay: 1000,
      maxReconnectDelay: 30000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      heartbeatStrategy: TickerStrategy.Worker,
      discardWebsocketOnCommFailure: true,
    });
    expect(create.mock.calls[0][0].webSocketFactory).toBeUndefined();
    expect(create.mock.calls[0][0].debug).toBeUndefined();
  });

  it('obtains the current access token before each CONNECT and puts it only in headers', async () => {
    const { service, auth, clients, create } = setup();
    service.activate();
    await clients[0].beforeConnect(clients[0]);
    expect(clients[0].connectHeaders).toEqual({ Authorization: 'Bearer current-token' });
    auth.accessToken.mockResolvedValue('refreshed-token');
    await clients[0].beforeConnect(clients[0]);
    expect(auth.accessToken).toHaveBeenCalledTimes(2);
    expect(clients[0].connectHeaders).toEqual({ Authorization: 'Bearer refreshed-token' });
    expect(create.mock.calls[0][0].brokerURL).toBe('ws://api.test/ws');
  });

  it('updates unread totals and ignores invalid JSON or malformed totals', () => {
    const { service, store, connect, frame, callbacks } = setup();
    service.activate();
    connect();
    frame('unread', { notifications: 3, messages: 8 });
    expect(store.unread()).toEqual({ notifications: 3, messages: 8 });
    callbacks.get('/user/queue/unread')!({ body: '{bad' } as IMessage);
    for (const payload of [
      null,
      [],
      { notifications: -1, messages: 0 },
      { notifications: '3', messages: 8 },
      { notifications: 1.5, messages: 8 },
    ]) {
      frame('unread', payload);
    }
    expect(store.unread()).toEqual({ notifications: 3, messages: 8 });
  });

  it('unsubscribes, removes CONNECT credentials and clears user state on deactivation', async () => {
    const { service, clients, store, connect, frame, unsubscribes, callbacks } = setup();
    service.activate();
    await clients[0].beforeConnect(clients[0]);
    connect();
    frame('unread', { notifications: 3, messages: 8 });
    const stale = callbacks.get('/user/queue/unread')!;
    await service.deactivate();
    await service.deactivate();
    expect(unsubscribes).toHaveLength(5);
    expect(unsubscribes.every((unsubscribe) => unsubscribe.mock.calls.length === 1)).toBe(true);
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
    expect(clients[0].connectHeaders).toEqual({});
    stale({ body: '{"notifications":99,"messages":99}' } as IMessage);
    connect();
    expect(store.unread()).toEqual({ notifications: 0, messages: 0 });
    expect(store.connection()).toBe('disconnected');
  });

  it('binds once to the real AuthStore and cleans up synchronously on sign-out', async () => {
    const { service, create, clients, connect, frame, store } = setup();
    const authStore = TestBed.inject(AuthStore);
    const cleanup = vi.spyOn(authStore, 'registerCleanup');
    service.connectToSession();
    service.connectToSession();
    TestBed.tick();
    expect(create).not.toHaveBeenCalled();
    await authStore.init();
    TestBed.tick();
    expect(create).toHaveBeenCalledTimes(1);
    expect(cleanup).toHaveBeenCalledTimes(1);
    connect();
    frame('unread', { notifications: 1, messages: 2 });
    authStore.clearSession();
    expect(store.connection()).toBe('disconnected');
    expect(store.unread()).toEqual({ notifications: 0, messages: 0 });
    TestBed.tick();
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
  });

  it('does not activate for a guest', async () => {
    const { service, auth, create } = setup();
    auth.identity = null;
    service.connectToSession();
    await TestBed.inject(AuthStore).init();
    TestBed.tick();
    expect(create).not.toHaveBeenCalled();
  });

  it.each(['missing', 'failure'])(
    'stops safely when token lookup has a %s result',
    async (result) => {
      const { service, auth, clients, store } = setup();
      if (result === 'missing') auth.accessToken.mockResolvedValue(null);
      else auth.accessToken.mockRejectedValue(new Error('sensitive provider detail'));
      service.activate();
      await expect(clients[0].beforeConnect(clients[0])).resolves.toBeUndefined();
      expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
      expect(clients[0].connectHeaders).toEqual({});
      expect(store.connection()).toBe('disconnected');
    },
  );

  it('ignores a token lookup finishing after logout', async () => {
    const { service, auth, clients } = setup();
    const token = deferred<string | null>();
    auth.accessToken.mockReturnValue(token.promise);
    service.activate();
    const pending = clients[0].beforeConnect(clients[0]);
    await service.deactivate();
    token.resolve('late-token');
    await pending;
    expect(clients[0].connectHeaders).toEqual({});
  });

  it('waits for socket shutdown before a rapid next activation', async () => {
    const { service, clients, create, store } = setup();
    service.activate();
    const shutdown = deferred<void>();
    vi.mocked(clients[0].deactivate).mockReturnValue(shutdown.promise);
    const pending = service.deactivate();
    service.activate();
    service.activate();
    expect(create).toHaveBeenCalledTimes(1);
    shutdown.resolve();
    await pending;
    await Promise.resolve();
    expect(create).toHaveBeenCalledTimes(2);
    clients[0].onWebSocketClose({});
    expect(store.connection()).toBe('connecting');
  });

  it('closes the connection when its injector is destroyed', () => {
    const { service, clients } = setup();
    service.activate();
    TestBed.resetTestingModule();
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
  });
});
