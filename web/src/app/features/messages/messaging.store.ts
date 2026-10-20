import { DestroyRef, inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { ConversationView, MessageView, OfferView } from '../../core/api/api-models';
import { MessagingApi } from '../../core/api/messaging-api';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { PhotoPipeline, ProcessedPhoto } from '../../core/media/photo-pipeline';
import { S3Uploader } from '../../core/media/s3-uploader';
import { TypingPresenceStore } from './typing-presence.store';

export interface ChatAttachment {
  id: number;
  name: string;
  url: string;
  status: 'processing' | 'uploading' | 'ready' | 'error';
  progress: number;
  key: string | null;
  error: string;
}

export function messagingError(error: unknown): string {
  const problem = error as AppError;
  if (problem?.status === 404) return 'This conversation is no longer available.';
  return problem?.code ? errorCopy(problem) : 'Something went wrong. Try again in a minute.';
}

export function mergeMessages(current: MessageView[], incoming: MessageView[]): MessageView[] {
  const byId = new Map(current.map((message) => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((a, b) => a.seq - b.seq || a.id.localeCompare(b.id));
}

const initialState = {
  conversations: [] as ConversationView[],
  inboxCursor: null as string | null,
  inboxLoaded: false,
  inboxLoading: false,
  inboxError: '',
  selectedId: null as string | null,
  conversation: null as ConversationView | null,
  messages: [] as MessageView[],
  historyCursor: null as string | null,
  threadLoading: false,
  threadError: '',
  olderLoading: false,
  olderError: '',
  refreshing: false,
  refreshError: '',
  draft: '',
  imageKeys: [] as string[],
  attachments: [] as ChatAttachment[],
  imageError: '',
  sending: false,
  sendError: '',
  offers: {} as Record<string, OfferView>,
  offerBusy: '',
  offerError: '',
};

export const MessagingStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withMethods((store, api = inject(MessagingApi), auth = inject(AuthStore), orders = inject(OrderApi)) => {
    const cues = inject(TypingPresenceStore);
    const photos = inject(PhotoPipeline);
    const uploader = inject(S3Uploader);
    let attachmentId = 0;
    const files = new Map<number, { file: File; photo?: ProcessedPhoto; abort?: AbortController }>();
    const clearAttachments = () => {
      for (const entry of files.values()) { entry.abort?.abort(); entry.photo?.cleanup(); }
      files.clear();
      patchState(store, { attachments: [], imageKeys: [], imageError: '' });
    };
    let generation = 0;
    let inboxGeneration = 0;
    let refreshGeneration = 0;
    const offerRequests = new Map<string, number>();
    const clear = () => {
      ++generation;
      ++inboxGeneration;
      ++refreshGeneration;
      clearAttachments();
      offerRequests.clear();
      patchState(store, initialState);
    };
    const dropThread = (error: unknown) => {
      ++generation;
      ++refreshGeneration;
      clearAttachments();
      offerRequests.clear();
      // selectedId stays: it is the route id and lets the unavailable state retry.
      patchState(store, {
        conversation: null, messages: [], historyCursor: null, threadLoading: false, threadError: messagingError(error),
        olderLoading: false, olderError: '', refreshing: false, refreshError: '',
        draft: '', imageKeys: [], sending: false, sendError: '', offers: {}, offerBusy: '', offerError: '',
        conversations: store.conversations().filter((row) => row.id !== store.selectedId()),
      });
    };
    const gone = (error: unknown) => [403, 404].includes((error as AppError)?.status);
    const unregister = auth.registerCleanup(clear);
    inject(DestroyRef).onDestroy(() => { unregister(); clear(); });

    async function loadInbox(append = false, preserve = false, force = false): Promise<void> {
      if ((!force && store.inboxLoading()) || (append && !store.inboxCursor()?.trim())) return;
      const token = ++inboxGeneration;
      const presenceRead = cues.capture();
      const cursor = append ? store.inboxCursor()! : null;
      patchState(store, { inboxLoading: true, inboxError: '' });
      try {
        const page = await firstValueFrom(api.conversations({ size: 20, ...(cursor ? { cursor } : {}) }));
        if (token !== inboxGeneration) return;
        cues.seed(page.items, presenceRead);
        const rows = new Map((append ? store.conversations() : page.items).map((row) => [row.id, row]));
        for (const row of (append ? page.items : preserve ? store.conversations() : [])) {
          if (append || !rows.has(row.id)) rows.set(row.id, row);
        }
        patchState(store, {
          conversations: [...rows.values()].map((row) => row.id === store.selectedId() ? { ...row, unreadFor: 0 } : row), inboxLoaded: true,
          inboxCursor: preserve && store.inboxLoaded() ? store.inboxCursor() : page.nextCursor?.trim() && page.nextCursor !== cursor ? page.nextCursor : null,
        });
      } catch (error) {
        if (token === inboxGeneration) patchState(store, { inboxError: messagingError(error) });
      } finally {
        if (token === inboxGeneration) patchState(store, { inboxLoading: false });
      }
    }

    async function refreshOffer(id: string, token = generation): Promise<void> {
      const request = (offerRequests.get(id) ?? 0) + 1;
      offerRequests.set(id, request);
      try {
        const offer = await firstValueFrom(orders.offer(id));
        if (token === generation && offerRequests.get(id) === request) patchState(store, { offers: { ...store.offers(), [id]: offer } });
      } catch (error) {
        if (token === generation && offerRequests.get(id) === request) patchState(store, { offerError: messagingError(error) });
      }
    }
    async function refreshCardOffers(messages: MessageView[], token: number): Promise<void> {
      const ids = new Set(messages.filter((message) => message.kind === 'OFFER' && message.offer).map((message) => message.offer!.offerId));
      await Promise.all([...ids].map((id) => refreshOffer(id, token)));
    }

    async function selectConversation(id: string | null): Promise<void> {
      const token = ++generation;
      ++refreshGeneration;
      clearAttachments();
      const presenceRead = cues.capture();
      patchState(store, {
        selectedId: id, conversation: null, messages: [], historyCursor: null,
        threadLoading: !!id, threadError: '',
        olderLoading: false, olderError: '', refreshing: false, refreshError: '',
        draft: '', imageKeys: [], sending: false, sendError: '', offers: {}, offerBusy: '', offerError: '',
      });
      if (!id) return;
      try {
        const [conversation, page] = await Promise.all([
          firstValueFrom(api.conversation(id)), firstValueFrom(api.messages(id, { size: 20 })),
        ]);
        if (token !== generation) return;
        cues.seed([conversation], presenceRead);
        patchState(store, {
          conversation: { ...conversation, unreadFor: 0 },
          threadLoading: false,
          messages: mergeMessages(store.messages(), page.items), historyCursor: page.nextCursor?.trim() ? page.nextCursor : null,
          conversations: store.conversations().map((row) => row.id === id ? { ...row, unreadFor: 0 } : row),
        });
        void refreshCardOffers(page.items, token);
      } catch (error) {
        if (token === generation) patchState(store, { conversation: null, messages: [], threadError: messagingError(error) });
      } finally {
        if (token === generation) patchState(store, { threadLoading: false });
      }
    }
    async function loadOlder(): Promise<boolean> {
      const id = store.selectedId(); const cursor = store.historyCursor(); const token = generation;
      if (!id || !store.conversation() || !cursor?.trim() || store.olderLoading()) return false;
      patchState(store, { olderLoading: true, olderError: '' });
      try {
        const page = await firstValueFrom(api.messages(id, { size: 20, cursor }));
        if (token !== generation) return false;
        patchState(store, {
          messages: mergeMessages(store.messages(), page.items),
          historyCursor: page.nextCursor?.trim() && page.nextCursor !== cursor ? page.nextCursor : null,
        });
        void refreshCardOffers(page.items, token);
        return true;
      } catch (error) {
        if (token === generation) { if (gone(error)) dropThread(error); else patchState(store, { olderError: messagingError(error) }); }
        return false;
      } finally {
        if (token === generation) patchState(store, { olderLoading: false });
      }
    }

    async function refreshHistory(force = false): Promise<void> {
      const id = store.selectedId(); const token = generation;
      if (!id || (!force && store.refreshing())) return;
      if (!store.conversation()) { if (force) await selectConversation(id); return; }
      const request = ++refreshGeneration;
      const presenceRead = cues.capture();
      patchState(store, { refreshing: true, refreshError: '' });
      try {
        const [conversation, page] = await Promise.all([
          firstValueFrom(api.conversation(id)), firstValueFrom(api.messages(id, { size: 20 })),
        ]);
        if (token !== generation || request !== refreshGeneration) return;
        cues.seed([conversation], presenceRead);
        // The oldest loaded cursor belongs to retained pages, not this refreshed first page.
        patchState(store, { conversation: { ...conversation, unreadFor: 0 }, messages: mergeMessages(store.messages(), page.items) });
        await refreshCardOffers(page.items, token);
      } catch (error) {
        if (token === generation && request === refreshGeneration) { if (gone(error)) dropThread(error); else patchState(store, { refreshError: messagingError(error) }); }
      } finally {
        if (token === generation && request === refreshGeneration) patchState(store, { refreshing: false });
      }
    }

    async function mergeIncoming(message: MessageView): Promise<void> {
      if (message.conversationId !== store.selectedId()) return;
      patchState(store, { messages: mergeMessages(store.messages(), [message]) });
      if (message.kind === 'OFFER' && message.offer) await refreshOffer(message.offer.offerId);
    }

    async function send(): Promise<boolean> {
      const id = store.selectedId(); const token = generation;
      const text = store.draft(); const imageKeys = [...store.imageKeys()];
      if (!id || !store.conversation() || store.sending() || store.attachments().some((item) => item.status !== 'ready') || (!text.trim() && !imageKeys.length)) return false;
      patchState(store, { sending: true, sendError: '' });
      try {
        const result = await firstValueFrom(api.sendMessage(id, { text: text || null, imageKeys }));
        if (token !== generation) return false;
        if (result.conversationId !== id || result.kind !== 'CHAT') throw new Error('Unexpected message response');
        const unchanged = store.draft() === text && store.imageKeys().join('\0') === imageKeys.join('\0');
        clearAttachments();
        patchState(store, {
          messages: mergeMessages(store.messages(), [result]),
          ...(unchanged ? { draft: '', imageKeys: [] } : {}),
        });
        void loadInbox(false, true);
        return true;
      } catch (error) {
        if (token === generation) patchState(store, { sendError: (error as AppError)?.status === 429 ? 'You are sending messages too quickly. Wait a moment.' : messagingError(error) });
        return false;
      } finally {
        if (token === generation) patchState(store, { sending: false });
      }
    }

    function updateAttachment(id: number, changes: Partial<ChatAttachment>): void {
      const attachments = store.attachments().map((item) => item.id === id ? { ...item, ...changes } : item);
      patchState(store, { attachments, imageKeys: attachments.flatMap((item) => item.key ? [item.key] : []) });
    }

    async function uploadImage(id: number): Promise<void> {
      const entry = files.get(id); const token = generation;
      const item = store.attachments().find((candidate) => candidate.id === id);
      if (!entry || !item || store.sending() || item.status === 'uploading') return;
      const active = () => token === generation && files.get(id) === entry;
      updateAttachment(id, { status: 'processing', error: '', progress: 0, key: null });
      try {
        if (!entry.photo) {
          const photo = await photos.process(entry.file, { maxEdge: 2000, maxBytes: 1048576 });
          if (!active()) { photo.cleanup(); return; }
          entry.photo = photo;
        }
        updateAttachment(id, { url: entry.photo.url, status: 'uploading' });
        const policy = await firstValueFrom(api.imageUploadPolicy({ contentType: entry.photo.blob.type }));
        if (!active()) return;
        const url = new URL(policy.url);
        if (url.protocol !== 'https:' || url.username || url.password || policy.contentType !== entry.photo.blob.type || !policy.key.trim()) throw new Error('Invalid upload policy');
        entry.abort = new AbortController();
        await uploader.upload(policy, entry.photo.blob, policy.contentType, (progress) => {
          if (active()) updateAttachment(id, { progress });
        }, entry.abort.signal);
        if (active()) updateAttachment(id, { status: 'ready', progress: 100, key: policy.key });
      } catch {
        if (active()) updateAttachment(id, { status: 'error', error: 'Photo could not be uploaded. Try again or remove it.' });
      }
    }

    async function addImages(selected: File[]): Promise<void> {
      if (store.sending() || !store.conversation()) return;
      patchState(store, { imageError: '' });
      const work: number[] = [];
      for (const file of selected) {
        if (store.attachments().length >= 3) { patchState(store, { imageError: 'You can add up to 3 photos.' }); break; }
        if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || !file.size || file.size > 1048576) {
          patchState(store, { imageError: 'Choose a JPG, PNG or WebP photo up to 1 MB.' }); continue;
        }
        const id = ++attachmentId;
        files.set(id, { file });
        patchState(store, { attachments: [...store.attachments(), { id, name: file.name, url: '', status: 'processing', progress: 0, key: null, error: '' }] });
        work.push(id);
      }
      await Promise.all(work.map(uploadImage));
    }

    function removeImage(id: number): void {
      if (store.sending()) return;
      const entry = files.get(id);
      files.delete(id);
      entry?.abort?.abort(); entry?.photo?.cleanup();
      const attachments = store.attachments().filter((item) => item.id !== id);
      patchState(store, { attachments, imageKeys: attachments.flatMap((item) => item.key ? [item.key] : []), imageError: '' });
    }

    const cardCount = (offerId: string) => store.messages().filter((row) => row.kind === 'OFFER' && row.offer?.offerId === offerId).length;
    // The messaging service appends the card after the order service publishes the event, so the refresh that follows a
    // command can precede it and the acting side would never see its own card.
    async function awaitOfferCard(offerId: string, known: number, token: number): Promise<void> {
      for (let attempt = 0; attempt < 5 && cardCount(offerId) <= known; attempt++) {
        await new Promise<void>((resolve) => setTimeout(resolve, 1000));
        if (token !== generation) return;
        await refreshHistory();
      }
    }
    async function offerCommand(action: 'create' | 'accept' | 'reject' | 'withdraw', value: string): Promise<boolean> {
      const conversation = store.conversation(); const token = generation;
      if (!conversation || store.offerBusy()) return false;
      const known = action === 'create' ? 0 : cardCount(value);
      patchState(store, { offerBusy: action === 'create' ? 'create' : value, offerError: '' });
      try {
        const result = await firstValueFrom(action === 'create'
          ? orders.createOffer({ listingId: conversation.listingId, amount: value })
          : action === 'accept' ? orders.acceptOffer(value) : action === 'reject' ? orders.rejectOffer(value) : orders.withdrawOffer(value));
        if (token !== generation) return false;
        if (result.listingId !== conversation.listingId) throw new Error('Unexpected offer response');
        offerRequests.set(result.id, (offerRequests.get(result.id) ?? 0) + 1);
        patchState(store, { offers: { ...store.offers(), [result.id]: result } });
        await Promise.all([refreshHistory(), loadInbox(false, true)]);
        void awaitOfferCard(result.id, known, token);
        return token === generation;
      } catch (error) {
        if (token !== generation) return false;
        const problem = error as AppError;
        patchState(store, { offerError: problem.code === 'offer-accept-conflict' ? 'This offer changed. Review its latest status.' : messagingError(error) });
        if (action !== 'create' && (problem.status === 409 || problem.code === 'offer-not-pending')) await refreshOffer(value, token);
        return false;
      } finally {
        if (token === generation) patchState(store, { offerBusy: '' });
      }
    }
    return {
      loadInbox, selectConversation, clear, loadOlder, refreshHistory, mergeIncoming, send, refreshOffer, offerCommand,
      addImages, removeImage, retryImage: (id: number) => store.attachments().find((item) => item.id === id)?.status === 'error' ? uploadImage(id) : Promise.resolve(),
      setDraft: (draft: string, imageKeys = store.imageKeys()) => patchState(store, { draft, imageKeys: [...imageKeys] }),
    };
  }),
);
export type MessagingStore = InstanceType<typeof MessagingStore>;
