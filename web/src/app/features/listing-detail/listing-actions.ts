import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import {
  ListingDetailResponse,
  OfferView,
  PublicProfileResponse,
  RejectionReason,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { ModalDialog } from '../../shared/ui/dialog';
import { MessagingApi } from '../../core/api/messaging-api';
import { OrderApi } from '../../core/api/order-api';
import { UserApi } from '../../core/api/user-api';
import { validateReturnUrl } from '../../core/auth/auth.guards';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { EurPipe } from '../../shared/ui/content';
import { Banner, ToastService } from '../../shared/ui/feedback';
import { FormField } from '../../shared/ui/forms';

const eur = new EurPipe();
const DEFAULT_MESSAGE = 'Hi, is this still available?';
const MAX_MESSAGE_LENGTH = 2000;
const OFFER_SHORTCUTS = [5, 10, 15] as const;
const REJECTION_COPY: Record<RejectionReason, string> = {
  SELLER: 'The seller declined your offer.',
  OTHER_ACCEPTED: 'The seller accepted another offer.',
  LISTING_UNAVAILABLE: 'The item is no longer available.',
  PRICE_CHANGED: 'The price changed, so your offer was closed.',
};
const CONFLICT_STATUSES = new Set([403, 404, 409, 422]);

const BUTTON_STYLES = `
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  min-height: var(--hit-min);
  padding: var(--space-sm) var(--space-md);
  border: 1.5px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  font-weight: var(--weight-semibold);
  text-decoration: none;
  cursor: pointer;
}
.btn:hover:not(:disabled) { background: var(--surface-hover); }
.btn:disabled { opacity: 0.5; cursor: default; }
.btn[aria-busy='true']::before {
  content: '';
  width: var(--space-md);
  height: var(--space-md);
  border: 2px solid currentColor;
  border-bottom-color: transparent;
  border-radius: var(--radius-round);
  animation: spin 1s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .btn[aria-busy='true']::before { animation: none; } }
.primary { background: var(--primary); border-color: var(--primary); color: var(--text-on-primary); }
.primary:hover:not(:disabled) { background: var(--primary-strong); }
.secondary { border-color: var(--ink); }
.secondary:hover:not(:disabled) { background: var(--ink); color: var(--text-on-primary); }
.danger { background: var(--danger); border-color: var(--danger); color: var(--text-on-primary); }
.danger:hover:not(:disabled) { background: var(--danger-strong); }
.danger-outline { border-color: var(--danger); color: var(--danger-strong); }
.danger-outline:hover:not(:disabled) { background: var(--danger-soft); }
.buttons { display: flex; gap: var(--space-sm); }
.buttons .btn { flex: 1; }
.error { margin: 0; color: var(--danger-strong); font-size: var(--text-body-size); }
`;

export function toAppError(error: unknown): AppError {
  const candidate = error as Partial<AppError> | null;
  return typeof candidate?.status === 'number' && typeof candidate.code === 'string'
    ? (candidate as AppError)
    : { status: 500, code: 'unknown-error', type: 'about:blank', fieldErrors: {}, extras: {} };
}

function isConflict(error: AppError): boolean {
  return CONFLICT_STATUSES.has(error.status);
}

// Offer amounts are compared in integer cents so decimal strings never pass through floating point.
export function toCents(value: string): number | null {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value);
  return match ? Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0')) : null;
}

