import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import {
  AttributeDefinitionResponse,
  AttributeValueInput,
  ListingAttributeView,
} from '../../core/api/api-models';

/** Raw control text per attribute id; booleans are 'true', 'false' or '' (not stated). */
export type RawAttributes = Readonly<Record<string, string>>;

const INTEGER = /^-?\d+$/;
const DECIMAL = /^-?\d+(\.\d+)?$/;

export function attributeControlId(definition: AttributeDefinitionResponse): string {
  return 'attr-' + definition.id;
}

/** Validates one value against its definition; returns the error copy or ''. */
export function attributeError(definition: AttributeDefinitionResponse, raw: string): string {
  const value = raw.trim();
  if (value === '') {
    return definition.required ? `${definition.name} is required for this category.` : '';
  }
  switch (definition.type) {
    case 'ENUM':
      return definition.allowedValues.includes(value)
        ? ''
        : `Choose one of the listed values for ${definition.name}.`;
    case 'INTEGER':
      return INTEGER.test(value) && Number.isSafeInteger(Number(value))
        ? ''
        : `${definition.name} must be a whole number.`;
    case 'DECIMAL':
      return DECIMAL.test(value) && Number.isFinite(Number(value))
        ? ''
        : `${definition.name} must be a number.`;
    case 'BOOLEAN':
      return value === 'true' || value === 'false' ? '' : `${definition.name} must be yes or no.`;
  }
}

// catalog-service accepts only a JSON string for ENUM, an integral number for INTEGER, any number
// for DECIMAL and a JSON boolean for BOOLEAN; numeric strings are rejected, so values are typed here.
export function buildAttributePayload(
  definitions: readonly AttributeDefinitionResponse[],
  raw: RawAttributes,
): { attributes: AttributeValueInput[]; errors: Record<string, string> } {
  const attributes: AttributeValueInput[] = [];
  const errors: Record<string, string> = {};
  for (const definition of definitions) {
    const value = (raw[definition.id] ?? '').trim();
    const error = attributeError(definition, value);
    if (error) {
      errors[definition.id] = error;
      continue;
    }
    if (value === '') continue;
    switch (definition.type) {
      case 'ENUM':
        attributes.push({ attributeId: definition.id, value });
        break;
      case 'INTEGER':
      case 'DECIMAL':
        attributes.push({ attributeId: definition.id, value: Number(value) });
        break;
      case 'BOOLEAN':
        attributes.push({ attributeId: definition.id, value: value === 'true' });
        break;
    }
  }
  return { attributes: Object.keys(errors).length ? [] : attributes, errors };
}

// Listing detail exposes attributes by name only, so edit joins them to the schema by name.
export function rawFromListing(
  definitions: readonly AttributeDefinitionResponse[],
  values: readonly ListingAttributeView[],
): Record<string, string> {
  const byName = new Map(values.map((view) => [view.name, view]));
  const raw: Record<string, string> = {};
  for (const definition of definitions) {
    const view = byName.get(definition.name);
    if (view && view.type === definition.type && view.value !== null) {
      raw[definition.id] = String(view.value);
    }
  }
  return raw;
}

/** Carries values whose attribute name and type survive a category change. */
export function carryOver(
  previous: readonly AttributeDefinitionResponse[],
  next: readonly AttributeDefinitionResponse[],
  raw: RawAttributes,
): { raw: Record<string, string>; cleared: number } {
  const nextByName = new Map(next.map((definition) => [definition.name, definition]));
  const carried: Record<string, string> = {};
  let cleared = 0;
  for (const definition of previous) {
    const value = (raw[definition.id] ?? '').trim();
    if (value === '') continue;
    const target = nextByName.get(definition.name);
    if (target && target.type === definition.type && !attributeError(target, value)) {
      carried[target.id] = value;
    } else {
      cleared++;
    }
  }
  return { raw: carried, cleared };
}

