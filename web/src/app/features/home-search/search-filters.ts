import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  Signal,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import {
  AttributeDefinitionResponse,
  CategoryTreeNode,
  ListingType,
} from '../../core/api/api-models';
import { Banner } from '../../shared/ui/feedback';
import { CountBadge, SegmentedControl, SelectionOption } from '../../shared/ui/navigation';
import {
  PRICE_MAX,
  SearchFilterState,
  SearchStateService,
  priceRangeInverted,
} from './search-state.service';

export const PRICE_STEP = 10;
// Meilisearch indexes ranges for price and vram_gb only; other numeric attributes filter by equality.
export const RANGE_ATTRIBUTES: readonly string[] = ['vram_gb'];

const AMOUNT = /^\d{1,7}(?:\.\d{1,2})?$/;
const PHONE_QUERY = '(max-width: 640px)';
const COMPACT_PHONE_QUERY = '(max-width: 440px)';
const FOCUSABLE =
  'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]';

type PricePatch = Partial<Pick<SearchFilterState, 'min' | 'max'>>;

// Most listings sit under the knee, so it gets more of the track than a linear scale would give it.
const SLIDER_KNEE = 500;
const SLIDER_KNEE_PERCENT = 60;

export function sliderPercent(value: number): number {
  return value <= SLIDER_KNEE
    ? (value / SLIDER_KNEE) * SLIDER_KNEE_PERCENT
    : SLIDER_KNEE_PERCENT +
        ((value - SLIDER_KNEE) / (PRICE_MAX - SLIDER_KNEE)) * (100 - SLIDER_KNEE_PERCENT);
}

export function sliderValue(percent: number): number {
  const clamped = Math.min(100, Math.max(0, percent));
  return clamped <= SLIDER_KNEE_PERCENT
    ? (clamped / SLIDER_KNEE_PERCENT) * SLIDER_KNEE
    : SLIDER_KNEE +
        ((clamped - SLIDER_KNEE_PERCENT) / (100 - SLIDER_KNEE_PERCENT)) * (PRICE_MAX - SLIDER_KNEE);
}

const PRICE_PRESETS = [
  { label: 'Under €50', min: null, max: '50' },
  { label: '€50–200', min: '50', max: '200' },
  { label: '€200–500', min: '200', max: '500' },
  { label: '€500+', min: '500', max: null },
] as const;

// Fixed roots and leaf/type consistency make Hardware/Software ancestry authoritative.
function categoryListingType(
  tree: readonly CategoryTreeNode[],
  categoryId: string | null,
): ListingType | null {
  if (!categoryId) return null;
  for (const root of tree) {
    if (!findCategoryNode([root], categoryId)) continue;
    if (root.name === 'Hardware') return 'HARDWARE';
    if (root.name === 'Software') return 'SOFTWARE';
  }
  return null;
}

function findCategoryNode(nodes: readonly CategoryTreeNode[], id: string): CategoryTreeNode | null {
  for (const node of nodes) {
    const found = node.id === id ? node : findCategoryNode(node.children, id);
    if (found) return found;
  }
  return null;
}

function categoriesForType(
  tree: readonly CategoryTreeNode[],
  type: ListingType | null,
): readonly CategoryTreeNode[] {
  if (!type) return [];
  const rootName = type === 'HARDWARE' ? 'Hardware' : 'Software';
  return tree.filter((root) => root.name === rootName);
}

function selectedTrail(
  nodes: readonly CategoryTreeNode[],
  selected: string | null,
  ancestors: readonly CategoryTreeNode[] = [],
): readonly CategoryTreeNode[] {
  if (selected === null) return [];
  for (const node of nodes) {
    if (node.id === selected) return node.children.length ? [...ancestors, node] : ancestors;
    const path = selectedTrail(node.children, selected, [...ancestors, node]);
    if (path.length) return path;
  }
  return [];
}

