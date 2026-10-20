import { Injectable } from '@angular/core';

export interface PendingUsernameData {
  username: string;
  email: string;
}
export type PendingUsernameResult = 'none' | 'applied' | 'conflict';
export type ApplyPendingUsername = (input: { username: string }) => Promise<unknown>;

const STORAGE_KEY = 'rebyte.pendingUsername';

function normalizeUsername(raw: string): string {
  if (typeof raw !== 'string') {
    throw new Error('Invalid username');
  }
  const username = raw.toLowerCase();
  if (!/^[a-z0-9_-]{3,30}$/.test(username) || /^user_[0-9a-f]{8}$/.test(username)) {
    throw new Error('Invalid or reserved username');
  }
  return username;
}

function normalizeEmail(raw: string): string {
  if (typeof raw !== 'string' || !raw.trim()) {
    throw new Error('Signup email is required');
  }
  return raw.trim().toLowerCase();
}

function isUsernameConflict(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const problem = error as { status?: number; code?: string; error?: { title?: string } };
  return (
    problem.status === 409 &&
    (problem.code === 'username-conflict' || problem.error?.title === 'username-conflict')
  );
}

@Injectable({ providedIn: 'root' })
export class PendingUsername {
  private inFlight?: { snapshot: string; result: Promise<PendingUsernameResult> };

  save(input: PendingUsernameData): void {
    const data: PendingUsernameData = {
      username: normalizeUsername(input.username),
      email: normalizeEmail(input.email),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  }

  read(): PendingUsernameData | null {
    const raw = this.readRaw();
    if (raw === null) {
      return null;
    }
    try {
      const data: unknown = JSON.parse(raw);
      if (
        typeof data !== 'object' ||
        data === null ||
        Array.isArray(data) ||
        Object.keys(data).some((key) => key !== 'username' && key !== 'email')
      ) {
        throw new Error('Invalid pending username data');
      }
      const pending = data as PendingUsernameData;
      return {
        username: normalizeUsername(pending.username),
        email: normalizeEmail(pending.email),
      };
    } catch {
      this.clear();
      return null;
    }
  }

  clear(): void {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Browser privacy settings can disable storage entirely.
    }
  }

  applyAfterLogin(
    email: string | undefined,
    update: ApplyPendingUsername,
  ): Promise<PendingUsernameResult> {
    const data = this.read();
    if (!data || !email || data.email !== normalizeEmail(email)) {
      return Promise.resolve('none');
    }
    const snapshot = this.readRaw();
    if (snapshot === null) {
      return Promise.resolve('none');
    }
    if (this.inFlight?.snapshot === snapshot) {
      return this.inFlight.result;
    }
    const result = this.apply(data, snapshot, update).finally(() => {
      if (this.inFlight?.result === result) {
        this.inFlight = undefined;
      }
    });
    this.inFlight = { snapshot, result };
    return result;
  }

  private async apply(
    data: PendingUsernameData,
    snapshot: string,
    update: ApplyPendingUsername,
  ): Promise<PendingUsernameResult> {
    try {
      await update({ username: data.username });
      // A confirm flow in another tab may have saved a different signup during the request.
      if (this.readRaw() === snapshot) {
        this.clear();
      }
      return 'applied';
    } catch (error) {
      if (isUsernameConflict(error)) {
        return 'conflict';
      }
      throw error;
    }
  }

  private readRaw(): string | null {
    try {
      return localStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  }
}
