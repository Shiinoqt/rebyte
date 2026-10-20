import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `<h1 tabindex="-1">Page not found</h1>
    <p>The page you are looking for does not exist or has moved.</p>
    <a class="back" routerLink="/">
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
        <path d="m15 18-6-6 6-6" />
      </svg>
      Back to home
    </a>`,
  styles: `
    :host {
      display: flex;
      flex: 1;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      text-align: center;
    }
    h1 {
      margin: 0;
      font-size: var(--text-display);
      font-weight: var(--weight-bold);
      line-height: var(--leading-heading);
      letter-spacing: var(--tracking-display);
    }
    p {
      color: var(--text-muted);
      margin: var(--space-md) 0;
    }
    .back {
      display: inline-flex;
      align-items: center;
      gap: var(--space-sm);
      min-height: var(--hit-min);
      color: var(--primary);
    }
    .back svg { width: 20px; height: 20px; }
    .back:hover { color: var(--primary-strong); }
    .back:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
  `,
})
export class NotFoundPage {}
