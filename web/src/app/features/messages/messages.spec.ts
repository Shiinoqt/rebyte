import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConversationView, ListingDetailResponse, MessagePage, MessageView, OfferView } from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { MessagingApi } from '../../core/api/messaging-api';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { RealtimeStore } from '../../core/realtime/realtime.store';
import { MESSAGES_ROUTES } from './messages.routes';
import { MessagesPage } from './messages.page';
import { MessagingStore } from './messaging.store';

const time = '2026-10-09T12:00:00Z';
function conversation(id = 'c1', changes: Partial<ConversationView> = {}): ConversationView {
  return { id, listingId: 'l1', listingType: 'HARDWARE', listingTitle: 'ThinkPad X1',
    listingPrice: '499.00', buyerSub: 'buyer', sellerSub: 'seller', otherParticipantSub: 'seller',
    otherParticipantUsername: 'alex', lastMessage: null, lastMessageAt: time, unreadFor: 2,
    partnerOnline: false, createdAt: time, ...changes };
}
function message(seq: number, changes: Partial<MessageView> = {}): MessageView {
  return { id: 'm' + seq, conversationId: 'c1', seq, senderSub: 'seller', kind: 'CHAT',
    text: 'Message ' + seq, offer: null, images: [], createdAt: time, ...changes };
}
let cleanup: () => void;
let catalog: { listing: ReturnType<typeof vi.fn> };
let api: { conversations: ReturnType<typeof vi.fn>; conversation: ReturnType<typeof vi.fn>;
  messages: ReturnType<typeof vi.fn>; sendMessage: ReturnType<typeof vi.fn> };
function offer(changes: Partial<OfferView> = {}): OfferView {
  return { id: 'of1', listingId: 'l1', listingTitle: 'ThinkPad X1', buyerSub: 'buyer', sellerSub: 'seller',
    amount: '400.00', askingPrice: '499.00', status: 'PENDING', rejectionReason: null,
    expiresAt: null, orderId: null, createdAt: time, ...changes };
}
function cardMessage(seq = 4, changes: Partial<MessageView> = {}): MessageView {
  return message(seq, { kind: 'OFFER', text: null, offer: { offerId: 'of1', event: 'CREATED', status: 'PENDING',
    amount: '400.00', askingPrice: '499.00', expiresAt: null, rejectionReason: null }, ...changes });
}
let orders: { offer: ReturnType<typeof vi.fn>; createOffer: ReturnType<typeof vi.fn>; acceptOffer: ReturnType<typeof vi.fn>;
  rejectOffer: ReturnType<typeof vi.fn>; withdrawOffer: ReturnType<typeof vi.fn> };
function configure() { cleanup = () => {};
  catalog = { listing: vi.fn(() => of({ photos: [] })) };
  api = { conversations: vi.fn(() => of({ items: [conversation()], nextCursor: null })),
    conversation: vi.fn((id: string) => of(conversation(id))),
    messages: vi.fn(() => of({ items: [message(3), message(2), message(1)], nextCursor: null })),
    sendMessage: vi.fn(() => of(message(4, { senderSub: 'buyer' }))) };
  orders = { offer: vi.fn(() => of(offer())), createOffer: vi.fn(() => of(offer())),
    acceptOffer: vi.fn(() => of(offer({ status: 'ACCEPTED' }))), rejectOffer: vi.fn(() => of(offer({ status: 'REJECTED' }))),
    withdrawOffer: vi.fn(() => of(offer({ status: 'WITHDRAWN' }))) };
  TestBed.configureTestingModule({ providers: [MessagingStore,
    provideRouter([{ path: 'conversations', children: MESSAGES_ROUTES }]),
    { provide: MessagingApi, useValue: api },
    { provide: CatalogApi, useValue: catalog },
    { provide: RealtimeService, useValue: { sendTyping: vi.fn() } },
    { provide: OrderApi, useValue: orders },
    { provide: AuthStore, useValue: { identity: signal({ sub: 'buyer', groups: [] }),
      registerCleanup: vi.fn((hook: () => void) => { const prior = cleanup; cleanup = () => { prior?.(); hook(); }; return vi.fn(); }) } },
  ] });
}
beforeEach(configure);
afterEach(() => TestBed.resetTestingModule());

