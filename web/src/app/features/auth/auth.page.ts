import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { validateReturnUrl } from '../../core/auth/auth.guards';
import { BusyButton } from '../../shared/ui/feedback';
import { FormField } from '../../shared/ui/forms';
import { AuthFlowService, AuthRouteStep, AuthStep, AuthResult } from './auth-flow.service';
import { AuthShowcase } from './auth-showcase';

type FieldName = 'username' | 'email' | 'password' | 'code' | 'newPassword';
type ForgotStage = 'email' | 'code';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_PATTERN = /^\d{6}$/;
const USERNAME_HINT = '3 to 30 characters: lowercase letters, digits, _ or -.';
const USERNAME_RULE = `Use ${USERNAME_HINT}`;
const PASSWORD_HINT =
  'At least 12 characters, including uppercase and lowercase letters, a number and a symbol. No spaces.';
const PASSWORD_SYMBOLS = `^$*.[]{}()?"!@#%&/\\,><':;|_~\u0060=+-`;
const USERNAME_COPY = {
  taken: 'That username is taken.',
  reserved: "That username isn't available.",
  invalid: USERNAME_RULE,
} as const;
const ROUTE_STEPS: readonly AuthRouteStep[] = ['login', 'signup', 'confirm', 'forgot'];

const TITLES: Readonly<Record<AuthStep, string>> = {
  login: 'Log in',
  signup: 'Create your account',
  confirm: 'Check your email',
  forgot: 'Reset your password',
  'choose-username': 'That name was just taken',
};

const SUBMIT_LABELS: Readonly<Record<AuthStep, string>> = {
  login: 'Log in',
  signup: 'Create account',
  confirm: 'Confirm email',
  forgot: 'Send reset code',
  'choose-username': 'Save username',
};

function routeStep(value: unknown): AuthRouteStep {
  return ROUTE_STEPS.find((step) => step === value) ?? 'login';
}

