import { Injectable } from '@angular/core';

export const PENDING_CHECKOUT_KEY = 'rebyte.pendingCheckout';
const IDEMPOTENCY_KEY = 'rebyte.checkoutIdempotency';

export interface PendingCheckout {
  orderId: string;
  listingId: string;
  offerId?: string;
  startedAt: string;
  // Re-validated against the PayPal allowlist wherever it is used; storage is not trusted.
  approvalUrl?: string;
}

interface StoredKey {
  fingerprint: string;
  key: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Order correlation and Idempotency-Key for the checkout in this tab. Both live in
 * sessionStorage because PayPal returns to fixed URLs that carry no Rebyte order id.
 */
@Injectable({ providedIn: 'root' })
export class PendingCheckoutStore {
  // Private browsing can make sessionStorage throw; the page then keeps state for this load only.
  private readonly fallback = new Map<string, string>();

  read(): PendingCheckout | null {
    const parsed = this.parse(PENDING_CHECKOUT_KEY);
    if (
      !isRecord(parsed) ||
      typeof parsed['orderId'] !== 'string' ||
      typeof parsed['listingId'] !== 'string' ||
      typeof parsed['startedAt'] !== 'string' ||
      !parsed['orderId'] ||
      !parsed['listingId']
    ) {
      return null;
    }
    return {
      orderId: parsed['orderId'],
      listingId: parsed['listingId'],
      startedAt: parsed['startedAt'],
      ...(typeof parsed['offerId'] === 'string' && parsed['offerId']
        ? { offerId: parsed['offerId'] }
        : {}),
      ...(typeof parsed['approvalUrl'] === 'string' && parsed['approvalUrl']
        ? { approvalUrl: parsed['approvalUrl'] }
        : {}),
    };
  }

  save(pending: PendingCheckout): void {
    this.write(PENDING_CHECKOUT_KEY, JSON.stringify(pending));
  }

  clear(): void {
    this.remove(PENDING_CHECKOUT_KEY);
  }

  /** The key of the last unresolved attempt when its request is unchanged, otherwise a fresh one. */
  idempotencyKey(fingerprint: string): string {
    const parsed = this.parse(IDEMPOTENCY_KEY);
    if (
      isRecord(parsed) &&
      parsed['fingerprint'] === fingerprint &&
      typeof parsed['key'] === 'string' &&
      parsed['key']
    ) {
      return parsed['key'];
    }
    const stored: StoredKey = { fingerprint, key: crypto.randomUUID() };
    this.write(IDEMPOTENCY_KEY, JSON.stringify(stored));
    return stored.key;
  }

  rotateKey(): void {
    this.remove(IDEMPOTENCY_KEY);
  }

  private parse(name: string): unknown {
    try {
      const raw = this.fallback.get(name) ?? sessionStorage.getItem(name);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  private write(name: string, value: string): void {
    try {
      sessionStorage.setItem(name, value);
      this.fallback.delete(name);
    } catch {
      this.fallback.set(name, value);
    }
  }

  private remove(name: string): void {
    this.fallback.delete(name);
    try {
      sessionStorage.removeItem(name);
    } catch {
      // Nothing to remove when storage is unavailable.
    }
  }
}
