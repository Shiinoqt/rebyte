import { BreakpointObserver } from '@angular/cdk/layout';
import { CdkDrag, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import {
  ChangeDetectionStrategy,
  Component,
  Injectable,
  input,
  OnDestroy,
  signal,
} from '@angular/core';
import { Subscription } from 'rxjs';
import {
  PhotoPipeline,
  PhotoProcessingError,
  ProcessedPhoto,
} from '../../core/media/photo-pipeline';

export interface ListingPhoto {
  id: string;
  name: string;
  url: string | null;
  key?: string;
  processed?: ProcessedPhoto;
}

export interface PhotoUploadState {
  stage: 'queued' | 'uploading' | 'confirming' | 'done' | 'failed';
  progress: number;
}

@Injectable()
export class PhotoSelection implements OnDestroy {
  readonly items = signal<readonly ListingPhoto[]>([]);
  readonly processing = signal(false);
  readonly errors = signal<readonly string[]>([]);
  readonly announcement = signal('');
  private serial = 0;
  private destroyed = false;

  constructor(private readonly pipeline: PhotoPipeline) {}

  hydrate(keys: readonly string[], urls: readonly string[]): void {
    this.items.set(
      keys.map((key, index) => ({
        id: `stored-${key}`,
        key,
        url: urls[index] ?? null,
        name: `Photo ${index + 1}`,
      })),
    );
  }

  async add(files: readonly File[]): Promise<void> {
    if (this.processing() || this.destroyed) return;
    this.processing.set(true);
    this.errors.set([]);
    try {
      for (const file of files) {
        if (this.destroyed) break;
        if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
          this.reject(`${file.name} is not a JPG, PNG or WebP.`);
          continue;
        }
        if (this.items().length >= 5) {
          this.reject('You can add up to 5 photos.');
          break;
        }
        const id = `local-${++this.serial}`;
        this.items.update((items) => [...items, { id, name: file.name, url: null }]);
        this.announcement.set(`Processing ${file.name}.`);
        try {
          const processed = await this.pipeline.process(file, { maxEdge: 2000, maxBytes: 2097152 });
          if (this.destroyed) {
            processed.cleanup();
            break;
          }
          this.items.update((items) =>
            items.map((item) =>
              item.id === id ? { ...item, url: processed.url, processed } : item,
            ),
          );
        } catch (error) {
          this.items.update((items) => items.filter((item) => item.id !== id));
          this.reject(
            error instanceof PhotoProcessingError && error.code === 'photo-too-large'
              ? `${file.name} could not fit within 2 MiB. Choose a smaller photo.`
              : `${file.name} could not be processed. Choose another photo.`,
          );
        }
      }
    } finally {
      this.processing.set(false);
      if (!this.destroyed)
        this.announcement.set(
          `${this.items().length} photos selected. The first photo is the cover.`,
        );
    }
  }

  move(from: number, to: number): void {
    if (
      this.processing() ||
      from === to ||
      from < 0 ||
      to < 0 ||
      from >= this.items().length ||
      to >= this.items().length
    )
      return;
    const items = [...this.items()];
    moveItemInArray(items, from, to);
    this.items.set(items);
    this.announcement.set(`Photo moved to position ${to + 1}. The first photo is the cover.`);
  }

  remove(index: number): void {
    if (this.processing()) return;
    const item = this.items()[index];
    if (!item) return;
    item.processed?.cleanup();
    this.items.update((items) => items.filter((_, i) => i !== index));
    this.announcement.set(`Photo removed. ${this.items().length} photos selected.`);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.items().forEach((item) => item.processed?.cleanup());
    this.items.set([]);
  }

  private reject(message: string): void {
    this.errors.update((errors) => [...errors, message]);
  }
}

