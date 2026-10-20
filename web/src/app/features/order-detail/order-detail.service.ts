import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  Carrier,
  OrderStatus,
  OrderView,
  PaymentMethod,
  ShipOrderRequest,
} from '../../core/api/api-models';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { ToastService } from '../../shared/ui/feedback';

export type DetailState = 'loading' | 'ready' | 'unavailable' | 'error';
export type Command = 'ship' | 'deliver' | 'cancel';

export const STATUS_LABEL: Readonly<Record<OrderStatus, string>> = {
  AWAITING_PAYMENT: 'Awaiting payment',
  PAID: 'Paid',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  REFUNDED: 'Refunded',
};

export const CARRIER_LABEL: Readonly<Record<Carrier, string>> = {
  POSTE_ITALIANE: 'Poste Italiane',
  BRT: 'BRT',
  GLS: 'GLS',
  SDA: 'SDA',
  DHL: 'DHL',
  UPS: 'UPS',
  FEDEX: 'FedEx',
  DPD: 'DPD',
  OTHER: 'Other',
};

export const PAYMENT_LABEL: Readonly<Record<PaymentMethod, string>> = {
  PAYPAL: 'PayPal',
  CREDIT_CARD: 'Credit card',
  BANK_TRANSFER: 'Bank transfer',
};

export interface LifecycleActions {
  ship: boolean;
  deliver: boolean;
  cancel: boolean;
}

const NO_ACTIONS: LifecycleActions = { ship: false, deliver: false, cancel: false };

/**
 * Which commands the signed-in sub may attempt. Mirrors OrderFulfilmentService.check (only the
 * seller shipping, buyer receipt, hardware, no pending refund) and the buyer cancellation window;
 * ADMIN grants nothing here. The backend stays authoritative and a stale answer is reconciled
 * by refetching.
 */
export function lifecycleActions(order: OrderView, sub: string | null): LifecycleActions {
  if (!sub) return NO_ACTIONS;
  const hardwareWithoutRefund = order.listingType === 'HARDWARE' && !order.refundRequestedAt;
  return {
    ship: order.sellerSub === sub && order.status === 'PAID' && hardwareWithoutRefund,
    deliver: order.buyerSub === sub && order.status === 'SHIPPED' && hardwareWithoutRefund,
    cancel: sub === order.buyerSub && order.status === 'AWAITING_PAYMENT',
  };
}

export interface ShipForm {
  carrier: string;
  trackingCode: string;
}
export type ShipErrors = Partial<Record<keyof ShipForm, string>>;

export const TRACKING_MAX_LENGTH = 64;
// ShipOrderRequest.trackingCode pattern.
const TRACKING_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 ./-]*$/;

// The backend strips spaces only, so a tab or newline must still fail the pattern.
function stripSpaces(value: string): string {
  return value.replace(/^ +| +$/g, '');
}

export function validateShipping(form: ShipForm): ShipErrors {
  const errors: ShipErrors = {};
  if (!form.carrier) errors.carrier = 'Choose a carrier.';
  else if (!Object.hasOwn(CARRIER_LABEL, form.carrier)) {
    errors.carrier = 'Choose a carrier from the list.';
  }
  const code = stripSpaces(form.trackingCode);
  if (!code) errors.trackingCode = 'Enter the tracking code.';
  else if (code.length > TRACKING_MAX_LENGTH) {
    errors.trackingCode = `Use at most ${TRACKING_MAX_LENGTH} characters.`;
  } else if (!TRACKING_PATTERN.test(code)) {
    errors.trackingCode =
      'Use letters, digits, spaces, dots, slashes and hyphens, starting with a letter or digit.';
  }
  return errors;
}

export function buildShipRequest(form: ShipForm): ShipOrderRequest {
  return { carrier: form.carrier as Carrier, trackingCode: stripSpaces(form.trackingCode) };
}

export type CommandResult =
  | { kind: 'ignored' }
  | { kind: 'done' }
  | { kind: 'conflict' }
  | { kind: 'failed'; message: string; fieldErrors: ShipErrors };

export interface Notice {
  kind: 'warning' | 'error';
  text: string;
}

const SUCCESS_TEXT: Readonly<Record<Command, string>> = {
  ship: 'Order marked as shipped',
  deliver: 'Receipt confirmed',
  cancel: 'Order cancelled',
};

// Codes that mean the order is no longer where the viewer last saw it.
const CONFLICT_COPY: Readonly<Record<string, string>> = {
  'order-transition-conflict':
    'This order changed before your action went through. The page now shows its current state.',
  'order-refund-pending':
    'A refund has been requested for this order, so it can no longer be shipped or delivered.',
  'order-cancellation-conflict':
    'This order can no longer be cancelled. The page now shows its current state.',
  'order-action-forbidden':
    'You cannot perform this action on this order. The page now shows the current order.',
};

const UNKNOWN_ERROR: AppError = {
  status: 500,
  code: 'unknown-error',
  type: 'about:blank',
  fieldErrors: {},
  extras: {},
};

function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'string'
  );
}

