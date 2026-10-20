import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Directive,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

let nextControlId = 0;

@Component({
  selector: 'app-count-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './navigation.css',
  host: { 'data-ui': 'count-badge' },
  template: `@if (count() > 0) {
    <span class="count" role="status" [attr.aria-label]="count() + ' ' + label()">{{
      count() > 99 ? '99+' : count()
    }}</span>
  }`,
})
export class CountBadge {
  readonly count = input(0);
  readonly label = input('unread');
}

@Component({
  selector: 'app-load-more',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './navigation.css',
  host: { 'data-ui': 'load-more' },
  template: `@if (hasMore()) {
      <button
        type="button"
        [disabled]="loading() || disabled()"
        [attr.aria-disabled]="loading() || disabled()"
        [attr.aria-busy]="loading()"
        [attr.aria-describedby]="disabled() && reason() ? reasonId : null"
        (click)="load.emit()"
      >
        {{ loading() ? 'Loading...' : 'Show more' }}
      </button>
    }
    @if (disabled() && reason()) {
      <p class="reason" [id]="reasonId">{{ reason() }}</p>
    }
    <p class="count-announcement" role="status" aria-live="polite" aria-atomic="true">
      {{ count() }} {{ noun() }}
    </p>`,
})
export class LoadMore {
  readonly count = input(0);
  readonly noun = input('listings');
  readonly hasMore = input(true);
  readonly loading = input(false);
  readonly disabled = input(false);
  readonly reason = input('');
  readonly load = output<void>();
  readonly reasonId = `load-more-reason-${nextControlId++}`;
}

export interface SelectionOption {
  value: string;
  label: string;
  disabled?: boolean;
  reason?: string;
  panelId?: string;
}

@Directive()
abstract class SelectionControl {
  readonly options = input.required<readonly SelectionOption[]>();
  readonly selected = input('');
  readonly label = input.required<string>();
  readonly selectedChange = output<string>();
  readonly controlId = `selection-${nextControlId++}`;
  readonly active = computed(() => {
    const enabled = this.options().filter((option) => !option.disabled);
    return enabled.find((option) => option.value === this.selected())?.value ?? enabled[0]?.value;
  });

  choose(option: SelectionOption): void {
    if (!option.disabled) this.selectedChange.emit(option.value);
  }

  navigate(event: KeyboardEvent, option: SelectionOption): void {
    const enabled = this.options().filter((item) => !item.disabled);
    const current = enabled.findIndex((item) => item.value === option.value);
    if (!enabled.length || current < 0) return;
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = (current + 1) % enabled.length;
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        next = (current - 1 + enabled.length) % enabled.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = enabled.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.choose(enabled[next]);
    const group = (event.currentTarget as HTMLElement).closest(
      '[role="tablist"], [role="radiogroup"]',
    );
    const targets = group?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
    targets?.[next]?.focus();
  }
}

@Component({
  selector: 'app-pill-tabs',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './navigation.css',
  host: { 'data-ui': 'pill-tabs' },
  template: `<div class="choices pills" role="tablist" [attr.aria-label]="label()">
      @for (option of options(); track option.value; let i = $index) {
        <div class="choice">
          <button
            type="button"
            role="tab"
            [id]="controlId + '-tab-' + i"
            [attr.aria-controls]="option.panelId || null"
            [attr.aria-selected]="active() === option.value"
            [tabIndex]="active() === option.value ? 0 : -1"
            [disabled]="!!option.disabled"
            [attr.aria-disabled]="!!option.disabled"
            [attr.aria-describedby]="
              option.disabled && option.reason ? controlId + '-reason-' + i : null
            "
            (click)="choose(option)"
            (keydown)="navigate($event, option)"
          >
            {{ option.label }}
          </button>
        </div>
      }
    </div>
    @for (option of options(); track option.value; let i = $index) {
      @if (option.disabled && option.reason) {
        <p class="reason" [id]="controlId + '-reason-' + i">
          {{ option.label }}: {{ option.reason }}
        </p>
      }
    }`,
})
export class PillTabs extends SelectionControl {}

