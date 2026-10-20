import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'rebyte.reducedMotion';

@Injectable({ providedIn: 'root' })
export class MotionPreference {
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly mediaQuery = this.document.defaultView?.matchMedia?.(
    '(prefers-reduced-motion: reduce)',
  );
  readonly userReducedMotion = signal(this.readSavedPreference());
  readonly systemReducedMotion = signal(this.mediaQuery?.matches ?? false);
  readonly enabled = computed(() => this.systemReducedMotion() || this.userReducedMotion());

  constructor() {
    this.mediaQuery?.addEventListener('change', this.onSystemPreferenceChange);
    this.destroyRef.onDestroy(() =>
      this.mediaQuery?.removeEventListener('change', this.onSystemPreferenceChange),
    );
    this.applyPreference();
  }

  setUserPreference(reduced: boolean): void {
    this.userReducedMotion.set(reduced);
    this.applyPreference();
    try {
      this.document.defaultView?.localStorage.setItem(STORAGE_KEY, String(reduced));
    } catch {
      // Keep the in-memory preference active when browser storage is unavailable.
    }
  }

  private readonly onSystemPreferenceChange = (event: MediaQueryListEvent): void => {
    this.systemReducedMotion.set(event.matches);
    this.applyPreference();
  };

  private readSavedPreference(): boolean {
    try {
      const saved = this.document.defaultView?.localStorage.getItem(STORAGE_KEY);
      if (saved === 'true' || saved === 'false') return saved === 'true';
    } catch {
      return this.document.documentElement.hasAttribute('data-reduced-motion');
    }
    return false;
  }

  private applyPreference(): void {
    this.document.documentElement.toggleAttribute('data-reduced-motion', this.enabled());
  }
}
