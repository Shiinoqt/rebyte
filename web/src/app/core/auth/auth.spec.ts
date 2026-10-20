import { TestBed } from '@angular/core/testing';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import { AuthAdapter, AuthEvent, AuthIdentity } from './auth.adapter';
import { AMPLIFY_SDK, AmplifyAuthAdapter, AmplifySdk } from './amplify-auth.adapter';
import { AuthStore } from './auth.store';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { adminGuard, authGuard, guestGuard, validateReturnUrl } from './auth.guards';
import { PendingUsername } from './pending-username';

const sdk = {
  configure: vi.fn(),
  listen: vi.fn(),
  fetchAuthSession: vi.fn(),
  signUp: vi.fn(),
  confirmSignUp: vi.fn(),
  autoSignIn: vi.fn(),
  signIn: vi.fn(),
  resetPassword: vi.fn(),
  confirmResetPassword: vi.fn(),
  updatePassword: vi.fn(),
  signOut: vi.fn(),
  resendSignUpCode: vi.fn(),
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => (resolve = done));
  return { promise, resolve };
}

const MEMBER: AuthIdentity = { sub: 'access-sub', email: 'alice@example.test', groups: ['BUYER'] };

class FakeAuthAdapter implements AuthAdapter {
  identity: AuthIdentity | null = MEMBER;
  configure = vi.fn();
  fetchSession = vi.fn(() => Promise.resolve(this.identity));
  accessToken = vi.fn(() => Promise.resolve(null));
  signUp = vi.fn();
  confirmSignUp = vi.fn();
  resendSignUpCode = vi.fn();
  autoSignIn = vi.fn();
  signIn = vi.fn();
  resetPassword = vi.fn();
  confirmResetPassword = vi.fn();
  updatePassword = vi.fn();
  signOut = vi.fn();
  stopListening = vi.fn();
  listener?: (event: AuthEvent) => void;

  onAuthEvent(listener: (event: AuthEvent) => void) {
    this.listener = listener;
    return this.stopListening;
  }

  emit(event: AuthEvent) {
    this.listener?.(event);
  }
}

function setupStore() {
  const adapter = new FakeAuthAdapter();
  TestBed.configureTestingModule({
    providers: [provideTestAppConfig(), { provide: AuthAdapter, useValue: adapter }],
  });
  return { adapter, store: TestBed.inject(AuthStore) };
}

