import { HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { provideTestAppConfig } from '../../../testing/test-config';
import { CatalogApi } from './catalog-api';
import { MessagingApi } from './messaging-api';
import { OrderApi } from './order-api';
import { UserApi } from './user-api';
import { ApiClient } from './api-client';
import {
  CheckoutRequest,
  PublishHardwareRequest,
  PublishSoftwareRequest,
  UploadPolicy,
} from './api-models';

type Clients = { user: UserApi; catalog: CatalogApi; order: OrderApi; messaging: MessagingApi };
type Contract = {
  name: string;
  call: (clients: Clients) => Observable<unknown>;
  method: string;
  path: string;
  body?: unknown;
  params?: Record<string, string>;
};
const hardware: PublishHardwareRequest = {
  title: 'GPU',
  description: 'Used GPU',
  price: '100.00',
  categoryId: 'gpu',
  photos: [],
  paymentMethods: ['PAYPAL'],
  condition: 'GOOD',
  shippingLocation: 'Roma',
  shippingCost: '5.00',
  attributes: [{ attributeId: 'vram', value: 8 }],
};
const software: PublishSoftwareRequest = {
  title: 'Editor',
  description: 'Software',
  price: '20.00',
  categoryId: 'apps',
  photos: [],
  paymentMethods: ['CREDIT_CARD'],
  platforms: ['LINUX'],
  versionNumber: '1.0',
  notes: null,
  attributes: [],
  licenceKeyPattern: null,
};
const checkout: CheckoutRequest = {
  listingId: 'listing',
  method: 'PAYPAL',
  expectedTotal: '105.00',
  address: {
    recipient: 'Buyer',
    street: 'Via Roma 1',
    city: 'Roma',
    postalCode: '00100',
    country: 'IT',
  },
  offerId: null,
};
const contracts: Contract[] = [
  { name: 'current user', call: (c) => c.user.getMe(), method: 'GET', path: '/users/me' },
  {
    name: 'replace profile',
    call: (c) => c.user.updateProfile({ username: 'buyer', location: null, bio: null }),
    method: 'PUT',
    path: '/users/me',
    body: { username: 'buyer', location: null, bio: null },
  },
  {
    name: 'activate developer',
    call: (c) => c.user.activateDeveloperProfile(),
    method: 'POST',
    path: '/users/me/developer-profile',
  },
  {
    name: 'public profile',
    call: (c) => c.user.getPublicProfile('seller'),
    method: 'GET',
    path: '/users/seller',
  },
  {
    name: 'username availability uses u',
    call: (c) => c.user.usernameAvailable('chosen_name'),
    method: 'GET',
    path: '/users/username-available',
    params: { u: 'chosen_name' },
  },
  {
    name: 'avatar policy',
    call: (c) => c.user.avatarUploadPolicy(),
    method: 'POST',
    path: '/users/me/avatar/upload-policy',
  },
  {
    name: 'confirm avatar',
    call: (c) => c.user.confirmAvatar({ key: 'avatars/me/photo' }),
    method: 'POST',
    path: '/users/me/avatar/confirm',
    body: { key: 'avatars/me/photo' },
  },
  {
    name: 'remove avatar',
    call: (c) => c.user.deleteAvatar(),
    method: 'DELETE',
    path: '/users/me/avatar',
  },
  {
    name: 'follow seller',
    call: (c) => c.user.follow('seller'),
    method: 'PUT',
    path: '/users/seller/follow',
  },
  {
    name: 'unfollow seller',
    call: (c) => c.user.unfollow('seller'),
    method: 'DELETE',
    path: '/users/seller/follow',
  },
  {
    name: 'category tree',
    call: (c) => c.catalog.categories(),
    method: 'GET',
    path: '/categories',
  },
  {
    name: 'category detail',
    call: (c) => c.catalog.category('gpu'),
    method: 'GET',
    path: '/categories/gpu',
  },
  {
    name: 'effective schema',
    call: (c) => c.catalog.categorySchema('gpu'),
    method: 'GET',
    path: '/categories/gpu/schema',
  },
  {
    name: 'create category',
    call: (c) => c.catalog.createCategory({ name: 'Hardware', parentId: null }),
    method: 'POST',
    path: '/categories',
    body: { name: 'Hardware', parentId: null },
  },
  {
    name: 'define attribute',
    call: (c) =>
      c.catalog.defineAttribute('gpu', {
        name: 'vram',
        type: 'INTEGER',
        required: true,
        allowedValues: [],
        unit: 'GB',
      }),
    method: 'POST',
    path: '/categories/gpu/attributes',
    body: { name: 'vram', type: 'INTEGER', required: true, allowedValues: [], unit: 'GB' },
  },
  {
    name: 'retire encoded allowed value',
    call: (c) => c.catalog.retireAllowedValue('gpu', 'model', 'RTX 4090/24GB'),
    method: 'DELETE',
    path: '/categories/gpu/attributes/model/allowed-values/RTX%204090%2F24GB',
  },
  {
    name: 'search exact query names',
    call: (c) =>
      c.catalog.search({
        q: 'GPU',
        categoryId: 'gpu',
        listingType: 'HARDWARE',
        minPrice: '10.00',
        maxPrice: null,
        offset: 24,
        size: 24,
        sort: 'newest',
        attributes: { 'attr.vram_gb.min': '8', 'attr.brand': 'AMD' },
      }),
    method: 'GET',
    path: '/search',
    params: {
      q: 'GPU',
      categoryId: 'gpu',
      listingType: 'HARDWARE',
      minPrice: '10.00',
      offset: '24',
      size: '24',
      sort: 'newest',
      'attr.vram_gb.min': '8',
      'attr.brand': 'AMD',
    },
  },
  {
    name: 'category browse',
    call: (c) =>
      c.catalog.browseCategory('gpu', {
        offset: 0,
        size: 24,
        sort: 'oldest',
        attributes: { 'attr.vram_gb.max': '16' },
      }),
    method: 'GET',
    path: '/categories/gpu/listings',
    params: { offset: '0', size: '24', sort: 'oldest', 'attr.vram_gb.max': '16' },
  },
  {
    name: 'listing detail',
    call: (c) => c.catalog.listing('listing'),
    method: 'GET',
    path: '/listings/listing',
  },
  {
    name: 'my listings filter and terminal cursor',
    call: (c) => c.catalog.myListings({ cursor: null, size: 20, status: 'REMOVED' }),
    method: 'GET',
    path: '/listings/mine',
    params: { size: '20', status: 'REMOVED' },
  },
  {
    name: 'public seller listings',
    call: (c) => c.catalog.sellerListings('seller', { cursor: 'next+/=', size: 20 }),
    method: 'GET',
    path: '/users/seller/listings',
    params: { cursor: 'next+/=', size: '20' },
  },
  {
    name: 'publish hardware',
    call: (c) => c.catalog.publishHardware(hardware),
    method: 'POST',
    path: '/listings/hardware',
    body: hardware,
  },
  {
    name: 'publish software',
    call: (c) => c.catalog.publishSoftware(software),
    method: 'POST',
    path: '/listings/software',
    body: software,
  },
  {
    name: 'change price',
    call: (c) => c.catalog.changePrice('listing', { price: '98.10' }),
    method: 'PATCH',
    path: '/listings/listing/price',
    body: { price: '98.10' },
  },
  {
    name: 'edit sends keys',
    call: (c) =>
      c.catalog.editListing('listing', {
        title: 'GPU',
        description: 'Used',
        photos: ['listings/listing/photo'],
        attributes: [],
        categoryId: null,
        licenceKeyPattern: null,
      }),
    method: 'PUT',
    path: '/listings/listing',
    body: {
      title: 'GPU',
      description: 'Used',
      photos: ['listings/listing/photo'],
      attributes: [],
      categoryId: null,
      licenceKeyPattern: null,
    },
  },
  {
    name: 'owner removes listing',
    call: (c) => c.catalog.deleteListing('listing'),
    method: 'DELETE',
    path: '/listings/listing',
  },
  {
    name: 'admin removal reason',
    call: (c) => c.catalog.deleteListing('listing', 'Policy violation'),
    method: 'DELETE',
    path: '/listings/listing',
    params: { reason: 'Policy violation' },
  },
  {
    name: 'photo policy',
    call: (c) => c.catalog.photoUploadPolicy('listing'),
    method: 'POST',
    path: '/listings/listing/photos/upload-policy',
  },
  {
    name: 'confirm photo key',
    call: (c) => c.catalog.confirmPhoto('listing', { key: 'listings/listing/photo' }),
    method: 'POST',
    path: '/listings/listing/photos/confirm',
    body: { key: 'listings/listing/photo' },
  },
  {
    name: 'versions',
    call: (c) => c.catalog.versions('listing'),
    method: 'GET',
    path: '/listings/listing/versions',
  },
  {
    name: 'release version',
    call: (c) => c.catalog.releaseVersion('listing', { number: '2.0', notes: null }),
    method: 'POST',
    path: '/listings/listing/versions',
    body: { number: '2.0', notes: null },
  },
  {
    name: 'artifact policy',
    call: (c) => c.catalog.artifactUploadPolicy('listing', '2.0'),
    method: 'POST',
    path: '/listings/listing/versions/2.0/artifact-upload-policy',
  },
  {
    name: 'confirm artifact',
    call: (c) => c.catalog.confirmArtifact('listing', '2.0', { key: 'artifact' }),
    method: 'POST',
    path: '/listings/listing/versions/2.0/artifact/confirm',
    body: { key: 'artifact' },
  },
  {
    name: 'artifact download policy',
    call: (c) => c.catalog.artifactDownload('listing', '2.0'),
    method: 'GET',
    path: '/listings/listing/versions/2.0/download',
  },
  {
    name: 'favourite listing',
    call: (c) => c.catalog.addFavourite('listing'),
    method: 'PUT',
    path: '/favourites/listing',
  },
  {
    name: 'unfavourite listing',
    call: (c) => c.catalog.removeFavourite('listing'),
    method: 'DELETE',
    path: '/favourites/listing',
  },
  {
    name: 'favourites retain page pagination',
    call: (c) => c.catalog.favourites({ page: 0, size: 20 }),
    method: 'GET',
    path: '/favourites',
    params: { page: '0', size: '20' },
  },
  {
    name: 'start rebuild',
    call: (c) => c.catalog.rebuildSearch(),
    method: 'POST',
    path: '/admin/search/rebuild',
  },
  {
    name: 'rebuild status',
    call: (c) => c.catalog.searchRebuildStatus(),
    method: 'GET',
    path: '/admin/search/rebuild',
  },
  {
    name: 'drift check',
    call: (c) => c.catalog.checkSearchDrift(),
    method: 'POST',
    path: '/admin/search/drift-check',
  },
  { name: 'read order', call: (c) => c.order.order('order'), method: 'GET', path: '/orders/order' },
  {
    name: 'purchases omit null cursor',
    call: (c) => c.order.purchases({ cursor: null, size: 20 }),
    method: 'GET',
    path: '/orders/me/purchases',
    params: { size: '20' },
  },
  {
    name: 'sales cursor',
    call: (c) => c.order.sales({ cursor: 'opaque', size: 20 }),
    method: 'GET',
    path: '/orders/me/sales',
    params: { cursor: 'opaque', size: '20' },
  },
  {
    name: 'cancel order',
    call: (c) => c.order.cancelOrder('order'),
    method: 'POST',
    path: '/orders/order/cancel',
  },
  {
    name: 'payment capture',
    call: (c) => c.order.capturePayment('order'),
    method: 'POST',
    path: '/payments/order/capture',
  },
  {
    name: 'ship order',
    call: (c) => c.order.shipOrder('order', { carrier: 'GLS', trackingCode: 'ABC-123' }),
    method: 'POST',
    path: '/orders/order/ship',
    body: { carrier: 'GLS', trackingCode: 'ABC-123' },
  },
  {
    name: 'deliver order',
    call: (c) => c.order.deliverOrder('order'),
    method: 'POST',
    path: '/orders/order/deliver',
  },
  {
    name: 'report problem',
    call: (c) =>
      c.order.reportProblem('order', { reason: 'DAMAGED', description: 'Arrived damaged' }),
    method: 'POST',
    path: '/orders/order/problem',
    body: { reason: 'DAMAGED', description: 'Arrived damaged' },
  },
  {
    name: 'withdraw problem',
    call: (c) => c.order.withdrawProblem('order'),
    method: 'POST',
    path: '/orders/order/problem/withdraw',
  },
  {
    name: 'seller problem refund optional body',
    call: (c) => c.order.refundProblem('order'),
    method: 'POST',
    path: '/orders/order/problem/refund',
  },
  {
    name: 'seller problem refund note',
    call: (c) => c.order.refundProblem('order', { note: 'Agreed' }),
    method: 'POST',
    path: '/orders/order/problem/refund',
    body: { note: 'Agreed' },
  },
  {
    name: 'review',
    call: (c) => c.order.createReview('order', { rating: 5, comment: null }),
    method: 'POST',
    path: '/orders/order/review',
    body: { rating: 5, comment: null },
  },
  {
    name: 'my licenses',
    call: (c) => c.order.licenses({ cursor: null, size: 20 }),
    method: 'GET',
    path: '/licences/me',
    params: { size: '20' },
  },
  {
    name: 'single review',
    call: (c) => c.order.review('review'),
    method: 'GET',
    path: '/reviews/review',
  },
  {
    name: 'public seller reviews',
    call: (c) => c.order.sellerReviews('seller', { cursor: null, size: 20 }),
    method: 'GET',
    path: '/users/seller/reviews',
    params: { size: '20' },
  },
  {
    name: 'listing reviews retain the filter while paging',
    call: (c) =>
      c.order.sellerReviews('seller', { listingId: 'listing/id', cursor: 'next', size: 5 }),
    method: 'GET',
    path: '/users/seller/reviews',
    params: { listingId: 'listing/id', cursor: 'next', size: '5' },
  },
  {
    name: 'offer creation has no note',
    call: (c) => c.order.createOffer({ listingId: 'listing', amount: '80.00' }),
    method: 'POST',
    path: '/offers',
    body: { listingId: 'listing', amount: '80.00' },
  },
  {
    name: 'offer detail',
    call: (c) => c.order.offer('offer'),
    method: 'GET',
    path: '/offers/offer',
  },
  {
    name: 'sent offers',
    call: (c) => c.order.sentOffers({ listingId: 'listing', cursor: null, size: 20 }),
    method: 'GET',
    path: '/offers/me/sent',
    params: { listingId: 'listing', size: '20' },
  },
  {
    name: 'received offers',
    call: (c) => c.order.receivedOffers({ cursor: 'next', size: 20 }),
    method: 'GET',
    path: '/offers/me/received',
    params: { cursor: 'next', size: '20' },
  },
  {
    name: 'accept offer',
    call: (c) => c.order.acceptOffer('offer'),
    method: 'POST',
    path: '/offers/offer/accept',
  },
  {
    name: 'reject offer',
    call: (c) => c.order.rejectOffer('offer'),
    method: 'POST',
    path: '/offers/offer/reject',
  },
  {
    name: 'withdraw offer',
    call: (c) => c.order.withdrawOffer('offer'),
    method: 'POST',
    path: '/offers/offer/withdraw',
  },
  {
    name: 'admin refund',
    call: (c) => c.order.adminRefundOrder('order', { reason: 'Support decision' }),
    method: 'POST',
    path: '/admin/orders/order/refund',
    body: { reason: 'Support decision' },
  },
  {
    name: 'stuck orders',
    call: (c) => c.order.stuckOrders({ cursor: null, size: 20 }),
    method: 'GET',
    path: '/admin/orders/stuck',
    params: { size: '20' },
  },
  {
    name: 'admin problem queue',
    call: (c) => c.order.adminProblems({ status: 'OPEN', cursor: null, size: 20 }),
    method: 'GET',
    path: '/admin/problems',
    params: { status: 'OPEN', size: '20' },
  },
  {
    name: 'admin problem refund',
    call: (c) => c.order.adminRefundProblem('problem'),
    method: 'POST',
    path: '/admin/problems/problem/refund',
  },
  {
    name: 'dismiss problem',
    call: (c) => c.order.adminDismissProblem('problem', { note: 'Resolved' }),
    method: 'POST',
    path: '/admin/problems/problem/dismiss',
    body: { note: 'Resolved' },
  },
  {
    name: 'conversations',
    call: (c) => c.messaging.conversations({ cursor: null, size: 20 }),
    method: 'GET',
    path: '/conversations',
    params: { size: '20' },
  },
  {
    name: 'start conversation',
    call: (c) =>
      c.messaging.startConversation({ listingId: 'listing', text: 'Hello', imageKeys: [] }),
    method: 'POST',
    path: '/conversations',
    body: { listingId: 'listing', text: 'Hello', imageKeys: [] },
  },
  {
    name: 'conversation detail',
    call: (c) => c.messaging.conversation('conversation'),
    method: 'GET',
    path: '/conversations/conversation',
  },
  {
    name: 'send image-only message',
    call: (c) =>
      c.messaging.sendMessage('conversation', { text: null, imageKeys: ['chat-images/me/photo'] }),
    method: 'POST',
    path: '/conversations/conversation/messages',
    body: { text: null, imageKeys: ['chat-images/me/photo'] },
  },
  {
    name: 'newest messages omit null cursor',
    call: (c) => c.messaging.messages('conversation', { cursor: null, size: 20 }),
    method: 'GET',
    path: '/conversations/conversation/messages',
    params: { size: '20' },
  },
  {
    name: 'older messages keep cursor',
    call: (c) => c.messaging.messages('conversation', { cursor: '10', size: 20 }),
    method: 'GET',
    path: '/conversations/conversation/messages',
    params: { cursor: '10', size: '20' },
  },
  {
    name: 'chat image policy',
    call: (c) => c.messaging.imageUploadPolicy({ contentType: 'image/webp' }),
    method: 'POST',
    path: '/messages/image-upload-policy',
    body: { contentType: 'image/webp' },
  },
  {
    name: 'notifications unread filter',
    call: (c) => c.messaging.notifications({ unread: false, cursor: null, size: 20 }),
    method: 'GET',
    path: '/notifications',
    params: { unread: 'false', size: '20' },
  },
  {
    name: 'unread summary',
    call: (c) => c.messaging.unreadSummary(),
    method: 'GET',
    path: '/notifications/unread-summary',
  },
  {
    name: 'mark notification read',
    call: (c) => c.messaging.readNotification('notification'),
    method: 'POST',
    path: '/notifications/notification/read',
  },
  {
    name: 'mark all notifications read',
    call: (c) => c.messaging.readAllNotifications(),
    method: 'POST',
    path: '/notifications/read-all',
  },
  {
    name: 'delete read notification',
    call: (c) => c.messaging.deleteNotification('notification'),
    method: 'DELETE',
    path: '/notifications/notification',
  },
  {
    name: 'delete all read notifications',
    call: (c) => c.messaging.deleteReadNotifications(),
    method: 'DELETE',
    path: '/notifications',
    params: { read: 'true' },
  },
  {
    name: 'email preferences',
    call: (c) => c.messaging.emailPreferences(),
    method: 'GET',
    path: '/notifications/email-preferences',
  },
  {
    name: 'update email preferences',
    call: (c) => c.messaging.updateEmailPreferences({ emailEnabled: false }),
    method: 'PUT',
    path: '/notifications/email-preferences',
    body: { emailEnabled: false },
  },
];

describe('domain API contracts', () => {
  let clients: Clients;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideTestAppConfig(), provideHttpClient(), provideHttpClientTesting()],
    });
    clients = {
      user: TestBed.inject(UserApi),
      catalog: TestBed.inject(CatalogApi),
      order: TestBed.inject(OrderApi),
      messaging: TestBed.inject(MessagingApi),
    };
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it.each(contracts)('$name uses the controller contract', (contract) => {
    contract.call(clients).subscribe();
    const request = http.expectOne((r) => r.url === `http://api.test${contract.path}`);
    expect(request.request.method).toBe(contract.method);
    expect(request.request.body).toEqual(contract.body ?? null);
    expect(
      Object.fromEntries(
        request.request.params.keys().map((key) => [key, request.request.params.get(key)]),
      ),
    ).toEqual(contract.params ?? {});
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    request.flush(null);
  });

  it('checkout sends exactly one Idempotency-Key and retains decimal strings and the five address fields', () => {
    clients.order.checkout(checkout, 'attempt-123').subscribe();
    const request = http.expectOne('http://api.test/orders');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.getAll('Idempotency-Key')).toEqual(['attempt-123']);
    expect(request.request.body).toEqual(checkout);
    request.flush({
      orderId: 'order',
      approvalUrl: 'https://sandbox.paypal.com/checkout',
      status: 'AWAITING_PAYMENT',
    });
  });

  it('software checkout explicitly sends null address and an accepted offer id', () => {
    clients.order
      .checkout({ ...checkout, address: null, offerId: 'offer' }, 'attempt-456')
      .subscribe();
    const request = http.expectOne('http://api.test/orders');
    expect(request.request.body.address).toBeNull();
    expect(request.request.body.offerId).toBe('offer');
    request.flush({ orderId: 'order', approvalUrl: null, status: 'CANCELLED' });
  });

  it('public profiles preserve counts, anonymous follow state and explicit nulls', () => {
    const result = vi.fn();
    const profile = {
      sub: 'seller',
      username: 'seller',
      avatar: null,
      registration: null,
      location: null,
      bio: null,
      isDeveloper: false,
      ratingAvg: null,
      ratingCount: 0,
      completedSales: 0,
      activeListings: [],
      followerCount: 4,
      followingCount: 2,
      followedByMe: false,
    };
    clients.user.getPublicProfile('seller').subscribe(result);
    http.expectOne('http://api.test/users/seller').flush(profile);
    expect(result).toHaveBeenCalledWith(profile);
  });

  it('listing cards preserve coverUrl and nullable type-specific fields', () => {
    const result = vi.fn();
    const page = {
      items: [
        {
          id: 'listing',
          title: 'GPU',
          price: '100.00',
          type: 'HARDWARE',
          status: 'ACTIVE',
          coverUrl: 'https://cdn.test/photo',
          condition: 'GOOD',
          shippingLocation: 'Roma',
          platforms: null,
          currentVersion: null,
          publication: '2026-10-08T00:00:00Z',
        },
      ],
      nextCursor: null,
    };
    clients.catalog.myListings().subscribe(result);
    http.expectOne('http://api.test/listings/mine').flush(page);
    expect(result).toHaveBeenCalledWith(page);
  });

  it('seller reviews preserve the privacy-safe DTO without inventing authorSub', () => {
    const result = vi.fn();
    const page = {
      items: [
        {
          id: 'review',
          listingId: 'listing',
          listingTitle: 'GPU',
          authorUsername: null,
          sellerSub: 'seller',
          rating: 5,
          comment: null,
          createdAt: '2026-10-08T00:00:00Z',
        },
      ],
      nextCursor: null,
    };
    clients.order.sellerReviews('seller').subscribe(result);
    http.expectOne('http://api.test/users/seller/reviews').flush(page);
    expect(result).toHaveBeenCalledWith(page);
    expect(result.mock.calls[0][0].items[0]).not.toHaveProperty('authorSub');
  });

  it('returns upload policies unchanged and refuses to post their S3 target through ApiClient', () => {
    const policy: UploadPolicy = {
      url: 'https://s3.test/bucket',
      key: 'avatars/me/photo',
      algorithm: 'AWS4-HMAC-SHA256',
      credential: 'test',
      date: '20261008T000000Z',
      policy: 'test-policy',
      signature: 'test-signature',
      securityToken: null,
    };
    const result = vi.fn();
    clients.user.avatarUploadPolicy().subscribe(result);
    http.expectOne('http://api.test/users/me/avatar/upload-policy').flush(policy);
    expect(result).toHaveBeenCalledWith(policy);
    expect(() => TestBed.inject(ApiClient).post(policy.url, policy)).toThrow();
    http.expectNone((r) => r.url.startsWith('https://s3.test'));
  });

  it.each(['order', 'messaging'] as const)(
    '%s dead-letter replay preserves HTTP status and X-Audit-Id',
    (domain) => {
      const result = vi.fn();
      clients[domain].replayDeadLetter('notify.test.dlq').subscribe(result);
      const path =
        domain === 'order'
          ? '/admin/orders/dead-letters/notify.test.dlq/replay'
          : '/admin/messaging/dead-letters/notify.test.dlq/replay';
      const request = http.expectOne(`http://api.test${path}`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toBeNull();
      request.flush(null, {
        status: 200,
        statusText: 'OK',
        headers: { 'X-Audit-Id': 'audit-123' },
      });
      const response = result.mock.calls[0][0] as HttpResponse<void>;
      expect(response.status).toBe(200);
      expect(response.headers.get('X-Audit-Id')).toBe('audit-123');
    },
  );

  it('messaging replay preserves an empty-queue 204', () => {
    const result = vi.fn();
    clients.messaging.replayDeadLetter('notify.test.dlq').subscribe(result);
    http
      .expectOne('http://api.test/admin/messaging/dead-letters/notify.test.dlq/replay')
      .flush(null, { status: 204, statusText: 'No Content' });
    expect(result.mock.calls[0][0].status).toBe(204);
    expect(result.mock.calls[0][0].headers.has('X-Audit-Id')).toBe(false);
  });
  it.each(['get', 'post'] as const)(
    'unsubscribe %s preserves the server HTML response',
    (method) => {
      const result = vi.fn();
      const call =
        method === 'get'
          ? clients.messaging.unsubscribeConfirmation('signed+/=')
          : clients.messaging.unsubscribe('signed+/=');
      call.subscribe(result);
      const request = http.expectOne(
        (r) => r.url === 'http://api.test/notifications/email-preferences/unsubscribe',
      );
      expect(request.request.method).toBe(method.toUpperCase());
      expect(request.request.params.get('token')).toBe('signed+/=');
      expect(request.request.responseType).toBe('text');
      expect(request.request.body).toBeNull();
      request.flush('<html>Confirmation</html>');
      expect(result).toHaveBeenCalledWith('<html>Confirmation</html>');
    },
  );
});
