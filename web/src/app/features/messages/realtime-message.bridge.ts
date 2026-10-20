import { DestroyRef, effect, inject, Injectable, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { MessagingApi } from '../../core/api/messaging-api';
import { AuthStore } from '../../core/auth/auth.store';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { RealtimeStore } from '../../core/realtime/realtime.store';
import { MessagingStore } from './messaging.store';

@Injectable()
export class RealtimeMessageBridge {
  private readonly realtime = inject(RealtimeStore);
  private readonly transport = inject(RealtimeService);
  private readonly store = inject(MessagingStore);
  private readonly api = inject(MessagingApi);
  private readonly auth = inject(AuthStore);
  private readonly destroy = inject(DestroyRef);
  private version = 0;
  private pending = false;
  private running = false;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private typingTimer?: ReturnType<typeof setTimeout>;
  private lastTypingAt = -Infinity;
  private preserve: (command: () => Promise<unknown>) => Promise<void> = async (command) => { await command(); };
  readonly connection = this.realtime.connection;

  constructor() {
    this.realtime.messageEvents.pipe(takeUntilDestroyed()).subscribe(({ message }) => {
      if (!this.auth.identity() || this.connection() !== 'connected') return;
      void this.preserve(() => this.store.mergeIncoming({
        ...message, images: message.images.map((image) => ({ ...image })),
        offer: message.offer ? { ...message.offer, amount: String(message.offer.amount), askingPrice: String(message.offer.askingPrice) } : null,
      }));
      this.scheduleRefresh();
    });
    effect(() => {
      const connection = this.connection();
      this.realtime.connectionEpoch();
      untracked(() => {
        ++this.version;
        this.running = false;
        this.cancelTyping();
        if (connection === 'connected' && this.auth.identity()) this.scheduleRefresh();
      });
    });
    effect(() => { this.store.selectedId(); untracked(() => this.cancelTyping()); });
    const unregister = this.auth.registerCleanup(() => this.clear());
    this.destroy.onDestroy(() => { unregister(); this.clear(); });
  }

  bind(preserve: RealtimeMessageBridge['preserve']): void { this.preserve = preserve; }

  private clear(): void {
    ++this.version;
    this.pending = false;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = undefined;
    this.running = false;
    this.cancelTyping();
  }

  private scheduleRefresh(): void {
    this.pending = true;
    if (this.running || this.refreshTimer !== undefined) return;
    this.refreshTimer = setTimeout(() => { this.refreshTimer = undefined; void this.refresh(); }, 50);
  }

  private async refresh(): Promise<void> {
    if (this.destroy.destroyed || !this.auth.identity() || this.connection() !== 'connected') return;
    this.running = true;
    const runVersion = this.version;
    try {
      while (runVersion === this.version && this.pending && !this.destroy.destroyed && this.connection() === 'connected') {
        this.pending = false;
        const version = this.version;
        const epoch = this.realtime.connectionEpoch();
        await this.preserve(async () => {
          await Promise.all([this.store.loadInbox(false, true, true), this.store.refreshHistory(true)]);
        });
        if (version !== this.version || epoch !== this.realtime.connectionEpoch()) continue;
        const unread = this.realtime.unread();
        try {
          const summary = await firstValueFrom(this.api.unreadSummary());
          // A pushed summary is newer than an HTTP read already in flight.
          if (version === this.version && epoch === this.realtime.connectionEpoch() && unread === this.realtime.unread()) this.realtime.receive('unread', summary);
        } catch { /* The next event or reconnect retries this advisory count. */ }
      }
    } finally { if (runVersion === this.version) this.running = false; }
  }

  typing(): void {
    const id = this.store.selectedId();
    if (!id || !this.store.draft().trim() || this.connection() !== 'connected') { this.cancelTyping(); return; }
    const remaining = 1500 - (Date.now() - this.lastTypingAt);
    if (remaining <= 0) {
      clearTimeout(this.typingTimer); this.typingTimer = undefined;
      this.transport.sendTyping(id); this.lastTypingAt = Date.now();
    } else if (this.typingTimer === undefined) {
      this.typingTimer = setTimeout(() => {
        this.typingTimer = undefined;
        if (this.store.selectedId() === id) this.typing();
      }, remaining);
    }
  }

  cancelTyping(): void {
    clearTimeout(this.typingTimer); this.typingTimer = undefined;
  }
}
