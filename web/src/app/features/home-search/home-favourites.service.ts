import { DestroyRef, effect, inject, Injectable, signal } from '@angular/core';
import { Observable, ReplaySubject, Subscription, of } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { ToastService } from '../../shared/ui/feedback';

const PAGE_SIZE = 100;

@Injectable({ providedIn: 'root' })
export class HomeFavourites {
  private readonly auth = inject(AuthStore);
  private readonly catalog = inject(CatalogApi);
  private readonly toasts = inject(ToastService);
  private readonly favouriteIds = signal<ReadonlySet<string>>(new Set());
  readonly ids = this.favouriteIds.asReadonly();

  private sessionSub: string | null = null;
  private generation = 0;
  private requests = new Subscription();
  private readonly pending = new Set<string>();
  private readonly touched = new Set<string>();

  constructor() {
    effect(() => {
      const sub = this.auth.isAuthenticated() ? (this.auth.identity()?.sub ?? null) : null;
      if (sub === this.sessionSub) return;
      this.startSession(sub);
    });
    inject(DestroyRef).onDestroy(() => this.resetSession());
  }

  toggle(listingId: string, favourite: boolean): Observable<boolean> {
    const sub = this.sessionSub;
    if (
      !sub ||
      !this.auth.isAuthenticated() ||
      this.auth.identity()?.sub !== sub ||
      this.pending.has(listingId)
    ) {
      return of(false);
    }

    const before = this.favouriteIds().has(listingId);
    if (before === favourite && favourite) return of(true);

    const result = new ReplaySubject<boolean>(1);

    const generation = this.generation;
    this.pending.add(listingId);
    this.touched.add(listingId);
    this.setFavourite(listingId, favourite);
    const request: Observable<unknown> = favourite
      ? this.catalog.addFavourite(listingId)
      : this.catalog.removeFavourite(listingId);
    this.requests.add(
      request.subscribe({
        next: () => {
          if (this.isCurrent(sub, generation)) this.pending.delete(listingId);
          result.next(this.isCurrent(sub, generation));
          result.complete();
        },
        error: (failure: unknown) => {
          if (!this.isCurrent(sub, generation)) return;
          this.pending.delete(listingId);
          this.setFavourite(listingId, before);
          this.toasts.show({ text: errorCopy(this.toAppError(failure)), kind: 'error' });
          result.next(false);
          result.complete();
        },
      }),
    );
    return result.asObservable();
  }

  private startSession(sub: string | null): void {
    this.resetSession();
    if (!sub) return;

    this.sessionSub = sub;
    const generation = this.generation;
    this.loadPage(sub, generation, 0);
  }

  private loadPage(sub: string, generation: number, page: number): void {
    this.requests.add(
      this.catalog.favourites({ page, size: PAGE_SIZE }).subscribe({
        next: (response) => {
          if (!this.isCurrent(sub, generation)) return;
          this.favouriteIds.update((current) => {
            const next = new Set(current);
            for (const favourite of response.favourites) {
              if (!this.touched.has(favourite.listingId)) next.add(favourite.listingId);
            }
            return next;
          });
          if (page + 1 < response.totalPages) this.loadPage(sub, generation, page + 1);
        },
        error: (failure: unknown) => {
          if (this.isCurrent(sub, generation)) {
            this.toasts.show({ text: errorCopy(this.toAppError(failure)), kind: 'error' });
          }
        },
      }),
    );
  }

  private resetSession(): void {
    ++this.generation;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.sessionSub = null;
    this.pending.clear();
    this.touched.clear();
    this.favouriteIds.set(new Set());
  }

  private isCurrent(sub: string, generation: number): boolean {
    return (
      this.sessionSub === sub &&
      this.generation === generation &&
      this.auth.isAuthenticated() &&
      this.auth.identity()?.sub === sub
    );
  }

  private setFavourite(listingId: string, favourite: boolean): void {
    this.favouriteIds.update((current) => {
      const next = new Set(current);
      if (favourite) next.add(listingId);
      else next.delete(listingId);
      return next;
    });
  }

  private toAppError(error: unknown): AppError {
    const candidate = error as Partial<AppError> | null;
    return typeof candidate?.status === 'number' && typeof candidate.code === 'string'
      ? (candidate as AppError)
      : { status: 500, code: 'unknown-error', type: 'about:blank', fieldErrors: {}, extras: {} };
  }
}
