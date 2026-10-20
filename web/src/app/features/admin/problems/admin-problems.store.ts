import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Subscription, take } from 'rxjs';
import { AdminProblemItem, AdminProblemQuery } from '../../../core/api/api-models';
import { OrderApi } from '../../../core/api/order-api';
import { AuthStore } from '../../../core/auth/auth.store';
import { AppError } from '../../../core/http/app-error';
import { errorCopy } from '../../../core/http/error-copy';

export type ProblemFilter = 'ALL' | 'OPEN' | 'REFUND_PENDING';
export type ProblemAction = 'refund' | 'dismiss';

export function trimNote(raw: string): string | null {
  // Request records use Java String.trim(), which preserves non-ASCII spaces.
  return raw.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '') || null;
}

export function noteError(raw: string, required: boolean): string | null {
  const note = trimNote(raw);
  // Jakarta NotBlank follows Java Character.isWhitespace, including these Unicode spaces.
  if (
    required &&
    (!note ||
      /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(
        note,
      ))
  )
    return 'Enter a reason for dismissing this problem.';
  if (note && note.length > 1000) return 'Use 1,000 characters or fewer.';
  if (note && /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(note))
    return 'Remove unsupported control characters.';
  return null;
}
type QueueState = 'idle' | 'loading' | 'ready' | 'error';
interface QueuePage {
  items: AdminProblemItem[];
  cursor: string | null;
  state: QueueState;
  loaded: boolean;
  loadingMore: boolean;
  error: string;
}
const empty = (): QueuePage => ({
  items: [],
  cursor: null,
  state: 'idle',
  loaded: false,
  loadingMore: false,
  error: '',
});
const emptyCache = (): Record<ProblemFilter, QueuePage> => ({
  ALL: empty(),
  OPEN: empty(),
  REFUND_PENDING: empty(),
});

@Injectable()
export class AdminProblemsStore {
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cache = signal(emptyCache());
  private readonly selected = signal<ProblemFilter>('ALL');
  private readonly denied = signal(false);
  private readonly sessionSub = signal<string | null>(null);
  private request?: Subscription;
  private command?: Subscription;
  private commandGeneration = 0;
  private readonly commandBusy = signal<string | null>(null);
  private readonly commandError = signal('');
  private readonly commandNotice = signal('');
  private generation = 0;
  private readonly allowed = computed(
    () => this.auth.isAdmin() && !this.denied() && this.sessionSub() === this.auth.identity()?.sub,
  );
  private readonly current = computed(() => this.cache()[this.selected()]);

  readonly filter = this.selected.asReadonly();
  readonly items = computed(() => (this.allowed() ? this.current().items : []));
  readonly cursor = computed(() => (this.allowed() ? this.current().cursor : null));
  readonly state = computed(() => (this.allowed() ? this.current().state : 'forbidden'));
  readonly loadingMore = computed(() => this.allowed() && this.current().loadingMore);
  readonly error = computed(() => (this.allowed() ? this.current().error : ''));
  readonly busyId = computed(() => (this.allowed() ? this.commandBusy() : null));
  readonly actionError = computed(() => (this.allowed() ? this.commandError() : ''));
  readonly notice = computed(() => (this.allowed() ? this.commandNotice() : ''));

  constructor() {
    effect(() => {
      const sub = this.auth.identity()?.sub ?? null;
      const admin = this.auth.isAdmin();
      untracked(() => {
        this.clear();
        this.sessionSub.set(sub);
        this.denied.set(!admin);
        if (admin) this.load(false);
      });
    });
    const unregister = this.auth.registerCleanup(() => {
      this.clear();
      this.denied.set(true);
    });
    this.destroyRef.onDestroy(() => {
      unregister();
      this.clear();
    });
  }

  select(filter: ProblemFilter): void {
    if (filter !== 'ALL' && filter !== 'OPEN' && filter !== 'REFUND_PENDING') return;
    if (filter !== this.selected()) {
      this.cancelPage();
      this.selected.set(filter);
    }
    if (this.allowed() && !this.current().loaded && this.current().state !== 'loading')
      this.load(false);
  }

  reload(): void {
    if (!this.allowed()) return;
    this.cancelPage();
    this.cache.set(emptyCache());
    this.load(false);
  }

  more(): void {
    if (this.cursor() !== null) this.load(true);
  }

