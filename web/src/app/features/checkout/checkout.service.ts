import { inject, Injectable, InjectionToken } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  Address,
  CheckoutRequest,
  CheckoutResponse,
  ListingDetailResponse,
  Money,
  OfferView,
  OrderStatus,
} from '../../core/api/api-models';
import { OrderApi } from '../../core/api/order-api';
import { PendingCheckoutStore } from './pending-checkout.store';

export type AddressForm = Address;
export type AddressErrors = Partial<Record<keyof AddressForm, string>>;
export interface CountryRule {
  code: string;
  name: string;
  example: string;
  pattern: RegExp;
}

// CheckoutRequest.Address limits in order-service.
export const ADDRESS_LIMITS = { recipient: 150, street: 200, city: 100, postalCode: 20 } as const;

// Mirrors OrderCheckoutService.EU_COUNTRIES and POSTAL_PATTERNS; the server stays authoritative.
const COUNTRY_DATA: readonly [code: string, name: string, pattern: string, example: string][] = [
  ['AT', 'Austria', '\\d{4}', '1010'],
  ['BE', 'Belgium', '\\d{4}', '1000'],
  ['BG', 'Bulgaria', '\\d{4}', '1000'],
  ['HR', 'Croatia', '\\d{5}', '10000'],
  ['CY', 'Cyprus', '\\d{4}', '1010'],
  ['CZ', 'Czechia', '\\d{3} ?\\d{2}', '110 00'],
  ['DK', 'Denmark', '\\d{4}', '1050'],
  ['EE', 'Estonia', '\\d{5}', '10111'],
  ['FI', 'Finland', '\\d{5}', '00100'],
  ['FR', 'France', '\\d{5}', '75001'],
  ['DE', 'Germany', '\\d{5}', '10115'],
  ['GR', 'Greece', '\\d{3} ?\\d{2}', '105 57'],
  ['HU', 'Hungary', '\\d{4}', '1051'],
  ['IE', 'Ireland', '[A-Z0-9]{3} ?[A-Z0-9]{4}', 'D02 X285'],
  ['IT', 'Italy', '\\d{5}', '00100'],
  ['LV', 'Latvia', '(?:LV-)?\\d{4}', 'LV-1050'],
  ['LT', 'Lithuania', '(?:LT-)?\\d{5}', 'LT-01100'],
  ['LU', 'Luxembourg', '\\d{4}', '1111'],
  ['MT', 'Malta', '[A-Z]{3} ?\\d{4}', 'VLT 1117'],
  ['NL', 'Netherlands', '\\d{4} ?[A-Z]{2}', '1012 AB'],
  ['PL', 'Poland', '\\d{2}-\\d{3}', '00-950'],
  ['PT', 'Portugal', '\\d{4}-\\d{3}', '1000-001'],
  ['RO', 'Romania', '\\d{6}', '010011'],
  ['SK', 'Slovakia', '\\d{3} ?\\d{2}', '811 01'],
  ['SI', 'Slovenia', '\\d{4}', '1000'],
  ['ES', 'Spain', '\\d{5}', '28001'],
  ['SE', 'Sweden', '\\d{3} ?\\d{2}', '111 22'],
];

export const EU_COUNTRIES: readonly CountryRule[] = COUNTRY_DATA.map(
  ([code, name, pattern, example]) => ({
    code,
    name,
    example,
    pattern: new RegExp(`^(?:${pattern})$`),
  }),
).sort((a, b) => a.name.localeCompare(b.name, 'en'));

const MONEY = /^(\d+)(?:\.(\d{1,2}))?$/;

function minorUnits(value: Money): bigint {
  const match = MONEY.exec(value.trim());
  if (!match) throw new RangeError('Invalid money amount');
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'));
}

/** Adds two decimal strings in integer minor units so no binary float is involved. */
export function addMoney(first: Money, second: Money): Money {
  const sum = minorUnits(first) + minorUnits(second);
  return `${sum / 100n}.${String(sum % 100n).padStart(2, '0')}`;
}

export function validateAddress(form: AddressForm): AddressErrors {
  const errors: AddressErrors = {};
  const recipient = form.recipient.trim();
  const street = form.street.trim();
  const city = form.city.trim();
  const postalCode = form.postalCode.trim();
  const country = form.country.trim().toUpperCase();
  const rule = EU_COUNTRIES.find((candidate) => candidate.code === country);

  if (!recipient) errors.recipient = 'Enter the recipient name.';
  else if (recipient.length > ADDRESS_LIMITS.recipient) {
    errors.recipient = `Use at most ${ADDRESS_LIMITS.recipient} characters.`;
  }
  if (!street) errors.street = 'Enter the street address.';
  else if (street.length > ADDRESS_LIMITS.street) {
    errors.street = `Use at most ${ADDRESS_LIMITS.street} characters.`;
  }
  if (!city) errors.city = 'Enter the city.';
  else if (city.length > ADDRESS_LIMITS.city) {
    errors.city = `Use at most ${ADDRESS_LIMITS.city} characters.`;
  }
  if (!country) errors.country = 'Choose a country.';
  else if (!rule) errors.country = 'Choose a country from the list.';
  if (!postalCode) errors.postalCode = 'Enter the postal code.';
  else if (postalCode.length > ADDRESS_LIMITS.postalCode) {
    errors.postalCode = `Use at most ${ADDRESS_LIMITS.postalCode} characters.`;
  } else if (rule && !rule.pattern.test(postalCode.toUpperCase())) {
    errors.postalCode = `Enter a valid postal code, for example ${rule.example}.`;
  }
  return errors;
}

