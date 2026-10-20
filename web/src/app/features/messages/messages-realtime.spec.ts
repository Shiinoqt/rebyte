import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { ConversationView, MessagePage, MessageView } from '../../core/api/api-models';
import { MessagingApi } from '../../core/api/messaging-api';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { PhotoPipeline, ProcessedPhoto } from '../../core/media/photo-pipeline';
import { S3Uploader } from '../../core/media/s3-uploader';
import { RealtimeStore } from '../../core/realtime/realtime.store';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { MessagingStore } from './messaging.store';
import { TypingPresenceStore } from './typing-presence.store';
import { RealtimeMessageBridge } from './realtime-message.bridge';

const at = '2026-10-13T12:00:00Z';
const conversation = (id = 'c1', online = true): ConversationView => ({ id, listingId: 'l1', listingType: 'HARDWARE', listingTitle: 'Laptop', listingPrice: '499.00', buyerSub: 'buyer', sellerSub: 'seller', otherParticipantSub: 'seller', otherParticipantUsername: 'alex', lastMessage: null, lastMessageAt: at, unreadFor: 0, partnerOnline: online, createdAt: at });
const message = (seq: number): MessageView => ({ id: `m${seq}`, seq, conversationId: 'c1', senderSub: 'seller', kind: 'CHAT', text: `Message ${seq}`, images: [], offer: null, createdAt: at });
const file = (name = 'photo.jpg', type = 'image/jpeg', size = 10) => new File([new Uint8Array(size)], name, { type });
const policy = { url: 'https://photos.s3.eu-west-1.amazonaws.com/', key: 'chat/buyer/photo', contentType: 'image/jpeg', algorithm: 'AWS4-HMAC-SHA256', credential: 'synthetic', date: '20261013T120000Z', policy: 'synthetic', signature: 'synthetic', securityToken: null };
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((r) => resolve = r); return { promise, resolve }; }
async function settle() { for (let i = 0; i < 20; i++) await Promise.resolve(); }
function setup() {
  const cleanups: (() => void)[] = [];
  const identity = signal<{sub: string; groups: string[]} | null>({ sub: 'buyer', groups: [] });
  const api = { conversations: vi.fn(() => of({ items: [conversation()], nextCursor: null })), conversation: vi.fn(() => of(conversation())), messages: vi.fn(() => of<MessagePage>({ items: [message(1)], nextCursor: null })), unreadSummary: vi.fn(() => of({ notifications: 1, messages: 0 })), sendMessage: vi.fn(() => of(message(9))), imageUploadPolicy: vi.fn(() => of(policy)) };
  const photos = { process: vi.fn(async () => ({ blob: new Blob(['photo'], { type: 'image/jpeg' }), width: 10, height: 10, url: 'blob:photo', cleanup: vi.fn() } as ProcessedPhoto)) };
  const uploader = { upload: vi.fn(async () => {}) };
  const transport = { sendTyping: vi.fn() };
  TestBed.configureTestingModule({ providers: [MessagingStore, RealtimeMessageBridge, { provide: MessagingApi, useValue: api }, { provide: OrderApi, useValue: { offer: vi.fn() } }, { provide: AuthStore, useValue: { identity, registerCleanup: (fn: () => void) => { cleanups.push(fn); return () => {}; } } }, { provide: PhotoPipeline, useValue: photos }, { provide: S3Uploader, useValue: uploader }, { provide: RealtimeService, useValue: transport }] });
  const realtime = TestBed.inject(RealtimeStore), store = TestBed.inject(MessagingStore), cues = TestBed.inject(TypingPresenceStore);
  return { api, photos, uploader, transport, realtime, store, cues, logout: () => { identity.set(null); realtime.reset(); cleanups.forEach((fn) => fn()); } };
}
afterEach(() => { TestBed.resetTestingModule(); vi.useRealTimers(); });

