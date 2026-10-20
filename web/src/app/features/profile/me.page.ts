import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ErrorState, Skeleton } from '../../shared/ui/feedback';
import { AccountLayout } from './account-layout';
import { AccountNav, narrowViewport } from './account-nav';
import { ME_TABS, MeTabsStore } from './me-tabs.store';
import { MeListingsTab } from './me-listings.tab';
import { MeLibraryTab, MeLibraryStore } from './me-library.tab';
import { MeReviewsTab, MeReviewsStore } from './me-reviews.tab';

@Component({
  selector: 'app-me-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AccountLayout,
    AccountNav,
    ErrorState,
    Skeleton,
    MeListingsTab,
    MeLibraryTab,
    MeReviewsTab,
  ],
  providers: [MeTabsStore, MeLibraryStore, MeReviewsStore],
  styleUrl: './me.page.css',
  template: `
    @if (narrow() && tabs.rawTab() === null) {
      <div class="account-list">
        <h1 class="visually-hidden" tabindex="-1">Your account</h1>
        <app-account-nav variant="list" />
      </div>
    } @else {
      <app-account-layout [active]="tabs.tab()">
        <h1 [id]="headingId" tabindex="-1">{{ sectionLabel() }}</h1>
        <section class="section" [attr.aria-labelledby]="headingId" [attr.data-tab]="tabs.tab()">
          @switch (tabs.activeStatus()) {
            @case ('loading') {
              <app-skeleton [label]="'Loading ' + sectionLabel()" />
            }
            @case ('error') {
              <app-error-state (retry)="tabs.retry()" />
            }
          }
          <div [attr.data-tab-content]="tabs.tab()">
            @if (tabs.tab() === 'listings') {
              <app-me-listings-tab />
            }
            @if (tabs.tab() === 'library') {
              <app-me-library-tab />
            }
            @if (tabs.tab() === 'reviews') {
              <app-me-reviews-tab />
            }
          </div>
        </section>
      </app-account-layout>
    }
  `,
})
export class MePage {
  readonly tabs = inject(MeTabsStore);
  readonly narrow = narrowViewport();
  readonly headingId = 'me-section-title';
  readonly sectionLabel = computed(
    () => ME_TABS.find((tab) => tab.value === this.tabs.tab())?.label ?? '',
  );
}