export interface CheckoutInput {
  listing: Pick<ListingDetailResponse, 'id' | 'type' | 'price' | 'shippingCost'>;
  offer: Pick<OfferView, 'id' | 'amount'> | null;
  address: AddressForm | null;
}

/** Item price (the accepted amount for an offer) plus seller shipping for hardware. */
export function checkoutTotal(input: Pick<CheckoutInput, 'listing' | 'offer'>): Money {
  const item = input.offer ? input.offer.amount : input.listing.price;
  const shipping = input.listing.type === 'HARDWARE' ? (input.listing.shippingCost ?? '0') : '0';
  return addMoney(item, shipping);
}

export function buildCheckoutRequest(input: CheckoutInput): CheckoutRequest {
  const request: CheckoutRequest = {
    listingId: input.listing.id,
    method: 'PAYPAL',
    expectedTotal: checkoutTotal(input),
  };
  if (input.listing.type === 'HARDWARE') {
    if (!input.address) throw new Error('A shipping address is required for hardware.');
    request.address = {
      recipient: input.address.recipient.trim(),
      street: input.address.street.trim(),
      city: input.address.city.trim(),
      postalCode: input.address.postalCode.trim(),
      country: input.address.country.trim().toUpperCase(),
    };
  }
  if (input.offer) request.offerId = input.offer.id;
  return request;
}

/** Two requests with the same fingerprint are the same purchase and may share an Idempotency-Key. */
export function checkoutFingerprint(request: CheckoutRequest): string {
  const address = request.address;
  return JSON.stringify([
    request.listingId,
    request.offerId ?? null,
    request.method,
    request.expectedTotal,
    address
      ? [address.recipient, address.street, address.city, address.postalCode, address.country]
      : null,
  ]);
}

export type CheckoutOutcome =
  | { kind: 'redirect'; orderId: string; approvalUrl: string }
  | { kind: 'order'; orderId: string; status: OrderStatus }
  | { kind: 'blocked'; orderId: string };

/** Performs the full-page hand-off to PayPal; replaced in tests. */
export const CHECKOUT_NAVIGATOR = new InjectionToken<(url: string) => void>('CheckoutNavigator', {
  factory: () => (url) => window.location.assign(url),
});
/** How long the redirect may take before the visible fallback link appears. */
export const CHECKOUT_FALLBACK_DELAY_MS = new InjectionToken<number>('CheckoutFallbackDelayMs', {
  factory: () => 4000,
});

/**
 * The server-provided approval URL is untrusted input to a full-page navigation: only
 * HTTPS on paypal.com or one of its subdomains, without credentials or a custom port.
 */
export function approvalTarget(url: string | null | undefined): string | null {
  if (typeof url !== 'string' || !url) return null;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.port) {
    return null;
  }
  const host = parsed.hostname;
  return host === 'paypal.com' || host.endsWith('.paypal.com') ? parsed.href : null;
}

@Injectable({ providedIn: 'root' })
export class CheckoutService {
  private readonly orders = inject(OrderApi);
  private readonly pending = inject(PendingCheckoutStore);

  async submit(request: CheckoutRequest): Promise<CheckoutOutcome> {
    const key = this.pending.idempotencyKey(checkoutFingerprint(request));
    const response = await firstValueFrom(this.orders.checkout(request, key));
    // A definite answer ends this attempt, so an identical later request is a new purchase.
    this.pending.rotateKey();
    return this.settle(request, response);
  }

  /** Forces a new key for the next attempt, for example after the server rejects the old one. */
  discardKey(): void {
    this.pending.rotateKey();
  }

  // An idempotent replay reports the order's current status, which may no longer be awaiting payment.
  private settle(request: CheckoutRequest, response: CheckoutResponse): CheckoutOutcome {
    const { orderId, status } = response;
    if (status !== 'AWAITING_PAYMENT') {
      if (this.pending.read()?.orderId === orderId) this.pending.clear();
      return { kind: 'order', orderId, status };
    }
    const approvalUrl = approvalTarget(response.approvalUrl);
    this.pending.save({
      orderId,
      listingId: request.listingId,
      ...(request.offerId ? { offerId: request.offerId } : {}),
      startedAt: new Date().toISOString(),
      ...(approvalUrl ? { approvalUrl } : {}),
    });
    return approvalUrl ? { kind: 'redirect', orderId, approvalUrl } : { kind: 'blocked', orderId };
  }
}
