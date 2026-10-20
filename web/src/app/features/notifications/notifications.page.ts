import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NotificationView } from '../../core/api/api-models';
import { Banner, EmptyState, ErrorState, Skeleton } from '../../shared/ui/feedback';
import { CountBadge, LoadMore } from '../../shared/ui/navigation';
import { NotificationStore, notificationTarget, notificationText } from './notification.store';

@Component({
  selector: 'app-notifications-page', standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [NotificationStore],
  imports: [DatePipe, RouterLink, Banner, EmptyState, ErrorState, Skeleton, CountBadge, LoadMore],
  styleUrl: './notifications.page.css',
  template: `<section class="notification-center">
    <header class="notification-heading">
      <h1 tabindex="-1">Notifications</h1>
      <app-count-badge [count]="store.unreadCount()" label="unread notifications" />
      <a class="email-preferences" routerLink="/settings">
        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
        Email preferences
      </a>
    </header>
    <div class="notification-toolbar">
      <button type="button" class="mark-all-read" aria-label="Mark all as read" title="Mark all as read" (click)="store.markAllRead()" [disabled]="!store.unreadCount() || !!store.busy()"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m2 12 5 5L17 7m-5 10L22 7" /></svg></button>
      <button type="button" (click)="store.removeRead()" [disabled]="!hasRead() || !!store.busy()">Delete read</button>
    </div>
    @if (store.actionError()) { <app-banner kind="error" [text]="store.actionError()" /> }
    @if (store.summaryError()) { <app-banner kind="error" [text]="store.summaryError()" /> }
    @if (store.loading() && !store.loaded()) { <app-skeleton label="Loading notifications" shape="line" /> }
    @if (store.listError()) { <app-error-state title="Notifications could not be loaded" [text]="store.listError()" (retry)="store.load()" /> }
    @if (store.loaded() && !store.items().length && !store.listError()) {
      <app-empty-state title="You're all caught up" text="New messages, offers, orders and price drops will show up here." />
    }
    @if (store.items().length) {
      <div class="notification-list" aria-label="Notifications">
        @for (row of store.items(); track row.id) {
          <article class="notification-row" [class.unread]="!row.read" [attr.data-notification-id]="row.id">
            <span class="notification-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="iconPath(row.type)" /></svg></span>
            <div class="notification-body">
              @if (target(row); as href) {
                <a [href]="href" (click)="open($event, row, href)">{{ text(row) }}</a>
              } @else {
                <span>{{ text(row) }}</span><small class="unavailable">The linked item is unavailable.</small>
              }
              <time [attr.datetime]="row.createdAt" [attr.title]="row.createdAt | date:'medium'">{{ relativeTime(row.createdAt) }}</time>
            </div>
            <div class="notification-actions">
              @if (!row.read) { <button type="button" [disabled]="!!store.busy()" (click)="store.markRead(row.id)">Mark read</button> }
              @if (row.read) { <button class="icon-action" type="button" aria-label="Delete notification" title="Delete notification" [disabled]="!!store.busy()" (click)="store.remove(row.id)">
                <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6" /></svg>
              </button> }
            </div>
          </article>
        }
      </div>
    }
    <app-load-more [hasMore]="!!store.cursor()" [loading]="store.loading()" [count]="store.items().length" noun="notifications" (load)="store.load(true)" />
  </section>`,
})
export class NotificationsPage {
  readonly store = inject(NotificationStore);
  private readonly router = inject(Router);
  readonly target = notificationTarget;
  readonly text = notificationText;
  constructor() { void this.store.load(); void this.store.refreshSummary(); }
  hasRead(): boolean { return this.store.items().some((row) => row.read); }
  iconPath(type: NotificationView['type']): string {
    return ({
      MESSAGE: 'M4 5h16v11H8l-4 3V5Z',
      FAVOURITE: 'M12 21 3.5 12.5a5 5 0 0 1 7-7L12 7l1.5-1.5a5 5 0 0 1 7 7L12 21Z',
      PRICE_DROP: 'M4 6h4l5 6 4-3 3 8m-3 0h3v-3',
      RELEASE: 'M12 3v12m-4-4 4 4 4-4M4 17v3h16v-3',
      REVIEW: 'm12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17l-5.6 3 1.1-6.2L3 9.6l6.2-.9L12 3Z',
      FOLLOW: 'M14 7a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM3 20v-2a6 6 0 0 1 12 0v2m4-9v6m-3-3h6',
      OFFER_RECEIVED: 'M3 5h10l8 8-8 8-10-10V5Zm5 3h.01',
      OFFER_ACCEPTED: 'M3 5h10l8 8-8 8-10-10V5Zm5 3h.01',
      ORDER: 'M3 7 12 3l9 4v10l-9 4-9-4V7Zm0 0 9 4 9-4m-9 4v10',
    })[type];
  }
  relativeTime(at: string): string {
    const minutes = Math.max(0, Math.floor((Date.now() - Date.parse(at)) / 60_000));
    if (minutes < 1) return 'Just now';
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return days < 7 ? `${days}d ago` : new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric' }).format(new Date(at));
  }
  async open(event: MouseEvent, row: NotificationView, href: string): Promise<void> {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (await this.store.markRead(row.id)) await this.router.navigateByUrl(href);
  }
}
