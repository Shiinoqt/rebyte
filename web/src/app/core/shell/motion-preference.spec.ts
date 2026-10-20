import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MotionPreference } from './motion-preference';

describe('motion preference', () => {
  let matchMediaDescriptor: PropertyDescriptor | undefined;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['reducedMotion'];
    matchMediaDescriptor = Object.getOwnPropertyDescriptor(window, 'matchMedia');
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['reducedMotion'];
    vi.restoreAllMocks();
    if (matchMediaDescriptor) {
      Object.defineProperty(window, 'matchMedia', matchMediaDescriptor);
    } else {
      delete (window as Partial<Window>).matchMedia;
    }
  });

  it('restores and persists a user preference', () => {
    TestBed.inject(MotionPreference).setUserPreference(true);
    expect(document.documentElement.hasAttribute('data-reduced-motion')).toBe(true);
    expect(localStorage.getItem('rebyte.reducedMotion')).toBe('true');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const restored = TestBed.inject(MotionPreference);
    expect(restored.userReducedMotion()).toBe(true);
    expect(restored.enabled()).toBe(true);
  });

  it('keeps device motion reduction active and follows preference changes', () => {
    let listener: ((event: MediaQueryListEvent) => void) | undefined;
    const mediaQuery = {
      matches: true,
      addEventListener: vi.fn((_type: string, callback: (event: MediaQueryListEvent) => void) => {
        listener = callback;
      }),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList;
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => mediaQuery });

    const preference = TestBed.inject(MotionPreference);
    expect(preference.enabled()).toBe(true);
    preference.setUserPreference(false);
    expect(preference.enabled()).toBe(true);
    expect(document.documentElement.hasAttribute('data-reduced-motion')).toBe(true);

    listener?.({ matches: false } as MediaQueryListEvent);
    expect(preference.enabled()).toBe(false);
    expect(document.documentElement.hasAttribute('data-reduced-motion')).toBe(false);
    TestBed.resetTestingModule();
    expect(mediaQuery.removeEventListener).toHaveBeenCalledOnce();
  });

  it('keeps the preference usable when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Storage is unavailable', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage is unavailable', 'SecurityError');
    });
    const preference = TestBed.inject(MotionPreference);
    preference.setUserPreference(true);
    expect(preference.enabled()).toBe(true);
    expect(document.documentElement.hasAttribute('data-reduced-motion')).toBe(true);
  });
});
