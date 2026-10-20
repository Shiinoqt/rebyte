import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  EMPTY,
  Subject,
  Subscription,
  catchError,
  firstValueFrom,
  map,
  of,
  switchMap,
  timer,
} from 'rxjs';
import { UsernameAvailabilityResponse } from '../../core/api/api-models';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter, CodeDeliveryDetails } from '../../core/auth/auth.adapter';
import { validateReturnUrl } from '../../core/auth/auth.guards';
import { AuthStore } from '../../core/auth/auth.store';
import { PendingUsername, PendingUsernameResult } from '../../core/auth/pending-username';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { CurrentProfile } from '../../core/shell/current-profile';
import { ToastService } from '../../shared/ui/feedback';

export type AuthRouteStep = 'login' | 'signup' | 'confirm' | 'forgot';
export type AuthStep = AuthRouteStep | 'choose-username';
export type AuthResult = { ok: true } | { ok: false; message: string };

export const RESEND_COOLDOWN_SECONDS = 30;

const GENERIC_COPY = 'Something went wrong. Try again in a minute.';
const GENERATED_HANDLE = /^user_[0-9a-f]{8}$/;
const USERNAME_CHARS = /^[a-z0-9_-]+$/;

export const USERNAME_DEBOUNCE_MS = 400;
export const USERNAME_MIN_LENGTH = 3;
export const USERNAME_MAX_LENGTH = 30;

const AUTH_COPY = {
  credentials: 'Email or password is incorrect.',
  unconfirmed: 'Confirm your email first. We sent you a code.',
  duplicate: 'An account with this email already exists. Log in instead.',
  wrongCode: 'That code is not right. Check the email and try again.',
  expiredCode: 'That code has expired. Send a new one.',
  tooMany: 'Too many attempts. Wait a few minutes and try again.',
  weakPassword:
    "That password doesn't meet the password rules. Use a longer one with a mix of letters, numbers and symbols.",
  offline: 'You appear to be offline.',
  usernameTaken: 'That username is taken.',
} as const;

/** Maps an Amplify error to UI-SPEC copy by its name; the raw message is never shown. */
export function authErrorCopy(error: unknown): string {
  const name =
    typeof error === 'object' && error !== null ? (error as { name?: unknown }).name : '';
  switch (name) {
    case 'NotAuthorizedException':
    case 'UserNotFoundException':
      return AUTH_COPY.credentials;
    case 'UserNotConfirmedException':
      return AUTH_COPY.unconfirmed;
    case 'UsernameExistsException':
      return AUTH_COPY.duplicate;
    case 'CodeMismatchException':
      return AUTH_COPY.wrongCode;
    case 'ExpiredCodeException':
      return AUTH_COPY.expiredCode;
    case 'LimitExceededException':
    case 'TooManyRequestsException':
    case 'TooManyFailedAttemptsException':
      return AUTH_COPY.tooMany;
    case 'InvalidPasswordException':
      return AUTH_COPY.weakPassword;
    case 'NetworkError':
      return AUTH_COPY.offline;
    default:
      return GENERIC_COPY;
  }
}

function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'string'
  );
}

function apiErrorCopy(error: unknown): string {
  return isAppError(error) ? errorCopy(error) : GENERIC_COPY;
}

function isUsernameConflict(error: unknown): boolean {
  return isAppError(error) && error.status === 409 && error.code === 'username-conflict';
}

function failure(message: string): AuthResult {
  return { ok: false, message };
}

@Injectable({ providedIn: 'root' })
export class AuthFlowService {
  private readonly adapter = inject(AuthAdapter);
  private readonly store = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly users = inject(UserApi);
  private readonly pending = inject(PendingUsername);
  private readonly profile = inject(CurrentProfile);
  private readonly toasts = inject(ToastService);
  private cooldownTimer: ReturnType<typeof setInterval> | undefined;

