import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injectable,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, Subject, Subscription, firstValueFrom, takeUntil } from 'rxjs';
import { OrderApi } from '../../core/api/order-api';
import { CatalogApi } from '../../core/api/catalog-api';
import { LicenseView, VersionResponse } from '../../core/api/api-models';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { CopyField, StatusChip } from '../../shared/ui/content';
import { EmptyState, ErrorState } from '../../shared/ui/feedback';
import { reloadThroughAnchor, startAccountAutoReload } from './account-auto-reload';

export function validArtifactUrl(value: string, expiresAt: string, now = Date.now()): boolean {
  try {
    const url = new URL(value);
    return (
      url.protocol === 'https:' &&
      !url.username &&
      !url.password &&
      !url.port &&
      !url.hash &&
      /^(?:[a-z0-9][a-z0-9.-]*\.)?s3(?:[.-][a-z]{2}(?:-[a-z]+)+-\d)?\.amazonaws\.com$/.test(
        url.hostname,
      ) &&
      Number.isFinite(Date.parse(expiresAt)) &&
      Date.parse(expiresAt) > now
    );
  } catch {
    return false;
  }
}

@Injectable()
export class MeLibraryStore {
  private readonly api = inject(OrderApi);
  private readonly catalog = inject(CatalogApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stop = new Subject<void>();
  private generation = 0;
  private active = false;
  private appendFailed = false;
  private background?: Subscription;
  readonly refreshing = signal(false);
  readonly items = signal<LicenseView[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loaded = signal(false);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly versions = signal<Record<string, VersionResponse[]>>({});
  readonly versionLoading = signal<Record<string, boolean>>({});
  readonly versionErrors = signal<Record<string, boolean>>({});
  readonly downloading = signal<string | null>(null);
  readonly message = signal('');
  constructor() {
    const unregister = this.auth.registerCleanup(() => this.reset());
    this.destroyRef.onDestroy(() => {
      unregister();
      this.pause();
      this.stop.complete();
    });
  }
  activate(): void {
    this.active = true;
    if (!this.loaded() && !this.error()) void this.load();
  }
  pause(): void {
    this.active = false;
    this.cancelReload();
    ++this.generation;
    this.stop.next();
    this.loading.set(false);
    this.downloading.set(null);
    this.versionLoading.set({});
  }
  private read<T>(source: Observable<T>): Promise<T> {
    return firstValueFrom(source.pipe(takeUntil(this.stop)));
  }
  async load(append = false): Promise<void> {
    if (!this.active || !this.auth.identity() || this.loading() || (append && !this.cursor()))
      return;
    this.cancelReload();
    const generation = this.generation;
    this.loading.set(true);
    this.error.set(false);
    this.appendFailed = append;
    try {
      const page = await this.read(
        this.api.licenses({ size: 20, ...(append ? { cursor: this.cursor() } : {}) }),
      );
      if (generation !== this.generation) return;
      this.items.set([
        ...new Map(
          (append ? [...this.items(), ...page.items] : page.items).map((row) => [row.orderId, row]),
        ).values(),
      ]);
      this.cursor.set(page.nextCursor);
      this.loaded.set(true);
    } catch {
      if (generation === this.generation) this.error.set(true);
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  retry(): void {
    void this.load(this.appendFailed);
  }
  autoReload(): boolean {
    const sub = this.auth.identity()?.sub;
    if (
      !this.active ||
      !sub ||
      this.loading() ||
      this.refreshing() ||
      this.downloading() ||
      Object.values(this.versionLoading()).some(Boolean)
    )
      return false;
    const generation = this.generation;
    this.refreshing.set(true);
    this.background = reloadThroughAnchor(
      (cursor) => this.api.licenses({ size: 20, ...(cursor === null ? {} : { cursor }) }),
      (item) => item.orderId,
      this.items().at(-1)?.orderId ?? null,
    )
      .pipe(takeUntil(this.stop))
      .subscribe({
        next: (page) => {
          this.refreshing.set(false);
          if (generation !== this.generation || sub !== this.auth.identity()?.sub) return;
          this.items.set(page.items);
          this.cursor.set(page.nextCursor);
          this.loaded.set(true);
          this.error.set(false);
          this.appendFailed = false;
        },
        error: () => this.refreshing.set(false),
      });
    return true;
  }
  private cancelReload(): void {
    this.background?.unsubscribe();
    this.refreshing.set(false);
  }
  async loadVersions(license: LicenseView): Promise<void> {
    const id = license.listingId;
    if (!this.active || license.status !== 'ACTIVE' || this.versionLoading()[id]) return;
    this.cancelReload();
    const generation = this.generation;
    this.versionLoading.update((rows) => ({ ...rows, [id]: true }));
    this.versionErrors.update((rows) => ({ ...rows, [id]: false }));
    try {
      const versions = await this.read(this.catalog.versions(id));
      if (generation === this.generation)
        this.versions.update((rows) => ({ ...rows, [id]: versions }));
    } catch {
      if (generation === this.generation)
        this.versionErrors.update((rows) => ({ ...rows, [id]: true }));
    } finally {
      if (generation === this.generation)
        this.versionLoading.update((rows) => ({ ...rows, [id]: false }));
    }
  }
  async download(license: LicenseView, number: string): Promise<void> {
    if (
      !this.active ||
      this.downloading() ||
      license.status !== 'ACTIVE' ||
      !this.versions()[license.listingId]?.some(
        (row) => row.number === number && row.artifactAvailable,
      )
    )
      return;
    this.cancelReload();
    const generation = this.generation;
    this.downloading.set(license.orderId + ':' + number);
    this.message.set('');
    try {
      const result = await this.read(this.catalog.artifactDownload(license.listingId, number));
      if (generation !== this.generation) return;
      if (!validArtifactUrl(result.url, result.expiresAt))
        throw new Error('Invalid download response');
      this.navigateDownload(result.url);
    } catch (error) {
      if (generation === this.generation)
        this.message.set(
          typeof error === 'object' && error !== null && 'code' in error
            ? errorCopy(error as AppError)
            : 'Could not download this version. Try again.',
        );
    } finally {
      if (generation === this.generation) this.downloading.set(null);
    }
  }
  navigateDownload(url: string): void {
    window.location.assign(url);
  }
  refresh(): void {
    this.reset();
    this.activate();
  }
  reset(): void {
    this.pause();
    this.items.set([]);
    this.cursor.set(null);
    this.loaded.set(false);
    this.error.set(false);
    this.versions.set({});
    this.versionErrors.set({});
    this.message.set('');
  }
}

@Component({
  selector: 'app-me-library-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DatePipe, CopyField, StatusChip, EmptyState, ErrorState],
  styleUrl: './me.page.css',
  template: `
    @if (store.loading()) {
      <p role="status">Loading licences...</p>
    }
    @if (store.error()) {
      <app-error-state (retry)="store.retry()" />
    }
    @if (store.message()) {
      <p role="alert">{{ store.message() }}</p>
    }
    @for (license of store.items(); track license.orderId) {
      <article class="account-row">
        <div class="library-heading">
          <h2>
            <a [routerLink]="['/listings', license.listingId]">{{ license.listingTitle }}</a>
          </h2>
          <app-status-chip
            [text]="license.status === 'ACTIVE' ? 'Active' : 'Revoked'"
            [tone]="license.status === 'ACTIVE' ? 'accent' : 'neutral'"
          />
        </div>
        <div class="row-meta">
          <time>Issued {{ license.issuedAt | date: 'mediumDate' }}</time>
          @if (license.revokedAt) {
            <time>Revoked {{ license.revokedAt | date: 'mediumDate' }}</time>
          }
        </div>
        <div class="actions">
          @if (license.key) {
            <button
              class="action reveal-key"
              type="button"
              [attr.aria-label]="revealed().has(license.orderId) ? 'Hide key' : 'Show key'"
              [attr.title]="revealed().has(license.orderId) ? 'Hide key' : 'Show key'"
              [attr.aria-expanded]="revealed().has(license.orderId)"
              (click)="toggleKey(license.orderId)"
            >
              <svg aria-hidden="true" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                @if (revealed().has(license.orderId)) {
                  <path d="m3 3 18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6C3.9 8.3 2 12 2 12s3.5 7 10 7a10 10 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2" />
                } @else {
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                  <circle cx="12" cy="12" r="3" />
                }
              </svg>
            </button>
          }
          <a class="action" [routerLink]="['/orders', license.orderId]">View order</a>
          @if (license.status === 'ACTIVE') {
            <button
              class="action"
              [disabled]="store.versionLoading()[license.listingId]"
              (click)="store.loadVersions(license)"
            >
              {{
                store.versionLoading()[license.listingId]
                  ? 'Loading versions...'
                  : 'Choose a version'
              }}
            </button>
          }
        </div>
        @if (license.key && revealed().has(license.orderId)) {
          <app-copy-field label="Licence key" [value]="license.key" />
        }
        @if (license.status === 'ACTIVE') {
          @if (store.versionErrors()[license.listingId]) {
            <p role="alert">Could not load versions. Try again.</p>
          }
          <div class="library-versions">
            @for (version of store.versions()[license.listingId] || []; track version.number) {
              <div class="library-version">
                <span>{{ version.number }}{{ version.current ? ' · Current' : '' }}</span>
                <button
                  class="action"
                  [disabled]="!version.artifactAvailable || !!store.downloading()"
                  (click)="store.download(license, version.number)"
                >
                  {{ version.artifactAvailable ? 'Download' : 'No artifact available' }}
                </button>
              </div>
            }
          </div>
          @if (store.versions()[license.listingId]?.length === 0) {
            <p>No versions available</p>
          }
        } @else {
          <p>Downloads are unavailable for a revoked licence.</p>
        }
      </article>
    }
    @if (store.loaded() && !store.loading() && !store.error() && !store.items().length) {
      <app-empty-state
        title="No licences yet"
        text="Software you buy will appear here with its licence key and downloads."
      />
    }
    @if (store.cursor()) {
      <button
        class="action show-more"
        [disabled]="store.loading() || store.refreshing()"
        (click)="store.load(true)"
      >
        Show more
      </button>
    }
  `,
})
export class MeLibraryTab {
  readonly store = inject(MeLibraryStore);
  readonly revealed = signal(new Set<string>());
  constructor() {
    const destroyRef = inject(DestroyRef);
    const stopReload = startAccountAutoReload(destroyRef, () => this.store.autoReload());
    const wasLoaded = this.store.loaded();
    this.store.activate();
    if (wasLoaded) this.store.autoReload();
    const unregister = inject(AuthStore).registerCleanup(() => {
      stopReload();
      this.revealed.set(new Set());
    });
    destroyRef.onDestroy(() => {
      unregister();
      this.store.reset();
      this.revealed.set(new Set());
    });
  }
  toggleKey(id: string): void {
    this.revealed.update((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
}