export function humanise(name: string): string {
  const spaced = name.replace(/_/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function snapThumb(value: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(PRICE_MAX, Math.max(0, Math.round(value / PRICE_STEP) * PRICE_STEP));
}

function mediaQuerySignal(queryText: string): Signal<boolean> {
  const query = inject(DOCUMENT).defaultView?.matchMedia?.(queryText) ?? null;
  const matches = signal(query?.matches ?? false);
  if (query) {
    const listener = (event: MediaQueryListEvent) => matches.set(event.matches);
    query.addEventListener('change', listener);
    inject(DestroyRef).onDestroy(() => query.removeEventListener('change', listener));
  }
  return matches;
}

const CHEVRON_DOWN = 'M6 9l6 6 6-6';
const CHEVRON_RIGHT = 'M9 6l6 6-6 6';
const CHECK = 'M5 13l4 4L19 7';

@Component({
  selector: 'app-price-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.inline-only]': 'inlineOnly()',
    '(document:click)': 'outside($event)',
    '(keydown)': 'keydown($event)',
  },
  styles: `
    :host {
      position: relative;
      display: inline-block;
      min-width: 0;
    }
    :host(.inline-only) {
      display: block;
    }
    button {
      min-height: max(var(--control-md), 44px);
      padding: 0 var(--space-md);
      border: 1px solid var(--border);
      border-radius: var(--radius-pill);
      background: var(--surface);
      color: var(--ink);
      font: inherit;
      font-weight: var(--weight-semibold);
      cursor: pointer;
    }
    button:hover {
      background: var(--surface-hover);
    }
    button:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 2px;
    }
    .trigger {
      display: inline-flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-sm);
      white-space: nowrap;
    }
    .trigger[aria-expanded='true'] {
      border-color: var(--primary);
    }
    .trigger svg {
      width: 16px;
      height: 16px;
      fill: none;
      stroke: currentColor;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .popover {
      position: absolute;
      top: calc(100% + var(--space-sm));
      right: 0;
      z-index: var(--z-dropdown);
      width: min(25rem, calc(100vw - 2 * var(--space-md)));
      padding: var(--space-md);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-md);
    }
    .popover-heading,
    .sheet-heading {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-sm);
      margin-bottom: var(--space-sm);
      font-weight: var(--weight-semibold);
    }
    .clear-price {
      min-height: var(--hit-min);
      padding: 0 var(--space-sm);
      border-color: transparent;
      background: transparent;
      color: var(--primary-strong);
      font-size: var(--text-caption);
    }
    .price {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      align-items: center;
      gap: var(--space-sm);
      min-width: 0;
      padding: var(--space-sm) 0;
    }
    :host(.inline-only) .price {
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      padding: 0;
    }
    :host(.inline-only) .track {
      display: none;
    }
    :host(.inline-only) .error {
      grid-column: 1 / -1;
    }
    .price-panel:focus {
      outline: none;
    }
    .range-readout {
      margin-left: var(--space-xs);
      color: var(--text-muted);
      font-family: var(--font-mono);
      font-size: var(--text-caption);
      font-weight: var(--weight-regular);
    }
    .presets {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-xs);
      margin-top: var(--space-sm);
    }
    .preset {
      min-height: var(--hit-min);
      padding: 0 var(--space-xs);
      white-space: nowrap;
      border: 1px solid var(--border);
      border-radius: var(--radius-pill);
      background: var(--surface);
      color: var(--ink);
      font-family: var(--font-mono);
      font-size: var(--text-caption);
      cursor: pointer;
    }
    .preset:hover {
      background: var(--surface-sunken);
    }
    .preset[aria-pressed='true'] {
      border-color: var(--primary);
      color: var(--primary-strong);
    }
    .preset:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 2px;
    }
    .track {
      position: relative;
      height: var(--hit-min);
      margin: 0 10px;
      touch-action: none;
      cursor: pointer;
    }
    .rail,
    .span {
      position: absolute;
      top: 50%;
      height: 4px;
      margin-top: -2px;
      border-radius: var(--radius-pill);
    }
    .rail {
      left: 0;
      right: 0;
      background: var(--border);
    }
    .span {
      background: var(--primary);
    }
    .thumb {
      position: absolute;
      top: 50%;
      width: 20px;
      height: 20px;
      margin: -10px 0 0 -10px;
      border: 2px solid var(--primary);
      border-radius: var(--radius-round);
      background: var(--surface);
      box-shadow: var(--shadow-sm);
    }
    .thumb::before {
      content: '';
      position: absolute;
      inset: -12px;
    }
    .thumb:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 3px;
    }
    .amount {
      display: grid;
      grid-template-columns: 1fr auto;
      align-items: center;
      gap: 2px var(--space-xs);
      min-width: 0;
      padding: var(--space-xs);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      background: var(--surface);
      color: var(--text-muted);
    }
    .field-label {
      grid-column: 1 / -1;
      font-size: var(--text-caption);
      line-height: 1.2;
    }
    input {
      width: 100%;
      min-width: 0;
      border: 0;
      background: transparent;
      color: var(--ink);
      font-family: var(--font-mono);
      font-size: var(--text-body-size);
    }
    input:focus-visible {
      outline: none;
    }
    .amount:focus-within {
      border-color: var(--primary);
      box-shadow: 0 0 0 1px var(--primary);
    }
    .error {
      grid-column: 1 / -1;
      margin: 0;
      color: var(--danger-strong);
      font-size: var(--text-caption);
    }
    @media (max-width: 640px) {
      input {
        font-size: var(--control-text-phone);
      }
      .sheet-price .amount {
        min-height: var(--control-lg);
      }
    }
  `,
  template: `
    @if (!inlineOnly()) {
      <button
        #trigger
        type="button"
        class="trigger"
        aria-haspopup="dialog"
        [attr.aria-expanded]="open()"
        (click)="toggle()"
      >
        {{ triggerLabel() }}
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
    }
    @if (inlineOnly()) {
      <div class="sheet-heading">
        <span>Price</span>
        <button type="button" class="clear-price" (click)="clearPrice()">Clear price</button>
      </div>
    }
    @if (inlineOnly() || open()) {
      <div
        class="price-panel"
        [class.popover]="!inlineOnly()"
        [class.sheet-price]="inlineOnly()"
        [attr.role]="inlineOnly() ? 'group' : 'dialog'"
        [attr.aria-label]="inlineOnly() ? 'Price' : 'Price filter'"
        [attr.tabindex]="inlineOnly() ? null : -1"
        #panel
      >
        @if (!inlineOnly()) {
          <div class="popover-heading">
            <span>Price <span class="range-readout" aria-hidden="true">{{ readout() }}</span></span>
            <button type="button" class="clear-price" (click)="clearPrice()">Clear price</button>
          </div>
        }
        @if (!inlineOnly()) {
          <div
            #track
            class="track"
            (pointerdown)="pointerDown($event)"
            (pointermove)="pointerMove($event)"
            (pointerup)="pointerUp()"
            (pointercancel)="pointerUp()"
          >
            <div class="rail"></div>
            <div
              class="span"
              [style.left.%]="percent(shownLow())"
              [style.width.%]="spanWidth()"
            ></div>
            <div
              class="thumb"
              data-thumb="min"
              role="slider"
              tabindex="0"
              aria-label="Minimum price"
              aria-valuemin="0"
              [attr.aria-valuemax]="priceMax"
              [attr.aria-valuenow]="shownLow()"
              [attr.aria-valuetext]="'€' + shownLow()"
              [style.left.%]="percent(shownLow())"
              (keydown)="key($event, 'min')"
              (keyup)="release('min')"
              (blur)="release('min')"
            ></div>
            <div
              class="thumb"
              data-thumb="max"
              role="slider"
              tabindex="0"
              aria-label="Maximum price"
              aria-valuemin="0"
              [attr.aria-valuemax]="priceMax"
              [attr.aria-valuenow]="high()"
              [attr.aria-valuetext]="high() === priceMax ? 'Any price' : '€' + high()"
              [style.left.%]="percent(high())"
              (keydown)="key($event, 'max')"
              (keyup)="release('max')"
              (blur)="release('max')"
            ></div>
          </div>
        }
        <div class="price" role="group" aria-label="Price range">
          <label class="amount min-amount">
            <span class="field-label">Min</span>
            <span aria-hidden="true">€</span>
            <input
              type="text"
              inputmode="decimal"
              autocomplete="off"
              aria-label="Minimum price in euros"
              placeholder="0"
              [value]="minText()"
              (input)="minText.set(value($event))"
              (blur)="commitText('min')"
              (keydown.enter)="commitText('min')"
            />
          </label>
          <label class="amount max-amount">
            <span class="field-label">Max</span>
            <span aria-hidden="true">€</span>
            <input
              type="text"
              inputmode="decimal"
              autocomplete="off"
              aria-label="Maximum price in euros"
              placeholder="Any"
              [value]="maxText()"
              (input)="maxText.set(value($event))"
              (blur)="commitText('max')"
              (keydown.enter)="commitText('max')"
            />
          </label>
          @if (message()) {
            <p class="error" role="alert">{{ message() }}</p>
          }
        </div>
        @if (!inlineOnly()) {
          <div class="presets" role="group" aria-label="Quick price ranges">
            @for (preset of presets; track preset.label) {
              <button
                type="button"
                class="preset"
                [attr.aria-pressed]="min() === preset.min && max() === preset.max"
                (click)="applyPreset(preset)"
              >
                {{ preset.label }}
              </button>
            }
          </div>
        }
      </div>
    }
  `,
})
export class PriceControl {
  readonly min = input<string | null>(null);
  readonly max = input<string | null>(null);
  readonly inlineOnly = input(false);
  readonly commit = output<PricePatch>();
  readonly clear = output<void>();

  protected readonly priceMax = PRICE_MAX;
  protected readonly open = signal(false);
  protected readonly triggerLabel = computed(() => {
    const min = this.min();
    const max = this.max();
    if (min !== null && max !== null) return `€${min}–€${max}`;
    if (min !== null) return `€${min}+`;
    if (max !== null) return `Up to €${max}`;
    return 'Price';
  });
  private readonly low = linkedSignal(() => snapThumb(Number(this.min() ?? 0), 0));
  protected readonly high = linkedSignal(() =>
    this.max() === null ? PRICE_MAX : snapThumb(Number(this.max()), PRICE_MAX),
  );
  protected readonly shownLow = computed(() => Math.min(this.low(), this.high()));
  protected readonly minText = linkedSignal(() => this.min() ?? '');
  protected readonly maxText = linkedSignal(() => this.max() ?? '');
  private readonly formatError = signal('');
  protected readonly message = computed(
    () =>
      this.formatError() ||
      (priceRangeInverted({ min: this.min(), max: this.max() })
        ? "The minimum can't be above the maximum."
        : ''),
  );
  protected readonly spanWidth = computed(
    () => this.percent(this.high()) - this.percent(this.shownLow()),
  );
  private readonly track = viewChild.required<ElementRef<HTMLElement>>('track');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  protected readonly presets = PRICE_PRESETS;
  protected readonly readout = computed(() => {
    const low = this.shownLow();
    const high = this.high();
    return `€${low} – ${high === PRICE_MAX ? 'Any' : '€' + high}`;
  });
  private readonly dirty = new Set<'min' | 'max'>();
  private dragging: 'min' | 'max' | null = null;

  protected toggle(): void {
    this.open.update((open) => !open);
    if (this.open()) {
      // The dialog itself takes focus so opening does not draw a focus ring on the Min field.
      afterNextRender(() => this.panel()?.nativeElement.focus(), { injector: this.injector });
    }
  }

  protected outside(event: MouseEvent): void {
    if (
      this.open() &&
      event.target instanceof Node &&
      !this.host.nativeElement.contains(event.target)
    ) {
      this.open.set(false);
    }
  }

  protected keydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || !this.open()) return;
    event.stopPropagation();
    this.close();
  }

  protected clearPrice(): void {
    if (!this.inlineOnly()) this.close();
    this.clear.emit();
  }

  private close(): void {
    this.open.set(false);
    this.trigger()?.nativeElement.focus();
  }

  protected applyPreset(preset: (typeof PRICE_PRESETS)[number]): void {
    this.formatError.set('');
    this.commit.emit({ min: preset.min, max: preset.max });
  }

  protected percent(value: number): number {
    return sliderPercent(value);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected key(event: KeyboardEvent, which: 'min' | 'max'): void {
    const current = which === 'min' ? this.shownLow() : this.high();
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowUp':
        next = current + PRICE_STEP;
        break;
      case 'ArrowLeft':
      case 'ArrowDown':
        next = current - PRICE_STEP;
        break;
      case 'PageUp':
        next = current + PRICE_STEP * 10;
        break;
      case 'PageDown':
        next = current - PRICE_STEP * 10;
        break;
      case 'Home':
        next = which === 'min' ? 0 : this.shownLow();
        break;
      case 'End':
        next = which === 'min' ? this.high() : PRICE_MAX;
        break;
      default:
        return;
    }
    event.preventDefault();
    if (this.setThumb(which, next)) this.dirty.add(which);
  }

  protected release(which: 'min' | 'max'): void {
    if (this.dirty.delete(which)) this.commitThumb(which);
  }

  protected pointerDown(event: PointerEvent): void {
    const value = this.valueAt(event.clientX);
    const low = this.shownLow();
    const high = this.high();
    const which =
      Math.abs(value - low) < Math.abs(value - high) || (low === high && value < low)
        ? 'min'
        : 'max';
    this.dragging = which;
    const track = this.track().nativeElement;
    track.setPointerCapture?.(event.pointerId);
    track.querySelector<HTMLElement>(`[data-thumb="${which}"]`)?.focus();
    event.preventDefault();
    this.setThumb(which, value);
  }

  protected pointerMove(event: PointerEvent): void {
    if (this.dragging) this.setThumb(this.dragging, this.valueAt(event.clientX));
  }

  protected pointerUp(): void {
    const which = this.dragging;
    this.dragging = null;
    if (which) {
      this.dirty.delete(which);
      this.commitThumb(which);
    }
  }

  protected commitText(which: 'min' | 'max'): void {
    const text = (which === 'min' ? this.minText() : this.maxText()).trim();
    if (text !== '' && !AMOUNT.test(text)) {
      this.formatError.set('Enter an amount with at most 2 decimals.');
      return;
    }
    this.formatError.set('');
    const bound = which === 'min' ? 0 : PRICE_MAX;
    const display = text !== '' && Number(text) === bound ? '' : text;
    (which === 'min' ? this.minText : this.maxText).set(display);
    const amount = text === '' ? null : text;
    this.commit.emit(which === 'min' ? { min: amount } : { max: amount });
  }

  private valueAt(clientX: number): number {
    const rect = this.track().nativeElement.getBoundingClientRect();
    const ratio = rect.width > 0 ? (clientX - rect.left) / rect.width : 0;
    return snapThumb(sliderValue(ratio * 100), 0);
  }

  // Returns whether the thumb moved; the thumbs can touch but never cross.
  private setThumb(which: 'min' | 'max', value: number): boolean {
    if (which === 'min') {
      const next = Math.max(0, Math.min(value, this.high()));
      const changed = next !== this.shownLow();
      this.low.set(next);
      return changed;
    }
    const next = Math.min(PRICE_MAX, Math.max(value, this.shownLow()));
    const changed = next !== this.high();
    this.high.set(next);
    return changed;
  }

  private commitThumb(which: 'min' | 'max'): void {
    this.formatError.set('');
    if (which === 'min') {
      this.commit.emit({ min: this.low() === 0 ? null : String(this.low()) });
    } else {
      this.commit.emit({ max: this.high() === PRICE_MAX ? null : String(this.high()) });
    }
  }
}

