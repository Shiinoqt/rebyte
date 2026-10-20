import { DatePipe } from '@angular/common';
import { FocusTrapFactory } from '@angular/cdk/a11y';
import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AdminProblemItem, ProblemReason, ProblemStatus } from '../../../core/api/api-models';
import { AuthStore } from '../../../core/auth/auth.store';
import { ModalDialog } from '../../../shared/ui/dialog';
import { IconButton, LoadMore } from '../../../shared/ui/navigation';
import {
  AdminProblemsStore,
  noteError,
  ProblemAction,
  ProblemFilter,
} from './admin-problems.store';

@Component({
  selector: 'app-admin-problems-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, LoadMore, IconButton, ReactiveFormsModule, ModalDialog],
  providers: [AdminProblemsStore],
  styleUrl: './admin-problems.page.css',
  template: `
    <section class="problems-page" aria-labelledby="problems-heading">
      <header class="page-heading">
        <h1 id="problems-heading">Problems</h1>
        @if (store.state() !== 'forbidden') {
          <div class="queue-tools">
            <select
              id="problem-filter"
              aria-label="Problem status"
              [value]="store.filter()"
              [disabled]="store.busyId() !== null"
              (change)="filterChanged($event)"
            >
              <option value="ALL">All active</option>
              <option value="OPEN">Open</option>
              <option value="REFUND_PENDING">Refund pending</option>
            </select>
            <app-icon-button
              label="Refresh problems"
              title="Refresh problems"
              (activated)="store.reload()"
              [disabled]="store.state() === 'loading' || store.busyId() !== null"
            >
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.75"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
                focusable="false"
              >
                <path d="M20 7v5h-5" />
                <path d="M20 12a8 8 0 1 0-2.34 5.66" />
              </svg>
            </app-icon-button>
          </div>
        }
      </header>
      <p class="notice" role="status">{{ store.notice() }}</p>
      @if (!dialog() && store.busyId()) {
        <p role="status">Submitting request...</p>
      }
      @if (store.state() === 'forbidden') {
        <div class="state-panel">
          <h2>Admin access required</h2>
          <p>You do not have permission to view these problems.</p>
        </div>
      } @else if (store.state() === 'loading' || store.state() === 'idle') {
        <p class="state-panel" role="status">Loading problems...</p>
      } @else {
        @if (store.error()) {
          <div class="queue-error" role="alert">
            <p>{{ store.error() }}</p>
            <button
              type="button"
              (click)="store.state() === 'error' ? store.reload() : store.more()"
            >
              Try again
            </button>
          </div>
        }
        @if (store.items().length) {
          <table>
            <caption class="sr-only">
              Reported order problems
            </caption>
            <thead>
              <tr>
                <th scope="col">Order and problem</th>
                <th scope="col">Status</th>
                <th scope="col">Reported</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (item of store.items(); track item.id) {
                <tr>
                  <td class="problem-content">
                    <a class="order-link" [routerLink]="['/orders', item.orderId]">{{
                      item.orderId
                    }}</a>
                    <h2>{{ reasonLabel(item.reason) }}</h2>
                    @if (item.description.length > 160) {
                      <details class="description">
                        <summary>
                          {{ item.description.slice(0, 160) }}... <span>Read more</span>
                        </summary>
                        <p>{{ item.description }}</p>
                      </details>
                    } @else {
                      <p class="description">{{ item.description }}</p>
                    }
                    @if (
                      item.outcome || item.resolvedBy || item.resolvedAt || item.resolutionNote
                    ) {
                      <details class="audit">
                        <summary>Resolution details</summary>
                        <dl>
                          @if (item.outcome) {
                            <div>
                              <dt>Outcome</dt>
                              <dd>{{ item.outcome === 'REFUND' ? 'Refund' : 'Dismissed' }}</dd>
                            </div>
                          }
                          @if (item.resolvedBy) {
                            <div>
                              <dt>Resolved by</dt>
                              <dd>{{ item.resolvedBy }}</dd>
                            </div>
                          }
                          @if (item.resolvedAt) {
                            <div>
                              <dt>Resolved</dt>
                              <dd>{{ item.resolvedAt | date: 'medium' }}</dd>
                            </div>
                          }
                          @if (item.resolutionNote) {
                            <div>
                              <dt>Note</dt>
                              <dd class="resolution-note">{{ item.resolutionNote }}</dd>
                            </div>
                          }
                        </dl>
                      </details>
                    }
                  </td>
                  <td class="status-cell">
                    <span class="status-chip" [class.pending]="item.status === 'REFUND_PENDING'">{{
                      statusLabel(item.status)
                    }}</span>
                  </td>
                  <td class="reported">
                    <span class="mobile-label">Reported </span
                    ><time [attr.datetime]="item.createdAt">{{
                      item.createdAt | date: 'mediumDate'
                    }}</time>
                  </td>
                  <td class="row-actions">
                    @if (item.status === 'OPEN' || item.status === 'REFUND_PENDING') {
                      <button
                        type="button"
                        [disabled]="store.busyId() !== null"
                        [attr.aria-label]="
                          (item.status === 'REFUND_PENDING'
                            ? 'Retry refund for order '
                            : 'Refund in full for order ') + item.orderId
                        "
                        (click)="open(item, 'refund')"
                      >
                        {{ item.status === 'REFUND_PENDING' ? 'Retry refund' : 'Refund in full' }}
                      </button>
                    }
                    @if (item.status === 'OPEN') {
                      <button
                        type="button"
                        class="quiet"
                        [disabled]="store.busyId() !== null"
                        [attr.aria-label]="'Dismiss problem for order ' + item.orderId"
                        (click)="open(item, 'dismiss')"
                      >
                        Dismiss
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        } @else if (store.state() === 'ready') {
          <div class="state-panel">
            <h2>
              {{
                store.cursor()
                  ? 'No problems on this page'
                  : store.filter() === 'ALL'
                    ? 'No open problems'
                    : 'No problems match this filter'
              }}
            </h2>
            @if (store.cursor()) {
              <p>More results are available.</p>
            }
          </div>
        }
        <app-load-more
          [count]="store.items().length"
          noun="problems shown"
          [hasMore]="store.cursor() !== null"
          [loading]="store.loadingMore()"
          [disabled]="store.busyId() !== null"
          (load)="store.more()"
        />
      }
    </section>
    @if (dialog(); as target) {
      <app-modal-dialog
        #resolutionDialog
        [heading]="
          target.action === 'refund' ? 'Refund this order in full?' : 'Dismiss this problem?'
        "
        (closed)="closeDialog()"
      >
        <p class="dialog-order">
          Order <strong>{{ target.item.orderId }}</strong>
        </p>
        @if (target.action === 'refund') {
          <p>
            The full amount paid will be returned to the buyer. This cannot be undone. The refund
            stays pending until payment confirmation.
          </p>
        } @else {
          <p>This closes the problem without a refund. Your reason will be recorded.</p>
        }
        <form (submit)="$event.preventDefault(); confirm()">
          <label for="resolution-note">{{
            target.action === 'refund' ? 'Note (optional)' : 'Reason'
          }}</label>
          <textarea
            id="resolution-note"
            rows="3"
            [formControl]="note"
            [readOnly]="store.busyId() !== null"
            [attr.aria-invalid]="note.invalid && note.touched"
            aria-describedby="note-help note-error"
            [attr.aria-required]="target.action === 'dismiss'"
            autofocus
          ></textarea>
          <p id="note-help" class="helper">Up to 1,000 characters.</p>
          <p id="note-error" class="error" role="alert">
            {{ note.touched ? note.errors?.['note'] : '' }}
          </p>
          <p class="error" role="alert">{{ store.actionError() }}</p>
          <div class="dialog-actions">
            <button
              type="button"
              class="quiet"
              (click)="closeDialog()"
              [disabled]="store.busyId() !== null"
            >
              Cancel
            </button>
            <button
              type="submit"
              class="primary"
              [class.refund-confirm]="target.action === 'refund'"
              [disabled]="store.busyId() !== null || note.invalid"
            >
              {{
                store.busyId()
                  ? 'Submitting...'
                  : target.action === 'refund'
                    ? 'Request full refund'
                    : 'Dismiss problem'
              }}
            </button>
          </div>
        </form>
      </app-modal-dialog>
    }
  `,
})
export class AdminProblemsPage {
  readonly store = inject(AdminProblemsStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthStore);
  private readonly focusTraps = inject(FocusTrapFactory);
  private readonly dialogElement = viewChild<ModalDialog, ElementRef<HTMLElement>>(
    'resolutionDialog',
    { read: ElementRef },
  );
  readonly dialog = signal<{ item: AdminProblemItem; action: ProblemAction; sub: string } | null>(
    null,
  );
  readonly note = new FormControl('', { nonNullable: true });

