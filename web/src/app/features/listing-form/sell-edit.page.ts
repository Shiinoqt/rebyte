import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  AttributeDefinitionResponse,
  CategoryTreeNode,
  Condition,
  ListingDetailResponse,
  ListingType,
  Platform,
} from '../../core/api/api-models';
import { AppError } from '../../core/http/app-error';
import { errorCopy } from '../../core/http/error-copy';
import { EurPipe } from '../../shared/ui/content';
import { ModalDialog } from '../../shared/ui/dialog';
import { Banner, BusyButton, ErrorState, Skeleton, ToastService } from '../../shared/ui/feedback';
import { FormField, MoneyInput } from '../../shared/ui/forms';
import { SegmentedControl, SelectionOption } from '../../shared/ui/navigation';
import {
  AttributeForm,
  attributeControlId,
  attributeError,
  buildAttributePayload,
  carryOver,
  rawFromListing,
} from './attribute-form';
import { CategoryPicker } from '../home-search/search-filters';
import { LeafOption, leavesUnder, ListingFormService, typeRoot } from './listing-form.service';
import { PhotoPicker, PhotoSelection } from './photo-picker';
import { UploadQueueService } from './upload-queue.service';

type Mode = 'create' | 'edit';
type PageStatus = 'loading' | 'ready' | 'error' | 'missing' | 'not-owner' | 'not-editable';

const CONDITIONS: readonly { value: Condition; label: string }[] = [
  { value: 'LIKE_NEW', label: 'Like new' },
  { value: 'VERY_GOOD', label: 'Very good' },
  { value: 'GOOD', label: 'Good' },
  { value: 'ACCEPTABLE', label: 'Acceptable' },
];
// Countries a seller in the demo can ship from: the EU plus nearby non-EU markets.
const SHIPPING_COUNTRIES = [
  'IT', 'AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'LV',
  'LT', 'LU', 'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE', 'CH', 'NO', 'GB', 'SM', 'VA',
];
const REGION_NAMES = new Intl.DisplayNames(['en'], { type: 'region' });
function countryName(code: string): string {
  return REGION_NAMES.of(code) ?? code;
}
function leafNode(id: string, name: string): CategoryTreeNode {
  return { id, name, leaf: true, children: [] };
}
const PLATFORMS: readonly { value: Platform; label: string }[] = [
  { value: 'WINDOWS', label: 'Windows' },
  { value: 'MACOS', label: 'macOS' },
  { value: 'LINUX', label: 'Linux' },
  { value: 'WEB', label: 'Web' },
  { value: 'ANDROID', label: 'Android' },
  { value: 'IOS', label: 'iOS' },
];
const DEFAULT_LICENCE_PATTERN = 'RBYT-#####-#####-#####-#####';
const PRICE = /^\d{1,8}(\.\d{1,2})?$/;
const VERSION = /^\d{1,9}(\.\d{1,9}){0,7}$/;
const LICENCE_CHARS = /^[A-Z0-9#-]+$/;
const COPY = {
  title: 'Add a title.',
  category: 'Choose the most specific category.',
  condition: 'Choose a condition.',
  description: 'Add a description.',
  price: 'Enter a price greater than 0, with at most 2 decimals.',
  shippingLocation: 'Say where the item ships from.',
  shippingCost: 'Enter 0 or more, with at most 2 decimals.',
  platforms: 'Pick at least one platform.',
  version: 'Use numbers separated by dots, like 1.0.2.',
  versionMissing: 'Add a version number.',
  licence: 'Use A-Z, 0-9, hyphens and at least 16 # characters, at most 64 characters.',
  specifications:
    'Some specifications no longer match this category. Reload the page and try again.',
} as const;
const FIELD_ORDER = [
  'listing-title',
  'listing-category',
  'listing-condition',
  'listing-description',
  'listing-price',
  'listing-shipping-cost',
  'listing-shipping-location',
  'listing-platforms',
  'listing-version',
  'listing-licence-pattern',
];
const ATTRIBUTE_ERRORS = new Set([
  'missing-required-attribute',
  'unknown-attribute',
  'attribute-type-mismatch',
]);
const UNKNOWN_ERROR: AppError = {
  status: 500,
  code: 'unknown-error',
  type: 'about:blank',
  fieldErrors: {},
  extras: {},
};

function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'string'
  );
}

function isValidPrice(value: string, allowZero: boolean): boolean {
  const trimmed = value.trim();
  return PRICE.test(trimmed) && (allowZero || Number(trimmed) > 0);
}

// Mirrors LicenseKeyPattern.isValid in it.rebyte.commons: the server stays authoritative.
function isValidLicencePattern(value: string): boolean {
  return (
    value.length <= 64 &&
    LICENCE_CHARS.test(value) &&
    [...value].filter((character) => character === '#').length >= 16
  );
}