@Component({
  selector: 'app-auth-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AuthShowcase, BusyButton, FormField, NgTemplateOutlet, RouterLink],
  styleUrl: './auth.page.css',
  template: `<div class="layout">
    <div class="panel">
      <a class="auth-wordmark" routerLink="/" aria-label="rebyte home">re<span>byte</span></a>
      <p class="panel-title">Pre-loved tech, from people like you.</p>
      <p class="panel-text">
        Buy and sell second-hand hardware, and software made by indie developers.
      </p>
      <app-auth-showcase />
    </div>
    <div class="form-col">
      <div class="card">
        <h1 #heading id="auth-heading" tabindex="-1">{{ title() }}</h1>
        @if (step() === 'login' || step() === 'signup') {
          <nav class="mode-links" aria-label="Account">
            <a
              routerLink="/login"
              [queryParams]="returnQuery()"
              [attr.aria-current]="step() === 'login' ? 'page' : null"
              (click)="preserveEmail()"
              >Log in</a
            >
            <a
              routerLink="/signup"
              [queryParams]="returnQuery()"
              [attr.aria-current]="step() === 'signup' ? 'page' : null"
              (click)="preserveEmail()"
              >Sign up</a
            >
          </nav>
        }
        @if (step() === 'choose-username') {
          <p class="lead">
            Someone else claimed <strong>&#64;{{ flow.takenName() }}</strong> while you were
            confirming your email. Pick another one.
          </p>
        }
        @if (step() === 'confirm') {
          <p class="lead">
            We sent a 6-digit code to
            <strong>{{ email() || 'your email' }}</strong
            >.
          </p>
        }
        @if (step() === 'forgot' && stage() === 'code') {
          <p class="lead">
            If <strong>{{ email() }}</strong> has an account, we sent a code to it.
          </p>
        }
        <form novalidate aria-labelledby="auth-heading" (submit)="submit($event)">
          @if (step() === 'signup' || step() === 'choose-username') {
            <div class="username">
              <label for="auth-username">Username</label>
              <div class="username-control">
                <span aria-hidden="true">&#64;</span>
                <input
                  id="auth-username"
                  type="text"
                  required
                  aria-required="true"
                  autocomplete="off"
                  autocapitalize="none"
                  spellcheck="false"
                  placeholder="your_name"
                  [value]="username()"
                  [attr.aria-invalid]="usernameView().error"
                  [attr.aria-describedby]="
                    usernameView().error
                      ? 'auth-username-status'
                      : 'auth-username-hint auth-username-status'
                  "
                  (input)="onUsernameInput($event)"
                  (blur)="onUsernameBlur()"
                />
              </div>
              @if (!usernameView().error) {
                <p class="hint" id="auth-username-hint">{{ usernameHint }}</p>
              }
              <p class="username-status" id="auth-username-status" aria-live="polite">
                @if (usernameView().text) {
                  <span
                    class="status"
                    [class.error]="usernameView().error"
                    [attr.data-state]="usernameView().state"
                  >
                    @if (!usernameView().error) {
                      <span class="status-icon" aria-hidden="true"></span>
                    }
                    {{ usernameView().text }}
                  </span>
                }
              </p>
            </div>
          }
          @if (showEmail()) {
            <app-form-field
              [id]="'auth-email'"
              label="Email"
              type="email"
              [required]="true"
              autocomplete="email"
              placeholder="name@example.com"
              [value]="email()"
              [error]="errors().email ?? ''"
              (valueChange)="email.set($event)"
              (blurred)="validate('email')"
            />
          }
          @if (step() === 'login' || step() === 'signup') {
            <app-form-field
              class="password"
              [id]="'auth-password'"
              label="Password"
              [required]="true"
              [hint]="step() === 'signup' && errors().password !== passwordHint ? passwordHint : ''"
              [type]="showPassword() ? 'text' : 'password'"
              [autocomplete]="step() === 'signup' ? 'new-password' : 'current-password'"
              [value]="password()"
              [error]="errors().password ?? ''"
              (valueChange)="password.set($event)"
              (blurred)="validate('password')"
            >
              <ng-container *ngTemplateOutlet="reveal" ngProjectAs="[fieldSuffix]" />
            </app-form-field>
          }
          @if (showCode()) {
            <div class="code">
              <label for="auth-code">Confirmation code</label>
              <input
                id="auth-code"
                type="text"
                required
                aria-required="true"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                placeholder="123456"
                [value]="code()"
                [attr.aria-invalid]="!!errors().code"
                [attr.aria-describedby]="errors().code ? 'auth-code-error' : null"
                (input)="code.set(codeValue($event))"
                (blur)="validate('code')"
              />
              @if (errors().code) {
                <p class="field-error" id="auth-code-error" role="alert">{{ errors().code }}</p>
              }
            </div>
          }
          @if (step() === 'forgot' && stage() === 'code') {
            <app-form-field
              class="password"
              [id]="'auth-new-password'"
              label="New password"
              [required]="true"
              [hint]="errors().newPassword === passwordHint ? '' : passwordHint"
              [type]="showPassword() ? 'text' : 'password'"
              autocomplete="new-password"
              [value]="newPassword()"
              [error]="errors().newPassword ?? ''"
              (valueChange)="newPassword.set($event)"
              (blurred)="validate('newPassword')"
            >
              <ng-container *ngTemplateOutlet="reveal" ngProjectAs="[fieldSuffix]" />
            </app-form-field>
          }
          @if (formError()) {
            <p class="form-error" role="alert">{{ formError() }}</p>
          }
          <app-busy-button
            type="submit"
            variant="primary"
            [label]="submitLabel()"
            [busy]="busy()"
          />
        </form>
        @if (showResend()) {
          <button
            type="button"
            class="link"
            data-resend
            [disabled]="flow.resendCooldown() > 0 || busy()"
            (click)="resend()"
          >
            @if (flow.resendCooldown() > 0) {
              Send a new code in {{ flow.resendCooldown() }}s
            } @else {
              Send a new code
            }
          </button>
        }
        @if (step() === 'login') {
          <a class="link" routerLink="/forgot" [queryParams]="returnQuery()">Forgot password?</a>
        }
        @if (step() === 'choose-username') {
          <button type="button" class="link" data-logout (click)="flow.logOut()">Log out</button>
        }
        @if (step() === 'confirm' || step() === 'forgot') {
          <a class="link" routerLink="/login" [queryParams]="returnQuery()">Back to log in</a>
        }
      </div>
    </div>
  </div>
  <ng-template #reveal>
    <button
      type="button"
      class="reveal"
      fieldSuffix
      [attr.aria-label]="showPassword() ? 'Hide password' : 'Show password'"
      [attr.aria-pressed]="showPassword()"
      (click)="showPassword.set(!showPassword())"
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
        <circle cx="12" cy="12" r="3" />
        @if (showPassword()) {
          <path d="m3 3 18 18" />
        }
      </svg>
    </button>
  </ng-template>`,
})
export class AuthPage {
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly flow = inject(AuthFlowService);

