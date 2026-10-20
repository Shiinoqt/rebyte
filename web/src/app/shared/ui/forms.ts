import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  computed,
  input,
  output,
  signal,
} from '@angular/core';

@Directive()
abstract class LabelledField {
  readonly id = input.required<string>();
  readonly label = input.required<string>();
  readonly value = input('');
  readonly hint = input('');
  readonly error = input('');
  readonly required = input(false);
  readonly disabled = input(false);
  readonly reason = input('');
  readonly readonly = input(false);
  readonly placeholder = input('');
  readonly autocomplete = input('off');
  readonly valueChange = output<string>();
  readonly blurred = output<void>();
  readonly describedBy = computed(
    () =>
      [
        this.hint() ? `${this.id()}-hint` : '',
        this.error() ? `${this.id()}-error` : '',
        this.disabled() && this.reason() ? `${this.id()}-reason` : '',
      ]
        .filter(Boolean)
        .join(' ') || null,
  );

  changed(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement).value);
  }
}

export interface FieldOption {
  value: string;
  label: string;
  disabled?: boolean;
}

@Component({
  selector: 'app-form-field',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './forms.css',
  host: { 'data-ui': 'form-field' },
  template: `<label [for]="id()"
      >{{ label() }}
      @if (required()) {
        <span aria-hidden="true">*</span>
      }
    </label>
    @switch (kind()) {
      @case ('textarea') {
        <textarea
          [id]="id()"
          [value]="value()"
          [rows]="rows()"
          [placeholder]="placeholder()"
          [required]="required()"
          [disabled]="disabled()"
          [readOnly]="readonly()"
          [attr.aria-required]="required()"
          [attr.aria-disabled]="disabled()"
          [attr.aria-invalid]="!!error()"
          [attr.aria-describedby]="describedBy()"
          [autocomplete]="autocomplete()"
          (input)="changed($event)"
          (blur)="blurred.emit()"
        ></textarea>
      }
      @case ('select') {
        <select
          [id]="id()"
          [value]="value()"
          [required]="required()"
          [disabled]="disabled() || readonly()"
          [attr.aria-required]="required()"
          [attr.aria-disabled]="disabled() || readonly()"
          [attr.aria-invalid]="!!error()"
          [attr.aria-describedby]="describedBy()"
          (change)="changed($event)"
          (blur)="blurred.emit()"
        >
          <option value="" [selected]="!value()">{{ placeholder() || 'Select...' }}</option>
          @for (option of options(); track option.value) {
            <option
              [value]="option.value"
              [selected]="option.value === value()"
              [disabled]="!!option.disabled"
            >
              {{ option.label }}
            </option>
          }
        </select>
      }
      @default {
        <div class="control">
        <input
          [id]="id()"
          [type]="type()"
          [value]="value()"
          [placeholder]="placeholder()"
          [autocomplete]="autocomplete()"
          [required]="required()"
          [disabled]="disabled()"
          [readOnly]="readonly()"
          [attr.aria-required]="required()"
          [attr.aria-disabled]="disabled()"
          [attr.aria-invalid]="!!error()"
          [attr.aria-describedby]="describedBy()"
          (input)="changed($event)"
          (blur)="blurred.emit()"
        />
        <ng-content select="[fieldSuffix]" />
        </div>
      }
    }
    @if (hint()) {
      <p class="hint" [id]="id() + '-hint'">{{ hint() }}</p>
    }
    @if (disabled() && reason()) {
      <p class="hint" [id]="id() + '-reason'">{{ reason() }}</p>
    }
    @if (error()) {
      <p class="field-error" role="alert" [id]="id() + '-error'">
        {{ error() }}
      </p>
    }`,
})
export class FormField extends LabelledField {
  readonly kind = input<'text' | 'textarea' | 'select'>('text');
  readonly type = input<'text' | 'email' | 'password' | 'search' | 'url' | 'tel'>('text');
  readonly options = input<readonly FieldOption[]>([]);
  readonly rows = input(4);
}

