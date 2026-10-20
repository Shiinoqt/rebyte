import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, firstValueFrom, forkJoin, map, of, Subscription } from 'rxjs';
import { MeResponse, UpdateProfileRequest } from '../../core/api/api-models';
import { MessagingApi } from '../../core/api/messaging-api';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { PhotoPipeline } from '../../core/media/photo-pipeline';
import { S3Uploader } from '../../core/media/s3-uploader';
import { CurrentProfile } from '../../core/shell/current-profile';
import { AccountSummary } from '../profile/account-summary';

export type SettingsStatus = 'loading' | 'ready' | 'error';

// user-service caps avatars at rebyte.media.upload.max-size-bytes; the server stays authoritative.
export const AVATAR_LIMITS = { maxEdge: 2000, maxBytes: 524288 } as const;

// Provided by SettingsPage: each visit loads fresh account state and drops it on leave.
@Injectable()
export class SettingsService {
  private readonly users = inject(UserApi);
  private readonly messaging = inject(MessagingApi);
  private readonly adapter = inject(AuthAdapter);
  private readonly auth = inject(AuthStore);
  private readonly pipeline = inject(PhotoPipeline);
  private readonly uploader = inject(S3Uploader);
  private readonly current = inject(CurrentProfile);
  private readonly summary = inject(AccountSummary);
  private request: Subscription | null = null;

  readonly status = signal<SettingsStatus>('loading');
  readonly account = signal<MeResponse | null>(null);
  readonly avatar = signal<string | null>(null);
  private readonly activated = signal(false);
  // The shared account summary already reads the public profile, so its flag is reused;
  // null while that read is in flight.
  readonly isDeveloper = computed(() =>
    this.activated() ? true : (this.summary.aggregates()?.isDeveloper ?? null),
  );
  // null means the preference could not be read, so the switch stays disabled.
  readonly emailEnabled = signal<boolean | null>(null);
  readonly avatarBusy = signal(false);
  readonly avatarProgress = signal<number | null>(null);
  readonly email = computed(() => this.auth.identity()?.email ?? null);

  load(): void {
    if (!this.auth.identity()) return;
    this.request?.unsubscribe();
    this.status.set('loading');
    this.request = forkJoin({
      account: this.users.getMe(),
      emailEnabled: this.messaging.emailPreferences().pipe(
        map((preference) => preference.emailEnabled),
        catchError(() => of(null)),
      ),
    }).subscribe({
      next: ({ account, emailEnabled }) => {
        this.account.set(account);
        this.avatar.set(account.avatar);
        this.emailEnabled.set(emailEnabled);
        this.status.set('ready');
      },
      error: () => this.status.set('error'),
    });
  }

  async saveProfile(request: UpdateProfileRequest): Promise<void> {
    const account = await firstValueFrom(this.users.updateProfile(request));
    this.account.set(account);
    this.refreshOwnStores();
  }

  // The bytes go straight to S3 under a short-lived policy, so the policy is fetched just before.
  async changeAvatar(file: File): Promise<void> {
    const processed = await this.pipeline.process(file, AVATAR_LIMITS);
    this.avatarBusy.set(true);
    this.avatarProgress.set(0);
    try {
      const policy = await firstValueFrom(this.users.avatarUploadPolicy());
      await this.uploader.upload(policy, processed.blob, 'image/jpeg', (percent) =>
        this.avatarProgress.set(percent),
      );
      const account = await firstValueFrom(this.users.confirmAvatar({ key: policy.key }));
      this.account.set(account);
      this.avatar.set(account.avatar);
      this.refreshOwnStores();
    } finally {
      processed.cleanup();
      this.avatarBusy.set(false);
      this.avatarProgress.set(null);
    }
  }

  // DELETE is idempotent, so a second removal (another tab, a retry) still succeeds.
  async removeAvatar(): Promise<void> {
    const previous = this.avatar();
    this.avatar.set(null);
    this.avatarBusy.set(true);
    try {
      await firstValueFrom(this.users.deleteAvatar(), { defaultValue: undefined });
      this.account.update((account) => (account ? { ...account, avatar: null } : account));
      this.refreshOwnStores();
    } catch (error) {
      this.avatar.set(previous);
      throw error;
    } finally {
      this.avatarBusy.set(false);
    }
  }

  updatePassword(oldPassword: string, newPassword: string): Promise<void> {
    return this.adapter.updatePassword({ oldPassword, newPassword });
  }

  async setEmailEnabled(emailEnabled: boolean): Promise<void> {
    const previous = this.emailEnabled();
    this.emailEnabled.set(emailEnabled);
    try {
      const saved = await firstValueFrom(this.messaging.updateEmailPreferences({ emailEnabled }));
      this.emailEnabled.set(saved.emailEnabled);
    } catch (error) {
      this.emailEnabled.set(previous);
      throw error;
    }
  }

  async activateDeveloper(): Promise<void> {
    await firstValueFrom(this.users.activateDeveloperProfile());
    this.activated.set(true);
    this.summary.refresh();
  }

  // clearSession runs the registered cleanup hooks, which is what stops the realtime connection.
  async logout(): Promise<void> {
    await this.adapter.signOut();
    this.auth.clearSession();
  }

  private refreshOwnStores(): void {
    this.current.reload();
    this.summary.refresh();
  }
}
