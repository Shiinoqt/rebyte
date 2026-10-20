import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { Subscription, take } from 'rxjs';
import { OrderView, VersionResponse } from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { AuthStore } from '../../core/auth/auth.store';
import { CopyField } from '../../shared/ui/content';
import { Banner, BusyButton } from '../../shared/ui/feedback';
import { FormField } from '../../shared/ui/forms';
import { CHECKOUT_NAVIGATOR } from '../checkout/checkout.service';
import { OrderDetailService } from './order-detail.service';
import { ORDER_CARD_STYLES } from './problem-card';

const DOWNLOAD_ERRORS: Readonly<Record<string, string>> = {
  'licence-required': 'Your licence for this item is not active.',
  'listing-moderated': 'This listing was removed.',
  'artifact-not-available': 'This version has no installer yet.',
  'version-not-found': 'This version is no longer available. Refresh the versions and try again.',
  'listing-not-found': 'This software is no longer available.',
  'order-service-unavailable':
    'Licence verification is temporarily unavailable. Try again in a minute.',
};
const LICENCE_STYLES = `
  .head { display:grid; gap:var(--space-xs); }
  .optional-card { gap:var(--space-lg); }
  .group { display:grid; gap:var(--space-sm); }
  .micro { color:var(--text-muted); font-family:var(--font-mono); font-size:11px; font-weight:var(--weight-semibold); letter-spacing:var(--tracking-micro); text-transform:uppercase; }
  .row { display:flex; align-items:center; flex-wrap:wrap; gap:var(--space-sm); }
  .row.download { align-items:flex-end; }
  .row app-copy-field, .row app-form-field, .masked { flex:1 1 12rem; min-width:0; }
  .masked { font-family:var(--font-mono); color:var(--text-muted); }
  .reveal { display:grid; place-items:center; width:var(--hit-min); min-height:var(--hit-min); padding:0; border:0; border-radius:var(--radius-md); background:transparent; color:var(--text-muted); cursor:pointer; }
  .reveal:hover { color:var(--ink); }
  .reveal:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
  .reveal svg { width:20px; height:20px; fill:none; }
`;
const STALE_VERSION_ERRORS = new Set(['version-not-found', 'artifact-not-available']);
@Component({
  selector: 'app-licence-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, CopyField, Banner, BusyButton, FormField],
  styles: [ORDER_CARD_STYLES, LICENCE_STYLES],
  template: `
    @if (order().license; as licence) {
      @if (licence.status === 'ACTIVE') {
        <section class="optional-card">
          <div class="head">
            <h2>Software licence</h2>
            <p class="note">Issued {{ licence.issuedAt | date: 'd MMM y' }}</p>
          </div>
          @if (buyer() && licence.key; as key) {
            <div class="group">
              <h3 class="micro">Licence key</h3>
              <div class="row">
                @if (shown()) {
                  <app-copy-field label="Licence key" [showLabel]="false" [value]="key" />
                } @else {
                  <span class="masked" role="img" aria-label="Licence key hidden"
                    >•••• •••• •••• ••••</span
                  >
                }
                <button type="button" class="reveal" [attr.aria-label]="shown() ? 'Hide key' : 'Show key'" [attr.title]="shown() ? 'Hide key' : 'Show key'" [attr.aria-pressed]="shown()" (click)="shown.set(!shown())">
                  <svg
                    aria-hidden="true"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    stroke-width="1.75"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  >
                    @if (shown()) {
                      <path
                        d="m3 3 18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6C3.9 8.3 2 12 2 12s3.5 7 10 7a10 10 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2"
                      />
                    } @else {
                      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                      <circle cx="12" cy="12" r="3" />
                    }
                  </svg>
                </button>
              </div>
            </div>
          }
          @if (buyer()) {
            <div class="group">
              <h3 class="micro">Download</h3>
              @if (loading()) {
                <p role="status">Loading software versions...</p>
              }
              @if (versions().length) {
                <div class="row download">
                  <app-form-field
                    [id]="'software-version'"
                    label="Version"
                    kind="select"
                    [options]="options()"
                    [value]="version()"
                    [disabled]="busy()"
                    (valueChange)="version.set($event)"
                  />
                  <app-busy-button
                    label="Download"
                    [busy]="busy()"
                    [disabled]="!canDownload()"
                    (pressed)="download()"
                  />
                </div>
                @if (!selected()?.artifactAvailable) {
                  <p class="note">This version has no installer yet.</p>
                }
              } @else if (!loading() && !error()) {
                <p>No software versions are available.</p>
              }
              @if (error()) {
                <app-banner kind="error" [text]="error()" />
                @if (staleVersions()) {
                  <app-busy-button
                    label="Refresh versions"
                    variant="neutral"
                    [disabled]="busy() || loading()"
                    (pressed)="loadVersions()"
                  />
                }
              }
            </div>
          }
        </section>
      }
    }
  `,
})
export class LicenceCard {
  readonly order = input.required<OrderView>();
  private readonly catalog = inject(CatalogApi);
  private readonly auth = inject(AuthStore);
  private readonly navigate = inject(CHECKOUT_NAVIGATOR);
  private readonly detail = inject(OrderDetailService);
  private requests = new Subscription();
  private generation = 0;
  readonly shown = signal(false);
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly error = signal('');
  /** True when reloading the version list can fix the current error. */
  readonly staleVersions = signal(false);
  readonly version = signal('');
  readonly versions = signal<VersionResponse[]>([]);
  readonly buyer = computed(
    () => !!this.auth.identity()?.sub && this.auth.identity()?.sub === this.order().buyerSub,
  );
  readonly selected = computed(() => this.versions().find((v) => v.number === this.version()));
  readonly canDownload = computed(
    () =>
      this.buyer() &&
      this.order().license?.status === 'ACTIVE' &&
      !!this.selected()?.artifactAvailable &&
      !this.loading(),
  );
  readonly options = computed(() =>
    this.versions().map((v) => ({
      value: v.number,
      label: `${v.number}${v.current ? ' (current)' : ''}${v.artifactAvailable ? '' : ' - installer unavailable'}`,
    })),
  );
  constructor() {
    const unregister = this.auth.registerCleanup(() => this.clear());
    inject(DestroyRef).onDestroy(() => {
      unregister();
      this.clear();
    });
    effect((onCleanup) => {
      this.order();
      this.auth.identity()?.sub;
      this.clear();
      this.loadVersions();
      onCleanup(() => this.clear());
    });
  }
  private clear(): void {
    ++this.generation;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.shown.set(false);
    this.busy.set(false);
    this.loading.set(false);
    this.version.set('');
    this.versions.set([]);
    this.error.set('');
    this.staleVersions.set(false);
  }
  loadVersions(): void {
    if (!this.buyer() || this.order().license?.status !== 'ACTIVE') return;
    const generation = ++this.generation;
    const sub = this.auth.identity()?.sub;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.loading.set(true);
    this.error.set('');
    this.staleVersions.set(false);
    this.requests.add(
      this.catalog
        .versions(this.order().listingId)
        .pipe(take(1))
        .subscribe({
          next: (versions) => {
            if (generation !== this.generation || sub !== this.auth.identity()?.sub) return;
            this.versions.set(versions);
            this.version.set(
              (
                versions.find((v) => v.current && v.artifactAvailable) ??
                versions.find((v) => v.artifactAvailable) ??
                versions[0]
              )?.number ?? '',
            );
            this.loading.set(false);
          },
          error: () => {
            if (generation !== this.generation || sub !== this.auth.identity()?.sub) return;
            this.error.set("We couldn't load software versions. Try again.");
            this.staleVersions.set(true);
            this.loading.set(false);
          },
        }),
    );
  }
  download(): void {
    if (this.busy() || !this.canDownload()) return;
    const generation = this.generation;
    const sub = this.auth.identity()?.sub;
    const order = this.order();
    this.busy.set(true);
    this.error.set('');
    this.staleVersions.set(false);
    const current = () =>
      generation === this.generation &&
      sub === this.auth.identity()?.sub &&
      this.order().id === order.id &&
      this.canDownload();
    this.requests.add(
      this.catalog
        .artifactDownload(order.listingId, this.version())
        .pipe(take(1))
        .subscribe({
          next: (response) => {
            if (!current()) return;
            this.busy.set(false);
            try {
              const target = new URL(response.url);
              if (
                target.protocol !== 'https:' ||
                target.username ||
                target.password ||
                !Number.isFinite(Date.parse(response.expiresAt)) ||
                Date.parse(response.expiresAt) <= Date.now()
              )
                throw new Error();
              this.navigate(target.href);
            } catch {
              this.error.set("We couldn't open a valid download link. Try again.");
            }
          },
          error: (error: unknown) => {
            if (!current()) return;
            const code =
              error && typeof error === 'object' && 'code' in error ? String(error.code) : '';
            this.error.set(
              DOWNLOAD_ERRORS[code] ?? "We couldn't prepare your download. Try again in a minute.",
            );
            this.staleVersions.set(STALE_VERSION_ERRORS.has(code));
            this.busy.set(false);
            // The licence changed after the page loaded; refetch so the order shows its current state.
            if (code === 'licence-required') void this.detail.load(order.id);
          },
        }),
    );
  }
}