@Component({
  selector: 'app-money-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './forms.css',
  host: { 'data-ui': 'money-input' },
  template: `<label [for]="id()"
      >{{ label() }}
      @if (required()) {
        <span aria-hidden="true">*</span>
      }
    </label>
    <div class="money-control">
      <span aria-hidden="true">€</span
      ><input
        type="text"
        inputmode="decimal"
        [id]="id()"
        [value]="value()"
        [placeholder]="placeholder()"
        [required]="required()"
        [disabled]="disabled()"
        [readOnly]="readonly()"
        [attr.aria-required]="required()"
        [attr.aria-disabled]="disabled()"
        [attr.aria-invalid]="!!message()"
        [attr.aria-describedby]="moneyDescription()"
        [autocomplete]="autocomplete()"
        (input)="changed($event)"
        (blur)="touched.set(true); blurred.emit()"
      />
    </div>
    @if (hint()) {
      <p class="hint" [id]="id() + '-hint'">{{ hint() }}</p>
    }
    @if (disabled() && reason()) {
      <p class="hint" [id]="id() + '-reason'">{{ reason() }}</p>
    }
    @if (message()) {
      <p class="field-error" role="alert" [id]="id() + '-error'">
        {{ message() }}
      </p>
    }`,
})
export class MoneyInput extends LabelledField {
  readonly touched = signal(false);
  readonly message = computed(() => {
    if (this.error()) return this.error();
    if (!this.touched()) return '';
    if (!this.value()) return this.required() ? 'Enter an amount.' : '';
    return /^\d+(\.\d{1,2})?$/.test(this.value())
      ? ''
      : 'Use a non-negative amount with at most 2 decimals.';
  });
  readonly moneyDescription = computed(
    () =>
      [
        this.hint() ? `${this.id()}-hint` : '',
        this.message() ? `${this.id()}-error` : '',
        this.disabled() && this.reason() ? `${this.id()}-reason` : '',
      ]
        .filter(Boolean)
        .join(' ') || null,
  );
}

let nextRatingId = 0;
const RATING_WORDS = ['Choose a rating', 'Poor', 'Fair', 'Good', 'Very good', 'Excellent'];

@Component({
  selector: 'app-star-rating',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './forms.css',
  host: { 'data-ui': 'star-rating' },
  template: `@if (editable()) {
      <div
        class="stars"
        role="radiogroup"
        [attr.aria-label]="label()"
        [attr.aria-describedby]="disabled() && reason() ? reasonId : null"
      >
        @for (star of stars; track star) {
          <button
            type="button"
            role="radio"
            [attr.aria-label]="star + (star === 1 ? ' star' : ' stars')"
            [attr.aria-checked]="value() === star"
            [tabIndex]="(value() >= 1 ? value() === star : star === 1) ? 0 : -1"
            [disabled]="disabled()"
            [attr.aria-disabled]="disabled()"
            (click)="valueChange.emit(star)"
            (keydown)="navigate($event, star)"
          >
            <svg
              aria-hidden="true"
              viewBox="0 0 24 24"
              [class.filled]="star <= value()"
              stroke="currentColor"
              stroke-width="1.75"
              stroke-linejoin="round"
            >
              <path
                d="m12 3 2.78 5.63L21 9.54l-4.5 4.39 1.06 6.2L12 17.2l-5.56 2.93 1.06-6.2L3 9.54l6.22-.91L12 3Z"
              />
            </svg>
          </button>
        }
      </div>
      @if (disabled() && reason()) {
        <p class="hint" [id]="reasonId">{{ reason() }}</p>
      }
    } @else {
      <span class="stars" aria-hidden="true">
        @for (star of stars; track star) {
          <svg
            viewBox="0 0 24 24"
            [class.filled]="star <= value()"
            stroke="currentColor"
            stroke-width="1.75"
            stroke-linejoin="round"
          >
            <path
              d="m12 3 2.78 5.63L21 9.54l-4.5 4.39 1.06 6.2L12 17.2l-5.56 2.93 1.06-6.2L3 9.54l6.22-.91L12 3Z"
            />
          </svg>
        }
      </span>
    }
    <span class="rating-value">{{ valueText() }}</span>`,
})
export class StarRating {
  readonly value = input(0);
  readonly label = input('Rating');
  readonly editable = input(false);
  readonly disabled = input(false);
  readonly reason = input('');
  readonly valueChange = output<number>();
  readonly stars = [1, 2, 3, 4, 5];
  readonly valueText = computed(() =>
    this.editable()
      ? (RATING_WORDS[this.value()] ?? `${this.value()} out of 5`)
      : `${this.value()} out of 5`,
  );
  readonly reasonId = `rating-reason-${nextRatingId++}`;

  navigate(event: KeyboardEvent, star: number): void {
    if (this.disabled()) return;
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowUp':
        next = star === 5 ? 1 : star + 1;
        break;
      case 'ArrowLeft':
      case 'ArrowDown':
        next = star === 1 ? 5 : star - 1;
        break;
      case 'Home':
        next = 1;
        break;
      case 'End':
        next = 5;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.valueChange.emit(next);
    (event.currentTarget as HTMLElement).parentElement
      ?.querySelectorAll<HTMLButtonElement>('button')
      [next - 1]?.focus();
  }
}
