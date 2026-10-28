export type Money = string;
export type Instant = string;
export type JsonValue =
  string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface Address {
  recipient: string;
  street: string;
  city: string;
  postalCode: string;
  country: string;
}

export interface ProblemBody {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
  currentTotal?: Money;
  conversationId?: string;
  [extension: string]: unknown;
}

export interface UploadPolicy {
  url: string;
  key: string;
  algorithm: string;
  credential: string;
  date: string;
  policy: string;
  signature: string;
  securityToken: string | null;
}
export type AvatarUploadPolicyResponse = UploadPolicy;
export type PhotoUploadPolicyResponse = UploadPolicy;
export type ArtifactUploadPolicyResponse = UploadPolicy;
export interface ChatImageUploadPolicyResponse extends UploadPolicy {
  contentType: string;
}
export interface ArtifactDownloadResponse {
  url: string;
  expiresAt: Instant;
}
export interface AvatarConfirmRequest {
  key: string;
}
export interface ConfirmPhotoRequest {
  key: string;
}
export interface ConfirmArtifactRequest {
  key: string;
}

export interface MeResponse {
  id: string;
  username: string;
  registration: Instant;
  location: string | null;
  bio: string | null;
  avatar: string | null;
}
export interface UpdateProfileRequest {
  username: string;
  location: string | null;
  bio: string | null;
}
export interface PublicProfileResponse {
  sub: string;
  username: string;
  avatar: string | null;
  registration: Instant | null;
  location: string | null;
  bio: string | null;
  isDeveloper: boolean;
  ratingAvg: number | null;
  ratingCount: number;
  completedSales: number;
  activeListings: string[];
  followerCount: number;
  followingCount: number;
  followedByMe: boolean;
}
export interface DeveloperProfileResponse {
  sub: string;
  activatedAt: Instant;
}
export interface UsernameAvailabilityResponse {
  available: boolean;
  reason: 'invalid-username' | 'reserved-handle' | 'username-taken' | null;
}

