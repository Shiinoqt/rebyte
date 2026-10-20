import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CatalogApi } from '../../../core/api/catalog-api';
import { AuthStore } from '../../../core/auth/auth.store';
import { AdminCategoriesPage } from './admin-categories.page';
import { ADMIN_CATEGORIES_ROUTES } from '../admin-categories.routes';
import {
  AttributeDefinitionResponse,
  CategoryResponse,
  CategoryTreeNode,
  EffectiveSchemaResponse,
  DefineAttributeRequest,
} from '../../../core/api/api-models';
import {
  AdminCategoriesService,
  categoryError,
  attributeError,
  attributeRequest,
} from './admin-categories.service';

const node = (id: string, children: CategoryTreeNode[] = []): CategoryTreeNode => ({
  id,
  name: id,
  leaf: !children.length,
  children,
});
describe('attribute commands and dialogs', () => {
  it('keeps the phone selector on a newly created category after rendering its option', () => {
    const { api } = setup();
    const fixture = TestBed.createComponent(AdminCategoriesPage);
    fixture.detectChanges();
    api.categories.mockReturnValue(of([node('hardware', [node('gpu'), node('new')])]));
    fixture.componentInstance.store.create({ name: 'New', parentId: 'hardware' });
    fixture.detectChanges();
    TestBed.tick();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement.querySelector('#selected-category') as HTMLSelectElement).value,
    ).toBe('new');
  });
  it('registers the implemented page as the empty category child', () => {
    expect(ADMIN_CATEGORIES_ROUTES[0].path).toBe('');
    expect(ADMIN_CATEGORIES_ROUTES[0].component).toBe(AdminCategoriesPage);
  });
  it.each(['ENUM', 'INTEGER', 'DECIMAL', 'BOOLEAN'] as const)(
    'defines %s with only supported metadata then refreshes schema',
    (type) => {
      const { store, api } = setup();
      store.select('gpu');
      const request = attributeRequest('Capacity', type, true, ['Large'], 'GB');
      const count = api.categorySchema.mock.calls.length;
      store.define(request);
      expect(api.defineAttribute).toHaveBeenCalledWith('gpu', request);
      expect(Object.keys(api.defineAttribute.mock.calls[0][1]).sort()).toEqual([
        'allowedValues',
        'name',
        'required',
        'type',
        'unit',
      ]);
      expect(api.categorySchema.mock.calls.length).toBeGreaterThan(count);
    },
  );
  it('retires an exact enum value through CatalogApi and refreshes authoritative schema', () => {
    const { store, api } = setup();
    store.select('gpu');
    api.categorySchema.mockImplementation((id) =>
      of(schema(id, id === 'gpu' ? [{ ...attribute(), allowedValues: ['PCIe'] }] : [])),
    );
    store.retire('bus', 'USB / C');
    expect(api.retireAllowedValue).toHaveBeenCalledWith('gpu', 'bus', 'USB / C');
    expect(store.attributes()[0].allowedValues).toEqual(['PCIe']);
    store.retire('bus', 'USB / C');
    expect(api.retireAllowedValue).toHaveBeenCalledOnce();
  });
  it.each(['duplicate-attribute-name', 'invalid-attribute-definition'])(
    'binds %s to definition fields without clearing selected data',
    (code) => {
      const { store, api } = setup();
      store.select('gpu');
      api.defineAttribute.mockReturnValue(
        throwError(() => ({ status: 422, code, fieldErrors: {} })),
      );
      store.define(attributeRequest('Bus', 'ENUM', false, ['PCIe'], ''));
      expect(store.fields()).toHaveProperty('name');
      expect(store.category()?.id).toBe('gpu');
      expect(store.attributes()).toHaveLength(1);
    },
  );
  it('preserves a form and returns from confirmation after an asynchronous field error', () => {
    const { api } = setup();
    const fixture = TestBed.createComponent(AdminCategoriesPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.open('category');
    page.categoryForm.setValue({ name: 'Graphics', parentId: 'hardware' });
    page.reviewCategory();
    const pending = new Subject<CategoryResponse>();
    api.createCategory.mockReturnValue(pending);
    page.confirm();
    expect(page.store.busy()).toBe(true);
    pending.error({ status: 409, code: 'duplicate-sibling-name', fieldErrors: {} });
    fixture.detectChanges();
    TestBed.tick();
    expect(page.decision()).toBeNull();
    expect(page.editor()).toBe('category');
    expect(page.categoryForm.getRawValue()).toEqual({ name: 'Graphics', parentId: 'hardware' });
    expect(page.errors()).toHaveProperty('name');
  });
  it('renders exactly the four types, confirms before mutation and clears dialogs on auth cleanup', () => {
    const { api, cleanup } = setup();
    const fixture = TestBed.createComponent(AdminCategoriesPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.store.select('gpu');
    page.open('attribute');
    fixture.detectChanges();
    const options = fixture.nativeElement.querySelectorAll(
      '#attribute-type option',
    ) as NodeListOf<HTMLOptionElement>;
    expect(Array.from(options, (o) => o.value)).toEqual(['ENUM', 'INTEGER', 'DECIMAL', 'BOOLEAN']);
    page.attributeForm.patchValue({ name: 'Bus' });
    page.valueEntry.setValue('PCIe');
    page.reviewAttribute();
    fixture.detectChanges();
    expect(api.defineAttribute).not.toHaveBeenCalled();
    page.back();
    expect(page.attributeForm.controls.name.value).toBe('Bus');
    cleanup();
    fixture.detectChanges();
    expect(page.editor()).toBeNull();
    expect(page.store.attributes()).toEqual([]);
  });
  it('can dismiss a pending dialog without replaying or cancelling a sent command', () => {
    const { api } = setup();
    const fixture = TestBed.createComponent(AdminCategoriesPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.open('category');
    page.categoryForm.setValue({ name: 'Graphics', parentId: 'hardware' });
    page.reviewCategory();
    const pending = new Subject<CategoryResponse>();
    api.createCategory.mockReturnValue(pending);
    page.confirm();
    page.close();
    fixture.detectChanges();
    expect(page.editor()).toBeNull();
    expect(page.store.busy()).toBe(true);
    expect(pending.observed).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Saving changes...');
    pending.next(detail('new'));
    expect(api.createCategory).toHaveBeenCalledOnce();
    expect(page.store.busy()).toBe(false);
  });
});
const detail = (id: string): CategoryResponse => ({
  id,
  name: id,
  parentId: id === 'hardware' ? null : 'hardware',
  ancestorNames: ['Hardware', id],
  subcategoryIds: [],
  leaf: true,
  createdAt: '2026-10-12T00:00:00Z',
});
const attribute = (id = 'bus'): AttributeDefinitionResponse => ({
  id,
  name: id,
  type: 'ENUM',
  required: true,
  allowedValues: ['PCIe', 'USB / C'],
  unit: null,
  definedAt: '2026-10-12T00:00:00Z',
});
const schema = (
  id: string,
  attributes: AttributeDefinitionResponse[] = [],
): EffectiveSchemaResponse => ({ categoryId: id, attributes });
function setup(admin = true) {
  const identity = signal<{ sub: string } | null>({ sub: 'admin' }),
    role = signal(admin);
  const callbacks = new Set<() => void>();
  const api = {
    categories: vi.fn(() => of([node('hardware', [node('gpu')]), node('software')])),
    category: vi.fn((id: string) => of(detail(id))),
    categorySchema: vi.fn((id: string) => of(schema(id, id === 'gpu' ? [attribute()] : []))),
    createCategory: vi.fn(() => of(detail('new'))),
    defineAttribute: vi.fn((_id: string, _request: DefineAttributeRequest) => of(attribute('new'))),
    retireAllowedValue: vi.fn(() => of({ ...attribute(), allowedValues: ['PCIe'] })),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      AdminCategoriesService,
      { provide: CatalogApi, useValue: api },
      {
        provide: AuthStore,
        useValue: {
          identity,
          isAdmin: computed(() => !!identity() && role()),
          registerCleanup: (fn: () => void) => {
            callbacks.add(fn);
            return () => callbacks.delete(fn);
          },
        },
      },
    ],
  });
  const store = TestBed.inject(AdminCategoriesService);
  TestBed.tick();
  return { store, api, identity, role, cleanup: () => callbacks.forEach((fn) => fn()) };
}
afterEach(() => TestBed.resetTestingModule());
describe('category administration contracts', () => {
  it('restricts root names and rejects blank names without invented limits', () => {
    expect(categoryError({ name: 'Hardware', parentId: null })).toEqual({});
    expect(categoryError({ name: 'Software', parentId: null })).toEqual({});
    expect(categoryError({ name: 'Other', parentId: null })).toHaveProperty('name');
    expect(categoryError({ name: '  ', parentId: 'hardware' })).toHaveProperty('name');
    expect(categoryError({ name: 'x'.repeat(2000), parentId: 'hardware' })).toEqual({});
  });
  it.each(['ENUM', 'INTEGER', 'DECIMAL', 'BOOLEAN'] as const)(
    'builds only supported %s fields',
    (type) => {
      const request = attributeRequest(' RAM ', type, true, ['PCIe'], ' GB ');
      expect(request).toEqual({
        name: 'RAM',
        type,
        required: true,
        allowedValues: type === 'ENUM' ? ['PCIe'] : [],
        unit: 'GB',
      });
      expect(attributeError(request)).toEqual({});
    },
  );
  it('rejects unsupported types, enum duplicates and invalid combinations', () => {
    expect(attributeError({ ...attribute(), type: 'TEXT' as never })).toHaveProperty('type');
    expect(attributeError({ ...attribute(), allowedValues: [] })).toHaveProperty('allowedValues');
    expect(attributeError({ ...attribute(), allowedValues: ['Café', ' cafe '] })).toHaveProperty(
      'allowedValues',
    );
    expect(attributeError({ ...attribute(), type: 'BOOLEAN' })).toHaveProperty('allowedValues');
  });
});
describe('category tree and creation', () => {
  it('loads hierarchy and selected detail/effective schema', () => {
    const { store, api } = setup();
    expect(store.rows().map((row) => [row.id, row.depth])).toEqual([
      ['hardware', 0],
      ['gpu', 1],
      ['software', 0],
    ]);
    store.select('gpu');
    expect(store.category()?.id).toBe('gpu');
    expect(store.attributes()).toEqual([attribute()]);
    expect(api.categorySchema).toHaveBeenCalledWith('hardware');
    expect(store.ownAttribute('bus')).toBe(true);
  });
  it('does not expose inherited attributes as locally editable', () => {
    const { store, api } = setup();
    api.categorySchema.mockImplementation((id) => of(schema(id, [attribute()])));
    store.select('gpu');
    expect(store.ownAttribute('bus')).toBe(false);
    store.retire('bus', 'PCIe');
    expect(api.retireAllowedValue).not.toHaveBeenCalled();
  });
  it('cancels superseded selection reads and ignores late responses', () => {
    const { store, api } = setup();
    const source = new Subject<CategoryResponse>();
    api.category.mockReturnValueOnce(source);
    store.select('gpu');
    store.select('hardware');
    expect(source.observed).toBe(false);
    source.next(detail('gpu'));
    expect(store.category()?.id).toBe('hardware');
  });
  it('refreshes the server tree and selects the newly created category', () => {
    const { store, api } = setup();
    const done = vi.fn();
    api.categories.mockReturnValue(of([node('hardware', [node('gpu'), node('new')])]));
    store.create({ name: 'New', parentId: 'hardware' }, done);
    expect(api.createCategory).toHaveBeenCalledWith({ name: 'New', parentId: 'hardware' });
    expect(api.categories).toHaveBeenCalledTimes(2);
    expect(store.category()?.id).toBe('new');
    expect(done).toHaveBeenCalledOnce();
  });
  it.each([
    ['duplicate-sibling-name', 'name'],
    ['invalid-category-name', 'name'],
    ['category-not-leaf', 'parentId'],
  ])('binds %s to %s', (code, field) => {
    const { store, api } = setup();
    api.createCategory.mockReturnValue(throwError(() => ({ status: 422, code, fieldErrors: {} })));
    const done = vi.fn();
    store.create({ name: 'New', parentId: 'hardware' }, done);
    expect(store.fields()).toHaveProperty(field);
    expect(done).not.toHaveBeenCalled();
  });
  it('keeps successful writes from being retried after a failed refresh', () => {
    const { store, api } = setup();
    api.categories
      .mockReturnValueOnce(throwError(() => ({ status: 500, code: '', fieldErrors: {} })))
      .mockReturnValue(of([node('hardware', [node('new')])]));
    const done = vi.fn();
    store.create({ name: 'New', parentId: 'hardware' }, done);
    expect(done).toHaveBeenCalledOnce();
    expect(store.state()).toBe('error');
    store.reload();
    expect(api.createCategory).toHaveBeenCalledOnce();
    expect(store.category()?.id).toBe('new');
  });
  it('prevents duplicate and concurrent writes', () => {
    const { store, api } = setup();
    const source = new Subject<CategoryResponse>();
    api.createCategory.mockReturnValue(source);
    store.create({ name: 'New', parentId: 'hardware' });
    store.create({ name: 'New', parentId: 'hardware' });
    store.select('gpu');
    expect(api.createCategory).toHaveBeenCalledOnce();
    expect(store.busy()).toBe(true);
    source.next(detail('new'));
    source.complete();
    expect(store.busy()).toBe(false);
  });
  it('does not fetch or mutate for non-admins', () => {
    const { store, api } = setup(false);
    store.reload();
    store.create({ name: 'Hardware', parentId: null });
    expect(store.state()).toBe('forbidden');
    expect(api.categories).not.toHaveBeenCalled();
    expect(api.createCategory).not.toHaveBeenCalled();
  });
  it('clears data and cancels requests on cleanup and role loss', () => {
    const { store, api, role, cleanup } = setup();
    const source = new Subject<CategoryResponse>();
    api.createCategory.mockReturnValue(source);
    store.create({ name: 'New', parentId: 'hardware' });
    cleanup();
    expect(source.observed).toBe(false);
    expect(store.tree()).toEqual([]);
    role.set(false);
    TestBed.tick();
    expect(store.state()).toBe('forbidden');
  });
  it('clears stale data after backend denial', () => {
    const { store, api } = setup();
    api.category.mockReturnValue(throwError(() => ({ status: 403, code: 'access-denied' })));
    store.select('gpu');
    expect(store.state()).toBe('forbidden');
    expect(store.tree()).toEqual([]);
  });
});
