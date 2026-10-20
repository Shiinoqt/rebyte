import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { authErrorCopy } from '../auth/auth-flow.service';
import { AppError } from '../../core/http/app-error';
import { MotionPreference } from '../../core/shell/motion-preference';
import { errorCopy } from '../../core/http/error-copy';
import { PhotoProcessingError } from '../../core/media/photo-pipeline';
import { ModalDialog } from '../../shared/ui/dialog';
import { Banner, BusyButton, ErrorState, Skeleton, ToastService } from '../../shared/ui/feedback';
import { FormField } from '../../shared/ui/forms';
import { AccountLayout } from '../profile/account-layout';
import { GENERATED_HANDLE } from '../profile/account-summary';
import { SettingsService } from './settings.service';

type ProfileField = 'username' | 'location' | 'bio';
type PasswordField = 'current' | 'next';

const USERNAME_HINT = '3 to 30 characters: lowercase letters, digits, _ or -.';
const USERNAME_RULE = `Use ${USERNAME_HINT}`;
const USERNAME_PATTERN = /^[a-z0-9_-]{3,30}$/;
const LOCATION_MAX = 80;
const BIO_MAX = 500;
const USERNAME_ERRORS: Readonly<Record<string, string>> = {
  'username-conflict': 'That username is taken.',
  'reserved-handle': "That username isn't available.",
  'invalid-username': USERNAME_RULE,
};
const PHOTO_ERRORS: Readonly<Record<string, (name: string) => string>> = {
  'photo-type': (name) => `${name} is not a JPG, PNG or WebP.`,
  'photo-too-large': (name) => `${name} is larger than 512 KB.`,
  'photo-empty': (name) => `${name} is empty.`,
  'photo-processing-failed': (name) => `${name} could not be read. Try another photo.`,
};
const EMAIL_CHANGE_REASON =
  "Email changes aren't available yet: they need a verification step that isn't set up.";

const UNKNOWN_ERROR: AppError = {
  status: 500,
  code: 'unknown-error',
  type: 'about:blank',
  fieldErrors: {},
  extras: {},
};

function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'string'
  );
}