describe('AuthStore through AuthAdapter', () => {
  it('clears private state when a signed-in event changes the subject, but not on a token refresh', async () => {
    const { adapter, store } = setupStore();
    await store.init();
    const clear = vi.fn();
    store.registerCleanup(clear);
    adapter.emit('tokenRefresh');
    await Promise.resolve();
    expect(clear).not.toHaveBeenCalled();
    adapter.identity = { sub: 'another-member', groups: [] };
    adapter.emit('signedIn');
    await Promise.resolve();
    expect(clear).toHaveBeenCalledTimes(1);
    expect(store.identity()?.sub).toBe('another-member');
    expect(store.status()).toBe('authenticated');
    store.clearSession();
    expect(clear).toHaveBeenCalledTimes(2);
  });

  it('configures once from loaded runtime config and resolves identity without tokens', async () => {
    const { adapter, store } = setupStore();
    expect(store.status()).toBe('resolving');
    await Promise.all([store.init(), store.init()]);
    expect(adapter.configure).toHaveBeenCalledExactlyOnceWith(TEST_APP_CONFIG.cognito);
    expect(adapter.fetchSession).toHaveBeenCalledTimes(1);
    expect(store.status()).toBe('authenticated');
    expect(store.identity()).toEqual(MEMBER);
    expect(store.isAuthenticated()).toBe(true);
    expect(store.isAdmin()).toBe(false);
    expect(adapter.accessToken).not.toHaveBeenCalled();
  });

  it.each([['ADMIN'], ['admin'], ['AdMiN']])(
    'maps access-token group %s like the backend converter',
    async (group) => {
      const { adapter, store } = setupStore();
      adapter.identity = { ...MEMBER, groups: [group] };
      await store.init();
      expect(store.isAdmin()).toBe(true);
    },
  );

  it('does not treat a partial group name as ADMIN', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = { ...MEMBER, groups: ['SUPERADMIN', 'ROLE_ADMIN'] };
    await store.init();
    expect(store.isAdmin()).toBe(false);
  });

  it('resolves a missing session as guest', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = null;
    await store.init();
    expect(store.status()).toBe('guest');
    expect(store.identity()).toBeNull();
  });

  it('copies only identity fields even if an adapter returns extra token data', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = { ...MEMBER, accessToken: 'must-stay-out-of-state' } as AuthIdentity;
    await store.init();
    expect(store.identity()).toEqual({
      sub: 'access-sub',
      email: 'alice@example.test',
      groups: ['BUYER'],
    });
  });

  it('resolves a session-fetch failure as guest', async () => {
    const { adapter, store } = setupStore();
    adapter.fetchSession.mockRejectedValue(new Error('Session unavailable'));
    await store.init();
    expect(store.status()).toBe('guest');
  });

  it.each(['signedOut', 'tokenRefresh_failure'] as const)(
    '%s clears identity and runs hooks once per session',
    async (event) => {
      const { adapter, store } = setupStore();
      const first = vi.fn();
      const second = vi.fn();
      store.registerCleanup(first);
      store.registerCleanup(second);
      await store.init();
      adapter.emit(event);
      adapter.emit('signedOut');
      adapter.emit('tokenRefresh_failure');
      expect(store.status()).toBe('guest');
      expect(store.identity()).toBeNull();
      expect(store.isAdmin()).toBe(false);
      expect(first).toHaveBeenCalledTimes(1);
      expect(second).toHaveBeenCalledTimes(1);
      adapter.emit('signedIn');
      await store.whenResolved();
      adapter.emit(event);
      expect(first).toHaveBeenCalledTimes(2);
    },
  );

  it('isolates failing hooks and supports unregistering a hook', async () => {
    const { store } = setupStore();
    const removed = vi.fn();
    store.registerCleanup(removed)();
    store.registerCleanup(() => {
      throw new Error('Cleanup unavailable');
    });
    store.registerCleanup(() => Promise.reject(new Error('Async cleanup unavailable')));
    const last = vi.fn();
    store.registerCleanup(last);
    await store.init();
    store.clearSession();
    await Promise.resolve();
    expect(removed).not.toHaveBeenCalled();
    expect(last).toHaveBeenCalledTimes(1);
  });

  it.each(['signedIn', 'tokenRefresh'] as const)(
    '%s re-resolves identity and roles',
    async (event) => {
      const { adapter, store } = setupStore();
      await store.init();
      adapter.identity = { ...MEMBER, groups: ['ADMIN'] };
      adapter.emit(event);
      await store.whenResolved();
      expect(store.isAdmin()).toBe(true);
    },
  );

  it('cleans up when a refresh lookup rejects', async () => {
    const { adapter, store } = setupStore();
    const cleanup = vi.fn();
    store.registerCleanup(cleanup);
    await store.init();
    adapter.fetchSession.mockRejectedValue(new Error('Expired session'));
    adapter.emit('tokenRefresh');
    await store.whenResolved();
    expect(store.status()).toBe('guest');
    expect(cleanup).toHaveBeenCalledTimes(1);
  });

  it('does not resurrect a signed-out identity from an in-flight lookup', async () => {
    const { adapter, store } = setupStore();
    await store.init();
    const lookup = deferred<AuthIdentity | null>();
    adapter.fetchSession.mockReturnValue(lookup.promise);
    adapter.emit('tokenRefresh');
    const resolved = store.whenResolved();
    adapter.emit('signedOut');
    await resolved;
    lookup.resolve(MEMBER);
    await Promise.resolve();
    expect(store.status()).toBe('guest');
    expect(store.identity()).toBeNull();
  });

  it('keeps a signed-in user authenticated while a token refresh re-reads the session', async () => {
    const { adapter, store } = setupStore();
    await store.init();
    const lookup = deferred<AuthIdentity | null>();
    adapter.fetchSession.mockReturnValue(lookup.promise);
    adapter.emit('tokenRefresh');
    expect(store.status()).toBe('authenticated');
    lookup.resolve(MEMBER);
    await lookup.promise;
    await Promise.resolve();
    expect(store.status()).toBe('authenticated');
    expect(store.identity()?.sub).toBe('access-sub');
  });

  it('disposes the Hub subscription when its injector is destroyed', async () => {
    const { adapter, store } = setupStore();
    await store.init();
    TestBed.resetTestingModule();
    expect(adapter.stopListening).toHaveBeenCalledTimes(1);
  });
});

