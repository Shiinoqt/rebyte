import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ListingDetailResponse,
  OfferView,
  OrderStatus,
  OrderView,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AppConfigService } from '../../core/config/app-config';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { EurPipe, MoneyRow, MoneyRows, StatusChip } from '../../shared/ui/content';
import { Banner, BusyButton, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { FieldOption, FormField } from '../../shared/ui/forms';
import {
  addMoney,
  AddressErrors,
  AddressForm,
  approvalTarget,
  buildCheckoutRequest,
  CHECKOUT_FALLBACK_DELAY_MS,
  CHECKOUT_NAVIGATOR,
  CheckoutOutcome,
  CheckoutService,
  checkoutTotal,
  EU_COUNTRIES,
  validateAddress,
} from './checkout.service';
import { PendingCheckout, PendingCheckoutStore } from './pending-checkout.store';

type PageStatus =
  | 'loading'
  | 'ready'
  | 'error'
  | 'unavailable'
  | 'no-paypal'
  | 'offer-unusable'
  | 'order'
  | 'redirecting';

const UNKNOWN_ERROR: AppError = {
  status: 500,
  code: 'unknown-error',
  type: 'about:blank',
  fieldErrors: {},
  extras: {},
};
// The generic 5xx copy would hide these two, which tell the buyer whether they were charged.
const CHECKOUT_COPY: Readonly<Record<string, string>> = {
  'payment-unavailable':
    "PayPal is not responding. You haven't been charged. Try again in a minute.",
  'checkout-outcome-unknown':
    "We couldn't confirm your order. Check your orders before trying again.",
  'idempotency-key-conflict':
    'Something changed since your last attempt. Press Pay with PayPal to try again.',
  'offer-hold-unverified':
    "We couldn't confirm that the item is still held for you. Try again in a minute.",
};
const OFFER_REJECTIONS = new Set([
  'offer-not-checkoutable',
  'offer-listing-mismatch',
  'offer-price-changed',
]);
const VALIDATION_FIELDS: Readonly<Record<string, [keyof AddressForm, string]>> = {
  recipient: ['recipient', 'Check the recipient name.'],
  street: ['street', 'Check the street address.'],
  city: ['city', 'Check the city.'],
  postalCode: ['postalCode', 'Check the postal code.'],
  country: ['country', 'Check the country.'],
};
const FIELD_ORDER: readonly (keyof AddressForm)[] = [
  'recipient',
  'street',
  'postalCode',
  'city',
  'country',
];
const FIELD_IDS: Readonly<Record<keyof AddressForm, string>> = {
  recipient: 'checkout-recipient',
  street: 'checkout-street',
  postalCode: 'checkout-postal-code',
  city: 'checkout-city',
  country: 'checkout-country',
};
const COUNTRY_OPTIONS: readonly FieldOption[] = EU_COUNTRIES.map((country) => ({
  value: country.code,
  label: country.name,
}));

const EUR = new EurPipe();

function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'string'
  );
}

function deadline(instant: string): string {
  const date = new Date(instant);
  const time = date.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  return date.toDateString() === new Date().toDateString()
    ? time
    : `${date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}, ${time}`;
}

interface Handoff {
  orderId: string;
  kind: 'resume' | 'blocked' | 'status';
  heading: string;
  text: string;
  approvalUrl: string | null;
}

function statusHandoff(orderId: string, status: OrderStatus): Handoff {
  const base = { orderId, kind: 'status' as const, approvalUrl: null };
  switch (status) {
    case 'CANCELLED':
      return {
        ...base,
        heading: 'This order was cancelled.',
        text: 'Go back to the listing to start a new checkout.',
      };
    case 'REFUNDED':
      return { ...base, heading: 'This order was refunded.', text: 'Open the order for details.' };
    default:
      return {
        ...base,
        heading: 'This order is already paid.',
        text: 'Open the order to follow it.',
      };
  }
}

