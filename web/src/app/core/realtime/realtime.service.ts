import { DestroyRef, effect, inject, Injectable, Injector, untracked } from '@angular/core';
import { Client, ReconnectionTimeMode, StompSubscription, TickerStrategy } from '@stomp/stompjs';
import { AuthAdapter } from '../auth/auth.adapter';
import { AuthStore } from '../auth/auth.store';
import { AppConfigService } from '../config/app-config';
import { RealtimeStore } from './realtime.store';
import { StompAdapter } from './stomp.adapter';

const QUEUES = ['messages', 'notifications', 'unread', 'presence', 'typing'] as const;

@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly adapter = inject(StompAdapter);
  private readonly auth = inject(AuthAdapter);
  private readonly authStore = inject(AuthStore);
  private readonly config = inject(AppConfigService);
  private readonly store = inject(RealtimeStore);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private client?: Client;
  private subscriptions: StompSubscription[] = [];
  private stopping?: Promise<void>;
  private desired = false;
  private bound = false;
  private destroyed = false;
  private connectionEpoch = 0;
  private unregisterCleanup?: () => void;
  private lastTypingAt?: number;
  readonly refetchGeneration = this.store.refetchGeneration;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.unregisterCleanup?.();
      void this.deactivate();
    });
  }

  connectToSession(): void {
    if (this.bound || this.destroyed) return;
    this.bound = true;
    this.unregisterCleanup = this.authStore.registerCleanup(() => this.deactivate());
    effect(
      () => {
        const status = this.authStore.status();
        const sub = this.authStore.identity()?.sub;
        untracked(() => {
          if (status === 'authenticated' && sub) this.activate();
          else if (status === 'guest') void this.deactivate();
        });
      },
      { injector: this.injector },
    );
  }

  activate(): void {
    if (this.destroyed) return;
    this.desired = true;
    if (this.client || this.stopping) return;
    let connectedOnce = false;
    const client = this.adapter.createClient({
      brokerURL: this.config.config.wsUrl,
      reconnectDelay: 1000,
      maxReconnectDelay: 30000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      heartbeatStrategy: TickerStrategy.Worker,
      discardWebsocketOnCommFailure: true,
      beforeConnect: async (connectingClient) => {
        connectingClient.connectHeaders = {};
        if (!this.isCurrent(connectingClient)) return;
        let token: string | null = null;
        try {
          token = await this.auth.accessToken();
        } catch {
          // Provider failures may include credentials; neither retain nor log their details.
        }
        if (!this.isCurrent(connectingClient)) return;
        if (!token?.trim()) {
          void this.deactivate();
          return;
        }
        connectingClient.connectHeaders = { Authorization: `Bearer ${token}` };
      },
      onConnect: () => {
        if (!this.isCurrent(client) || this.subscriptions.length > 0) return;
        const epoch = ++this.connectionEpoch;
        for (const queue of QUEUES) {
          this.subscriptions.push(
            client.subscribe(`/user/queue/${queue}`, (frame) => {
              if (!this.isCurrent(client) || epoch !== this.connectionEpoch) return;
              let payload: unknown;
              try {
                payload = JSON.parse(frame.body);
              } catch {
                return;
              }
              this.store.receive(queue, payload);
            }),
          );
        }
        this.store.setConnection('connected');
        if (connectedOnce) this.store.refetchAfterReconnect();
        connectedOnce = true;
      },
      onWebSocketClose: () => {
        if (!this.isCurrent(client)) return;
        ++this.connectionEpoch;
        this.subscriptions = [];
        this.store.setConnection('reconnecting');
      },
    });
    this.client = client;
    this.store.setConnection('connecting');
    client.activate();
  }

  sendTyping(conversationId: string): void {
    const client = this.client;
    if (
      !client ||
      !this.isCurrent(client) ||
      this.store.connection() !== 'connected' ||
      !client.connected ||
      !conversationId.trim() ||
      /[/\s\u0000-\u001f\u007f]/.test(conversationId)
    )
      return;
    const now = Date.now();
    // The backend throttles both per user and per conversation, so switching threads shares this budget.
    if (this.lastTypingAt !== undefined && now - this.lastTypingAt < 1500) return;
    try {
      client.publish({ destination: `/app/conversations/${conversationId}/typing` });
      this.lastTypingAt = now;
    } catch {
      // A transport closing between the state check and SEND must not break the input handler.
    }
  }

  deactivate(): Promise<void> {
    this.desired = false;
    const client = this.client;
    this.client = undefined;
    ++this.connectionEpoch;
    const subscriptions = this.subscriptions;
    this.subscriptions = [];
    this.lastTypingAt = undefined;
    this.store.reset();
    for (const subscription of subscriptions) {
      try {
        subscription.unsubscribe();
      } catch {
        // A socket can close before cleanup reaches its subscriptions.
      }
    }
    if (!client) return this.stopping ?? Promise.resolve();
    client.connectHeaders = {};
    // Serialize sessions so rapid sign-out/sign-in cannot overlap native sockets.
    this.stopping = client
      .deactivate()
      .catch(() => undefined)
      .then(() => {
        this.stopping = undefined;
        if (this.desired && !this.destroyed) this.activate();
      });
    return this.stopping;
  }

  private isCurrent(client: Client): boolean {
    return this.desired && this.client === client && !this.destroyed;
  }
}
