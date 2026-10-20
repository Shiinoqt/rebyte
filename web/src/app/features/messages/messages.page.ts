import { DatePipe } from '@angular/common';
import { afterNextRender, ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, Injector, signal, untracked, viewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ConversationView, MessageOffer, MessageView } from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { AuthStore } from '../../core/auth/auth.store';
import { Avatar, EurPipe, StatusChip } from '../../shared/ui/content';
import { MoneyInput } from '../../shared/ui/forms';
import { Banner, BusyButton, EmptyState, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { CountBadge, LoadMore } from '../../shared/ui/navigation';
import { MessagingStore } from './messaging.store';
import { RealtimeMessageBridge } from './realtime-message.bridge';
import { TypingPresenceStore } from './typing-presence.store';

@Component({
  selector: 'app-messages-page', standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RealtimeMessageBridge],
  styleUrl: './messages.page.css',
  imports: [RouterLink, DatePipe, Avatar, EurPipe, StatusChip, MoneyInput, Banner, BusyButton, EmptyState, ErrorState, Skeleton, CountBadge, LoadMore],
  template: `<section class="messages-panel" [class.thread-selected]="store.selectedId()">
    <aside class="inbox" aria-label="Conversations">
      @if (store.selectedId()) { <h2>Messages</h2> } @else { <h1 tabindex="-1">Messages</h1> }
      @for (row of store.conversations(); track row.id) {
        <a class="thread-row" [class.selected]="store.selectedId() === row.id"
          [class.unread]="row.unreadFor > 0" [routerLink]="['/conversations', row.id]"
          [attr.aria-current]="store.selectedId() === row.id ? 'page' : null">
          <span class="participant-avatar"><app-avatar [username]="row.otherParticipantUsername ?? ''" />
            @if (cues.online(row.otherParticipantSub)) { <span class="online-dot" role="img" aria-label="Online"></span> }
          </span>
          <span class="thread-summary"><span class="row-heading"><strong>{{ participant(row) }}</strong>
            <time [attr.datetime]="row.lastMessageAt">{{ relativeTime(row.lastMessageAt) }}</time></span>
            <span class="preview">{{ preview(row) }}</span></span>
          <app-count-badge [count]="row.unreadFor" label="unread messages" />
        </a>
      }
      @if (store.inboxLoading()) {
        <div role="status" aria-label="Loading conversations" aria-busy="true">
          @for (row of [0, 1, 2]; track row) {
            <div class="thread-row" aria-hidden="true">
              <app-skeleton shape="avatar" />
              <div class="thread-summary">
                <app-skeleton shape="line" [style.margin-top]="'calc(-1 * var(--space-sm))'" />
                <app-skeleton shape="line" [style.width.%]="65" [style.margin-top]="'calc(-1 * var(--space-sm))'" />
              </div>
            </div>
          }
        </div>
      }
      @if (store.inboxError()) { <app-error-state title="Conversations could not be loaded" [text]="store.inboxError()" (retry)="store.loadInbox()" /> }
      @else if (store.inboxLoaded() && !store.conversations().length) { <app-empty-state title="No conversations yet" text="Message a seller from a listing to get started." /> }
      <app-load-more [hasMore]="!!store.inboxCursor()" [loading]="store.inboxLoading()" [count]="store.conversations().length" noun="conversations" (load)="store.loadInbox(true)" />
    </aside>
    <div class="conversation-pane">
      @if (store.selectedId()) { <h1 class="visually-hidden" tabindex="-1">Messages</h1> }
      @if (store.threadLoading()) {
        <div class="conversation-header" aria-hidden="true">
          <app-skeleton shape="avatar" />
          <app-skeleton class="conversation-title" shape="line" [style.min-height]="'var(--hit-min)'" />
        </div>
        <div class="history" role="status" aria-label="Loading conversation" aria-busy="true">
          @for (width of [55, 40, 65, 45]; track $index) {
            <div class="message" [class.mine]="$odd" aria-hidden="true">
              <app-skeleton shape="line" [style.width.%]="width" />
            </div>
          }
        </div>
        <div class="composer" aria-hidden="true">
          <app-skeleton shape="line" [style.flex]="1" [style.min-height]="'var(--hit-min)'" />
        </div>
      }
      @else if (store.threadError()) { <app-error-state title="Conversation unavailable" [text]="store.threadError()" (retry)="store.selectConversation(store.selectedId())" /> }
      @else if (store.conversation(); as conversation) {
        <header class="conversation-header">
          <a class="phone-back" routerLink="/conversations" aria-label="Back to messages">
            <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6" /></svg>
          </a>
          <app-avatar [username]="conversation.otherParticipantUsername ?? ''" />
          <div class="conversation-title"><a [routerLink]="['/u', conversation.otherParticipantSub]">{{ participant(conversation) }}</a>
            @if (cues.online(conversation.otherParticipantSub)) { <span class="online-label"><span class="online-dot" aria-hidden="true"></span>Online</span> }
          </div>
          <a class="listing-chip" [routerLink]="['/listings', conversation.listingId]" [attr.aria-label]="'View listing ' + conversation.listingTitle">
            <span class="listing-thumbnail">
              @if (listingPhoto(); as photo) { <img [src]="photo" alt="" (error)="listingPhoto.set(null)" /> }
              @else { <svg class="listing-placeholder" aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><circle cx="8" cy="8" r="1" /><path d="m3 17 6-6 4 4 3-3 5 5" /></svg> }
            </span>
            <span class="listing-summary"><span class="listing-title">{{ conversation.listingTitle }}</span><span class="listing-price">{{ conversation.listingPrice | eur }}</span></span>
          </a>
        </header>
        <div #history class="history" aria-label="Message history" tabindex="0" (scroll)="onScroll()" [attr.aria-busy]="store.olderLoading()">
          @if (store.historyCursor()) {
            <app-busy-button class="older-control" variant="neutral" [label]="store.olderLoading() ? 'Loading earlier messages...' : 'Load earlier messages'" [busy]="store.olderLoading()" (pressed)="loadOlder()" />
          }
          @if (store.olderError()) { <app-banner kind="error" [text]="store.olderError()" actionLabel="Try again" (action)="loadOlder()" /> }
          @for (message of store.messages(); track message.id) {
            <article class="message" [class.mine]="message.senderSub === auth.identity()?.sub" [attr.data-message-id]="message.id">
              @if (message.kind === 'OFFER' && card(message); as offer) {
                <div class="offer-card">
                  <span class="offer-label">Offer</span>
                  <div class="offer-price"><strong>{{ offer.amount | eur }}</strong><s>{{ offer.askingPrice | eur }}</s></div>
                  <app-status-chip [text]="offerStatus(offer.status)" [tone]="offer.status === 'REJECTED' ? 'danger' : offer.status === 'PENDING' || offer.status === 'ACCEPTED' ? 'accent' : 'neutral'" />
                  @if (offer.rejectionReason) { <p class="offer-reason">{{ rejectionReason(offer.rejectionReason) }}</p> }
                  @if (offer.status === 'PENDING') {
                    <div class="offer-actions">
                      @if (auth.identity()?.sub === conversation.sellerSub) {
                        <app-busy-button label="Accept" [busy]="store.offerBusy() === offer.offerId" [disabled]="!!store.offerBusy()" (pressed)="offerAction('accept', offer.offerId)" />
                        <app-busy-button label="Decline" variant="neutral" [disabled]="!!store.offerBusy()" (pressed)="offerAction('reject', offer.offerId)" />
                      } @else if (auth.identity()?.sub === conversation.buyerSub) {
                        <app-busy-button label="Withdraw" variant="neutral" [busy]="store.offerBusy() === offer.offerId" [disabled]="!!store.offerBusy()" (pressed)="offerAction('withdraw', offer.offerId)" />
                      }
                    </div>
                  } @else if (offer.status === 'ACCEPTED' && auth.identity()?.sub === conversation.buyerSub) {
                    @if (offer.expiresAt && remaining(offer.expiresAt) > 0) {
                      <a class="checkout-link" [routerLink]="['/checkout', conversation.listingId]" [queryParams]="{offer: offer.offerId}">Checkout at {{ offer.amount | eur }}</a>
                      <p class="countdown" role="timer">{{ countdown(offer.expiresAt) }}</p>
                    } @else { <p class="offer-reason">This offer has expired.</p> }
                  }
                </div>
              } @else if (message.kind === 'CHAT') { <div class="bubble">@if (message.text) { <span>{{ message.text }}</span> }
                @if (message.images.length) { <div class="image-grid">
                  @for (image of message.images; track image.url) { <a [href]="image.url" target="_blank" rel="noopener noreferrer"><img class="message-image" [src]="image.url" alt="Message attachment" /></a> }
                </div> }
              </div> }
              <time [attr.datetime]="message.createdAt">{{ message.createdAt | date:'HH:mm' }}</time>
            </article>
          }
          @if (!store.messages().length) { <p class="empty-thread">No messages yet.</p> }
          @if (isTyping()) {
            <div class="message" aria-hidden="true"><div class="bubble typing-bubble"><span></span><span></span><span></span></div></div>
          }
          <p class="visually-hidden" aria-live="polite">{{ isTyping() ? participant(conversation) + ' is typing' : '' }}</p>
        </div>
        @if (newMessages()) { <button class="new-messages" (click)="scrollToNewest()">New messages</button> }
        @if (store.refreshError()) { <app-banner kind="error" [text]="store.refreshError()" actionLabel="Refresh messages" (action)="refresh()" /> }
        @if (store.offerError()) { <app-banner kind="error" [text]="store.offerError()" /> }
        @if (offerOpen() && canOffer()) {
          <form class="offer-form" (submit)="$event.preventDefault(); createOffer()">
            <app-money-input id="message-offer-amount" label="Your offer" [required]="true" [value]="offerAmount()" (valueChange)="offerAmount.set($event)" [error]="offerValidation()" />
            <app-busy-button label="Send offer" type="submit" [busy]="store.offerBusy() === 'create'" />
            <app-busy-button label="Cancel" variant="neutral" (pressed)="offerOpen.set(false)" />
          </form>
        }
        @if (store.sendError()) { <app-banner kind="error" [text]="store.sendError()" /> }
        @if (store.imageError()) { <app-banner kind="error" [text]="store.imageError()" /> }
        @if (store.attachments().length) {
          <div class="attachments" aria-label="Photos to send">
            @for (photo of store.attachments(); track photo.id) {
              <div class="attachment">
                @if (photo.url) { <img [src]="photo.url" [alt]="photo.name" /> }
                <span class="attachment-name">{{ photo.name }}</span>
                @if (photo.status === 'processing') { <span role="status">Preparing photo...</span> }
                @if (photo.status === 'uploading') { <progress [value]="photo.progress" max="100" [attr.aria-label]="'Uploading ' + photo.name"></progress> }
                @if (photo.error) { <span role="alert">{{ photo.error }}</span><button type="button" (click)="store.retryImage(photo.id)" [disabled]="store.sending()">Retry photo</button> }
                <button type="button" [attr.aria-label]="'Remove ' + photo.name" (click)="store.removeImage(photo.id)" [disabled]="store.sending()">Remove</button>
              </div>
            }
          </div>
        }
        <form class="composer" (submit)="$event.preventDefault(); send()">
          <input #imageInput type="file" hidden multiple accept="image/jpeg,image/png,image/webp" (change)="chooseImages($event)" />
          <button class="attach-button" type="button" aria-label="Attach photos" title="Attach photos" [disabled]="store.sending() || store.attachments().length >= 3" (click)="imageInput.click()">
            <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="m21 11-8.5 8.5a6 6 0 0 1-8.5-8.5l9-9a4 4 0 0 1 5.7 5.7l-9 9a2 2 0 0 1-2.9-2.9L15 6" /></svg>
          </button>
          @if (canOffer()) { <button class="attach-button" type="button" aria-label="Make an offer" title="Make an offer" [attr.aria-expanded]="offerOpen()" (click)="offerOpen.set(!offerOpen())">
            <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M4 10h12M4 14h9M19 6a7.7 7.7 0 0 0-5.2-2A7.9 7.9 0 0 0 6 12c0 4.4 3.5 8 7.8 8 2 0 3.8-.8 5.2-2" /></svg>
          </button> }
          <textarea aria-label="Write a message" placeholder="Write a message" rows="1" [value]="store.draft()"
            [disabled]="store.sending()" [attr.aria-describedby]="store.sendError() ? 'send-help' : null"
            (input)="draftChanged($any($event.target).value)" (keydown)="composerKey($event)"></textarea>
          <app-busy-button data-action="send" label="Send" type="submit" [busy]="store.sending()"
            [disabled]="imagesPending() || (!store.draft().trim() && !store.imageKeys().length)" />
        </form>
        @if (store.sendError()) { <p class="visually-hidden" id="send-help">{{ store.sendError() }}</p> }
      } @else { <app-empty-state title="Your messages" text="Choose a conversation to read your messages." /> }
    </div>
  </section>`,
})
export class MessagesPage {
  readonly store = inject(MessagingStore);
  readonly bridge = inject(RealtimeMessageBridge);
  readonly cues = inject(TypingPresenceStore);
  readonly auth = inject(AuthStore);
  private readonly catalog = inject(CatalogApi);
  private readonly listingId = computed(() => this.store.conversation()?.listingId ?? null);
  readonly listingPhoto = signal<string | null>(null);
  readonly isTyping = computed(() => {
    const conversation = this.store.conversation();
    return !!conversation && this.cues.typing(conversation.id, conversation.otherParticipantSub);
  });
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly destroy = inject(DestroyRef);
  private selectionVersion = 0;
  private readonly history = viewChild<ElementRef<HTMLElement>>('history');
  readonly newMessages = signal(false);
  readonly offerOpen = signal(false);
  readonly offerAmount = signal('');
  readonly offerValidation = signal('');
  readonly now = signal(Date.now());
  constructor() {
    effect((onCleanup) => {
      const id = this.listingId();
      this.listingPhoto.set(null);
      if (!id) return;
      const request = this.catalog.listing(id).subscribe({
        next: (listing) => { if (this.listingId() === id) this.listingPhoto.set(listing.photos[0] || null); },
        error: () => { if (this.listingId() === id) this.listingPhoto.set(null); },
      });
      onCleanup(() => request.unsubscribe());
    });
    effect(() => {
      if (this.isTyping()) untracked(() => { void this.preservingPosition(() => Promise.resolve()); });
    });
    this.bridge.bind((command) => this.preservingPosition(command));
    const navigationDraft = inject(Router).currentNavigation()?.extras.state?.['draft'];
    let pendingDraft = typeof navigationDraft === 'string' ? navigationDraft : null;
    const destroy = inject(DestroyRef);
    const timer = setInterval(() => this.now.set(Date.now()), 30_000);
    destroy.onDestroy(() => clearInterval(timer));
    destroy.onDestroy(() => { void this.store.selectConversation(null); });
    void this.store.loadInbox();
    this.route.paramMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      this.newMessages.set(false); this.offerOpen.set(false); this.offerAmount.set(''); this.offerValidation.set('');
      const id = params.get('id');
      const version = ++this.selectionVersion;
      void this.store.selectConversation(id).then(() => {
        if (id && version === this.selectionVersion && this.store.selectedId() === id) {
          if (pendingDraft !== null && this.store.conversation()) { this.store.setDraft(pendingDraft); pendingDraft = null; }
          this.scrollToNewest();
        }
      });
    });
  }
  participant(row: ConversationView): string { return row.otherParticipantUsername ? '@' + row.otherParticipantUsername : 'Participant'; }
  preview(row: ConversationView): string {
    const last = row.lastMessage;
    return last?.preview || (last?.imageCount ? 'Photo' : last?.kind === 'OFFER' ? 'Offer' : '');
  }
  relativeTime(at: string | null): string {
    if (!at) return '';
    const minutes = Math.max(0, Math.floor((this.now() - Date.parse(at)) / 60_000));
    if (!Number.isFinite(minutes)) return '';
    return minutes < 1 ? 'now' : minutes < 60 ? `${minutes}m` : minutes < 1440 ? `${Math.floor(minutes / 60)}h` : `${Math.floor(minutes / 1440)}d`;
  }
  private afterRender(callback: (element: HTMLElement) => void, id = this.store.selectedId()): void {
    if (this.destroy.destroyed) return;
    const version = this.selectionVersion;
    afterNextRender({ mixedReadWrite: () => {
      const element = this.history()?.nativeElement;
      if (element && version === this.selectionVersion && this.store.selectedId() === id) callback(element);
    } }, { injector: this.injector });
  }
  scrollToNewest(): void {
    this.newMessages.set(false);
    this.afterRender((element) => { element.scrollTop = element.scrollHeight; });
  }
  async loadOlder(): Promise<void> {
    const element = this.history()?.nativeElement;
    if (!element || this.store.olderLoading()) return;
    const height = element.scrollHeight; const top = element.scrollTop; const id = this.store.selectedId();
    const version = this.selectionVersion;
    if (await this.store.loadOlder() && version === this.selectionVersion) this.afterRender((current) => {
      current.scrollTop = top + current.scrollHeight - height;
    }, id);
  }
  onScroll(): void {
    const element = this.history()?.nativeElement;
    if (!element) return;
    if (element.scrollHeight - element.clientHeight - element.scrollTop <= 64) this.newMessages.set(false);
    if (element.scrollTop <= 4 && !this.store.threadLoading()) void this.loadOlder();
  }
  private async preservingPosition(command: () => Promise<unknown>): Promise<void> {
    const element = this.history()?.nativeElement; const id = this.store.selectedId();
    const top = element?.scrollTop ?? 0;
    const nearBottom = !!element && element.scrollHeight - element.clientHeight - top <= 64;
    const latest = this.store.messages().at(-1)?.seq ?? 0;
    const version = this.selectionVersion;
    await command();
    if (this.destroy.destroyed || version !== this.selectionVersion || id !== this.store.selectedId()) return;
    if ((this.store.messages().at(-1)?.seq ?? 0) > latest && !nearBottom) this.newMessages.set(true);
    if (nearBottom) this.afterRender((current) => {
      if (Math.abs(current.scrollTop - top) <= 4) current.scrollTop = current.scrollHeight;
    }, id);
  }
  send(): Promise<void> { this.bridge.cancelTyping(); return this.preservingPosition(() => this.store.send()); }
  draftChanged(value: string): void { this.store.setDraft(value); this.bridge.typing(); }
  imagesPending(): boolean { return this.store.attachments().some((photo) => photo.status !== 'ready'); }
  chooseImages(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []); input.value = '';
    void this.store.addImages(files);
  }
  refresh(): Promise<void> { return this.preservingPosition(() => this.store.refreshHistory()); }
  composerKey(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault(); void this.send();
    }
  }
  card(message: MessageView): MessageOffer | null {
    if (!message.offer) return null;
    const current = this.store.offers()[message.offer.offerId];
    return current ? { ...message.offer, status: current.status, amount: current.amount, askingPrice: current.askingPrice,
      rejectionReason: current.rejectionReason, expiresAt: current.expiresAt } : message.offer;
  }
  offerStatus(status: string): string { return ({ PENDING: 'Pending', ACCEPTED: 'Accepted', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn', EXPIRED: 'Expired' } as Record<string, string>)[status] ?? status; }
  rejectionReason(reason: string): string {
    return ({ SELLER: 'The seller declined this offer.', OTHER_ACCEPTED: 'Another offer was accepted.', PRICE_CHANGED: 'The asking price changed.', LISTING_UNAVAILABLE: 'The listing is no longer available.' } as Record<string, string>)[reason] ?? 'This offer was declined.';
  }
  canOffer(): boolean {
    const conversation = this.store.conversation();
    return conversation?.listingType === 'HARDWARE' && conversation.buyerSub === this.auth.identity()?.sub;
  }
  offerAction(action: 'accept' | 'reject' | 'withdraw', id: string): Promise<void> {
    return this.preservingPosition(() => this.store.offerCommand(action, id));
  }
  async createOffer(): Promise<void> {
    const amount = this.offerAmount(); const asking = this.store.conversation()?.listingPrice;
    const cents = (value: string) => { const [whole, decimals = ''] = value.split('.'); return BigInt(whole) * 100n + BigInt(decimals.padEnd(2, '0')); };
    if (!this.canOffer() || !/^\d+(\.\d{1,2})?$/.test(amount) || !asking || cents(amount) <= 0n || cents(amount) >= cents(asking)) {
      this.offerValidation.set('Enter an amount greater than zero and below the asking price.'); return;
    }
    this.offerValidation.set('');
    await this.preservingPosition(async () => { if (await this.store.offerCommand('create', amount)) { this.offerOpen.set(false); this.offerAmount.set(''); } });
  }
  remaining(expiresAt: string): number { return Math.max(0, Date.parse(expiresAt) - this.now()); }
  countdown(expiresAt: string): string {
    const minutes = Math.ceil(this.remaining(expiresAt) / 60_000);
    return `${Math.floor(minutes / 60)}h ${minutes % 60}m left`;
  }
}
