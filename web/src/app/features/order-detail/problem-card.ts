import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  Injectable,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Observable, Subscription, take } from 'rxjs';
import {
  CreateReviewRequest,
  OrderView,
  ProblemReason,
  ReportProblemRequest,
  ResolveProblemRequest,
} from '../../core/api/api-models';
import { OrderApi } from '../../core/api/order-api';
import { AuthStore } from '../../core/auth/auth.store';
import { EurPipe, StatusChip } from '../../shared/ui/content';
import { ModalDialog } from '../../shared/ui/dialog';
import { BusyButton } from '../../shared/ui/feedback';
import { FieldOption, FormField } from '../../shared/ui/forms';
import { OrderDetailService } from './order-detail.service';

export const REASONS: Readonly<Record<ProblemReason, string>> = {
  NOT_RECEIVED: "I didn't receive it",
  NOT_AS_DESCRIBED: 'Not as described',
  DAMAGED: 'Arrived damaged',
  LICENCE_NOT_WORKING: "Licence key doesn't work",
  OTHER: 'Something else',
};
export const ORDER_CARD_STYLES = `
  :host { display:block; min-width:0; }
  :host(:empty) { display:none; }
  .optional-card { padding:var(--space-lg); border:1px solid var(--border); border-radius:var(--radius-lg); background:var(--surface); display:grid; gap:var(--space-md); min-width:0; }
  h2 { margin:0; font-size:var(--text-heading); }
  h3 { margin:0; font-size:var(--text-body-size); }
  app-status-chip { justify-self:start; }
  .title-row { display:flex; align-items:center; flex-wrap:wrap; gap:var(--space-sm); }
  p { margin:0; overflow-wrap:anywhere; }
  .prose { white-space:pre-wrap; }
  .note { color:var(--text-muted); font-size:var(--text-caption); line-height:var(--leading-body); }
  .problem-open { border-color:var(--danger-border); }
  .dialog-form { display:flex; flex-direction:column; gap:var(--space-md); }
  .dialog-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:var(--space-sm); }
  .field-error { color:var(--danger-strong); font-size:var(--text-caption); }
  .wide { display:block; }
  .wide ::ng-deep button { width:100%; }
  .rail-help { display:grid; gap:var(--space-sm); padding-top:var(--space-md); border-top:1px solid var(--border); }
`;
const CONTROLS = /[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/;
const trim = (text: string) => text.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '');
export function validateReport(request: ReportProblemRequest): {
  reason?: string;
  description?: string;
} {
  const errors: { reason?: string; description?: string } = {};
  if (!Object.hasOwn(REASONS, request.reason)) errors.reason = 'Choose a reason.';
  const description = trim(request.description);
  if (!description) errors.description = 'Describe the problem.';
  else if (description.length > 2000) errors.description = 'Use at most 2000 characters.';
  else if (CONTROLS.test(request.description))
    errors.description = 'Remove unsupported control characters.';
  return errors;
}
export function validateReview(request: CreateReviewRequest): {
  rating?: string;
  comment?: string;
} {
  const errors: { rating?: string; comment?: string } = {};
  if (!Number.isInteger(request.rating) || request.rating < 1 || request.rating > 5)
    errors.rating = 'Choose a rating from 1 to 5 stars.';
  if (trim(request.comment ?? '').length > 2000) errors.comment = 'Use at most 2000 characters.';
  else if (CONTROLS.test(request.comment ?? ''))
    errors.comment = 'Remove unsupported control characters.';
  return errors;
}
export function validateRefund(request: ResolveProblemRequest): { note?: string } {
  if (trim(request.note ?? '').length > 1000) return { note: 'Use at most 1000 characters.' };
  if (CONTROLS.test(request.note ?? '')) return { note: 'Remove unsupported control characters.' };
  return {};
}
type ExtraCommand = 'report' | 'withdraw' | 'refund' | 'review';
const ERROR_COPY: Readonly<Record<string, string>> = {
  'problem-already-reported': 'A problem was already reported for this order.',
  'problem-not-reportable': "A problem can't be reported for this order now.",
  'problem-not-open': 'This report is no longer open.',
  'review-already-exists': 'You already reviewed this order.',
  'order-not-reviewable': 'You can review this order once it is delivered.',
  'order-action-forbidden': 'You cannot perform this action on this order.',
  'validation-failed': 'Check your entries and try again.',
};

