import { Injectable } from '@angular/core';
import { ApiClient } from './api-client';
import {
  ArtifactDownloadResponse,
  ArtifactUploadPolicyResponse,
  AttributeDefinitionResponse,
  CategoryBrowseQuery,
  CategoryResponse,
  CategoryTreeNode,
  ChangePriceRequest,
  ConfirmArtifactRequest,
  ConfirmPhotoRequest,
  CreateCategoryRequest,
  CursorQuery,
  DefineAttributeRequest,
  DriftReport,
  EditListingRequest,
  EditListingResponse,
  EffectiveSchemaResponse,
  FavouritePage,
  FavouriteQuery,
  FavouriteResponse,
  ListingCardPage,
  ListingCreatedResponse,
  ListingDetailResponse,
  MyListingsQuery,
  PhotoUploadPolicyResponse,
  PublishHardwareRequest,
  PublishSoftwareRequest,
  RebuildStatusResponse,
  ReleaseVersionRequest,
  SearchQuery,
  SearchResultPage,
  VersionResponse,
} from './api-models';

@Injectable({ providedIn: 'root' })
export class CatalogApi {
  constructor(private readonly api: ApiClient) {}

  categories() {
    return this.api.get<CategoryTreeNode[]>('/categories');
  }
  category(id: string) {
    return this.api.get<CategoryResponse>(`/categories/${encodeURIComponent(id)}`);
  }
  categorySchema(id: string) {
    return this.api.get<EffectiveSchemaResponse>(`/categories/${encodeURIComponent(id)}/schema`);
  }
  createCategory(request: CreateCategoryRequest) {
    return this.api.post<CategoryResponse>('/categories', request);
  }
  defineAttribute(categoryId: string, request: DefineAttributeRequest) {
    return this.api.post<AttributeDefinitionResponse>(
      `/categories/${encodeURIComponent(categoryId)}/attributes`,
      request,
    );
  }
  retireAllowedValue(categoryId: string, attributeId: string, value: string) {
    return this.api.delete<AttributeDefinitionResponse>(
      `/categories/${encodeURIComponent(categoryId)}/attributes/${encodeURIComponent(attributeId)}/allowed-values/${encodeURIComponent(value)}`,
    );
  }
  search(query: SearchQuery = {}) {
    const { attributes, ...params } = query;
    return this.api.get<SearchResultPage>('/search', { params: { ...params, ...attributes } });
  }
  browseCategory(categoryId: string, query: CategoryBrowseQuery = {}) {
    const { attributes, ...params } = query;
    return this.api.get<SearchResultPage>(
      `/categories/${encodeURIComponent(categoryId)}/listings`,
      { params: { ...params, ...attributes } },
    );
  }
  listing(id: string) {
    return this.api.get<ListingDetailResponse>(`/listings/${encodeURIComponent(id)}`);
  }
  myListings(query: MyListingsQuery = {}) {
    return this.api.get<ListingCardPage>('/listings/mine', { params: { ...query } });
  }
  sellerListings(sub: string, query: CursorQuery = {}) {
    return this.api.get<ListingCardPage>(`/users/${encodeURIComponent(sub)}/listings`, {
      params: { ...query },
    });
  }
  publishHardware(request: PublishHardwareRequest) {
    return this.api.post<ListingCreatedResponse>('/listings/hardware', request);
  }
  publishSoftware(request: PublishSoftwareRequest) {
    return this.api.post<ListingCreatedResponse>('/listings/software', request);
  }
  changePrice(id: string, request: ChangePriceRequest) {
    return this.api.patch<ListingCreatedResponse>(
      `/listings/${encodeURIComponent(id)}/price`,
      request,
    );
  }
  editListing(id: string, request: EditListingRequest) {
    return this.api.put<EditListingResponse>(`/listings/${encodeURIComponent(id)}`, request);
  }
  deleteListing(id: string, reason: string | null = null) {
    return this.api.delete<void>(`/listings/${encodeURIComponent(id)}`, { params: { reason } });
  }
  photoUploadPolicy(id: string) {
    return this.api.post<PhotoUploadPolicyResponse>(
      `/listings/${encodeURIComponent(id)}/photos/upload-policy`,
    );
  }
  confirmPhoto(id: string, request: ConfirmPhotoRequest) {
    return this.api.post<string[]>(`/listings/${encodeURIComponent(id)}/photos/confirm`, request);
  }
  versions(id: string) {
    return this.api.get<VersionResponse[]>(`/listings/${encodeURIComponent(id)}/versions`);
  }
  releaseVersion(id: string, request: ReleaseVersionRequest) {
    return this.api.post<VersionResponse>(`/listings/${encodeURIComponent(id)}/versions`, request);
  }
  artifactUploadPolicy(id: string, number: string) {
    return this.api.post<ArtifactUploadPolicyResponse>(
      `/listings/${encodeURIComponent(id)}/versions/${encodeURIComponent(number)}/artifact-upload-policy`,
    );
  }
  confirmArtifact(id: string, number: string, request: ConfirmArtifactRequest) {
    return this.api.post<VersionResponse>(
      `/listings/${encodeURIComponent(id)}/versions/${encodeURIComponent(number)}/artifact/confirm`,
      request,
    );
  }
  artifactDownload(id: string, number: string) {
    return this.api.get<ArtifactDownloadResponse>(
      `/listings/${encodeURIComponent(id)}/versions/${encodeURIComponent(number)}/download`,
    );
  }
  addFavourite(listingId: string) {
    return this.api.put<FavouriteResponse>(`/favourites/${encodeURIComponent(listingId)}`);
  }
  removeFavourite(listingId: string) {
    return this.api.delete<void>(`/favourites/${encodeURIComponent(listingId)}`);
  }
  favourites(query: FavouriteQuery = {}) {
    return this.api.get<FavouritePage>('/favourites', { params: { ...query } });
  }
  rebuildSearch() {
    return this.api.post<RebuildStatusResponse>('/admin/search/rebuild');
  }
  searchRebuildStatus() {
    return this.api.get<RebuildStatusResponse>('/admin/search/rebuild');
  }
  checkSearchDrift() {
    return this.api.post<DriftReport>('/admin/search/drift-check');
  }
}
