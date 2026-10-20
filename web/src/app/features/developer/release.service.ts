import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, forkJoin, fromEvent, Observable, Subscription, takeUntil } from 'rxjs';
import {
  ListingDetailResponse,
  ReleaseVersionRequest,
  VersionResponse,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { UserApi } from '../../core/api/user-api';
import { AuthStore } from '../../core/auth/auth.store';
import { S3Uploader } from '../../core/media/s3-uploader';

export const ARTIFACT_MAX_BYTES = 104857600;
const VERSION = /^[0-9]{1,9}(?:\.[0-9]{1,9}){0,7}$/;

export function compareVersions(a: string, b: string): number {
  const left = a.split('.').map(Number);
  const right = b.split('.').map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const difference = (left[i] ?? 0) - (right[i] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

export function versionError(value: string, existing: VersionResponse[]): string | null {
  if (!VERSION.test(value) || value.trim() !== value) return 'invalid-version-number';
  if (existing.some((v) => compareVersions(value, v.number) === 0))
    return 'duplicate-version-number';
  if (existing.some((v) => compareVersions(value, v.number) < 0))
    return 'version-number-not-increasing';
  return null;
}

export interface ArtifactState {
  file: File | null;
  phase: 'idle' | 'policy' | 'uploading' | 'confirming' | 'done' | 'failed';
  progress: number;
  error: string | null;
}

function code(error: unknown): string {
  return error && typeof error === 'object' && 'code' in error && typeof error.code === 'string'
    ? error.code
    : 'unknown-error';
}

@Injectable()
export class ReleaseService {
  private readonly catalog = inject(CatalogApi);
  private readonly users = inject(UserApi);
  private readonly auth = inject(AuthStore);
  private readonly uploader = inject(S3Uploader);
  private readonly destroyRef = inject(DestroyRef);
  private request: Subscription | null = null;
  private controller = new AbortController();
  private listingId = '';
  private owner = '';

  readonly status = signal<'loading' | 'ready' | 'forbidden' | 'error'>('loading');
  readonly listing = signal<ListingDetailResponse | null>(null);
  readonly versions = signal<VersionResponse[]>([]);
  readonly releasing = signal(false);
  readonly releaseError = signal<string | null>(null);
  readonly uploads = signal<Record<string, ArtifactState>>({});
  readonly notice = signal('');

  constructor() {
    const unregister = this.auth.registerCleanup(() => this.clear());
    this.destroyRef.onDestroy(() => {
      unregister();
      this.clear();
    });
  }

  load(id: string): void {
    this.clear();
    this.listingId = id;
    const sub = this.auth.identity()?.sub;
    if (!sub) return;
    this.owner = sub;
    const controller = this.controller;
    this.status.set('loading');
    this.request = forkJoin({
      listing: this.catalog.listing(id),
      profile: this.users.getPublicProfile(sub),
    }).subscribe({
      next: ({ listing, profile }) => {
        if (!this.active(controller)) return;
        this.listing.set(listing);
        if (listing.type !== 'SOFTWARE' || listing.sellerSub !== sub || !profile.isDeveloper) {
          this.status.set('forbidden');
          return;
        }
        this.catalog
          .versions(id)
          .pipe(takeUntil(fromEvent(controller.signal, 'abort')))
          .subscribe({
            next: (versions) => {
              if (this.active(controller)) {
                this.setVersions(versions);
                this.status.set('ready');
              }
            },
            error: () => {
              if (this.active(controller)) this.status.set('error');
            },
          });
      },
      error: () => {
        if (this.active(controller)) this.status.set('error');
      },
    });
  }

  async release(request: ReleaseVersionRequest): Promise<boolean> {
    if (this.status() !== 'ready' || this.releasing()) return false;
    const invalid = versionError(request.number, this.versions());
    this.releaseError.set(invalid);
    if (invalid) return false;
    const controller = this.controller;
    this.releasing.set(true);
    this.notice.set('');
    try {
      const version = await this.read(
        this.catalog.releaseVersion(this.listingId, request),
        controller,
      );
      if (!this.active(controller)) return false;
      this.setVersions([
        ...this.versions()
          .filter((v) => v.number !== version.number)
          .map((v) => ({ ...v, current: false })),
        version,
      ]);
      this.notice.set(`Version ${version.number} released.`);
      return true;
    } catch (error) {
      if (this.active(controller)) this.releaseError.set(code(error));
      return false;
    } finally {
      if (this.active(controller)) this.releasing.set(false);
    }
  }

  selectFile(number: string, file: File | null): void {
    if (!this.attachable(number) || this.busy(number)) return;
    const invalid = file && (file.size < 1 || file.size > ARTIFACT_MAX_BYTES);
    this.patch(number, {
      file,
      phase: invalid ? 'failed' : 'idle',
      progress: 0,
      error: invalid ? 'invalid-artifact-size' : null,
    });
  }

  busy(number: string): boolean {
    return ['policy', 'uploading', 'confirming'].includes(this.uploads()[number]?.phase ?? 'idle');
  }

  async upload(number: string): Promise<void> {
    const file = this.uploads()[number]?.file;
    if (
      !this.attachable(number) ||
      this.busy(number) ||
      !file ||
      file.size < 1 ||
      file.size > ARTIFACT_MAX_BYTES
    )
      return;
    const controller = this.controller;
    this.patch(number, { phase: 'policy', progress: 0, error: null });
    try {
      const policy = await this.read(
        this.catalog.artifactUploadPolicy(this.listingId, number),
        controller,
      );
      if (!this.active(controller)) return;
      this.patch(number, { phase: 'uploading' });
      await this.uploader.upload(
        policy,
        file,
        'application/octet-stream',
        (progress) => {
          if (this.active(controller)) this.patch(number, { progress });
        },
        controller.signal,
      );
      if (!this.active(controller)) return;
      this.patch(number, { phase: 'confirming' });
      const confirmed = await this.read(
        this.catalog.confirmArtifact(this.listingId, number, { key: policy.key }),
        controller,
      );
      if (!this.active(controller)) return;
      this.setVersions(this.versions().map((v) => (v.number === confirmed.number ? confirmed : v)));
      this.patch(number, { phase: 'done', file: null, progress: 100 });
      await this.refreshVersions(controller);
    } catch (error) {
      if (!this.active(controller)) return;
      this.patch(number, { phase: 'failed', error: code(error) });
      if (code(error) === 'artifact-already-attached') await this.refreshVersions(controller);
    }
  }

  private attachable(number: string): boolean {
    return (
      this.status() === 'ready' &&
      this.versions().some((v) => v.number === number && !v.artifactAvailable)
    );
  }

  private async refreshVersions(controller: AbortController): Promise<void> {
    const before = this.versions();
    try {
      const versions = await this.read(this.catalog.versions(this.listingId), controller);
      // An upload refresh can finish after another release or attachment has already updated the list.
      if (this.active(controller) && this.versions() === before) this.setVersions(versions);
    } catch {
      if (this.active(controller))
        this.notice.set(
          'Could not refresh versions. Reload this page to check their latest state.',
        );
    }
  }

  private setVersions(versions: VersionResponse[]): void {
    this.versions.set([...versions].sort((a, b) => compareVersions(b.number, a.number)));
  }

  private patch(number: string, update: Partial<ArtifactState>): void {
    this.uploads.update((states) => ({
      ...states,
      [number]: {
        ...(states[number] ?? { file: null, phase: 'idle', progress: 0, error: null }),
        ...update,
      },
    }));
  }

  private read<T>(source: Observable<T>, controller: AbortController): Promise<T> {
    return firstValueFrom(source.pipe(takeUntil(fromEvent(controller.signal, 'abort'))));
  }

  private active(controller: AbortController): boolean {
    return (
      controller === this.controller &&
      !controller.signal.aborted &&
      this.auth.identity()?.sub === this.owner
    );
  }

  private clear(): void {
    this.controller.abort();
    this.controller = new AbortController();
    this.request?.unsubscribe();
    this.request = null;
    this.status.set('forbidden');
    this.listing.set(null);
    this.versions.set([]);
    this.uploads.set({});
    this.releasing.set(false);
    this.releaseError.set(null);
    this.notice.set('');
  }
}
