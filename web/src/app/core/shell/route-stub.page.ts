import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-route-stub',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<h1 tabindex="-1">{{ stubTitle() }}</h1>',
  styles: `
    h1 {
      font-size: var(--text-display);
      font-weight: var(--weight-bold);
      line-height: var(--leading-heading);
      letter-spacing: var(--tracking-display);
    }
    :host-context(app-account-section-page) h1 {
      margin: 0;
      font-size: var(--text-heading);
      letter-spacing: var(--tracking-heading);
    }
  `,
})
export class RouteStubPage {
  readonly stubTitle = input.required<string>();
}
