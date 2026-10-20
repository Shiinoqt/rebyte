import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AccountNav, AccountSection, narrowViewport } from './account-nav';

// One projection slot keeps the content mounted while the sidebar and back link swap at 900px.
@Component({
  selector: 'app-account-layout',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AccountNav],
  template: `
    <div class="account-layout" [class.narrow]="narrow()">
      @if (!narrow()) {
        <app-account-nav class="account-sidebar" variant="sidebar" [active]="active()" />
      }
      <div class="account-main">
        @if (narrow()) {
          <a class="back" routerLink="/me">
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="m15 18-6-6 6-6" /></svg>
            Account
          </a>
        }
        <ng-content />
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      max-width: var(--container-wide);
      margin-inline: auto;
    }
    .account-layout {
      display: grid;
      grid-template-columns: 280px minmax(0, 1fr);
      align-items: start;
      gap: var(--space-2xl);
    }
    .account-layout.narrow {
      grid-template-columns: minmax(0, 1fr);
    }
    .account-sidebar {
      position: sticky;
      top: calc(var(--header-height) + var(--space-lg));
    }
    .account-main {
      display: flex;
      flex-direction: column;
      gap: var(--space-lg);
      min-width: 0;
    }
    .back {
      display: inline-flex;
      align-self: flex-start;
      align-items: center;
      gap: var(--space-xs);
      min-height: var(--hit-min);
      color: var(--primary);
      font-weight: var(--weight-semibold);
      text-decoration: none;
    }
    .back:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 2px;
    }
    .back svg {
      width: 20px;
      height: 20px;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.75;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
  `,
})
export class AccountLayout {
  readonly active = input<AccountSection | null>(null);
  readonly narrow = narrowViewport();
}

// Route host for the standalone account pages, which name their sidebar row in route data.
@Component({
  selector: 'app-account-section-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AccountLayout, RouterOutlet],
  template: `<app-account-layout [active]="section()"><router-outlet /></app-account-layout>`,
  styles: `
    router-outlet {
      display: none;
    }
  `,
})
export class AccountSectionPage {
  readonly section = input.required<AccountSection>();
}