@Component({
  selector: 'app-segmented-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './navigation.css',
  host: { 'data-ui': 'segmented-control' },
  template: `<div
      #choices
      class="choices segmented"
      [class.sliding]="thumb() !== null"
      [attr.role]="mode() === 'tabs' ? 'tablist' : 'radiogroup'"
      [attr.aria-label]="label()"
    >
      @if (thumb(); as box) {
        <span
          class="segmented-thumb"
          aria-hidden="true"
          [class.placed]="placed()"
          [style.width.px]="box.width"
          [style.transform]="'translateX(' + box.left + 'px)'"
        ></span>
      }
      @for (option of options(); track option.value; let i = $index) {
        <div class="choice">
          <button
            type="button"
            [attr.role]="mode() === 'tabs' ? 'tab' : 'radio'"
            [id]="controlId + '-choice-' + i"
            [attr.aria-controls]="mode() === 'tabs' ? option.panelId || null : null"
            [attr.aria-selected]="mode() === 'tabs' ? active() === option.value : null"
            [attr.aria-checked]="mode() === 'radio' ? active() === option.value : null"
            [tabIndex]="active() === option.value ? 0 : -1"
            [disabled]="!!option.disabled"
            [attr.aria-disabled]="!!option.disabled"
            [attr.aria-describedby]="
              option.disabled && option.reason ? controlId + '-reason-' + i : null
            "
            (click)="choose(option)"
            (keydown)="navigate($event, option)"
          >
            {{ option.label }}
          </button>
        </div>
      }
    </div>
    @for (option of options(); track option.value; let i = $index) {
      @if (option.disabled && option.reason) {
        <p class="reason" [id]="controlId + '-reason-' + i">
          {{ option.label }}: {{ option.reason }}
        </p>
      }
    }`,
})
export class SegmentedControl extends SelectionControl {
  readonly mode = input<'tabs' | 'radio'>('radio');
  private readonly choices = viewChild.required<ElementRef<HTMLElement>>('choices');
  protected readonly thumb = signal<{ left: number; width: number } | null>(null);
  // The first placement must not animate, or the thumb slides in from the left edge.
  protected readonly placed = signal(false);
  private readonly resized = signal(0);

  constructor() {
    super();
    afterRenderEffect(() => {
      const value = this.active();
      this.resized();
      const container = this.choices().nativeElement;
      const index = this.options().findIndex((option) => option.value === value);
      const choice = container.querySelectorAll<HTMLElement>('.choice')[index];
      const next = choice ? { left: choice.offsetLeft, width: choice.offsetWidth } : null;
      const current = this.thumb();
      if (next?.left !== current?.left || next?.width !== current?.width) this.thumb.set(next);
      if (next && !this.placed()) requestAnimationFrame(() => this.placed.set(true));
    });
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(() => this.resized.update((n) => n + 1));
      afterRenderEffect(() => observer.observe(this.choices().nativeElement));
      inject(DestroyRef).onDestroy(() => observer.disconnect());
    }
  }
}

@Component({
  selector: 'app-icon-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './navigation.css',
  host: { 'data-ui': 'icon-button' },
  template: `<button
      type="button"
      class="icon-button"
      [attr.aria-label]="label()"
      [attr.aria-pressed]="pressed()"
      [disabled]="disabled()"
      [attr.aria-disabled]="disabled()"
      [attr.aria-describedby]="disabled() && reason() ? reasonId : null"
      (click)="activated.emit()"
    >
      <span aria-hidden="true"><ng-content /></span>
    </button>
    @if (disabled() && reason()) {
      <p class="reason" [id]="reasonId">{{ reason() }}</p>
    }`,
})
export class IconButton {
  readonly label = input.required<string>();
  readonly pressed = input<boolean | null>(null);
  readonly disabled = input(false);
  readonly reason = input('');
  readonly activated = output<void>();
  readonly reasonId = `icon-button-reason-${nextControlId++}`;
}