@Component({
  selector: 'app-category-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.inline]': 'inline()',
    '(document:click)': 'outside($event)',
    '(keydown)': 'keydown($event)',
  },
  styles: `
    :host {
      position: relative;
      display: inline-block;
      min-width: 0;
    }
    :host(.inline) {
      display: block;
    }
    button {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-sm);
      width: 100%;
      min-height: max(var(--control-md), 44px);
      padding: var(--space-sm) var(--space-md);
      border: 1px solid transparent;
      border-radius: var(--radius-md);
      background: transparent;
      color: var(--ink);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    button:hover {
      background: var(--surface-hover);
    }
    button:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 2px;
    }
    .trigger {
      border-color: var(--border);
      border-radius: var(--radius-pill);
      background: var(--surface);
      font-weight: var(--weight-semibold);
      white-space: nowrap;
    }
    .trigger[aria-expanded='true'] {
      border-color: var(--primary);
    }
    .trigger:disabled {
      color: var(--text-muted);
      cursor: not-allowed;
    }
    .trigger span {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .panel {
      padding: var(--space-xs);
      background: var(--surface);
    }
    .disabled-hint {
      margin: 0;
      padding: var(--space-sm) var(--space-md);
      color: var(--text-muted);
      font-size: var(--text-caption);
    }
    .popover {
      position: absolute;
      top: calc(100% + var(--space-sm));
      left: 0;
      z-index: var(--z-dropdown);
      min-width: 240px;
      max-height: 60vh;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      padding: var(--space-sm);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-md);
    }
    /* The list scrolls inside the padded panel so the scrollbar never clips the rounded corners. */
    .popover > ul {
      min-height: 0;
      overflow-y: auto;
      scrollbar-gutter: stable;
      scrollbar-width: thin;
      scrollbar-color: var(--border-strong) transparent;
    }
    ul {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .crumbs {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-xs);
      padding: var(--space-xs) var(--space-sm);
      color: var(--text-muted);
      font-size: var(--text-caption);
    }
    .crumbs button {
      width: auto;
      min-height: var(--hit-min);
      padding: 0 var(--space-sm);
      font-size: var(--text-caption);
    }
    .selected {
      background: var(--surface-selected);
      font-weight: var(--weight-semibold);
    }
    .category-name {
      min-width: 0;
      overflow-wrap: anywhere;
    }
    svg {
      flex: none;
      width: 16px;
      height: 16px;
      fill: none;
      stroke: currentColor;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .check {
      color: var(--primary);
    }
  `,
  template: `
    @if (!inline()) {
      <button
        #trigger
        type="button"
        class="trigger"
        [disabled]="disabled()"
        aria-haspopup="true"
        [attr.aria-expanded]="open() && !disabled()"
        (click)="toggle()"
      >
        <span>{{ label() }}</span>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path [attr.d]="chevronDown" /></svg>
      </button>
    }
    @if (inline() && disabled()) {
      <p class="disabled-hint">Choose a type first.</p>
    }
    @if ((inline() && !disabled()) || (!inline() && open() && !disabled())) {
      <div class="panel" role="group" aria-label="Categories" [class.popover]="!inline()">
        @if (!flattened() && trail().length) {
          <nav class="crumbs" aria-label="Category path">
            <button type="button" (click)="goTo(0)">All categories</button>
            @for (node of trail(); track node.id; let i = $index) {
              <span aria-hidden="true">&gt;</span>
              <button type="button" (click)="goTo(i + 1)">{{ node.name }}</button>
            }
          </nav>
        }
        <ul>
          @if (!flattened() && current(); as parent) {
            <li>
              <button
                type="button"
                data-row
                [class.selected]="selected() === parent.id"
                [attr.aria-current]="selected() === parent.id ? 'true' : null"
                (click)="choose(parent.id)"
              >
                <span>All in {{ parent.name }}</span>
                @if (selected() === parent.id) {
                  <svg class="check" viewBox="0 0 24 24" aria-hidden="true">
                    <path [attr.d]="check" />
                  </svg>
                }
              </button>
            </li>
          } @else {
            <li>
              <button
                type="button"
                data-row
                [class.selected]="selected() === null"
                [attr.aria-current]="selected() === null ? 'true' : null"
                (click)="choose(null)"
              >
                <span>All categories</span>
                @if (selected() === null) {
                  <svg class="check" viewBox="0 0 24 24" aria-hidden="true">
                    <path [attr.d]="check" />
                  </svg>
                }
              </button>
            </li>
          }
          @for (row of displayRows(); track row.node.id) {
            <li>
              <button
                type="button"
                data-row
                [attr.data-children]="!flattened() && row.node.children.length ? '' : null"
                [class.selected]="selected() === row.node.id"
                [attr.aria-current]="selected() === row.node.id ? 'true' : null"
                [style.padding-left.rem]="flattened() ? 0.75 + row.depth * 1.25 : null"
                (click)="flattened() ? choose(row.node.id) : activate(row.node)"
              >
                <span class="category-name">{{ row.node.name }}</span>
                @if (!flattened() && row.node.children.length) {
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path [attr.d]="chevronRight" /></svg>
                } @else if (selected() === row.node.id) {
                  <svg class="check" viewBox="0 0 24 24" aria-hidden="true">
                    <path [attr.d]="check" />
                  </svg>
                }
              </button>
            </li>
          }
        </ul>
      </div>
    }
  `,
})
export class CategoryPicker {
  readonly tree = input.required<readonly CategoryTreeNode[]>();
  readonly selected = input<string | null>(null);
  readonly inline = input(false);
  readonly flattened = input(false);
  readonly disabled = input(false);
  readonly select = output<string | null>();

