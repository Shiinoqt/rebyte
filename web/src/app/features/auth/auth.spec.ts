import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, Routes, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CatalogApi } from '../../core/api/catalog-api';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter, AuthIdentity } from '../../core/auth/auth.adapter';
import { guestGuard } from '../../core/auth/auth.guards';
import { AuthStatus, AuthStore } from '../../core/auth/auth.store';
import { CurrentProfile } from '../../core/shell/current-profile';
import { ToastService } from '../../shared/ui/feedback';
import { AuthFlowService, RESEND_COOLDOWN_SECONDS, authErrorCopy } from './auth-flow.service';
import { AUTH_ROUTES } from './auth.routes';

@Component({ template: '<h1>Page</h1>' })
class TargetPage {}

const routes: Routes = [
  { path: '', component: TargetPage },
  { path: 'orders/:id', component: TargetPage },
  ...(['login', 'signup', 'confirm', 'forgot'] as const).map((authStep) => ({
    path: authStep,
    canActivate: [guestGuard],
    data: { authStep },
    loadChildren: () => AUTH_ROUTES,
  })),
];

const SIGNED_IN_RESULT = { isSignedIn: true, nextStep: { signInStep: 'DONE' } };

function awsError(name: string, message = 'Raw Amplify message that must never reach the page') {
  return Object.assign(new Error(message), { name });
}

