import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { MeResponse } from '../api/api-models';
import { UserApi } from '../api/user-api';
import { AuthStore } from '../auth/auth.store';

// Cognito claims carry no marketplace username, so the header reads it from the user profile.
@Injectable({ providedIn: 'root' })
export class CurrentProfile {
  private readonly auth = inject(AuthStore);
  private readonly users = inject(UserApi);
  private readonly current = signal<MeResponse | null>(null);
  private loadedFor: string | null = null;

  readonly profile = this.current.asReadonly();

  constructor() {
    effect(() => {
      const sub = this.auth.isAuthenticated() ? (this.auth.identity()?.sub ?? null) : null;
      untracked(() => {
        if (sub === null) {
          this.loadedFor = null;
          this.current.set(null);
        } else if (sub !== this.loadedFor) {
          this.loadedFor = sub;
          this.current.set(null);
          this.reload();
        }
      });
    });
  }

  reload(): void {
    const sub = this.loadedFor;
    if (sub === null) return;
    this.users.getMe().subscribe({
      next: (profile) => {
        if (this.loadedFor === sub) this.current.set(profile);
      },
      error: () => undefined,
    });
  }
}