  protected readonly chevronDown = CHEVRON_DOWN;
  protected readonly chevronRight = CHEVRON_RIGHT;
  protected readonly check = CHECK;
  protected readonly open = signal(false);
  protected readonly trail = linkedSignal(() => selectedTrail(this.tree(), this.selected()));
  protected readonly current = computed(() => this.trail().at(-1) ?? null);
  protected readonly rows = computed(() => this.current()?.children ?? this.tree());
  protected readonly displayRows = computed(() => {
    if (!this.flattened()) return this.rows().map((node) => ({ node, depth: 0 }));
    const result: { node: CategoryTreeNode; depth: number }[] = [];
    const visit = (nodes: readonly CategoryTreeNode[], depth: number) => {
      for (const node of nodes) {
        result.push({ node, depth: Math.min(depth, 4) });
        visit(node.children, depth + 1);
      }
    };
    visit(this.tree(), 0);
    return result;
  });
  protected readonly label = computed(() =>
    this.disabled()
      ? 'Choose a type first'
      : (this.find(this.tree(), this.selected())?.name ?? 'All categories'),
  );
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  constructor() {
    effect(() => {
      if (this.disabled()) this.open.set(false);
    });
  }

  protected toggle(): void {
    if (this.disabled()) return;
    if (this.open()) {
      this.open.set(false);
      return;
    }
    this.trail.set(selectedTrail(this.tree(), this.selected()));
    this.open.set(true);
    this.focusFirstRow();
  }

