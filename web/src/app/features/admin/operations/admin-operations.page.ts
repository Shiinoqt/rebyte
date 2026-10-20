import { DatePipe } from '@angular/common';
import { FocusTrapFactory } from '@angular/cdk/a11y';
import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { StuckOrderItem } from '../../../core/api/api-models';
import { AuthStore } from '../../../core/auth/auth.store';
import { ModalDialog } from '../../../shared/ui/dialog';
import { AdminOperationsService, REPLAY_QUEUES, ReplayService } from './admin-operations.service';

type Decision = { sub: string } & (
  | { kind: 'rebuild' }
  | { kind: 'refund'; item: StuckOrderItem }
  | { kind: 'replay'; service: ReplayService; queue: string }
);
@Component({
  selector: 'app-admin-operations-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, ReactiveFormsModule, ModalDialog],
  providers: [AdminOperationsService],
  styleUrl: './admin-operations.page.css',
  template: `
    <section class="operations-page" aria-labelledby="operations-heading">
      <header class="page-heading">
        <h1 id="operations-heading">Operations</h1>
        <p class="muted">Search maintenance, order reconciliation and message recovery.</p>
      </header>
      @if (!store.allowed()) {
        <div class="panel">
          <h2>Admin access required</h2>
          <p>You do not have permission to use these operations.</p>
        </div>
      } @else {
        @if (store.busy() && !dialog()) {
          <p class="feedback" role="status">Submitting {{ store.busy() }} request...</p>
        }
        @if (store.message() && !dialog()) {
          <p class="error feedback" role="alert">{{ store.message() }}</p>
        }
        <div class="maintenance-grid">
          <section class="panel" aria-labelledby="search-heading">
            <div class="section-heading">
              <h2 id="search-heading">Search index</h2>
              <span class="chip">{{ searchLabel() }}</span>
            </div>
            @if (store.search(); as status) {
              <dl class="timestamps">
                <div>
                  <dt>Started</dt>
                  <dd>
                    {{ status.startedAt ? (status.startedAt | date: 'medium') : 'Not recorded' }}
                  </dd>
                </div>
                <div>
                  <dt>Completed</dt>
                  <dd>
                    {{
                      status.completedAt ? (status.completedAt | date: 'medium') : 'Not recorded'
                    }}
                  </dd>
                </div>
              </dl>
              @if (status.running) {
                <p class="muted">Results can be incomplete while the rebuild runs.</p>
              }
              @if (status.error && !status.running) {
                <p class="error">
                  The last rebuild failed. Review the service logs before retrying.
                </p>
              }
            }
            @if (store.statusLoading()) {
              <p role="status">Checking rebuild status...</p>
            }
            @if (store.searchError()) {
              <p class="error" role="alert">{{ store.searchError() }} Status is not current.</p>
            }
            @if (store.pollExpired()) {
              <p role="status">
                Automatic monitoring stopped after five minutes. Refresh status to check again; the
                rebuild may still be running.
              </p>
            }
            <div class="actions">
              <button
                class="primary"
                (click)="openRebuild()"
                [disabled]="
                  !!store.busy() ||
                  store.statusLoading() ||
                  !store.search() ||
                  !!store.search()?.running ||
                  !!store.searchError()
                "
              >
                Rebuild index
              </button>
              <button
                (click)="store.refreshSearch()"
                [disabled]="store.statusLoading() || store.busy() === 'rebuild'"
              >
                Refresh status
              </button>
            </div>
            <div class="drift-section">
              <div class="section-heading">
                <h3>Index drift</h3>
                <button (click)="store.checkDrift()" [disabled]="!!store.busy()">
                  {{ store.driftLoading() ? 'Checking...' : 'Check drift' }}
                </button>
              </div>
              <p class="muted">
                Compare listings with the search index. This check does not repair differences.
              </p>
              @if (store.driftError()) {
                <p class="error" role="alert">{{ store.driftError() }}</p>
              }
              @if (store.drift(); as report) {
                <dl class="drift-counts" aria-label="Drift results">
                  <div>
                    <dd>{{ report.missingFromIndex.length }}</dd>
                    <dt>Missing from index</dt>
                  </div>
                  <div>
                    <dd>{{ report.missingFromSource.length }}</dd>
                    <dt>Missing from source</dt>
                  </div>
                  <div>
                    <dd>{{ staleRows().length }}</dd>
                    <dt>Stale documents</dt>
                  </div>
                </dl>
                @if (
                  !report.missingFromIndex.length &&
                  !report.missingFromSource.length &&
                  !staleRows().length
                ) {
                  <p role="status">No drift found.</p>
                } @else {
                  <details>
                    <summary>View drift details</summary>
                    @if (report.missingFromIndex.length) {
                      <h4>Missing from index</h4>
                      <ul>
                        @for (id of report.missingFromIndex; track id) {
                          <li class="mono">{{ id }}</li>
                        }
                      </ul>
                    }
                    @if (report.missingFromSource.length) {
                      <h4>Missing from source</h4>
                      <ul>
                        @for (id of report.missingFromSource; track id) {
                          <li class="mono">{{ id }}</li>
                        }
                      </ul>
                    }
                    @if (staleRows().length) {
                      <h4>Stale documents</h4>
                      <ul>
                        @for (row of staleRows(); track row.id) {
                          <li>
                            <span class="mono">{{ row.id }}</span
                            >: source sequence {{ row.sourceSequence }}, indexed sequence
                            {{ row.indexedSequence }}
                          </li>
                        }
                      </ul>
                    }
                  </details>
                }
              }
            </div>
          </section>
          <section class="panel" aria-labelledby="replay-heading">
            <h2 id="replay-heading">Dead-letter replay</h2>
            <p class="muted">
              Return one parked message to processing. Resolve its original failure before
              replaying.
            </p>
            @for (service of services; track service) {
              <div class="queue-group">
                <label [for]="service + '-queue'">{{
                  service === 'order' ? 'Order queue' : 'Messaging queue'
                }}</label>
                <select
                  [id]="service + '-queue'"
                  [value]="selectedQueues()[service]"
                  [disabled]="!!store.busy()"
                  (change)="selectQueue(service, $event)"
                >
                  @for (queue of queues[service]; track queue) {
                    <option [value]="queue">{{ queue }}</option>
                  }
                </select>
                <p class="queue-name mono">{{ selectedQueues()[service] }}</p>
                <button
                  (click)="openReplay(service)"
                  [disabled]="!!store.busy()"
                  [attr.aria-label]="'Replay one ' + service + ' message'"
                >
                  Replay one
                </button>
              </div>
            }
            @if (store.replayResult(); as result) {
              <div class="result" role="status">
                <p>
                  {{
                    result.outcome === 'empty'
                      ? 'Queue is empty. No message was replayed.'
                      : 'One message returned to processing.'
                  }}
                </p>
                <p class="mono">{{ result.queue }}</p>
                @if (result.auditId) {
                  <p>
                    Audit ID <span class="mono">{{ result.auditId }}</span>
                  </p>
                } @else if (result.outcome === 'replayed') {
                  <p class="muted">No audit ID was returned.</p>
                }
              </div>
            }
          </section>
        </div>
        <section class="panel orders-panel" aria-labelledby="orders-heading">
          <div class="section-heading">
            <h2 id="orders-heading">Stuck orders</h2>
            <button
              (click)="store.refreshOrders()"
              [disabled]="store.ordersLoading() || store.busy() === 'refund'"
            >
              Refresh orders
            </button>
          </div>
          <p class="muted">
            Inspect payment and checkout issues. Refund eligibility is checked against the current
            order state.
          </p>
          @if (store.refundResult(); as result) {
            <div class="result" role="status">
              Refund response for <span class="mono">{{ result.orderId }}</span
              >: <strong>{{ result.status }}</strong
              >. Check the order for its final state.
            </div>
          }
          @if (store.ordersError()) {
            <p class="error feedback" role="alert">{{ store.ordersError() }}</p>
          }
          @if (store.ordersLoading()) {
            <p class="feedback" role="status">Loading stuck orders...</p>
          }
          @if (store.orders().length) {
            <ul class="order-list">
              @for (order of store.orders(); track order.orderId) {
                <li class="order-row">
                  <div>
                    <a class="mono" [routerLink]="['/orders', order.orderId]">{{
                      order.orderId
                    }}</a>
                    <p class="classification">{{ categoryLabel(order.category) }}</p>
                  </div>
                  <dl>
                    <div>
                      <dt>Created</dt>
                      <dd>{{ order.createdAt | date: 'medium' }}</dd>
                    </div>
                    <div>
                      <dt>Expires</dt>
                      <dd>{{ order.expiryAt | date: 'medium' }}</dd>
                    </div>
                  </dl>
                  <dl>
                    <div>
                      <dt>PayPal status</dt>
                      <dd>{{ order.lastPayPalStatus || 'Not reported' }}</dd>
                    </div>
                    <div>
                      <dt>Cancellation</dt>
                      <dd class="mono">{{ order.cancellationReason || 'None' }}</dd>
                    </div>
                  </dl>
                  <button
                    class="refund"
                    (click)="openRefund(order)"
                    [disabled]="!!store.busy()"
                    [attr.aria-label]="'Refund in full for ' + order.orderId"
                  >
                    Refund in full
                  </button>
                </li>
              }
            </ul>
          } @else if (!store.ordersLoading() && !store.ordersError()) {
            <div class="empty">
              <h3>{{ store.nextCursor() ? 'No matches on this page' : 'No stuck orders' }}</h3>
              <p class="muted">
                {{
                  store.nextCursor()
                    ? 'More candidates are available. Continue to the next page.'
                    : 'No checkout or payment issues were returned.'
                }}
              </p>
            </div>
          }
          @if (store.nextCursor()) {
            <button
              class="load-more"
              (click)="store.loadMore()"
              [disabled]="store.ordersLoading() || store.busy() === 'refund'"
            >
              Load more
            </button>
          }
        </section>
      }
    </section>
    @if (dialog(); as target) {
      <app-modal-dialog
        #confirmation
        [heading]="dialogHeading()"
        size="confirm"
        (closed)="closeDialog()"
      >
        <p>
          {{
            target.kind === 'rebuild'
              ? 'Results can be incomplete while it runs.'
              : target.kind === 'refund'
                ? 'Request a full refund for this order. This action cannot be undone here, and final confirmation may arrive later.'
                : 'Replay one message from this queue. Processing can trigger order or notification actions.'
          }}
        </p>
        @if (target.kind === 'refund') {
          <p class="mono">{{ target.item.orderId }}</p>
          <label for="refund-reason">Refund reason <span class="muted">(required)</span></label>
          <textarea
            id="refund-reason"
            rows="3"
            [formControl]="reason"
            [readOnly]="!!store.busy()"
            [attr.aria-invalid]="!!store.reasonError()"
            [attr.aria-describedby]="store.reasonError() ? 'reason-error' : null"
          ></textarea>
          @if (store.reasonError()) {
            <p id="reason-error" class="error" role="alert">{{ store.reasonError() }}</p>
          }
        }
        @if (target.kind === 'replay') {
          <p class="mono">{{ target.queue }}</p>
        }
        @if (store.message()) {
          <p class="error" role="alert">{{ store.message() }}</p>
        }
        @if (store.busy()) {
          <p role="status">Submitting request. Closing this dialog does not cancel it.</p>
        }
        <div class="dialog-actions">
          <button (click)="closeDialog()">{{ store.busy() ? 'Close' : 'Cancel' }}</button>
          <button
            [class.primary]="target.kind !== 'refund'"
            [class.refund]="target.kind === 'refund'"
            [disabled]="!!store.busy()"
            (click)="confirm()"
          >
            {{
              store.busy()
                ? 'Submitting...'
                : target.kind === 'rebuild'
                  ? 'Rebuild index'
                  : target.kind === 'refund'
                    ? 'Request full refund'
                    : 'Replay one message'
            }}
          </button>
        </div>
      </app-modal-dialog>
    }
  `,
})
export class AdminOperationsPage {
  readonly store = inject(AdminOperationsService);
  private readonly auth = inject(AuthStore);
  private readonly traps = inject(FocusTrapFactory);
  private readonly dialogElement = viewChild<ModalDialog, ElementRef<HTMLElement>>('confirmation', {
    read: ElementRef,
  });
  readonly queues = REPLAY_QUEUES;
  readonly services = ['order', 'messaging'] as const;
  readonly selectedQueues = signal<Record<ReplayService, string>>({
    order: REPLAY_QUEUES.order[0],
    messaging: REPLAY_QUEUES.messaging[0],
  });
  readonly dialog = signal<Decision | null>(null);
  readonly reason = new FormControl('', { nonNullable: true });
  readonly staleRows = computed(() =>
    Object.entries(this.store.drift()?.staleDocuments ?? {}).map(([id, lag]) => ({ id, ...lag })),
  );
  readonly searchLabel = computed(() => {
    const status = this.store.search();
    if (this.store.searchError()) return 'Status unavailable';
    return !status
      ? 'Checking status'
      : status.running
        ? 'Running'
        : status.error
          ? 'Failed'
          : status.completedAt
            ? 'Completed'
            : status.startedAt
              ? 'Stopped'
              : 'Never run';
  });
  readonly dialogHeading = computed(() =>
    this.dialog()?.kind === 'rebuild'
      ? 'Rebuild the search index?'
      : this.dialog()?.kind === 'refund'
        ? 'Refund this order in full?'
        : 'Replay this message?',
  );
  constructor() {
    afterRenderEffect((cleanup) => {
      const body = this.dialogElement()?.nativeElement.querySelector<HTMLElement>('dialog > .body');
      if (!body) return;
      const trap = this.traps.create(body);
      cleanup(() => trap.destroy());
    });
    effect(() => {
      const sub = this.auth.identity()?.sub,
        target = this.dialog();
      if (!this.store.allowed() || (target && target.sub !== sub)) this.closeDialog();
    });
  }
  openRebuild(): void {
    this.open({ kind: 'rebuild', sub: this.auth.identity()?.sub ?? '' });
  }
  openRefund(item: StuckOrderItem): void {
    this.open({ kind: 'refund', item, sub: this.auth.identity()?.sub ?? '' });
  }
  openReplay(service: ReplayService): void {
    this.open({
      kind: 'replay',
      service,
      queue: this.selectedQueues()[service],
      sub: this.auth.identity()?.sub ?? '',
    });
  }
  closeDialog(): void {
    this.dialog.set(null);
    this.reason.reset('');
  }
  confirm(): void {
    const target = this.dialog();
    if (
      !target ||
      !this.store.allowed() ||
      this.store.busy() ||
      target.sub !== this.auth.identity()?.sub
    )
      return;
    const done = () => {
      if (this.dialog() === target) this.closeDialog();
    };
    if (target.kind === 'rebuild') this.store.rebuild(true, done);
    else if (target.kind === 'replay') this.store.replay(target.service, target.queue, true, done);
    else {
      this.store.refund(target.item.orderId, this.reason.value, true, done);
      if (this.store.reasonError())
        this.dialogElement()?.nativeElement.querySelector<HTMLTextAreaElement>('textarea')?.focus();
    }
  }
  selectQueue(service: ReplayService, event: Event): void {
    const queue = (event.target as HTMLSelectElement).value;
    if (!this.store.busy() && (this.queues[service] as readonly string[]).includes(queue))
      this.selectedQueues.update((value) => ({ ...value, [service]: queue }));
  }
  categoryLabel(category: StuckOrderItem['category']): string {
    return {
      failed_create: 'Checkout creation failed',
      captured_awaiting_webhook: 'Captured, awaiting confirmation',
      awaiting_timeout: 'Awaiting payment timeout',
    }[category];
  }
  private open(target: Decision): void {
    if (!this.store.allowed() || this.store.busy()) return;
    this.store.clearActionError();
    this.reason.reset('');
    this.dialog.set(target);
  }
}