describe('Messaging presence and typing contract', () => {
  it('seeds HTTP presence, preserves transitions racing the read and does not expire online at 45 seconds', async () => {
    vi.useFakeTimers(); const { store, realtime, cues, api } = setup(); realtime.setConnection('connected');
    const pending = new Subject<ConversationView>(); api.conversation.mockReturnValue(pending);
    const selecting = store.selectConversation('c1'); realtime.receive('presence', { userSub: 'seller', online: false });
    pending.next(conversation()); await selecting; expect(cues.online('seller')).toBe(false);
    realtime.receive('presence', { userSub: 'seller', online: true }); await vi.advanceTimersByTimeAsync(60000); expect(cues.online('seller')).toBe(true);
    realtime.setConnection('reconnecting'); expect(cues.online('seller')).toBeNull();
    realtime.setConnection('connected'); await store.loadInbox(); expect(cues.online('seller')).toBe(true);
  });
  it('rejects a presence seed from a previous connection even after reconnect', () => {
    const { realtime, cues } = setup(); realtime.setConnection('connected'); const token = cues.capture();
    realtime.setConnection('reconnecting'); realtime.setConnection('connected'); cues.seed([conversation()], token); expect(cues.online('seller')).toBeNull();
  });
  it.each([2000, 5000, 8000])('honors %s ms typing TTL and renews from receipt', async (ttlMs) => {
    vi.useFakeTimers(); const { realtime, cues } = setup(); realtime.setConnection('connected');
    const frame = () => realtime.receive('typing', { conversationId: 'c1', userSub: 'seller', ttlMs });
    frame(); await vi.advanceTimersByTimeAsync(ttlMs - 1); expect(cues.typing('c1', 'seller')).toBe(true);
    frame(); await vi.advanceTimersByTimeAsync(ttlMs - 1); expect(cues.typing('c1', 'seller')).toBe(true);
    await vi.advanceTimersByTimeAsync(1); expect(cues.typing('c1', 'seller')).toBe(false);
  });
  it.each([undefined, null, 0, -1, '5000', 1.5, Infinity])('ignores invalid TTL %s', (ttlMs) => {
    const { realtime, cues } = setup(); realtime.setConnection('connected'); realtime.receive('typing', { conversationId: 'c1', userSub: 'seller', ttlMs }); expect(cues.typing('c1', 'seller')).toBe(false);
  });
  it('clears typing on message, disconnect and logout', () => {
    const { realtime, cues, logout } = setup(); realtime.setConnection('connected');
    const typing = () => realtime.receive('typing', { conversationId: 'c1', userSub: 'seller', ttlMs: 5000 });
    typing(); realtime.receive('messages', { type: 'message', conversationId: 'c1', message: message(2) }); expect(cues.typing('c1', 'seller')).toBe(false);
    typing(); realtime.setConnection('reconnecting'); expect(realtime.typing().size).toBe(0);
    realtime.setConnection('connected'); typing(); logout(); expect(realtime.typing().size).toBe(0); expect(cues.online('seller')).toBeNull();
  });
});