  protected goTo(depth: number): void {
    this.trail.update((trail) => trail.slice(0, depth));
    this.focusFirstRow();
  }

  protected activate(node: CategoryTreeNode): void {
    if (node.children.length) {
      this.trail.update((trail) => [...trail, node]);
      this.focusFirstRow();
    } else {
      this.choose(node.id);
    }
  }

  protected choose(id: string | null): void {
    this.select.emit(id);
    this.close();
  }

  protected outside(event: MouseEvent): void {
    if (
      this.open() &&
      event.target instanceof Node &&
      !this.host.nativeElement.contains(event.target)
    ) {
      this.open.set(false);
    }
  }

  protected keydown(event: KeyboardEvent): void {
    if (this.disabled()) return;
    if (event.key === 'Escape' && !this.inline() && this.open()) {
      event.stopPropagation();
      this.close();
      return;
    }
    const rows = Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('[data-row]'));
    const index = rows.indexOf(event.target as HTMLElement);
    if (index < 0) return;
    const target = event.target as HTMLElement;
    switch (event.key) {
      case 'ArrowDown':
        rows[(index + 1) % rows.length].focus();
        break;
      case 'ArrowUp':
        rows[(index - 1 + rows.length) % rows.length].focus();
        break;
      case 'Home':
        rows[0].focus();
        break;
      case 'End':
        rows[rows.length - 1].focus();
        break;
      case 'ArrowRight':
        if (!target.hasAttribute('data-children')) return;
        target.click();
        break;
      case 'ArrowLeft':
        if (this.flattened() || !this.trail().length) return;
        this.goTo(this.trail().length - 1);
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  private close(): void {
    this.open.set(false);
    this.trigger()?.nativeElement.focus();
  }

  private focusFirstRow(): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('[data-row]')?.focus(),
      { injector: this.injector },
    );
  }

  private find(nodes: readonly CategoryTreeNode[], id: string | null): CategoryTreeNode | null {
    if (id === null) return null;
    for (const node of nodes) {
      const found = node.id === id ? node : this.find(node.children, id);
      if (found) return found;
    }
    return null;
  }
}