describe('auth screens', () => {
  const status = signal<AuthStatus>('guest');
  const identity = signal<AuthIdentity | null>(null);
  const store = {
    status,
    identity,
    isAuthenticated: computed(() => status() === 'authenticated'),
    whenResolved: vi.fn(async (): Promise<void> => undefined),
    clearSession: vi.fn(),
  };
  const adapter = {
    signIn: vi.fn(),
    signUp: vi.fn(),
    confirmSignUp: vi.fn(),
    autoSignIn: vi.fn(),
    resendSignUpCode: vi.fn(),
    resetPassword: vi.fn(),
    confirmResetPassword: vi.fn(),
    signOut: vi.fn(),
  };
  const users = {
    getMe: vi.fn(),
    updateProfile: vi.fn(),
    usernameAvailable: vi.fn(),
  };
  const profile = { reload: vi.fn() };
  const catalog = { search: vi.fn() };
  let harness: RouterTestingHarness;

  function authenticate(email = 'member@example.test'): void {
    status.set('authenticated');
    identity.set({ sub: 'member', email, groups: [] });
  }

  beforeEach(() => {
    localStorage.clear();
    status.set('guest');
    identity.set(null);
    vi.clearAllMocks();
    store.whenResolved.mockResolvedValue(undefined);
    adapter.signIn.mockImplementation(async () => {
      authenticate();
      return SIGNED_IN_RESULT;
    });
    adapter.resendSignUpCode.mockResolvedValue({});
    adapter.resetPassword.mockResolvedValue({
      isPasswordReset: false,
      nextStep: { resetPasswordStep: 'CONFIRM_RESET_PASSWORD' },
    });
    adapter.confirmResetPassword.mockResolvedValue(undefined);
    catalog.search.mockReturnValue(of({ hits: [], total: 0, offset: 0, size: 12, facets: [] }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: AuthStore, useValue: store },
        { provide: AuthAdapter, useValue: adapter },
        { provide: UserApi, useValue: users },
        { provide: CurrentProfile, useValue: profile },
        { provide: CatalogApi, useValue: catalog },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  const root = () => harness.routeNativeElement as HTMLElement;
  const router = () => TestBed.inject(Router);
  const heading = () => root().querySelector('h1')?.textContent?.trim();
  const field = (id: string) => root().querySelector<HTMLInputElement>('#' + id);
  const formError = () => root().querySelector('.form-error')?.textContent?.trim();

  async function open(url: string): Promise<void> {
    harness = await RouterTestingHarness.create(url);
    harness.detectChanges();
  }

  function type(id: string, value: string): void {
    const input = field(id);
    if (!input) throw new Error('missing field #' + id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
  }

  function submit(): void {
    root().querySelector<HTMLButtonElement>('form button[type="submit"]')!.click();
  }

  async function until(check: () => void): Promise<void> {
    await vi.waitFor(() => {
      harness.detectChanges();
      check();
    });
  }

  it.each(['/login', '/signup', '/confirm', '/forgot'])(
    'keeps one home wordmark in the responsive auth layout for %s',
    async (url) => {
      await open(url);
      const marks = root().querySelectorAll<HTMLAnchorElement>('.auth-wordmark');
      expect(marks).toHaveLength(1);
      expect(marks[0].getAttribute('href')).toBe('/');
      expect(marks[0].textContent?.replace(/\s/g, '')).toBe('rebyte');
      expect(marks[0].closest('.panel')).not.toBeNull();
      expect(root().querySelector('app-auth-showcase')).not.toBeNull();
    },
  );

  it('marks each displayed auth field as required', async () => {
    await open('/login');
    for (const id of ['auth-email', 'auth-password']) {
      expect(field(id)?.required).toBe(true);
      expect(field(id)?.getAttribute('aria-required')).toBe('true');
    }

    await router().navigateByUrl('/signup');
    harness.detectChanges();
    for (const id of ['auth-username', 'auth-email', 'auth-password']) {
      expect(field(id)?.required).toBe(true);
      expect(field(id)?.getAttribute('aria-required')).toBe('true');
    }

    await router().navigateByUrl('/confirm');
    harness.detectChanges();
    expect(field('auth-code')?.required).toBe(true);
    expect(field('auth-code')?.getAttribute('aria-required')).toBe('true');

    await router().navigateByUrl('/forgot');
    harness.detectChanges();
    type('auth-email', 'member@example.test');
    submit();
    await until(() => expect(field('auth-new-password')).not.toBeNull());
    for (const id of ['auth-code', 'auth-new-password']) {
      expect(field(id)?.required).toBe(true);
      expect(field(id)?.getAttribute('aria-required')).toBe('true');
    }
  });

  it('switches account modes with semantic links and carries email only', async () => {
    await open('/login?returnUrl=%2Forders%2F42');
    type('auth-email', 'member@example.test');
    type('auth-password', 'should-not-carry');
    const accountNav = root().querySelector('nav[aria-label="Account"]')!;
    expect(accountNav.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('Log in');
    accountNav.querySelector<HTMLAnchorElement>('a[href^="/signup"]')!.click();

    await until(() => expect(router().url).toBe('/signup?returnUrl=%2Forders%2F42'));
    expect(field('auth-email')?.value).toBe('member@example.test');
    expect(field('auth-password')?.value).toBe('');
    expect(root().querySelector('nav[aria-label="Account"] [aria-current="page"]')?.textContent?.trim()).toBe('Sign up');

    type('auth-password', 'also-not-carried');
    root().querySelector<HTMLAnchorElement>('nav[aria-label="Account"] a[href^="/login"]')!.click();
    await until(() => expect(router().url).toBe('/login?returnUrl=%2Forders%2F42'));
    expect(field('auth-email')?.value).toBe('member@example.test');
    expect(field('auth-password')?.value).toBe('');
    expect(Object.values(localStorage).some((value) => value.includes('should-not-carry'))).toBe(false);
    expect(Object.values(localStorage).some((value) => value.includes('also-not-carried'))).toBe(false);
  });

  it('shows three value points and the first three photographed listings as decorative cards', async () => {
    const hit = (id: string, coverUrl: string | null) => ({
      id, title: 'Listing ' + id, description: '', price: '120.00', listingType: 'HARDWARE',
      status: 'ACTIVE', categoryId: 'c', categoryPath: [], sellerUsername: null, publishedAt: '',
      condition: 'VERY_GOOD', shippingLocation: null, platforms: [], currentVersion: null, coverUrl, attr: {},
    });
    catalog.search.mockReturnValue(
      of({ hits: [hit('a', 'a.jpg'), hit('b', null), hit('c', 'c.jpg'), hit('d', 'd.jpg'), hit('e', 'e.jpg')], total: 5, offset: 0, size: 12, facets: [] }),
    );
    await open('/login');
    const showcase = root().querySelector('app-auth-showcase')!;
    expect(showcase.querySelectorAll('.points li')).toHaveLength(3);
    expect(Array.from(showcase.querySelectorAll('.card .title')).map((title) => title.textContent)).toEqual([
      'Listing a', 'Listing c', 'Listing d',
    ]);
    expect(showcase.querySelector('.stack')?.getAttribute('aria-hidden')).toBe('true');
    expect(showcase.querySelector('a')).toBeNull();
  });

  it('keeps the value points but no cards when the listing request fails', async () => {
    catalog.search.mockReturnValue(throwError(() => new Error('down')));
    await open('/login');
    const showcase = root().querySelector('app-auth-showcase')!;
    expect(showcase.querySelectorAll('.points li')).toHaveLength(3);
    expect(showcase.querySelector('.stack')).toBeNull();
  });

  describe('password visibility', () => {
    const toggleFor = (id: string) =>
      field(id)?.closest('.control')?.querySelector<HTMLButtonElement>('button.reveal') ?? null;

    it.each([
      ['/login', 'auth-password'],
      ['/signup', 'auth-password'],
    ])('toggles %s password with an icon button inside the field', async (url, id) => {
      await open(url);
      const toggle = toggleFor(id)!;
      expect(toggle).not.toBeNull();
      expect(toggle.textContent?.trim()).toBe('');
      expect(toggle.querySelector('svg')).not.toBeNull();
      expect(toggle.getAttribute('aria-label')).toBe('Show password');
      expect(toggle.getAttribute('aria-pressed')).toBe('false');
      expect(field(id)?.type).toBe('password');

      toggle.click();
      harness.detectChanges();

      expect(field(id)?.type).toBe('text');
      expect(toggle.getAttribute('aria-label')).toBe('Hide password');
      expect(toggle.getAttribute('aria-pressed')).toBe('true');
      expect(root().textContent).not.toContain('Show password');
    });

    it('toggles the new password in the reset step', async () => {
      await open('/forgot');
      type('auth-email', 'member@example.test');
      submit();
      await until(() => expect(field('auth-new-password')).not.toBeNull());

      const toggle = toggleFor('auth-new-password')!;
      expect(toggle.getAttribute('aria-label')).toBe('Show password');
      toggle.click();
      harness.detectChanges();
      expect(field('auth-new-password')?.type).toBe('text');
    });
  });

  describe('login', () => {
    it('signs in through Cognito and returns to a safe returnUrl', async () => {
      await open('/login?returnUrl=%2Forders%2F42');
      expect(heading()).toBe('Log in');
      expect(root().textContent).not.toMatch(/local/i);

      type('auth-email', 'member@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/orders/42'));
      expect(adapter.signIn).toHaveBeenCalledExactlyOnceWith({
        email: 'member@example.test',
        password: 'S3cret-pass!',
      });
    });

    it.each([
      'https://evil.example/',
      '//evil.example',
      '/%2F%2Fevil.example',
      'javascript:alert(1)',
    ])('never follows the unsafe returnUrl %s', async (returnUrl) => {
      await open('/login?returnUrl=' + encodeURIComponent(returnUrl));
      type('auth-email', 'member@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/'));
    });

    it('shows the generic credentials copy and never the Amplify message', async () => {
      adapter.signIn.mockRejectedValue(
        awsError('NotAuthorizedException', 'Incorrect username or password.'),
      );
      await open('/login');
      type('auth-email', 'member@example.test');
      type('auth-password', 'wrong');
      submit();

      await until(() => expect(formError()).toBe('Email or password is incorrect.'));
      expect(root().textContent).not.toContain('Incorrect username or password.');
      expect(router().url).toBe('/login');
    });

    it('treats an unknown account like a wrong password', async () => {
      adapter.signIn.mockRejectedValue(awsError('UserNotFoundException'));
      await open('/login');
      type('auth-email', 'nobody@example.test');
      type('auth-password', 'whatever');
      submit();

      await until(() => expect(formError()).toBe('Email or password is incorrect.'));
    });

    it('validates both fields on submit and focuses the first invalid one', async () => {
      await open('/login');
      submit();

      await until(() => {
        expect(root().textContent).toContain('Enter an email address like name@example.com.');
        expect(root().textContent).toContain('Enter your password.');
      });
      expect(field('auth-email')?.getAttribute('aria-invalid')).toBe('true');
      expect(field('auth-password')?.getAttribute('aria-invalid')).toBe('true');
      const messages = [...root().querySelectorAll('.field-error')].map((e) =>
        e.textContent?.trim(),
      );
      expect(messages).toEqual([
        'Enter an email address like name@example.com.',
        'Enter your password.',
      ]);
      await vi.waitFor(() => expect(document.activeElement).toBe(field('auth-email')));
      expect(adapter.signIn).not.toHaveBeenCalled();
    });

    it('routes an unconfirmed account to the confirm step with its returnUrl', async () => {
      adapter.signIn.mockResolvedValue({
        isSignedIn: false,
        nextStep: { signInStep: 'CONFIRM_SIGN_UP' },
      });
      await open('/login?returnUrl=%2Forders%2F42');
      type('auth-email', 'new@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/confirm?returnUrl=%2Forders%2F42'));
      expect(heading()).toBe('Check your email');
      expect(TestBed.inject(AuthFlowService).email()).toBe('new@example.test');
    });

    it('routes a reset-required account to the forgot step', async () => {
      adapter.signIn.mockResolvedValue({
        isSignedIn: false,
        nextStep: { signInStep: 'RESET_PASSWORD' },
      });
      await open('/login');
      type('auth-email', 'old@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/forgot'));
      expect(heading()).toBe('Reset your password');
      expect(field('auth-email')?.value).toBe('old@example.test');
    });

    it('offers the sign-up tab and the forgot link', async () => {
      await open('/login');
      expect(root().querySelector('nav[aria-label="Account"]')).not.toBeNull();
      expect(root().querySelector('nav a[aria-current="page"]')?.textContent?.trim()).toBe('Log in');
      expect(root().querySelector('a[href="/forgot"]')?.textContent?.trim()).toBe(
        'Forgot password?',
      );
      expect(root().textContent).not.toMatch(/google|continue with/i);
    });

    it.each(['login', 'signup', 'confirm', 'forgot'])(
      'redirects a signed-in user away from /%s',
      async (path) => {
        authenticate();
        await open('/' + path);
        expect(router().url).toBe('/');
        expect(heading()).toBe('Page');
      },
    );
  });

  describe('confirm', () => {
    beforeEach(() => {
      TestBed.inject(AuthFlowService).email.set('new@example.test');
    });

    it('confirms the code and signs in automatically', async () => {
      adapter.confirmSignUp.mockResolvedValue({
        isSignUpComplete: true,
        nextStep: { signUpStep: 'COMPLETE_AUTO_SIGN_IN' },
      });
      adapter.autoSignIn.mockImplementation(async () => {
        authenticate('new@example.test');
        return SIGNED_IN_RESULT;
      });
      await open('/confirm?returnUrl=%2Forders%2F42');
      expect(field('auth-code')?.getAttribute('inputmode')).toBe('numeric');
      expect(field('auth-code')?.getAttribute('autocomplete')).toBe('one-time-code');

      type('auth-code', '123456');
      submit();

      await until(() => expect(router().url).toBe('/orders/42'));
      expect(adapter.confirmSignUp).toHaveBeenCalledExactlyOnceWith({
        email: 'new@example.test',
        code: '123456',
      });
    });

    it('falls back to the login form with a toast when auto sign-in is unavailable', async () => {
      adapter.confirmSignUp.mockResolvedValue({
        isSignUpComplete: true,
        nextStep: { signUpStep: 'COMPLETE_AUTO_SIGN_IN' },
      });
      adapter.autoSignIn.mockRejectedValue(awsError('AutoSignInException'));
      await open('/confirm');
      type('auth-code', '123456');
      submit();

      await until(() => expect(router().url).toBe('/login'));
      expect(TestBed.inject(ToastService).current()?.text).toBe(
        'Email confirmed. Log in to continue.',
      );
    });

    it('goes to login when the account was confirmed outside the sign-up session', async () => {
      adapter.confirmSignUp.mockResolvedValue({
        isSignUpComplete: true,
        nextStep: { signUpStep: 'DONE' },
      });
      await open('/confirm');
      type('auth-code', '123456');
      submit();

      await until(() => expect(router().url).toBe('/login'));
      expect(adapter.autoSignIn).not.toHaveBeenCalled();
    });

    it.each([
      ['CodeMismatchException', 'That code is not right. Check the email and try again.'],
      ['ExpiredCodeException', 'That code has expired. Send a new one.'],
    ])('maps %s to its copy', async (name, copy) => {
      adapter.confirmSignUp.mockRejectedValue(awsError(name));
      await open('/confirm');
      type('auth-code', '123456');
      submit();

      await until(() => expect(formError()).toBe(copy));
      expect(root().textContent).not.toContain('Raw Amplify message');
      expect(router().url).toBe('/confirm');
    });

    it('rejects a malformed code without calling Cognito', async () => {
      await open('/confirm');
      type('auth-code', '12ab');
      submit();

      await until(() =>
        expect(root().textContent).toContain('Enter the 6-digit code from your email.'),
      );
      expect(adapter.confirmSignUp).not.toHaveBeenCalled();
    });

    it('asks for the email when the page is opened after a reload', async () => {
      TestBed.inject(AuthFlowService).email.set('');
      await open('/confirm');
      expect(field('auth-email')).not.toBeNull();
    });

    it('limits "Send a new code" to one request every 30 seconds', async () => {
      await open('/confirm');
      const resend = () => root().querySelector<HTMLButtonElement>('[data-resend]')!;
      expect(resend().textContent?.trim()).toBe('Send a new code');
      expect(resend().disabled).toBe(false);

      vi.useFakeTimers();
      resend().click();
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();

      expect(adapter.resendSignUpCode).toHaveBeenCalledExactlyOnceWith('new@example.test');
      expect(resend().disabled).toBe(true);
      expect(resend().textContent?.trim()).toBe(`Send a new code in ${RESEND_COOLDOWN_SECONDS}s`);

      await vi.advanceTimersByTimeAsync(29_000);
      harness.detectChanges();
      expect(resend().disabled).toBe(true);
      expect(resend().textContent?.trim()).toBe('Send a new code in 1s');

      await vi.advanceTimersByTimeAsync(1_000);
      harness.detectChanges();
      expect(resend().disabled).toBe(false);
      expect(resend().textContent?.trim()).toBe('Send a new code');
    });

    it('starts the cooldown right after sign-up because the code was just sent', async () => {
      adapter.signUp.mockResolvedValue({
        isSignUpComplete: false,
        nextStep: { signUpStep: 'CONFIRM_SIGN_UP' },
      });
      const flow = TestBed.inject(AuthFlowService);
      await flow.signUp({
        email: 'new@example.test',
        username: 'fresh_name',
        password: 'S3cret-pass!',
        returnUrl: '/',
      });
      expect(flow.resendCooldown()).toBe(RESEND_COOLDOWN_SECONDS);
      expect(router().url).toBe('/confirm');
    });
  });

  describe('forgot', () => {
    it('advances from the email step to the code and new password step', async () => {
      await open('/forgot');
      expect(heading()).toBe('Reset your password');
      expect(field('auth-code')).toBeNull();

      type('auth-email', 'member@example.test');
      submit();

      await until(() => expect(field('auth-code')).not.toBeNull());
      expect(adapter.resetPassword).toHaveBeenCalledExactlyOnceWith('member@example.test');
      expect(field('auth-new-password')).not.toBeNull();
      expect(field('auth-email')).toBeNull();
    });

    it('applies the verified password policy to password reset', async () => {
      await open('/forgot');
      type('auth-email', 'member@example.test');
      submit();
      await until(() => expect(field('auth-code')).not.toBeNull());
      type('auth-code', '654321');
      expect(field('auth-new-password')?.getAttribute('aria-describedby')).toContain(
        'auth-new-password-hint',
      );
      type('auth-new-password', 'S3cret pass!');
      submit();

      await until(() => {
        expect(adapter.confirmResetPassword).not.toHaveBeenCalled();
        expect(root().querySelector('.field-error')?.textContent).toContain(
          'At least 12 characters, including uppercase and lowercase letters, a number and a symbol. No spaces.',
        );
      });
      expect(root().querySelector('#auth-new-password-hint')).toBeNull();
      expect(field('auth-new-password')?.getAttribute('aria-describedby')).toBe(
        'auth-new-password-error',
      );
      type('auth-new-password', 'N3w-secret-pass!');
      field('auth-new-password')!.dispatchEvent(new Event('blur'));
      harness.detectChanges();
      expect(root().querySelector('#auth-new-password-error')).toBeNull();
      expect(root().querySelector('#auth-new-password-hint')).not.toBeNull();
    });

    it('sets the new password and returns to login with a toast', async () => {
      await open('/forgot?returnUrl=%2Forders%2F42');
      type('auth-email', 'member@example.test');
      submit();
      await until(() => expect(field('auth-code')).not.toBeNull());

      type('auth-code', '654321');
      type('auth-new-password', 'N3w-secret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/login?returnUrl=%2Forders%2F42'));
      expect(adapter.confirmResetPassword).toHaveBeenCalledExactlyOnceWith({
        email: 'member@example.test',
        code: '654321',
        newPassword: 'N3w-secret-pass!',
      });
      expect(TestBed.inject(ToastService).current()?.text).toBe(
        'Password updated. Log in with your new password.',
      );
    });

    it('does not reveal whether the account exists', async () => {
      adapter.resetPassword.mockRejectedValue(awsError('UserNotFoundException'));
      await open('/forgot');
      type('auth-email', 'nobody@example.test');
      submit();

      await until(() => expect(field('auth-code')).not.toBeNull());
    });

    it('maps rate limiting on the reset request', async () => {
      adapter.resetPassword.mockRejectedValue(awsError('LimitExceededException'));
      await open('/forgot');
      type('auth-email', 'member@example.test');
      submit();

      await until(() =>
        expect(formError()).toBe('Too many attempts. Wait a few minutes and try again.'),
      );
      expect(field('auth-code')).toBeNull();
    });

    it.each([
      ['CodeMismatchException', 'That code is not right. Check the email and try again.'],
      ['ExpiredCodeException', 'That code has expired. Send a new one.'],
    ])('maps %s when the code is submitted', async (name, copy) => {
      adapter.confirmResetPassword.mockRejectedValue(awsError(name));
      await open('/forgot');
      type('auth-email', 'member@example.test');
      submit();
      await until(() => expect(field('auth-code')).not.toBeNull());

      type('auth-code', '654321');
      type('auth-new-password', 'N3w-secret-pass!');
      submit();

      await until(() => expect(formError()).toBe(copy));
      expect(router().url).toBe('/forgot');
    });

    it('limits "Send a new code" on the reset step to one request every 30 seconds', async () => {
      await open('/forgot');
      vi.useFakeTimers();
      type('auth-email', 'member@example.test');
      submit();
      await vi.advanceTimersByTimeAsync(0);
      harness.detectChanges();
      expect(field('auth-code')).not.toBeNull();

      const resend = () => root().querySelector<HTMLButtonElement>('[data-resend]')!;
      expect(resend().disabled).toBe(true);
      await vi.advanceTimersByTimeAsync(RESEND_COOLDOWN_SECONDS * 1000);
      harness.detectChanges();
      expect(resend().disabled).toBe(false);

      resend().click();
      await vi.advanceTimersByTimeAsync(0);
      expect(adapter.resetPassword).toHaveBeenCalledTimes(2);
    });
  });

  describe('signup username', () => {
    const PENDING_KEY = 'rebyte.pendingUsername';
    const GENERATED = 'user_0a1b2c3d';
    const conflict = {
      status: 409,
      code: 'username-conflict',
      type: 'about:blank',
      fieldErrors: {},
      extras: {},
    };
    const me = (username = GENERATED) => ({
      id: 'member',
      username,
      registration: '2026-01-01T00:00:00Z',
      location: 'Milan',
      bio: 'Retro hardware fan',
      avatar: null,
    });
    const status = () => root().querySelector('#auth-username-status')?.textContent?.trim();
    const usernameInput = () => field('auth-username')!;

    async function openSignup(url = '/signup'): Promise<void> {
      await open(url);
      vi.useFakeTimers();
    }

    async function typeName(name: string, wait = 400): Promise<void> {
      type('auth-username', name);
      await vi.advanceTimersByTimeAsync(wait);
      harness.detectChanges();
    }

    function fillSignup(): void {
      type('auth-email', 'New@Example.test');
      type('auth-password', 'S3cret-pass!');
    }

    beforeEach(() => {
      users.usernameAvailable.mockImplementation(() => of({ available: true, reason: null }));
      users.getMe.mockImplementation(() => of(me()));
      users.updateProfile.mockImplementation((request: { username: string }) =>
        of(me(request.username)),
      );
      adapter.signUp.mockResolvedValue({
        isSignUpComplete: false,
        nextStep: { signUpStep: 'CONFIRM_SIGN_UP' },
      });
    });

    it('asks for the username before email and password with the rules as a hint', async () => {
      await open('/signup');
      const ids = [...root().querySelectorAll('input')].map((input) => input.id);
      expect(ids).toEqual(['auth-username', 'auth-email', 'auth-password']);
      expect(heading()).toBe('Create your account');
      expect(root().textContent).toContain(
        '3 to 30 characters: lowercase letters, digits, _ or -.',
      );
      expect(usernameInput().getAttribute('autocomplete')).toBe('off');
      expect(field('auth-password')?.getAttribute('aria-describedby')).toContain('auth-password-hint');
      expect(root().textContent).toContain(
        'At least 12 characters, including uppercase and lowercase letters, a number and a symbol. No spaces.',
      );
    });

    it.each([
      ['s3cret-pass!', 'missing uppercase'],
      ['S3CRET-PASS!', 'missing lowercase'],
      ['Secret-pass!', 'missing number'],
      ['S3cretpass12', 'missing symbol'],
      ['S3cret pass!', 'whitespace instead of a symbol'],
      ['S3cret-pa!', 'shorter than 12 characters'],
      ['A1a!' + '😀'.repeat(4), 'fewer than 12 characters'],
    ])('rejects a signup password that is %s', async (password) => {
      await openSignup();
      await typeName('fresh_name');
      type('auth-email', 'new@example.test');
      type('auth-password', password);
      submit();

      await until(() => {
        expect(adapter.signUp).not.toHaveBeenCalled();
        expect(root().querySelector('.field-error')?.textContent).toContain(
          'At least 12 characters, including uppercase and lowercase letters, a number and a symbol. No spaces.',
        );
      });
      expect(root().querySelector('#auth-password-hint')).toBeNull();
      expect(field('auth-password')?.getAttribute('aria-describedby')).toBe('auth-password-error');
      type('auth-password', 'Abcdefghij_1');
      field('auth-password')!.dispatchEvent(new Event('blur'));
      harness.detectChanges();
      expect(root().querySelector('#auth-password-error')).toBeNull();
      expect(root().querySelector('#auth-password-hint')).not.toBeNull();
    });

    it('accepts an allowed underscore symbol at the 12-character boundary', async () => {
      await openSignup();
      await typeName('fresh_name');
      type('auth-email', 'new@example.test');
      type('auth-password', 'Abcdefghij_1');
      submit();

      await until(() => expect(adapter.signUp).toHaveBeenCalled());
    });

    it.each([
      ['Ab!', 'Use 3 to 30 characters: lowercase letters, digits, _ or -.'],
      ['a'.repeat(31), 'Use 3 to 30 characters: lowercase letters, digits, _ or -.'],
      [GENERATED, "That username isn't available."],
    ])('flags %s locally without calling the API', async (name, copy) => {
      await openSignup();
      await typeName(name, 1000);

      expect(status()).toContain(copy);
      expect(usernameInput().getAttribute('aria-invalid')).toBe('true');
      expect(users.usernameAvailable).not.toHaveBeenCalled();
    });

    it('does not check names shorter than 3 characters', async () => {
      await openSignup();
      await typeName('ab', 1000);

      expect(users.usernameAvailable).not.toHaveBeenCalled();
      expect(status()).not.toContain('Checking');
    });

    it('shows checking, then waits 400 ms after the last keystroke for one request', async () => {
      await openSignup();
      type('auth-username', 'fre');
      await vi.advanceTimersByTimeAsync(100);
      type('auth-username', 'fres');
      await vi.advanceTimersByTimeAsync(100);
      type('auth-username', 'Fresh_Name');
      expect(status()).toContain('Checking...');

      await vi.advanceTimersByTimeAsync(399);
      expect(users.usernameAvailable).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      harness.detectChanges();

      expect(users.usernameAvailable).toHaveBeenCalledExactlyOnceWith('fresh_name');
      expect(status()).toContain('@fresh_name is available.');
      expect(usernameInput().getAttribute('aria-invalid')).toBe('false');
    });

    it.each([
      ['username-taken', 'That username is taken.'],
      ['invalid-username', 'Use 3 to 30 characters: lowercase letters, digits, _ or -.'],
      ['reserved-handle', "That username isn't available."],
    ])('maps the server reason %s', async (reason, copy) => {
      users.usernameAvailable.mockImplementation(() => of({ available: false, reason }));
      await openSignup();
      await typeName('someone');

      expect(status()).toContain(copy);
      expect(usernameInput().getAttribute('aria-invalid')).toBe('true');
    });

    it('cancels the stale request so an old answer never wins', async () => {
      const first = new Subject<{ available: boolean; reason: string | null }>();
      const second = new Subject<{ available: boolean; reason: string | null }>();
      users.usernameAvailable.mockReturnValueOnce(first).mockReturnValueOnce(second);
      await openSignup();
      await typeName('first_name');
      expect(first.observed).toBe(true);

      await typeName('second_name');
      expect(first.observed).toBe(false);
      expect(users.usernameAvailable).toHaveBeenLastCalledWith('second_name');

      first.next({ available: false, reason: 'username-taken' });
      second.next({ available: true, reason: null });
      harness.detectChanges();

      expect(status()).toContain('@second_name is available.');
    });

    it('drops an in-flight check when the name becomes locally invalid', async () => {
      const pendingCheck = new Subject<{ available: boolean; reason: string | null }>();
      users.usernameAvailable.mockReturnValueOnce(pendingCheck);
      await openSignup();
      await typeName('first_name');
      await typeName('first name');

      expect(pendingCheck.observed).toBe(false);
      pendingCheck.next({ available: true, reason: null });
      harness.detectChanges();
      expect(status()).toContain('Use 3 to 30 characters');
    });

    it('shows no error when the check fails and retries on the next keystroke', async () => {
      users.usernameAvailable.mockReturnValueOnce(
        throwError(() => ({
          status: 429,
          code: 'rate-limited',
          type: '',
          fieldErrors: {},
          extras: {},
        })),
      );
      await openSignup();
      await typeName('first_name');
      expect(status()).not.toContain('Checking');
      expect(root().querySelector('#auth-username-status .error')).toBeNull();

      await typeName('first_names');
      expect(status()).toContain('@first_names is available.');
    });

    it('blocks Create account while the name is taken', async () => {
      users.usernameAvailable.mockImplementation(() =>
        of({ available: false, reason: 'username-taken' }),
      );
      await openSignup();
      await typeName('taken_name');
      fillSignup();
      vi.useRealTimers();
      submit();

      await vi.waitFor(() => expect(document.activeElement).toBe(usernameInput()));
      expect(adapter.signUp).not.toHaveBeenCalled();
      expect(status()).toContain('That username is taken.');
    });

    it('requires a username before Create account', async () => {
      await open('/signup');
      fillSignup();
      submit();

      await until(() => expect(status()).toContain('Use 3 to 30 characters'));
      await vi.waitFor(() => expect(document.activeElement).toBe(usernameInput()));
      expect(adapter.signUp).not.toHaveBeenCalled();
    });

    it('waits for a pending check before creating the account', async () => {
      await openSignup();
      type('auth-username', 'fresh_name');
      fillSignup();
      vi.useRealTimers();
      submit();

      await vi.waitFor(() => expect(adapter.signUp).toHaveBeenCalledTimes(1));
      expect(users.usernameAvailable).toHaveBeenCalledWith('fresh_name');
    });

    it('sends only email and password to Cognito and keeps the chosen name for the confirm step', async () => {
      await openSignup('/signup?returnUrl=%2Forders%2F42');
      await typeName('Fresh_Name');
      fillSignup();
      vi.useRealTimers();
      submit();

      await until(() => expect(router().url).toBe('/confirm?returnUrl=%2Forders%2F42'));
      expect(adapter.signUp).toHaveBeenCalledExactlyOnceWith({
        email: 'New@Example.test',
        password: 'S3cret-pass!',
      });
      expect(JSON.parse(localStorage.getItem(PENDING_KEY)!)).toEqual({
        username: 'fresh_name',
        email: 'new@example.test',
      });
      expect(users.updateProfile).not.toHaveBeenCalled();
    });

    it('shows the duplicate email copy and keeps the form', async () => {
      adapter.signUp.mockRejectedValue(awsError('UsernameExistsException'));
      await openSignup();
      await typeName('fresh_name');
      fillSignup();
      vi.useRealTimers();
      submit();

      await until(() =>
        expect(formError()).toBe('An account with this email already exists. Log in instead.'),
      );
      expect(localStorage.getItem(PENDING_KEY)).toBeNull();
      expect(router().url).toBe('/signup');
    });

    it('applies the chosen name after the first sign-in without clearing location or bio', async () => {
      localStorage.setItem(
        PENDING_KEY,
        JSON.stringify({ username: 'fresh_name', email: 'new@example.test' }),
      );
      TestBed.inject(AuthFlowService).email.set('new@example.test');
      adapter.confirmSignUp.mockResolvedValue({
        isSignUpComplete: true,
        nextStep: { signUpStep: 'COMPLETE_AUTO_SIGN_IN' },
      });
      adapter.autoSignIn.mockImplementation(async () => {
        authenticate('new@example.test');
        return SIGNED_IN_RESULT;
      });
      await open('/confirm');
      type('auth-code', '123456');
      submit();

      await until(() => expect(router().url).toBe('/'));
      expect(users.updateProfile).toHaveBeenCalledExactlyOnceWith({
        username: 'fresh_name',
        location: 'Milan',
        bio: 'Retro hardware fan',
      });
      expect(localStorage.getItem(PENDING_KEY)).toBeNull();
      expect(profile.reload).toHaveBeenCalled();
    });

    it('applies the chosen name on a plain login too and never shows the generated handle', async () => {
      localStorage.setItem(
        PENDING_KEY,
        JSON.stringify({ username: 'fresh_name', email: 'member@example.test' }),
      );
      await open('/login');
      type('auth-email', 'member@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/'));
      expect(users.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({ username: 'fresh_name' }),
      );
      expect(document.body.textContent).not.toContain(GENERATED);
    });

    it('leaves a username the member already chose untouched', async () => {
      users.getMe.mockImplementation(() => of(me('already_chosen')));
      localStorage.setItem(
        PENDING_KEY,
        JSON.stringify({ username: 'fresh_name', email: 'member@example.test' }),
      );
      await open('/login');
      type('auth-email', 'member@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/'));
      expect(users.updateProfile).not.toHaveBeenCalled();
      expect(localStorage.getItem(PENDING_KEY)).toBeNull();
    });

    it('ignores a pending name saved for a different email', async () => {
      localStorage.setItem(
        PENDING_KEY,
        JSON.stringify({ username: 'fresh_name', email: 'someone.else@example.test' }),
      );
      await open('/login');
      type('auth-email', 'member@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/'));
      expect(users.getMe).not.toHaveBeenCalled();
      expect(users.updateProfile).not.toHaveBeenCalled();
      expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
    });

    it('keeps the pending name and continues when applying it fails for another reason', async () => {
      users.updateProfile.mockImplementation(() =>
        throwError(() => ({
          status: 503,
          code: 'unavailable',
          type: '',
          fieldErrors: {},
          extras: {},
        })),
      );
      localStorage.setItem(
        PENDING_KEY,
        JSON.stringify({ username: 'fresh_name', email: 'member@example.test' }),
      );
      await open('/login?returnUrl=%2Forders%2F42');
      type('auth-email', 'member@example.test');
      type('auth-password', 'S3cret-pass!');
      submit();

      await until(() => expect(router().url).toBe('/orders/42'));
      expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
    });

    describe('name taken between signup and first sign-in', () => {
      async function reachTakenScreen(): Promise<void> {
        localStorage.setItem(
          PENDING_KEY,
          JSON.stringify({ username: 'fresh_name', email: 'member@example.test' }),
        );
        users.updateProfile.mockImplementationOnce(() => throwError(() => conflict));
        await open('/login?returnUrl=%2Forders%2F42');
        type('auth-email', 'member@example.test');
        type('auth-password', 'S3cret-pass!');
        submit();
        await until(() => expect(heading()).toBe('That name was just taken'));
      }

      it('shows the dedicated screen and keeps the pending name', async () => {
        await reachTakenScreen();

        expect(root().textContent).toContain(
          'Someone else claimed @fresh_name while you were confirming your email. Pick another one.',
        );
        expect(field('auth-username')?.value).toBe('');
        expect(field('auth-email')).toBeNull();
        expect(field('auth-password')).toBeNull();
        expect(root().textContent).not.toContain(GENERATED);
        expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
        expect(router().url).toBe('/login?returnUrl=%2Forders%2F42');
      });

      it('saves a new available name and continues to the returnUrl', async () => {
        await reachTakenScreen();
        vi.useFakeTimers();
        await typeName('other_name');
        vi.useRealTimers();
        root().querySelector<HTMLButtonElement>('form button[type="submit"]')!.click();

        await until(() => expect(router().url).toBe('/orders/42'));
        expect(users.updateProfile).toHaveBeenLastCalledWith({
          username: 'other_name',
          location: 'Milan',
          bio: 'Retro hardware fan',
        });
        expect(localStorage.getItem(PENDING_KEY)).toBeNull();
        expect(profile.reload).toHaveBeenCalled();
      });

      it('accepts another try when the saved name is taken again', async () => {
        await reachTakenScreen();
        users.updateProfile.mockImplementationOnce(() => throwError(() => conflict));
        vi.useFakeTimers();
        await typeName('other_name');
        vi.useRealTimers();
        submit();

        await until(() => expect(formError()).toBe('That username is taken.'));
        expect(heading()).toBe('That name was just taken');

        vi.useFakeTimers();
        await typeName('third_name');
        vi.useRealTimers();
        submit();
        await until(() => expect(router().url).toBe('/orders/42'));
        expect(users.updateProfile).toHaveBeenLastCalledWith(
          expect.objectContaining({ username: 'third_name' }),
        );
      });

      it('does not save a name the live check reports as taken', async () => {
        await reachTakenScreen();
        users.usernameAvailable.mockImplementation(() =>
          of({ available: false, reason: 'username-taken' }),
        );
        vi.useFakeTimers();
        await typeName('busy_name');
        vi.useRealTimers();
        submit();

        await until(() => expect(status()).toContain('That username is taken.'));
        expect(users.updateProfile).toHaveBeenCalledTimes(1);
      });

      it('lets the member log out instead', async () => {
        await reachTakenScreen();
        root().querySelector<HTMLButtonElement>('[data-logout]')!.click();

        await until(() => expect(router().url).toBe('/login'));
        expect(adapter.signOut).toHaveBeenCalledTimes(1);
        expect(heading()).toBe('Log in');
      });
    });
  });

  describe('error copy', () => {
    it.each([
      ['NotAuthorizedException', 'Email or password is incorrect.'],
      ['UserNotConfirmedException', 'Confirm your email first. We sent you a code.'],
      ['UsernameExistsException', 'An account with this email already exists. Log in instead.'],
      ['CodeMismatchException', 'That code is not right. Check the email and try again.'],
      ['ExpiredCodeException', 'That code has expired. Send a new one.'],
      ['LimitExceededException', 'Too many attempts. Wait a few minutes and try again.'],
      ['TooManyRequestsException', 'Too many attempts. Wait a few minutes and try again.'],
      ['NetworkError', 'You appear to be offline.'],
      ['SomethingNew', 'Something went wrong. Try again in a minute.'],
    ])('maps %s without echoing the message', (name, copy) => {
      expect(authErrorCopy(awsError(name))).toBe(copy);
    });

    it('falls back to the generic copy for non-error values', () => {
      expect(authErrorCopy(undefined)).toBe('Something went wrong. Try again in a minute.');
      expect(authErrorCopy('boom')).toBe('Something went wrong. Try again in a minute.');
    });
  });
});
