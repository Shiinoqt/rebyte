import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Instant, OrderStatus, OrderView } from '../../core/api/api-models';
import { AuthStore } from '../../core/auth/auth.store';
import { LicenceCard } from './licence-card';
import { OrderExtras, ProblemCard } from './problem-card';
import { ReviewForm } from './review-form';
import { CopyField, MoneyRow, MoneyRows, StatusChip } from '../../shared/ui/content';
import { ModalDialog } from '../../shared/ui/dialog';
import { Banner, BusyButton, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { FieldOption, FormField } from '../../shared/ui/forms';
import { approvalTarget, CHECKOUT_NAVIGATOR, EU_COUNTRIES } from '../checkout/checkout.service';
import { PendingCheckoutStore } from '../checkout/pending-checkout.store';
import {
  buildShipRequest,
  CARRIER_LABEL,
  Command,
  CommandResult,
  OrderDetailService,
  PAYMENT_LABEL,
  ShipErrors,
  STATUS_LABEL,
  TRACKING_MAX_LENGTH,
  validateShipping,
} from './order-detail.service';

type StepState = 'done' | 'current' | 'todo';
interface Step {
  label: string;
  state: StepState;
  at: Instant | null;
}

const CARRIER_OPTIONS: readonly FieldOption[] = Object.entries(CARRIER_LABEL).map(
  ([value, label]) => ({ value, label }),
);

const STATE_TEXT: Readonly<Record<StepState, string>> = {
  done: 'Completed',
  current: 'Current step',
  todo: 'Upcoming',
};

function stepsFor(order: OrderView): Step[] {
  const hardware = order.listingType === 'HARDWARE';
  const sequence: { status: OrderStatus; label: string; at: Instant | undefined }[] = [
    { status: 'PAID', label: 'Paid', at: order.paidAt },
    ...(hardware ? [{ status: 'SHIPPED' as const, label: 'Shipped', at: order.shippedAt }] : []),
    { status: 'DELIVERED', label: 'Delivered', at: order.deliveredAt },
  ];
  const reached = Math.max(
    0,
    sequence.findIndex((step) => step.status === order.status),
  );
  return sequence.map((step, index) => ({
    label: step.label,
    state: index < reached ? 'done' : index === reached ? 'current' : 'todo',
    at: index <= reached ? (step.at ?? null) : null,
  }));
}

function countdownText(expiresAt: string, now: number): string {
  const seconds = Math.max(0, Math.floor((Date.parse(expiresAt) - now) / 1000));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = String(seconds % 60).padStart(2, '0');
  return hours > 0
    ? `${hours} h ${String(minutes).padStart(2, '0')} min left`
    : `${minutes} min ${rest} s left`;
}

@Component({
  selector: 'app-order-detail-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    Banner,
    BusyButton,
    CopyField,
    DatePipe,
    ErrorState,
    FormField,
    ModalDialog,
    MoneyRows,
    RouterLink,
    Skeleton,
    StatusChip,
    LicenceCard,
    ProblemCard,
    ReviewForm,
  ],
  providers: [OrderDetailService, OrderExtras],
  styleUrl: './order-detail.page.css',
  template: `
    @if (auth.isAuthenticated()) {
      @switch (service.state()) {
        @case ('loading') {
          <h1 tabindex="-1" class="plain">Order</h1>
          <app-skeleton label="Loading order" shape="card" />
        }
        @case ('unavailable') {
          <h1 tabindex="-1" class="plain">Order</h1>
          <div class="unavailable" role="alert">
            <h2>This order isn't available.</h2>
            <a routerLink="/orders">Back to your orders</a>
          </div>
        }
        @case ('error') {
          <h1 tabindex="-1" class="plain">Order</h1>
          <app-error-state (retry)="load()" />
        }
        @default {
          @if (service.order(); as order) {
            <header class="head">
              <div class="title">
                <h1 tabindex="-1">
                  <a [routerLink]="['/listings', order.listingId]">{{ order.listingTitle }}</a>
                </h1>
                <app-status-chip [text]="statusLabel(order)" [tone]="statusTone(order)" />
              </div>
              <p class="meta">
                {{ order.listingType === 'HARDWARE' ? 'Hardware' : 'Software' }} order · Placed
                {{ order.createdAt | date: 'd MMM y' }}
              </p>
              <app-copy-field label="Order ID" [value]="order.id" />
            </header>

            <div class="layout">
              <div class="details">
                @if (service.notice(); as notice) {
                  <div class="notice">
                    <app-banner [kind]="notice.kind" [text]="notice.text" />
                  </div>
                }
                @if (order.status === 'AWAITING_PAYMENT') {
                  @if (expired(order)) {
                    <app-banner
                      kind="warning"
                      text="The payment window has closed. If you did not pay, this order will be cancelled."
                    />
                  } @else {
                    <app-banner
                      kind="info"
                      text="Waiting for payment. Complete it on PayPal before the window closes."
                    />
                    <p class="countdown" role="timer">{{ countdown(order) }}</p>
                  }
                } @else if (order.status === 'CANCELLED') {
                  <app-banner kind="info" text="This order was cancelled." />
                } @else if (order.status === 'REFUNDED') {
                  <app-banner
                    kind="info"
                    [text]="
                      order.license?.status === 'REVOKED'
                        ? 'This order was refunded and its licence was revoked.'
                        : 'This order was refunded.'
                    "
                  />
                } @else {
                  <ol
                    class="stepper"
                    aria-label="Order progress"
                    [style.--lead-steps]="steps(order).length - 1"
                  >
                    @for (step of steps(order); track step.label) {
                      <li
                        [attr.data-state]="step.state"
                        [attr.aria-current]="step.state === 'current' ? 'step' : null"
                      >
                        <span class="marker" aria-hidden="true"></span>
                        <span class="step-label">{{ step.label }}</span>
                        <span class="visually-hidden step-state">{{ stateText(step.state) }}</span>
                        @if (step.at; as at) {
                          <time class="step-time" [attr.datetime]="at">{{
                            at | date: 'd MMM y'
                          }}</time>
                        }
                      </li>
                    }
                  </ol>
                }

                @if (order.refundRequestedAt && order.problem?.status !== 'REFUND_PENDING') {
                  <app-banner kind="warning" text="A refund has been requested for this order." />
                }

                @if (extras.error()) {
                  <app-banner kind="error" [text]="extras.error()" />
                }
                @if (order.listingType === 'SOFTWARE' && order.license) {
                  <app-licence-card [order]="order" />
                }
                <app-problem-card [order]="order" />
                <app-review-form [order]="order" />

                @if (order.carrier || order.trackingCode) {
                  <section class="shipment" aria-labelledby="shipment-heading">
                    <h2 id="shipment-heading" class="micro">Shipment</h2>
                    @if (order.carrier; as carrier) {
                      <dl>
                        <div>
                          <dt>Carrier</dt>
                          <dd>{{ carrierLabel(carrier) }}</dd>
                        </div>
                      </dl>
                    }
                    @if (order.trackingCode; as tracking) {
                      <app-copy-field label="Tracking code" [value]="tracking" />
                    }
                  </section>
                }

                @if (order.shippingAddress; as address) {
                  <section class="address" aria-labelledby="address-heading">
                    <h2 id="address-heading" class="micro">Shipping address</h2>
                    <address>
                      <span>{{ address.recipient }}</span>
                      <span>{{ address.street }}</span>
                      <span>{{ address.postalCode }} {{ address.city }}</span>
                      <span>{{ countryName(address.country) }}</span>
                    </address>
                  </section>
                }

                <section class="parties" aria-labelledby="parties-heading">
                  <h2 id="parties-heading" class="micro">People</h2>
                  <dl>
                    <div data-party="buyer">
                      <dt>Buyer</dt>
                      <dd>
                        @if (isBuyer(order)) {
                          You
                        } @else {
                          <a
                            [routerLink]="['/u', order.buyerSub]"
                            aria-label="View profile of buyer"
                            >View profile</a
                          >
                        }
                      </dd>
                    </div>
                    <div data-party="seller">
                      <dt>Seller</dt>
                      <dd>
                        @if (isSeller(order)) {
                          You
                        } @else {
                          <a
                            [routerLink]="['/u', order.sellerSub]"
                            aria-label="View profile of seller"
                            >View profile</a
                          >
                        }
                      </dd>
                    </div>
                  </dl>
                </section>
              </div>

              <aside class="rail" aria-label="Order summary">
                <h2 class="micro">Payment</h2>
                <app-money-rows [rows]="rows(order)" />
                <p class="method">Payment method: {{ paymentLabel(order) }}</p>
                @if (actions().ship) {
                  <div class="actions">
                    <app-busy-button
                      label="Mark as shipped"
                      [disabled]="service.busy() !== null || !!extras.busy()"
                      (pressed)="openShip()"
                    />
                    <p class="note">
                      Ship the order as soon as you can. If it is not shipped in time, the buyer is
                      refunded automatically.
                    </p>
                  </div>
                }
                @if (actions().deliver) {
                  <div class="actions">
                    <app-busy-button
                      label="Confirm receipt"
                      [disabled]="service.busy() !== null || !!extras.busy()"
                      (pressed)="openDeliver()"
                    />
                    <p class="note">
                      Confirm only after you receive the item. Otherwise the order is marked as
                      delivered automatically after a while, unless a refund or a problem is
                      pending.
                    </p>
                  </div>
                }
                @if (actions().cancel) {
                  <div class="actions">
                    @if (approvalLink(); as link) {
                      <app-busy-button
                        label="Continue to PayPal"
                        [disabled]="service.busy() !== null"
                        (pressed)="resume(link)"
                      />
                    }
                    <app-busy-button
                      label="Cancel order"
                      variant="neutral"
                      [disabled]="service.busy() !== null"
                      (pressed)="openCancel()"
                    />
                  </div>
                }
                <app-problem-card [order]="order" [entry]="true" />
              </aside>
            </div>

            @switch (dialog()) {
              @case ('ship') {
                <app-modal-dialog
                  heading="Mark as shipped"
                  sub="The buyer sees the carrier and the tracking code."
                  (closed)="closeDialog()"
                >
                  <form class="dialog-form" novalidate (submit)="submitShip($event)">
                    @if (dialogError()) {
                      <app-banner kind="error" [text]="dialogError()" />
                    }
                    <app-form-field
                      [id]="'ship-carrier'"
                      label="Carrier"
                      kind="select"
                      placeholder="Select a carrier"
                      [required]="true"
                      [options]="carriers"
                      [value]="carrier()"
                      [error]="shipErrors().carrier ?? ''"
                      [disabled]="service.busy() === 'ship'"
                      (valueChange)="editShip('carrier', $event)"
                      (blurred)="validateShipField('carrier')"
                    />
                    <app-form-field
                      [id]="'ship-tracking'"
                      label="Tracking code"
                      [required]="true"
                      [hint]="
                        'Up to ' + trackingMax + ' characters: letters, digits, spaces, . / -'
                      "
                      [value]="trackingCode()"
                      [error]="shipErrors().trackingCode ?? ''"
                      [disabled]="service.busy() === 'ship'"
                      (valueChange)="editShip('trackingCode', $event)"
                      (blurred)="validateShipField('trackingCode')"
                    />
                    <div class="dialog-actions">
                      <app-busy-button
                        label="Not now"
                        variant="neutral"
                        [disabled]="service.busy() !== null"
                        (pressed)="closeDialog()"
                      />
                      <app-busy-button
                        type="submit"
                        label="Mark as shipped"
                        [busy]="service.busy() === 'ship'"
                      />
                    </div>
                  </form>
                </app-modal-dialog>
              }
              @case ('deliver') {
                <app-modal-dialog
                  heading="Confirm receipt?"
                  size="confirm"
                  (closed)="closeDialog()"
                >
                  <p>Confirm only if you have received the item. This cannot be undone.</p>
                  @if (dialogError()) {
                    <app-banner kind="error" [text]="dialogError()" />
                  }
                  <div class="dialog-actions">
                    <app-busy-button
                      label="Not yet"
                      variant="neutral"
                      [disabled]="service.busy() !== null"
                      (pressed)="closeDialog()"
                    />
                    <app-busy-button
                      label="Confirm receipt"
                      [busy]="service.busy() === 'deliver'"
                      (pressed)="confirmDeliver()"
                    />
                  </div>
                </app-modal-dialog>
              }
              @case ('cancel') {
                <app-modal-dialog
                  heading="Cancel this order?"
                  size="confirm"
                  (closed)="closeDialog()"
                >
                  <p>The order will be closed and can't be reopened.</p>
                  @if (dialogError()) {
                    <app-banner kind="error" [text]="dialogError()" />
                  }
                  <div class="dialog-actions">
                    <app-busy-button
                      label="Keep order"
                      variant="neutral"
                      [disabled]="service.busy() !== null"
                      (pressed)="closeDialog()"
                    />
                    <app-busy-button
                      label="Cancel order"
                      variant="danger"
                      [busy]="service.busy() === 'cancel'"
                      (pressed)="confirmCancel()"
                    />
                  </div>
                </app-modal-dialog>
              }
            }
          }
        }
      }
    } @else {
      <h1 tabindex="-1" class="plain">Order</h1>
      <p>Log in to view this order.</p>
    }
  `,
})
export class OrderDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly pending = inject(PendingCheckoutStore);
  private readonly navigate = inject(CHECKOUT_NAVIGATOR);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly service = inject(OrderDetailService);
  protected readonly extras = inject(OrderExtras);
  protected readonly auth = inject(AuthStore);

  readonly orderId = signal('');
  private readonly now = signal(Date.now());

  readonly carriers = CARRIER_OPTIONS;
  readonly trackingMax = TRACKING_MAX_LENGTH;
  readonly dialog = signal<Command | null>(null);
  readonly dialogError = signal('');
  readonly carrier = signal('');
  readonly trackingCode = signal('');
  readonly shipErrors = signal<ShipErrors>({});

  readonly actions = this.service.actions;
  // PayPal keeps no state for us: the link exists only in the tab that started this checkout.
  readonly approvalLink = computed(() => {
    const order = this.service.order();
    if (!order || !this.actions().cancel || this.expired(order)) return null;
    const pending = this.pending.read();
    return pending?.orderId === order.id ? approvalTarget(pending.approvalUrl) : null;
  });

  constructor() {
    let wasBusy = false;
    effect(() => {
      const busy = !!this.extras.busy();
      const ready = this.service.state() === 'ready';
      if (wasBusy && !busy && ready) this.focus('h1');
      wasBusy = busy;
    });
    let subject = this.auth.identity()?.sub;
    effect(() => {
      const next = this.auth.identity()?.sub;
      if (next !== subject) {
        subject = next;
        this.dialog.set(null);
        if (next && this.orderId()) void this.load();
      }
    });
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.orderId.set(params.get('id') ?? '');
      void this.load();
    });
  }

  openShip(): void {
    if (!this.actions().ship || this.extras.busy()) return;
    this.carrier.set('');
    this.trackingCode.set('');
    this.shipErrors.set({});
    this.dialogError.set('');
    this.dialog.set('ship');
  }

  openDeliver(): void {
    if (!this.actions().deliver || this.extras.busy()) return;
    this.dialogError.set('');
    this.dialog.set('deliver');
  }

  openCancel(): void {
    if (!this.actions().cancel || this.extras.busy()) return;
    this.dialogError.set('');
    this.dialog.set('cancel');
  }

  closeDialog(): void {
    this.dialog.set(null);
  }

  resume(url: string): void {
    const target = approvalTarget(url);
    if (target) this.navigate(target);
  }

  editShip(field: keyof ShipErrors, value: string): void {
    (field === 'carrier' ? this.carrier : this.trackingCode).set(value);
    if (this.shipErrors()[field]) this.validateShipField(field);
  }

  validateShipField(field: keyof ShipErrors): void {
    const message = validateShipping(this.shipForm())[field];
    this.shipErrors.update((errors) => {
      const next = { ...errors };
      if (message) next[field] = message;
      else delete next[field];
      return next;
    });
  }

  async submitShip(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.service.busy() || this.extras.busy()) return;
    const form = this.shipForm();
    const errors = validateShipping(form);
    this.shipErrors.set(errors);
    if (errors.carrier || errors.trackingCode) {
      this.focus(errors.carrier ? '#ship-carrier' : '#ship-tracking');
      return;
    }
    this.dialogError.set('');
    this.settle(await this.service.ship(buildShipRequest(form)));
  }

  async confirmDeliver(): Promise<void> {
    if (this.extras.busy()) return;
    this.dialogError.set('');
    this.settle(await this.service.deliver());
  }

  async confirmCancel(): Promise<void> {
    if (this.extras.busy()) return;
    this.dialogError.set('');
    this.settle(await this.service.cancel());
  }

  private settle(result: CommandResult): void {
    switch (result.kind) {
      case 'done':
      case 'conflict':
        this.dialog.set(null);
        // The control that opened the dialog usually disappears with the status change.
        this.focus('h1');
        break;
      case 'failed':
        this.shipErrors.update((errors) => ({ ...errors, ...result.fieldErrors }));
        if (result.message) {
          if (this.dialog()) this.dialogError.set(result.message);
          else this.service.setNotice({ kind: 'error', text: result.message });
        } else {
          this.focus(result.fieldErrors.carrier ? '#ship-carrier' : '#ship-tracking');
        }
        break;
      case 'ignored':
        break;
    }
  }

  private shipForm() {
    return { carrier: this.carrier(), trackingCode: this.trackingCode() };
  }

  private focus(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  load(): Promise<void> {
    this.dialog.set(null);
    this.extras.visit(this.orderId());
    return this.service.load(this.orderId());
  }

  statusLabel(order: OrderView): string {
    return STATUS_LABEL[order.status];
  }

  statusTone(order: OrderView): 'neutral' | 'accent' | 'danger' | 'warning' {
    switch (order.status) {
      case 'AWAITING_PAYMENT':
        return 'warning';
      case 'PAID':
      case 'SHIPPED':
      case 'DELIVERED':
        return 'accent';
      default:
        return 'neutral';
    }
  }

  carrierLabel(carrier: keyof typeof CARRIER_LABEL): string {
    return CARRIER_LABEL[carrier];
  }

  countryName(code: string): string {
    return EU_COUNTRIES.find((country) => country.code === code.toUpperCase())?.name ?? code;
  }

  paymentLabel(order: OrderView): string {
    return PAYMENT_LABEL[order.paymentMethod];
  }

  stateText(state: StepState): string {
    return STATE_TEXT[state];
  }

  steps(order: OrderView): Step[] {
    return stepsFor(order);
  }

  isBuyer(order: OrderView): boolean {
    return this.service.viewerSub() === order.buyerSub;
  }

  isSeller(order: OrderView): boolean {
    return this.service.viewerSub() === order.sellerSub;
  }

  expired(order: OrderView): boolean {
    return Date.parse(order.expiryAt) <= this.now();
  }

  countdown(order: OrderView): string {
    return countdownText(order.expiryAt, this.now());
  }

  readonly rows = (order: OrderView): MoneyRow[] => [
    { label: 'Item', value: order.itemPrice },
    ...(order.listingType === 'HARDWARE' ? [{ label: 'Shipping', value: order.shippingCost }] : []),
    {
      label: ['AWAITING_PAYMENT', 'CANCELLED'].includes(order.status) ? 'Total' : 'Total paid',
      value: order.pricePaid,
      total: true,
    },
  ];
}
