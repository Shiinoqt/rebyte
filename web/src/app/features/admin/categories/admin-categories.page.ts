import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FocusTrapFactory } from '@angular/cdk/a11y';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  AttributeDefinitionResponse,
  AttributeType,
  CreateCategoryRequest,
  DefineAttributeRequest,
} from '../../../core/api/api-models';
import { AuthStore } from '../../../core/auth/auth.store';
import { ModalDialog } from '../../../shared/ui/dialog';
import { IconButton } from '../../../shared/ui/navigation';
import {
  AdminCategoriesService,
  ATTRIBUTE_TYPES,
  attributeError,
  attributeRequest,
  categoryError,
  trim,
} from './admin-categories.service';

type Decision =
  | { kind: 'category'; request: CreateCategoryRequest }
  | { kind: 'attribute'; request: DefineAttributeRequest }
  | { kind: 'retire'; attribute: AttributeDefinitionResponse; value: string };
@Component({
  selector: 'app-admin-categories-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, ModalDialog, IconButton],
  providers: [AdminCategoriesService],
  styleUrl: './admin-categories.page.css',
  template: `
    <section class="categories-page" aria-labelledby="categories-heading">
      <header class="page-heading">
        <h1 id="categories-heading">Categories</h1>
      </header>
      @if (store.busy() && !editor()) {
        <p role="status">Saving changes...</p>
      }
      @if (store.message() && !editor()) {
        <p class="error" role="alert">{{ store.message() }}</p>
      }
      @if (store.state() === 'forbidden') {
        <div class="state">
          <h2>Admin access required</h2>
          <p>You do not have access to category administration.</p>
        </div>
      } @else {
        <p class="notice" role="status">{{ store.notice() }}</p>
        <div class="category-layout">
          <div class="category-sidebar">
            <div class="actions">
              <button
                class="primary"
                [disabled]="store.busy() || store.state() !== 'ready'"
                (click)="open('category')"
              >
                Add category
              </button>
              <app-icon-button
                label="Refresh categories"
                title="Refresh categories"
                [disabled]="store.busy() || store.state() === 'loading' || !!editor()"
                (activated)="store.reload()"
              >
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.75"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                >
                  <path d="M20 7v5h-5" />
                  <path d="M20 12a8 8 0 1 0-2.34 5.66" />
                </svg>
              </app-icon-button>
            </div>
            @if (store.state() === 'ready' && store.tree().length) {
              <nav class="category-tree" aria-label="Category tree">
                <ul>
                  @for (row of store.rows(); track row.id; let index = $index) {
                    <li>
                      <button
                        [style.padding-inline-start.px]="16 + row.depth * 20"
                        [class.selected]="store.selected() === row.id"
                        [attr.aria-current]="store.selected() === row.id ? 'true' : null"
                        [attr.aria-label]="row.path"
                        [disabled]="store.busy()"
                        (click)="store.select(row.id)"
                        (keydown)="treeKey($event, index)"
                      >
                        {{ row.name }}
                      </button>
                    </li>
                  }
                </ul>
              </nav>
              <div class="phone-selector">
                <label for="selected-category">Category</label>
                <select
                  id="selected-category"
                  [formControl]="selection"
                  [disabled]="store.busy()"
                  (change)="store.select($any($event.target).value)"
                >
                  @for (row of store.rows(); track row.id) {
                    <option [value]="row.id">{{ row.path }}</option>
                  }
                </select>
              </div>
            }
          </div>
          <section class="schema" aria-label="Category attributes">
            @if (store.state() === 'loading') {
              <p role="status">Loading categories...</p>
            } @else if (store.state() === 'error') {
              <div class="state">
                <h2>Categories unavailable</h2>
                <p>The category tree could not be refreshed.</p>
                <button (click)="store.reload()">Try again</button>
              </div>
            } @else if (!store.tree().length) {
              <div class="state">
                <h2>No categories yet</h2>
                <p>Add Hardware and Software as the root categories.</p>
              </div>
            } @else {
              @if (store.selectionLoading()) {
                <p role="status">Loading attributes...</p>
              } @else if (store.selectionError()) {
                <p role="alert">{{ store.selectionError() }}</p>
                <button (click)="store.select(store.selected()!)">Try again</button>
              } @else if (store.category(); as category) {
                <p class="path">{{ category.ancestorNames.join(' / ') }}</p>
                <header class="schema-heading">
                  <h2>{{ category.name }}</h2>
                  <button [disabled]="store.busy()" (click)="open('attribute')">
                    Add attribute
                  </button>
                </header>
                @if (!store.attributes().length) {
                  <p class="empty">No attributes defined yet.</p>
                }
                @for (attribute of store.attributes(); track attribute.id) {
                  <article class="attribute-row">
                    <div class="attribute-heading">
                      <h3>{{ attribute.name }}</h3>
                      <span class="type">{{ attribute.type }}</span
                      ><span class="muted">{{ attribute.required ? 'Required' : 'Optional' }}</span>
                      @if (attribute.unit) {
                        <span class="unit">{{ attribute.unit }}</span>
                      }
                    </div>
                    @if (!store.ownAttribute(attribute.id)) {
                      <p class="inherited">Inherited from a parent category</p>
                    }
                    @if (attribute.type === 'ENUM') {
                      <ul class="values" [attr.aria-label]="attribute.name + ' allowed values'">
                        @for (value of attribute.allowedValues; track value) {
                          <li>
                            <span>{{ value }}</span>
                            @if (store.ownAttribute(attribute.id)) {
                              <button
                                class="retire"
                                [attr.aria-label]="'Retire ' + value + ' from ' + attribute.name"
                                [disabled]="store.busy()"
                                (click)="retire(attribute, value)"
                              >
                                Retire
                              </button>
                            }
                          </li>
                        }
                      </ul>
                      @if (!attribute.allowedValues.length) {
                        <p class="muted">No active allowed values</p>
                      }
                    }
                  </article>
                }
              }
            }
          </section>
        </div>
      }
    </section>
    @if (editor()) {
      <app-modal-dialog #editorDialog [heading]="heading()" size="form" (closed)="close()">
        @if (decision(); as target) {
          @switch (target.kind) {
            @case ('category') {
              <p>
                Add <strong>{{ target.request.name }}</strong>
                {{
                  target.request.parentId
                    ? 'under ' + parentName(target.request.parentId)
                    : 'as a root category'
                }}?
              </p>
              <p class="muted">Categories cannot be deleted or moved.</p>
            }
            @case ('attribute') {
              <p>
                Add <strong>{{ target.request.name }}</strong> ({{ target.request.type }}) to
                {{ store.category()?.name }}?
              </p>
              <p class="muted">
                This definition cannot be edited or deleted. Descendant categories inherit it.
              </p>
            }
            @case ('retire') {
              <p>
                Retire <strong>{{ target.value }}</strong> from {{ target.attribute.name }}?
              </p>
              <p class="muted">
                Existing listings keep their value. It will no longer be available for new
                selections.
              </p>
            }
          }
          <p class="error" role="alert">{{ store.message() }}</p>
          <div class="dialog-actions">
            <button [disabled]="store.busy()" (click)="back()">
              {{ target.kind === 'retire' ? 'Cancel' : 'Back' }}</button
            ><button class="primary" [disabled]="store.busy()" (click)="confirm()">
              {{
                store.busy()
                  ? 'Saving...'
                  : target.kind === 'retire'
                    ? 'Retire value'
                    : target.kind === 'category'
                      ? 'Add category'
                      : 'Add attribute'
              }}
            </button>
          </div>
        } @else if (editor() === 'category') {
          <form [formGroup]="categoryForm" (ngSubmit)="reviewCategory()">
            <label for="category-name">Name</label
            ><input
              id="category-name"
              formControlName="name"
              autofocus
              [attr.aria-invalid]="!!errors()['name']"
              aria-describedby="category-name-error"
            />
            <p class="error" id="category-name-error" role="alert">{{ errors()['name'] }}</p>
            <label for="category-parent">Parent</label
            ><select
              id="category-parent"
              formControlName="parentId"
              [attr.aria-invalid]="!!errors()['parentId']"
              aria-describedby="category-parent-error"
            >
              <option value="">Root category</option>
              @for (row of store.rows(); track row.id) {
                <option [value]="row.id">{{ row.path }}</option>
              }
            </select>
            <p id="category-parent-error" class="error" role="alert">{{ errors()['parentId'] }}</p>
            @if (!categoryForm.controls.parentId.value) {
              <p class="muted">Root names must be Hardware or Software.</p>
            }
            <div class="dialog-actions">
              <button type="button" (click)="close()">Cancel</button
              ><button type="submit" class="primary">Review category</button>
            </div>
          </form>
        } @else if (editor() === 'attribute') {
          <form [formGroup]="attributeForm" (ngSubmit)="reviewAttribute()">
            <label for="attribute-name">Name</label
            ><input
              id="attribute-name"
              formControlName="name"
              autofocus
              [attr.aria-invalid]="!!errors()['name']"
              aria-describedby="attribute-name-error"
            />
            <p id="attribute-name-error" class="error" role="alert">{{ errors()['name'] }}</p>
            <label for="attribute-type">Type</label
            ><select
              id="attribute-type"
              formControlName="type"
              (change)="values.set([]); valueEntry.setValue('')"
              [attr.aria-invalid]="!!errors()['type']"
              aria-describedby="type-error"
            >
              @for (type of types; track type) {
                <option [value]="type">{{ type }}</option>
              }
            </select>
            <p id="type-error" class="error" role="alert">{{ errors()['type'] }}</p>
            <label class="checkbox"
              ><input type="checkbox" formControlName="required" />Required</label
            >
            @if (attributeForm.controls.type.value === 'ENUM') {
              <label for="allowed-value">Allowed values</label>
              <div class="value-entry">
                <input
                  id="allowed-value"
                  [formControl]="valueEntry"
                  placeholder="Enter a value"
                  (keydown.enter)="$event.preventDefault(); addValue()"
                  [attr.aria-invalid]="!!errors()['allowedValues']"
                  aria-describedby="values-error"
                /><button type="button" (click)="addValue()">Add value</button>
              </div>
              <ul class="values editable">
                @for (value of values(); track $index; let index = $index) {
                  <li>
                    <span>{{ value }}</span
                    ><button
                      type="button"
                      [attr.aria-label]="'Remove ' + value"
                      (click)="removeValue(index)"
                    >
                      <svg
                        width="16"
                        height="16"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="1.75"
                        aria-hidden="true"
                      >
                        <path d="m6 6 12 12M18 6 6 18" />
                      </svg>
                    </button>
                  </li>
                }
              </ul>
              <p id="values-error" class="error" role="alert">{{ errors()['allowedValues'] }}</p>
            }
            <label for="attribute-unit">Unit (optional)</label
            ><input
              id="attribute-unit"
              formControlName="unit"
              placeholder="e.g. GB"
              [attr.aria-invalid]="!!errors()['unit']"
              aria-describedby="unit-error"
            />
            <p id="unit-error" class="error" role="alert">{{ errors()['unit'] }}</p>
            <p class="error" role="alert">{{ errors()['definition'] }}</p>
            <div class="dialog-actions">
              <button type="button" (click)="close()">Cancel</button
              ><button type="submit" class="primary">Review attribute</button>
            </div>
          </form>
        }
      </app-modal-dialog>
    }
  `,
})
export class AdminCategoriesPage {
  readonly store = inject(AdminCategoriesService);
  private readonly auth = inject(AuthStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly focusTraps = inject(FocusTrapFactory);
  private readonly dialogElement = viewChild<ModalDialog, ElementRef<HTMLElement>>('editorDialog', {
    read: ElementRef,
  });
  readonly editor = signal<'category' | 'attribute' | 'retire' | null>(null);
  readonly decision = signal<Decision | null>(null);
  readonly localErrors = signal<Record<string, string>>({});
  readonly errors = computed(() => ({ ...this.localErrors(), ...this.store.fields() }));
  readonly types = ATTRIBUTE_TYPES;
  readonly values = signal<string[]>([]);
  readonly valueEntry = new FormControl('', { nonNullable: true });
  readonly selection = new FormControl('', { nonNullable: true });
  readonly categoryForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    parentId: new FormControl('', { nonNullable: true }),
  });
  readonly attributeForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    type: new FormControl<AttributeType>('ENUM', { nonNullable: true }),
    required: new FormControl(false, { nonNullable: true }),
    unit: new FormControl('', { nonNullable: true }),
  });
  readonly heading = computed(() =>
    this.decision()
      ? 'Confirm ' + (this.decision()!.kind === 'retire' ? 'retirement' : this.decision()!.kind)
      : this.editor() === 'category'
        ? 'Add category'
        : 'Add attribute',
  );
  constructor() {
    effect(() => this.selection.setValue(this.store.selected() ?? '', { emitEvent: false }));
    afterRenderEffect((cleanup) => {
      this.decision();
      const body = this.dialogElement()?.nativeElement.querySelector<HTMLElement>('dialog > .body');
      if (!body) return;
      const trap = this.focusTraps.create(body);
      cleanup(() => trap.destroy());
      // Switching the form to its confirmation removes the focused input.
      if (!body.contains(body.ownerDocument.activeElement))
        body.querySelector<HTMLElement>('input,select,button')?.focus();
    });
    effect(() => {
      this.auth.identity();
      if (!this.store.allowed()) this.reset();
    });
    effect(() => {
      if (
        !this.store.busy() &&
        Object.keys(this.store.fields()).length &&
        this.decision()?.kind !== 'retire'
      )
        this.decision.set(null);
    });
    const unregister = this.auth.registerCleanup(() => this.reset());
    inject(DestroyRef).onDestroy(unregister);
  }
  open(kind: 'category' | 'attribute'): void {
    if (!this.store.allowed() || this.store.busy()) return;
    this.reset();
    this.store.clearErrors();
    this.categoryForm.controls.parentId.setValue(this.store.selected() ?? '');
    this.editor.set(kind);
  }
  retire(attribute: AttributeDefinitionResponse, value: string): void {
    if (!this.store.ownAttribute(attribute.id) || this.store.busy()) return;
    this.reset();
    this.store.clearErrors();
    this.editor.set('retire');
    this.decision.set({ kind: 'retire', attribute, value });
  }
  reviewCategory(): void {
    this.store.clearErrors();
    const value = this.categoryForm.getRawValue();
    const request = { name: trim(value.name), parentId: value.parentId || null };
    this.localErrors.set(categoryError(request));
    if (Object.keys(this.localErrors()).length) return;
    this.decision.set({ kind: 'category', request });
  }
  addValue(): void {
    const value = trim(this.valueEntry.value);
    if (value) {
      this.values.update((rows) => [...rows, value]);
      this.valueEntry.setValue('');
      this.localErrors.set({});
    }
  }
  removeValue(index: number): void {
    this.values.update((rows) => rows.filter((_, i) => i !== index));
  }
  reviewAttribute(): void {
    this.store.clearErrors();
    if (trim(this.valueEntry.value) && this.attributeForm.controls.type.value === 'ENUM')
      this.addValue();
    const value = this.attributeForm.getRawValue(),
      request = attributeRequest(value.name, value.type, value.required, this.values(), value.unit);
    this.localErrors.set(attributeError(request));
    if (Object.keys(this.localErrors()).length) return;
    this.decision.set({ kind: 'attribute', request });
  }
  confirm(): void {
    const target = this.decision();
    if (!target || this.store.busy()) return;
    const done = () => this.reset();
    if (target.kind === 'category') this.store.create(target.request, done);
    else if (target.kind === 'attribute') this.store.define(target.request, done);
    else this.store.retire(target.attribute.id, target.value, done);
    if (!this.store.busy() && Object.keys(this.store.fields()).length) this.decision.set(null);
  }
  back(): void {
    if (this.store.busy()) return;
    if (this.decision()?.kind === 'retire') this.close();
    else this.decision.set(null);
  }
  close(): void {
    // Dismissing a dialog does not cancel a command already accepted by the server.
    this.reset();
  }
  parentName(id: string): string {
    return this.store.rows().find((row) => row.id === id)?.path ?? id;
  }
  treeKey(event: KeyboardEvent, index: number): void {
    const buttons = Array.from(
      this.host.nativeElement.querySelectorAll<HTMLButtonElement>('.category-tree button'),
    );
    const next =
      event.key === 'ArrowDown'
        ? Math.min(index + 1, buttons.length - 1)
        : event.key === 'ArrowUp'
          ? Math.max(index - 1, 0)
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? buttons.length - 1
              : -1;
    if (next < 0) return;
    event.preventDefault();
    buttons[next]?.focus();
    buttons[next]?.click();
  }
  private reset(): void {
    this.editor.set(null);
    this.decision.set(null);
    this.localErrors.set({});
    this.values.set([]);
    this.valueEntry.reset();
    this.categoryForm.reset();
    this.attributeForm.reset();
  }
}