describe('Realtime messaging convergence', () => {
  it('refetches all authoritative views on initial connect and reconnect without resetting draft/history', async () => {
    vi.useFakeTimers(); const { realtime, store, api } = setup(); await store.selectConversation('c1'); store.setDraft('keep me'); await store.mergeIncoming(message(3));
    TestBed.inject(RealtimeMessageBridge); TestBed.tick(); realtime.setConnection('connected'); TestBed.tick(); await vi.advanceTimersByTimeAsync(51);
    expect(api.unreadSummary).toHaveBeenCalledTimes(1); expect(api.conversations).toHaveBeenCalledTimes(1); expect(api.conversation).toHaveBeenCalledTimes(2); expect(api.messages).toHaveBeenCalledTimes(2);
    realtime.setConnection('reconnecting'); TestBed.tick(); realtime.setConnection('connected'); TestBed.tick(); await vi.advanceTimersByTimeAsync(51);
    expect(api.unreadSummary).toHaveBeenCalledTimes(2); expect(store.messages().map((m) => m.seq)).toEqual([1, 3]); expect(store.draft()).toBe('keep me');
  });
  it('merges duplicate and out-of-order pushes once, including pushes during the initial read', async () => {
    vi.useFakeTimers(); const { realtime, store, api } = setup(); TestBed.inject(RealtimeMessageBridge); realtime.setConnection('connected'); TestBed.tick();
    const response = new Subject<ConversationView>(); api.conversation.mockReturnValue(response); const selecting = store.selectConversation('c1');
    for (const seq of [3, 2, 3]) realtime.receive('messages', { type: 'message', conversationId: 'c1', message: message(seq) });
    response.next(conversation()); await selecting; expect(store.messages().map((m) => m.seq)).toEqual([1, 2, 3]);
  });
  it('does not overwrite a pushed unread summary with a late HTTP response', async () => {
    vi.useFakeTimers(); const { realtime, api } = setup(); const response = new Subject<{notifications: number; messages: number}>(); api.unreadSummary.mockReturnValue(response);
    TestBed.inject(RealtimeMessageBridge); realtime.setConnection('connected'); TestBed.tick(); await vi.advanceTimersByTimeAsync(51);
    realtime.receive('unread', { notifications: 4, messages: 7 }); response.next({ notifications: 0, messages: 0 }); await settle(); expect(realtime.unread()).toEqual({ notifications: 4, messages: 7 });
  });
  it('coalesces events during a slow inbox read into another authoritative fetch', async () => {
    vi.useFakeTimers(); const { realtime, api } = setup(); const response = new Subject<{items: ConversationView[]; nextCursor: null}>(); api.conversations.mockReturnValueOnce(response);
    TestBed.inject(RealtimeMessageBridge); realtime.setConnection('connected'); TestBed.tick(); await vi.advanceTimersByTimeAsync(51);
    realtime.receive('messages', { type: 'message', conversationId: 'c1', message: message(2) }); response.next({ items: [], nextCursor: null }); await settle();
    expect(api.conversations).toHaveBeenCalledTimes(2); expect(api.unreadSummary).toHaveBeenCalledTimes(2);
  });
  it('publishes leading and trailing typing at 1500 ms and cancels trailing work on thread changes', async () => {
    vi.useFakeTimers(); const { realtime, store, transport } = setup(); await store.selectConversation('c1'); const bridge = TestBed.inject(RealtimeMessageBridge); realtime.setConnection('connected'); TestBed.tick(); store.setDraft('hello');
    bridge.typing(); await vi.advanceTimersByTimeAsync(100); bridge.typing(); bridge.typing(); expect(transport.sendTyping).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1400); expect(transport.sendTyping).toHaveBeenCalledTimes(2);
    bridge.typing(); await store.selectConversation('c2'); TestBed.tick(); await vi.advanceTimersByTimeAsync(1500); expect(transport.sendTyping).toHaveBeenCalledTimes(2);
  });
  it('invalidates pending history and uploads on logout', async () => {
    const { store, api, logout } = setup(); const response = new Subject<MessagePage>(); api.messages.mockReturnValue(response);
    const selecting = store.selectConversation('c1'); logout(); response.next({ items: [message(9)], nextCursor: null }); await selecting; expect(store.messages()).toEqual([]); expect(store.conversation()).toBeNull();
  });
});

