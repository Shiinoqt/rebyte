import { DestroyRef, inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ConversationView } from '../../core/api/api-models';
import { RealtimeStore } from '../../core/realtime/realtime.store';
import { AuthStore } from '../../core/auth/auth.store';

@Injectable({ providedIn: 'root' })
export class TypingPresenceStore {
  private readonly realtime = inject(RealtimeStore);
  private revision = 0;
  private readonly transitions = new Map<string, number>();

  constructor() {
    const unregister = inject(AuthStore).registerCleanup(() => { ++this.revision; this.transitions.clear(); });
    inject(DestroyRef).onDestroy(unregister);
    this.realtime.presenceEvents.pipe(takeUntilDestroyed()).subscribe(({ userSub }) => {
      this.transitions.set(userSub, ++this.revision);
    });
  }

  capture(): { revision: number; epoch: number } {
    return { revision: this.revision, epoch: this.realtime.connectionEpoch() };
  }

  seed(rows: ConversationView[], token: ReturnType<TypingPresenceStore['capture']>): void {
    if (token.epoch !== this.realtime.connectionEpoch() || this.realtime.connection() !== 'connected') return;
    for (const row of rows) {
      // A late HTTP response cannot overwrite a transition received since that read began.
      if ((this.transitions.get(row.otherParticipantSub) ?? 0) <= token.revision) {
        this.realtime.seedPresence(row.otherParticipantSub, row.partnerOnline);
      }
    }
  }

  online(sub: string): boolean | null {
    return this.realtime.connection() === 'connected' ? this.realtime.presence().get(sub) ?? null : null;
  }

  typing(conversationId: string, sub: string): boolean {
    return this.realtime.connection() === 'connected' && !!this.realtime.typing().get(conversationId)?.has(sub);
  }
}