function optional(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

@Component({
  selector: 'app-settings-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AccountLayout, Banner, BusyButton, ErrorState, FormField, ModalDialog, Skeleton],
  providers: [SettingsService],
  styleUrl: './settings.page.css',
  template: `
    <app-account-layout active="settings">
      <section class="settings-content" aria-labelledby="settings-title">
        @if (generatedHandle()) {
          <app-banner
            style="margin-bottom: var(--space-lg)"
            text="Your username was generated for you."
            actionLabel="Choose a username"
            (action)="chooseUsername()"
          />
        }
        <header class="settings-head">
          <h1 id="settings-title" tabindex="-1">Account settings</h1>
          <p class="sub">Profile info and login details</p>
        </header>

        @switch (service.status()) {
          @case ('loading') {
            <app-skeleton label="Loading your settings" shape="line" />
          }
          @case ('error') {
            <app-error-state (retry)="service.load()" />
          }
          @case ('ready') {
            <div class="avatar-row">
              <div class="avatar-preview" [attr.aria-busy]="service.avatarBusy()">
                @if (service.avatar(); as url) {
                  <img [src]="url" alt="Your profile photo" />
                } @else {
                  <span aria-hidden="true">{{ initial() }}</span>
                }
                @if (service.avatarProgress(); as progress) {
                  <div
                    class="progress"
                    role="progressbar"
                    aria-label="Uploading photo"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    [attr.aria-valuenow]="progress"
                  >
                    <span [style.width.%]="progress"></span>
                  </div>
                }
              </div>
              <div class="avatar-actions">
                <div class="avatar-buttons">
                  <button
                    type="button"
                    class="btn neutral"
                    [disabled]="service.avatarBusy()"
                    aria-describedby="settings-avatar-hint"
                    (click)="fileInput.click()"
                  >
                    {{ service.avatar() ? 'Change photo' : 'Upload photo' }}
                  </button>
                  @if (service.avatar()) {
                    <button
                      type="button"
                      class="link remove"
                      [disabled]="service.avatarBusy()"
                      (click)="removeAvatar()"
                    >
                      Remove
                    </button>
                  }
                </div>
                <input
                  #fileInput
                  id="settings-avatar-file"
                  class="visually-hidden"
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  tabindex="-1"
                  aria-hidden="true"
                  (change)="pickAvatar($event)"
                />
                <p class="hint" id="settings-avatar-hint">
                  JPG, PNG or WebP, up to 512 KB. Square works best.
                </p>
                @if (avatarError()) {
                  <p class="field-error" id="settings-avatar-error" role="alert">
                    {{ avatarError() }}
                  </p>
                }
              </div>
            </div>

            <h2 class="section-label">Profile</h2>
            <div class="profile-grid">
              <app-form-field
                [id]="'settings-username'"
                label="Username"
                autocomplete="username"
                [hint]="profileErrors().username ? '' : usernameHint"
                [value]="username()"
                [error]="profileErrors().username ?? ''"
                (valueChange)="username.set($event)"
              />
              <app-form-field
                [id]="'settings-location'"
                label="Location"
                autocomplete="address-level2"
                [value]="location()"
                [error]="profileErrors().location ?? ''"
                (valueChange)="location.set($event)"
              />
            </div>
            <div class="bio">
              <app-form-field
                [id]="'settings-bio'"
                label="Bio"
                kind="textarea"
                [rows]="3"
                [value]="bio()"
                [error]="profileErrors().bio ?? ''"
                (valueChange)="bio.set($event)"
              />
              <p class="counter" id="settings-bio-count" [class.over]="bio().length > bioMax">
                {{ bio().length }}/{{ bioMax }}
              </p>
            </div>

            <h2 class="section-label">Login</h2>
            <div class="login">
              <div class="email-row">
                <div class="readonly">
                  <span class="readonly-label">Email</span>
                  <span class="readonly-value" id="settings-email">{{
                    service.email() ?? 'Unavailable'
                  }}</span>
                </div>
                <button
                  type="button"
                  class="btn neutral"
                  disabled
                  aria-disabled="true"
                  aria-describedby="settings-email-reason"
                >
                  Change email
                </button>
              </div>
              <p class="hint" id="settings-email-reason">{{ emailChangeReason }}</p>

              @if (passwordOpen()) {
                <div class="password" role="group" aria-label="Change password">
                  <app-form-field
                    [id]="'settings-current-password'"
                    label="Current password"
                    type="password"
                    autocomplete="current-password"
                    [value]="currentPassword()"
                    [error]="passwordErrors().current ?? ''"
                    (valueChange)="currentPassword.set($event)"
                  />
                  <app-form-field
                    [id]="'settings-new-password'"
                    label="New password"
                    type="password"
                    autocomplete="new-password"
                    [value]="newPassword()"
                    [error]="passwordErrors().next ?? ''"
                    (valueChange)="newPassword.set($event)"
                  />
                  @if (passwordError()) {
                    <p class="form-error" id="settings-password-error" role="alert">
                      {{ passwordError() }}
                    </p>
                  }
                  <div class="row">
                    <app-busy-button
                      label="Update password"
                      [busy]="passwordBusy()"
                      (pressed)="updatePassword()"
                    />
                    <button type="button" class="btn neutral" (click)="closePassword()">
                      Close
                    </button>
                  </div>
                </div>
              } @else {
                <button type="button" class="btn neutral" (click)="openPassword()">
                  Change password
                </button>
              }
            </div>

            <h2 class="section-label">Notifications</h2>
            <div class="switch-row">
              <label for="settings-email-enabled"
                >Email me about messages, new versions and orders</label
              >
              <input
                id="settings-email-enabled"
                type="checkbox"
                role="switch"
                [checked]="service.emailEnabled() === true"
                [disabled]="service.emailEnabled() === null || preferenceBusy()"
                aria-describedby="settings-email-enabled-help"
                (change)="toggleEmail($event)"
              />
            </div>
            <p class="hint" id="settings-email-enabled-help">
              {{
                service.emailEnabled() === null
                  ? "We couldn't load this setting. Reload the page to try again."
                  : 'Optional message and release emails can be disabled here. Order and payment emails are always sent.'
              }}
            </p>

            <h2 class="section-label">Accessibility</h2>
            <div class="switch-row">
              <label for="settings-reduced-motion">Reduce motion</label>
              <input
                id="settings-reduced-motion"
                type="checkbox"
                role="switch"
                [checked]="motion.enabled()"
                [disabled]="motion.systemReducedMotion()"
                aria-describedby="settings-reduced-motion-help"
                (change)="setReducedMotion($event)"
              />
            </div>
            <p class="hint" id="settings-reduced-motion-help">
              {{
                motion.systemReducedMotion()
                  ? 'Your device already reduces motion, so this setting is on.'
                  : 'Reduce animation and scrolling effects across Rebyte.'
              }}
            </p>

            <h2 class="section-label">Developer</h2>
            <div class="developer-row">
              <span class="developer-status">{{ developerLabel() }}</span>
              @if (service.isDeveloper() === false) {
                <button type="button" class="btn neutral" (click)="developerDialog.set(true)">
                  Become a developer
                </button>
              }
            </div>

            <div class="footer">
              <app-busy-button label="Save changes" [busy]="saving()" (pressed)="save()" />
              <button type="button" class="btn neutral" (click)="cancel()">Cancel</button>
              <button type="button" class="logout" [disabled]="loggingOut()" (click)="logout()">
                Log out
              </button>
            </div>
          }
        }
      </section>
    </app-account-layout>

    @if (developerDialog()) {
      <app-modal-dialog
        heading="Become a developer?"
        size="confirm"
        (closed)="developerDialog.set(false)"
      >
        <p class="dialog-body">
          This unlocks software publishing on your account. It can't be turned off later.
        </p>
        <div class="dialog-actions">
          <app-busy-button
            label="Become a developer"
            [busy]="activating()"
            (pressed)="activateDeveloper()"
          />
          <button type="button" class="btn neutral" autofocus (click)="developerDialog.set(false)">
            Not now
          </button>
        </div>
      </app-modal-dialog>
    }

    @if (leavePrompt()) {
      <app-modal-dialog
        heading="Leave without saving?"
        size="confirm"
        (closed)="settleLeave(false)"
      >
        <p class="dialog-body">Your changes on this page will be lost.</p>
        <div class="dialog-actions">
          <button type="button" class="btn danger" (click)="settleLeave(true)">Leave</button>
          <button type="button" class="btn neutral" autofocus (click)="settleLeave(false)">
            Keep editing
          </button>
        </div>
      </app-modal-dialog>
    }
  `,
})
export class SettingsPage {
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly service = inject(SettingsService);
  readonly motion = inject(MotionPreference);
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment);
  readonly generatedHandle = computed(() =>
    GENERATED_HANDLE.test(this.service.account()?.username ?? ''),
  );

  readonly usernameHint = USERNAME_HINT;
  readonly bioMax = BIO_MAX;
  readonly emailChangeReason = EMAIL_CHANGE_REASON;

  readonly username = signal('');
  readonly location = signal('');
  readonly bio = signal('');
  readonly profileErrors = signal<Partial<Record<ProfileField, string>>>({});
  readonly saving = signal(false);
  readonly avatarError = signal('');

  readonly passwordOpen = signal(false);
  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly passwordErrors = signal<Partial<Record<PasswordField, string>>>({});
  readonly passwordError = signal('');
  readonly passwordBusy = signal(false);

  readonly preferenceBusy = signal(false);
  readonly developerDialog = signal(false);
  readonly activating = signal(false);
  readonly loggingOut = signal(false);
  readonly leavePrompt = signal(false);
  private leaveResolver: ((leave: boolean) => void) | null = null;
  private bypassGuard = false;

  readonly initial = computed(() =>
    (this.service.account()?.username || '?').charAt(0).toUpperCase(),
  );
  readonly developerLabel = computed(() => {
    const developer = this.service.isDeveloper();
    if (developer === null) return 'Checking...';
    return developer ? 'Developer profile: active' : 'Not activated';
  });
  readonly dirty = computed(() => {
    const account = this.service.account();
    if (!account) return false;
    return (
      this.username().trim().toLowerCase() !== account.username ||
      optional(this.location()) !== (account.location ?? null) ||
      optional(this.bio()) !== (account.bio ?? null)
    );
  });

  constructor() {
    this.service.load();
    effect(() => {
      if (this.fragment() === 'settings-username' && this.service.status() === 'ready') {
        this.chooseUsername();
      }
    });
    // The form copies the account only when a load completes, so an avatar change or a
    // preference write never overwrites unsaved typing.
    let previous: string | null = null;
    effect(() => {
      const status = this.service.status();
      if (status === 'ready' && previous !== 'ready') {
        const account = untracked(() => this.service.account());
        if (account) {
          this.username.set(account.username);
          this.location.set(account.location ?? '');
          this.bio.set(account.bio ?? '');
        }
      }
      previous = status;
    });
  }

  chooseUsername(): void {
    this.focusLater('#settings-username');
  }

  canLeave(): boolean | Promise<boolean> {
    if (this.bypassGuard || !this.dirty()) return true;
    this.leavePrompt.set(true);
    return new Promise<boolean>((resolve) => (this.leaveResolver = resolve));
  }

  settleLeave(leave: boolean): void {
    this.leavePrompt.set(false);
    const resolve = this.leaveResolver;
    this.leaveResolver = null;
    resolve?.(leave);
  }

  async save(): Promise<void> {
    if (this.saving()) return;
    const username = this.username().trim().toLowerCase();
    const location = optional(this.location());
    const bio = optional(this.bio());
    const errors: Partial<Record<ProfileField, string>> = {};
    if (!USERNAME_PATTERN.test(username)) errors.username = USERNAME_RULE;
    if ((location ?? '').length > LOCATION_MAX) errors.location = 'Use at most 80 characters.';
    if ((bio ?? '').length > BIO_MAX) errors.bio = 'Use at most 500 characters.';
    this.profileErrors.set(errors);
    if (Object.keys(errors).length > 0) {
      this.focusFirstError(errors);
      return;
    }
    this.saving.set(true);
    try {
      await this.service.saveProfile({ username, location, bio });
      this.username.set(username);
      this.location.set(location ?? '');
      this.bio.set(bio ?? '');
      this.toasts.show({ text: 'Changes saved', kind: 'success' });
    } catch (error) {
      const code = isAppError(error) ? error.code : '';
      if (Object.hasOwn(USERNAME_ERRORS, code)) {
        const usernameErrors = { username: USERNAME_ERRORS[code] };
        this.profileErrors.set(usernameErrors);
        this.focusFirstError(usernameErrors);
      } else {
        this.toasts.show({ text: this.copy(error), kind: 'error' });
      }
    } finally {
      this.saving.set(false);
    }
  }

  cancel(): void {
    void this.router.navigateByUrl('/me');
  }

  async pickAvatar(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.avatarError.set('');
    try {
      await this.service.changeAvatar(file);
      this.toasts.show({ text: 'Profile photo updated', kind: 'success' });
    } catch (error) {
      if (error instanceof PhotoProcessingError) {
        this.avatarError.set(PHOTO_ERRORS[error.code](file.name));
      } else {
        this.toasts.show({ text: "We couldn't upload the photo. Try again.", kind: 'error' });
      }
    }
  }

  async removeAvatar(): Promise<void> {
    try {
      await this.service.removeAvatar();
      this.toasts.show({ text: 'Profile photo removed', kind: 'success' });
    } catch (error) {
      this.toasts.show({ text: this.copy(error), kind: 'error' });
    }
  }

  openPassword(): void {
    this.passwordOpen.set(true);
    this.focusLater('#settings-current-password');
  }

  closePassword(): void {
    this.passwordOpen.set(false);
    this.currentPassword.set('');
    this.newPassword.set('');
    this.passwordErrors.set({});
    this.passwordError.set('');
  }

  async updatePassword(): Promise<void> {
    if (this.passwordBusy()) return;
    const errors: Partial<Record<PasswordField, string>> = {};
    if (!this.currentPassword()) errors.current = 'Enter your password.';
    if (!this.newPassword()) errors.next = 'Enter a new password.';
    this.passwordErrors.set(errors);
    this.passwordError.set('');
    if (errors.current || errors.next) {
      this.focusLater(errors.current ? '#settings-current-password' : '#settings-new-password');
      return;
    }
    this.passwordBusy.set(true);
    try {
      await this.service.updatePassword(this.currentPassword(), this.newPassword());
      this.closePassword();
      this.toasts.show({ text: 'Password updated', kind: 'success' });
    } catch (error) {
      const name = typeof error === 'object' && error !== null ? (error as Error).name : '';
      this.passwordError.set(
        name === 'NotAuthorizedException'
          ? 'Your current password is not right.'
          : authErrorCopy(error),
      );
    } finally {
      this.passwordBusy.set(false);
    }
  }

  setReducedMotion(event: Event): void {
    this.motion.setUserPreference((event.target as HTMLInputElement).checked);
  }

  async toggleEmail(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const wanted = input.checked;
    this.preferenceBusy.set(true);
    try {
      await this.service.setEmailEnabled(wanted);
    } catch (error) {
      input.checked = !wanted;
      this.toasts.show({ text: this.copy(error), kind: 'error' });
    } finally {
      this.preferenceBusy.set(false);
    }
  }

  async activateDeveloper(): Promise<void> {
    if (this.activating()) return;
    this.activating.set(true);
    try {
      await this.service.activateDeveloper();
      this.developerDialog.set(false);
      this.toasts.show({ text: 'Developer profile activated', kind: 'success' });
    } catch (error) {
      this.toasts.show({ text: this.copy(error), kind: 'error' });
    } finally {
      this.activating.set(false);
    }
  }

  async logout(): Promise<void> {
    if (this.loggingOut()) return;
    this.loggingOut.set(true);
    try {
      await this.service.logout();
      this.bypassGuard = true;
      await this.router.navigateByUrl('/');
    } catch {
      this.toasts.show({ text: 'Could not log out. Try again.', kind: 'error' });
    } finally {
      this.loggingOut.set(false);
    }
  }

  private copy(error: unknown): string {
    return errorCopy(isAppError(error) ? error : UNKNOWN_ERROR);
  }

  private focusFirstError(errors: Partial<Record<ProfileField, string>>): void {
    const first = (['username', 'location', 'bio'] as const).find((field) => errors[field]);
    if (first) this.focusLater('#settings-' + first);
  }

  private focusLater(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }
}