describe('AmplifyAuthAdapter boundary', () => {
  let adapter: AmplifyAuthAdapter;

  beforeEach(() => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [{ provide: AMPLIFY_SDK, useValue: sdk as unknown as AmplifySdk }],
    });
    adapter = TestBed.inject(AmplifyAuthAdapter);
  });

  it('provides the real adapter as the default AuthAdapter token', () => {
    expect(TestBed.inject(AuthAdapter)).toBe(adapter);
  });

  it('configures the public Cognito app client for email login', () => {
    adapter.configure(TEST_APP_CONFIG.cognito);
    expect(sdk.configure).toHaveBeenCalledExactlyOnceWith({
      Auth: {
        Cognito: {
          userPoolId: 'eu-south-1_TESTPOOL',
          userPoolClientId: 'testclientid',
          loginWith: { email: true },
        },
      },
    });
  });

  it('uses the SDK default SRP path and preserves next steps', async () => {
    const result = { isSignedIn: false, nextStep: { signInStep: 'CONFIRM_SIGN_UP' } };
    sdk.signIn.mockResolvedValue(result);
    expect(
      await adapter.signIn({ email: 'alice@example.test', password: 'only-in-memory' }),
    ).toEqual(result);
    expect(sdk.signIn).toHaveBeenCalledExactlyOnceWith({
      username: 'alice@example.test',
      password: 'only-in-memory',
    });
  });

  it('signs up with email and auto-sign-in, keeping the profile username out of Cognito', async () => {
    await adapter.signUp({ email: 'alice@example.test', password: 'only-in-memory' });
    expect(sdk.signUp).toHaveBeenCalledExactlyOnceWith({
      username: 'alice@example.test',
      password: 'only-in-memory',
      options: { userAttributes: { email: 'alice@example.test' }, autoSignIn: true },
    });
  });

  it('translates confirmation and password inputs to installed SDK signatures', async () => {
    await adapter.confirmSignUp({ email: 'alice@example.test', code: '123456' });
    await adapter.autoSignIn();
    await adapter.resetPassword('alice@example.test');
    await adapter.confirmResetPassword({
      email: 'alice@example.test',
      code: '123456',
      newPassword: 'only-in-memory',
    });
    await adapter.updatePassword({ oldPassword: 'old-in-memory', newPassword: 'new-in-memory' });
    await adapter.signOut();
    expect(sdk.confirmSignUp).toHaveBeenCalledWith({
      username: 'alice@example.test',
      confirmationCode: '123456',
    });
    expect(sdk.autoSignIn).toHaveBeenCalledExactlyOnceWith();
    expect(sdk.resetPassword).toHaveBeenCalledWith({ username: 'alice@example.test' });
    expect(sdk.confirmResetPassword).toHaveBeenCalledWith({
      username: 'alice@example.test',
      confirmationCode: '123456',
      newPassword: 'only-in-memory',
    });
    expect(sdk.updatePassword).toHaveBeenCalledWith({
      oldPassword: 'old-in-memory',
      newPassword: 'new-in-memory',
    });
    expect(sdk.signOut).toHaveBeenCalledExactlyOnceWith();
  });

  it('derives sub/groups only from access claims and email only from ID claims', async () => {
    sdk.fetchAuthSession.mockResolvedValue({
      userSub: 'ignored-session-sub',
      tokens: {
        accessToken: {
          payload: {
            sub: 'access-sub',
            email: 'ignored-access@example.test',
            'cognito:groups': ['ADMIN', 7],
          },
          toString: () => 'private-access-token',
        },
        idToken: {
          payload: {
            sub: 'ignored-id-sub',
            email: 'alice@example.test',
            'cognito:groups': ['BUYER'],
          },
          toString: () => 'private-id-token',
        },
      },
    });
    expect(await adapter.fetchSession()).toEqual({
      sub: 'access-sub',
      email: 'alice@example.test',
      groups: ['ADMIN'],
    });
  });

  it('does not use ID-token groups to grant ADMIN', async () => {
    sdk.fetchAuthSession.mockResolvedValue({
      tokens: {
        accessToken: { payload: { sub: 'access-sub' } },
        idToken: { payload: { 'cognito:groups': ['ADMIN'] } },
      },
    });
    expect(await adapter.fetchSession()).toEqual({ sub: 'access-sub', groups: [] });
  });

  it.each([
    {},
    { tokens: { idToken: { payload: { sub: 'id-sub' } } } },
    { tokens: { accessToken: { payload: {} } } },
  ])('resolves missing access token or subject as guest (%j)', async (session) => {
    sdk.fetchAuthSession.mockResolvedValue(session);
    expect(await adapter.fetchSession()).toBeNull();
  });

  it('forwards forceRefresh through the sole token accessor', async () => {
    sdk.fetchAuthSession.mockResolvedValue({
      tokens: { accessToken: { toString: () => 'private-access-token' } },
    });
    expect(await adapter.accessToken({ forceRefresh: true })).toBe('private-access-token');
    expect(sdk.fetchAuthSession).toHaveBeenCalledExactlyOnceWith({ forceRefresh: true });
  });

  it('returns null for unavailable tokens or refresh failure', async () => {
    sdk.fetchAuthSession.mockResolvedValue({});
    expect(await adapter.accessToken()).toBeNull();
    sdk.fetchAuthSession.mockRejectedValue(new Error('Refresh unavailable'));
    expect(await adapter.accessToken({ forceRefresh: true })).toBeNull();
  });

  it('forwards only the four session Hub events without their data', () => {
    const stop = vi.fn();
    sdk.listen.mockReturnValue(stop);
    const listener = vi.fn();
    expect(adapter.onAuthEvent(listener)).toBe(stop);
    expect(sdk.listen.mock.calls[0][0]).toBe('auth');
    const receive = sdk.listen.mock.calls[0][1];
    for (const event of [
      'signedIn',
      'signedOut',
      'tokenRefresh',
      'tokenRefresh_failure',
      'signInWithRedirect',
    ]) {
      receive({ payload: { event, data: { private: 'not-forwarded' } } });
    }
    expect(listener.mock.calls).toEqual([
      ['signedIn'],
      ['signedOut'],
      ['tokenRefresh'],
      ['tokenRefresh_failure'],
    ]);
  });
});