describe('Messaging routes', () => {
  it('reserves inbox rows and the header, history and composer while loading', async () => {
    const inbox = new Subject<{ items: ConversationView[]; nextCursor: null }>();
    const thread = new Subject<ConversationView>();
    api.conversations.mockReturnValue(inbox);
    api.conversation.mockReturnValue(thread);
    const harness = await RouterTestingHarness.create('/conversations/c1');
    await harness.fixture.whenStable(); harness.detectChanges();
    const root = harness.routeNativeElement!;
    expect(root.querySelectorAll('.inbox [aria-label="Loading conversations"] .thread-row')).toHaveLength(3);
    expect(root.querySelector('.conversation-pane > .conversation-header app-skeleton[shape="avatar"]')).not.toBeNull();
    expect(root.querySelectorAll('.history[aria-label="Loading conversation"] .message')).toHaveLength(4);
    expect(root.querySelector('.conversation-pane > .composer app-skeleton')).not.toBeNull();
    expect(root.querySelector('textarea')).toBeNull();

    inbox.next({ items: [conversation()], nextCursor: null }); inbox.complete();
    thread.next(conversation()); thread.complete();
    await new Promise((resolve) => setTimeout(resolve));
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(root.querySelector('[aria-label="Loading conversations"]')).toBeNull();
    expect(root.querySelector('[aria-label="Loading conversation"]')).toBeNull();
    expect(root.querySelector('.conversation-pane > .conversation-header')).not.toBeNull();
    expect(root.querySelector('.conversation-pane > .history')).not.toBeNull();
    expect(root.querySelector('.conversation-pane > .composer textarea')).not.toBeNull();
  });

  it('renders the messaging screen at both canonical inbox and emailed thread paths', () => {
    expect(MESSAGES_ROUTES.map((route) => route.path)).toEqual(['', ':id']);
    expect(MESSAGES_ROUTES.map((route) => route.component)).toEqual([MessagesPage, MessagesPage]);
  });
  it('loads a directly emailed thread route and renders oldest first', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/conversations/c1', MessagesPage);
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(page.store.selectedId()).toBe('c1');
    expect(api.messages).toHaveBeenCalledWith('c1', { size: 20 });
    expect([...harness.routeNativeElement!.querySelectorAll('[data-message-id]')].map((node) => node.getAttribute('data-message-id'))).toEqual(['m1', 'm2', 'm3']);
    expect(harness.routeNativeElement!.querySelector('.phone-back')?.getAttribute('aria-label')).toBe('Back to messages');
  });
  it('restores selection after a page reload from the same URL', async () => {
    const first = await RouterTestingHarness.create('/conversations/c1');
    await first.fixture.whenStable();
    expect(api.conversation).toHaveBeenCalledWith('c1');
    TestBed.resetTestingModule(); configure();
    const second = await RouterTestingHarness.create('/conversations/c1');
    await second.fixture.whenStable();
    expect(api.conversation).toHaveBeenCalledWith('c1');
    second.routeNativeElement!.querySelector<HTMLButtonElement>('.phone-back')!.click();
    await second.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/conversations');
  });
  it('lets a reader send a message from the selected thread composer', async () => {
    const harness = await RouterTestingHarness.create('/conversations/c1');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('textarea[aria-label="Write a message"]')).not.toBeNull();
    expect(harness.routeNativeElement!.querySelector('[data-action="send"]')).not.toBeNull();
  });
  it('links the listing chip with its first photo and falls back on failed or superseded requests', async () => {
    catalog.listing.mockReturnValueOnce(of({ photos: ['/first.webp', '/second.webp'] }));
    const harness = await RouterTestingHarness.create('/conversations/c1');
    await harness.fixture.whenStable(); harness.detectChanges();
    const chip = () => harness.routeNativeElement!.querySelector<HTMLAnchorElement>('.listing-chip')!;
    expect(catalog.listing).toHaveBeenCalledWith('l1');
    expect(chip().getAttribute('href')).toBe('/listings/l1');
    expect(chip().getAttribute('aria-label')).toBe('View listing ThinkPad X1');
    expect(chip().querySelector('img')?.getAttribute('src')).toBe('/first.webp');
    chip().querySelector('img')!.dispatchEvent(new Event('error'));
    harness.detectChanges();
    expect(chip().querySelector('.listing-placeholder')).not.toBeNull();

    const pending = new Subject<ListingDetailResponse>();
    catalog.listing.mockReturnValueOnce(pending).mockReturnValueOnce(throwError(() => ({ status: 404 })));
    api.conversation.mockImplementation((id: string) => of(conversation(id, { listingId: id })));
    await harness.navigateByUrl('/conversations/c2');
    await harness.fixture.whenStable(); harness.detectChanges();
    await harness.navigateByUrl('/conversations/c3');
    await harness.fixture.whenStable(); harness.detectChanges();
    pending.next({ photos: ['/stale.webp'] } as ListingDetailResponse);
    harness.detectChanges();
    expect(chip().getAttribute('href')).toBe('/listings/c3');
    expect(chip().querySelector('img')).toBeNull();
    expect(chip().querySelector('.listing-placeholder')).not.toBeNull();
  });
  it('renders the inbox and canonical row links', async () => {
    const harness = await RouterTestingHarness.create('/conversations');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('.thread-row')?.getAttribute('href')).toBe('/conversations/c1');
    expect(harness.routeNativeElement!.textContent).toContain('@alex');
  });
  it('loads the next inbox page through the shared Show more control', async () => {
    api.conversations.mockReturnValueOnce(of({ items: [conversation()], nextCursor: 'next' }))
      .mockReturnValueOnce(of({ items: [conversation('c2')], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/conversations');
    await harness.fixture.whenStable(); harness.detectChanges();
    harness.routeNativeElement!.querySelector<HTMLButtonElement>('app-load-more button')!.click();
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(api.conversations).toHaveBeenLastCalledWith({ size: 20, cursor: 'next' });
    expect(harness.routeNativeElement!.querySelectorAll('.thread-row')).toHaveLength(2);
  });
  it('renders empty inbox and retryable error states', async () => {
    api.conversations.mockReturnValue(of({ items: [], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/conversations');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('No conversations yet');
    api.conversation.mockReturnValue(throwError(() => ({ status: 404, code: 'conversation-not-found' })));
    await harness.navigateByUrl('/conversations/missing');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('This conversation is no longer available.');
  });
  it('does not invent a participant username when the DTO has none', async () => {
    api.conversations.mockReturnValue(of({ items: [conversation('c1', { otherParticipantUsername: null })], nextCursor: null }));
    const harness = await RouterTestingHarness.create('/conversations');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Participant');
    expect(harness.routeNativeElement!.textContent).not.toContain('@seller');
  });
});

describe('Messaging inbox and selection', () => {
  it('appends cursor pages and deduplicates conversation IDs', async () => {
    const store = TestBed.inject(MessagingStore);
    api.conversations.mockReturnValueOnce(of({ items: [conversation()], nextCursor: 'next' }))
      .mockReturnValueOnce(of({ items: [conversation(), conversation('c2')], nextCursor: null }));
    await store.loadInbox(); await store.loadInbox(true);
    expect(store.conversations().map((row) => row.id)).toEqual(['c1', 'c2']);
    expect(api.conversations).toHaveBeenLastCalledWith({ size: 20, cursor: 'next' });
  });
  it('guards in-flight pages and stops at null or blank inbox cursors', async () => {
    const store = TestBed.inject(MessagingStore); const pending = new Subject<{ items: ConversationView[]; nextCursor: string | null }>();
    api.conversations.mockReturnValue(pending);
    const request = store.loadInbox(); await store.loadInbox();
    expect(api.conversations).toHaveBeenCalledTimes(1);
    pending.next({ items: [], nextCursor: ' ' }); await request; await store.loadInbox(true);
    expect(api.conversations).toHaveBeenCalledTimes(1);
  });
  it('opens with no cursor, marks read and reverses API history by seq', async () => {
    const store = TestBed.inject(MessagingStore); await store.loadInbox(); await store.selectConversation('c1');
    expect(api.messages).toHaveBeenCalledWith('c1', { size: 20 });
    expect(store.messages().map((row) => row.seq)).toEqual([1, 2, 3]);
    expect(store.conversation()?.unreadFor).toBe(0); expect(store.conversations()[0].unreadFor).toBe(0);
  });
  it('deduplicates first-page messages and rejects blank next cursors', async () => {
    api.messages.mockReturnValue(of({ items: [message(2), message(1), message(2)], nextCursor: '' }));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    expect(store.messages().map((row) => row.id)).toEqual(['m1', 'm2']); expect(store.historyCursor()).toBeNull();
  });
  it('discards superseded thread results during fast route changes', async () => {
    const pending = new Subject<MessagePage>(); api.messages.mockReturnValueOnce(pending);
    const store = TestBed.inject(MessagingStore); const older = store.selectConversation('c1');
    api.messages.mockReturnValue(of({ items: [message(8, { conversationId: 'c2' })], nextCursor: null }));
    await store.selectConversation('c2'); pending.next({ items: [message(1)], nextCursor: null }); await older;
    expect(store.conversation()?.id).toBe('c2'); expect(store.messages()[0].conversationId).toBe('c2');
  });
  it('clears private history on logout and ignores outstanding responses', async () => {
    const pending = new Subject<MessagePage>(); api.messages.mockReturnValue(pending);
    const store = TestBed.inject(MessagingStore); const request = store.selectConversation('c1'); cleanup();
    pending.next({ items: [message(1)], nextCursor: null }); await request;
    expect(store.messages()).toEqual([]); expect(store.selectedId()).toBeNull(); expect(store.conversations()).toEqual([]);
  });
  it('clears stale private thread content on an unauthorized or missing route', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    api.conversation.mockReturnValue(throwError(() => ({ status: 404, code: 'conversation-not-found' })));
    await store.selectConversation('missing');
    expect(store.messages()).toEqual([]); expect(store.conversation()).toBeNull(); expect(store.threadError()).toContain('no longer available');
  });
});

describe('Older history and outgoing commands', () => {
  it('prepends multiple reversed pages and deduplicates overlap by message id', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(6), message(5)], nextCursor: '5' }))
      .mockReturnValueOnce(of({ items: [message(5), message(4), message(3)], nextCursor: '3' }))
      .mockReturnValueOnce(of({ items: [message(2), message(1)], nextCursor: null }));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    await store.loadOlder(); await store.loadOlder(); await store.loadOlder();
    expect(store.messages().map((row) => row.seq)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(api.messages.mock.calls).toEqual([['c1', { size: 20 }], ['c1', { size: 20, cursor: '5' }], ['c1', { size: 20, cursor: '3' }]]);
  });
  it('continues after an empty page with a distinct cursor and stops repeated or blank cursors', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(8)], nextCursor: '8' }))
      .mockReturnValueOnce(of({ items: [], nextCursor: '4' }))
      .mockReturnValueOnce(of({ items: [message(3)], nextCursor: '4' }));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    await store.loadOlder(); expect(store.historyCursor()).toBe('4');
    await store.loadOlder(); expect(store.historyCursor()).toBeNull();
    await store.loadOlder(); expect(api.messages).toHaveBeenCalledTimes(3);
  });
  it('guards older requests in flight and ignores late older pages after route changes', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(4)], nextCursor: '4' }));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    const pending = new Subject<MessagePage>(); api.messages.mockReturnValueOnce(pending);
    const request = store.loadOlder(); await store.loadOlder();
    expect(api.messages).toHaveBeenCalledTimes(2);
    await store.selectConversation('c2'); pending.next({ items: [message(1)], nextCursor: null });
    expect(await request).toBe(false); expect(store.selectedId()).toBe('c2'); expect(store.olderLoading()).toBe(false);
  });
  it('keeps older pages and the oldest cursor during first-page refresh', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(5), message(4)], nextCursor: '4' }))
      .mockReturnValueOnce(of({ items: [message(3), message(2)], nextCursor: '2' }))
      .mockReturnValueOnce(of({ items: [message(6), message(5)], nextCursor: '5' }));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1'); await store.loadOlder(); await store.refreshHistory();
    expect(store.messages().map((row) => row.seq)).toEqual([2, 3, 4, 5, 6]); expect(store.historyCursor()).toBe('2');
  });
  it('retains history and cursor when loading older fails and allows retry', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(3)], nextCursor: '3' }))
      .mockReturnValueOnce(throwError(() => ({ status: 0, code: 'offline' })))
      .mockReturnValueOnce(of({ items: [message(2)], nextCursor: null }));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    expect(await store.loadOlder()).toBe(false); expect(store.olderError()).toContain('offline'); expect(store.historyCursor()).toBe('3');
    await store.loadOlder(); expect(store.messages().map((row) => row.seq)).toEqual([2, 3]);
  });
  async function openWithDraftAndOffer() {
    api.messages.mockReturnValueOnce(of({ items: [cardMessage(4), message(3)], nextCursor: '3' }));
    const store = TestBed.inject(MessagingStore); await store.loadInbox(); await store.selectConversation('c1');
    store.setDraft('private draft', ['private/key']);
    expect(store.offers()['of1']).toBeDefined();
    return store;
  }
  function expectThreadDropped(store: InstanceType<typeof MessagingStore>) {
    expect(store.conversation()).toBeNull(); expect(store.messages()).toEqual([]); expect(store.historyCursor()).toBeNull();
    expect(store.draft()).toBe(''); expect(store.imageKeys()).toEqual([]); expect(store.attachments()).toEqual([]);
    expect(store.offers()).toEqual({}); expect(store.offerBusy()).toBe('');
    expect(store.refreshing()).toBe(false); expect(store.refreshError()).toBe(''); expect(store.olderLoading()).toBe(false); expect(store.olderError()).toBe('');
    expect(store.conversations().some((row) => row.id === 'c1')).toBe(false); expect(store.selectedId()).toBe('c1');
    expect(store.threadError()).toContain('no longer available');
  }
  it('drops private thread state when a history refresh finds the conversation missing', async () => {
    const store = await openWithDraftAndOffer();
    api.conversation.mockReturnValue(throwError(() => ({ status: 404, code: 'conversation-not-found' })));
    await store.refreshHistory(); expectThreadDropped(store);
  });
  it('drops private thread state when a history refresh is forbidden', async () => {
    const store = await openWithDraftAndOffer();
    api.messages.mockReturnValue(throwError(() => ({ status: 403, code: 'forbidden' })));
    await store.refreshHistory(); expect(store.threadError()).not.toBe(''); expect(store.conversation()).toBeNull();
    expect(store.messages()).toEqual([]); expect(store.draft()).toBe(''); expect(store.offers()).toEqual({});
    expect(store.conversations().some((row) => row.id === 'c1')).toBe(false);
  });
  it('drops private thread state when an older page is gone', async () => {
    const store = await openWithDraftAndOffer();
    api.messages.mockReturnValue(throwError(() => ({ status: 404, code: 'conversation-not-found' })));
    expect(await store.loadOlder()).toBe(false); expectThreadDropped(store);
  });
  it('keeps the thread and reports refreshError on a transient history refresh failure', async () => {
    const store = await openWithDraftAndOffer();
    api.messages.mockReturnValue(throwError(() => ({ status: 0, code: 'offline' })));
    await store.refreshHistory();
    expect(store.refreshError()).toContain('offline'); expect(store.conversation()?.id).toBe('c1');
    expect(store.messages().length).toBe(2); expect(store.draft()).toBe('private draft'); expect(store.threadError()).toBe('');
  });
  it('sends only exact text/imageKeys and merges the authoritative response by id', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    api.sendMessage.mockReturnValue(of(message(3, { senderSub: 'buyer', text: 'updated payload' })));
    store.setDraft(' exact text\n', ['private/key']); expect(await store.send()).toBe(true);
    expect(api.sendMessage).toHaveBeenCalledWith('c1', { text: ' exact text\n', imageKeys: ['private/key'] });
    expect(store.messages()).toHaveLength(3); expect(store.messages().at(-1)?.text).toBe('updated payload'); expect(store.draft()).toBe('');
  });
  it('retains failed text and image keys with the chat rate-limit note', async () => {
    api.sendMessage.mockReturnValue(throwError(() => ({ status: 429, code: 'rate-limited' })));
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1'); store.setDraft('try again', ['key']);
    expect(await store.send()).toBe(false); expect(store.draft()).toBe('try again'); expect(store.imageKeys()).toEqual(['key']);
    expect(store.sendError()).toBe('You are sending messages too quickly. Wait a moment.'); expect(store.messages()).toHaveLength(3);
  });
  it('rejects blank messages and guards sends in flight', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1'); store.setDraft('   ');
    expect(await store.send()).toBe(false); expect(api.sendMessage).not.toHaveBeenCalled();
    const pending = new Subject<MessageView>(); api.sendMessage.mockReturnValue(pending); store.setDraft('hello');
    const request = store.send(); await store.send(); expect(api.sendMessage).toHaveBeenCalledTimes(1);
    pending.next(message(4)); await request;
  });
  it('does not erase a newer draft or merge a delayed send into another conversation', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    const pending = new Subject<MessageView>(); api.sendMessage.mockReturnValue(pending); store.setDraft('first');
    const request = store.send(); store.setDraft('second'); pending.next(message(4)); await request;
    expect(store.draft()).toBe('second');
    const later = new Subject<MessageView>(); api.sendMessage.mockReturnValue(later);
    const stale = store.send(); await store.selectConversation('c2'); later.next(message(5));
    expect(await stale).toBe(false); expect(store.messages().some((row) => row.id === 'm5')).toBe(false);
  });
  it('creates an offer through OrderApi without a note, chat request or synthetic message', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    expect(await store.offerCommand('create', '400.00')).toBe(true);
    expect(orders.createOffer).toHaveBeenCalledWith({ listingId: 'l1', amount: '400.00' });
    expect(api.sendMessage).not.toHaveBeenCalled(); expect(store.messages().every((row) => row.kind === 'CHAT')).toBe(true);
    expect(store.offers()['of1'].status).toBe('PENDING');
  });
  it.each([['accept', 'acceptOffer', 'ACCEPTED'], ['reject', 'rejectOffer', 'REJECTED'], ['withdraw', 'withdrawOffer', 'WITHDRAWN']] as const)(
    'uses OrderApi for %s and updates offer state without synthesizing a card', async (action, method, status) => {
      const store = TestBed.inject(MessagingStore); await store.selectConversation('c1'); await store.offerCommand(action, 'of1');
      expect(orders[method]).toHaveBeenCalledWith('of1'); expect(store.offers()['of1'].status).toBe(status);
      expect(api.sendMessage).not.toHaveBeenCalled(); expect(store.messages().filter((row) => row.kind === 'OFFER')).toHaveLength(0);
    });
  it('refetches the authoritative offer on conflict and preserves message history', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
    orders.acceptOffer.mockReturnValue(throwError(() => ({ status: 409, code: 'offer-accept-conflict' })));
    orders.offer.mockReturnValue(of(offer({ status: 'REJECTED', rejectionReason: 'OTHER_ACCEPTED' })));
    expect(await store.offerCommand('accept', 'of1')).toBe(false);
    expect(orders.offer).toHaveBeenCalledWith('of1'); expect(store.offers()['of1'].status).toBe('REJECTED'); expect(store.messages()).toHaveLength(3);
  });
  it('deduplicates delayed server offer history and frames while refetching offer state', async () => {
    const store = TestBed.inject(MessagingStore); await store.selectConversation('c1'); await store.offerCommand('create', '400.00');
    api.messages.mockReturnValue(of({ items: [cardMessage(), message(3)], nextCursor: '3' }));
    await store.refreshHistory(); await store.mergeIncoming(cardMessage());
    orders.offer.mockReturnValue(of(offer({ status: 'ACCEPTED' })));
    await store.mergeIncoming(cardMessage(5, { offer: { ...cardMessage().offer!, event: 'ACCEPTED', status: 'ACCEPTED' } }));
    expect(store.messages().map((row) => row.id)).toEqual(['m1', 'm2', 'm3', 'm4', 'm5']);
    expect(store.offers()['of1'].status).toBe('ACCEPTED'); expect(api.sendMessage).not.toHaveBeenCalled();
  });
  describe('waiting for the card an offer command produces', () => {
    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => { vi.useRealTimers(); });

    it('refreshes history until the acting side receives the card, then stops', async () => {
      const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
      await store.offerCommand('create', '400.00');
      expect(store.messages().some((row) => row.kind === 'OFFER')).toBe(false);
      api.messages.mockReturnValue(of({ items: [cardMessage(4), message(3), message(2), message(1)], nextCursor: null }));
      await vi.advanceTimersByTimeAsync(1000);
      expect(store.messages().filter((row) => row.kind === 'OFFER')).toHaveLength(1);
      const reads = api.messages.mock.calls.length;
      await vi.advanceTimersByTimeAsync(10_000);
      expect(api.messages.mock.calls.length).toBe(reads);
    });
    it('expects a new card for a follow-up command on an offer whose first card is already loaded', async () => {
      api.messages.mockReturnValue(of({ items: [cardMessage(4), message(3), message(2), message(1)], nextCursor: null }));
      const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
      await store.offerCommand('reject', 'of1');
      api.messages.mockReturnValue(of({ items: [cardMessage(5, { senderSub: 'seller' }), cardMessage(4), message(3)], nextCursor: null }));
      await vi.advanceTimersByTimeAsync(1000);
      expect(store.messages().filter((row) => row.kind === 'OFFER')).toHaveLength(2);
    });
    it('gives up after a bounded number of refreshes when no card ever arrives', async () => {
      const store = TestBed.inject(MessagingStore); await store.selectConversation('c1');
      await store.offerCommand('create', '400.00');
      await vi.advanceTimersByTimeAsync(30_000);
      const reads = api.messages.mock.calls.length;
      await vi.advanceTimersByTimeAsync(30_000);
      expect(api.messages.mock.calls.length).toBe(reads);
      expect(reads).toBeLessThanOrEqual(8);
    });
  });
  it('retains loaded inbox pages when offer completion refreshes its first page', async () => {
    api.conversations.mockReturnValueOnce(of({ items: [conversation()], nextCursor: 'next' }))
      .mockReturnValueOnce(of({ items: [conversation('c2')], nextCursor: 'older' }))
      .mockReturnValueOnce(of({ items: [conversation('c3'), conversation()], nextCursor: 'next' }));
    const store = TestBed.inject(MessagingStore); await store.loadInbox(); await store.loadInbox(true); await store.selectConversation('c1');
    await store.offerCommand('create', '400.00');
    expect(store.conversations().map((row) => row.id)).toEqual(['c3', 'c1', 'c2']); expect(store.inboxCursor()).toBe('older');
  });
});