@Component({
  selector: 'app-attribute-filters',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SegmentedControl],
  styles: `
    :host {
      display: block;
    }
    .attributes {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-md);
    }
    .attribute {
      display: flex;
      flex-direction: column;
      gap: var(--space-xs);
      min-width: 0;
    }
    .attribute-label {
      font-size: var(--text-caption);
      font-weight: var(--weight-semibold);
      color: var(--text-muted);
    }
    .unit {
      margin-left: var(--space-xs);
      font-family: var(--font-mono);
    }
    .controls {
      display: flex;
      align-items: center;
      gap: var(--space-sm);
    }
    input,
    select {
      min-height: var(--control-md);
      padding: 0 var(--space-md);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-md);
      background: var(--surface);
      color: var(--ink);
      font: inherit;
    }
    input {
      width: 7em;
      font-family: var(--font-mono);
    }
    .error {
      margin: 0;
      color: var(--danger-strong);
      font-size: var(--text-caption);
    }
    @media (max-width: 640px) {
      input,
      select {
        font-size: var(--control-text-phone);
      }
      .attributes {
        flex-direction: column;
      }
    }
  `,
  template: `
    <div class="attributes">
      @for (attribute of attributes(); track attribute.id) {
        <div class="attribute" [attr.data-attribute]="attribute.name">
          <span class="attribute-label"
            >{{ label(attribute.name) }}
            @if (attribute.unit) {
              <span class="unit">{{ attribute.unit }}</span>
            }
          </span>
          @switch (attribute.type) {
            @case ('ENUM') {
              <select
                [attr.aria-label]="label(attribute.name)"
                (change)="setEnum(attribute.name, $event)"
              >
                <option value="" [selected]="!attrs()[attribute.name]">Any</option>
                @for (value of attribute.allowedValues; track value) {
                  <option [value]="value" [selected]="attrs()[attribute.name] === value">
                    {{ optionText(attribute.name, value) }}
                  </option>
                }
              </select>
            }
            @case ('BOOLEAN') {
              <app-segmented-control
                [label]="label(attribute.name)"
                [options]="booleanOptions"
                [selected]="attrs()[attribute.name] ?? ''"
                (selectedChange)="set(attribute.name, $event)"
              />
            }
            @default {
              <div class="controls">
                @if (isRange(attribute.name)) {
                  <input
                    type="text"
                    inputmode="decimal"
                    autocomplete="off"
                    placeholder="Min"
                    [attr.aria-label]="label(attribute.name) + ' minimum'"
                    [value]="attrs()[attribute.name + '.min'] ?? ''"
                    (blur)="setNumber(attribute, attribute.name + '.min', $event)"
                    (keydown.enter)="setNumber(attribute, attribute.name + '.min', $event)"
                  />
                  <span aria-hidden="true">-</span>
                  <input
                    type="text"
                    inputmode="decimal"
                    autocomplete="off"
                    placeholder="Max"
                    [attr.aria-label]="label(attribute.name) + ' maximum'"
                    [value]="attrs()[attribute.name + '.max'] ?? ''"
                    (blur)="setNumber(attribute, attribute.name + '.max', $event)"
                    (keydown.enter)="setNumber(attribute, attribute.name + '.max', $event)"
                  />
                } @else {
                  <input
                    type="text"
                    [attr.inputmode]="attribute.type === 'INTEGER' ? 'numeric' : 'decimal'"
                    autocomplete="off"
                    placeholder="Any"
                    [attr.aria-label]="label(attribute.name)"
                    [value]="attrs()[attribute.name] ?? ''"
                    (blur)="setNumber(attribute, attribute.name, $event)"
                    (keydown.enter)="setNumber(attribute, attribute.name, $event)"
                  />
                }
              </div>
              @if (errors()[attribute.name]; as message) {
                <p class="error" role="alert">{{ message }}</p>
              }
            }
          }
        </div>
      }
    </div>
  `,
})
export class AttributeFilters {
  readonly attributes = input.required<readonly AttributeDefinitionResponse[]>();
  readonly attrs = input.required<Readonly<Record<string, string>>>();
  readonly facets = input<readonly { attribute: string; values: Record<string, number> }[]>([]);
  readonly attrsChange = output<Record<string, string>>();

  protected readonly booleanOptions: readonly SelectionOption[] = [
    { value: '', label: 'Any' },
    { value: 'true', label: 'Yes' },
    { value: 'false', label: 'No' },
  ];
  protected readonly errors = signal<Readonly<Record<string, string>>>({});

  protected label(name: string): string {
    return humanise(name);
  }

  protected isRange(name: string): boolean {
    return RANGE_ATTRIBUTES.includes(name);
  }

  protected optionText(name: string, value: string): string {
    const count = this.facets().find((facet) => facet.attribute === name)?.values[value];
    return count === undefined ? value : `${value} (${count})`;
  }

