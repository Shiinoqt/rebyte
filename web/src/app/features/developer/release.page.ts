import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ModalDialog } from '../../shared/ui/dialog';
import { ReleaseService, versionError } from './release.service';

const MESSAGES: Record<string, string> = {
  'invalid-version-number':
    'Use 1 to 8 numeric parts separated by dots, with at most 9 digits per part.',
  'duplicate-version-number':
    'This version already exists. Trailing zeros count as the same version.',
  'version-number-not-increasing': 'Choose a version number higher than every existing release.',
  'invalid-artifact-size': 'Choose a file between 1 byte and 100 MiB.',
  'artifact-already-attached':
    'An installer is already attached to this version. Reload to see its latest state.',
  'artifact-not-uploaded': 'The installer could not be verified. Retry the upload.',
  'artifact-storage-unavailable': 'Installer storage is unavailable. Try again shortly.',
  'access-denied': 'You no longer have permission to change this listing.',
  'rate-limited': 'Too many requests. Wait a moment and try again.',
};

@Component({
  selector: 'app-release-page',
  standalone: true,
  imports: [DatePipe, RouterLink, ReactiveFormsModule, ModalDialog],
  providers: [ReleaseService],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./release.page.css'],
  template: `
    <section class="release-page">
      <a class="back" [routerLink]="['/listings', listingId]">Back to listing</a>
      <header class="page-heading">
        <div>
          <h1>Software releases</h1>
          @if (service.listing(); as listing) {
            <p class="subtitle">{{ listing.title }}</p>
          }
        </div>
        @if (service.status() === 'ready') {
          <button
            type="button"
            class="primary"
            (click)="openRelease()"
            [disabled]="service.releasing()"
          >
            Release a new version
          </button>
        }
      </header>
      @if (service.status() === 'loading') {
        <p role="status">Loading releases...</p>
      } @else if (service.status() === 'forbidden') {
        <div class="state-panel">
          <h2>Release management is unavailable</h2>
          <p>
            Only the owner of a software listing with an active developer profile can manage its
            releases.
          </p>
          <a routerLink="/settings">Settings and profile</a>
        </div>
      } @else if (service.status() === 'error') {
        <div class="state-panel" role="alert">
          <h2>We couldn't load these releases.</h2>
          <button type="button" (click)="reload()">Try again</button>
        </div>
      } @else {
        <p class="helper">Installers up to 100 MiB. Attached files cannot be replaced.</p>
        <p class="notice" role="status">{{ service.notice() }}</p>
        <div class="versions">
          @for (version of service.versions(); track version.number; let index = $index) {
            <article class="version-card" [attr.aria-labelledby]="'version-' + index">
              <header class="version-heading">
                <h2 [id]="'version-' + index">v{{ version.number }}</h2>
                @if (version.current) {
                  <span class="chip current">Latest release</span>
                }
                <time [attr.datetime]="version.release">{{
                  version.release | date: 'mediumDate'
                }}</time>
              </header>
              @if (version.notes) {
                <p class="notes">{{ version.notes }}</p>
              }
              @if (version.artifactAvailable) {
                <p class="attached">Installer attached</p>
              } @else {
                <input
                  #installerPicker
                  type="file"
                  hidden
                  [attr.aria-label]="'Installer for v' + version.number"
                  [disabled]="service.busy(version.number)"
                  (change)="selectFile(version.number, $event)"
                />
                <div class="installer-footer">
                  @if (service.uploads()[version.number]?.file; as file) {
                    <button
                      type="button"
                      class="file-name"
                      (click)="chooseInstaller(installerPicker)"
                      [disabled]="service.busy(version.number)"
                      [attr.aria-label]="'Change installer: ' + file.name"
                    >
                      {{ file.name }} <span>{{ fileSize(file.size) }}</span>
                    </button>
                  } @else {
                    <span class="helper">No installer yet</span>
                  }
                  @if (!service.busy(version.number)) {
                    <button
                      type="button"
                      class="primary attach-installer"
                      (click)="attachmentAction(version.number, installerPicker)"
                    >
                      <svg aria-hidden="true" viewBox="0 0 24 24">
                        <path d="M12 16V4m-4 4 4-4 4 4M4 16v4h16v-4" />
                      </svg>
                      {{ attachmentLabel(version.number) }}
                    </button>
                  }
                </div>
                @if (service.uploads()[version.number]; as upload) {
                  @if (service.busy(version.number)) {
                    <div role="status" class="upload-progress">
                      <label [for]="'progress-' + index">{{
                        upload.phase === 'policy'
                          ? 'Preparing upload'
                          : upload.phase === 'confirming'
                            ? 'Confirming installer'
                            : 'Uploading installer: ' + upload.progress + '%'
                      }}</label>
                      <progress
                        [id]="'progress-' + index"
                        max="100"
                        [value]="upload.progress"
                      ></progress>
                    </div>
                  }
                  @if (upload.error) {
                    <p class="error" role="alert">{{ message(upload.error) }}</p>
                  }
                }
              }
            </article>
          } @empty {
            <div class="state-panel">
              <h2>No releases yet</h2>
              <p>Release your first version to attach an installer.</p>
            </div>
          }
        </div>
      }
    </section>
    @if (dialog()) {
      <app-modal-dialog
        [heading]="
          dialog() === 'form'
            ? 'Release a new version'
            : 'Release v' + form.controls.number.value + '?'
        "
        (closed)="closeDialog()"
      >
        @if (dialog() === 'form') {
          <form [formGroup]="form" (ngSubmit)="reviewRelease()">
            <div class="field">
              <label for="version-number">Version number</label>
              <input
                id="version-number"
                formControlName="number"
                autocomplete="off"
                class="mono"
                aria-describedby="version-hint version-error"
                [attr.aria-invalid]="fieldError() ? 'true' : null"
              />
              <p id="version-hint" class="helper">
                Use a higher version number, such as 1.1 or 2.0. Up to 8 numeric parts, 9 digits
                each.
              </p>
              <p id="version-error" class="error" role="alert">
                {{ fieldError() ? message(fieldError()!) : '' }}
              </p>
            </div>
            <div class="field">
              <label for="release-notes">Release notes <span class="helper">(optional)</span></label
              ><textarea id="release-notes" formControlName="notes" rows="5"></textarea>
            </div>
            <div class="dialog-actions">
              <button type="button" (click)="closeDialog()">Cancel</button
              ><button class="primary" type="submit">Review release</button>
            </div>
          </form>
        } @else {
          <p>
            This release is permanent. Its version number and release notes cannot be edited, and
            the version cannot be removed.
          </p>
          @if (form.controls.notes.value) {
            <p class="notes confirmation-notes">{{ form.controls.notes.value }}</p>
          }
          @if (service.releaseError()) {
            <p class="error" role="alert">{{ message(service.releaseError()!) }}</p>
          }
          <div class="dialog-actions">
            <button type="button" [disabled]="service.releasing()" (click)="dialog.set('form')">
              Go back
            </button>
            <button
              type="button"
              class="primary"
              [disabled]="service.releasing()"
              (click)="confirmRelease()"
            >
              {{ service.releasing() ? 'Releasing...' : 'Release version' }}
            </button>
          </div>
        }
      </app-modal-dialog>
    }
  `,
})
export class ReleasePage {
  readonly service = inject(ReleaseService);
  private readonly route = inject(ActivatedRoute);
  listingId = '';
  readonly dialog = signal<'form' | 'confirm' | null>(null);
  readonly fieldError = signal<string | null>(null);
  readonly form = new FormGroup({
    number: new FormControl('', { nonNullable: true }),
    notes: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.listingId = params.get('id') ?? this.route.parent?.snapshot.paramMap.get('id') ?? '';
      this.dialog.set(null);
      this.service.load(this.listingId);
    });
    this.form.controls.number.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.fieldError.set(null));
  }

  reload(): void {
    this.service.load(this.listingId);
  }
  openRelease(): void {
    if (this.service.status() !== 'ready' || this.service.releasing()) return;
    this.form.reset();
    this.fieldError.set(null);
    this.service.releaseError.set(null);
    this.dialog.set('form');
  }
  closeDialog(): void {
    this.dialog.set(null);
  }
  reviewRelease(): void {
    const error = versionError(this.form.controls.number.value, this.service.versions());
    this.fieldError.set(error);
    if (!error) {
      this.service.releaseError.set(null);
      this.dialog.set('confirm');
    }
  }
  async confirmRelease(): Promise<void> {
    if (this.dialog() !== 'confirm') return;
    const { number, notes } = this.form.getRawValue();
    if (await this.service.release({ number, notes: notes || null })) this.dialog.set(null);
    else if (
      [
        'duplicate-version-number',
        'invalid-version-number',
        'version-number-not-increasing',
      ].includes(this.service.releaseError() ?? '')
    ) {
      this.fieldError.set(this.service.releaseError());
      this.dialog.set('form');
    }
  }
  selectFile(number: string, event: Event): void {
    this.service.selectFile(number, (event.target as HTMLInputElement).files?.[0] ?? null);
  }
  chooseInstaller(input: HTMLInputElement): void {
    input.value = '';
    input.click();
  }
  attachmentLabel(number: string): string {
    const upload = this.service.uploads()[number];
    if (upload?.error === 'invalid-artifact-size') return 'Choose another file';
    if (!upload?.file) return 'Attach installer';
    return upload.phase === 'failed' ? 'Retry upload' : 'Upload installer';
  }
  attachmentAction(number: string, input: HTMLInputElement): void {
    const upload = this.service.uploads()[number];
    if (!upload?.file || upload.error === 'invalid-artifact-size') this.chooseInstaller(input);
    else void this.service.upload(number);
  }
  fileSize(bytes: number): string {
    return `${(bytes / 1048576).toFixed(2)} MiB`;
  }
  message(error: string): string {
    return MESSAGES[error] ?? 'This action could not be completed. Try again.';
  }
}
