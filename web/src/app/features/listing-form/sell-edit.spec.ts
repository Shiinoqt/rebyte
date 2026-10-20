import { BreakpointObserver } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import {
  AttributeDefinitionResponse,
  CategoryTreeNode,
  ListingDetailResponse,
  PublicProfileResponse,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { PhotoPipeline } from '../../core/media/photo-pipeline';
import { S3Uploader } from '../../core/media/s3-uploader';
import { UserApi } from '../../core/api/user-api';
import { AuthAdapter, AuthEvent, AuthIdentity } from '../../core/auth/auth.adapter';
import { AuthStore } from '../../core/auth/auth.store';
import { AppError } from '../../core/http/app-error';
import { ToastService } from '../../shared/ui/feedback';
import { buildAttributePayload, carryOver, rawFromListing } from './attribute-form';
import { LISTING_FORM_ROUTES } from './listing-form.routes';
import { SellEditPage } from './sell-edit.page';

const SUB = 'seller-sub';

@Component({ template: '<h1>Elsewhere</h1>' })
class ElsewherePage {}

class FakeAuthAdapter {
  constructor(public identity: AuthIdentity | null) {}
  configure = vi.fn();
  fetchSession = vi.fn(() => Promise.resolve(this.identity));
  signOut = vi.fn(() => Promise.resolve());
  onAuthEvent(_listener: (event: AuthEvent) => void) {
    return () => undefined;
  }
}

const TREE: CategoryTreeNode[] = [
  {
    id: 'hw-root',
    name: 'Hardware',
    leaf: false,
    children: [
      { id: 'hw-laptops', name: 'Laptops', leaf: true, children: [] },
      {
        id: 'hw-components',
        name: 'Components',
        leaf: false,
        children: [{ id: 'hw-gpus', name: 'GPUs', leaf: true, children: [] }],
      },
      { id: 'hw-cables', name: 'Cables', leaf: true, children: [] },
    ],
  },
  {
    id: 'sw-root',
    name: 'Software',
    leaf: false,
    children: [{ id: 'sw-tools', name: 'Tools', leaf: true, children: [] }],
  },
];

function definition(
  overrides: Partial<AttributeDefinitionResponse> &
    Pick<AttributeDefinitionResponse, 'id' | 'name' | 'type'>,
): AttributeDefinitionResponse {
  return {
    required: false,
    allowedValues: [],
    unit: null,
    definedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

const LAPTOP_SCHEMA: AttributeDefinitionResponse[] = [
  definition({
    id: 'a-brand',
    name: 'Brand',
    type: 'ENUM',
    required: true,
    allowedValues: ['Apple', 'Lenovo'],
  }),
  definition({ id: 'a-ram', name: 'RAM', type: 'INTEGER', required: true, unit: 'GB' }),
  definition({ id: 'a-screen', name: 'Screen', type: 'DECIMAL', unit: 'in' }),
  definition({ id: 'a-touch', name: 'Touchscreen', type: 'BOOLEAN' }),
  definition({ id: 'a-backlit', name: 'Backlit keyboard', type: 'BOOLEAN', required: true }),
];
const GPU_SCHEMA: AttributeDefinitionResponse[] = [
  definition({
    id: 'g-brand',
    name: 'Brand',
    type: 'ENUM',
    required: true,
    allowedValues: ['Apple', 'AMD'],
  }),
  definition({ id: 'g-vram', name: 'VRAM', type: 'INTEGER', unit: 'GB' }),
];
const SCHEMAS: Record<string, AttributeDefinitionResponse[]> = {
  'hw-laptops': LAPTOP_SCHEMA,
  'hw-gpus': GPU_SCHEMA,
  'hw-cables': [],
  'sw-tools': [],
};

function detail(overrides: Partial<ListingDetailResponse> = {}): ListingDetailResponse {
  return {
    id: 'l1',
    title: 'ThinkPad X1',
    description: 'Works well.',
    price: '499.00',
    status: 'ACTIVE',
    type: 'HARDWARE',
    publication: '2026-10-01T10:00:00Z',
    sellerSub: SUB,
    categoryId: 'hw-laptops',
    photos: ['https://cdn.test/listings/k1.jpg', 'https://cdn.test/listings/k2.jpg'],
    photoKeys: ['listings/l1/k1.jpg', 'listings/l1/k2.jpg'],
    paymentMethods: ['PAYPAL'],
    condition: 'GOOD',
    shippingLocation: 'Turin',
    shippingCost: '5.00',
    software: null,
    category: { id: 'hw-laptops', name: 'Laptops', ancestors: ['Hardware', 'Laptops'] },
    priceHistory: [],
    attributes: [
      { name: 'Brand', type: 'ENUM', value: 'Lenovo', unit: null },
      { name: 'RAM', type: 'INTEGER', value: 16, unit: 'GB' },
      { name: 'Screen', type: 'DECIMAL', value: 14.5, unit: 'in' },
      { name: 'Backlit keyboard', type: 'BOOLEAN', value: false, unit: null },
    ],
    seller: { sub: SUB, username: 'seller', avatar: null, ratingAvg: null, ratingCount: 0 },
    ...overrides,
  };
}

function profile(isDeveloper: boolean): PublicProfileResponse {
  return {
    sub: SUB,
    username: 'seller',
    avatar: null,
    registration: null,
    location: null,
    bio: null,
    isDeveloper,
    ratingAvg: null,
    ratingCount: 0,
    completedSales: 0,
    activeListings: [],
    followerCount: 0,
    followingCount: 0,
    followedByMe: false,
  };
}

function appError(status: number, code: string): AppError {
  return { status, code, type: 'urn:rebyte:problem:' + code, fieldErrors: {}, extras: {} };
}

describe('attribute form helpers', () => {
  it('rejects decimal values that cannot be represented as finite JSON numbers', () => {
    const result = buildAttributePayload([LAPTOP_SCHEMA[2]], { 'a-screen': '9'.repeat(400) });
    expect(result.errors['a-screen']).toBe('Screen must be a number.');
    expect(result.attributes).toEqual([]);
  });
  it('emits JSON strings for ENUM, numbers for INTEGER and DECIMAL and booleans for BOOLEAN', () => {
    const { attributes, errors } = buildAttributePayload(LAPTOP_SCHEMA, {
      'a-brand': 'Apple',
      'a-ram': '16',
      'a-screen': '13.3',
      'a-touch': 'true',
      'a-backlit': 'false',
    });
    expect(errors).toEqual({});
    expect(attributes).toEqual([
      { attributeId: 'a-brand', value: 'Apple' },
      { attributeId: 'a-ram', value: 16 },
      { attributeId: 'a-screen', value: 13.3 },
      { attributeId: 'a-touch', value: true },
      { attributeId: 'a-backlit', value: false },
    ]);
    expect(typeof attributes[1].value).toBe('number');
  });

  it('rejects fractional INTEGER, non-numeric DECIMAL, unknown ENUM and missing required values', () => {
    const { attributes, errors } = buildAttributePayload(LAPTOP_SCHEMA, {
      'a-brand': 'Dell',
      'a-ram': '16.5',
      'a-screen': '13,3x',
      'a-touch': '',
      'a-backlit': '',
    });
    expect(attributes).toEqual([]);
    expect(errors).toEqual({
      'a-brand': 'Choose one of the listed values for Brand.',
      'a-ram': 'RAM must be a whole number.',
      'a-screen': 'Screen must be a number.',
      'a-backlit': 'Backlit keyboard is required for this category.',
    });
  });

  it('omits optional values that were left empty', () => {
    const { attributes } = buildAttributePayload(LAPTOP_SCHEMA, {
      'a-brand': 'Lenovo',
      'a-ram': '8',
      'a-backlit': 'true',
    });
    expect(attributes.map((attribute) => attribute.attributeId)).toEqual([
      'a-brand',
      'a-ram',
      'a-backlit',
    ]);
  });

  it('joins listing attribute views to schema ids by name without losing their types', () => {
    expect(rawFromListing(LAPTOP_SCHEMA, detail().attributes)).toEqual({
      'a-brand': 'Lenovo',
      'a-ram': '16',
      'a-screen': '14.5',
      'a-backlit': 'false',
    });
  });

  it('keeps values whose attribute name survives a category change and counts the rest', () => {
    const result = carryOver(LAPTOP_SCHEMA, GPU_SCHEMA, {
      'a-brand': 'Apple',
      'a-ram': '16',
      'a-screen': '13.3',
      'a-touch': '',
    });
    expect(result.raw).toEqual({ 'g-brand': 'Apple' });
    expect(result.cleared).toBe(2);
  });

  it('clears a kept ENUM value that the new category does not allow', () => {
    const result = carryOver(LAPTOP_SCHEMA, GPU_SCHEMA, { 'a-brand': 'Lenovo' });
    expect(result.raw).toEqual({});
    expect(result.cleared).toBe(1);
  });
});

describe('sell and edit listing form', () => {
  let narrow: BehaviorSubject<{ matches: boolean; breakpoints: Record<string, boolean> }>;

  beforeEach(() => {
    narrow = new BehaviorSubject<{ matches: boolean; breakpoints: Record<string, boolean> }>({
      matches: false,
      breakpoints: {},
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  async function open(
    url: string,
    options: {
      developer?: boolean;
      listing?: ListingDetailResponse | 'missing';
      catalog?: Partial<Record<keyof CatalogApi, ReturnType<typeof vi.fn>>>;
    } = {},
  ) {
    const adapter = new FakeAuthAdapter({ sub: SUB, email: 'seller@example.test', groups: [] });
    let developer = options.developer ?? false;
    const users = {
      getMe: vi.fn(() =>
        of({
          id: SUB,
          username: 'seller',
          registration: '2026-01-01T00:00:00Z',
          location: null,
          bio: null,
          avatar: null,
        }),
      ),
      getPublicProfile: vi.fn(() => of(profile(developer))),
      activateDeveloperProfile: vi.fn(() => {
        developer = true;
        return of({ sub: SUB, activatedAt: '2026-10-08T10:00:00Z' });
      }),
    };
    const catalog = {
      categories: vi.fn(() => of(TREE)),
      categorySchema: vi.fn((id: string) => of({ categoryId: id, attributes: SCHEMAS[id] ?? [] })),
      myListings: vi.fn(() => of({ items: [{ id: 'l1' }], nextCursor: null })),
      listing: vi.fn(() =>
        options.listing === 'missing'
          ? throwError(() => appError(404, 'listing-not-found'))
          : of(options.listing ?? detail()),
      ),
      publishHardware: vi.fn(() =>
        of({
          id: 'new-1',
          title: 't',
          price: '1',
          status: 'ACTIVE',
          publication: '2026-10-08T10:00:00Z',
        }),
      ),
      publishSoftware: vi.fn(() =>
        of({
          id: 'new-2',
          title: 't',
          price: '1',
          status: 'ACTIVE',
          publication: '2026-10-08T10:00:00Z',
        }),
      ),
      editListing: vi.fn(() =>
        of({
          listing: {
            id: 'l1',
            title: 't',
            price: '499.00',
            status: 'ACTIVE',
            publication: '2026-10-01T10:00:00Z',
          },
          droppedAttributeIds: [] as string[],
        }),
      ),
      changePrice: vi.fn(() =>
        of({
          id: 'l1',
          title: 't',
          price: '450',
          status: 'ACTIVE',
          publication: '2026-10-01T10:00:00Z',
        }),
      ),
      deleteListing: vi.fn(() => of(undefined)),
      photoUploadPolicy: vi.fn(() =>
        of({
          key: 'new-key',
          url: 'https://s3.test',
          algorithm: 'algo',
          credential: 'credential',
          date: 'date',
          policy: 'policy',
          signature: 'signature',
          securityToken: null,
        }),
      ),
      confirmPhoto: vi.fn(() => of(['new-key'])),
      favourites: vi.fn(() =>
        of({ favourites: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }),
      ),
      ...options.catalog,
    };
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideRouter(
          [
            { path: 'sell', data: { mode: 'create' }, loadChildren: () => LISTING_FORM_ROUTES },
            {
              path: 'listings/:id/edit',
              data: { mode: 'edit' },
              loadChildren: () => LISTING_FORM_ROUTES,
            },
            { path: 'listings/:id', component: ElsewherePage },
            { path: 'me', component: ElsewherePage },
            { path: '', component: ElsewherePage },
          ],
          withComponentInputBinding(),
        ),
        { provide: AuthAdapter, useValue: adapter },
        { provide: UserApi, useValue: users },
        { provide: CatalogApi, useValue: catalog },
        { provide: S3Uploader, useValue: { upload: vi.fn(async () => {}) } },
        { provide: BreakpointObserver, useValue: { observe: () => narrow } },
      ],
    });
    await TestBed.inject(AuthStore).init();
    const toasts = vi.spyOn(TestBed.inject(ToastService), 'show');
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle(harness);
    const root = harness.routeNativeElement as HTMLElement;
    return { harness, root, catalog, users, toasts, router: TestBed.inject(Router) };
  }

  async function settle(harness: RouterTestingHarness) {
    for (let attempt = 0; attempt < 6; attempt++) {
      await harness.fixture.whenStable();
      harness.detectChanges();
    }
  }

  async function pendingPrompt(harness: RouterTestingHarness) {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const byId = <T extends HTMLElement>(root: HTMLElement, id: string) =>
    root.querySelector<T>('#' + id);
  const button = (root: HTMLElement, label: string) =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (candidate) => text(candidate) === label,
    );
  const errorFor = (root: HTMLElement, id: string) => text(root.querySelector(`#${id}-error`));

  function type(harness: RouterTestingHarness, root: HTMLElement, id: string, value: string) {
    const input = byId<HTMLInputElement | HTMLTextAreaElement>(root, id);
    if (!input) throw new Error('missing field #' + id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
  }

  async function choose(
    harness: RouterTestingHarness,
    root: HTMLElement,
    id: string,
    value: string,
  ) {
    const select = byId<HTMLSelectElement>(root, id);
    if (!select) throw new Error('missing select #' + id);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle(harness);
  }

  async function fillHardware(harness: RouterTestingHarness, root: HTMLElement) {
    type(harness, root, 'listing-title', 'ThinkPad X1');
    type(harness, root, 'listing-description', 'Works well.');
    await choose(harness, root, 'listing-category', 'hw-laptops');
    await choose(harness, root, 'listing-condition', 'GOOD');
    type(harness, root, 'listing-price', '499');
    type(harness, root, 'listing-shipping-location', 'Turin');
    type(harness, root, 'listing-shipping-cost', '0');
    await choose(harness, root, 'attr-a-brand', 'Apple');
    type(harness, root, 'attr-a-ram', '16');
    type(harness, root, 'attr-a-screen', '13.3');
    byId<HTMLInputElement>(root, 'attr-a-touch')!.click();
    harness.detectChanges();
    root.querySelector<HTMLInputElement>('input[name="attr-a-backlit"][value="false"]')!.click();
    harness.detectChanges();
  }

  describe('routes', () => {
    it('unlocks editing after a rejected retained-key save so validation can be corrected', async () => {
      const editListing = vi
        .fn()
        .mockReturnValueOnce(throwError(() => appError(422, 'category-not-leaf')))
        .mockReturnValue(
          of({
            listing: {
              id: 'l1',
              title: 't',
              price: '1',
              status: 'ACTIVE',
              publication: '2026-10-08T00:00:00Z',
            },
            droppedAttributeIds: [],
          }),
        );
      const { harness, root } = await open('/listings/l1/edit', { catalog: { editListing } });
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      await page.submit();
      await settle(harness);
      expect(errorFor(root, 'listing-category')).toContain('most specific');
      expect(document.activeElement).toBe(byId(root, 'listing-category'));
      expect(root.querySelector('fieldset.form-column')!.hasAttribute('disabled')).toBe(false);
      expect(page.media.pending()).toBe(false);
      await page.submit();
      expect(editListing).toHaveBeenCalledTimes(2);
    });
    it('publishes once, retries a photo, and shows one success only after final ordered save', async () => {
      const { harness, root, catalog, router, toasts } = await open('/sell');
      await fillHardware(harness, root);
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      vi.spyOn(TestBed.inject(PhotoPipeline), 'process').mockResolvedValue({
        blob: new Blob(['photo'], { type: 'image/jpeg' }),
        width: 10,
        height: 10,
        url: 'blob:test',
        cleanup: vi.fn(),
      });
      const upload = vi
        .spyOn(TestBed.inject(S3Uploader), 'upload')
        .mockRejectedValueOnce(new Error('network'))
        .mockResolvedValue(undefined);
      await page.photos.add([new File(['photo'], 'photo.jpg', { type: 'image/jpeg' })]);
      await page.submit();
      expect(router.url).toBe('/sell');
      expect(toasts).not.toHaveBeenCalled();
      await page.recoverMedia(false);
      expect(router.url).toBe('/listings/new-1');
      expect(catalog.publishHardware).toHaveBeenCalledOnce();
      expect(upload).toHaveBeenCalledTimes(2);
      expect(catalog.editListing).toHaveBeenCalledWith(
        'new-1',
        expect.objectContaining({ photos: ['new-key'] }),
      );
      expect(toasts).toHaveBeenCalledExactlyOnceWith({
        text: 'Listing published',
        kind: 'success',
      });
      await page.recoverMedia(false);
      expect(toasts).toHaveBeenCalledOnce();
    });

    it('continues an edited listing with retained and successful photos and one delayed success', async () => {
      let number = 0;
      const { harness, root, catalog, router, toasts } = await open('/listings/l1/edit', {
        catalog: {
          photoUploadPolicy: vi.fn(() =>
            of({
              key: `new-${++number}`,
              url: 'https://s3.test',
              algorithm: 'a',
              credential: 'c',
              date: 'd',
              policy: 'p',
              signature: 's',
              securityToken: null,
            }),
          ),
          confirmPhoto: vi.fn((id, body) => of([body.key])),
        },
      });
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      vi.spyOn(TestBed.inject(PhotoPipeline), 'process').mockImplementation(async (file) => ({
        blob: new Blob(['photo'], { type: 'image/jpeg' }),
        width: 10,
        height: 10,
        url: `blob:${file.name}`,
        cleanup: vi.fn(),
      }));
      vi.spyOn(TestBed.inject(S3Uploader), 'upload')
        .mockRejectedValueOnce(new Error('network'))
        .mockResolvedValue(undefined);
      page.photos.remove(0);
      await page.photos.add([
        new File(['photo'], 'a.jpg', { type: 'image/jpeg' }),
        new File(['photo'], 'b.jpg', { type: 'image/jpeg' }),
      ]);
      await page.submit();
      await settle(harness);
      expect(router.url).toBe('/listings/l1/edit');
      expect(toasts).not.toHaveBeenCalledWith(expect.objectContaining({ kind: 'success' }));
      expect(byId<HTMLInputElement>(root, 'listing-title')!.disabled).toBe(false);
      expect(root.querySelector('fieldset.form-column')!.hasAttribute('disabled')).toBe(true);
      expect(document.activeElement).toBe(root.querySelector('section.media-recovery'));
      await page.recoverMedia(true);
      expect(catalog.editListing.mock.calls.map((call) => call[1].photos)).toEqual([
        ['listings/l1/k2.jpg'],
        ['listings/l1/k2.jpg', 'new-2'],
      ]);
      expect(router.url).toBe('/listings/l1');
      expect(toasts).toHaveBeenCalledExactlyOnceWith({ text: 'Changes saved', kind: 'success' });
    });
    it('keeps the created listing open without success when a selected photo fails to upload', async () => {
      const { harness, root, catalog, router, toasts } = await open('/sell');
      await fillHardware(harness, root);
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      vi.spyOn(TestBed.inject(PhotoPipeline), 'process').mockResolvedValue({
        blob: new Blob(['photo'], { type: 'image/jpeg' }),
        width: 10,
        height: 10,
        url: 'blob:test',
        cleanup: vi.fn(),
      });
      vi.spyOn(TestBed.inject(S3Uploader), 'upload').mockRejectedValue(new Error('network'));
      await page.photos.add([new File(['photo'], 'photo.jpg', { type: 'image/jpeg' })]);
      await page.submit();
      await settle(harness);
      expect(router.url).toBe('/sell');
      expect(catalog.publishHardware).toHaveBeenCalledOnce();
      expect(catalog.photoUploadPolicy).toHaveBeenCalledWith('new-1');
      expect(catalog.confirmPhoto).not.toHaveBeenCalled();
      expect(toasts).not.toHaveBeenCalledWith(expect.objectContaining({ kind: 'success' }));
      expect(text(root)).toContain('Retry failed');
      expect(text(root)).toContain('Continue with successful photos');
    });
    it('shows the photo recovery under the picker, enabled and focused, outside the locked details', async () => {
      const { harness, root, router } = await open('/sell');
      await fillHardware(harness, root);
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      vi.spyOn(TestBed.inject(PhotoPipeline), 'process').mockResolvedValue({
        blob: new Blob(['photo'], { type: 'image/jpeg' }),
        width: 10,
        height: 10,
        url: 'blob:test',
        cleanup: vi.fn(),
      });
      const upload = vi
        .spyOn(TestBed.inject(S3Uploader), 'upload')
        .mockRejectedValue(new Error('network'));
      await page.photos.add([new File(['photo'], 'photo.jpg', { type: 'image/jpeg' })]);
      await page.submit();
      await settle(harness);
      const picker = root.querySelector('app-photo-picker')!;
      const recovery = root.querySelector('section.media-recovery')!;
      const fieldset = root.querySelector('fieldset.form-column')!;
      expect(picker.nextElementSibling).toBe(recovery);
      expect(recovery.nextElementSibling).toBe(fieldset);
      expect(fieldset.hasAttribute('disabled')).toBe(true);
      expect(fieldset.getAttribute('aria-label')).toBe('Listing details');
      expect(fieldset.contains(recovery)).toBe(false);
      for (const label of ['Retry failed', 'Continue with successful photos']) {
        const control = button(root, label)!;
        expect(control.disabled).toBe(false);
        expect(control.closest('fieldset[disabled]')).toBeNull();
      }
      expect(recovery.getAttribute('tabindex')).toBe('-1');
      expect(document.activeElement).toBe(recovery);

      root.querySelector<HTMLElement>('h1')!.focus();
      await page.recoverMedia(false);
      await settle(harness);
      expect(router.url).toBe('/sell');
      expect(upload).toHaveBeenCalledTimes(2);
      expect(document.activeElement).toBe(root.querySelector('section.media-recovery'));
    });
    it('places the photo picker before listing details', async () => {
      const { root } = await open('/sell');
      const picker = root.querySelector('app-photo-picker');
      expect(picker).not.toBeNull();
      expect(
        picker!.compareDocumentPosition(byId(root, 'listing-title')!) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it('updates the selected cover and leave guard when stored photos are reordered or removed', async () => {
      const { harness, root } = await open('/listings/l1/edit');
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      const cover = () => root.querySelector<HTMLImageElement>('app-photo-picker .tile img')?.src;
      expect(cover()).toBe('https://cdn.test/listings/k1.jpg');
      root.querySelector<HTMLButtonElement>('[aria-label="Move photo 2 earlier"]')!.click();
      harness.detectChanges();
      expect(cover()).toBe('https://cdn.test/listings/k2.jpg');
      const leave = page.canLeave();
      expect(leave).toBeInstanceOf(Promise);
      page.settleLeave(false);
      expect(await leave).toBe(false);
      root.querySelector<HTMLButtonElement>('[aria-label="Remove photo 1"]')!.click();
      harness.detectChanges();
      expect(cover()).toBe('https://cdn.test/listings/k1.jpg');
    });

    it('renders the create form on /sell from the inherited create mode', async () => {
      const { harness, root } = await open('/sell');
      expect(harness.routeDebugElement!.componentInstance).toBeInstanceOf(SellEditPage);
      expect(text(root.querySelector('h1'))).toBe('List an item');
      expect(root.querySelector('app-route-stub')).toBeNull();
      expect(button(root, 'Publish listing')).toBeDefined();
    });

    it('renders the edit form on /listings/:id/edit with the id from the parent route', async () => {
      const { harness, root, catalog } = await open('/listings/l1/edit');
      expect(harness.routeDebugElement!.componentInstance).toBeInstanceOf(SellEditPage);
      expect(catalog.listing).toHaveBeenCalledWith('l1');
      expect(text(root.querySelector('h1'))).toBe('Edit listing');
      expect(root.querySelector('app-route-stub')).toBeNull();
    });
  });

  describe('create', () => {
    it('blocks publishing until the selected category schema is ready and after schema failure', async () => {
      const pending = new Subject<{
        categoryId: string;
        attributes: AttributeDefinitionResponse[];
      }>();
      const { harness, root, catalog } = await open('/sell');
      await fillHardware(harness, root);
      catalog.categorySchema.mockImplementation(() => pending);
      const select = byId<HTMLSelectElement>(root, 'listing-category')!;
      select.value = 'hw-gpus';
      select.dispatchEvent(new Event('change'));
      harness.detectChanges();
      expect(button(root, 'Publish listing')!.disabled).toBe(true);
      pending.error(appError(503, 'catalog-unavailable'));
      await settle(harness);
      expect(button(root, 'Publish listing')!.disabled).toBe(true);
      expect(text(root)).toContain('Specifications could not be loaded');
      expect(catalog.publishHardware).not.toHaveBeenCalled();
    });

    it('refuses software type selection and submission without developer activation', async () => {
      const { harness, catalog } = await open('/sell');
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      page.switchType('SOFTWARE');
      expect(page.type()).toBe('HARDWARE');
      page.type.set('SOFTWARE');
      page.title.set('Tool');
      page.description.set('Useful tool');
      page.categoryId.set('sw-tools');
      page.price.set('10');
      page.platforms.set(['WEB']);
      page.versionNumber.set('1.0');
      page.licencePattern.set('RBYT-#####-#####-#####-#####');
      await page.submit();
      expect(catalog.publishSoftware).not.toHaveBeenCalled();
    });
    it('offers only leaf categories under the chosen type root, named by their path', async () => {
      const { root } = await open('/sell');
      const options = Array.from(byId<HTMLSelectElement>(root, 'listing-category')!.options);
      expect(options.map((option) => [option.value, option.textContent?.trim()])).toEqual([
        ['', 'Select...'],
        ['hw-laptops', 'Laptops'],
        ['hw-gpus', 'Components / GPUs'],
        ['hw-cables', 'Cables'],
      ]);
    });

    it('renders the four attribute types from the leaf schema with units and no range limits', async () => {
      const { harness, root, catalog } = await open('/sell');
      await choose(harness, root, 'listing-category', 'hw-laptops');
      expect(catalog.categorySchema).toHaveBeenCalledWith('hw-laptops');
      expect(text(root.querySelector('.specifications h2'))).toBe('Specifications');
      const brand = byId<HTMLSelectElement>(root, 'attr-a-brand')!;
      expect(Array.from(brand.options).map((option) => option.value)).toEqual([
        '',
        'Apple',
        'Lenovo',
      ]);
      const ram = byId<HTMLInputElement>(root, 'attr-a-ram')!;
      expect(ram.getAttribute('inputmode')).toBe('numeric');
      expect(text(root.querySelector('#attr-a-ram-unit'))).toBe('GB');
      expect(byId<HTMLInputElement>(root, 'attr-a-screen')!.getAttribute('inputmode')).toBe(
        'decimal',
      );
      expect(byId<HTMLInputElement>(root, 'attr-a-touch')!.getAttribute('role')).toBe('switch');
      expect(root.querySelectorAll('input[name="attr-a-backlit"][type="radio"]')).toHaveLength(2);
      for (const input of Array.from(root.querySelectorAll('.specifications input'))) {
        expect(input.hasAttribute('min')).toBe(false);
        expect(input.hasAttribute('max')).toBe(false);
      }
    });

    it('shows no Specifications group for a leaf without attributes', async () => {
      const { harness, root } = await open('/sell');
      await choose(harness, root, 'listing-category', 'hw-cables');
      expect(root.querySelector('.specifications')).toBeNull();
    });

    it('publishes a typed hardware payload with PayPal only, no photos, quantity or draft state', async () => {
      const { harness, root, catalog, toasts, router } = await open('/sell');
      await fillHardware(harness, root);
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(catalog.publishHardware).toHaveBeenCalledExactlyOnceWith({
        title: 'ThinkPad X1',
        description: 'Works well.',
        price: '499',
        categoryId: 'hw-laptops',
        photos: [],
        paymentMethods: ['PAYPAL'],
        condition: 'GOOD',
        shippingLocation: 'Turin',
        shippingCost: '0',
        attributes: [
          { attributeId: 'a-brand', value: 'Apple' },
          { attributeId: 'a-ram', value: 16 },
          { attributeId: 'a-screen', value: 13.3 },
          { attributeId: 'a-touch', value: true },
          { attributeId: 'a-backlit', value: false },
        ],
      });
      const payload = JSON.stringify(catalog.publishHardware.mock.calls[0]);
      expect(payload).not.toMatch(/quantity|draft|status/i);
      expect(text(root)).not.toMatch(/quantity/i);
      expect(toasts).toHaveBeenCalledExactlyOnceWith({
        text: 'Listing published',
        kind: 'success',
      });
      expect(router.url).toBe('/listings/new-1');
    });

    it('shows the fixed PayPal payment chip with its reason', async () => {
      const { root } = await open('/sell');
      const chip = root.querySelector('.payment-chip')!;
      expect(text(chip)).toBe('PayPal');
      expect(text(root.querySelector('#listing-payment-reason'))).toBe(
        'Rebyte checkout accepts PayPal only.',
      );
    });

    it('validates every field, counts the problems and focuses the first invalid one', async () => {
      const { harness, root, catalog } = await open('/sell');
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(catalog.publishHardware).not.toHaveBeenCalled();
      expect(errorFor(root, 'listing-title')).toBe('Add a title.');
      expect(errorFor(root, 'listing-category')).toBe('Choose the most specific category.');
      expect(errorFor(root, 'listing-condition')).toBe('Choose a condition.');
      expect(errorFor(root, 'listing-description')).toBe('Add a description.');
      expect(errorFor(root, 'listing-price')).toBe(
        'Enter a price greater than 0, with at most 2 decimals.',
      );
      expect(errorFor(root, 'listing-shipping-location')).toBe('Say where the item ships from.');
      expect(errorFor(root, 'listing-shipping-cost')).toBe(
        'Enter 0 or more, with at most 2 decimals.',
      );
      expect(text(root.querySelector('app-banner'))).toContain('7 fields need attention');
      expect(document.activeElement).toBe(byId(root, 'listing-title'));
    });

    it('blocks publishing on attribute errors and focuses the first bad attribute', async () => {
      const { harness, root, catalog } = await open('/sell');
      await fillHardware(harness, root);
      type(harness, root, 'attr-a-ram', '16.5');
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(catalog.publishHardware).not.toHaveBeenCalled();
      expect(errorFor(root, 'attr-a-ram')).toBe('RAM must be a whole number.');
      expect(document.activeElement).toBe(byId(root, 'attr-a-ram'));
    });

    it('focuses the first required boolean choice when its value is missing', async () => {
      const { harness, root } = await open('/sell');
      await fillHardware(harness, root);
      const page = harness.routeDebugElement!.componentInstance as SellEditPage;
      page.setAttribute({ id: 'a-backlit', value: '' });
      harness.detectChanges();
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(document.activeElement?.id).toBe('attr-a-backlit-true');
    });

    it('keeps same-name values after an explicit category change and says how many were cleared', async () => {
      const { harness, root } = await open('/sell');
      await choose(harness, root, 'listing-category', 'hw-laptops');
      await choose(harness, root, 'attr-a-brand', 'Apple');
      type(harness, root, 'attr-a-ram', '16');
      type(harness, root, 'attr-a-screen', '13.3');
      await choose(harness, root, 'listing-category', 'hw-gpus');
      expect(byId<HTMLSelectElement>(root, 'attr-g-brand')!.value).toBe('Apple');
      expect(byId(root, 'attr-a-ram')).toBeNull();
      expect(text(root.querySelector('.specifications app-banner .banner-text'))).toBe(
        '2 specifications were cleared because they do not apply to this category',
      );
    });

    it('keeps typed-in fields when switching type and resets the category to the new root', async () => {
      const { harness, root } = await open('/sell', { developer: true });
      type(harness, root, 'listing-title', 'Editor');
      await choose(harness, root, 'listing-category', 'hw-laptops');
      button(root, 'Software')!.click();
      await settle(harness);
      expect(byId<HTMLInputElement>(root, 'listing-title')!.value).toBe('Editor');
      const options = Array.from(byId<HTMLSelectElement>(root, 'listing-category')!.options);
      expect(options.map((option) => option.value)).toEqual(['', 'sw-tools']);
      expect(byId<HTMLSelectElement>(root, 'listing-category')!.value).toBe('');
    });

    it('disables Software with the developer reason for a non-developer', async () => {
      const { root } = await open('/sell');
      const software = button(root, 'Software')!;
      expect(software.disabled).toBe(true);
      expect(text(root.querySelector('#' + software.getAttribute('aria-describedby')))).toContain(
        'Software listings need a developer profile.',
      );
      expect(button(root, 'Become a developer')).toBeDefined();
      expect(byId(root, 'listing-version')).toBeNull();
    });

    it('enables Software after the developer confirmation without a reload', async () => {
      const { harness, root, users } = await open('/sell');
      button(root, 'Become a developer')!.click();
      harness.detectChanges();
      const dialog = root.querySelector('dialog')!;
      expect(text(dialog.querySelector('h2'))).toBe('Become a developer?');
      Array.from(dialog.querySelectorAll('button'))
        .find((candidate) => text(candidate) === 'Become a developer')!
        .click();
      await settle(harness);
      expect(users.activateDeveloperProfile).toHaveBeenCalledTimes(1);
      expect(button(root, 'Software')!.disabled).toBe(false);
    });

    it('publishes a software listing with platforms, version, notes and licence pattern', async () => {
      const { harness, root, catalog, router } = await open('/sell', { developer: true });
      button(root, 'Software')!.click();
      await settle(harness);
      type(harness, root, 'listing-title', 'Editor Pro');
      type(harness, root, 'listing-description', 'A text editor.');
      await choose(harness, root, 'listing-category', 'sw-tools');
      root
        .querySelector<HTMLInputElement>('input[name="listing-platforms"][value="LINUX"]')!
        .click();
      root
        .querySelector<HTMLInputElement>('input[name="listing-platforms"][value="MACOS"]')!
        .click();
      harness.detectChanges();
      type(harness, root, 'listing-version', '1.0.0');
      type(harness, root, 'listing-price', '15.50');
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(catalog.publishSoftware).toHaveBeenCalledExactlyOnceWith({
        title: 'Editor Pro',
        description: 'A text editor.',
        price: '15.50',
        categoryId: 'sw-tools',
        photos: [],
        paymentMethods: ['PAYPAL'],
        platforms: ['MACOS', 'LINUX'],
        versionNumber: '1.0.0',
        notes: null,
        attributes: [],
        licenceKeyPattern: 'RBYT-#####-#####-#####-#####',
      });
      expect(router.url).toBe('/listings/new-2');
    });

    it('validates software platforms, version number and licence pattern', async () => {
      const { harness, root, catalog } = await open('/sell', { developer: true });
      button(root, 'Software')!.click();
      await settle(harness);
      type(harness, root, 'listing-version', '1..0');
      type(harness, root, 'listing-licence-pattern', 'ABC-###');
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(catalog.publishSoftware).not.toHaveBeenCalled();
      expect(errorFor(root, 'listing-platforms')).toBe('Pick at least one platform.');
      expect(errorFor(root, 'listing-version')).toBe('Use numbers separated by dots, like 1.0.2.');
      expect(errorFor(root, 'listing-licence-pattern')).toBe(
        'Use A-Z, 0-9, hyphens and at least 16 # characters, at most 64 characters.',
      );
    });

    it('shows no success toast and maps a server attribute error to the specifications group', async () => {
      const { harness, root, toasts } = await open('/sell', {
        catalog: {
          publishHardware: vi.fn(() => throwError(() => appError(422, 'attribute-type-mismatch'))),
        },
      });
      await fillHardware(harness, root);
      button(root, 'Publish listing')!.click();
      await settle(harness);
      expect(toasts).not.toHaveBeenCalledWith(expect.objectContaining({ kind: 'success' }));
      expect(text(root.querySelector('#listing-specifications-error'))).toBe(
        'Some specifications no longer match this category. Reload the page and try again.',
      );
    });
  });

  describe('edit', () => {
    it('requires the target to appear in the authenticated owner listing pages', async () => {
      const myListings = vi.fn(() => of({ items: [], nextCursor: null }));
      const { root } = await open('/listings/l1/edit', { catalog: { myListings } });
      expect(myListings).toHaveBeenCalled();
      expect(byId(root, 'listing-title')).toBeNull();
      expect(text(root)).toContain("You can't edit this listing");
    });

    it('handles backend edit authorization failures without a success toast', async () => {
      const { harness, root, toasts } = await open('/listings/l1/edit', {
        catalog: { editListing: vi.fn(() => throwError(() => appError(403, 'listing-not-owner'))) },
      });
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(byId(root, 'listing-title')).toBeNull();
      expect(text(root)).toContain("You can't edit this listing");
      expect(toasts).not.toHaveBeenCalledWith(expect.objectContaining({ kind: 'success' }));
    });
    it('loads the owner listing, joins attributes by name and locks the fixed facts', async () => {
      const { root } = await open('/listings/l1/edit');
      expect(byId<HTMLInputElement>(root, 'listing-title')!.value).toBe('ThinkPad X1');
      expect(byId<HTMLTextAreaElement>(root, 'listing-description')!.value).toBe('Works well.');
      expect(byId<HTMLSelectElement>(root, 'listing-category')!.value).toBe('hw-laptops');
      expect(byId<HTMLSelectElement>(root, 'attr-a-brand')!.value).toBe('Lenovo');
      expect(byId<HTMLInputElement>(root, 'attr-a-ram')!.value).toBe('16');
      expect(byId<HTMLInputElement>(root, 'attr-a-screen')!.value).toBe('14.5');
      expect(
        root.querySelector<HTMLInputElement>('input[name="attr-a-backlit"][value="false"]')!
          .checked,
      ).toBe(true);
      expect(root.querySelector('app-segmented-control')).toBeNull();
      expect(root.querySelector('.type-chip')).toBeNull();
      expect(text(root.querySelector('.fixed-facts dd'))).toBe('Hardware');
      const facts = text(root.querySelector('.fixed-facts'));
      expect(facts).toContain('Good');
      expect(facts).toContain('Turin');
      expect(facts).toContain('Fixed after publishing');
      expect(byId(root, 'listing-condition')).toBeNull();
    });

    it('saves stored photo keys in order, typed attributes and unchanged category as null', async () => {
      const { harness, root, catalog, toasts } = await open('/listings/l1/edit');
      type(harness, root, 'listing-title', 'ThinkPad X1 Carbon');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(catalog.editListing).toHaveBeenCalledExactlyOnceWith('l1', {
        title: 'ThinkPad X1 Carbon',
        description: 'Works well.',
        photos: ['listings/l1/k1.jpg', 'listings/l1/k2.jpg'],
        attributes: [
          { attributeId: 'a-brand', value: 'Lenovo' },
          { attributeId: 'a-ram', value: 16 },
          { attributeId: 'a-screen', value: 14.5 },
          { attributeId: 'a-backlit', value: false },
        ],
        categoryId: null,
        licenceKeyPattern: null,
      });
      expect(JSON.stringify(catalog.editListing.mock.calls[0])).not.toContain('https://');
      expect(toasts).toHaveBeenCalledExactlyOnceWith({ text: 'Changes saved', kind: 'success' });
    });

    it('sends a moved category and reports dropped specifications', async () => {
      const editListing = vi.fn(() =>
        of({
          listing: {
            id: 'l1',
            title: 't',
            price: '499.00',
            status: 'ACTIVE' as const,
            publication: '2026-10-01T10:00:00Z',
          },
          droppedAttributeIds: ['a-ram', 'a-screen'],
        }),
      );
      const { harness, root, toasts } = await open('/listings/l1/edit', {
        catalog: { editListing },
      });
      await choose(harness, root, 'listing-category', 'hw-gpus');
      await choose(harness, root, 'attr-g-brand', 'AMD');
      button(root, 'Save changes')!.click();
      await settle(harness);
      expect(editListing.mock.calls[0]).toEqual([
        'l1',
        expect.objectContaining({
          categoryId: 'hw-gpus',
          attributes: [{ attributeId: 'g-brand', value: 'AMD' }],
        }),
      ]);
      expect(toasts).toHaveBeenCalledWith({ text: '2 specifications were removed', kind: 'info' });
    });

    it('changes the price through its own request with the public-change note', async () => {
      const { harness, root, catalog } = await open('/listings/l1/edit');
      button(root, 'Change price')!.click();
      harness.detectChanges();
      expect(text(root.querySelector('.price-note'))).toBe(
        'Price changes are public. People who favourited this item are notified when the price drops.',
      );
      type(harness, root, 'listing-new-price', '450');
      button(root, 'Update price')!.click();
      await settle(harness);
      expect(catalog.changePrice).toHaveBeenCalledExactlyOnceWith('l1', { price: '450' });
      expect(text(root.querySelector('.current-price'))).toContain('450');
    });

    it('explains an unchanged price', async () => {
      const { harness, root } = await open('/listings/l1/edit', {
        catalog: { changePrice: vi.fn(() => throwError(() => appError(422, 'price-unchanged'))) },
      });
      button(root, 'Change price')!.click();
      harness.detectChanges();
      type(harness, root, 'listing-new-price', '499');
      button(root, 'Update price')!.click();
      await settle(harness);
      expect(errorFor(root, 'listing-new-price')).toBe('That is already the current price.');
    });

    it.each([
      ['missing', 'missing' as const, "This listing isn't available"],
      ['owned by someone else', detail({ sellerSub: 'other' }), "You can't edit this listing"],
      ['sold', detail({ status: 'SOLD' }), "Sold or removed listings can't be edited."],
      ['removed', detail({ status: 'REMOVED' }), "Sold or removed listings can't be edited."],
    ])(
      'shows an unavailable state instead of a form when the listing is %s',
      async (_label, listing, copy) => {
        const { root } = await open('/listings/l1/edit', { listing });
        expect(byId(root, 'listing-title')).toBeNull();
        expect(text(root.querySelector('app-error-state, .unavailable'))).toContain(copy);
      },
    );

    it('removes the listing after confirming and returns to the account listings', async () => {
      const { harness, root, catalog, toasts, router } = await open('/listings/l1/edit');
      button(root, 'Remove listing')!.click();
      harness.detectChanges();
      const dialog = root.querySelector('dialog')!;
      expect(text(dialog.querySelector('h2'))).toBe('Remove this listing?');
      expect(text(dialog)).toContain(
        "'ThinkPad X1' will be taken down from the marketplace. This can't be undone.",
      );
      Array.from(dialog.querySelectorAll('button'))
        .find((candidate) => text(candidate) === 'Remove')!
        .click();
      await settle(harness);
      expect(catalog.deleteListing).toHaveBeenCalledExactlyOnceWith('l1');
      expect(toasts).toHaveBeenCalledWith({ text: 'Listing removed', kind: 'success' });
      expect(router.url).toBe('/me?tab=listings');
    });
  });

  describe('leaving', () => {
    it('asks before leaving a form with unsaved input', async () => {
      const { harness, root, router } = await open('/sell');
      type(harness, root, 'listing-title', 'Something');
      const navigation = router.navigateByUrl('/me');
      await pendingPrompt(harness);
      expect(text(root.querySelector('dialog h2'))).toBe('Leave without saving?');
      button(root, 'Keep editing')!.click();
      expect(await navigation).toBe(false);
    });

    it('leaves an untouched form without asking', async () => {
      const { router } = await open('/sell');
      expect(await router.navigateByUrl('/me')).toBe(true);
    });
  });
});
