import { Injectable, OnDestroy, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog-api';
import { EditListingRequest, EditListingResponse } from '../../core/api/api-models';
import { S3Uploader } from '../../core/media/s3-uploader';
import { ListingPhoto, PhotoUploadState } from './photo-picker';

interface MediaSave {
  id: string;
  photos: readonly ListingPhoto[];
  request: EditListingRequest;
  release: boolean;
}
interface UploadItem {
  photo: ListingPhoto;
  uploadedKey?: string;
  confirmedKey?: string;
}

@Injectable()
export class UploadQueueService implements OnDestroy {
  readonly busy = signal(false);
  readonly pending = signal(false);
  readonly states = signal<Readonly<Record<string, PhotoUploadState>>>({});
  readonly message = signal('');
  readonly failureStage = signal<'release' | 'photos' | 'final' | null>(null);
  readonly error = signal<unknown>(null);
  readonly removalsSaved = signal(false);
  readonly droppedAttributeIds = signal<readonly string[]>([]);
  private operation: MediaSave | null = null;
  private uploads: UploadItem[] = [];
  private released = false;
  private continueOnly = false;
  private readonly abort = new AbortController();
  private destroyed = false;

  constructor(
    private readonly catalog: CatalogApi,
    private readonly uploader: S3Uploader,
  ) {}

  start(operation: MediaSave): Promise<boolean> {
    if (this.busy() || this.pending() || this.destroyed) return Promise.resolve(false);
    this.operation = {
      ...operation,
      photos: [...operation.photos],
      request: { ...operation.request, attributes: [...operation.request.attributes] },
    };
    this.uploads = operation.photos
      .filter((photo) => photo.processed && !photo.key)
      .map((photo) => ({ photo }));
    this.released = !operation.release;
    this.continueOnly = false;
    this.removalsSaved.set(false);
    this.droppedAttributeIds.set([]);
    this.states.set(
      Object.fromEntries(
        this.uploads.map((item) => [item.photo.id, { stage: 'queued', progress: 0 }]),
      ),
    );
    this.pending.set(true);
    return this.run();
  }

  retry(): Promise<boolean> {
    return this.run();
  }
  discardRejectedSave(): void {
    if (this.busy() || this.released) return;
    this.pending.set(false);
    this.operation = null;
    this.states.set({});
  }
  continueSuccessful(): Promise<boolean> {
    if (!this.operation || this.busy() || !this.released) return Promise.resolve(false);
    this.continueOnly = true;
    return this.run();
  }

  private async run(): Promise<boolean> {
    const operation = this.operation;
    if (!operation || !this.pending() || this.busy() || this.destroyed) return false;
    this.busy.set(true);
    this.failureStage.set(null);
    this.error.set(null);
    try {
      if (!this.released) {
        this.message.set('Saving listing changes...');
        try {
          this.record(
            await firstValueFrom(
              this.catalog.editListing(operation.id, {
                ...operation.request,
                photos: operation.photos.flatMap((photo) => (photo.key ? [photo.key] : [])),
              }),
            ),
          );
          if (this.destroyed) return false;
          this.released = true;
          this.removalsSaved.set(true);
        } catch (error) {
          return this.fail('release', error);
        }
        if (!this.uploads.length) return this.complete();
      }

      if (!this.continueOnly) {
        for (const [index, item] of this.uploads.entries()) {
          if (this.destroyed) return false;
          if (item.confirmedKey) continue;
          this.message.set(`Uploading photo ${index + 1} of ${this.uploads.length}`);
          try {
            if (!item.uploadedKey) {
              this.state(item, 'uploading', 0);
              const policy = await firstValueFrom(this.catalog.photoUploadPolicy(operation.id));
              if (this.destroyed) return false;
              await this.uploader.upload(
                policy,
                item.photo.processed!.blob,
                'image/jpeg',
                (progress) => this.state(item, 'uploading', progress),
                this.abort.signal,
              );
              if (this.destroyed) return false;
              // A lost confirm response must retry this key, since the server may already have attached it.
              item.uploadedKey = policy.key;
            }
            this.state(item, 'confirming', 100);
            const keys = await firstValueFrom(
              this.catalog.confirmPhoto(operation.id, { key: item.uploadedKey }),
            );
            if (this.destroyed) return false;
            if (!keys.includes(item.uploadedKey))
              throw new Error('Photo confirmation did not include the uploaded key');
            item.confirmedKey = item.uploadedKey;
            this.state(item, 'done', 100);
          } catch (error) {
            if (this.destroyed) return false;
            this.state(item, 'failed', this.states()[item.photo.id]?.progress ?? 0);
            this.error.set(error);
          }
        }
        const failed = this.uploads.filter((item) => !item.confirmedKey).length;
        if (failed) {
          this.failureStage.set('photos');
          this.message.set(
            `${failed} ${failed === 1 ? 'photo could' : 'photos could'} not be uploaded.`,
          );
          return false;
        }
      }

      if (this.destroyed) return false;
      // The retained-only save runs once; repeating it would delete confirmations that survived a retry.
      const confirmed = new Map(this.uploads.map((item) => [item.photo.id, item.confirmedKey]));
      const photos = operation.photos.flatMap((photo) => {
        const key = photo.key ?? confirmed.get(photo.id);
        return key ? [key] : [];
      });
      if (!this.uploads.length && !operation.release) return this.complete();
      this.message.set('Saving photo order...');
      try {
        this.record(
          await firstValueFrom(
            this.catalog.editListing(operation.id, {
              ...operation.request,
              categoryId: operation.release ? null : operation.request.categoryId,
              photos,
            }),
          ),
        );
        if (this.destroyed) return false;
        return this.complete();
      } catch (error) {
        return this.fail('final', error);
      }
    } finally {
      this.busy.set(false);
    }
  }

  private state(item: UploadItem, stage: PhotoUploadState['stage'], progress: number): void {
    this.states.update((states) => ({ ...states, [item.photo.id]: { stage, progress } }));
  }
  private record(response: EditListingResponse): void {
    this.droppedAttributeIds.update((ids) => [
      ...new Set([...ids, ...response.droppedAttributeIds]),
    ]);
  }
  private fail(stage: 'release' | 'final', error: unknown): false {
    this.failureStage.set(stage);
    this.error.set(error);
    this.message.set(
      stage === 'release'
        ? 'Listing changes could not be saved. No new photos were uploaded.'
        : 'Photos are retained, but their final order could not be saved. Retry to finish.',
    );
    return false;
  }
  private complete(): true {
    this.pending.set(false);
    this.message.set('');
    return true;
  }
  ngOnDestroy(): void {
    this.destroyed = true;
    this.abort.abort();
  }
}
