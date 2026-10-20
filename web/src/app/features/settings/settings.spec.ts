import { BreakpointObserver } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import { MeResponse, PublicProfileResponse, UploadPolicy } from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { MessagingApi } from '../../core/api/messaging-api';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter, AuthEvent, AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { PhotoPipeline, PhotoProcessingError } from '../../core/media/photo-pipeline';
import { S3_XHR_FACTORY } from '../../core/media/s3-uploader';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ToastService } from '../../shared/ui/feedback';
import { SETTINGS_ROUTES } from './settings.routes';
import { SettingsService } from './settings.service';

const SUB = 'member-sub';

@Component({ template: '<h1>Elsewhere</h1>' })
class ElsewherePage {}

class FakeAuthAdapter {
  constructor(public identity: AuthIdentity | null) {}
  configure = vi.fn();
  fetchSession = vi.fn(() => Promise.resolve(this.identity));
  signOut = vi.fn(() => Promise.resolve());
  updatePassword = vi.fn((_input: { oldPassword: string; newPassword: string }) =>
    Promise.resolve(),
  );
  onAuthEvent(_listener: (event: AuthEvent) => void) {
    return () => undefined;
  }
}

class FakeXhr {
  upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  status = 0;
  url = '';
  form: FormData | null = null;
  open(_method: string, url: string) {
    this.url = url;
  }
  send(form: FormData) {
    this.form = form;
  }
  abort() {}
  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent);
  }
  respond(status: number) {
    this.status = status;
    this.onload?.();
  }
}

function me(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    id: SUB,
    username: 'alice',
    registration: '2026-01-15T10:00:00Z',
    location: 'Milan',
    bio: 'I repair laptops.',
    avatar: 'https://cdn.test/avatars/alice.jpg',
    ...overrides,
  };
}

function publicProfile(overrides: Partial<PublicProfileResponse> = {}): PublicProfileResponse {
  return {
    sub: SUB,
    username: 'alice',
    avatar: null,
    registration: '2026-01-15T10:00:00Z',
    location: 'Milan',
    bio: 'I repair laptops.',
    isDeveloper: false,
    ratingAvg: null,
    ratingCount: 0,
    completedSales: 0,
    activeListings: [],
    followerCount: 0,
    followingCount: 0,
    followedByMe: false,
    ...overrides,
  };
}

const POLICY: UploadPolicy = {
  url: 'https://bucket.s3.test/',
  key: 'avatars/member-sub/new.jpg',
  algorithm: 'AWS4-HMAC-SHA256',
  credential: 'cred',
  date: '20261008T000000Z',
  policy: 'cG9saWN5',
  signature: 'sig',
  securityToken: null,
};

function appError(status: number, code: string): AppError {
  return { status, code, type: 'urn:rebyte:problem:' + code, fieldErrors: {}, extras: {} };
}

function namedError(name: string): Error {
  const error = new Error('Raw Cognito message that must never reach the page');
  error.name = name;
  return error;
}

