import {
  ChangeDetectionStrategy,
  Component,
  Pipe,
  PipeTransform,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ToastService } from './feedback';

const eurFormatter = new Intl.NumberFormat('en-IE', { style: 'currency', currency: 'EUR' });

@Pipe({ name: 'eur', standalone: true })
export class EurPipe implements PipeTransform {
  transform(value: string): string {
    // Intl accepts decimal strings exactly; TypeScript's lib signature omits that overload.
    return (eurFormatter.format as unknown as (decimal: string) => string)(value);
  }
}

@Component({
  selector: 'app-avatar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './content.css',
  host: {
    'data-ui': 'avatar',
    class: 'avatar',
    role: 'img',
    '[attr.aria-label]': "'@' + username()",
    '[class.own]': 'own()',
  },
  template: `@if (url() && failedUrl() !== url()) {
      <img [src]="url()" [alt]="'@' + username()" width="40" height="40" (error)="failedUrl.set(url())" />
    } @else {
      <span aria-hidden="true">{{ initial() }}</span>
    }`,
})
export class Avatar {
  readonly username = input.required<string>();
  readonly url = input<string | null>(null);
  readonly own = input(false);
  readonly failedUrl = signal<string | null>(null);
  readonly initial = computed(() => Array.from(this.username().trim())[0]?.toUpperCase() ?? '?');
}

export interface ListingCardData {
  id: string;
  title: string;
  price: string | null;
  type?: 'HARDWARE' | 'SOFTWARE';
  status?: 'ACTIVE' | 'SOLD' | 'REMOVED';
  coverUrl?: string | null;
  condition?: 'LIKE_NEW' | 'VERY_GOOD' | 'GOOD' | 'ACCEPTABLE' | null;
  shippingLocation?: string | null;
  sellerUsername?: string | null;
  platforms?: readonly ('WINDOWS' | 'MACOS' | 'LINUX' | 'WEB' | 'ANDROID' | 'IOS')[] | null;
  currentVersion?: string | null;
}
const conditionLabels = {
  LIKE_NEW: 'Like new',
  VERY_GOOD: 'Very good',
  GOOD: 'Good',
  ACCEPTABLE: 'Acceptable',
};
const platformLabels = {
  WINDOWS: 'Windows',
  MACOS: 'macOS',
  LINUX: 'Linux',
  WEB: 'Web',
  ANDROID: 'Android',
  IOS: 'iOS',
};

@Component({
  selector: 'app-listing-card',
  standalone: true,
  imports: [RouterLink, EurPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './content.css',
  host: { 'data-ui': 'listing-card' },
  template: `<article class="card">
    <a class="listing-link" [routerLink]="['/listings', listing().id]">
      <div class="listing-photo">
        @if (listing().coverUrl && failedUrl() !== listing().coverUrl) {
          <img
            [src]="listing().coverUrl"
            [alt]="listing().title"
            loading="lazy"
            width="500"
            height="400"
            (error)="failedUrl.set(listing().coverUrl ?? null)"
          />
        } @else {
          <div class="placeholder" aria-hidden="true"><span>no photo</span></div>
        }
        @if (listing().type === 'SOFTWARE') {
          <span class="type">SOFTWARE</span>
        }
      </div>
      <div class="heading">
        <span class="title" [title]="listing().title">{{ listing().title }}</span
        ><span class="price">{{
          listing().price === null ? 'Price unavailable' : (listing().price! | eur)
        }}</span>
      </div>
      @if (meta()) {
        <p class="meta">{{ meta() }}</p>
      }
    </a>
    @if (showFavourite()) {
      <button
        class="favourite"
        type="button"
        [class.active]="favourite()"
        [attr.aria-pressed]="favourite()"
        [attr.aria-label]="
          (favourite() ? 'Remove from favourites: ' : 'Add to favourites: ') + listing().title
        "
        (click)="favouriteChange.emit(!favourite())"
      >
        <svg
          aria-hidden="true"
          viewBox="0 0 24 24"
          stroke="currentColor"
          stroke-width="1.75"
          stroke-linecap="round"
          stroke-linejoin="round"
          [attr.fill]="favourite() ? 'currentColor' : 'none'"
        >
          <path
            d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78L12 21.23l8.84-8.84a5.5 5.5 0 0 0 0-7.78Z"
          />
        </svg>
      </button>
    }
  </article>`,
})
export class ListingCard {
  readonly showFavourite = input(true);
  readonly listing = input.required<ListingCardData>();
  readonly favourite = input(false);
  readonly favouriteChange = output<boolean>();
  readonly failedUrl = signal<string | null>(null);
  readonly meta = computed(() => {
    const listing = this.listing();
    const parts =
      listing.type === 'HARDWARE'
        ? [listing.condition ? conditionLabels[listing.condition] : null, listing.shippingLocation]
        : listing.type === 'SOFTWARE'
          ? [
              listing.platforms?.map((platform) => platformLabels[platform]).join(', '),
              listing.currentVersion ? `v${listing.currentVersion}` : null,
            ]
          : [];
    if (listing.sellerUsername) parts.push(`@${listing.sellerUsername}`);
    return parts.filter(Boolean).join(' · ');
  });
}

@Component({
  selector: 'app-status-chip',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './content.css',
  host: { 'data-ui': 'status-chip', '[class]': "'chip ' + tone()", '[attr.data-tone]': 'tone()' },
  template: `{{ text() }}`,
})
export class StatusChip {
  readonly text = input.required<string>();
  readonly tone = input<'neutral' | 'accent' | 'danger' | 'warning'>('neutral');
}

export interface MoneyRow {
  label: string;
  value: string;
  total?: boolean;
}

@Component({
  selector: 'app-money-rows',
  standalone: true,
  imports: [EurPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './content.css',
  host: { 'data-ui': 'money-rows' },
  template: `<dl>
    @for (row of rows(); track $index) {
      <div class="money-row" [class.total]="row.total">
        <dt>{{ row.label }}</dt>
        <dd>{{ row.value | eur }}</dd>
      </div>
    }
  </dl>`,
})
export class MoneyRows {
  readonly rows = input.required<readonly MoneyRow[]>();
}

@Component({
  selector: 'app-copy-field',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './content.css',
  host: { 'data-ui': 'copy-field', class: 'copy-field' },
  template: `@if (showLabel()) {
      <span class="copy-label">{{ label() }}</span>
    }
    <code>{{ value() }}</code>
    <button
      type="button"
      [disabled]="copying()"
      [attr.aria-busy]="copying()"
      [attr.aria-label]="'Copy ' + label()"
      [attr.title]="'Copy ' + label()"
      (click)="copy()"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
        <rect x="9" y="9" width="11" height="11" rx="2" />
        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
      </svg>
    </button>
    @if (error()) {
      <p class="copy-error" role="alert">{{ error() }}</p>
    }`,
})
export class CopyField {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly showLabel = input(true);
  readonly copying = signal(false);
  readonly error = signal('');
  private readonly toasts = inject(ToastService);

  async copy(): Promise<void> {
    if (this.copying()) return;
    this.copying.set(true);
    this.error.set('');
    const label = this.label();
    const value = this.value();
    try {
      await navigator.clipboard.writeText(value);
      this.toasts.show({ text: `${label} copied`, kind: 'success' });
    } catch {
      this.error.set('Copy failed. Select the value and copy it manually.');
    } finally {
      this.copying.set(false);
    }
  }
}