@Component({
  selector: 'app-photo-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDrag, CdkDragHandle, CdkDropList],
  styleUrl: './photo-picker.css',
  template: `
    <section
      aria-labelledby="listing-photos-title"
      (dragover)="dragOver($event)"
      (drop)="filesDropped($event)"
    >
      <div class="heading">
        <h2 id="listing-photos-title">Photos</h2>
        <span class="count">{{ selection.items().length }} / 5</span>
      </div>
      <p id="listing-photos-hint" class="sr-only">
        Choose or drop up to five JPG, PNG or WebP photos. The first is your cover.
      </p>
      <div
        class="dropzone"
        [class.populated]="selection.items().length > 0"
        [attr.aria-disabled]="locked()"
      >
        @if (selection.items().length === 0) {
          <button
            type="button"
            class="add"
            [disabled]="locked()"
            aria-describedby="listing-photos-hint"
            (click)="fileInput.click()"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
            <span>Upload photos</span>
          </button>
        } @else {
          <div
            class="tiles"
            cdkDropList
            cdkDropListOrientation="mixed"
            [cdkDropListDisabled]="locked() || phone()"
            (cdkDropListDropped)="reorder($event.previousIndex, $event.currentIndex)"
          >
            @if (selection.items().length < 5) {
              <button
                type="button"
                class="add-tile"
                [disabled]="locked()"
                aria-label="Upload photos"
                aria-describedby="listing-photos-hint"
                (click)="fileInput.click()"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
              </button>
            }
            @for (photo of selection.items(); track photo.id; let i = $index) {
              <div class="tile" cdkDrag [cdkDragDisabled]="locked() || phone()">
                <div class="image">
                  @if (photo.url) {
                    <img [src]="photo.url" [alt]="'Selected photo ' + (i + 1)" />
                  } @else {
                    <span class="processing" role="status">Processing...</span>
                  }
                  @if (i === 0) {
                    <span class="cover">Cover</span>
                  }
                  <button
                    type="button"
                    class="remove"
                    [disabled]="locked()"
                    [attr.aria-label]="'Remove photo ' + (i + 1)"
                    (click)="selection.remove(i)"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="m6 6 12 12M6 18 18 6" />
                    </svg>
                  </button>
                </div>
                <div class="controls">
                  <button
                    type="button"
                    [disabled]="locked() || i === 0"
                    [attr.aria-label]="'Move photo ' + (i + 1) + ' earlier'"
                    (click)="reorder(i, i - 1)"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
                  </button>
                  <span class="drag-handle" cdkDragHandle aria-hidden="true"
                    ><svg viewBox="0 0 24 24">
                      <path d="M9 5h.01M9 12h.01M9 19h.01M15 5h.01M15 12h.01M15 19h.01" /></svg
                  ></span>
                  <button
                    type="button"
                    [disabled]="locked() || i === selection.items().length - 1"
                    [attr.aria-label]="'Move photo ' + (i + 1) + ' later'"
                    (click)="reorder(i, i + 1)"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
                  </button>
                </div>
                @if (states()[photo.id]; as state) {
                  <div class="upload-state" [class.failed]="state.stage === 'failed'">
                    <span>{{
                      state.stage === 'done'
                        ? 'Uploaded'
                        : state.stage === 'failed'
                          ? 'Upload failed'
                          : state.stage === 'confirming'
                            ? 'Confirming...'
                            : state.stage === 'uploading'
                              ? 'Uploading ' + state.progress + '%'
                              : 'Queued'
                    }}</span>
                    @if (state.stage === 'uploading') {
                      <progress
                        max="100"
                        [value]="state.progress"
                        [attr.aria-label]="'Photo ' + (i + 1) + ' upload progress'"
                      ></progress>
                    }
                  </div>
                }
              </div>
            }
          </div>
        }
      </div>
      <input
        #fileInput
        class="file-input"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        [disabled]="locked()"
        aria-label="Select listing photos"
        (change)="selected($event)"
      />
      @for (error of selection.errors(); track $index) {
        <p class="error" role="alert">{{ error }}</p>
      }
      <span class="sr-only" aria-live="polite">{{ selection.announcement() }}</span>
    </section>
  `,
})
export class PhotoPicker implements OnDestroy {
  readonly disabled = input(false);
  readonly states = input<Readonly<Record<string, PhotoUploadState>>>({});
  readonly phone = signal(false);
  private readonly breakpoints: Subscription;

  constructor(
    readonly selection: PhotoSelection,
    observer: BreakpointObserver,
  ) {
    this.breakpoints = observer
      .observe('(max-width: 900px)')
      .subscribe((state) => this.phone.set(state.matches));
  }

  locked(): boolean {
    return this.disabled() || this.selection.processing();
  }
  reorder(from: number, to: number): void {
    if (!this.locked()) this.selection.move(from, to);
  }
  selected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!this.locked()) void this.selection.add(Array.from(input.files ?? []));
    input.value = '';
  }
  dragOver(event: DragEvent): void {
    event.preventDefault();
  }
  filesDropped(event: DragEvent): void {
    event.preventDefault();
    if (!this.locked()) void this.selection.add(Array.from(event.dataTransfer?.files ?? []));
  }
  ngOnDestroy(): void {
    this.breakpoints.unsubscribe();
  }
}