describe('account settings', () => {
  let narrow: BehaviorSubject<{ matches: boolean; breakpoints: Record<string, boolean> }>;
  let xhrs: FakeXhr[];

  beforeEach(() => {
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['reducedMotion'];
    narrow = new BehaviorSubject<{ matches: boolean; breakpoints: Record<string, boolean> }>({
      matches: false,
      breakpoints: {},
    });
    xhrs = [];
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('rebyte.reducedMotion');
    delete document.documentElement.dataset['reducedMotion'];
  });

  async function open(
    options: {
      url?: string;
      account?: MeResponse;
      profile?: PublicProfileResponse;
      email?: string | null;
      emailEnabled?: boolean | 'error';
      users?: Partial<Record<keyof UserApi, ReturnType<typeof vi.fn>>>;
    } = {},
  ) {
    const identity: AuthIdentity = {
      sub: SUB,
      groups: [],
      ...(options.email === null ? {} : { email: options.email ?? 'alice@example.test' }),
    };
    const adapter = new FakeAuthAdapter(identity);
    let account = options.account ?? me();
    const users = {
      getMe: vi.fn(() => of(account)),
      getPublicProfile: vi.fn(() => of(options.profile ?? publicProfile())),
      updateProfile: vi.fn(
        (request: { username: string; location: string | null; bio: string | null }) => {
          account = { ...account, ...request };
          return of(account);
        },
      ),
      avatarUploadPolicy: vi.fn(() => of(POLICY)),
      confirmAvatar: vi.fn(() => of(me({ avatar: 'https://cdn.test/avatars/new.jpg' }))),
      deleteAvatar: vi.fn(() => of(undefined)),
      activateDeveloperProfile: vi.fn(() => of({ sub: SUB, activatedAt: '2026-10-08T10:00:00Z' })),
      ...options.users,
    };
    const messaging = {
      emailPreferences: vi.fn(() =>
        options.emailEnabled === 'error'
          ? throwError(() => appError(503, 'unavailable'))
          : of({ emailEnabled: options.emailEnabled ?? true }),
      ),
      updateEmailPreferences: vi.fn((request: { emailEnabled: boolean }) => of(request)),
    };
    const catalog = {
      favourites: vi.fn(() =>
        of({ favourites: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }),
      ),
    };
    const blob = new Blob(['jpeg'], { type: 'image/jpeg' });
    const pipeline = {
      process: vi.fn((_file: File, _options: { maxEdge: number; maxBytes: number }) =>
        Promise.resolve({
          blob,
          width: 400,
          height: 400,
          url: 'blob:preview',
          cleanup: vi.fn(),
        }),
      ),
    };
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideRouter(
          [
            { path: 'settings', loadChildren: () => SETTINGS_ROUTES },
            { path: 'me', component: ElsewherePage },
            { path: '', component: ElsewherePage },
          ],
          withComponentInputBinding(),
        ),
        { provide: AuthAdapter, useValue: adapter },
        { provide: UserApi, useValue: users },
        { provide: MessagingApi, useValue: messaging },
        { provide: CatalogApi, useValue: catalog },
        { provide: PhotoPipeline, useValue: pipeline },
        {
          provide: S3_XHR_FACTORY,
          useValue: () => {
            const xhr = new FakeXhr();
            xhrs.push(xhr);
            return xhr as unknown as XMLHttpRequest;
          },
        },
        { provide: BreakpointObserver, useValue: { observe: () => narrow } },
      ],
    });
    const store = TestBed.inject(AuthStore);
    await store.init();
    const realtime = TestBed.inject(RealtimeService);
    vi.spyOn(realtime, 'activate').mockImplementation(() => undefined);
    const deactivate = vi.spyOn(realtime, 'deactivate').mockResolvedValue(undefined);
    realtime.connectToSession();
    const toasts = vi.spyOn(TestBed.inject(ToastService), 'show');
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(options.url ?? '/settings');
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement as HTMLElement;
    return {
      harness,
      root,
      adapter,
      users,
      messaging,
      pipeline,
      store,
      deactivate,
      toasts,
      blob,
      router: TestBed.inject(Router),
    };
  }

  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const field = (root: HTMLElement, id: string) =>
    root.querySelector<HTMLInputElement | HTMLTextAreaElement>('#' + id);
  const button = (root: HTMLElement, label: string) =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (candidate) => text(candidate) === label,
    );
  const errorFor = (root: HTMLElement, id: string) => text(root.querySelector(`#${id}-error`));

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  // The router counts a navigation held by the leave prompt as pending work, so whenStable
  // would wait for the very answer the test is about to give.
  async function pendingPrompt(harness: RouterTestingHarness) {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function type(harness: RouterTestingHarness, root: HTMLElement, id: string, value: string) {
    const input = field(root, id);
    if (!input) throw new Error('missing field #' + id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
  }

  async function pickAvatar(harness: RouterTestingHarness, root: HTMLElement, name = 'me.png') {
    const input = root.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File(['png'], name, { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle(harness);
    return file;
  }

  describe('profile', () => {
    it('focuses the username field from the generated username banner', async () => {
      const { harness, root, router } = await open({ account: me({ username: 'user_1a2b3c4d' }) });
      expect(text(root.querySelector('app-banner'))).toContain('Your username was generated for you.');
      button(root, 'Choose a username')!.click();
      await settle(harness);
      expect(document.activeElement).toBe(field(root, 'settings-username'));
      expect(router.url).toBe('/settings');
    });

    it('focuses the username field when opened from the public profile', async () => {
      const { root } = await open({ url: '/settings#settings-username' });
      expect(document.activeElement).toBe(field(root, 'settings-username'));
    });

    it.each(['alice', 'user_friendly', 'user_1a2b3c4', 'user_1a2b3c4d5', 'user_1A2B3C4D'])(
      'has no username banner for the chosen handle %s',
      async (username) => {
        const { root } = await open({ account: me({ username }) });
        expect(root.querySelector('app-banner')).toBeNull();
      },
    );
    it('opens the settings card inside the account layout with the profile filled in', async () => {
      const { root } = await open();
      expect(text(root.querySelector('h1'))).toBe('Account settings');
      expect(root.querySelector('app-account-nav.sidebar')).not.toBeNull();
      expect(text(root.querySelector('a.row[aria-current="page"] .row-label'))).toBe(
        'Settings and profile',
      );
      expect(field(root, 'settings-username')?.value).toBe('alice');
      expect(field(root, 'settings-location')?.value).toBe('Milan');
      expect(field(root, 'settings-bio')?.value).toBe('I repair laptops.');
      expect(root.querySelector('.avatar-preview img')?.getAttribute('src')).toBe(
        'https://cdn.test/avatars/alice.jpg',
      );
    });

    it('uses an Account back link instead of the sidebar on narrow screens', async () => {
      narrow.next({ matches: true, breakpoints: {} });
      const { root } = await open();
      expect(root.querySelector('app-account-nav')).toBeNull();
      expect(root.querySelector('a.back')?.getAttribute('href')).toBe('/me');
    });

    it('saves username, location and bio together so unchanged fields are kept', async () => {
      const { harness, root, users, toasts } = await open();
      type(harness, root, 'settings-location', 'Turin');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(users.updateProfile).toHaveBeenCalledExactlyOnceWith({
        username: 'alice',
        location: 'Turin',
        bio: 'I repair laptops.',
      });
      expect(toasts).toHaveBeenCalledWith({ text: 'Changes saved', kind: 'success' });
    });

    it('normalises the username and clears emptied optional fields to null', async () => {
      const { harness, root, users } = await open();
      type(harness, root, 'settings-username', '  Alice_2  ');
      type(harness, root, 'settings-location', '   ');
      type(harness, root, 'settings-bio', '');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(users.updateProfile).toHaveBeenCalledExactlyOnceWith({
        username: 'alice_2',
        location: null,
        bio: null,
      });
    });

    it('keeps blank optional fields null when the account has none', async () => {
      const { harness, root, users } = await open({
        account: me({ location: null, bio: null }),
      });
      type(harness, root, 'settings-username', 'alice2');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(users.updateProfile).toHaveBeenCalledExactlyOnceWith({
        username: 'alice2',
        location: null,
        bio: null,
      });
    });

    it('blocks an invalid username, an over-long location and bio, and focuses the first', async () => {
      const { harness, root, users } = await open();
      type(harness, root, 'settings-username', 'ab');
      type(harness, root, 'settings-location', 'x'.repeat(81));
      type(harness, root, 'settings-bio', 'y'.repeat(501));
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(users.updateProfile).not.toHaveBeenCalled();
      expect(errorFor(root, 'settings-username')).toBe(
        'Use 3 to 30 characters: lowercase letters, digits, _ or -.',
      );
      expect(errorFor(root, 'settings-location')).toBe('Use at most 80 characters.');
      expect(errorFor(root, 'settings-bio')).toBe('Use at most 500 characters.');
      expect(root.querySelector('#settings-username-hint')).toBeNull();
      expect(document.activeElement).toBe(field(root, 'settings-username'));
    });

    it('keeps the saved initial on the photo fallback while the username is edited', async () => {
      const { harness, root } = await open({ account: me({ avatar: null }) });
      type(harness, root, 'settings-username', 'zed');
      expect(text(root.querySelector('.avatar-preview'))).toBe('A');
    });

    it('counts bio characters against the 500 limit', async () => {
      const { harness, root } = await open();
      type(harness, root, 'settings-bio', 'Hello');
      expect(text(root.querySelector('#settings-bio-count'))).toBe('5/500');
    });

    it.each([
      [409, 'username-conflict', 'That username is taken.'],
      [422, 'reserved-handle', "That username isn't available."],
      [400, 'invalid-username', 'Use 3 to 30 characters: lowercase letters, digits, _ or -.'],
    ])('binds the %s %s server error to the username field', async (status, code, copy) => {
      const { harness, root } = await open({
        users: { updateProfile: vi.fn(() => throwError(() => appError(status, code))) },
      });
      type(harness, root, 'settings-username', 'bob');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(errorFor(root, 'settings-username')).toBe(copy);
      expect(document.activeElement).toBe(field(root, 'settings-username'));
    });

    it('shows a retryable error state when the account cannot load', async () => {
      let calls = 0;
      const getMe = vi.fn(() =>
        ++calls === 1 ? throwError(() => appError(503, 'unavailable')) : of(me()),
      );
      const { harness, root } = await open({ users: { getMe } });
      const error = root.querySelector('.settings-content app-error-state');
      expect(error).not.toBeNull();
      error!.querySelector('button')!.click();
      await settle(harness);
      expect(field(root, 'settings-username')?.value).toBe('alice');
    });
  });

  describe('avatar', () => {
    it('processes, uploads straight to S3 with an image/jpeg Content-Type, then confirms', async () => {
      const { harness, root, pipeline, users, blob, toasts } = await open();
      await pickAvatar(harness, root);
      expect(pipeline.process).toHaveBeenCalledWith(expect.any(File), {
        maxEdge: 2000,
        maxBytes: 524288,
      });
      expect(users.avatarUploadPolicy).toHaveBeenCalledTimes(1);
      const xhr = xhrs[0];
      expect(xhr.url).toBe(POLICY.url);
      const keys = Array.from(xhr.form!.keys());
      expect(xhr.form!.get('Content-Type')).toBe('image/jpeg');
      expect(keys.indexOf('Content-Type')).toBeLessThan(keys.indexOf('file'));
      expect(keys[keys.length - 1]).toBe('file');
      expect(xhr.form!.get('file')).toBeInstanceOf(Blob);
      xhr.progress(50, 100);
      harness.detectChanges();
      expect(
        root.querySelector('.avatar-preview [role="progressbar"]')?.getAttribute('aria-valuenow'),
      ).toBe('50');
      xhr.respond(204);
      await settle(harness);
      expect(users.confirmAvatar).toHaveBeenCalledExactlyOnceWith({ key: POLICY.key });
      for (const call of [...users.updateProfile.mock.calls, ...users.confirmAvatar.mock.calls]) {
        expect(JSON.stringify(call)).not.toContain('blob');
        expect(call).not.toContain(blob);
      }
      expect(root.querySelector('.avatar-preview img')?.getAttribute('src')).toBe(
        'https://cdn.test/avatars/new.jpg',
      );
      expect(toasts).toHaveBeenCalledWith({ text: 'Profile photo updated', kind: 'success' });
    });

    it('rejects an unusable photo with a specific message and never asks for a policy', async () => {
      const { harness, root, pipeline, users } = await open();
      pipeline.process.mockRejectedValueOnce(new PhotoProcessingError('photo-too-large'));
      await pickAvatar(harness, root, 'huge.png');
      expect(users.avatarUploadPolicy).not.toHaveBeenCalled();
      expect(text(root.querySelector('#settings-avatar-error'))).toBe(
        'huge.png is larger than 512 KB.',
      );
    });

    it('keeps the old photo and reports a failed S3 upload', async () => {
      const { harness, root, users, toasts } = await open();
      await pickAvatar(harness, root);
      xhrs[0].respond(403);
      await settle(harness);
      expect(users.confirmAvatar).not.toHaveBeenCalled();
      expect(root.querySelector('.avatar-preview img')?.getAttribute('src')).toBe(
        'https://cdn.test/avatars/alice.jpg',
      );
      expect(toasts).toHaveBeenCalledWith({
        text: "We couldn't upload the photo. Try again.",
        kind: 'error',
      });
    });

    it('removes the photo without a dialog and shows the initial-letter fallback', async () => {
      const { harness, root, users, toasts } = await open();
      const remove = button(root, 'Remove')!;
      remove.click();
      await settle(harness);
      expect(users.deleteAvatar).toHaveBeenCalledTimes(1);
      expect(root.querySelector('dialog')).toBeNull();
      expect(root.querySelector('.avatar-preview img')).toBeNull();
      expect(text(root.querySelector('.avatar-preview'))).toBe('A');
      expect(button(root, 'Remove')).toBeUndefined();
      expect(toasts).toHaveBeenCalledWith({ text: 'Profile photo removed', kind: 'success' });
    });

    it('restores the photo when removal fails', async () => {
      const { harness, root, toasts } = await open({
        users: { deleteAvatar: vi.fn(() => throwError(() => appError(503, 'unavailable'))) },
      });
      button(root, 'Remove')!.click();
      await settle(harness);
      expect(root.querySelector('.avatar-preview img')?.getAttribute('src')).toBe(
        'https://cdn.test/avatars/alice.jpg',
      );
      expect(toasts).toHaveBeenCalledWith(expect.objectContaining({ kind: 'error' }));
    });

    it('treats a repeated removal as success because the endpoint is idempotent', async () => {
      const { harness, users } = await open();
      const service = harness.routeDebugElement!.injector.get(SettingsService);
      await service.removeAvatar();
      await service.removeAvatar();
      expect(users.deleteAvatar).toHaveBeenCalledTimes(2);
      expect(service.avatar()).toBeNull();
    });
  });

  describe('login', () => {
    it('shows the email read-only and explains why Change email is disabled', async () => {
      const { root } = await open();
      expect(text(root.querySelector('#settings-email'))).toBe('alice@example.test');
      const change = button(root, 'Change email')!;
      expect(change.disabled).toBe(true);
      const reasonId = change.getAttribute('aria-describedby')!;
      expect(text(root.querySelector('#' + reasonId))).toBe(
        "Email changes aren't available yet: they need a verification step that isn't set up.",
      );
    });

    it('says Unavailable when the session carries no email', async () => {
      const { root } = await open({ email: null });
      expect(text(root.querySelector('#settings-email'))).toBe('Unavailable');
    });

    it('changes the password through Cognito only and clears the fields', async () => {
      const { harness, root, adapter, toasts } = await open();
      button(root, 'Change password')!.click();
      harness.detectChanges();
      type(harness, root, 'settings-current-password', 'old-pass-1');
      type(harness, root, 'settings-new-password', 'new-pass-2!');
      button(root, 'Update password')!.click();
      await settle(harness);
      expect(adapter.updatePassword).toHaveBeenCalledExactlyOnceWith({
        oldPassword: 'old-pass-1',
        newPassword: 'new-pass-2!',
      });
      expect(field(root, 'settings-current-password')).toBeNull();
      expect(toasts).toHaveBeenCalledWith({ text: 'Password updated', kind: 'success' });
    });

    it('requires both passwords before calling Cognito', async () => {
      const { harness, root, adapter } = await open();
      button(root, 'Change password')!.click();
      harness.detectChanges();
      button(root, 'Update password')!.click();
      await settle(harness);
      expect(adapter.updatePassword).not.toHaveBeenCalled();
      expect(errorFor(root, 'settings-current-password')).toBe('Enter your password.');
      expect(errorFor(root, 'settings-new-password')).toBe('Enter a new password.');
    });

    it.each([
      ['NotAuthorizedException', 'Your current password is not right.'],
      [
        'InvalidPasswordException',
        "That password doesn't meet the password rules. Use a longer one with a mix of letters, numbers and symbols.",
      ],
      ['LimitExceededException', 'Too many attempts. Wait a few minutes and try again.'],
    ])('maps %s to copy without the raw message', async (name, copy) => {
      const { harness, root, adapter } = await open();
      adapter.updatePassword.mockRejectedValueOnce(namedError(name));
      button(root, 'Change password')!.click();
      harness.detectChanges();
      type(harness, root, 'settings-current-password', 'old-pass-1');
      type(harness, root, 'settings-new-password', 'new-pass-2!');
      button(root, 'Update password')!.click();
      await settle(harness);
      expect(text(root.querySelector('#settings-password-error'))).toBe(copy);
      expect(text(root)).not.toContain('Raw Cognito message');
    });
  });

  describe('accessibility', () => {
    it('shows a persistent reduced-motion switch in Settings', async () => {
      const { harness, root } = await open();
      const toggle = root.querySelector<HTMLInputElement>('#settings-reduced-motion')!;
      expect(toggle.getAttribute('role')).toBe('switch');
      expect(toggle.checked).toBe(false);
      expect(text(root.querySelector('#settings-reduced-motion-help'))).toBe(
        'Reduce animation and scrolling effects across Rebyte.',
      );

      toggle.click();
      harness.detectChanges();
      expect(toggle.checked).toBe(true);
      expect(localStorage.getItem('rebyte.reducedMotion')).toBe('true');
      expect(document.documentElement.hasAttribute('data-reduced-motion')).toBe(true);
    });

    it('locks the switch on when the device requests reduced motion', async () => {
      const descriptor = Object.getOwnPropertyDescriptor(window, 'matchMedia');
      const mediaQuery = {
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      } as unknown as MediaQueryList;
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        value: () => mediaQuery,
      });
      try {
        const { root } = await open();
        const toggle = root.querySelector<HTMLInputElement>('#settings-reduced-motion')!;
        expect(toggle.checked).toBe(true);
        expect(toggle.disabled).toBe(true);
        expect(text(root.querySelector('#settings-reduced-motion-help'))).toBe(
          'Your device already reduces motion, so this setting is on.',
        );
      } finally {
        if (descriptor) Object.defineProperty(window, 'matchMedia', descriptor);
        else delete (window as Partial<Window>).matchMedia;
      }
    });
  });

  describe('notifications', () => {
    it('reflects and writes the email preference, saying transactional mail always goes out', async () => {
      const { harness, root, messaging } = await open({ emailEnabled: true });
      const toggle = root.querySelector<HTMLInputElement>('#settings-email-enabled')!;
      expect(toggle.checked).toBe(true);
      expect(text(root.querySelector('#settings-email-enabled-help'))).toBe(
        'Optional message and release emails can be disabled here. Order and payment emails are always sent.',
      );
      toggle.click();
      await settle(harness);
      expect(messaging.updateEmailPreferences).toHaveBeenCalledExactlyOnceWith({
        emailEnabled: false,
      });
      expect(toggle.checked).toBe(false);
    });

    it('rolls the switch back when the preference cannot be saved', async () => {
      const { harness, root, messaging, toasts } = await open({ emailEnabled: false });
      messaging.updateEmailPreferences.mockReturnValueOnce(
        throwError(() => appError(503, 'unavailable')),
      );
      const toggle = root.querySelector<HTMLInputElement>('#settings-email-enabled')!;
      toggle.click();
      await settle(harness);
      expect(toggle.checked).toBe(false);
      expect(toasts).toHaveBeenCalledWith(expect.objectContaining({ kind: 'error' }));
    });

    it('disables the switch with a reason when the preference cannot be read', async () => {
      const { root } = await open({ emailEnabled: 'error' });
      const toggle = root.querySelector<HTMLInputElement>('#settings-email-enabled')!;
      expect(toggle.disabled).toBe(true);
      expect(text(root.querySelector('#settings-email-enabled-help'))).toBe(
        "We couldn't load this setting. Reload the page to try again.",
      );
    });
  });

  describe('developer profile', () => {
    it('confirms once with the D-29 copy, then shows the active state without a reload', async () => {
      const { harness, root, users } = await open();
      expect(text(root.querySelector('.developer-status'))).toBe('Not activated');
      button(root, 'Become a developer')!.click();
      harness.detectChanges();
      const dialog = root.querySelector('dialog')!;
      expect(text(dialog.querySelector('h2'))).toBe('Become a developer?');
      expect(text(dialog.querySelector('button[autofocus]'))).toBe('Not now');
      expect(text(dialog)).toContain(
        "This unlocks software publishing on your account. It can't be turned off later.",
      );
      const confirm = Array.from(dialog.querySelectorAll('button')).find(
        (candidate) => text(candidate) === 'Become a developer',
      )!;
      confirm.click();
      await settle(harness);
      expect(users.activateDeveloperProfile).toHaveBeenCalledTimes(1);
      expect(root.querySelector('dialog')).toBeNull();
      expect(text(root.querySelector('.developer-status'))).toBe('Developer profile: active');
      expect(button(root, 'Become a developer')).toBeUndefined();
    });

    it('leaves the account unchanged when the dialog is dismissed with Not now', async () => {
      const { harness, root, users } = await open();
      button(root, 'Become a developer')!.click();
      harness.detectChanges();
      button(root, 'Not now')!.click();
      await settle(harness);
      expect(users.activateDeveloperProfile).not.toHaveBeenCalled();
      expect(text(root.querySelector('.developer-status'))).toBe('Not activated');
    });

    it('reuses the account summary for developer status instead of a second profile read', async () => {
      const { users } = await open();
      expect(users.getPublicProfile).toHaveBeenCalledTimes(1);
    });

    it('shows an existing developer profile as active with no activation button', async () => {
      const { root } = await open({ profile: publicProfile({ isDeveloper: true }) });
      expect(text(root.querySelector('.developer-status'))).toBe('Developer profile: active');
      expect(button(root, 'Become a developer')).toBeUndefined();
    });
  });

  describe('leaving the page', () => {
    it('asks before discarding unsaved profile changes and stays when told to keep editing', async () => {
      const { harness, root, router } = await open();
      type(harness, root, 'settings-bio', 'Changed');
      const navigation = router.navigateByUrl('/me');
      await pendingPrompt(harness);
      const dialog = root.querySelector('dialog')!;
      expect(text(dialog.querySelector('h2'))).toBe('Leave without saving?');
      expect(text(dialog.querySelector('button[autofocus]'))).toBe('Keep editing');
      button(root, 'Keep editing')!.click();
      expect(await navigation).toBe(false);
      expect(router.url).toBe('/settings');
    });

    it('leaves after confirming the discard', async () => {
      const { harness, root, router } = await open();
      type(harness, root, 'settings-bio', 'Changed');
      const navigation = router.navigateByUrl('/me');
      await pendingPrompt(harness);
      button(root, 'Leave')!.click();
      expect(await navigation).toBe(true);
      expect(router.url).toBe('/me');
    });

    it('lets Cancel go back to the account without asking when nothing changed', async () => {
      const { harness, root, router } = await open();
      button(root, 'Cancel')!.click();
      await settle(harness);
      expect(router.url).toBe('/me');
    });

    it('does not ask again once the changes are saved', async () => {
      const { harness, root, router } = await open();
      type(harness, root, 'settings-bio', 'Changed');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(await router.navigateByUrl('/me')).toBe(true);
    });

    it('logs out through Cognito, clears the session and stops realtime', async () => {
      const { harness, root, adapter, store, deactivate, router } = await open();
      button(root, 'Log out')!.click();
      await settle(harness);
      expect(adapter.signOut).toHaveBeenCalledTimes(1);
      expect(store.isAuthenticated()).toBe(false);
      expect(deactivate).toHaveBeenCalled();
      expect(router.url).toBe('/');
    });
  });
});