@Component({
  selector: 'app-sell-edit-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AttributeForm,
    CategoryPicker,
    Banner,
    BusyButton,
    ErrorState,
    EurPipe,
    FormField,
    ModalDialog,
    MoneyInput,
    PhotoPicker,
    RouterLink,
    SegmentedControl,
    Skeleton,
  ],
  providers: [ListingFormService, PhotoSelection, UploadQueueService],
  styleUrl: './sell-edit.page.css',
  template: `
    <h1 tabindex="-1">{{ mode === 'create' ? 'List an item' : 'Edit listing' }}</h1>

    @switch (status()) {
      @case ('loading') {
        <app-skeleton label="Loading the listing form" shape="line" />
      }
      @case ('error') {
        <app-error-state (retry)="load()" />
      }
      @case ('missing') {
        <div class="unavailable" role="alert">
          <h2>This listing isn't available</h2>
          <p>It may have been sold or removed.</p>
          <a routerLink="/me" [queryParams]="{ tab: 'listings' }">Back to your listings</a>
        </div>
      }
      @case ('not-owner') {
        <div class="unavailable" role="alert">
          <h2>You can't edit this listing</h2>
          <p>Only the seller can change it.</p>
          <a [routerLink]="['/listings', listingId]">Back to the listing</a>
        </div>
      }
      @case ('not-editable') {
        <div class="unavailable" role="alert">
          <h2>Sold or removed listings can't be edited.</h2>
          <a routerLink="/me" [queryParams]="{ tab: 'listings' }">Back to your listings</a>
        </div>
      }
      @case ('ready') {
        <div class="form-layout">
          <app-photo-picker [disabled]="saving() || media.pending()" [states]="media.states()" />
          @if (media.busy()) {
            <p class="media-progress" role="status">{{ media.message() }}</p>
          } @else if (media.pending()) {
            <section class="media-recovery" tabindex="-1" aria-label="Photo save needs attention">
              <p role="alert">{{ mediaFailurePrefix() }} {{ media.message() }}</p>
              @if (mode === 'edit' && media.removalsSaved()) {
                <p class="hint">
                  Listing changes and removed photos are already saved. Successful uploads are
                  retained.
                </p>
              }
              <div class="row-actions">
                <button type="button" class="btn" (click)="recoverMedia(false)">
                  Retry failed
                </button>
                @if (media.failureStage() === 'photos') {
                  <button type="button" class="btn" (click)="recoverMedia(true)">
                    Continue with successful photos
                  </button>
                }
              </div>
            </section>
          }

          <fieldset
            class="form-column"
            aria-label="Listing details"
            [disabled]="saving() || media.pending()"
          >
            @if (attention(); as count) {
              <app-banner
                kind="error"
                [text]="count === 1 ? '1 field needs attention' : count + ' fields need attention'"
              />
            }

            @if (mode === 'create') {
              <div class="type">
                <app-segmented-control
                  label="Listing type"
                  [options]="typeOptions()"
                  [selected]="type()"
                  (selectedChange)="switchType($event)"
                />
                @if (service.isDeveloper() === false) {
                  <button type="button" class="btn neutral" (click)="developerDialog.set(true)">
                    Become a developer
                  </button>
                }
              </div>
            }

            <app-form-field
              [id]="'listing-title'"
              label="Title"
              [required]="true"
              [value]="title()"
              [error]="errors()['listing-title'] ?? ''"
              (valueChange)="title.set($event)"
              (blurred)="validateField('listing-title')"
            />

            <div class="row">
              <div class="field">
                <label for="listing-category">Category <span aria-hidden="true">*</span></label>
                <app-category-picker
                  triggerId="listing-category"
                  [leafOnly]="true"
                  [tree]="categoryTree()"
                  [selected]="categoryId() || null"
                  [invalid]="!!errors()['listing-category']"
                  [describedBy]="errors()['listing-category'] ? 'listing-category-error' : null"
                  (select)="pickCategory($event ?? '')"
                />
                @if (errors()['listing-category']) {
                  <p class="field-error" id="listing-category-error" role="alert">
                    {{ errors()['listing-category'] }}
                  </p>
                }
              </div>
              @if (mode === 'create' && type() === 'HARDWARE') {
                <div class="field">
                  <label for="listing-condition">Condition <span aria-hidden="true">*</span></label>
                  <app-category-picker
                    triggerId="listing-condition"
                    placeholder="Select a condition"
                    [leafOnly]="true"
                    [filterable]="false"
                    [tree]="conditionOptions"
                    [selected]="condition() || null"
                    [invalid]="!!errors()['listing-condition']"
                    [describedBy]="errors()['listing-condition'] ? 'listing-condition-error' : null"
                    (select)="setCondition($event)"
                  />
                  @if (errors()['listing-condition']) {
                    <p class="field-error" id="listing-condition-error" role="alert">
                      {{ errors()['listing-condition'] }}
                    </p>
                  }
                </div>
              }
            </div>

            <app-form-field
              [id]="'listing-description'"
              label="Description"
              kind="textarea"
              [rows]="4"
              [required]="true"
              [value]="description()"
              [error]="errors()['listing-description'] ?? ''"
              (valueChange)="description.set($event)"
              (blurred)="validateField('listing-description')"
            />

            @if (mode === 'create') {
              <div [class.row]="type() === 'HARDWARE'">
                <app-money-input
                  [id]="'listing-price'"
                  label="Price"
                  [required]="true"
                  [value]="price()"
                  [error]="errors()['listing-price'] ?? ''"
                  (valueChange)="price.set($event)"
                  (blurred)="validateField('listing-price')"
                />
                @if (type() === 'HARDWARE') {
                  <app-money-input
                    [id]="'listing-shipping-cost'"
                    label="Shipping cost"
                    hint="Enter 0 for free shipping"
                    [required]="true"
                    [value]="shippingCost()"
                    [error]="errors()['listing-shipping-cost'] ?? ''"
                    (valueChange)="shippingCost.set($event)"
                    (blurred)="validateField('listing-shipping-cost')"
                  />
                }
              </div>
            } @else {
              <div class="price-block">
                <p class="current-price">
                  <span class="label">Price</span>
                  <span class="amount">{{ currentPrice() | eur }}</span>
                </p>
                @if (priceOpen()) {
                  <div class="price-editor" role="group" aria-label="Change price">
                    <app-money-input
                      [id]="'listing-new-price'"
                      label="New price"
                      [value]="newPrice()"
                      [error]="priceError()"
                      (valueChange)="newPrice.set($event)"
                    />
                    <p class="price-note">
                      Price changes are public. People who favourited this item are notified when
                      the price drops.
                    </p>
                    <div class="row-actions">
                      <app-busy-button
                        label="Update price"
                        [busy]="priceBusy()"
                        (pressed)="changePrice()"
                      />
                      <button type="button" class="btn neutral" (click)="closePrice()">
                        Close
                      </button>
                    </div>
                  </div>
                } @else {
                  <button type="button" class="btn neutral" (click)="openPrice()">
                    Change price
                  </button>
                }
              </div>
            }

            @if (mode === 'create' && type() === 'HARDWARE') {
              <div class="row">
                <div class="field">
                  <label for="listing-shipping-country">Ships from country <span aria-hidden="true">*</span></label>
                  <app-category-picker
                    triggerId="listing-shipping-country"
                    placeholder="Select a country"
                    filterLabel="Filter countries"
                    [leafOnly]="true"
                    [tree]="countryOptions"
                    [selected]="shippingCountry()"
                    (select)="shippingCountry.set($event ?? 'IT')"
                  />
                </div>
                <app-form-field
                  [id]="'listing-shipping-location'"
                  label="City"
                  [required]="true"
                  [value]="shippingCity()"
                  [error]="errors()['listing-shipping-location'] ?? ''"
                  (valueChange)="shippingCity.set($event)"
                  (blurred)="validateField('listing-shipping-location')"
                />
              </div>
            }

            @if (mode === 'create' && type() === 'SOFTWARE') {
              <fieldset
                class="platforms"
                [attr.aria-invalid]="!!errors()['listing-platforms']"
                [attr.aria-describedby]="
                  errors()['listing-platforms'] ? 'listing-platforms-error' : null
                "
              >
                <legend>Platforms <span aria-hidden="true">*</span></legend>
                <div class="chips">
                  @for (platform of platformOptions; track platform.value) {
                    <label class="chip" [class.on]="platforms().includes(platform.value)">
                      <input
                        type="checkbox"
                        name="listing-platforms"
                        [value]="platform.value"
                        [checked]="platforms().includes(platform.value)"
                        (change)="togglePlatform(platform.value)"
                      />
                      {{ platform.label }}
                    </label>
                  }
                </div>
                @if (errors()['listing-platforms']) {
                  <p class="field-error" id="listing-platforms-error" role="alert">
                    {{ errors()['listing-platforms'] }}
                  </p>
                }
              </fieldset>
              <div class="row">
                <app-form-field
                  class="mono"
                  [id]="'listing-version'"
                  label="First version number"
                  [required]="true"
                  placeholder="1.0.0"
                  [value]="versionNumber()"
                  [error]="errors()['listing-version'] ?? ''"
                  (valueChange)="versionNumber.set($event)"
                  (blurred)="validateField('listing-version')"
                />
              </div>
              <app-form-field
                [id]="'listing-notes'"
                label="Release notes"
                kind="textarea"
                [rows]="3"
                [value]="notes()"
                (valueChange)="notes.set($event)"
              />
            }

            @if (type() === 'SOFTWARE') {
              <details class="licence" [open]="!!errors()['listing-licence-pattern']">
                <summary>Licence key format</summary>
                <app-form-field
                  class="mono"
                  [id]="'listing-licence-pattern'"
                  label="Licence key pattern"
                  [hint]="licenceHint()"
                  [value]="licencePattern()"
                  [error]="errors()['listing-licence-pattern'] ?? ''"
                  (valueChange)="licencePattern.set($event)"
                  (blurred)="validateField('listing-licence-pattern')"
                />
              </details>
            }

            @if (schemaLoading()) {
              <app-skeleton label="Loading specifications" shape="line" />
            } @else if (schemaError()) {
              <app-error-state title="Specifications could not be loaded" (retry)="retrySchema()" />
            } @else if (definitions().length || cleared() || specificationsError()) {
              <section class="specifications" aria-labelledby="listing-specifications-title">
                @if (definitions().length) {
                  <h2 id="listing-specifications-title">Specifications</h2>
                }
                @if (cleared(); as count) {
                  <app-banner
                    kind="warning"
                    [text]="
                      (count === 1
                        ? '1 specification was cleared'
                        : count + ' specifications were cleared') +
                      ' because they do not apply to this category'
                    "
                  />
                }
                @if (specificationsError()) {
                  <p
                    class="form-error"
                    id="listing-specifications-error"
                    role="alert"
                    tabindex="-1"
                  >
                    {{ specificationsError() }}
                  </p>
                }
                <app-attribute-form
                  [definitions]="definitions()"
                  [values]="attributes()"
                  [errors]="attributeErrors()"
                  (valueChange)="setAttribute($event)"
                  (blurred)="validateAttribute($event)"
                />
              </section>
            }

            @if (mode === 'create') {
              <div class="payment">
                <span class="label">Payment</span>
                <span
                  class="payment-chip"
                  aria-disabled="true"
                  aria-describedby="listing-payment-reason"
                  >PayPal</span
                >
                <p class="hint" id="listing-payment-reason">Rebyte checkout accepts PayPal only.</p>
              </div>
            } @else if (listing(); as current) {
              <dl class="fixed-facts" aria-describedby="listing-fixed-reason">
                <div>
                  <dt>Type</dt>
                  <dd>{{ current.type === 'HARDWARE' ? 'Hardware' : 'Software' }}</dd>
                </div>
                @if (current.type === 'HARDWARE') {
                  <div>
                    <dt>Condition</dt>
                    <dd>{{ conditionLabel(current.condition) }}</dd>
                  </div>
                  <div>
                    <dt>Shipping from</dt>
                    <dd>{{ current.shippingLocation }}</dd>
                  </div>
                  <div>
                    <dt>Shipping cost</dt>
                    <dd>
                      @if (current.shippingCost !== null) {
                        {{ current.shippingCost | eur }}
                      } @else {
                        Unavailable
                      }
                    </dd>
                  </div>
                } @else {
                  <div>
                    <dt>Platforms</dt>
                    <dd>{{ platformLabels(current.software?.platforms ?? []) }}</dd>
                  </div>
                }
                <div>
                  <dt>Payment</dt>
                  <dd>PayPal</dd>
                </div>
                <p class="hint" id="listing-fixed-reason">Fixed after publishing</p>
              </dl>
            }

            <div class="actions">
              <app-busy-button
                [label]="
                  media.busy()
                    ? media.message()
                    : mode === 'create'
                      ? 'Publish listing'
                      : 'Save changes'
                "
                [busy]="saving()"
                [disabled]="!!submitBlockedReason()"
                [reason]="submitBlockedReason()"
                (pressed)="submit()"
              />
              <button type="button" class="btn neutral" (click)="cancel()">Cancel</button>
              @if (mode === 'edit') {
                <button type="button" class="remove" (click)="removeDialog.set(true)">
                  Remove listing
                </button>
              }
            </div>
          </fieldset>
        </div>
      }
    }

    @if (developerDialog()) {
      <app-modal-dialog
        heading="Become a developer?"
        size="confirm"
        (closed)="developerDialog.set(false)"
      >
        <p class="dialog-body">
          This unlocks software publishing on your account. It can't be turned off later.
        </p>
        <div class="dialog-actions">
          <app-busy-button
            label="Become a developer"
            [busy]="activating()"
            (pressed)="activateDeveloper()"
          />
          <button type="button" class="btn neutral" autofocus (click)="developerDialog.set(false)">
            Not now
          </button>
        </div>
      </app-modal-dialog>
    }

    @if (removeDialog()) {
      <app-modal-dialog
        heading="Remove this listing?"
        size="confirm"
        (closed)="removeDialog.set(false)"
      >
        <p class="dialog-body">
          '{{ listing()?.title }}' will be taken down from the marketplace. This can't be undone.
        </p>
        <div class="dialog-actions">
          <button type="button" class="btn neutral" autofocus (click)="removeDialog.set(false)">
            Keep it
          </button>
          <app-busy-button
            label="Remove"
            variant="danger"
            [busy]="removing()"
            (pressed)="remove()"
          />
        </div>
      </app-modal-dialog>
    }

    @if (leavePrompt()) {
      <app-modal-dialog
        heading="Leave without saving?"
        size="confirm"
        (closed)="settleLeave(false)"
      >
        <p class="dialog-body">Your changes on this page will be lost.</p>
        <div class="dialog-actions">
          <button type="button" class="btn danger" (click)="settleLeave(true)">Leave</button>
          <button type="button" class="btn neutral" autofocus (click)="settleLeave(false)">
            Keep editing
          </button>
        </div>
      </app-modal-dialog>
    }
  `,
})
export class SellEditPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly service = inject(ListingFormService);
  readonly photos = inject(PhotoSelection);
  readonly media = inject(UploadQueueService);
  private mediaListingId = '';
  private mediaSucceeded = false;

  readonly mode: Mode = this.route.snapshot.data['mode'] === 'edit' ? 'edit' : 'create';
  readonly listingId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly conditions = CONDITIONS;
  readonly platformOptions = PLATFORMS;

  readonly status = signal<PageStatus>('loading');
  readonly tree = signal<readonly CategoryTreeNode[]>([]);
  readonly listing = signal<ListingDetailResponse | null>(null);

  readonly type = signal<ListingType>('HARDWARE');
  readonly categoryId = signal('');
  readonly title = signal('');
  readonly description = signal('');
  readonly price = signal('');
  readonly condition = signal<Condition | ''>('');
  readonly shippingCity = signal('');
  readonly shippingCountry = signal('IT');
  // The catalog stores one free-text location, so the form joins it as "City, Country".
  readonly shippingLocation = computed(() => {
    const city = this.shippingCity().trim();
    return city ? `${city}, ${countryName(this.shippingCountry())}` : '';
  });
  readonly conditionOptions = CONDITIONS.map((option) => leafNode(option.value, option.label));
  readonly countryOptions = SHIPPING_COUNTRIES.map((code) => leafNode(code, countryName(code)));
  readonly shippingCost = signal('');
  readonly platforms = signal<readonly Platform[]>([]);
  readonly versionNumber = signal('');
  readonly notes = signal('');
  readonly licencePattern = signal('');
  readonly definitions = signal<readonly AttributeDefinitionResponse[]>([]);
  readonly attributes = signal<Readonly<Record<string, string>>>({});
  readonly cleared = signal(0);

  readonly errors = signal<Readonly<Record<string, string>>>({});
  readonly attributeErrors = signal<Readonly<Record<string, string>>>({});
  readonly specificationsError = signal('');
  readonly schemaLoading = signal(false);
  readonly schemaError = signal(false);
  private schemaRequest = 0;
  readonly submitBlockedReason = computed(() => {
    if (this.media.pending()) return 'Finish saving your photos above before continuing.';
    if (this.photos.processing()) return 'Wait for your photos to finish processing.';
    if (this.schemaLoading()) return 'Wait for the category specifications to load.';
    if (this.schemaError()) return 'Reload the category specifications before continuing.';
    if (
      this.mode === 'create' &&
      this.type() === 'SOFTWARE' &&
      this.service.isDeveloper() !== true
    ) {
      return 'Software listings need a developer profile.';
    }
    return '';
  });
  readonly attention = signal(0);
  readonly saving = signal(false);

  readonly currentPrice = signal('');
  readonly priceOpen = signal(false);
  readonly newPrice = signal('');
  readonly priceError = signal('');
  readonly priceBusy = signal(false);

  readonly developerDialog = signal(false);
  readonly activating = signal(false);
  readonly removeDialog = signal(false);
  readonly removing = signal(false);
  readonly leavePrompt = signal(false);
  private leaveResolver: ((leave: boolean) => void) | null = null;
  private bypassGuard = false;
  private baseline = '';
  private originalCategoryId = '';

  readonly leaves = computed<LeafOption[]>(() => leavesUnder(this.tree(), this.type()));
  readonly categoryTree = computed(() => typeRoot(this.tree(), this.type())?.children ?? []);
  readonly typeOptions = computed<SelectionOption[]>(() => [
    { value: 'HARDWARE', label: 'Hardware' },
    {
      value: 'SOFTWARE',
      label: 'Software',
      disabled: this.service.isDeveloper() !== true,
      reason:
        this.service.isDeveloper() === false ? 'Software listings need a developer profile.' : '',
    },
  ]);
  readonly licenceHint = computed(() =>
    this.mode === 'edit'
      ? 'Leave empty to keep the current format. Use A-Z, 0-9 and hyphens. Each # becomes a random character. At least 16 # are required, 64 characters at most.'
      : 'Use A-Z, 0-9 and hyphens. Each # becomes a random character. At least 16 # are required, 64 characters at most.',
  );

  constructor() {
    this.load();
  }

  async load(): Promise<void> {
    this.status.set('loading');
    try {
      const treeRequest = this.service.categories();
      if (this.mode === 'create') {
        this.tree.set(await treeRequest);
        this.status.set('ready');
        this.baseline = this.snapshot();
        return;
      }
      const [tree, target] = await Promise.all([
        treeRequest,
        this.service.editTarget(this.listingId),
      ]);
      this.tree.set(tree);
      if (target.kind !== 'ready') {
        this.status.set(target.kind);
        return;
      }
      await this.hydrate(target.listing);
      this.status.set('ready');
      this.baseline = this.snapshot();
    } catch {
      this.status.set('error');
    }
  }

  canLeave(): boolean | Promise<boolean> {
    if (this.bypassGuard || this.status() !== 'ready' || this.snapshot() === this.baseline) {
      return true;
    }
    this.leavePrompt.set(true);
    return new Promise<boolean>((resolve) => (this.leaveResolver = resolve));
  }

  settleLeave(leave: boolean): void {
    this.leavePrompt.set(false);
    const resolve = this.leaveResolver;
    this.leaveResolver = null;
    resolve?.(leave);
  }

  switchType(value: string): void {
    if (this.mode === 'edit' || !['HARDWARE', 'SOFTWARE'].includes(value)) return;
    const next = value as ListingType;
    if (next === this.type()) return;
    if (next === 'SOFTWARE' && this.service.isDeveloper() !== true) return;
    this.schemaRequest++;
    this.schemaLoading.set(false);
    this.schemaError.set(false);
    this.type.set(next);
    this.categoryId.set('');
    this.definitions.set([]);
    this.attributes.set({});
    this.cleared.set(0);
    this.attributeErrors.set({});
    if (next === 'SOFTWARE' && !this.licencePattern()) {
      this.licencePattern.set(DEFAULT_LICENCE_PATTERN);
    }
  }

  async pickCategory(id: string): Promise<void> {
    await this.loadSchema(id);
    this.validateField('listing-category');
  }

  async retrySchema(): Promise<void> {
    await this.loadSchema(this.categoryId());
  }

  private async loadSchema(id: string): Promise<void> {
    const request = ++this.schemaRequest;
    const previous = this.definitions();
    this.categoryId.set(id);
    this.schemaLoading.set(false);
    this.schemaError.set(false);
    this.specificationsError.set('');
    this.attributeErrors.set({});
    if (!id) {
      this.cleared.set(Object.values(this.attributes()).filter((value) => value.trim()).length);
      this.definitions.set([]);
      this.attributes.set({});
      return;
    }
    if (!this.leaves().some((leaf) => leaf.id === id)) {
      this.schemaError.set(true);
      return;
    }
    this.schemaLoading.set(true);
    if (this.errors()['listing-category']) this.validateField('listing-category');
    try {
      const next = await this.service.schema(id);
      if (request !== this.schemaRequest) return;
      const carried = carryOver(previous, next, this.attributes());
      this.definitions.set(next);
      this.attributes.set(carried.raw);
      this.cleared.set(carried.cleared);
    } catch {
      if (request === this.schemaRequest) this.schemaError.set(true);
    } finally {
      if (request === this.schemaRequest) this.schemaLoading.set(false);
    }
  }

  setCondition(value: string | null): void {
    this.condition.set((value ?? '') as Condition | '');
    this.validateField('listing-condition');
  }

  togglePlatform(platform: Platform): void {
    const selected = new Set(this.platforms());
    if (selected.has(platform)) selected.delete(platform);
    else selected.add(platform);
    this.platforms.set(
      PLATFORMS.map((option) => option.value).filter((value) => selected.has(value)),
    );
    if (this.errors()['listing-platforms']) this.validateField('listing-platforms');
  }

  setAttribute(change: { id: string; value: string }): void {
    this.attributes.update((values) => ({ ...values, [change.id]: change.value }));
    if (this.attributeErrors()[change.id]) this.validateAttribute(change.id);
  }

  validateField(id: string): void {
    const message = this.fieldError(id);
    this.errors.update((errors) => {
      const next = { ...errors };
      if (message) next[id] = message;
      else delete next[id];
      return next;
    });
  }

  validateAttribute(id: string): void {
    const definition = this.definitions().find((candidate) => candidate.id === id);
    if (!definition) return;
    const message = attributeError(definition, this.attributes()[id] ?? '');
    this.attributeErrors.update((errors) => {
      const next = { ...errors };
      if (message) next[id] = message;
      else delete next[id];
      return next;
    });
  }

  async submit(): Promise<void> {
    if (this.saving() || this.status() !== 'ready' || this.submitBlockedReason()) return;
    const errors: Record<string, string> = {};
    for (const id of FIELD_ORDER) {
      const message = this.fieldError(id);
      if (message) errors[id] = message;
    }
    const { attributes, errors: attributeErrors } = buildAttributePayload(
      this.definitions(),
      this.attributes(),
    );
    this.errors.set(errors);
    this.attributeErrors.set(attributeErrors);
    this.specificationsError.set('');
    const count = Object.keys(errors).length + Object.keys(attributeErrors).length;
    this.attention.set(count);
    if (count > 0) {
      const first =
        FIELD_ORDER.find((id) => errors[id]) ??
        attributeControlId(
          this.definitions().find((definition) => attributeErrors[definition.id])!,
        );
      this.focus(first === 'listing-platforms' ? 'input[name="listing-platforms"]' : '#' + first);
      return;
    }
    this.saving.set(true);
    try {
      if (this.mode === 'create') await this.publish(attributes);
      else await this.save(attributes);
      this.focusMediaRecovery();
    } catch (error) {
      this.handleWriteError(error);
    } finally {
      this.saving.set(false);
    }
  }

  mediaFailurePrefix(): string {
    if (this.mode === 'create') return 'Listing published.';
    return this.media.failureStage() === 'release' ? '' : 'Listing changes saved.';
  }

  async recoverMedia(continueSuccessful: boolean): Promise<void> {
    if (this.saving() || this.media.busy()) return;
    this.saving.set(true);
    try {
      if (await (continueSuccessful ? this.media.continueSuccessful() : this.media.retry())) {
        await this.finishMedia();
      } else if (this.media.failureStage() === 'release') {
        this.handleMediaReleaseError();
      }
      this.focusMediaRecovery();
    } finally {
      this.saving.set(false);
    }
  }

  cancel(): void {
    void this.router.navigateByUrl(
      this.mode === 'edit' ? `/listings/${this.listingId}` : '/me?tab=listings',
    );
  }

  openPrice(): void {
    this.priceOpen.set(true);
    this.newPrice.set('');
    this.priceError.set('');
    this.focus('#listing-new-price');
  }

  closePrice(): void {
    this.priceOpen.set(false);
    this.priceError.set('');
  }

  async changePrice(): Promise<void> {
    if (this.priceBusy()) return;
    const value = this.newPrice().trim();
    if (!isValidPrice(value, false)) {
      this.priceError.set(COPY.price);
      return;
    }
    this.priceBusy.set(true);
    try {
      const updated = await this.service.changePrice(this.listingId, value);
      this.currentPrice.set(String(updated.price));
      this.priceOpen.set(false);
      this.toasts.show({ text: 'Price updated', kind: 'success' });
    } catch (error) {
      this.priceError.set(
        isAppError(error) && error.code === 'price-unchanged'
          ? 'That is already the current price.'
          : this.copy(error),
      );
    } finally {
      this.priceBusy.set(false);
    }
  }

  async activateDeveloper(): Promise<void> {
    if (this.activating()) return;
    this.activating.set(true);
    try {
      await this.service.activateDeveloper();
      this.developerDialog.set(false);
      this.toasts.show({ text: 'Developer profile activated', kind: 'success' });
    } catch (error) {
      this.toasts.show({ text: this.copy(error), kind: 'error' });
    } finally {
      this.activating.set(false);
    }
  }

  async remove(): Promise<void> {
    if (this.removing()) return;
    this.removing.set(true);
    try {
      await this.service.remove(this.listingId);
      this.removeDialog.set(false);
      this.toasts.show({ text: 'Listing removed', kind: 'success' });
      this.bypassGuard = true;
      await this.router.navigateByUrl('/me?tab=listings');
    } catch (error) {
      this.toasts.show({ text: this.copy(error), kind: 'error' });
    } finally {
      this.removing.set(false);
    }
  }

  conditionLabel(condition: Condition | null | undefined): string {
    return CONDITIONS.find((option) => option.value === condition)?.label ?? '';
  }

  platformLabels(platforms: readonly Platform[]): string {
    return PLATFORMS.filter((option) => platforms.includes(option.value))
      .map((option) => option.label)
      .join(', ');
  }

  private async hydrate(listing: ListingDetailResponse): Promise<void> {
    const definitions = await this.service.schema(listing.categoryId);
    this.listing.set(listing);
    this.photos.hydrate(listing.photoKeys, listing.photos);
    this.type.set(listing.type);
    this.categoryId.set(listing.categoryId);
    this.originalCategoryId = listing.categoryId;
    this.title.set(listing.title);
    this.description.set(listing.description);
    this.currentPrice.set(String(listing.price));
    this.definitions.set(definitions);
    this.attributes.set(rawFromListing(definitions, listing.attributes));
  }

  private async publish(attributes: ReturnType<typeof buildAttributePayload>['attributes']) {
    const common = {
      title: this.title().trim(),
      description: this.description().trim(),
      price: this.price().trim(),
      categoryId: this.categoryId(),
      photos: [],
      paymentMethods: ['PAYPAL' as const],
    };
    const created =
      this.type() === 'HARDWARE'
        ? await this.service.publishHardware({
            ...common,
            condition: this.condition() as Condition,
            shippingLocation: this.shippingLocation().trim(),
            shippingCost: this.shippingCost().trim(),
            attributes,
          })
        : await this.service.publishSoftware({
            ...common,
            platforms: [...this.platforms()],
            versionNumber: this.versionNumber().trim(),
            notes: this.notes().trim() || null,
            attributes,
            licenceKeyPattern: this.licencePattern().trim() || null,
          });
    this.mediaListingId = created.id;
    if (
      await this.media.start({
        id: created.id,
        photos: this.photos.items(),
        release: false,
        request: {
          title: common.title,
          description: common.description,
          photos: [],
          attributes,
          categoryId: null,
          licenceKeyPattern: null,
        },
      })
    )
      await this.finishMedia();
  }

  private async save(attributes: ReturnType<typeof buildAttributePayload>['attributes']) {
    const listing = this.listing()!;
    const moved = this.categoryId() !== this.originalCategoryId;
    this.mediaListingId = this.listingId;
    const completed = await this.media.start({
      id: this.listingId,
      photos: this.photos.items(),
      release: true,
      request: {
        title: this.title().trim(),
        description: this.description().trim(),
        photos: [],
        attributes,
        categoryId: moved ? this.categoryId() : null,
        licenceKeyPattern:
          listing.type === 'SOFTWARE' ? this.licencePattern().trim() || null : null,
      },
    });
    if (completed) await this.finishMedia();
    else if (this.media.failureStage() === 'release') this.handleMediaReleaseError();
  }

  private handleMediaReleaseError(): void {
    const error = this.media.error();
    this.handleWriteError(error);
    if (isAppError(error) && error.status >= 400 && error.status < 500 && error.status !== 429) {
      this.media.discardRejectedSave();
    }
  }

  private async finishMedia(): Promise<void> {
    if (this.mediaSucceeded) return;
    this.mediaSucceeded = true;
    this.originalCategoryId = this.categoryId();
    this.cleared.set(0);
    this.baseline = this.snapshot();
    this.toasts.show({
      text: this.mode === 'create' ? 'Listing published' : 'Changes saved',
      kind: 'success',
    });
    const dropped = this.media.droppedAttributeIds().length;
    if (dropped > 0) {
      this.toasts.show({
        text:
          dropped === 1 ? '1 specification was removed' : `${dropped} specifications were removed`,
        kind: 'info',
      });
    }
    this.bypassGuard = true;
    await this.router.navigateByUrl(`/listings/${this.mediaListingId}`);
  }

  private handleWriteError(error: unknown): void {
    const code = isAppError(error) ? error.code : '';
    if (this.mode === 'edit' && isAppError(error) && error.status === 403) {
      this.status.set('not-owner');
      return;
    }
    if (this.mode === 'edit' && isAppError(error) && error.status === 404) {
      this.status.set('missing');
      return;
    }
    if (ATTRIBUTE_ERRORS.has(code)) {
      this.specificationsError.set(COPY.specifications);
      this.focus('#listing-specifications-error');
      return;
    }
    const fieldErrors: Record<string, string> = {
      'category-not-leaf': COPY.category,
      'category-root-mismatch': 'Choose a category of this listing type.',
      'category-move-not-allowed': 'A listing can only move within its own type.',
      'invalid-licence-key-pattern': COPY.licence,
    };
    const field =
      code === 'invalid-licence-key-pattern' ? 'listing-licence-pattern' : 'listing-category';
    if (fieldErrors[code]) {
      this.errors.update((errors) => ({ ...errors, [field]: fieldErrors[code] }));
      this.focus('#' + field);
      return;
    }
    if (code === 'listing-not-editable') {
      this.status.set('not-editable');
      return;
    }
    if (code === 'validation-failed' && isAppError(error)) {
      const mapped = this.mapValidation(error.fieldErrors);
      if (Object.keys(mapped).length) {
        this.errors.update((errors) => ({ ...errors, ...mapped }));
        this.attention.set(Object.keys(mapped).length);
        this.focus('#' + FIELD_ORDER.find((id) => mapped[id]));
        return;
      }
    }
    this.toasts.show({ text: this.copy(error), kind: 'error' });
  }

  private mapValidation(fieldErrors: Readonly<Record<string, string>>): Record<string, string> {
    const byField: Record<string, [string, string]> = {
      title: ['listing-title', COPY.title],
      description: ['listing-description', COPY.description],
      price: ['listing-price', COPY.price],
      categoryId: ['listing-category', COPY.category],
      condition: ['listing-condition', COPY.condition],
      shippingLocation: ['listing-shipping-location', COPY.shippingLocation],
      shippingCost: ['listing-shipping-cost', COPY.shippingCost],
      platforms: ['listing-platforms', COPY.platforms],
      versionNumber: ['listing-version', COPY.version],
    };
    const mapped: Record<string, string> = {};
    for (const field of Object.keys(fieldErrors)) {
      const target = byField[field];
      if (target) mapped[target[0]] = target[1];
    }
    return mapped;
  }

  private fieldError(id: string): string {
    const create = this.mode === 'create';
    const hardware = this.type() === 'HARDWARE';
    switch (id) {
      case 'listing-title':
        return this.title().trim() ? '' : COPY.title;
      case 'listing-category':
        return this.leaves().some((leaf) => leaf.id === this.categoryId()) ? '' : COPY.category;
      case 'listing-condition':
        return !create || !hardware || this.condition() ? '' : COPY.condition;
      case 'listing-description':
        return this.description().trim() ? '' : COPY.description;
      case 'listing-price':
        return !create || isValidPrice(this.price(), false) ? '' : COPY.price;
      case 'listing-shipping-location':
        return !create || !hardware || this.shippingLocation().trim() ? '' : COPY.shippingLocation;
      case 'listing-shipping-cost':
        return !create || !hardware || isValidPrice(this.shippingCost(), true)
          ? ''
          : COPY.shippingCost;
      case 'listing-platforms':
        return !create || hardware || this.platforms().length ? '' : COPY.platforms;
      case 'listing-version': {
        if (!create || hardware) return '';
        const version = this.versionNumber().trim();
        if (!version) return COPY.versionMissing;
        return VERSION.test(version) ? '' : COPY.version;
      }
      case 'listing-licence-pattern': {
        if (hardware) return '';
        const pattern = this.licencePattern().trim();
        if (!pattern) return create ? COPY.licence : '';
        return isValidLicencePattern(pattern) ? '' : COPY.licence;
      }
      default:
        return '';
    }
  }

  private snapshot(): string {
    return JSON.stringify([
      this.type(),
      this.categoryId(),
      this.title(),
      this.description(),
      this.price(),
      this.condition(),
      this.shippingLocation(),
      this.shippingCost(),
      this.platforms(),
      this.versionNumber(),
      this.notes(),
      this.licencePattern(),
      this.attributes(),
      this.photos.items().map((photo) => photo.id),
    ]);
  }

  private copy(error: unknown): string {
    return errorCopy(isAppError(error) ? error : UNKNOWN_ERROR);
  }

  private focusMediaRecovery(): void {
    // A rejected 4xx release save discards the pending media and focuses its field instead.
    if (this.media.pending()) this.focus('.media-recovery');
  }

  private focus(selector: string): void {
    afterNextRender(
      () => {
        const target =
          this.host.nativeElement.querySelector<HTMLElement>(selector) ??
          this.host.nativeElement.querySelector<HTMLElement>(selector + '-true');
        target?.focus();
        target?.scrollIntoView?.({ block: 'center' });
      },
      { injector: this.injector },
    );
  }
}