@Injectable()
export class OrderExtras {
  private readonly api = inject(OrderApi);
  private readonly detail = inject(OrderDetailService);
  private readonly auth = inject(AuthStore);
  private request: Subscription | null = null;
  private generation = 0;
  private routeId = '';
  readonly busy = signal<ExtraCommand | null>(null);
  readonly error = signal('');
  readonly actions = computed(() => {
    const order = this.detail.order();
    const sub = this.auth.identity()?.sub;
    const buyer = !!sub && sub === order?.buyerSub;
    const seller = !!sub && sub === order?.sellerSub;
    return {
      report:
        buyer &&
        !!order &&
        ['PAID', 'SHIPPED', 'DELIVERED'].includes(order.status) &&
        !order.problem,
      withdraw: buyer && order?.problem?.status === 'OPEN',
      refund:
        seller && !!order?.problem && ['OPEN', 'REFUND_PENDING'].includes(order.problem.status),
      review: buyer && order?.status === 'DELIVERED' && !order.review,
    };
  });
  constructor() {
    const unregister = this.auth.registerCleanup(() => this.clear());
    inject(DestroyRef).onDestroy(() => {
      unregister();
      this.clear();
    });
    let subject = this.auth.identity()?.sub;
    effect(() => {
      const next = this.auth.identity()?.sub;
      if (next !== subject) {
        subject = next;
        this.clear();
      }
    });
  }
  report(request: ReportProblemRequest): void {
    if (Object.keys(validateReport(request)).length) return;
    this.run('report', (id) =>
      this.api.reportProblem(id, { ...request, description: trim(request.description) }),
    );
  }
  visit(id: string): void {
    if (id !== this.routeId) {
      this.routeId = id;
      this.clear();
    }
  }
  withdraw(): void {
    this.run('withdraw', (id) => this.api.withdrawProblem(id));
  }
  refund(request: ResolveProblemRequest): void {
    if (Object.keys(validateRefund(request)).length) return;
    this.run('refund', (id) =>
      this.api.refundProblem(id, { note: trim(request.note ?? '') || null }),
    );
  }
  review(request: CreateReviewRequest): void {
    if (Object.keys(validateReview(request)).length) return;
    this.run('review', (id) =>
      this.api.createReview(id, { ...request, comment: trim(request.comment ?? '') || null }),
    );
  }
  private clear(): void {
    ++this.generation;
    this.request?.unsubscribe();
    this.request = null;
    this.busy.set(null);
    this.error.set('');
  }
  private run(command: ExtraCommand, send: (id: string) => Observable<unknown>): void {
    const order = this.detail.order();
    if (!order || this.busy() || this.detail.busy() || !this.actions()[command]) return;
    const generation = ++this.generation;
    const sub = this.auth.identity()?.sub;
    this.busy.set(command);
    this.error.set('');
    const current = () =>
      generation === this.generation &&
      sub === this.auth.identity()?.sub &&
      this.detail.order()?.id === order.id;
    const reconcile = (message: string) => {
      if (!current()) return;
      // Refetch even after uncertain writes; a failed response does not prove the command failed.
      void this.detail.load(order.id).finally(() => {
        if (generation !== this.generation || sub !== this.auth.identity()?.sub) return;
        this.error.set(message);
        this.busy.set(null);
      });
    };
    this.request = send(order.id)
      .pipe(take(1))
      .subscribe({
        next: () => reconcile(''),
        error: (error: unknown) => {
          const code =
            error && typeof error === 'object' && 'code' in error ? String(error.code) : '';
          reconcile(
            ERROR_COPY[code] ??
              "We couldn't confirm that action. Check the current order before trying again.",
          );
        },
      });
  }
}