  clearActionError(): void {
    this.commandError.set('');
  }

  resolve(id: string, action: ProblemAction, rawNote: string, close: () => void = () => {}): void {
    if (!this.allowed() || this.commandBusy()) return;
    this.commandError.set('');
    const item = this.items().find((candidate) => candidate.id === id);
    if (
      !item ||
      (item.status !== 'OPEN' && !(action === 'refund' && item.status === 'REFUND_PENDING'))
    ) {
      this.commandError.set('This problem changed. Refresh the queue before trying again.');
      return;
    }
    const validation = noteError(rawNote, action === 'dismiss');
    if (validation) {
      this.commandError.set(validation);
      return;
    }
    const note = trimNote(rawNote);
    const generation = ++this.commandGeneration;
    const sub = this.sessionSub();
    this.commandBusy.set(id);
    this.commandNotice.set('');
    const request =
      action === 'refund'
        ? this.api.adminRefundProblem(id, { note })
        : this.api.adminDismissProblem(id, { note: note! });
    this.command = request.pipe(take(1)).subscribe({
      next: () => {
        if (
          generation !== this.commandGeneration ||
          sub !== this.auth.identity()?.sub ||
          !this.allowed()
        )
          return;
        this.commandBusy.set(null);
        this.commandNotice.set(
          action === 'refund'
            ? 'Refund requested. Awaiting payment confirmation.'
            : 'Problem dismissed.',
        );
        close();
        this.reload();
      },
      error: (error: AppError) => {
        if (
          generation !== this.commandGeneration ||
          sub !== this.auth.identity()?.sub ||
          !this.allowed()
        )
          return;
        this.commandBusy.set(null);
        if (error.status === 401 || error.status === 403) {
          this.clear();
          this.denied.set(true);
          close();
          return;
        }
        if (error.status === 409 || error.code === 'problem-not-open') {
          this.commandNotice.set('This problem changed. The queue has been refreshed.');
          close();
          this.reload();
          return;
        }
        this.commandError.set(
          error.code === 'refund-unavailable' || error.status >= 500 || error.status === 0
            ? 'The request could not be confirmed. Check the order before trying again.'
            : errorCopy(error),
        );
      },
    });
  }

  private load(more: boolean): void {
    if (!this.allowed() || this.current().loadingMore || this.current().state === 'loading') return;
    const filter = this.selected();
    const query: AdminProblemQuery = {
      size: 20,
      ...(filter !== 'ALL' ? { status: filter } : {}),
      ...(more && this.current().cursor !== null ? { cursor: this.current().cursor } : {}),
    };
    const generation = ++this.generation;
    const sub = this.sessionSub();
    this.patch(filter, { error: '', ...(more ? { loadingMore: true } : { state: 'loading' }) });
    this.request = this.api.adminProblems(query).subscribe({
      next: (page) => {
        if (generation !== this.generation || sub !== this.auth.identity()?.sub || !this.allowed())
          return;
        const items = new Map(
          (more ? this.cache()[filter].items : []).map((item) => [item.id, item]),
        );
        for (const item of page.items) items.set(item.id, item);
        this.patch(filter, {
          items: [...items.values()],
          cursor: page.nextCursor,
          loaded: true,
          loadingMore: false,
          state: 'ready',
        });
      },
      error: (error: AppError) => {
        if (generation !== this.generation) return;
        if (error.status === 401 || error.status === 403) {
          this.clear();
          this.denied.set(true);
          return;
        }
        this.patch(filter, {
          loadingMore: false,
          state: more ? 'ready' : 'error',
          error: 'Problems could not be loaded. Try again.',
        });
      },
    });
  }

  private patch(filter: ProblemFilter, value: Partial<QueuePage>): void {
    this.cache.update((cache) => ({ ...cache, [filter]: { ...cache[filter], ...value } }));
  }

  private cancelPage(): void {
    ++this.generation;
    this.request?.unsubscribe();
    const current = this.current();
    if (current.state === 'loading' || current.loadingMore) {
      this.patch(this.selected(), { state: current.loaded ? 'ready' : 'idle', loadingMore: false });
    }
  }

  private clear(): void {
    this.cancelPage();
    ++this.commandGeneration;
    this.command?.unsubscribe();
    this.commandBusy.set(null);
    this.commandError.set('');
    this.commandNotice.set('');
    this.cache.set(emptyCache());
  }
}
