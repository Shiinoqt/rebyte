import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { AppConfigService } from '../config/app-config';
import { AuthAdapter, AuthIdentity } from './auth.adapter';

export type AuthStatus = 'resolving' | 'guest' | 'authenticated';
export type AuthCleanupHook = () => void | Promise<void>;

@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly adapter = inject(AuthAdapter);
  private readonly config = inject(AppConfigService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly currentStatus = signal<AuthStatus>('resolving');
  private readonly currentIdentity = signal<AuthIdentity | null>(null);
  private readonly hooks = new Set<AuthCleanupHook>();
  private readonly waiters = new Set<() => void>();
  private initialization?: Promise<void>;
  private generation = 0;
  private cleanupArmed = false;

  readonly status = this.currentStatus.asReadonly();
  readonly identity = this.currentIdentity.asReadonly();
  readonly isAuthenticated = computed(() => this.status() === 'authenticated');
  readonly isAdmin = computed(
    () =>
      this.isAuthenticated() &&
      (this.identity()?.groups.some((group) => group.toUpperCase() === 'ADMIN') ?? false),
  );

  init(): Promise<void> {
    if (this.initialization) {
      return this.initialization;
    }
    this.adapter.configure(this.config.config.cognito);
    const stop = this.adapter.onAuthEvent((event) => {
      if (event === 'signedOut' || event === 'tokenRefresh_failure') {
        this.clearSession();
      } else if (event === 'signedIn' || this.status() !== 'resolving') {
        void this.resolveSession();
      }
    });
    this.destroyRef.onDestroy(() => {
      stop();
      this.clearSession();
    });
    this.initialization = this.resolveSession();
    return this.initialization;
  }

  whenResolved(): Promise<void> {
    if (this.status() !== 'resolving') {
      return Promise.resolve();
    }
    return new Promise((resolve) => this.waiters.add(resolve));
  }

  registerCleanup(hook: AuthCleanupHook): () => void {
    this.hooks.add(hook);
    return () => {
      this.hooks.delete(hook);
    };
  }

  clearSession(): void {
    ++this.generation;
    this.currentIdentity.set(null);
    this.currentStatus.set('guest');
    if (this.cleanupArmed) {
      this.cleanupArmed = false;
      for (const hook of this.hooks) {
        try {
          void Promise.resolve(hook()).catch(() => undefined);
        } catch {
          // One failing store must not prevent the other user-scoped stores from clearing.
        }
      }
    }
    this.releaseWaiters();
  }

  private async resolveSession(): Promise<void> {
    const generation = ++this.generation;
    // A token refresh re-reads the session without demoting a signed-in user, so guards and the shell do not flicker.
    if (this.currentStatus() !== 'authenticated') {
      this.currentStatus.set('resolving');
    }
    try {
      const identity = await this.adapter.fetchSession();
      // A sign-out or newer sign-in supersedes a session lookup already in flight.
      if (generation !== this.generation) {
        return;
      }
      if (!identity) {
        this.clearSession();
        return;
      }
      if (this.currentIdentity()?.sub && this.currentIdentity()?.sub !== identity.sub) {
        this.clearSession();
        // Cleanup hooks may start a newer auth resolution or sign out again.
        if (this.generation !== generation + 1) return;
      }
      this.currentIdentity.set({
        sub: identity.sub,
        ...(identity.email !== undefined ? { email: identity.email } : {}),
        groups: [...identity.groups],
      });
      this.cleanupArmed = true;
      this.currentStatus.set('authenticated');
      this.releaseWaiters();
    } catch {
      if (generation === this.generation) {
        this.clearSession();
      }
    }
  }

  private releaseWaiters(): void {
    for (const resolve of this.waiters) {
      resolve();
    }
    this.waiters.clear();
  }
}