function runGuard(guard: CanActivateFn, url = '/sell') {
  return TestBed.runInInjectionContext(() =>
    guard(new ActivatedRouteSnapshot(), { url } as RouterStateSnapshot),
  ) as Promise<boolean | UrlTree>;
}

describe('route guards through AuthAdapter', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  it('waits for session resolution before deciding a protected navigation', async () => {
    const { adapter, store } = setupStore();
    const lookup = deferred<AuthIdentity | null>();
    adapter.fetchSession.mockReturnValue(lookup.promise);
    const init = store.init();
    let decided = false;
    const decision = runGuard(authGuard).then((value) => {
      decided = true;
      return value;
    });
    await Promise.resolve();
    expect(decided).toBe(false);
    lookup.resolve(MEMBER);
    await init;
    expect(await decision).toBe(true);
  });

  it('redirects a guest with exactly one returnUrl preserving path and query', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = null;
    await store.init();
    const result = await runGuard(authGuard, '/checkout/return?token=paypal-order&PayerID=payer');
    const router = TestBed.inject(Router);
    const tree = router.parseUrl(router.serializeUrl(result as UrlTree));
    expect(tree.root.children['primary'].segments.map((part) => part.path)).toEqual(['login']);
    expect(tree.queryParams).toEqual({
      returnUrl: '/checkout/return?token=paypal-order&PayerID=payer',
    });
  });

  it('replaces an unsafe protected-navigation destination with /', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = null;
    await store.init();
    expect((await runGuard(authGuard, '//evil.test')) as UrlTree).toHaveProperty('queryParams', {
      returnUrl: '/',
    });
  });

  it('allows guests on auth screens and redirects signed-in users home', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = null;
    await store.init();
    expect(await runGuard(guestGuard)).toBe(true);
    adapter.identity = MEMBER;
    adapter.emit('signedIn');
    await store.whenResolved();
    expect(TestBed.inject(Router).serializeUrl((await runGuard(guestGuard)) as UrlTree)).toBe('/');
  });

  it('requires authentication for admin routes and redirects guests to login', async () => {
    const { adapter, store } = setupStore();
    adapter.identity = null;
    await store.init();
    const tree = (await runGuard(adminGuard, '/admin/problems')) as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(tree).split('?')[0]).toBe('/login');
    expect(tree.queryParams).toEqual({ returnUrl: '/admin/problems' });
  });

  it('refuses authenticated non-admins and allows access-token ADMIN members', async () => {
    const { adapter, store } = setupStore();
    await store.init();
    expect(TestBed.inject(Router).serializeUrl((await runGuard(adminGuard)) as UrlTree)).toBe('/');
    adapter.identity = { ...MEMBER, groups: ['ADMIN'] };
    adapter.emit('tokenRefresh');
    await store.whenResolved();
    expect(await runGuard(adminGuard)).toBe(true);
  });

  it.each([guestGuard, adminGuard])(
    'also waits for resolution on guest/admin navigation',
    async (guard) => {
      const { adapter, store } = setupStore();
      const lookup = deferred<AuthIdentity | null>();
      adapter.fetchSession.mockReturnValue(lookup.promise);
      const init = store.init();
      let decided = false;
      const result = runGuard(guard).then((value) => {
        decided = true;
        return value;
      });
      await Promise.resolve();
      expect(decided).toBe(false);
      lookup.resolve(null);
      await init;
      await result;
      expect(decided).toBe(true);
    },
  );
});