export function fromCents(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

export function percentOff(askingCents: number, percent: number): number {
  return Math.floor((askingCents * (100 - percent) + 50) / 100);
}

@Component({
  selector: 'app-login-required-dialog',
  standalone: true,
  imports: [ModalDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [BUTTON_STYLES],
  template: `<app-modal-dialog [heading]="heading()" size="confirm" (closed)="closed.emit()">
    @if (body()) {
      <p>{{ body() }}</p>
    }
    <div class="buttons">
      <button type="button" class="btn primary" (click)="logIn()">Log in</button>
      <button type="button" class="btn" (click)="closed.emit()">Not now</button>
    </div>
  </app-modal-dialog>`,
})
export class LoginRequiredDialog {
  readonly heading = input('Log in to continue');
  readonly body = input('');
  readonly closed = output<void>();
  private readonly router = inject(Router);

  logIn(): void {
    void this.router.navigate(['/login'], {
      queryParams: { returnUrl: validateReturnUrl(this.router.url) },
    });
    this.closed.emit();
  }
}

@Component({
  selector: 'app-follow-button',
  standalone: true,
  imports: [LoginRequiredDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ${BUTTON_STYLES}
    .pill {
      border-radius: var(--radius-pill);
      padding-inline: var(--space-lg);
      white-space: nowrap;
    }
    .pill[data-following='true'] {
      background: var(--primary-soft);
      border-color: var(--primary-border);
      color: var(--primary-strong);
    }
  `,
  template: `<button
      type="button"
      class="btn pill"
      [attr.data-following]="profile().followedByMe"
      [disabled]="busy()"
      (click)="toggle()"
    >
      {{ profile().followedByMe ? 'Following' : 'Follow' }}
    </button>
    @if (loginOpen()) {
      <app-login-required-dialog (closed)="loginOpen.set(false)" />
    }`,
})
export class FollowButton {
  readonly profile = input.required<PublicProfileResponse>();
  readonly profileChange = output<PublicProfileResponse>();
  readonly busy = signal(false);
  readonly loginOpen = signal(false);

  private readonly auth = inject(AuthStore);
  private readonly users = inject(UserApi);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  toggle(): void {
    if (!this.auth.isAuthenticated()) {
      this.loginOpen.set(true);
      return;
    }
    if (this.busy()) return;
    const before = this.profile();
    const following = !before.followedByMe;
    const delta = following ? 1 : -1;
    this.busy.set(true);
    this.profileChange.emit({
      ...before,
      followedByMe: following,
      followerCount: Math.max(0, before.followerCount + delta),
    });
    const request = following ? this.users.follow(before.sub) : this.users.unfollow(before.sub);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.busy.set(false),
      error: (error: unknown) => {
        this.busy.set(false);
        this.profileChange.emit(before);
        this.toasts.show({ text: errorCopy(toAppError(error)), kind: 'error' });
      },
    });
  }
}

@Component({
  selector: 'app-message-seller-dialog',
  standalone: true,
  imports: [ModalDialog, FormField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [BUTTON_STYLES],
  template: `<app-modal-dialog [heading]="heading()" (closed)="closed.emit()">
    <form novalidate (submit)="$event.preventDefault(); send()">
      <app-form-field
        id="message-seller-text"
        label="Message"
        kind="textarea"
        [rows]="4"
        [value]="text()"
        [error]="error()"
        (valueChange)="text.set($event); error.set('')"
      />
      <div class="buttons">
        <button type="submit" class="btn primary" [disabled]="busy()" [attr.aria-busy]="busy()">
          Send message
        </button>
        <button type="button" class="btn" (click)="closed.emit()">Cancel</button>
      </div>
    </form>
  </app-modal-dialog>`,
})
export class MessageSellerDialog {
  readonly listingId = input.required<string>();
  readonly seller = input<string | null>(null);
  readonly closed = output<void>();
  readonly conflict = output<void>();
  readonly heading = computed(() =>
    this.seller() ? `Message @${this.seller()}` : 'Message seller',
  );
  readonly text = signal(DEFAULT_MESSAGE);
  readonly error = signal('');
  readonly busy = signal(false);

  private readonly messaging = inject(MessagingApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  send(): void {
    if (this.busy()) return;
    const text = this.text().trim();
    if (!text) {
      this.error.set('Write a message first.');
      return;
    }
    if (Array.from(text).length > MAX_MESSAGE_LENGTH) {
      this.error.set(`Messages can be up to ${MAX_MESSAGE_LENGTH} characters.`);
      return;
    }
    this.busy.set(true);
    this.messaging
      .startConversation({ listingId: this.listingId(), text, imageKeys: [] })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (conversation) => {
          this.busy.set(false);
          void this.router.navigate(['/conversations', conversation.id]);
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          const error = toAppError(failure);
          const existing = error.extras['conversationId'];
          if (error.code === 'conversation-exists' && typeof existing === 'string') {
            void this.router.navigate(['/conversations', existing], { state: { draft: text } });
            return;
          }
          this.error.set(errorCopy(error));
          if (isConflict(error)) this.conflict.emit();
        },
      });
  }
}

@Component({
  selector: 'app-make-offer-dialog',
  standalone: true,
  imports: [ModalDialog, EurPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ${BUTTON_STYLES}
    .amount {
      display: flex;
      align-items: center;
      gap: var(--space-sm);
      padding: var(--space-sm) var(--space-md);
      border: 1.5px solid var(--primary-border);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }
    .amount span {
      color: var(--text-muted);
      font: var(--weight-bold) var(--text-display) var(--font-mono);
    }
    .amount input {
      flex: 1;
      min-width: 0;
      border: 0;
      background: none;
      color: var(--ink);
      font: var(--weight-bold) var(--text-display) var(--font-mono);
    }
    .amount input:focus-visible {
      outline: none;
      box-shadow: none;
    }
    .amount:focus-within {
      box-shadow: 0 0 0 4px var(--focus-halo);
    }
    .chips {
      display: flex;
      gap: var(--space-sm);
    }
    .chips .btn {
      flex: 1;
      flex-direction: column;
      gap: 0;
      padding: var(--space-sm) var(--space-xs);
    }
    .chips .btn[aria-pressed='true'] {
      background: var(--primary-soft);
      border-color: var(--primary);
    }
    .price {
      font: var(--weight-semibold) var(--text-body-size) var(--font-mono);
    }
    .label {
      font-size: var(--text-caption);
      color: var(--text-muted);
    }
    .hint {
      margin: 0;
      color: var(--text-muted);
    }
    .hint.warn {
      color: var(--warning);
    }
    .note {
      margin: 0;
      color: var(--text-muted);
      font-size: var(--text-caption);
      text-align: center;
    }
    form {
      display: grid;
      gap: var(--space-md);
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
    }
  `,
  template: `<app-modal-dialog
    heading="Make an offer"
    [sub]="title() + ' · listed at ' + eurText(asking())"
    (closed)="closed.emit()"
  >
    <form novalidate (submit)="$event.preventDefault(); send()">
      <label class="visually-hidden" for="offer-amount">Your offer in euros</label>
      <div class="amount">
        <span aria-hidden="true">€</span>
        <input
          id="offer-amount"
          type="text"
          inputmode="decimal"
          autocomplete="off"
          placeholder="0.00"
          aria-describedby="offer-hint"
          [value]="amount()"
          (input)="setAmount($any($event.target).value)"
        />
      </div>
      <div class="chips" role="group" aria-label="Offer shortcuts">
        @for (chip of chips(); track chip.percent) {
          <button
            type="button"
            class="btn"
            [disabled]="!chip.usable"
            [attr.aria-pressed]="chip.value === amount()"
            (click)="setAmount(chip.value)"
          >
            <span class="price">{{ chip.value | eur }}</span>
            <span class="label">{{ chip.percent }}% off</span>
          </button>
        }
      </div>
      <p id="offer-hint" class="hint" [class.warn]="state() === 'high'">{{ hint() }}</p>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      <button
        type="submit"
        class="btn primary"
        [disabled]="state() !== 'valid' || busy()"
        [attr.aria-busy]="busy()"
      >
        Send offer
      </button>
      <p class="note">
        The seller can accept or decline. If they accept, the item is held for you and you pay your
        offer plus shipping.
      </p>
    </form>
  </app-modal-dialog>`,
})
export class MakeOfferDialog {
  readonly listingId = input.required<string>();
  readonly title = input.required<string>();
  readonly asking = input.required<string>();
  readonly created = output<OfferView>();
  readonly closed = output<void>();
  readonly conflict = output<void>();
  readonly amount = signal('');
  readonly error = signal('');
  readonly busy = signal(false);

  readonly askingCents = computed(() => toCents(this.asking()) ?? 0);
  readonly cents = computed(() => toCents(this.amount()));
  readonly state = computed<'empty' | 'format' | 'zero' | 'high' | 'valid'>(() => {
    if (!this.amount()) return 'empty';
    const cents = this.cents();
    if (cents === null) return 'format';
    if (cents <= 0) return 'zero';
    return cents >= this.askingCents() ? 'high' : 'valid';
  });
  readonly chips = computed(() =>
    OFFER_SHORTCUTS.map((percent) => {
      const cents = percentOff(this.askingCents(), percent);
      return {
        percent,
        value: fromCents(cents),
        usable: cents > 0 && cents < this.askingCents(),
      };
    }),
  );
  readonly hint = computed(() => {
    switch (this.state()) {
      case 'empty':
        return 'Enter your offer';
      case 'format':
        return 'Use an amount with at most 2 decimals.';
      case 'zero':
        return 'Enter an amount greater than zero.';
      case 'high':
        return "That's the asking price. Use Buy now instead.";
      default:
        return `You'd save ${eur.transform(fromCents(this.askingCents() - (this.cents() ?? 0)))}. Shipping is added at checkout.`;
    }
  });

  private readonly orders = inject(OrderApi);
  private readonly destroyRef = inject(DestroyRef);

  eurText(value: string): string {
    return eur.transform(value);
  }

  setAmount(value: string): void {
    this.amount.set(value.trim());
    this.error.set('');
  }

  send(): void {
    const cents = this.cents();
    if (this.state() !== 'valid' || cents === null || this.busy()) return;
    this.busy.set(true);
    this.orders
      .createOffer({ listingId: this.listingId(), amount: fromCents(cents) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (offer) => {
          this.busy.set(false);
          this.created.emit(offer);
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          const error = toAppError(failure);
          this.error.set(
            error.code === 'validation-failed'
              ? (error.fieldErrors['amount'] ?? errorCopy(error))
              : errorCopy(error),
          );
          if (isConflict(error)) this.conflict.emit();
        },
      });
  }
}

@Component({
  selector: 'app-remove-listing-dialog',
  standalone: true,
  imports: [ModalDialog, FormField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [BUTTON_STYLES],
  template: `<app-modal-dialog
    [heading]="needsReason() ? 'Remove this listing (moderation)?' : 'Remove this listing?'"
    size="confirm"
    (closed)="closed.emit()"
  >
    <form novalidate (submit)="$event.preventDefault(); remove()">
      <p>“{{ title() }}” will be taken down from the marketplace. This can't be undone.</p>
      @if (needsReason()) {
        <app-form-field
          id="remove-reason"
          label="Reason for removal"
          kind="textarea"
          [rows]="3"
          [required]="true"
          [value]="reason()"
          [error]="reasonError()"
          (valueChange)="reason.set($event); reasonError.set('')"
        />
      }
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      <div class="buttons">
        <button type="button" class="btn" (click)="closed.emit()">Keep it</button>
        <button type="submit" class="btn danger" [disabled]="busy()" [attr.aria-busy]="busy()">
          Remove
        </button>
      </div>
    </form>
  </app-modal-dialog>`,
})
export class RemoveListingDialog {
  readonly listingId = input.required<string>();
  readonly title = input.required<string>();
  readonly needsReason = input(false);
  readonly removed = output<void>();
  readonly closed = output<void>();
  readonly conflict = output<void>();
  readonly reason = signal('');
  readonly reasonError = signal('');
  readonly error = signal('');
  readonly busy = signal(false);

  private readonly catalog = inject(CatalogApi);
  private readonly destroyRef = inject(DestroyRef);

  remove(): void {
    if (this.busy()) return;
    const reason = this.reason().trim();
    if (this.needsReason() && !reason) {
      this.reasonError.set('Enter a reason for the removal.');
      return;
    }
    this.busy.set(true);
    const request = this.needsReason()
      ? this.catalog.deleteListing(this.listingId(), reason)
      : this.catalog.deleteListing(this.listingId());
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.removed.emit();
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        const error = toAppError(failure);
        this.error.set(errorCopy(error));
        if (isConflict(error)) this.conflict.emit();
      },
    });
  }
}

type Gate = 'continue' | 'favourites';

@Component({
  selector: 'app-listing-actions',
  standalone: true,
  imports: [
    RouterLink,
    EurPipe,
    Banner,
    LoginRequiredDialog,
    MessageSellerDialog,
    MakeOfferDialog,
    RemoveListingDialog,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ${BUTTON_STYLES}
    :host {
      display: grid;
      gap: var(--space-md);
    }
    .bar-price {
      display: none;
    }
    .row {
      display: flex;
      gap: var(--space-sm);
    }
    .buy,
    .message {
      flex: 1;
      min-height: 48px;
    }
    .message svg {
      display: none;
    }
    .heart {
      width: 56px;
      min-height: 48px;
      padding: 0;
    }
    .heart svg {
      width: 22px;
      height: 22px;
    }
    .heart[aria-pressed='true'] {
      color: var(--danger);
    }
    .offer {
      border: 1.5px dashed var(--primary-border-btn);
      background: var(--primary-tint);
      color: var(--primary);
    }
    .offer:hover:not(:disabled) {
      background: var(--primary-tint-hover);
      border-style: solid;
    }
    .reason,
    .caption {
      margin: 0;
      color: var(--text-muted);
      font-size: var(--text-caption);
    }
    .panel {
      display: grid;
      gap: var(--space-sm);
      padding: var(--space-md);
      border: 1px solid var(--primary-border);
      border-radius: var(--radius-lg);
      background: var(--primary-tint);
    }
    .panel .row .btn {
      flex: 1;
    }
    .software-owner {
      padding: 0;
      border: 0;
      border-radius: 0;
      background: transparent;
    }
    .software-owner .row {
      flex-wrap: wrap;
    }
    .software-owner .row .btn {
      flex: 1 1 auto;
    }
    .remove-quiet {
      justify-self: end;
      min-height: var(--hit-min);
      padding: var(--space-sm);
      border: 0;
      border-radius: var(--radius-md);
      background: transparent;
      color: var(--danger-strong);
      font: inherit;
      cursor: pointer;
    }
    .remove-quiet:hover {
      background: var(--danger-soft);
    }
    .offer-state {
      display: grid;
      gap: var(--space-sm);
    }
    .inline-confirm {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-sm);
    }
    .inline-confirm span {
      flex: 1 0 100%;
      font-weight: var(--weight-semibold);
    }
    @media (max-width: 900px) {
      .bar {
        position: fixed;
        left: 0;
        right: 0;
        bottom: 0;
        z-index: var(--z-header);
        display: flex;
        align-items: center;
        gap: var(--space-md);
        padding: var(--space-sm) var(--gutter) calc(var(--space-sm) + env(safe-area-inset-bottom));
        border-top: 1px solid var(--border);
        background: var(--surface);
      }
      .bar-price {
        display: block;
        font: var(--weight-bold) var(--text-heading) var(--font-mono);
      }
      .bar .row {
        flex: 1;
      }
    }
    @media (max-width: 640px) {
      .bar.with-tabs {
        bottom: calc(var(--tabbar-height) + env(safe-area-inset-bottom));
      }
      .message {
        flex: 0 0 48px;
        padding: 0;
      }
      .message svg {
        display: block;
        width: 22px;
        height: 22px;
      }
      .message .label {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
      }
      .heart {
        width: 48px;
      }
    }
  `,
  template: `
    @let l = listing();
    @if (isOwn()) {
      <div class="panel" [class.software-owner]="l.type === 'SOFTWARE'">
        @if (l.status === 'ACTIVE') {
          <div class="row">
            <a class="btn primary" [routerLink]="['/listings', l.id, 'edit']">Edit listing</a>
            @if (l.type === 'SOFTWARE') {
              <a class="btn" [routerLink]="['/listings', l.id, 'releases']">Manage releases</a>
            } @else {
              <button type="button" class="btn danger-outline" (click)="removeOpen.set(true)">
                Remove listing
              </button>
            }
          </div>
          @if (l.type === 'SOFTWARE') {
            <button type="button" class="remove-quiet" (click)="removeOpen.set(true)">
              Remove listing
            </button>
          }
        } @else if (l.status === 'SOLD') {
          <p class="caption">Sold listings can't be edited or removed.</p>
          @if (l.type === 'SOFTWARE') {
            <a class="btn" [routerLink]="['/listings', l.id, 'releases']">Manage releases</a>
          }
        }
      </div>
    } @else {
      <div class="bar" [class.with-tabs]="auth.isAuthenticated()">
        <span class="bar-price">{{ l.price | eur }}</span>
        <div class="row">
          @if (l.status === 'SOLD') {
            <button type="button" class="btn primary buy" disabled aria-describedby="buy-reason">
              Buy now
            </button>
          } @else if (auth.isAuthenticated()) {
            <a class="btn primary buy" [routerLink]="['/checkout', l.id]">Buy now</a>
          } @else {
            <button type="button" class="btn primary buy" (click)="gate.set('continue')">
              Buy now
            </button>
          }
          <button type="button" class="btn secondary message" (click)="openMessage()">
            <svg
              aria-hidden="true"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.75"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z" />
            </svg>
            <span class="label">Message seller</span>
          </button>
          <button
            type="button"
            class="btn heart"
            [attr.aria-pressed]="favourite()"
            [attr.aria-label]="favourite() ? 'Remove from favourites' : 'Add to favourites'"
            (click)="toggleFavourite()"
          >
            <svg
              aria-hidden="true"
              viewBox="0 0 24 24"
              stroke="currentColor"
              stroke-width="1.75"
              stroke-linecap="round"
              stroke-linejoin="round"
              [attr.fill]="favourite() ? 'currentColor' : 'none'"
            >
              <path
                d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78L12 21.23l8.84-8.84a5.5 5.5 0 0 0 0-7.78Z"
              />
            </svg>
          </button>
        </div>
      </div>
      @if (l.status === 'SOLD') {
        <p id="buy-reason" class="reason">This item is no longer available.</p>
      }

      @if (offer(); as current) {
        <div class="offer-state">
          @switch (current.status) {
            @case ('PENDING') {
              <app-banner
                [text]="'Your offer of ' + money(current.amount) + ' is waiting for the seller'"
                actionLabel="Withdraw offer"
                (action)="confirmWithdraw.set(true)"
              />
              @if (confirmWithdraw()) {
                <div class="inline-confirm" role="group" aria-label="Withdraw offer?">
                  <span>Withdraw offer?</span>
                  <button type="button" class="btn danger" (click)="withdraw(current)">
                    Withdraw
                  </button>
                  <button type="button" class="btn" (click)="confirmWithdraw.set(false)">
                    Keep
                  </button>
                </div>
              }
            }
            @case ('ACCEPTED') {
              <app-banner [text]="acceptedText(current)" />
              @if (countdown(current); as left) {
                <p class="caption" role="timer">{{ left }}</p>
              }
              <a
                class="btn primary"
                [routerLink]="['/checkout', l.id]"
                [queryParams]="{ offer: current.id }"
                >Checkout at {{ current.amount | eur }}</a
              >
            }
            @case ('REJECTED') {
              <app-banner kind="warning" [text]="rejectionText(current)" />
            }
          }
        </div>
      }
      @if (canOffer() && !offerBlocking()) {
        <button type="button" class="btn offer" (click)="openOffer()">Make an offer</button>
      }
      @if (auth.isAdmin()) {
        <div class="panel">
          <strong>Moderation</strong>
          @if (l.status === 'ACTIVE' || l.status === 'SOLD') {
            <button type="button" class="btn danger-outline" (click)="removeOpen.set(true)">
              Remove listing (moderation)
            </button>
          }
        </div>
      }
    }

    @if (gate(); as kind) {
      <app-login-required-dialog
        [heading]="kind === 'favourites' ? 'Log in to save favourites' : 'Log in to continue'"
        [body]="kind === 'favourites' ? 'Favourites sync across your devices.' : ''"
        (closed)="gate.set(null)"
      />
    }
    @if (messageOpen()) {
      <app-message-seller-dialog
        [listingId]="l.id"
        [seller]="l.seller.username"
        (closed)="messageOpen.set(false)"
        (conflict)="changed.emit()"
      />
    }
    @if (offerOpen()) {
      <app-make-offer-dialog
        [listingId]="l.id"
        [title]="l.title"
        [asking]="l.price"
        (created)="offerCreated($event)"
        (closed)="offerOpen.set(false)"
        (conflict)="offerConflict()"
      />
    }
    @if (removeOpen()) {
      <app-remove-listing-dialog
        [listingId]="l.id"
        [title]="l.title"
        [needsReason]="auth.isAdmin()"
        (removed)="removed()"
        (closed)="removeOpen.set(false)"
        (conflict)="changed.emit()"
      />
    }
  `,
})
export class ListingActions {
  readonly listing = input.required<ListingDetailResponse>();
  readonly changed = output<void>();

  readonly auth = inject(AuthStore);
  private readonly catalog = inject(CatalogApi);
  private readonly orders = inject(OrderApi);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  readonly gate = signal<Gate | null>(null);
  readonly messageOpen = signal(false);
  readonly offerOpen = signal(false);
  readonly removeOpen = signal(false);
  readonly favourite = signal(false);
  readonly confirmWithdraw = signal(false);
  readonly offers = signal<readonly OfferView[]>([]);
  readonly now = signal(Date.now());

  readonly isOwn = computed(() => {
    const sub = this.auth.identity()?.sub;
    return !!sub && sub === this.listing().sellerSub;
  });
  readonly canOffer = computed(
    () => this.listing().type === 'HARDWARE' && this.listing().status === 'ACTIVE',
  );
  readonly offer = computed(() => {
    if (!this.canOffer() || !this.auth.isAuthenticated()) return null;
    const items = this.offers();
    const pending = items.find((item) => item.status === 'PENDING');
    if (pending) return pending;
    const accepted = items.find(
      (item) =>
        item.status === 'ACCEPTED' &&
        (item.expiresAt === null || Date.parse(item.expiresAt) > this.now()),
    );
    if (accepted) return accepted;
    return items[0]?.status === 'REJECTED' ? items[0] : null;
  });
  readonly offerBlocking = computed(
    () => this.offer()?.status === 'PENDING' || this.offer()?.status === 'ACCEPTED',
  );

  private offersRequest = new Subscription();
  private favouritesRequest = new Subscription();
  private favouriteBusy = false;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.offersRequest.unsubscribe();
      this.favouritesRequest.unsubscribe();
    });
    effect(() => {
      const listingId = this.listing().id;
      const watching = this.auth.isAuthenticated() && !this.isOwn();
      untracked(() => {
        if (watching) this.loadFavourite(listingId);
        else this.favourite.set(false);
      });
    });
    effect(() => {
      const listingId = this.listing().id;
      const watching = this.auth.isAuthenticated() && !this.isOwn() && this.canOffer();
      untracked(() => {
        if (watching) this.loadOffers(listingId);
        else this.offers.set([]);
      });
    });
    effect((onCleanup) => {
      const accepted = this.offer()?.status === 'ACCEPTED' && this.offer()?.expiresAt;
      if (!accepted) return;
      const timer = setInterval(() => this.now.set(Date.now()), 1000);
      onCleanup(() => clearInterval(timer));
    });
  }

  money(value: string): string {
    return eur.transform(value);
  }

  openMessage(): void {
    if (this.auth.isAuthenticated()) this.messageOpen.set(true);
    else this.gate.set('continue');
  }

  openOffer(): void {
    if (this.auth.isAuthenticated()) this.offerOpen.set(true);
    else this.gate.set('continue');
  }

  toggleFavourite(): void {
    if (!this.auth.isAuthenticated()) {
      this.gate.set('favourites');
      return;
    }
    if (this.favouriteBusy) return;
    const adding = !this.favourite();
    const id = this.listing().id;
    this.favourite.set(adding);
    this.favouriteBusy = true;
    const request: Observable<unknown> = adding
      ? this.catalog.addFavourite(id)
      : this.catalog.removeFavourite(id);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => (this.favouriteBusy = false),
      error: (failure: unknown) => {
        this.favouriteBusy = false;
        const error = toAppError(failure);
        // Removing a favourite that is already gone leaves the wanted state in place.
        if (!adding && error.status === 404) return;
        this.favourite.set(!adding);
        this.toasts.show({
          text:
            error.code === 'listing-not-available'
              ? 'This item is no longer available.'
              : errorCopy(error),
          kind: 'error',
        });
        if (isConflict(error)) this.changed.emit();
      },
    });
  }

  offerCreated(offer: OfferView): void {
    this.offerOpen.set(false);
    this.offers.update((items) => [offer, ...items.filter((item) => item.id !== offer.id)]);
    this.toasts.show({ text: 'Offer sent', kind: 'success' });
  }

  offerConflict(): void {
    this.loadOffers(this.listing().id);
    this.changed.emit();
  }

  withdraw(offer: OfferView): void {
    this.orders
      .withdrawOffer(offer.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.confirmWithdraw.set(false);
          this.offers.update((items) =>
            items.map((item) => (item.id === updated.id ? updated : item)),
          );
          this.toasts.show({ text: 'Offer withdrawn', kind: 'success' });
        },
        error: (failure: unknown) => {
          this.confirmWithdraw.set(false);
          this.toasts.show({ text: errorCopy(toAppError(failure)), kind: 'error' });
          this.loadOffers(this.listing().id);
        },
      });
  }

  removed(): void {
    this.removeOpen.set(false);
    this.toasts.show({ text: 'Listing removed', kind: 'success' });
    if (this.auth.isAdmin()) this.changed.emit();
    else void this.router.navigate(['/me'], { queryParams: { tab: 'listings' } });
  }

  acceptedText(offer: OfferView): string {
    const seller = this.listing().seller.username;
    const who = seller ? `@${seller}` : 'The seller';
    const amount = this.money(offer.amount);
    return offer.expiresAt
      ? `${who} accepted your offer of ${amount}. Pay before ${this.deadline(offer.expiresAt)} to keep the item.`
      : `${who} accepted your offer of ${amount}. Check out to keep the item.`;
  }

  rejectionText(offer: OfferView): string {
    return (
      (offer.rejectionReason ? REJECTION_COPY[offer.rejectionReason] : undefined) ??
      REJECTION_COPY.SELLER
    );
  }

  countdown(offer: OfferView): string {
    if (!offer.expiresAt) return '';
    const seconds = Math.max(0, Math.floor((Date.parse(offer.expiresAt) - this.now()) / 1000));
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const rest = String(seconds % 60).padStart(2, '0');
    return hours > 0
      ? `${hours} h ${String(minutes).padStart(2, '0')} min left`
      : `${minutes} min ${rest} s left`;
  }

  private deadline(instant: string): string {
    const date = new Date(instant);
    const time = date.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
    return date.toDateString() === new Date(this.now()).toDateString()
      ? time
      : `${date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}, ${time}`;
  }

  // The API has no per-listing favourite flag, so the caller's favourites are scanned page by page.
  private loadFavourite(listingId: string): void {
    this.favouritesRequest.unsubscribe();
    this.favouritesRequest = new Subscription();
    this.favourite.set(false);
    const scan = (page: number): void => {
      this.favouritesRequest.add(
        this.catalog.favourites({ page, size: 100 }).subscribe({
          next: (result) => {
            if (result.favourites.some((entry) => entry.listingId === listingId)) {
              this.favourite.set(true);
            } else if (page + 1 < result.totalPages && page < 19) {
              scan(page + 1);
            }
          },
          error: () => undefined,
        }),
      );
    };
    scan(0);
  }

  private loadOffers(listingId: string): void {
    this.offersRequest.unsubscribe();
    this.offersRequest = this.orders.sentOffers({ listingId }).subscribe({
      next: (page) => this.offers.set(page?.items ?? []),
      error: () => this.offers.set([]),
    });
  }
}
