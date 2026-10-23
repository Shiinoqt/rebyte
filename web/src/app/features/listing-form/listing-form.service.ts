import { computed, inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  AttributeDefinitionResponse,
  CategoryTreeNode,
  EditListingRequest,
  EditListingResponse,
  ListingCreatedResponse,
  ListingCardPage,
  ListingDetailResponse,
  ListingType,
  Money,
  PublishHardwareRequest,
  PublishSoftwareRequest,
} from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { UserApi } from '../../core/api/user-api';
import { AuthStore } from '../../core/auth/auth.store';
import { AccountSummary } from '../profile/account-summary';

export interface LeafOption {
  id: string;
  label: string;
}

export type EditTarget =
  | { kind: 'ready'; listing: ListingDetailResponse }
  | { kind: 'missing' }
  | { kind: 'not-owner' }
  | { kind: 'not-editable' };

const ROOT_NAMES: Record<ListingType, string> = { HARDWARE: 'Hardware', SOFTWARE: 'Software' };

export function typeRoot(tree: readonly CategoryTreeNode[], type: ListingType): CategoryTreeNode | undefined {
  return tree.find((node) => node.name === ROOT_NAMES[type]);
}

/** Leaf categories under a type root, labelled by their path below the root. */
export function leavesUnder(tree: readonly CategoryTreeNode[], type: ListingType): LeafOption[] {
  const root = typeRoot(tree, type);
  const leaves: LeafOption[] = [];
  const walk = (node: CategoryTreeNode, path: string[]) => {
    for (const child of node.children) {
      const childPath = [...path, child.name];
      if (child.leaf) leaves.push({ id: child.id, label: childPath.join(' / ') });
      else walk(child, childPath);
    }
  };
  if (root) walk(root, []);
  return leaves;
}

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { status?: unknown }).status === 404;
}

@Injectable()
export class ListingFormService {
  private readonly catalog = inject(CatalogApi);
  private readonly users = inject(UserApi);
  private readonly auth = inject(AuthStore);
  private readonly summary = inject(AccountSummary);
  private treeRequest: Promise<CategoryTreeNode[]> | null = null;

  // Software publishing needs an activated developer profile; null while the summary loads.
  readonly isDeveloper = computed(() => this.summary.aggregates()?.isDeveloper ?? null);

  categories(): Promise<CategoryTreeNode[]> {
    this.treeRequest ??= firstValueFrom(this.catalog.categories()).catch((error) => {
      this.treeRequest = null;
      throw error;
    });
    return this.treeRequest;
  }

  async schema(categoryId: string): Promise<AttributeDefinitionResponse[]> {
    const schema = await firstValueFrom(this.catalog.categorySchema(categoryId));
    return schema.attributes;
  }

  // The backend stays authoritative; this only spares an owner-only form to people who cannot use it.
  async editTarget(id: string): Promise<EditTarget> {
    let listing: ListingDetailResponse;
    try {
      listing = await firstValueFrom(this.catalog.listing(id));
    } catch (error) {
      if (isNotFound(error)) return { kind: 'missing' };
      throw error;
    }
    if (listing.sellerSub !== this.auth.identity()?.sub) return { kind: 'not-owner' };
    if (listing.status !== 'ACTIVE') return { kind: 'not-editable' };
    let cursor: string | null = null;
    const seen = new Set<string>();
    do {
      const page: ListingCardPage = await firstValueFrom(this.catalog.myListings({ size: 20, cursor }));
      if (page.items.some((item) => item.id === id)) return { kind: 'ready', listing };
      cursor = page.nextCursor;
      if (cursor && seen.has(cursor)) throw new Error('Owner listing cursor repeated');
      if (cursor) seen.add(cursor);
    } while (cursor);
    return { kind: 'not-owner' };
  }

  publishHardware(request: PublishHardwareRequest): Promise<ListingCreatedResponse> {
    return firstValueFrom(this.catalog.publishHardware(request));
  }

  publishSoftware(request: PublishSoftwareRequest): Promise<ListingCreatedResponse> {
    return firstValueFrom(this.catalog.publishSoftware(request));
  }

  edit(id: string, request: EditListingRequest): Promise<EditListingResponse> {
    return firstValueFrom(this.catalog.editListing(id, request));
  }

  changePrice(id: string, price: Money): Promise<ListingCreatedResponse> {
    return firstValueFrom(this.catalog.changePrice(id, { price }));
  }

  async remove(id: string): Promise<void> {
    await firstValueFrom(this.catalog.deleteListing(id), { defaultValue: undefined });
  }

  async activateDeveloper(): Promise<void> {
    await firstValueFrom(this.users.activateDeveloperProfile());
    this.summary.refresh();
  }
}