@Component({
  selector: 'app-problem-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BusyButton, FormField, ModalDialog, StatusChip, EurPipe],
  styles: ORDER_CARD_STYLES + `
    .refund-status { display:inline-flex; align-items:center; gap:var(--space-xs); padding:var(--space-xs) var(--space-sm); border:1px solid var(--border); border-radius:var(--radius-pill); background:var(--surface); color:var(--warning); font-size:var(--text-caption); }
    .refund-status svg { width:14px; height:14px; flex-shrink:0; }
  `,
  template: `
    @if (entry()) {
      @if (work.actions().report) {
        <div class="rail-help">
          <p class="note">Something wrong with this order?</p>
          <app-busy-button
            class="wide"
            label="Report a problem"
            variant="neutral"
            [disabled]="!!work.busy()"
            (pressed)="open('report')"
          />
        </div>
      }
    } @else if (order().problem; as problem) {
      <section class="optional-card" [class.problem-open]="problem.status === 'OPEN'">
        <div class="title-row">
          @if (problem.status === 'WITHDRAWN') {
            <h2>Withdrawn report</h2>
          } @else if (problem.status === 'RESOLVED') {
            <h2>Resolved report</h2>
          } @else {
            <h2>{{ isSeller() ? 'The buyer reported a problem' : 'Reported problem' }}</h2>
            @if (problem.status === 'REFUND_PENDING') {
              <span class="refund-status">
                <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 7v5l3 2" />
                </svg>
                Refund pending
              </span>
            } @else {
              <app-status-chip [text]="statusText(problem.status)" />
            }
          }
        </div>
        <h3>{{ reasons[problem.reason] }}</h3>
        <p class="prose">{{ problem.description }}</p>
        @if (problem.resolutionNote) {
          <p class="prose">{{ problem.resolutionNote }}</p>
        }
        @if (problem.outcome) {
          <p>Resolution: {{ problem.outcome === 'REFUND' ? 'Refund' : 'Report dismissed' }}</p>
        }
        @if (work.actions().withdraw) {
          @if (withdrawConfirm()) {
            <p>Withdraw your report?</p>
            <div class="dialog-actions">
              <app-busy-button
                label="Keep"
                variant="neutral"
                [disabled]="!!work.busy()"
                (pressed)="withdrawConfirm.set(false)"
              />
              <app-busy-button
                label="Withdraw"
                [busy]="work.busy() === 'withdraw'"
                (pressed)="work.withdraw()"
              />
            </div>
          } @else {
            <app-busy-button
              label="Withdraw report"
              variant="neutral"
              [disabled]="!!work.busy()"
              (pressed)="withdrawConfirm.set(true)"
            />
          }
        }
        @if (work.actions().refund) {
          <app-busy-button
            [label]="(problem.status === 'REFUND_PENDING' ? 'Retry refund ' : 'Refund ') + (order().pricePaid | eur)"
            variant="danger"
            [busy]="work.busy() === 'refund'"
            [disabled]="!!work.busy()"
            (pressed)="open('refund')"
          />
          @if (problem.status === 'REFUND_PENDING') {
            <p class="note">The refund is still processing. You can retry the request.</p>
          }
        }
      </section>
    }
    @if (dialog(); as mode) {
      <app-modal-dialog
        [heading]="mode === 'report' ? 'Report a problem' : order().problem?.status === 'REFUND_PENDING' ? 'Retry this refund?' : 'Refund this order in full?'"
        (closed)="dialog.set(null)"
      >
        <form class="dialog-form" novalidate (submit)="submit($event)">
          @if (mode === 'report') {
            <app-form-field
              [id]="'problem-reason'"
              label="Reason"
              kind="select"
              [required]="true"
              [options]="options()"
              [value]="reason()"
              [error]="errors().reason ?? ''"
              [disabled]="!!work.busy()"
              (valueChange)="reason.set($event)"
            />
            <app-form-field
              [id]="'problem-description'"
              label="Description"
              kind="textarea"
              [required]="true"
              [value]="description()"
              [hint]="description().length + ' / 2000 characters'"
              [error]="errors().description ?? ''"
              [disabled]="!!work.busy()"
              (valueChange)="description.set($event)"
              (blurred)="validate()"
            />
          } @else {
            <p>
              The buyer is refunded through PayPal and the problem is closed. This can't be undone.
            </p>
            <app-form-field
              [id]="'refund-note'"
              label="Note (optional)"
              kind="textarea"
              [value]="note()"
              [hint]="note().length + ' / 1000 characters'"
              [error]="errors().note ?? ''"
              [disabled]="!!work.busy()"
              (valueChange)="note.set($event)"
              (blurred)="validate()"
            />
          }
          <div class="dialog-actions">
            <app-busy-button
              label="Cancel"
              variant="neutral"
              [disabled]="!!work.busy()"
              (pressed)="dialog.set(null)"
            />
            <app-busy-button
              type="submit"
              [label]="mode === 'report' ? 'Send report' : order().problem?.status === 'REFUND_PENDING' ? 'Retry refund ' + (order().pricePaid | eur) : 'Refund in full'"
              [variant]="mode === 'report' ? 'primary' : 'danger'"
              [busy]="!!work.busy()"
            />
          </div>
        </form>
      </app-modal-dialog>
    }
  `,
})
export class ProblemCard {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  readonly order = input.required<OrderView>();
  /** Renders only the report entry point, for the order summary rail. */
  readonly entry = input(false);
  readonly work = inject(OrderExtras);
  private readonly auth = inject(AuthStore);
  readonly reasons = REASONS;
  readonly dialog = signal<'report' | 'refund' | null>(null);
  readonly withdrawConfirm = signal(false);
  readonly reason = signal('');
  readonly description = signal('');
  readonly note = signal('');
  readonly errors = signal<{ reason?: string; description?: string; note?: string }>({});
  readonly isSeller = computed(() => this.auth.identity()?.sub === this.order().sellerSub);
  readonly options = computed<readonly FieldOption[]>(() =>
    Object.entries(REASONS)
      .filter(([key]) =>
        this.order().listingType === 'SOFTWARE'
          ? !['NOT_RECEIVED', 'DAMAGED'].includes(key)
          : key !== 'LICENCE_NOT_WORKING',
      )
      .map(([value, label]) => ({ value, label })),
  );
  statusText(status: string): string {
    return (
      {
        OPEN: 'Open',
        REFUND_PENDING: 'Refund pending',
        RESOLVED: 'Resolved',
        WITHDRAWN: 'Withdrawn',
      } as Record<string, string>
    )[status];
  }
  open(mode: 'report' | 'refund'): void {
    if (!this.work.actions()[mode]) return;
    this.reason.set('');
    this.description.set('');
    this.note.set('');
    this.errors.set({});
    this.dialog.set(mode);
  }
  validate(): void {
    this.errors.set(
      this.dialog() === 'report'
        ? validateReport({
            reason: this.reason() as ProblemReason,
            description: this.description(),
          })
        : validateRefund({ note: this.note() }),
    );
  }
  submit(event: Event): void {
    event.preventDefault();
    this.validate();
    if (Object.keys(this.errors()).length) {
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(),
        { injector: this.injector },
      );
      return;
    }
    if (this.dialog() === 'report')
      this.work.report({ reason: this.reason() as ProblemReason, description: this.description() });
    else this.work.refund({ note: this.note() });
  }
}