  protected setEnum(name: string, event: Event): void {
    this.set(name, (event.target as HTMLSelectElement).value);
  }

  protected setNumber(attribute: AttributeDefinitionResponse, key: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    const valid =
      value === '' ||
      (attribute.type === 'INTEGER' ? /^\d{1,15}$/ : /^\d{1,15}(?:\.\d+)?$/).test(value);
    this.errors.update((errors) => ({
      ...errors,
      [attribute.name]: valid
        ? ''
        : attribute.type === 'INTEGER'
          ? 'Enter a whole number.'
          : 'Enter a number.',
    }));
    if (valid) this.set(key, value);
  }

  protected set(key: string, value: string): void {
    const next = { ...this.attrs() };
    if (value === '') delete next[key];
    else next[key] = value;
    this.attrsChange.emit(next);
  }
}

@Component({
  selector: 'app-search-filters',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Banner, CategoryPicker, PriceControl, AttributeFilters, SegmentedControl, CountBadge],
  styles: `
    :host {
      display: block;
      margin-bottom: var(--space-lg);
    }
    .bar,
    .phone-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-sm);
    }
    .bar {
      justify-content: center;
      padding: var(--space-xs);
      border-radius: var(--radius-pill);
    }
    .bar-container {
      container: filters-bar / inline-size;
    }
    .bar app-category-picker {
      flex: 0 1 12rem;
    }
    .bar app-segmented-control {
      flex: 0 0 auto;
      min-width: 20rem;
      --space-xs: 0;
    }
    .bar app-price-control {
      flex: 0 0 auto;
      min-width: 0;
    }
    .phone-row app-segmented-control {
      flex: 1 1 14rem;
      min-width: min(100%, 14rem);
      --space-md: var(--space-sm);
    }
    .phone-row {
      flex-wrap: nowrap;
    }
    .phone-row.compact .filters-button {
      margin-left: 0;
    }
    .sheet.compact app-segmented-control {
      --space-md: var(--space-sm);
      --space-xs: 0;
    }
    .attributes {
      margin-top: var(--space-md);
    }
    app-banner {
      margin-bottom: var(--space-md);
    }
    button {
      min-height: var(--control-md);
      padding: 0 var(--space-md);
      border: 1px solid var(--border);
      border-radius: var(--radius-pill);
      background: var(--surface);
      color: var(--ink);
      font: inherit;
      font-weight: var(--weight-semibold);
      cursor: pointer;
    }
    button:hover {
      background: var(--surface-hover);
    }
    button:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 2px;
    }
    .clear {
      border-color: transparent;
      background: transparent;
      color: var(--primary-strong);
    }
    @container filters-bar (max-width: 66rem) {
      .bar {
        display: grid;
        grid-template-columns: minmax(20rem, 1fr) minmax(10rem, 1fr) auto auto;
        grid-template-areas:
          'type category price clear';
        border-radius: var(--radius-lg);
      }
      .bar > app-segmented-control {
        grid-area: type;
      }
      .bar > app-category-picker {
        grid-area: category;
        width: 100%;
        min-width: 0;
      }
      .bar > app-price-control {
        grid-area: price;
      }
      .bar > .clear {
        grid-area: clear;
        justify-self: end;
      }
    }
    @container filters-bar (max-width: 44rem) {
      .bar {
        display: flex;
      }
      .bar > app-category-picker {
        width: auto;
      }
    }
    @container filters-bar (max-width: 33rem) {
      .bar {
        display: grid;
        grid-template-columns: minmax(0, 1fr);
        grid-template-areas:
          'type'
          'category'
          'price'
          'clear';
      }
      .bar > app-segmented-control {
        width: 100%;
        min-width: min(100%, 20rem);
      }
      .bar > app-category-picker {
        width: 100%;
      }
      .bar > app-price-control {
        justify-self: start;
      }
    }
    .filters-button {
      display: inline-flex;
      align-items: center;
      gap: var(--space-sm);
      min-height: max(var(--control-md), 44px);
      margin-left: auto;
    }
    .sheet {
      position: fixed;
      inset: 0;
      z-index: var(--z-modal);
      display: flex;
      flex-direction: column;
      background: var(--surface-modal);
    }
    .sheet header,
    .sheet footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-sm);
      padding: var(--space-md);
    }
    .sheet header {
      border-bottom: 1px solid var(--border);
    }
    .sheet footer {
      border-top: 1px solid var(--border);
      background: var(--surface-modal);
    }
    .sheet h2 {
      margin: 0;
      font-size: var(--text-heading);
      font-weight: var(--weight-semibold);
    }
    .sheet-body {
      flex: 1;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: var(--space-lg);
      padding: var(--space-md);
    }
    .section-title {
      margin: 0 0 var(--space-sm);
      font-size: var(--text-caption);
      font-weight: var(--weight-semibold);
      color: var(--text-muted);
    }
    .primary {
      flex: 1;
      border-color: var(--primary);
      background: var(--primary);
      color: var(--text-on-primary);
    }
    .primary:hover {
      background: var(--primary-strong);
    }
  `,
  template: `
    @if (search.filterRefused()) {
      <app-banner kind="warning" text="This filter is not available yet" />
    }
    @if (phone()) {
      <div class="phone-row" [class.compact]="compactPhone()">
        @if (!compactPhone()) {
          <app-segmented-control
            label="Listing type"
            [options]="typeOptions"
            [selected]="search.state().type ?? ''"
            (selectedChange)="setType($event)"
          />
        }
        <button
          #sheetTrigger
          type="button"
          class="filters-button"
          aria-haspopup="dialog"
          (click)="openSheet()"
        >
          Filters
          <app-count-badge [count]="search.filters()" label="active filters" />
        </button>
      </div>
      @if (sheetOpen()) {
        <div
          #sheet
          class="sheet"
          role="dialog"
          aria-modal="true"
          aria-labelledby="filters-sheet-title"
          [class.compact]="compactPhone()"
          (keydown)="sheetKey($event)"
        >
          <header>
            <h2 id="filters-sheet-title">Filters</h2>
            <button #close type="button" aria-label="Close filters" (click)="closeSheet()">
              Close
            </button>
          </header>
          <div class="sheet-body">
            @if (compactPhone()) {
              <section>
                <h3 class="section-title">Listing type</h3>
                <app-segmented-control
                  label="Listing type"
                  [options]="typeOptions"
                  [selected]="search.state().type ?? ''"
                  (selectedChange)="setType($event)"
                />
              </section>
            }
            <section>
              <h3 class="section-title">Category</h3>
              <app-category-picker
                [inline]="true"
                [flattened]="true"
                [tree]="visibleTree()"
                [disabled]="!search.state().type"
                [selected]="search.state().cat"
                (select)="search.update({ cat: $event })"
              />
            </section>
            <app-price-control
              [inlineOnly]="true"
              [min]="search.state().min"
              [max]="search.state().max"
              (commit)="search.update($event)"
              (clear)="search.update({ min: null, max: null })"
            />
            @if (attributes().length) {
              <app-attribute-filters
                [attributes]="attributes()"
                [attrs]="search.state().attrs"
                [facets]="search.facets()"
                (attrsChange)="search.update({ attrs: $event })"
              />
            }
          </div>
          <footer>
            <button type="button" class="primary" (click)="closeSheet()">
              Show {{ search.total() }} {{ search.total() === 1 ? 'result' : 'results' }}
            </button>
            <button type="button" (click)="search.clearFilters()">Clear</button>
          </footer>
        </div>
      }
    } @else {
      <div class="bar-container">
        <div class="bar" role="group" aria-label="Filters">
          <app-segmented-control
            label="Listing type"
            [options]="typeOptions"
            [selected]="search.state().type ?? ''"
            (selectedChange)="setType($event)"
          />
          <app-category-picker
            [flattened]="true"
            [tree]="visibleTree()"
            [disabled]="!search.state().type"
            [selected]="search.state().cat"
            (select)="search.update({ cat: $event })"
          />
          <app-price-control
            [min]="search.state().min"
            [max]="search.state().max"
            (commit)="search.update($event)"
            (clear)="search.update({ min: null, max: null })"
          />
          @if (search.hasFilters()) {
            <button type="button" class="clear" (click)="search.clearFilters()">Clear</button>
          }
        </div>
      </div>
      @if (attributes().length) {
        <div class="attributes">
          <app-attribute-filters
            [attributes]="attributes()"
            [attrs]="search.state().attrs"
            [facets]="search.facets()"
            (attrsChange)="search.update({ attrs: $event })"
          />
        </div>
      }
    }
  `,
})
export class SearchFilters {
  readonly tree = input.required<readonly CategoryTreeNode[]>();
  readonly attributes = input<readonly AttributeDefinitionResponse[]>([]);