describe('returnUrl validation', () => {
  it.each([
    undefined,
    null,
    '',
    'sell',
    'https://evil.test',
    '//evil.test',
    '///evil.test',
    '/\\evil.test',
    '/sell\\next',
    '/%2fevil.test',
    '/%5cevil.test',
    '/%252fevil.test',
    '/%255cevil.test',
    '/\nevil.test',
    '/%0a/evil.test',
    '/sell?next=https://evil.test',
    '/sell?next=javascript:alert(1)',
    '/%ZZ',
    ['/', '//evil.test'],
    1,
  ])('rejects hostile or malformed destination %j', (input) => {
    expect(validateReturnUrl(input)).toBe('/');
  });

  it.each([
    '/',
    '/sell',
    '/orders/abc?tab=details',
    '/checkout/return?token=order&PayerID=payer',
    '/?q=graphics%20card',
    '/listings/abc#photos',
  ])('preserves safe relative destination %s', (input) => {
    expect(validateReturnUrl(input)).toBe(input);
  });
});

describe('PendingUsername', () => {
  let pending: PendingUsername;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    pending = TestBed.inject(PendingUsername);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('round-trips a normalized name and signup email across instances with only allowed fields', () => {
    pending.save({
      username: 'Alice_One',
      email: 'Alice@Example.Test',
      password: 'not-persisted',
      code: 'not-persisted',
      token: 'not-persisted',
    } as { username: string; email: string });
    expect(new PendingUsername().read()).toEqual({
      username: 'alice_one',
      email: 'alice@example.test',
    });
    expect(JSON.parse(localStorage.getItem('rebyte.pendingUsername')!)).toEqual({
      username: 'alice_one',
      email: 'alice@example.test',
    });
    expect(localStorage.length).toBe(1);
    expect(sessionStorage.length).toBe(0);
  });

  it.each(['ab', 'a'.repeat(31), ' alice ', 'ali.ce', 'alïce', 'USER_DEADBEEF', '', null])(
    'rejects invalid or reserved username %j like the backend',
    (username) => {
      expect(() =>
        pending.save({ username: username as string, email: 'alice@example.test' }),
      ).toThrow();
      expect(localStorage.getItem('rebyte.pendingUsername')).toBeNull();
    },
  );

  it('rejects a trailing newline as the backend full-string matcher does', () => {
    expect(() => pending.save({ username: 'alice\n', email: 'alice@example.test' })).toThrow();
    expect(localStorage.getItem('rebyte.pendingUsername')).toBeNull();
  });

  it.each(['ABC', 'a'.repeat(30), 'User_Friendly', 'user_1234567', 'user_123456789'])(
    'allows non-reserved names and backend length boundaries (%s)',
    (username) => {
      pending.save({ username, email: 'alice@example.test' });
      expect(pending.read()?.username).toBe(username.toLowerCase());
    },
  );

  it('applies once after matching first login, sending only username, then removes the key', async () => {
    pending.save({ username: 'Alice', email: 'alice@example.test' });
    const update = vi.fn().mockResolvedValue({ username: 'alice' });
    expect(await pending.applyAfterLogin('ALICE@example.test', update)).toBe('applied');
    expect(update).toHaveBeenCalledExactlyOnceWith({ username: 'alice' });
    expect(pending.read()).toBeNull();
    expect(await pending.applyAfterLogin('alice@example.test', update)).toBe('none');
    expect(update).toHaveBeenCalledTimes(1);
  });

  it.each([undefined, 'bob@example.test'])(
    'does not apply a signup belonging to another or unknown email (%s)',
    async (email) => {
      pending.save({ username: 'alice', email: 'alice@example.test' });
      const update = vi.fn();
      expect(await pending.applyAfterLogin(email, update)).toBe('none');
      expect(update).not.toHaveBeenCalled();
      expect(pending.read()?.username).toBe('alice');
    },
  );

  it.each([
    { status: 409, code: 'username-conflict' },
    { status: 409, error: { title: 'username-conflict' } },
  ])('retains the pending name on a username-conflict race (%j)', async (error) => {
    pending.save({ username: 'alice', email: 'alice@example.test' });
    expect(await pending.applyAfterLogin('alice@example.test', () => Promise.reject(error))).toBe(
      'conflict',
    );
    expect(pending.read()).toEqual({ username: 'alice', email: 'alice@example.test' });
  });

  it('retains retryable data and propagates other failures', async () => {
    pending.save({ username: 'alice', email: 'alice@example.test' });
    const error = { status: 503, code: 'unavailable' };
    await expect(
      pending.applyAfterLogin('alice@example.test', () => Promise.reject(error)),
    ).rejects.toBe(error);
    expect(pending.read()?.username).toBe('alice');
    expect(await pending.applyAfterLogin('alice@example.test', () => Promise.resolve())).toBe(
      'applied',
    );
  });

  it('allows retry after a callback throws synchronously', async () => {
    pending.save({ username: 'alice', email: 'alice@example.test' });
    await expect(
      pending.applyAfterLogin('alice@example.test', () => {
        throw new Error('Update unavailable');
      }),
    ).rejects.toThrow('Update unavailable');
    expect(await pending.applyAfterLogin('alice@example.test', () => Promise.resolve())).toBe(
      'applied',
    );
  });

  it('shares one update among concurrent bootstrap callers', async () => {
    pending.save({ username: 'alice', email: 'alice@example.test' });
    const saved = deferred<void>();
    const update = vi.fn(() => saved.promise);
    const first = pending.applyAfterLogin('alice@example.test', update);
    const second = pending.applyAfterLogin('alice@example.test', update);
    expect(update).toHaveBeenCalledTimes(1);
    saved.resolve();
    expect(await Promise.all([first, second])).toEqual(['applied', 'applied']);
    expect(pending.read()).toBeNull();
  });

  it('does not erase a newer signup saved while an older update was in flight', async () => {
    pending.save({ username: 'alice', email: 'alice@example.test' });
    const saved = deferred<void>();
    const result = pending.applyAfterLogin('alice@example.test', () => saved.promise);
    pending.save({ username: 'bob', email: 'bob@example.test' });
    saved.resolve();
    await result;
    expect(pending.read()).toEqual({ username: 'bob', email: 'bob@example.test' });
  });

  it.each([
    'not-json',
    '{}',
    '{"username":"alice","email":"alice@example.test","token":"untrusted"}',
    '{"username":"USER_DEADBEEF","email":"alice@example.test"}',
  ])('ignores and clears malformed or credential-bearing stored data (%s)', (raw) => {
    localStorage.setItem('rebyte.pendingUsername', raw);
    expect(pending.read()).toBeNull();
    expect(localStorage.getItem('rebyte.pendingUsername')).toBeNull();
  });

  it('handles unavailable browser storage when resolving a login', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Storage blocked', 'SecurityError');
    });
    expect(pending.read()).toBeNull();
    expect(await pending.applyAfterLogin('alice@example.test', vi.fn())).toBe('none');
  });
});