@Component({
  selector: 'app-checkout-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    Banner,
    BusyButton,
    DatePipe,
    ErrorState,
    EurPipe,
    FormField,
    MoneyRows,
    RouterLink,
    Skeleton,
    StatusChip,
  ],
  styleUrl: './checkout.page.css',
  template: `
    @switch (status()) {
      @case ('loading') {
        <h1 tabindex="-1" class="plain">Checkout</h1>
        <app-skeleton label="Loading checkout" shape="line" />
      }
      @case ('error') {
        <h1 tabindex="-1" class="plain">Checkout</h1>
        <app-error-state (retry)="load()" />
      }
      @case ('unavailable') {
        <h1 tabindex="-1" class="plain">Checkout</h1>
        <div class="unavailable" role="alert">
          <h2>This item is no longer available.</h2>
          <a routerLink="/">Back to browsing</a>
        </div>
      }
      @case ('no-paypal') {
        <h1 tabindex="-1" class="plain">Checkout</h1>
        <div class="unavailable" role="alert">
          <h2>This listing does not accept PayPal.</h2>
          <a [routerLink]="['/listings', listingId]">Back to the listing</a>
        </div>
      }
      @case ('offer-unusable') {
        <h1 tabindex="-1" class="plain">Checkout</h1>
        <div class="unavailable" role="alert">
          <h2>{{ offerExpired() ? 'This offer has expired' : "This offer can't be used" }}</h2>
          <a [routerLink]="['/listings', listingId]">Back to the listing</a>
        </div>
      }
      @case ('order') {
        <h1 tabindex="-1" class="plain">Checkout</h1>
        @if (handoff(); as hand) {
          <section class="unavailable" [attr.role]="hand.kind === 'blocked' ? 'alert' : 'status'">
            <h2>{{ hand.heading }}</h2>
            <p>{{ hand.text }}</p>
            @if (hand.approvalUrl; as url) {
              <app-busy-button label="Continue to PayPal" (pressed)="resume(url)" />
            }
            <a [routerLink]="['/orders', hand.orderId]">View order</a>
            <a [routerLink]="['/listings', listingId]">Back to the listing</a>
          </section>
        }
      }
      @default {
        <form class="card" novalidate (submit)="submit($event)">
          <header class="head">
            <h1 tabindex="-1">Checkout</h1>
            <p class="kind">{{ hardware() ? 'Hardware order' : 'Software order' }}</p>
          </header>
          <div class="form-pane">
            @if (demoCheckoutEnabled) {
              <p class="demo-checkout-note">
                Payments use the PayPal sandbox; no real money moves. Choose "Pay with a card"
                on PayPal and use a test card from the
                <a
                  href="https://developer.paypal.com/credit-card-number-generator"
                  target="_blank"
                  rel="noopener noreferrer"
                >PayPal sandbox card generator</a>.
              </p>
            }
            @if (attention(); as count) {
              <app-banner
                kind="error"
                [text]="
                  count === 1 ? 'Fix 1 field to continue' : 'Fix ' + count + ' fields to continue'
                "
              />
            }
            @if (serverError(); as problem) {
              <app-banner
                kind="error"
                [text]="problem"
                [actionLabel]="ordersLink() ? 'Your orders' : ''"
                (action)="openOrders()"
              />
            }
            @if (offer(); as accepted) {
              <app-banner
                kind="info"
                [text]="
                  'Your offer of ' +
                  (accepted.amount | eur) +
                  ' was accepted.' +
                  (accepted.expiresAt
                    ? ' This item is held for you until ' + held(accepted.expiresAt) + '.'
                    : '')
                "
              />
              @if (countdown(); as left) {
                <p class="countdown" role="timer">{{ left }}</p>
              }
            }

            @if (status() === 'redirecting') {
              <div class="redirecting" role="status">
                <span class="spinner" aria-hidden="true"></span>
                <p>Redirecting to PayPal...</p>
              </div>
              @if (fallback()) {
                <a class="fallback" [href]="approvalUrl()">Continue to PayPal</a>
              }
            } @else {
              @if (hardware()) {
                <fieldset class="address" [disabled]="submitting()">
                  <legend class="micro">Shipping address</legend>
                  @if (previous(); as old) {
                    <div class="previous">
                      <p>
                        Last shipped on {{ old.createdAt | date: 'd MMM y' }} to
                        {{ old.shippingAddress!.recipient }}, {{ old.shippingAddress!.street }},
                        {{ old.shippingAddress!.city }} {{ old.shippingAddress!.postalCode }},
                        {{ old.shippingAddress!.country }}
                      </p>
                      <button type="button" class="link" (click)="copyPrevious(old)">
                        Use this address
                      </button>
                    </div>
                    @if (copied()) {
                      <p class="hint">
                        Values copied into this order. Editing them here does not change other
                        orders.
                      </p>
                    }
                  }
                  <app-form-field
                    [id]="'checkout-recipient'"
                    label="Recipient"
                    autocomplete="name"
                    [required]="true"
                    [value]="recipient()"
                    [error]="errors().recipient ?? ''"
                    (valueChange)="edit('recipient', $event)"
                    (blurred)="validateField('recipient')"
                  />
                  <app-form-field
                    [id]="'checkout-street'"
                    label="Street address"
                    autocomplete="street-address"
                    [required]="true"
                    [value]="street()"
                    [error]="errors().street ?? ''"
                    (valueChange)="edit('street', $event)"
                    (blurred)="validateField('street')"
                  />
                  <div class="postal-row">
                    <app-form-field
                      [id]="'checkout-postal-code'"
                      label="Postal code"
                      autocomplete="postal-code"
                      [required]="true"
                      [hint]="postalHint()"
                      [value]="postalCode()"
                      [error]="errors().postalCode ?? ''"
                      (valueChange)="edit('postalCode', $event)"
                      (blurred)="validateField('postalCode')"
                    />
                    <app-form-field
                      [id]="'checkout-city'"
                      label="City"
                      autocomplete="address-level2"
                      [required]="true"
                      [value]="city()"
                      [error]="errors().city ?? ''"
                      (valueChange)="edit('city', $event)"
                      (blurred)="validateField('city')"
                    />
                  </div>
                  <app-form-field
                    [id]="'checkout-country'"
                    label="Country"
                    kind="select"
                    placeholder="Select a country"
                    [required]="true"
                    [options]="countries"
                    [value]="country()"
                    [error]="errors().country ?? ''"
                    (valueChange)="edit('country', $event)"
                    (blurred)="validateField('country')"
                  />
                  <p class="facts">
                    Ships from {{ listing()?.shippingLocation }}. Shipping
                    {{ listing()?.shippingCost ?? '0.00' | eur }} (set by the seller).
                  </p>
                </fieldset>
              } @else {
                <div class="callout">
                  <h2>Instant digital delivery</h2>
                  <p>
                    No shipping. Your licence key appears on your order page as soon as payment
                    clears.
                  </p>
                </div>
              }

              <section class="payment" aria-labelledby="checkout-payment">
                <h2 id="checkout-payment" class="micro">Payment</h2>
                <div class="payment-row">
                  <strong>PayPal</strong>
                  <span>You will approve the payment on PayPal and come back here.</span>
                </div>
              </section>
              <a class="cancel in-form" [routerLink]="['/listings', listingId]">Cancel</a>
            }
          </div>

          <div class="rail">
            <div class="rail-body">
              <aside class="summary" aria-label="Order summary">
                <h2 class="micro">Order summary</h2>
                <div class="item">
                  @if (listing()?.photos?.[0]; as photo) {
                    <img class="thumb" [src]="photo" alt="" />
                  } @else {
                    <span class="thumb placeholder" aria-hidden="true"></span>
                  }
                  <div class="item-text">
                    <p class="item-title">{{ listing()?.title }}</p>
                    @if (listing()?.seller?.username; as seller) {
                      <p class="seller">&#64;{{ seller }}</p>
                    }
                  </div>
                </div>
                @if (offer()) {
                  <app-status-chip text="Offer accepted" tone="accent" />
                }
                <app-money-rows [rows]="rows()" />
              </aside>
              <div class="pay-bar">
                <p class="bar-total">
                  <span>Total</span> <strong>{{ total() | eur }}</strong>
                </p>
                <app-busy-button
                  type="submit"
                  [label]="'Pay with PayPal ' + (total() | eur)"
                  [busy]="submitting() || status() === 'redirecting'"
                />
                <a class="cancel" [routerLink]="['/listings', listingId]">Cancel</a>
              </div>
            </div>
          </div>
        </form>
      }
    }
  `,
})
export class CheckoutPage {
  readonly demoCheckoutEnabled = inject(AppConfigService).config.demoCheckoutEnabled;
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthStore);
  private readonly catalog = inject(CatalogApi);
  private readonly orders = inject(OrderApi);
  private readonly checkout = inject(CheckoutService);
  private readonly pending = inject(PendingCheckoutStore);
  private readonly navigate = inject(CHECKOUT_NAVIGATOR);
  private readonly fallbackDelay = inject(CHECKOUT_FALLBACK_DELAY_MS);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  readonly listingId = this.route.snapshot.paramMap.get('listingId') ?? '';
  private readonly offerId = this.route.snapshot.queryParamMap.get('offer');
  private fallbackTimer: ReturnType<typeof setTimeout> | undefined;
  readonly countries = COUNTRY_OPTIONS;

  readonly status = signal<PageStatus>('loading');
  readonly listing = signal<ListingDetailResponse | null>(null);
  readonly offer = signal<OfferView | null>(null);
  readonly previous = signal<OrderView | null>(null);
  readonly copied = signal(false);
  readonly now = signal(Date.now());

  readonly recipient = signal('');
  readonly street = signal('');
  readonly postalCode = signal('');
  readonly city = signal('');
  readonly country = signal('');
  readonly errors = signal<AddressErrors>({});
  private readonly attentionShown = signal(false);
  readonly attention = computed(() =>
    this.attentionShown() ? FIELD_ORDER.filter((field) => this.errors()[field]).length : 0,
  );
  readonly serverError = signal('');
  readonly submitting = signal(false);
  readonly ordersLink = signal(false);
  readonly handoff = signal<Handoff | null>(null);
  readonly approvalUrl = signal('');
  readonly fallback = signal(false);

  readonly hardware = computed(() => this.listing()?.type !== 'SOFTWARE');
  readonly offerExpired = computed(() => {
    const expiresAt = this.offer()?.expiresAt;
    return !!expiresAt && Date.parse(expiresAt) <= this.now();
  });
  readonly countdown = computed(() => {
    const expiresAt = this.offer()?.expiresAt;
    if (!expiresAt) return '';
    const seconds = Math.max(0, Math.floor((Date.parse(expiresAt) - this.now()) / 1000));
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const rest = String(seconds % 60).padStart(2, '0');
    return hours > 0
      ? `${hours} h ${String(minutes).padStart(2, '0')} min left`
      : `${minutes} min ${rest} s left`;
  });
  readonly total = computed(() => {
    const listing = this.listing();
    return listing ? checkoutTotal({ listing, offer: this.offer() }) : '0.00';
  });
  readonly rows = computed<MoneyRow[]>(() => {
    const listing = this.listing();
    if (!listing) return [];
    const rows: MoneyRow[] = [{ label: 'Item', value: this.offer()?.amount ?? listing.price }];
    if (listing.type === 'HARDWARE') {
      rows.push({ label: 'Shipping', value: listing.shippingCost ?? '0.00' });
    }
    rows.push({ label: 'Total', value: this.total(), total: true });
    return rows;
  });
  readonly postalHint = computed(() => {
    const rule = EU_COUNTRIES.find((candidate) => candidate.code === this.country());
    return rule ? `Example: ${rule.example}` : '';
  });

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroyRef.onDestroy(() => {
      clearInterval(timer);
      clearTimeout(this.fallbackTimer);
    });
    void this.load();
  }

  held(instant: string): string {
    return deadline(instant);
  }

  async load(): Promise<void> {
    this.status.set('loading');
    try {
      const listing = await firstValueFrom(this.catalog.listing(this.listingId));
      const offer = this.offerId ? await this.fetchOffer(this.offerId) : null;
      this.listing.set(listing);
      this.offer.set(offer);
      if (listing.sellerSub === this.auth.identity()?.sub || listing.status === 'REMOVED') {
        await this.leave();
        return;
      }
      // Before the offer and SOLD checks: this buyer's own order holds the listing and consumes the offer.
      if (await this.restore()) return;
      if (this.offerId && !this.usable(offer)) {
        this.status.set('offer-unusable');
        return;
      }
      if (listing.status === 'SOLD' && !offer) {
        await this.leave();
        return;
      }
      if (!listing.paymentMethods.includes('PAYPAL')) {
        this.status.set('no-paypal');
        return;
      }
      this.status.set('ready');
      if (listing.type === 'HARDWARE') void this.loadPrevious();
    } catch (error) {
      this.status.set(isAppError(error) && error.status === 404 ? 'unavailable' : 'error');
    }
  }

  edit(field: keyof AddressForm, value: string): void {
    const setters: Record<keyof AddressForm, (next: string) => void> = {
      recipient: (next) => this.recipient.set(next),
      street: (next) => this.street.set(next),
      postalCode: (next) => this.postalCode.set(next),
      city: (next) => this.city.set(next),
      country: (next) => this.country.set(next),
    };
    setters[field](value);
    if (field === 'country' && this.postalCode()) this.validateField('postalCode');
    if (this.errors()[field]) this.validateField(field);
  }

  validateField(field: keyof AddressForm): void {
    const message = validateAddress(this.form())[field];
    this.errors.update((errors) => {
      const next = { ...errors };
      if (message) next[field] = message;
      else delete next[field];
      return next;
    });
  }

  copyPrevious(order: OrderView): void {
    const address = order.shippingAddress;
    if (!address) return;
    this.recipient.set(address.recipient);
    this.street.set(address.street);
    this.postalCode.set(address.postalCode);
    this.city.set(address.city);
    this.country.set(address.country.toUpperCase());
    this.errors.set({});
    this.attentionShown.set(false);
    this.copied.set(true);
  }

  async submit(event?: Event): Promise<void> {
    event?.preventDefault();
    const listing = this.listing();
    if (!listing || this.submitting() || this.status() !== 'ready' || this.offerExpired()) return;
    this.serverError.set('');
    let address: AddressForm | null = null;
    if (listing.type === 'HARDWARE') {
      address = this.form();
      const errors = validateAddress(address);
      const invalid = FIELD_ORDER.filter((field) => errors[field]);
      this.errors.set(errors);
      this.attentionShown.set(invalid.length > 0);
      if (invalid.length) {
        this.focus('#' + FIELD_IDS[invalid[0]]);
        return;
      }
    }
    this.attentionShown.set(false);
    this.submitting.set(true);
    try {
      const outcome = await this.checkout.submit(
        buildCheckoutRequest({ listing, offer: this.offer(), address }),
      );
      this.finish(outcome);
    } catch (error) {
      await this.fail(isAppError(error) ? error : UNKNOWN_ERROR);
    } finally {
      this.submitting.set(false);
    }
  }

  openOrders(): void {
    void this.router.navigate(['/orders']);
  }

  resume(url: string): void {
    const target = approvalTarget(url);
    if (target) this.startRedirect(target);
  }

  private finish(outcome: CheckoutOutcome): void {
    switch (outcome.kind) {
      case 'redirect':
        this.startRedirect(outcome.approvalUrl);
        break;
      case 'blocked':
        this.handoff.set({
          orderId: outcome.orderId,
          kind: 'blocked',
          heading: "We couldn't open PayPal safely",
          text: 'Your order was created, but its payment link could not be verified. Open the order to review or cancel it.',
          approvalUrl: null,
        });
        this.status.set('order');
        break;
      case 'order':
        this.handoff.set(statusHandoff(outcome.orderId, outcome.status));
        this.status.set('order');
        break;
    }
  }

  private startRedirect(url: string): void {
    this.approvalUrl.set(url);
    this.fallback.set(false);
    this.status.set('redirecting');
    clearTimeout(this.fallbackTimer);
    this.fallbackTimer = setTimeout(() => this.fallback.set(true), this.fallbackDelay);
    this.navigate(url);
  }

  private async restore(): Promise<boolean> {
    const pending = this.pending.read();
    if (
      !pending ||
      pending.listingId !== this.listingId ||
      (pending.offerId ?? null) !== (this.offerId ?? null)
    ) {
      return false;
    }
    let order: OrderView | null = null;
    try {
      order = await firstValueFrom(this.orders.order(pending.orderId));
    } catch (error) {
      if (isAppError(error) && error.status >= 400 && error.status < 500) {
        this.pending.clear();
        return false;
      }
    }
    if (order?.status === 'CANCELLED') {
      this.pending.clear();
      return false;
    }
    if (order && order.status !== 'AWAITING_PAYMENT') {
      this.handoff.set(statusHandoff(pending.orderId, order.status));
    } else {
      this.handoff.set(this.resumable(pending));
    }
    this.status.set('order');
    return true;
  }

  private resumable(pending: PendingCheckout): Handoff {
    return {
      orderId: pending.orderId,
      kind: 'resume',
      heading: 'You already started this checkout',
      text: 'Your order is waiting for payment. Continue on PayPal, or open the order to review or cancel it.',
      approvalUrl: approvalTarget(pending.approvalUrl),
    };
  }

  private async fail(error: AppError): Promise<void> {
    this.serverError.set('');
    this.ordersLink.set(error.code === 'checkout-outcome-unknown');
    switch (error.code) {
      case 'listing-unavailable':
        this.status.set('unavailable');
        return;
      case 'price-mismatch':
        this.serverError.set(this.mismatchCopy(error));
        await this.refreshListing();
        return;
      case 'idempotency-key-conflict':
        this.checkout.discardKey();
        break;
      case 'invalid-shipping-address':
        this.errors.update((errors) => ({
          ...errors,
          postalCode: 'Check the postal code for the selected country.',
        }));
        this.focus('#' + FIELD_IDS.postalCode);
        return;
      case 'validation-failed': {
        const mapped = this.mapValidation(error.fieldErrors);
        if (mapped.length) {
          this.errors.update((errors) => ({ ...errors, ...Object.fromEntries(mapped) }));
          this.focus('#' + FIELD_IDS[mapped[0][0]]);
          return;
        }
        break;
      }
      default:
        if (OFFER_REJECTIONS.has(error.code)) {
          this.status.set('offer-unusable');
          return;
        }
    }
    this.serverError.set(CHECKOUT_COPY[error.code] ?? errorCopy(error));
  }

  private mismatchCopy(error: AppError): string {
    const raw = error.extras['currentTotal'];
    try {
      const total = addMoney(String(raw), '0');
      return `The total changed to ${EUR.transform(total)}. Review it and try again.`;
    } catch {
      return 'The total changed. Review it and try again.';
    }
  }

  private mapValidation(
    fieldErrors: Readonly<Record<string, string>>,
  ): [keyof AddressForm, string][] {
    const mapped: [keyof AddressForm, string][] = [];
    for (const name of Object.keys(fieldErrors)) {
      const target = VALIDATION_FIELDS[name.split('.').pop() ?? ''];
      if (target && !mapped.some(([field]) => field === target[0])) mapped.push(target);
    }
    return FIELD_ORDER.flatMap((field) => mapped.filter(([candidate]) => candidate === field));
  }

  private async refreshListing(): Promise<void> {
    try {
      this.listing.set(await firstValueFrom(this.catalog.listing(this.listingId)));
    } catch {
      // The banner already told the buyer to review; a later submit reports any lasting failure.
    }
  }

  private form(): AddressForm {
    return {
      recipient: this.recipient(),
      street: this.street(),
      city: this.city(),
      postalCode: this.postalCode(),
      country: this.country(),
    };
  }

  private usable(offer: OfferView | null): boolean {
    return (
      !!offer &&
      offer.listingId === this.listingId &&
      offer.buyerSub === this.auth.identity()?.sub &&
      offer.status === 'ACCEPTED' &&
      offer.orderId === null &&
      (offer.expiresAt === null || Date.parse(offer.expiresAt) > Date.now())
    );
  }

  private async fetchOffer(id: string): Promise<OfferView | null> {
    try {
      return await firstValueFrom(this.orders.offer(id));
    } catch (error) {
      if (isAppError(error) && error.status >= 400 && error.status < 500) return null;
      throw error;
    }
  }

  private async loadPrevious(): Promise<void> {
    try {
      const page = await firstValueFrom(this.orders.purchases());
      this.previous.set(
        page.items.find((order) => order.listingType === 'HARDWARE' && order.shippingAddress) ??
          null,
      );
    } catch {
      // Prefill is a convenience; the form works without it.
    }
  }

  private async leave(): Promise<void> {
    await this.router.navigate(['/listings', this.listingId], { replaceUrl: true });
  }

  private focus(selector: string): void {
    afterNextRender(
      () => {
        const target = this.host.nativeElement.querySelector<HTMLElement>(selector);
        target?.focus();
        target?.scrollIntoView?.({ block: 'center' });
      },
      { injector: this.injector },
    );
  }
}
