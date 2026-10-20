import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { forkJoin, map, Observable, of, Subscription, switchMap, take } from 'rxjs';
import {
  AttributeDefinitionResponse,
  AttributeType,
  CategoryResponse,
  CategoryTreeNode,
  CreateCategoryRequest,
  DefineAttributeRequest,
} from '../../../core/api/api-models';
import { CatalogApi } from '../../../core/api/catalog-api';
import { AuthStore } from '../../../core/auth/auth.store';
import { AppError } from '../../../core/http/app-error';
import { errorCopy } from '../../../core/http/error-copy';

export const ATTRIBUTE_TYPES: AttributeType[] = ['ENUM', 'INTEGER', 'DECIMAL', 'BOOLEAN'];
export const trim = (value: string) => value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
const blank = (value: string) =>
  /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(
    value,
  );
const normalize = (value: string) =>
  trim(value).normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase();
export function categoryError(request: CreateCategoryRequest): Record<string, string> {
  if (blank(request.name)) return { name: 'Enter a category name.' };
  if (request.parentId === null && !['Hardware', 'Software'].includes(request.name))
    return { name: 'Root categories must be named Hardware or Software.' };
  return {};
}
export function attributeRequest(
  name: string,
  type: AttributeType,
  required: boolean,
  values: string[],
  unit: string,
): DefineAttributeRequest {
  return {
    name: trim(name),
    type,
    required,
    allowedValues: type === 'ENUM' ? values.map(trim) : [],
    unit: trim(unit) || null,
  };
}
export function attributeError(request: DefineAttributeRequest): Record<string, string> {
  const errors: Record<string, string> = {};
  if (blank(request.name)) errors['name'] = 'Enter an attribute name.';
  if (!ATTRIBUTE_TYPES.includes(request.type)) errors['type'] = 'Choose a supported type.';
  if (
    request.type === 'ENUM' &&
    (!request.allowedValues.length || request.allowedValues.some(blank))
  )
    errors['allowedValues'] = 'Add at least one nonblank value.';
  else if (
    request.type === 'ENUM' &&
    new Set(request.allowedValues.map(normalize)).size !== request.allowedValues.length
  )
    errors['allowedValues'] = 'Each allowed value must be unique.';
  else if (request.type !== 'ENUM' && request.allowedValues.length)
    errors['allowedValues'] = 'Only ENUM attributes accept allowed values.';
  return errors;
}
interface TreeRow {
  id: string;
  name: string;
  depth: number;
  path: string;
}
function flatten(nodes: CategoryTreeNode[], depth = 0, parents: string[] = []): TreeRow[] {
  return nodes.flatMap((node) => [
    { id: node.id, name: node.name, depth, path: [...parents, node.name].join(' / ') },
    ...flatten(node.children, depth + 1, [...parents, node.name]),
  ]);
}
@Injectable()
export class AdminCategoriesService {
  private readonly api = inject(CatalogApi);
  private readonly auth = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly data = signal<CategoryTreeNode[]>([]);
  private readonly detail = signal<CategoryResponse | null>(null);
  private readonly definitions = signal<AttributeDefinitionResponse[]>([]);
  private readonly inherited = signal(new Set<string>());
  private readonly denied = signal(false);
  private readonly session = signal<string | null>(null);
  private readonly phase = signal<'loading' | 'ready' | 'error'>('loading');
  private treeRead?: Subscription;
  private detailRead?: Subscription;
  private write?: Subscription;
  private generation = 0;
  private selectionGeneration = 0;
  private commandGeneration = 0;
  private pendingSelection: string | null = null;
  readonly allowed = computed(
    () => this.auth.isAdmin() && !this.denied() && this.session() === this.auth.identity()?.sub,
  );
  readonly tree = computed(() => (this.allowed() ? this.data() : []));
  readonly rows = computed(() => flatten(this.tree()));
  readonly category = computed(() => (this.allowed() ? this.detail() : null));
  readonly attributes = computed(() => (this.allowed() ? this.definitions() : []));
  readonly state = computed(() => (this.allowed() ? this.phase() : 'forbidden'));
  readonly selected = signal<string | null>(null);
  readonly selectionLoading = signal(false);
  readonly selectionError = signal('');
  readonly busy = signal(false);
  readonly fields = signal<Record<string, string>>({});
  readonly message = signal('');
  readonly notice = signal('');
  constructor() {
    effect(() => {
      const sub = this.auth.identity()?.sub ?? null,
        admin = this.auth.isAdmin();
      untracked(() => {
        this.clear();
        this.session.set(sub);
        this.denied.set(!admin);
        if (admin) this.reload();
      });
    });
    const unregister = this.auth.registerCleanup(() => {
      this.clear();
      this.denied.set(true);
    });
    this.destroyRef.onDestroy(() => {
      unregister();
      this.clear();
    });
  }
  ownAttribute(id: string): boolean {
    return (
      this.allowed() && this.attributes().some((row) => row.id === id) && !this.inherited().has(id)
    );
  }
  reload(): void {
    if (!this.allowed() || this.busy()) return;
    this.treeRead?.unsubscribe();
    this.detailRead?.unsubscribe();
    ++this.selectionGeneration;
    const generation = ++this.generation;
    this.phase.set('loading');
    this.selectionLoading.set(false);
    this.treeRead = this.api.categories().subscribe({
      next: (tree) => {
        if (generation !== this.generation || !this.allowed()) return;
        this.data.set(tree);
        this.phase.set('ready');
        const id = this.pendingSelection ?? this.selected();
        const chosen = this.rows().find((row) => row.id === id) ?? this.rows()[0];
        this.pendingSelection = null;
        if (chosen) this.select(chosen.id);
        else {
          this.selected.set(null);
          this.detail.set(null);
          this.definitions.set([]);
        }
      },
      error: (error: AppError) => {
        if (generation !== this.generation) return;
        if (!this.forbidden(error)) this.phase.set('error');
      },
    });
  }
  select(id: string): void {
    if (!this.allowed() || this.busy() || !this.rows().some((row) => row.id === id)) return;
    this.detailRead?.unsubscribe();
    const generation = ++this.selectionGeneration;
    this.selected.set(id);
    this.detail.set(null);
    this.definitions.set([]);
    this.inherited.set(new Set());
    this.selectionLoading.set(true);
    this.selectionError.set('');
    this.detailRead = forkJoin({
      detail: this.api.category(id),
      schema: this.api.categorySchema(id),
    })
      .pipe(
        switchMap((result) =>
          result.detail.parentId === null
            ? of({ ...result, parent: [] as AttributeDefinitionResponse[] })
            : this.api
                .categorySchema(result.detail.parentId)
                .pipe(map((parent) => ({ ...result, parent: parent.attributes }))),
        ),
      )
      .subscribe({
        next: (result) => {
          if (generation !== this.selectionGeneration || !this.allowed()) return;
          this.detail.set(result.detail);
          this.definitions.set(result.schema.attributes);
          this.inherited.set(new Set(result.parent.map((row) => row.id)));
          this.selectionLoading.set(false);
        },
        error: (error: AppError) => {
          if (generation !== this.selectionGeneration) return;
          this.selectionLoading.set(false);
          if (!this.forbidden(error))
            this.selectionError.set('Category details could not be loaded. Try again.');
        },
      });
  }
  create(request: CreateCategoryRequest, done: () => void = () => {}): void {
    if (!this.allowed() || this.busy()) return;
    const errors = categoryError(request);
    this.fields.set(errors);
    if (Object.keys(errors).length) return;
    this.mutate(
      this.api.createCategory({ name: request.name, parentId: request.parentId }),
      'category',
      (created) => {
        this.pendingSelection = created.id;
        this.notice.set('Category added.');
        done();
        this.reload();
      },
    );
  }
  define(request: DefineAttributeRequest, done: () => void = () => {}): void {
    const id = this.category()?.id;
    if (!id || !this.allowed() || this.busy()) return;
    const errors = attributeError(request);
    this.fields.set(errors);
    if (Object.keys(errors).length) return;
    const body: DefineAttributeRequest = {
      name: request.name,
      type: request.type,
      required: request.required,
      allowedValues: [...request.allowedValues],
      unit: request.unit,
    };
    this.mutate(this.api.defineAttribute(id, body), 'attribute', () => {
      this.notice.set('Attribute added.');
      done();
      this.select(id);
    });
  }
  retire(attributeId: string, value: string, done: () => void = () => {}): void {
    const id = this.category()?.id,
      definition = this.attributes().find((row) => row.id === attributeId);
    if (
      !id ||
      !this.allowed() ||
      this.busy() ||
      !this.ownAttribute(attributeId) ||
      definition?.type !== 'ENUM' ||
      !definition.allowedValues.includes(value)
    )
      return;
    this.mutate(this.api.retireAllowedValue(id, attributeId, value), 'retire', (result) => {
      this.definitions.update((rows) => rows.map((row) => (row.id === result.id ? result : row)));
      this.notice.set('Allowed value retired.');
      done();
      this.select(id);
    });
  }
  clearErrors(): void {
    this.fields.set({});
    this.message.set('');
  }
  private mutate<T>(
    source: Observable<T>,
    kind: 'category' | 'attribute' | 'retire',
    done: (result: T) => void,
  ): void {
    const generation = ++this.commandGeneration;
    this.busy.set(true);
    this.message.set('');
    this.notice.set('');
    this.write = source.pipe(take(1)).subscribe({
      next: (result) => {
        if (generation !== this.commandGeneration || !this.allowed()) return;
        this.busy.set(false);
        done(result);
      },
      error: (error: AppError) => {
        if (generation !== this.commandGeneration) return;
        this.busy.set(false);
        if (this.forbidden(error)) return;
        const fields: Record<string, string> = { ...error.fieldErrors };
        if (['duplicate-sibling-name', 'duplicate-attribute-name'].includes(error.code))
          fields['name'] = 'This name is already in use in this category path.';
        if (error.code === 'invalid-category-name')
          fields['name'] = 'Root categories must be named Hardware or Software.';
        if (error.code === 'category-not-leaf')
          fields['parentId'] = 'This category already contains listings and cannot have children.';
        if (error.code === 'category-not-found' && kind === 'category')
          fields['parentId'] = 'This parent category is no longer available.';
        if (error.code === 'invalid-attribute-definition') {
          fields['name'] ??= 'Check the attribute name and definition.';
          fields['allowedValues'] ??= 'Check the allowed values for this definition.';
          fields['definition'] =
            'Check the name and allowed values. Names must be unique along the category path and cannot replace fixed listing fields.';
        }
        this.fields.set(fields);
        this.message.set(
          Object.keys(fields).length
            ? 'Check the highlighted fields.'
            : error.status === 409
              ? 'This category changed. Reload its details before trying again.'
              : errorCopy(error),
        );
      },
    });
  }
  private forbidden(error: AppError): boolean {
    if (error.status !== 401 && error.status !== 403) return false;
    this.clear();
    this.denied.set(true);
    return true;
  }
  private clear(): void {
    ++this.generation;
    ++this.selectionGeneration;
    ++this.commandGeneration;
    this.treeRead?.unsubscribe();
    this.detailRead?.unsubscribe();
    this.write?.unsubscribe();
    this.data.set([]);
    this.detail.set(null);
    this.definitions.set([]);
    this.inherited.set(new Set());
    this.selected.set(null);
    this.pendingSelection = null;
    this.selectionLoading.set(false);
    this.selectionError.set('');
    this.busy.set(false);
    this.clearErrors();
    this.notice.set('');
  }
}