  private readonly fromRoute = routeStep(this.route.snapshot.data['authStep']);
  private readonly returnUrl = validateReturnUrl(
    this.route.snapshot.queryParamMap.get('returnUrl'),
  );
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
  private headingReady = false;

  protected readonly checker = this.flow.createUsernameChecker();
  protected readonly usernameHint = USERNAME_HINT;
  protected readonly passwordHint = PASSWORD_HINT;
  protected readonly step = computed<AuthStep>(() => this.flow.forcedStep() ?? this.fromRoute);
  protected readonly stage = signal<ForgotStage>('email');
  protected readonly title = computed(() => TITLES[this.step()]);
  protected readonly email = signal(
    this.fromRoute === 'confirm' ? this.flow.knownEmail() : this.flow.email(),
  );
  private readonly emailKnownOnArrival = this.email() !== '';
  protected readonly username = signal('');
  private readonly usernameTouched = signal(false);
  protected readonly password = signal('');
  protected readonly newPassword = signal('');
  protected readonly code = signal('');
  protected readonly showPassword = signal(false);
  protected readonly errors = signal<Partial<Record<FieldName, string>>>({});
  protected readonly formError = signal('');
  protected readonly busy = signal(false);

  protected readonly usernameView = computed<{
    state: string;
    text: string;
    error: boolean;
  }>(() => {
    const status = this.checker.status();
    switch (status) {
      case 'checking':
        return { state: status, text: 'Checking...', error: false };
      case 'available':
        return { state: status, text: `@${this.checker.name()} is available.`, error: false };
      case 'taken':
      case 'reserved':
      case 'invalid':
        return { state: status, text: USERNAME_COPY[status], error: true };
      default:
        return this.usernameTouched() && this.checker.name().length < 3
          ? { state: 'invalid', text: USERNAME_RULE, error: true }
          : { state: status, text: '', error: false };
    }
  });
  protected readonly returnQuery = computed(() =>
    this.returnUrl === '/' ? {} : { returnUrl: this.returnUrl },
  );
  protected readonly showEmail = computed(() => {
    switch (this.step()) {
      case 'login':
      case 'signup':
        return true;
      case 'confirm':
        return !this.emailKnownOnArrival;
      case 'forgot':
        return this.stage() === 'email';
      default:
        return false;
    }
  });
  protected readonly showCode = computed(
    () => this.step() === 'confirm' || (this.step() === 'forgot' && this.stage() === 'code'),
  );
  protected readonly showResend = this.showCode;
  protected readonly submitLabel = computed(() =>
    this.step() === 'forgot' && this.stage() === 'code'
      ? 'Set new password'
      : SUBMIT_LABELS[this.step()],
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.checker.destroy();
      this.flow.leave();
    });
    effect(() => {
      const step = this.step();
      this.stage();
      if (!this.headingReady) {
        this.headingReady = true;
        return;
      }
      if (step === 'choose-username') {
        untracked(() => this.startUsernameChoice());
      }
      afterNextRender(() => this.heading()?.nativeElement.focus({ preventScroll: true }), {
        injector: this.injector,
      });
    });
  }

  protected codeValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected onUsernameInput(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.username.set(raw);
    this.checker.update(raw);
  }

  protected onUsernameBlur(): void {
    this.username.set(this.username().toLowerCase());
    this.usernameTouched.set(true);
  }

  protected preserveEmail(): void {
    this.flow.email.set(this.email());
  }

  protected validate(name: FieldName): void {
    const message = this.messageFor(name);
    this.errors.update((current) => ({ ...current, [name]: message }));
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) {
      return;
    }
    this.formError.set('');
    if (!this.validateStep()) {
      this.focusFirstInvalid();
      return;
    }
    this.busy.set(true);
    let result: AuthResult;
    try {
      result = await this.run();
    } finally {
      this.busy.set(false);
    }
    if (!result.ok) {
      this.formError.set(result.message);
      if (this.checker.status() !== 'available' && this.needsUsername()) {
        this.focusFirstInvalid();
      }
    }
  }

  protected async resend(): Promise<void> {
    this.formError.set('');
    const result =
      this.step() === 'confirm'
        ? await this.flow.resendConfirmationCode(this.email())
        : await this.flow.resendResetCode();
    if (!result.ok) {
      this.formError.set(result.message);
    }
  }

  private async run(): Promise<AuthResult> {
    const returnUrl = this.returnUrl;
    switch (this.step()) {
      case 'login':
        return this.flow.login({ email: this.email(), password: this.password(), returnUrl });
      case 'signup': {
        const unavailable = await this.unavailableUsername();
        if (unavailable) {
          return unavailable;
        }
        return this.flow.signUp({
          email: this.email(),
          username: this.checker.name(),
          password: this.password(),
          returnUrl,
        });
      }
      case 'confirm':
        return this.flow.confirm({ email: this.email(), code: this.code(), returnUrl });
      case 'forgot':
        if (this.stage() === 'email') {
          const result = await this.flow.requestReset(this.email());
          if (result.ok) {
            this.stage.set('code');
          }
          return result;
        }
        return this.flow.completeReset({
          email: this.email(),
          code: this.code(),
          newPassword: this.newPassword(),
          returnUrl,
        });
      case 'choose-username': {
        const unavailable = await this.unavailableUsername();
        return unavailable ?? this.flow.saveChosenUsername(this.checker.name(), returnUrl);
      }
    }
  }

  private needsUsername(): boolean {
    return this.step() === 'signup' || this.step() === 'choose-username';
  }

  /** The live check is advisory but submit still waits for a definite "available" before going on. */
  private async unavailableUsername(): Promise<AuthResult | null> {
    const status = await this.checker.resolveNow();
    if (status === 'available') {
      return null;
    }
    this.usernameTouched.set(true);
    const message =
      status === 'taken' || status === 'reserved' || status === 'invalid'
        ? USERNAME_COPY[status]
        : "We couldn't check that username. Try again in a moment.";
    return { ok: false, message };
  }

  private startUsernameChoice(): void {
    this.username.set('');
    this.usernameTouched.set(false);
    this.checker.reset();
    this.errors.set({});
    this.formError.set('');
  }

  private fieldsForStep(): FieldName[] {
    switch (this.step()) {
      case 'login':
        return ['email', 'password'];
      case 'signup':
        return ['username', 'email', 'password'];
      case 'choose-username':
        return ['username'];
      case 'confirm':
        return this.showEmail() ? ['email', 'code'] : ['code'];
      case 'forgot':
        return this.stage() === 'email' ? ['email'] : ['code', 'newPassword'];
      default:
        return [];
    }
  }

  private messageFor(name: FieldName): string {
    switch (name) {
      case 'username':
        return this.usernameMessage();
      case 'email':
        return EMAIL_PATTERN.test(this.email().trim())
          ? ''
          : 'Enter an email address like name@example.com.';
      case 'password':
        if (!this.password()) return 'Enter your password.';
        return this.step() === 'signup' && !passwordMeetsPolicy(this.password())
          ? PASSWORD_HINT
          : '';
      case 'newPassword':
        if (!this.newPassword()) return 'Enter your password.';
        return passwordMeetsPolicy(this.newPassword()) ? '' : PASSWORD_HINT;
      case 'code':
        return CODE_PATTERN.test(this.code().trim())
          ? ''
          : 'Enter the 6-digit code from your email.';
    }
  }

  private usernameMessage(): string {
    this.usernameTouched.set(true);
    const status = this.checker.status();
    if (status === 'taken' || status === 'reserved' || status === 'invalid') {
      return USERNAME_COPY[status];
    }
    return status === 'idle' && this.checker.name().length < 3 ? USERNAME_RULE : '';
  }

  private validateStep(): boolean {
    const next: Partial<Record<FieldName, string>> = {};
    let valid = true;
    for (const name of this.fieldsForStep()) {
      const message = this.messageFor(name);
      next[name] = message;
      valid &&= message === '';
    }
    this.errors.set(next);
    return valid;
  }

  private focusFirstInvalid(): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(),
      { injector: this.injector },
    );
  }
}

function passwordMeetsPolicy(password: string): boolean {
  return (
    [...password].length >= 12 &&
    !/\s/.test(password) &&
    /[A-Z]/.test(password) &&
    /[a-z]/.test(password) &&
    /\d/.test(password) &&
    [...password].some((character) => PASSWORD_SYMBOLS.includes(character))
  );
}
