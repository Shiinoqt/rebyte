import { DestroyRef, Injectable, Injector, effect, inject, untracked } from '@angular/core';
import { PRIMARY_OUTLET, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { AuthStore } from '../auth/auth.store';
import { MessagingStore } from '../../features/messages/messaging.store';
import { ToastService } from '../../shared/ui/feedback';
import { MessageEvent, RealtimeStore } from './realtime.store';

@Injectable({ providedIn: 'root' })
export class MessageToastListener {
  private readonly auth = inject(AuthStore);
  private readonly realtime = inject(RealtimeStore);
  private readonly messaging = inject(MessagingStore);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroy = inject(DestroyRef);
  private readonly seenIds = new Set<string>();
  private subscription?: Subscription;
  private sessionSub: string | null = null;
  private generation = 0;
  private started = false;

  start(): void {
    if (this.started || this.destroy.destroyed) return;
    this.started = true;
    const unregister = this.auth.registerCleanup(() => this.stopSession());
    this.destroy.onDestroy(() => { unregister(); this.stopSession(); });
    effect(() => {
      const status = this.auth.status();
      const sub = this.auth.identity()?.sub ?? null;
      untracked(() => {
        const nextSub = status === 'authenticated' ? sub : null;
        if (nextSub === this.sessionSub) return;
        this.stopSession();
        if (!nextSub) return;
        this.sessionSub = nextSub;
        const generation = this.generation;
        this.subscription = this.realtime.messageEvents.subscribe(event => {
          if (this.isCurrent(nextSub, generation)) this.receive(event, nextSub, generation);
        });
      });
    }, { injector: this.injector });
  }

  private stopSession(): void {
    ++this.generation;
    this.sessionSub = null;
    this.subscription?.unsubscribe();
    this.subscription = undefined;
    this.seenIds.clear();
    // Queued feedback can contain the previous account's handle or private workflow details.
    while (this.toasts.current()) this.toasts.dismiss();
  }

  private isCurrent(sub: string, generation: number): boolean {
    return !this.destroy.destroyed && generation === this.generation &&
      this.auth.status() === 'authenticated' && this.auth.identity()?.sub === sub;
  }

  private receive(event: MessageEvent, sub: string, generation: number): void {
    const { message, conversationId } = event;
    if (message.senderSub === sub || this.seenIds.has(message.id)) return;
    this.seenIds.add(message.id);
    if (this.seenIds.size > 1000) this.seenIds.delete(this.seenIds.values().next().value!);

    const primary = this.router.parseUrl(this.router.url).root.children[PRIMARY_OUTLET];
    if (primary?.segments.length === 2 && primary.segments[0].path === 'conversations' &&
        primary.segments[1].path === conversationId && !primary.hasChildren()) return;

    const selected = this.messaging.conversation();
    const conversation = selected?.id === conversationId ? selected :
      this.messaging.conversations().find(row => row.id === conversationId);
    const username = conversation?.otherParticipantUsername;
    this.toasts.show({
      kind: 'info', text: username ? `New message from @${username}` : 'New message',
      action: { label: 'Open', run: () => {
        if (this.isCurrent(sub, generation)) void this.router.navigate(['/conversations', conversationId]);
      } },
    });
  }
}
