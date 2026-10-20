import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog-api';
import { EurPipe } from '../../shared/ui/content';

const CONDITION_LABELS: Record<string, string> = {
  LIKE_NEW: 'Like new',
  VERY_GOOD: 'Very good',
  GOOD: 'Good',
  ACCEPTABLE: 'Acceptable',
};

// Decorative marketing column: the cards are not links and the whole block is hidden from assistive tech.
@Component({
  selector: 'app-auth-showcase',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EurPipe],
  template: `<ul class="points">
      @for (point of points; track point) {
        <li>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>{{ point }}
        </li>
      }
    </ul>
    @if (cards().length) {
      <div class="stack" aria-hidden="true">
        @for (card of cards(); track card.id) {
          <div class="card">
            <img [src]="card.coverUrl" alt="" width="184" height="116" loading="lazy" />
            <span class="title">{{ card.title }}</span>
            <span class="meta"
              ><span class="price">{{ card.price | eur }}</span>
              @if (card.condition) {
                · {{ conditionLabel(card.condition) }}
              }</span
            >
          </div>
        }
      </div>
    }`,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-lg);
    }
    .points {
      display: grid;
      gap: var(--space-sm);
      margin: 0;
      padding: 0;
      list-style: none;
      color: var(--ink);
    }
    .points li {
      display: flex;
      align-items: center;
      gap: var(--space-sm);
    }
    .points svg {
      flex: none;
      width: 18px;
      height: 18px;
      fill: none;
      stroke: var(--primary);
      stroke-width: 2.25;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .stack {
      position: relative;
      align-self: center;
      width: min(440px, 100%);
      height: 224px;
    }
    .card {
      position: absolute;
      top: var(--space-md);
      left: 50%;
      display: flex;
      flex-direction: column;
      gap: 2px;
      width: 184px;
      padding: var(--space-sm);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-md);
      transform: translateX(-50%);
    }
    .card:nth-child(1) {
      transform: translateX(-112%) rotate(-7deg);
    }
    .card:nth-child(3) {
      transform: translateX(12%) rotate(7deg);
    }
    .card:nth-child(2) {
      z-index: 1;
      top: 0;
    }
    img {
      width: 100%;
      height: 116px;
      margin-bottom: var(--space-xs);
      border-radius: var(--radius-md);
      background: var(--surface-sunken);
      object-fit: cover;
    }
    .title {
      overflow: hidden;
      color: var(--ink);
      font-weight: var(--weight-semibold);
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .meta {
      color: var(--text-muted);
      font-size: var(--text-caption);
    }
    .price {
      color: var(--ink);
      font-family: var(--font-mono);
      font-variant-numeric: tabular-nums;
    }
    @media (min-width: 1800px) {
      :host {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 440px;
        align-items: center;
      }
      .stack {
        justify-self: end;
      }
    }
    @media (max-height: 700px) {
      .stack {
        display: none;
      }
    }
  `,
})
export class AuthShowcase {
  private readonly catalog = inject(CatalogApi);
  protected readonly points = [
    'Category specs on every hardware listing',
    'Secure checkout with PayPal',
    'Software licences delivered with your order',
  ];
  // shortcut: newest three listings with photos, no curation; upgrade to a featured endpoint if one exists.
  protected readonly cards = toSignal(
    this.catalog.search({ size: 12 }).pipe(
      map((page) => page.hits.filter((hit) => hit.coverUrl).slice(0, 3)),
      catchError(() => of([])),
    ),
    { initialValue: [] },
  );

  protected conditionLabel(condition: string): string {
    return CONDITION_LABELS[condition] ?? condition;
  }
}