describe('Chat image workflow', () => {
  it('processes, requests a fresh policy, uploads with policy Content-Type, then sends the uploaded key', async () => {
    const { store, api, photos, uploader } = setup(); await store.selectConversation('c1'); await store.addImages([file()]);
    expect(photos.process).toHaveBeenCalledWith(expect.any(File), { maxEdge: 2000, maxBytes: 1048576 }); expect(api.imageUploadPolicy).toHaveBeenCalledWith({ contentType: 'image/jpeg' });
    expect(uploader.upload).toHaveBeenCalledWith(policy, expect.any(Blob), 'image/jpeg', expect.any(Function), expect.any(AbortSignal)); expect(api.sendMessage).not.toHaveBeenCalled();
    store.setDraft('Photo attached'); await store.send(); expect(api.sendMessage).toHaveBeenCalledWith('c1', { text: 'Photo attached', imageKeys: [policy.key] }); expect(store.draft()).toBe(''); expect(store.attachments()).toEqual([]); expect(store.messages().filter((m) => m.id === 'm9')).toHaveLength(1);
  });
  it('blocks send while uploading and retains previews after failure; retry obtains a fresh policy', async () => {
    const { store, uploader, api } = setup(); await store.selectConversation('c1'); const pending = deferred<void>(); uploader.upload.mockReturnValueOnce(pending.promise);
    const adding = store.addImages([file()]); await settle(); expect(store.attachments()[0].status).toBe('uploading'); store.setDraft('keep'); expect(await store.send()).toBe(false); pending.resolve(); await adding;
    api.sendMessage.mockReturnValueOnce(throwError(() => ({ status: 503 }))); expect(await store.send()).toBe(false); expect(store.attachments()[0].url).toBe('blob:photo'); expect(store.imageKeys()).toEqual([policy.key]); expect(store.draft()).toBe('keep');
    store.removeImage(store.attachments()[0].id); uploader.upload.mockRejectedValueOnce(new Error('network')); await store.addImages([file()]); const id = store.attachments()[0].id; expect(store.attachments()[0].status).toBe('error'); expect(await store.send()).toBe(false);
    await store.retryImage(id); expect(api.imageUploadPolicy).toHaveBeenCalledTimes(3); expect(store.attachments()[0].status).toBe('ready');
  });
  it('rejects invalid and oversized files and limits attachment count to three', async () => {
    const { store, photos } = setup(); await store.selectConversation('c1'); await store.addImages([file('bad.svg', 'image/svg+xml'), file('big.jpg', 'image/jpeg', 1048577), file('empty.jpg', 'image/jpeg', 0)]); expect(photos.process).not.toHaveBeenCalled(); expect(store.imageError()).toContain('1 MB');
    await store.addImages([file('1'), file('2'), file('3'), file('4')]); expect(store.attachments()).toHaveLength(3); expect(photos.process).toHaveBeenCalledTimes(3); expect(store.imageError()).toContain('3 photos');
  });
  it('removal aborts in-flight uploads and prevents late completion from restoring the attachment', async () => {
    const { store, uploader } = setup(); await store.selectConversation('c1'); const pending = deferred<void>(); uploader.upload.mockReturnValueOnce(pending.promise);
    const adding = store.addImages([file()]); await settle(); const args = uploader.upload.mock.calls[0] as unknown as [unknown, unknown, unknown, unknown, AbortSignal];
    store.removeImage(store.attachments()[0].id); expect(args[4].aborted).toBe(true); pending.resolve(); await adding; expect(store.attachments()).toEqual([]); expect(store.imageKeys()).toEqual([]);
  });
  it('releases a processed preview if its conversation changes before processing finishes', async () => {
    const { store, photos, api } = setup(); await store.selectConversation('c1'); const pending = deferred<ProcessedPhoto>(); photos.process.mockReturnValueOnce(pending.promise); const adding = store.addImages([file()]); await store.selectConversation('c2');
    const cleanup = vi.fn(); pending.resolve({ blob: new Blob(), width: 1, height: 1, url: 'blob:late', cleanup }); await adding; expect(cleanup).toHaveBeenCalledOnce(); expect(api.imageUploadPolicy).not.toHaveBeenCalled(); expect(store.attachments()).toEqual([]);
  });
  it('rejects insecure or mismatched upload policies before uploading', async () => {
    const { store, api, uploader } = setup(); await store.selectConversation('c1'); api.imageUploadPolicy.mockReturnValueOnce(of({ ...policy, contentType: 'image/png' })); await store.addImages([file()]); expect(uploader.upload).not.toHaveBeenCalled();
    api.imageUploadPolicy.mockReturnValueOnce(of({ ...policy, url: 'http://example.com' })); await store.retryImage(store.attachments()[0].id); expect(uploader.upload).not.toHaveBeenCalled(); expect(store.attachments()[0].status).toBe('error');
  });
});