// The backend answers 404 for an order the viewer is not party to; 403 is treated identically.
function isUnavailable(error: unknown): boolean {
  return isAppError(error) && (error.status === 404 || error.status === 403);
}

function shipFieldErrors(fieldErrors: Readonly<Record<string, string>>): ShipErrors {
  const mapped: ShipErrors = {};
  for (const name of Object.keys(fieldErrors)) {
    const field = name.split('.').pop();
    if (field === 'carrier') mapped.carrier = 'Choose a carrier.';
    if (field === 'trackingCode') mapped.trackingCode = 'Check the tracking code.';
  }
  return mapped;
}

/**
 * State of the order shown on S7. Provided per page so a new visit never inherits the
 * previous order, and every response is checked against the load that requested it.
 */
@Injectable()
export class OrderDetailService {
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly toasts = inject(ToastService);
  private readonly currentOrder = signal<OrderView | null>(null);
  private readonly currentState = signal<DetailState>('loading');
  private readonly currentBusy = signal<Command | null>(null);
  private readonly currentNotice = signal<Notice | null>(null);
  private generation = 0;

  readonly order = this.currentOrder.asReadonly();
  readonly state = this.currentState.asReadonly();
  readonly busy = this.currentBusy.asReadonly();
  readonly notice = this.currentNotice.asReadonly();
  readonly viewerSub = computed(() => this.auth.identity()?.sub ?? null);
  readonly actions = computed(() => {
    const order = this.currentOrder();
    return order ? lifecycleActions(order, this.viewerSub()) : NO_ACTIONS;
  });

  /** Drops whatever is shown first: a 403 or 404 must not leave a previous order on screen. */
  async load(id: string): Promise<void> {
    const generation = ++this.generation;
    this.currentOrder.set(null);
    this.currentNotice.set(null);
    this.currentState.set('loading');
    try {
      const order = await firstValueFrom(this.api.order(id));
      if (generation !== this.generation) return;
      this.currentOrder.set(order);
      this.currentState.set('ready');
    } catch (error) {
      if (generation !== this.generation) return;
      this.currentState.set(isUnavailable(error) ? 'unavailable' : 'error');
    }
  }

  ship(request: ShipOrderRequest): Promise<CommandResult> {
    return this.run('ship', (id) => firstValueFrom(this.api.shipOrder(id, request)));
  }

  deliver(): Promise<CommandResult> {
    return this.run('deliver', (id) => firstValueFrom(this.api.deliverOrder(id)));
  }

  cancel(): Promise<CommandResult> {
    return this.run('cancel', (id) => firstValueFrom(this.api.cancelOrder(id)).then(() => null));
  }

  setNotice(notice: Notice | null): void {
    this.currentNotice.set(notice);
  }

  // Single-flight: the busy flag is set before the first await, so a second call sees it.
  private async run(
    command: Command,
    send: (id: string) => Promise<OrderView | null>,
  ): Promise<CommandResult> {
    const order = this.currentOrder();
    if (this.currentBusy() || !order || !this.actions()[command]) return { kind: 'ignored' };
    const generation = this.generation;
    this.currentBusy.set(command);
    this.currentNotice.set(null);
    try {
      let response: OrderView | null;
      try {
        response = await send(order.id);
      } catch (error) {
        return await this.reconcileFailure(order.id, generation, error);
      }
      await this.refresh(order.id, generation, response);
      this.toasts.show({ text: SUCCESS_TEXT[command], kind: 'success' });
      return { kind: 'done' };
    } finally {
      this.currentBusy.set(null);
    }
  }

  private async reconcileFailure(
    id: string,
    generation: number,
    error: unknown,
  ): Promise<CommandResult> {
    const problem = isAppError(error) ? error : UNKNOWN_ERROR;
    const conflict = CONFLICT_COPY[problem.code];
    if (conflict || problem.status === 404) {
      if (conflict && generation === this.generation) {
        this.currentNotice.set({ kind: 'warning', text: conflict });
      }
      await this.refresh(id, generation, null);
      return { kind: 'conflict' };
    }
    const fieldErrors =
      problem.code === 'validation-failed' ? shipFieldErrors(problem.fieldErrors) : {};
    return {
      kind: 'failed',
      message: Object.keys(fieldErrors).length ? '' : errorCopy(problem),
      fieldErrors,
    };
  }

  /**
   * Replaces the shown order with the server's current one. When the refetch itself fails after a
   * command that succeeded, that command's own response is the freshest server state available.
   */
  private async refresh(id: string, generation: number, fallback: OrderView | null): Promise<void> {
    try {
      const fresh = await firstValueFrom(this.api.order(id));
      if (generation !== this.generation) return;
      this.currentOrder.set(fresh);
      this.currentState.set('ready');
    } catch (error) {
      if (generation !== this.generation) return;
      if (!isUnavailable(error) && fallback) {
        this.currentOrder.set(fallback);
        this.currentState.set('ready');
        return;
      }
      this.currentOrder.set(null);
      this.currentState.set(isUnavailable(error) ? 'unavailable' : 'error');
    }
  }
}