  readonly email = signal('');
  readonly forcedStep = signal<AuthStep | null>(null);
  readonly takenName = signal('');
  readonly resendCooldown = signal(0);
  readonly codeDestination = signal('');

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopCooldown());
  }

  createUsernameChecker(): UsernameChecker {
    return new UsernameChecker(this.users);
  }

  knownEmail(): string {
    return this.email() || this.pending.read()?.email || '';
  }

  /** Drops state that must not outlive the auth page; the carried email stays for the next step. */
  leave(): void {
    this.forcedStep.set(null);
    this.takenName.set('');
  }

  async login(input: { email: string; password: string; returnUrl: string }): Promise<AuthResult> {
    const email = input.email.trim();
    let result;
    try {
      result = await this.adapter.signIn({ email, password: input.password });
    } catch (error) {
      if (this.isAlreadyAuthenticated(error)) {
        return this.enter(email, input.returnUrl);
      }
      return failure(authErrorCopy(error));
    }
    if (result.isSignedIn) {
      return this.enter(email, input.returnUrl);
    }
    switch (result.nextStep.signInStep) {
      case 'CONFIRM_SIGN_UP':
        this.email.set(email);
        await this.goTo('/confirm', input.returnUrl);
        return { ok: true };
      case 'RESET_PASSWORD':
        this.email.set(email);
        await this.goTo('/forgot', input.returnUrl);
        return { ok: true };
      default:
        return failure(GENERIC_COPY);
    }
  }

  async signUp(input: {
    email: string;
    username: string;
    password: string;
    returnUrl: string;
  }): Promise<AuthResult> {
    const email = input.email.trim();
    let result;
    try {
      result = await this.adapter.signUp({ email, password: input.password });
    } catch (error) {
      return failure(authErrorCopy(error));
    }
    try {
      this.pending.save({ username: input.username, email });
    } catch {
      return failure(GENERIC_COPY);
    }
    this.email.set(email);
    this.setDestination(result.nextStep.codeDeliveryDetails);
    if (result.nextStep.signUpStep === 'CONFIRM_SIGN_UP') {
      this.startCooldown();
      await this.goTo('/confirm', input.returnUrl);
    } else {
      await this.goTo('/login', input.returnUrl);
    }
    return { ok: true };
  }

  async confirm(input: { email: string; code: string; returnUrl: string }): Promise<AuthResult> {
    const email = input.email.trim();
    let result;
    try {
      result = await this.adapter.confirmSignUp({ email, code: input.code.trim() });
    } catch (error) {
      return failure(authErrorCopy(error));
    }
    this.stopCooldown();
    if (result.nextStep.signUpStep === 'COMPLETE_AUTO_SIGN_IN') {
      try {
        const signedIn = await this.adapter.autoSignIn();
        if (signedIn.isSignedIn) {
          return this.enter(email, input.returnUrl);
        }
      } catch {
        // Auto sign-in state lives in memory and is lost after a reload; the login form is the fallback.
      }
    }
    this.toasts.show({ kind: 'success', text: 'Email confirmed. Log in to continue.' });
    await this.goTo('/login', input.returnUrl);
    return { ok: true };
  }

  async resendConfirmationCode(email: string): Promise<AuthResult> {
    if (this.resendCooldown() > 0) {
      return { ok: true };
    }
    try {
      await this.adapter.resendSignUpCode(email.trim());
    } catch (error) {
      return failure(authErrorCopy(error));
    }
    this.startCooldown();
    return { ok: true };
  }

  async requestReset(email: string): Promise<AuthResult> {
    const trimmed = email.trim();
    try {
      await this.adapter.resetPassword(trimmed);
    } catch (error) {
      // An unknown address looks the same as a known one, so the form cannot be used to probe accounts.
      if ((error as { name?: unknown } | null)?.name !== 'UserNotFoundException') {
        return failure(authErrorCopy(error));
      }
    }
    this.email.set(trimmed);
    this.startCooldown();
    return { ok: true };
  }

  async resendResetCode(): Promise<AuthResult> {
    if (this.resendCooldown() > 0) {
      return { ok: true };
    }
    return this.requestReset(this.email());
  }

  async completeReset(input: {
    email: string;
    code: string;
    newPassword: string;
    returnUrl: string;
  }): Promise<AuthResult> {
    try {
      await this.adapter.confirmResetPassword({
        email: input.email.trim(),
        code: input.code.trim(),
        newPassword: input.newPassword,
      });
    } catch (error) {
      return failure(authErrorCopy(error));
    }
    this.stopCooldown();
    this.toasts.show({ kind: 'success', text: 'Password updated. Log in with your new password.' });
    await this.goTo('/login', input.returnUrl);
    return { ok: true };
  }

  /** Replaces the username after the pending one lost the race (D-28). */
  async saveChosenUsername(username: string, returnUrl: string): Promise<AuthResult> {
    try {
      await this.putUsername(username);
    } catch (error) {
      return failure(isUsernameConflict(error) ? AUTH_COPY.usernameTaken : apiErrorCopy(error));
    }
    this.pending.clear();
    this.profile.reload();
    this.forcedStep.set(null);
    await this.router.navigateByUrl(validateReturnUrl(returnUrl));
    return { ok: true };
  }

  async logOut(): Promise<void> {
    this.forcedStep.set(null);
    this.takenName.set('');
    try {
      await this.adapter.signOut();
    } catch {
      this.store.clearSession();
    }
    await this.router.navigate(['/login']);
  }

  private isAlreadyAuthenticated(error: unknown): boolean {
    return (error as { name?: unknown } | null)?.name === 'UserAlreadyAuthenticatedException';
  }

  /** Runs once a Cognito step reports a signed-in user: apply the chosen name, then leave the auth routes. */
  private async enter(email: string, returnUrl: string): Promise<AuthResult> {
    await this.store.whenResolved();
    if (!this.store.isAuthenticated()) {
      return failure(GENERIC_COPY);
    }
    const outcome = await this.applyPendingUsername(this.store.identity()?.email ?? email);
    if (outcome === 'conflict') {
      this.takenName.set(this.pending.read()?.username ?? '');
      this.forcedStep.set('choose-username');
      return { ok: true };
    }
    if (outcome === 'applied') {
      this.profile.reload();
    }
    await this.router.navigateByUrl(validateReturnUrl(returnUrl));
    return { ok: true };
  }

  private async applyPendingUsername(email: string): Promise<PendingUsernameResult> {
    try {
      return await this.pending.applyAfterLogin(email, async ({ username }) => {
        const me = await firstValueFrom(this.users.getMe());
        // PUT /users/me replaces location and bio, and a chosen name is never overwritten by a stale signup.
        if (!GENERATED_HANDLE.test(me.username)) {
          return;
        }
        await firstValueFrom(
          this.users.updateProfile({ username, location: me.location, bio: me.bio }),
        );
      });
    } catch {
      // The pending entry stays and the profile hub offers the name choice as a fallback.
      return 'none';
    }
  }

  private async putUsername(username: string): Promise<void> {
    const me = await firstValueFrom(this.users.getMe());
    await firstValueFrom(
      this.users.updateProfile({ username, location: me.location, bio: me.bio }),
    );
  }

  private async goTo(path: string, returnUrl: string): Promise<void> {
    const safe = validateReturnUrl(returnUrl);
    await this.router.navigate([path], { queryParams: safe === '/' ? {} : { returnUrl: safe } });
  }

  private setDestination(details: CodeDeliveryDetails | undefined): void {
    this.codeDestination.set(details?.destination ?? '');
  }

  private startCooldown(): void {
    this.stopCooldown();
    this.resendCooldown.set(RESEND_COOLDOWN_SECONDS);
    this.cooldownTimer = setInterval(() => {
      const left = this.resendCooldown() - 1;
      this.resendCooldown.set(Math.max(left, 0));
      if (left <= 0) {
        clearInterval(this.cooldownTimer);
        this.cooldownTimer = undefined;
      }
    }, 1000);
  }

  private stopCooldown(): void {
    clearInterval(this.cooldownTimer);
    this.cooldownTimer = undefined;
    this.resendCooldown.set(0);
  }
}