  protected readonly search = inject(SearchStateService);
  private readonly selectedType = computed(() => this.search.state().type);
  protected readonly visibleTree = computed(() =>
    categoriesForType(this.tree(), this.selectedType()).flatMap((root) => root.children),
  );
  protected readonly phone = mediaQuerySignal(PHONE_QUERY);
  protected readonly compactPhone = mediaQuerySignal(COMPACT_PHONE_QUERY);
  protected readonly sheetOpen = signal(false);
  protected readonly typeOptions: readonly SelectionOption[] = [
    { value: '', label: 'All' },
    { value: 'HARDWARE', label: 'Hardware' },
    { value: 'SOFTWARE', label: 'Software' },
  ];
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly sheetTrigger = viewChild<ElementRef<HTMLButtonElement>>('sheetTrigger');
  private readonly sheet = viewChild<ElementRef<HTMLElement>>('sheet');
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('close');

  constructor() {
    effect(() => {
      const state = this.search.state();
      const categoryType = categoryListingType(this.tree(), state.cat);
      if (!state.type && state.cat && categoryType) {
        this.search.update({ type: categoryType });
        return;
      }
      if (state.type && state.cat && categoryType && categoryType !== state.type) {
        this.search.update({ cat: null, attrs: {} });
      }
    });
    effect(() => {
      if (!this.phone()) this.sheetOpen.set(false);
    });
    effect((onCleanup) => {
      if (!this.sheetOpen()) return;
      const body = this.document.body;
      const previous = body.style.overflow;
      body.style.overflow = 'hidden';
      onCleanup(() => (body.style.overflow = previous));
    });
  }

  protected setType(value: string): void {
    const type: ListingType | null = value === 'HARDWARE' || value === 'SOFTWARE' ? value : null;
    if (!type) {
      this.search.update({ type: null, cat: null, attrs: {} });
      return;
    }
    const state = this.search.state();
    const categoryType = categoryListingType(this.tree(), state.cat);
    this.search.update(
      type && categoryType && categoryType !== type ? { type, cat: null, attrs: {} } : { type },
    );
  }

  protected openSheet(): void {
    this.sheetOpen.set(true);
    afterNextRender(() => this.closeButton()?.nativeElement.focus(), { injector: this.injector });
  }

  protected closeSheet(): void {
    this.sheetOpen.set(false);
    this.sheetTrigger()?.nativeElement.focus();
  }

  protected sheetKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closeSheet();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = Array.from(
      this.sheet()?.nativeElement.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [],
    );
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = this.document.activeElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