export type ListingType = 'HARDWARE' | 'SOFTWARE';
export type ListingStatus = 'ACTIVE' | 'SOLD' | 'REMOVED';
export type Condition = 'LIKE_NEW' | 'VERY_GOOD' | 'GOOD' | 'ACCEPTABLE';
export type Platform = 'WINDOWS' | 'MACOS' | 'LINUX' | 'WEB' | 'ANDROID' | 'IOS';
export type PaymentMethod = 'BANK_TRANSFER' | 'CREDIT_CARD' | 'PAYPAL';
export type AttributeType = 'ENUM' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN';
export interface CategoryTreeNode {
  id: string;
  name: string;
  leaf: boolean;
  children: CategoryTreeNode[];
}
export interface CategoryResponse {
  id: string;
  name: string;
  parentId: string | null;
  ancestorNames: string[];
  subcategoryIds: string[];
  leaf: boolean;
  createdAt: Instant;
}
export interface CreateCategoryRequest {
  name: string;
  parentId: string | null;
}
export interface DefineAttributeRequest {
  name: string;
  type: AttributeType;
  required: boolean;
  allowedValues: string[];
  unit: string | null;
}
export interface AttributeDefinitionResponse extends DefineAttributeRequest {
  id: string;
  definedAt: Instant;
}
export interface EffectiveSchemaResponse {
  categoryId: string;
  attributes: AttributeDefinitionResponse[];
}
export interface AttributeValueInput {
  attributeId: string;
  value: JsonValue;
}
export interface PublishHardwareRequest {
  title: string;
  description: string;
  price: Money;
  categoryId: string;
  photos: string[];
  paymentMethods: PaymentMethod[];
  condition: Condition;
  shippingLocation: string;
  shippingCost: Money;
  attributes: AttributeValueInput[];
}
export interface PublishSoftwareRequest {
  title: string;
  description: string;
  price: Money;
  categoryId: string;
  photos: string[];
  paymentMethods: PaymentMethod[];
  platforms: Platform[];
  versionNumber: string;
  notes: string | null;
  attributes: AttributeValueInput[];
  licenceKeyPattern: string | null;
}
export interface ChangePriceRequest {
  price: Money;
}
export interface EditListingRequest {
  title: string;
  description: string;
  photos: string[];
  attributes: AttributeValueInput[];
  categoryId: string | null;
  licenceKeyPattern: string | null;
}
export interface ListingCreatedResponse {
  id: string;
  title: string;
  price: Money;
  status: ListingStatus;
  publication: Instant;
}
export interface EditListingResponse {
  listing: ListingCreatedResponse;
  droppedAttributeIds: string[];
}
export interface ReleaseVersionRequest {
  number: string;
  notes: string | null;
}
export interface VersionResponse {
  number: string;
  release: Instant;
  notes: string | null;
  artifactAvailable: boolean;
  current: boolean;
}
export interface SoftwareListingView {
  platforms: Platform[];
  versions: VersionResponse[];
}
export interface ListingCategoryView {
  id: string;
  name: string;
  ancestors: string[];
}
export interface ListingAttributeView {
  name: string;
  type: AttributeType;
  value: JsonValue;
  unit: string | null;
}
export interface PriceChangeResponse {
  instant: Instant;
  oldPrice: Money;
  newPrice: Money;
  drop: boolean;
}
export interface SellerSummaryView {
  sub: string;
  username: string | null;
  avatar: string | null;
  ratingAvg: number | null;
  ratingCount: number;
}
export interface ModerationView {
  reason: string;
  instant: Instant;
}
export interface ListingDetailResponse {
  id: string;
  title: string;
  description: string;
  price: Money;
  status: ListingStatus;
  type: ListingType;
  publication: Instant;
  sellerSub: string;
  categoryId: string;
  photos: string[];
  photoKeys: string[];
  paymentMethods: PaymentMethod[];
  condition: Condition | null;
  shippingLocation: string | null;
  shippingCost: Money | null;
  software: SoftwareListingView | null;
  category: ListingCategoryView;
  priceHistory: PriceChangeResponse[];
  attributes: ListingAttributeView[];
  seller: SellerSummaryView;
  // The server uses NON_NULL here because ordinary listings have no moderation payload.
  moderation?: ModerationView;
}
export interface ListingCard {
  id: string;
  title: string;
  price: Money;
  type: ListingType;
  status: ListingStatus;
  coverUrl: string | null;
  condition: Condition | null;
  shippingLocation: string | null;
  platforms: Platform[] | null;
  currentVersion: string | null;
  publication: Instant;
}
export type ListingCardPage = CursorPage<ListingCard>;
export interface SearchHit {
  id: string;
  title: string;
  description: string;
  price: Money;
  listingType: ListingType;
  status: ListingStatus;
  categoryId: string;
  categoryPath: string[];
  sellerUsername: string | null;
  publishedAt: Instant;
  condition: Condition | null;
  shippingLocation: string | null;
  platforms: Platform[];
  currentVersion: string | null;
  coverUrl: string | null;
  attr: Record<string, JsonValue>;
}
export interface FacetDistribution {
  attribute: string;
  values: Record<string, number>;
}
export interface SearchResultPage {
  hits: SearchHit[];
  total: number;
  offset: number;
  size: number;
  facets: FacetDistribution[];
}
export type FavouriteListingStatus = 'AVAILABLE' | 'SOLD' | 'UNAVAILABLE';
export interface FavouriteResponse {
  listingId: string;
  sellerSub: string;
  createdAt: Instant;
}
export interface FavouriteEntry {
  listingId: string;
  title: string;
  price: Money | null;
  photos: string[];
  coverUrl: string | null;
  status: FavouriteListingStatus;
  createdAt: Instant;
}
export interface FavouritePage {
  favourites: FavouriteEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface RebuildStatusResponse {
  running: boolean;
  startedAt: Instant | null;
  completedAt: Instant | null;
  error: string | null;
}
export interface SequenceLag {
  sourceSequence: number;
  indexedSequence: number;
}
export interface DriftReport {
  missingFromIndex: string[];
  missingFromSource: string[];
  staleDocuments: Record<string, SequenceLag>;
}

export type OrderStatus =
  'AWAITING_PAYMENT' | 'PAID' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED' | 'REFUNDED';
export type OfferStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED' | 'WITHDRAWN';
export type RejectionReason = 'SELLER' | 'OTHER_ACCEPTED' | 'LISTING_UNAVAILABLE' | 'PRICE_CHANGED';
export type LicenseStatus = 'ACTIVE' | 'REVOKED';
export type Carrier =
  'POSTE_ITALIANE' | 'BRT' | 'GLS' | 'SDA' | 'DHL' | 'UPS' | 'FEDEX' | 'DPD' | 'OTHER';
export type ProblemReason =
  'NOT_RECEIVED' | 'NOT_AS_DESCRIBED' | 'DAMAGED' | 'LICENCE_NOT_WORKING' | 'OTHER';
export type ProblemStatus = 'OPEN' | 'REFUND_PENDING' | 'RESOLVED' | 'WITHDRAWN';
export type ProblemOutcome = 'REFUND' | 'DISMISS';
export interface CheckoutRequest {
  listingId: string;
  method: PaymentMethod;
  expectedTotal: Money;
  // The backend rejects an address on software and requires it on hardware, so the key is omitted rather than null.
  address?: Address | null;
  offerId?: string | null;
}
export interface CheckoutResponse {
  orderId: string;
  approvalUrl: string | null;
  status: OrderStatus;
}
export interface CaptureResponse {
  orderId: string;
  status: 'ACCEPTED_AWAITING_CONFIRMATION';
}
export interface ShipOrderRequest {
  carrier: Carrier;
  trackingCode: string;
}
export interface ReportProblemRequest {
  reason: ProblemReason;
  description: string;
}
export interface ResolveProblemRequest {
  note: string | null;
}
export interface DismissProblemRequest {
  note: string;
}
export interface CreateReviewRequest {
  rating: number;
  comment: string | null;
}
export interface RefundRequest {
  reason: string;
}
export interface RefundResponse {
  orderId: string;
  status: string;
}
export interface CreateOfferRequest {
  listingId: string;
  amount: Money;
}
export interface LicenseSummary {
  status: LicenseStatus;
  issuedAt: Instant;
  revokedAt: Instant | null;
  // Only the buyer receives the key; absence must retain the server's privacy distinction.
  key?: string;
}
export interface LicenseView {
  key: string;
  orderId: string;
  listingId: string;
  listingTitle: string;
  status: LicenseStatus;
  issuedAt: Instant;
  revokedAt: Instant | null;
}
export interface ProblemView {
  id: string;
  orderId: string;
  reason: ProblemReason;
  description: string;
  status: ProblemStatus;
  outcome: ProblemOutcome | null;
  resolutionNote: string | null;
  createdAt: Instant;
  resolvedAt: Instant | null;
}
export interface ReviewView {
  id: string;
  listingId: string;
  listingTitle: string;
  authorUsername: string | null;
  sellerSub: string;
  rating: number;
  comment: string | null;
  createdAt: Instant;
}
export interface OrderView {
  id: string;
  listingId: string;
  listingTitle: string;
  listingType: ListingType;
  buyerSub: string;
  sellerSub: string;
  itemPrice: Money;
  shippingCost: Money;
  pricePaid: Money;
  paymentMethod: PaymentMethod;
  status: OrderStatus;
  createdAt: Instant;
  expiryAt: Instant;
  // These properties use NON_NULL in OrderView, including role-filtered shippingAddress.
  shippingAddress?: Address;
  paidAt?: Instant;
  shippedAt?: Instant;
  deliveredAt?: Instant;
  carrier?: Carrier;
  trackingCode?: string;
  refundRequestedAt?: Instant;
  license?: LicenseSummary;
  problem?: ProblemView;
  review?: ReviewView;
}
export interface OfferView {
  id: string;
  listingId: string;
  listingTitle: string;
  buyerSub: string;
  sellerSub: string;
  amount: Money;
  askingPrice: Money;
  status: OfferStatus;
  rejectionReason: RejectionReason | null;
  expiresAt: Instant | null;
  orderId: string | null;
  createdAt: Instant;
}
export interface StuckOrderItem {
  orderId: string;
  category: 'failed_create' | 'captured_awaiting_webhook' | 'awaiting_timeout';
  createdAt: Instant;
  expiryAt: Instant;
  cancellationReason: string | null;
  lastPayPalStatus: string | null;
}
export interface AdminProblemItem extends ProblemView {
  resolvedBy: string | null;
}
export type OrderPage = CursorPage<OrderView>;
export type OfferPage = CursorPage<OfferView>;
export type LicensePage = CursorPage<LicenseView>;
export type ReviewPage = CursorPage<ReviewView>;
export type StuckOrderPage = CursorPage<StuckOrderItem>;
export type AdminProblemPage = CursorPage<AdminProblemItem>;

export type MessageKind = 'CHAT' | 'OFFER';
export type OfferEvent = 'CREATED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED';
export type NotificationType =
  | 'MESSAGE'
  | 'FAVOURITE'
  | 'PRICE_DROP'
  | 'RELEASE'
  | 'REVIEW'
  | 'FOLLOW'
  | 'OFFER_RECEIVED'
  | 'OFFER_ACCEPTED'
  | 'ORDER';
export interface StartConversationRequest {
  listingId: string;
  text: string;
  imageKeys: string[];
}
export interface SendMessageRequest {
  text: string | null;
  imageKeys: string[];
}
export interface ChatImageUploadPolicyRequest {
  contentType: string;
}
export interface LastMessageView {
  messageId: string;
  senderSub: string;
  kind: MessageKind;
  preview: string | null;
  imageCount: number;
  at: Instant;
}
export interface ConversationView {
  id: string;
  listingId: string;
  listingType: string;
  listingTitle: string;
  listingPrice: Money;
  buyerSub: string;
  sellerSub: string;
  otherParticipantSub: string;
  otherParticipantUsername: string | null;
  lastMessage: LastMessageView | null;
  lastMessageAt: Instant | null;
  unreadFor: number;
  partnerOnline: boolean;
  createdAt: Instant;
}
export interface MessageOffer {
  offerId: string;
  event: OfferEvent;
  status: string;
  amount: Money;
  askingPrice: Money;
  expiresAt: Instant | null;
  rejectionReason: string | null;
}
export interface ImageView {
  url: string;
  expiresAt: Instant;
}
export interface MessageView {
  id: string;
  conversationId: string;
  seq: number;
  senderSub: string;
  kind: MessageKind;
  text: string | null;
  offer: MessageOffer | null;
  images: ImageView[];
  createdAt: Instant;
}
export interface NotificationView {
  id: string;
  type: NotificationType;
  createdAt: Instant;
  read: boolean;
  readAt: Instant | null;
  target: Record<string, string>;
  snapshot: Record<string, string>;
}
export interface UnreadSummary {
  notifications: number;
  messages: number;
}
export interface ReadAllResult {
  updated: number;
}
export interface EmailPreferenceView {
  emailEnabled: boolean;
}
export interface UpdateEmailPreferenceRequest {
  emailEnabled: boolean;
}
export type ConversationPage = CursorPage<ConversationView>;
export type MessagePage = CursorPage<MessageView>;
export type NotificationPage = CursorPage<NotificationView>;

export interface CursorQuery {
  cursor?: string | null;
  size?: number | null;
}
export interface ReviewQuery extends CursorQuery {
  listingId?: string | null;
}
export interface MyListingsQuery extends CursorQuery {
  status?: ListingStatus | null;
}
export type AttributeSearchParams = Partial<
  Record<`attr.${string}`, string | number | boolean | null>
>;
export type SearchSort = 'newest' | 'oldest';
export interface CategoryBrowseQuery {
  offset?: number | null;
  size?: number | null;
  sort?: SearchSort | null;
  attributes?: AttributeSearchParams;
}
export interface SearchQuery extends CategoryBrowseQuery {
  q?: string | null;
  categoryId?: string | null;
  listingType?: ListingType | null;
  minPrice?: Money | null;
  maxPrice?: Money | null;
}
export interface FavouriteQuery {
  page?: number | null;
  size?: number | null;
}
export interface OfferQuery extends CursorQuery {
  listingId?: string | null;
}
export interface AdminProblemQuery extends CursorQuery {
  status?: 'OPEN' | 'REFUND_PENDING' | null;
}
export interface NotificationQuery extends CursorQuery {
  unread?: boolean | null;
}