  constructor() {
    afterRenderEffect((cleanup) => {
      const body = this.dialogElement()?.nativeElement.querySelector<HTMLElement>('dialog > .body');
      if (!body) return;
      // CDK anchors must stay inside the native dialog's top layer to receive focus.
      const trap = this.focusTraps.create(body);
      cleanup(() => trap.destroy());
    });
    effect(() => {
      const sub = this.auth.identity()?.sub;
      const target = this.dialog();
      if (this.store.state() === 'forbidden' || (target && target.sub !== sub)) this.closeDialog();
    });
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const value = params.get('status');
      this.store.select(value === 'OPEN' || value === 'REFUND_PENDING' ? value : 'ALL');
    });
  }

  open(item: AdminProblemItem, action: ProblemAction): void {
    if (
      this.store.busyId() ||
      this.store.state() === 'forbidden' ||
      (item.status !== 'OPEN' && !(action === 'refund' && item.status === 'REFUND_PENDING'))
    )
      return;
    this.store.clearActionError();
    this.dialog.set({ item, action, sub: this.auth.identity()!.sub });
    this.note.setValidators((control) => {
      const message = noteError(control.value, action === 'dismiss');
      return message ? { note: message } : null;
    });
    this.note.reset('');
  }

  closeDialog(): void {
    this.dialog.set(null);
    this.note.reset('');
  }

  confirm(): void {
    const target = this.dialog();
    this.note.markAsTouched();
    if (!target || this.note.invalid || this.store.busyId()) return;
    this.store.resolve(target.item.id, target.action, this.note.value, () => this.closeDialog());
  }

  filterChanged(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    const filter: ProblemFilter = value === 'OPEN' || value === 'REFUND_PENDING' ? value : 'ALL';
    this.store.select(filter);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { status: filter === 'ALL' ? null : filter },
      queryParamsHandling: 'merge',
    });
  }

  reasonLabel(reason: ProblemReason): string {
    return {
      NOT_RECEIVED: 'Not received',
      NOT_AS_DESCRIBED: 'Not as described',
      DAMAGED: 'Damaged item',
      LICENCE_NOT_WORKING: 'Licence not working',
      OTHER: 'Other problem',
    }[reason];
  }

  statusLabel(status: ProblemStatus): string {
    return {
      OPEN: 'Open',
      REFUND_PENDING: 'Refund pending',
      RESOLVED: 'Resolved',
      WITHDRAWN: 'Withdrawn',
    }[status];
  }
}
