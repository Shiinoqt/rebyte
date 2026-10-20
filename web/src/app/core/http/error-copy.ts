import { AppError } from './app-error';

const GENERIC = 'Something went wrong. Try again in a minute.';
const COPY: Readonly<Record<string, string>> = {
  'listing-unavailable': 'This item is no longer available.',
  'self-purchase-forbidden': "You can't buy your own listing.",
  'payment-method-not-allowed': 'This listing does not accept PayPal.',
  'payment-unavailable':
    "PayPal is not responding. You haven't been charged. Try again in a minute.",
  'checkout-outcome-unknown':
    "We couldn't confirm your order. Check your orders before trying again.",
  'duplicate-pending-offer': 'You already have a pending offer on this item.',
  'self-offer-forbidden': "You can't make an offer on your own listing.",
  'offer-hardware-only': 'Offers are for hardware listings only.',
  'invalid-offer-amount': 'Enter an amount below the asking price.',
  'offer-not-pending': 'This offer was already answered.',
  'problem-not-reportable': "A problem can't be reported for this order now.",
  'problem-already-reported': 'A problem was already reported for this order.',
  'order-not-reviewable': 'You can review this order once it is delivered.',
  'review-already-exists': 'You already reviewed this order.',
  'conversation-exists': '',
  'self-conversation': "You can't message yourself.",
  'listing-not-available': 'This listing is no longer available to message about.',
  'listing-not-editable': "Sold or removed listings can't be edited.",
  'photo-limit-exceeded': 'A listing can have up to 5 photos.',
  'developer-profile-required': 'Become a developer to sell software.',
  'category-not-leaf': 'Choose the most specific category.',
  'photo-key-not-on-listing':
    'One of the photos is no longer on this listing. Reload the page and try again.',
  'licence-required': 'Your licence for this item is not active.',
  'artifact-not-available': 'This version has no installer yet.',
  'listing-moderated': 'This listing was removed.',
  'validation-failed': 'Check the highlighted fields and try again.',
  unauthenticated: 'Log in to continue.',
  'access-denied': "You don't have permission to do that.",
  'rate-limited': "You're doing that too quickly. Wait a moment and try again.",
  offline: 'You appear to be offline.',
};

export function errorCopy(error: AppError): string {
  if (error.status === 0) {
    return COPY['offline'];
  }
  if (error.status >= 500) {
    return GENERIC;
  }
  if (error.code === 'price-mismatch') {
    const total = error.extras['currentTotal'];
    return (typeof total === 'number' && Number.isFinite(total) && total >= 0) ||
      (typeof total === 'string' && /^\d+(\.\d+)?$/.test(total))
      ? `The total changed to ${total}. Review it and try again.`
      : 'The total changed. Review it and try again.';
  }
  return Object.hasOwn(COPY, error.code) ? COPY[error.code] : GENERIC;
}
