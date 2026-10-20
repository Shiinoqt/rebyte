import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injectable,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

let nextReasonId = 0;

@Component({
  selector: 'app-skeleton',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './feedback.css',
  host: {
    'data-ui': 'skeleton',
    role: 'status',
    'aria-busy': 'true',
    '[attr.aria-label]': 'label()',
  },
  template: `<div aria-hidden="true" [class]="'skeleton-' + shape()">
    <div class="skeleton-image"></div>
    <div class="skeleton-line"></div>
    <div class="skeleton-line short"></div>
  </div>`,
})
export class Skeleton {
  readonly label = input('Loading');
  readonly shape = input<'card' | 'line' | 'avatar'>('card');
}

@Component({
  selector: 'app-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './feedback.css',
  host: {
    'data-ui': 'banner',
    '[attr.role]': "kind() === 'info' ? 'status' : 'alert'",
    '[attr.data-kind]': 'kind()',
  },
  template: `<span class="banner-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24">
        @if (kind() === 'info') {
          <path d="M12 16v-4M12 8h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z" />
        } @else {
          <path
            d="M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"
          />
        }
      </svg>
    </span>
    <span class="visually-hidden">{{ severity() }}:</span>
    <span class="banner-text">{{ text() }}</span>
    @if (actionLabel()) {
      <button type="button" (click)="action.emit()">{{ actionLabel() }}</button>
    }`,
})
export class Banner {
  readonly kind = input<'info' | 'warning' | 'error'>('info');
  readonly text = input.required<string>();
  readonly actionLabel = input('');
  readonly action = output<void>();
  readonly severity = computed(
    () => ({ info: 'Information', warning: 'Warning', error: 'Error' })[this.kind()],
  );
}

@Component({
  selector: 'app-empty-state',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './feedback.css',
  host: { 'data-ui': 'empty-state', class: 'state' },
  template: `<h2>{{ title() }}</h2>
    <p>{{ text() }}</p>
    @if (actionLabel()) {
      <button type="button" (click)="action.emit()">{{ actionLabel() }}</button>
    }`,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly text = input('');
  readonly actionLabel = input('');
  readonly action = output<void>();
}

@Component({
  selector: 'app-error-state',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './feedback.css',
  host: { 'data-ui': 'error-state', role: 'alert', class: 'state' },
  template: `<h2>{{ title() }}</h2>
    <p>{{ text() }}</p>
    <button type="button" (click)="retry.emit()">{{ actionLabel() }}</button>`,
})
export class ErrorState {
  readonly title = input("We couldn't load this.");
  readonly text = input('Check your connection and try again.');
  readonly actionLabel = input('Try again');
  readonly retry = output<void>();
}

@Component({
  selector: 'app-busy-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './feedback.css',
  host: { 'data-ui': 'busy-button' },
  template: `<button
      [type]="type()"
      [class]="variant()"
      [disabled]="busy() || disabled()"
      [attr.aria-disabled]="busy() || disabled()"
      [attr.aria-busy]="busy()"
      [attr.aria-describedby]="disabled() && reason() ? reasonId : null"
      (click)="pressed.emit()"
    >
      @if (busy()) {
        <span class="spinner" aria-hidden="true"></span>
      }
      {{ label() }}
    </button>
    @if (disabled() && reason()) {
      <p class="reason" [id]="reasonId">{{ reason() }}</p>
    }`,
})
export class BusyButton {
  readonly label = input.required<string>();
  readonly busy = input(false);
  readonly disabled = input(false);
  readonly reason = input('');
  readonly type = input<'button' | 'submit'>('button');
  readonly variant = input<
    'primary' | 'secondary' | 'neutral' | 'danger' | 'danger-outline' | 'link'
  >('primary');
  readonly pressed = output<void>();
  readonly reasonId = `busy-button-reason-${nextReasonId++}`;
}

export interface Toast {
  text: string;
  kind: 'success' | 'info' | 'error';
  action?: { label: string; run: () => void };
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly active = signal<(Toast & { id: number }) | null>(null);
  readonly current = this.active.asReadonly();
  private readonly queue: Toast[] = [];
  private timer: ReturnType<typeof setTimeout> | undefined;
  private nextId = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timer);
      this.queue.length = 0;
    });
  }

  show(toast: Toast): void {
    this.queue.push(toast);
    if (!this.active()) this.advance();
  }

  dismiss(): void {
    clearTimeout(this.timer);
    this.advance();
  }

  private advance(): void {
    const next = this.queue.shift();
    this.active.set(next ? { ...next, id: this.nextId++ } : null);
    if (next) this.timer = setTimeout(() => this.dismiss(), next.kind === 'error' ? 8000 : 4000);
  }
}

@Component({
  selector: 'app-toast-host',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './feedback.css',
  host: { 'data-ui': 'toast-host', class: 'toast' },
  template: `@for (toast of visible(); track toast.id) {
      <div class="toast-row" animate.enter="toast-enter" animate.leave="toast-leave">
        <span class="toast-icon" [attr.data-kind]="toast.kind" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            @switch (toast.kind) {
              @case ('success') {
                <path d="M20 6 9 17l-5-5" />
              }
              @case ('error') {
                <path
                  d="M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"
                />
              }
              @default {
                <path d="M12 16v-4M12 8h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z" />
              }
            }
          </svg>
        </span>
        <span class="toast-text" aria-hidden="true">{{ toast.text }}</span>
        <div class="toast-controls">
          @if (toast.action; as action) {
            <button type="button" class="toast-action" data-toast-action (click)="runAction(toast)">
              {{ action.label }}
            </button>
          }
          <button
            type="button"
            class="toast-close"
            aria-label="Dismiss notification"
            (click)="close(toast)"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12" /></svg>
          </button>
        </div>
      </div>
    }
    <!-- Live regions stay mounted and visually hidden so announcements survive card animations. -->
    <div class="toast-region" role="status" aria-live="polite" aria-atomic="true">
      @for (toast of visible(); track toast.id) {
        @if (toast.kind !== 'error') {
          <span
            ><span class="visually-hidden">{{
              toast.kind === 'success' ? 'Success:' : 'Information:'
            }}</span>
            <span class="toast-text">{{ toast.text }}</span></span
          >
        }
      }
    </div>
    <div class="toast-region" role="alert" aria-live="assertive" aria-atomic="true">
      @for (toast of visible(); track toast.id) {
        @if (toast.kind === 'error') {
          <span
            ><span class="visually-hidden">Error:</span>
            <span class="toast-text">{{ toast.text }}</span></span
          >
        }
      }
    </div>`,
})
export class ToastHost {
  readonly toasts = inject(ToastService);
  readonly visible = computed(() => {
    const toast = this.toasts.current();
    return toast ? [toast] : [];
  });
  close(toast: Toast & { id: number }): void {
    // A card still animating out must not dismiss the toast that replaced it.
    if (this.toasts.current()?.id === toast.id) this.toasts.dismiss();
  }
  runAction(toast: Toast & { id: number }): void {
    // Advance first so an action that enqueues another toast cannot dismiss it.
    if (this.toasts.current()?.id !== toast.id) return;
    this.toasts.dismiss();
    toast.action?.run();
  }
}