@Component({
  selector: 'app-attribute-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './sell-edit.page.css',
  template: `
    @for (definition of definitions(); track definition.id) {
      @let id = controlId(definition);
      @let error = errors()[definition.id] ?? '';
      <div class="attribute" [class.invalid]="!!error">
        @switch (definition.type) {
          @case ('ENUM') {
            <label [for]="id"
              >{{ definition.name }}
              @if (definition.required) {
                <span aria-hidden="true">*</span>
              }
            </label>
            <select
              [id]="id"
              [value]="values()[definition.id] ?? ''"
              [attr.aria-required]="definition.required"
              [attr.aria-invalid]="!!error"
              [attr.aria-describedby]="error ? id + '-error' : null"
              (change)="emit(definition, $event)"
              (blur)="blurred.emit(definition.id)"
            >
              <option value="" [selected]="!values()[definition.id]">Select...</option>
              @for (allowed of definition.allowedValues; track allowed) {
                <option [value]="allowed" [selected]="values()[definition.id] === allowed">
                  {{ allowed }}
                </option>
              }
            </select>
          }
          @case ('BOOLEAN') {
            @if (definition.required) {
              <fieldset
                class="yes-no"
                [attr.aria-invalid]="!!error"
                [attr.aria-describedby]="error ? id + '-error' : null"
              >
                <legend>{{ definition.name }} <span aria-hidden="true">*</span></legend>
                @for (choice of yesNo; track choice.value) {
                  <label class="chip">
                    <input
                      type="radio"
                      [id]="id + '-' + choice.value"
                      [name]="id"
                      [value]="choice.value"
                      [checked]="values()[definition.id] === choice.value"
                      (change)="emit(definition, $event)"
                      (blur)="blurred.emit(definition.id)"
                    />
                    {{ choice.label }}
                  </label>
                }
              </fieldset>
            } @else {
              <div class="switch-row">
                <label [for]="id">{{ definition.name }}</label>
                <input
                  type="checkbox"
                  role="switch"
                  [id]="id"
                  [checked]="values()[definition.id] === 'true'"
                  (change)="toggle(definition, $event)"
                />
              </div>
            }
          }
          @default {
            <label [for]="id"
              >{{ definition.name }}
              @if (definition.required) {
                <span aria-hidden="true">*</span>
              }
            </label>
            <div class="with-unit">
              <input
                type="text"
                [id]="id"
                [attr.inputmode]="definition.type === 'INTEGER' ? 'numeric' : 'decimal'"
                [value]="values()[definition.id] ?? ''"
                [attr.aria-required]="definition.required"
                [attr.aria-invalid]="!!error"
                [attr.aria-describedby]="
                  [definition.unit ? id + '-unit' : '', error ? id + '-error' : '']
                    .join(' ')
                    .trim() || null
                "
                (input)="emit(definition, $event)"
                (blur)="blurred.emit(definition.id)"
              />
              @if (definition.unit) {
                <span class="unit" [id]="id + '-unit'">{{ definition.unit }}</span>
              }
            </div>
          }
        }
        @if (error) {
          <p class="field-error" role="alert" [id]="id + '-error'">{{ error }}</p>
        }
      </div>
    }
  `,
})
export class AttributeForm {
  readonly definitions = input.required<readonly AttributeDefinitionResponse[]>();
  readonly values = input.required<RawAttributes>();
  readonly errors = input<Readonly<Record<string, string>>>({});
  readonly valueChange = output<{ id: string; value: string }>();
  readonly blurred = output<string>();
  readonly yesNo = [
    { value: 'true', label: 'Yes' },
    { value: 'false', label: 'No' },
  ] as const;

  controlId(definition: AttributeDefinitionResponse): string {
    return attributeControlId(definition);
  }

  emit(definition: AttributeDefinitionResponse, event: Event): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement;
    this.valueChange.emit({ id: definition.id, value: target.value });
  }

  toggle(definition: AttributeDefinitionResponse, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.valueChange.emit({ id: definition.id, value: checked ? 'true' : 'false' });
  }
}