export type UsernameStatus = 'idle' | 'checking' | 'available' | 'taken' | 'invalid' | 'reserved';

function localUsernameStatus(name: string): UsernameStatus | null {
  if (name.length > USERNAME_MAX_LENGTH || (name !== '' && !USERNAME_CHARS.test(name))) {
    return 'invalid';
  }
  if (GENERATED_HANDLE.test(name)) {
    return 'reserved';
  }
  return name.length < USERNAME_MIN_LENGTH ? 'idle' : null;
}

function remoteUsernameStatus(response: UsernameAvailabilityResponse): UsernameStatus {
  if (response.available) {
    return 'available';
  }
  switch (response.reason) {
    case 'invalid-username':
      return 'invalid';
    case 'reserved-handle':
      return 'reserved';
    default:
      return 'taken';
  }
}

/**
 * Live availability of the username being typed. Each keystroke supersedes the previous one:
 * switchMap drops the pending debounce and unsubscribes a request already in flight, so a stale
 * answer can never overwrite the state of the current name. The answer is advisory; the unique
 * index behind PUT /users/me stays the authority.
 */
export class UsernameChecker {
  private readonly input = new Subject<string | null>();
  private readonly subscription: Subscription;
  private readonly currentStatus = signal<UsernameStatus>('idle');
  private readonly currentName = signal('');

  readonly status = this.currentStatus.asReadonly();
  /** The lowercased candidate, which is what the server stores. */
  readonly name = this.currentName.asReadonly();

  constructor(private readonly users: UserApi) {
    this.subscription = this.input
      .pipe(
        switchMap((name) =>
          name === null
            ? EMPTY
            : timer(USERNAME_DEBOUNCE_MS).pipe(
                switchMap(() => this.users.usernameAvailable(name)),
                map(remoteUsernameStatus),
                // A rate limit or network failure shows nothing; the next keystroke checks again.
                catchError(() => of<UsernameStatus>('idle')),
              ),
        ),
      )
      .subscribe((status) => this.currentStatus.set(status));
  }

  update(raw: string): void {
    const name = raw.toLowerCase();
    this.currentName.set(name);
    const local = localUsernameStatus(name);
    if (local) {
      this.currentStatus.set(local);
      this.input.next(null);
    } else {
      this.currentStatus.set('checking');
      this.input.next(name);
    }
  }

  /** Settles the current name now (submit must not wait out the debounce) and returns the outcome. */
  async resolveNow(): Promise<UsernameStatus> {
    const name = this.currentName();
    const settled = this.currentStatus();
    if (localUsernameStatus(name) !== null || (settled !== 'idle' && settled !== 'checking')) {
      return settled;
    }
    this.input.next(null);
    let status: UsernameStatus;
    try {
      status = remoteUsernameStatus(await firstValueFrom(this.users.usernameAvailable(name)));
    } catch {
      status = 'idle';
    }
    if (this.currentName() === name) {
      this.currentStatus.set(status);
    }
    return this.currentStatus();
  }

  reset(): void {
    this.input.next(null);
    this.currentName.set('');
    this.currentStatus.set('idle');
  }

  destroy(): void {
    this.subscription.unsubscribe();
  }
}