describe('Thread viewport and controls', () => {
  async function thread() {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/conversations/c1', MessagesPage);
    await harness.fixture.whenStable(); harness.detectChanges();
    const element = harness.routeNativeElement!.querySelector<HTMLElement>('.history')!;
    return { harness, page, element };
  }
  it('does not show the new-messages control when the connect refresh loads a thread that is still opening', async () => {
    TestBed.inject(RealtimeStore).setConnection('connected');
    const pending = new Subject<MessagePage>(); api.messages.mockReturnValueOnce(pending);
    const harness = await RouterTestingHarness.create('/conversations/c1');
    await new Promise((resolve) => setTimeout(resolve, 100));
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Message 3');
    expect(harness.routeNativeElement!.textContent).not.toContain('New messages');
    pending.next({ items: [], nextCursor: null }); pending.complete();
  });
  it('restores scrollTop by the measured height delta after older messages render', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(3)], nextCursor: '3' }));
    const { harness, page, element } = await thread();
    let height = 1000; Object.defineProperty(element, 'scrollHeight', { get: () => height }); element.scrollTop = 40;
    const pending = new Subject<MessagePage>(); api.messages.mockReturnValue(pending);
    const request = page.loadOlder(); pending.next({ items: [message(2), message(1)], nextCursor: null }); height = 1500;
    await request; await harness.fixture.whenStable(); harness.detectChanges();
    expect(element.scrollTop).toBe(540);
  });
  it('loads older automatically at the top without duplicate in-flight requests', async () => {
    api.messages.mockReturnValueOnce(of({ items: [message(4)], nextCursor: '4' }));
    const { page, element } = await thread(); const pending = new Subject<MessagePage>(); api.messages.mockReturnValue(pending);
    element.scrollTop = 0; page.onScroll(); page.onScroll(); expect(api.messages).toHaveBeenCalledTimes(2);
    pending.next({ items: [], nextCursor: null });
  });
  it('leaves a reader of older messages in place after a send and shows the new-messages control', async () => {
    const { harness, page, element } = await thread();
    Object.defineProperty(element, 'scrollHeight', { value: 1200 }); Object.defineProperty(element, 'clientHeight', { value: 200 }); element.scrollTop = 100;
    page.store.setDraft('hello'); await page.send(); await harness.fixture.whenStable(); harness.detectChanges();
    expect(element.scrollTop).toBe(100); expect(harness.routeNativeElement!.textContent).toContain('New messages');
  });
  it('scrolls to newest after a send only when already near bottom', async () => {
    const { harness, page, element } = await thread();
    Object.defineProperty(element, 'scrollHeight', { value: 1200 }); Object.defineProperty(element, 'clientHeight', { value: 200 }); element.scrollTop = 1000;
    page.store.setDraft('hello'); await page.send(); await harness.fixture.whenStable(); harness.detectChanges();
    expect(element.scrollTop).toBe(1200); expect(page.newMessages()).toBe(false);
  });
  it.each([100, 1000])('announces typing and preserves the reader position at scrollTop %s', async (top) => {
    const { harness, element } = await thread();
    const realtime = TestBed.inject(RealtimeStore);
    realtime.setConnection('connected');
    await harness.fixture.whenStable(); harness.detectChanges();
    Object.defineProperty(element, 'scrollHeight', { get: () => element.querySelector('.typing-bubble') ? 1240 : 1200 });
    Object.defineProperty(element, 'clientHeight', { value: 200 }); element.scrollTop = top;
    realtime.receive('typing', { conversationId: 'c1', userSub: 'seller', ttlMs: 5000 });
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(element.querySelectorAll('.typing-bubble span')).toHaveLength(3);
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toBe('@alex is typing');
    expect(element.scrollTop).toBe(top === 1000 ? 1240 : top);
    realtime.setConnection('reconnecting');
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(element.querySelector('.typing-bubble')).toBeNull();
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toBe('');
  });
  it('keeps scroll position during an asynchronous offer card history refresh', async () => {
    const { harness, page, element } = await thread();
    Object.defineProperty(element, 'scrollHeight', { value: 1200 }); Object.defineProperty(element, 'clientHeight', { value: 200 }); element.scrollTop = 120;
    api.messages.mockReturnValue(of({ items: [cardMessage(), message(3)], nextCursor: '3' }));
    await page.offerAction('withdraw', 'of1'); await harness.fixture.whenStable(); harness.detectChanges();
    expect(element.scrollTop).toBe(120); expect(harness.routeNativeElement!.querySelectorAll('.offer-card')).toHaveLength(1);
  });
  it('sends with Enter and preserves Shift+Enter and IME composition', async () => {
    const { page } = await thread(); page.store.setDraft('hello');
    const shift = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true }); page.composerKey(shift);
    const ime = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, cancelable: true }); page.composerKey(ime);
    expect(api.sendMessage).not.toHaveBeenCalled(); expect(shift.defaultPrevented).toBe(false);
    const enter = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }); page.composerKey(enter);
    expect(api.sendMessage).toHaveBeenCalledTimes(1); expect(enter.defaultPrevented).toBe(true);
  });
  it('restores the listing-dialog draft on conversation-exists navigation', async () => {
    const harness = await RouterTestingHarness.create('/conversations');
    await harness.fixture.whenStable();
    await TestBed.inject(Router).navigateByUrl('/conversations/c1', { state: { draft: 'Still available?' } });
    await harness.fixture.whenStable(); harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('Still available?');
  });
  it('renders only server-provided offer cards and buyer withdrawal controls', async () => {
    api.messages.mockReturnValue(of({ items: [cardMessage()], nextCursor: null }));
    const { harness } = await thread();
    expect(harness.routeNativeElement!.querySelectorAll('.offer-card')).toHaveLength(1);
    expect(harness.routeNativeElement!.textContent).toContain('Withdraw'); expect(harness.routeNativeElement!.textContent).not.toContain('Decline');
  });
  it('validates the offer amount before making a request and omits notes', async () => {
    const { page } = await thread(); page.offerAmount.set('499.00'); await page.createOffer(); expect(orders.createOffer).not.toHaveBeenCalled();
    page.offerAmount.set('400.001'); await page.createOffer(); expect(orders.createOffer).not.toHaveBeenCalled();
    page.offerAmount.set('400.00'); await page.createOffer(); expect(orders.createOffer).toHaveBeenCalledWith({ listingId: 'l1', amount: '400.00' });
  });
});
