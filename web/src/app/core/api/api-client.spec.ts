import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AppConfigService } from '../config/app-config';
import { provideTestAppConfig, TEST_APP_CONFIG } from '../../../testing/test-config';
import { ApiClient } from './api-client';
import { Address, CategoryTreeNode, CursorPage, ProblemBody, SearchResultPage } from './api-models';

function setup(base = 'http://gateway.test') {
  TestBed.configureTestingModule({
    providers: [
      provideTestAppConfig(),
      { provide: AppConfigService, useValue: { config: { ...TEST_APP_CONFIG, apiBaseUrl: base } } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return { api: TestBed.inject(ApiClient), http: TestBed.inject(HttpTestingController) };
}

describe('ApiClient', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it.each(['http://gateway.test', 'http://gateway.test/'])(
    'gets anonymous categories from %s exactly once',
    (base) => {
      const { api, http } = setup(base);
      const categories: CategoryTreeNode[] = [
        { id: 'hardware', name: 'Hardware', leaf: true, children: [] },
      ];
      const result = vi.fn();
      api.get<CategoryTreeNode[]>('/categories').subscribe(result);
      const request = http.expectOne('http://gateway.test/categories');
      expect(request.request.method).toBe('GET');
      expect(request.request.body).toBeNull();
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush(categories);
      expect(result).toHaveBeenCalledWith(categories);
    },
  );

  it('preserves a runtime base path and accepts a path without a leading slash', () => {
    const { api, http } = setup('http://gateway.test/api/');
    api.get('categories').subscribe();
    http.expectOne('http://gateway.test/api/categories').flush([]);
  });

  it.each([
    'https://s3.test/upload',
    'http://gateway.test/categories',
    '//s3.test/upload',
    'data:text/plain,hello',
    '\\s3.test\\upload',
    '/\\s3.test/upload',
    '../upload',
    '/%2e%2e/upload',
    '/categories\n',
    '/categories#fragment',
  ])('refuses unsafe path %s before issuing HTTP', (path) => {
    const { api, http } = setup();
    expect(() => api.get(path)).toThrow();
    http.expectNone(() => true);
  });

  it.each(['post', 'put', 'patch'] as const)('sends a typed %s body without coercion', (method) => {
    const { api, http } = setup();
    const body = { price: '12.30', shippingCost: '0.00', bio: null };
    api[method]('/resource', body).subscribe();
    const request = http.expectOne('http://gateway.test/resource');
    expect(request.request.method).toBe(method.toUpperCase());
    expect(request.request.body).toEqual(body);
    request.flush(body);
  });

  it('deletes without a fabricated body', () => {
    const { api, http } = setup();
    api.delete<void>('/resource').subscribe();
    const request = http.expectOne('http://gateway.test/resource');
    expect(request.request.method).toBe('DELETE');
    expect(request.request.body).toBeNull();
    request.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('retains explicit null cursors and the separate search page shape', () => {
    const { api, http } = setup();
    const cursor: CursorPage<string> = { items: ['one'], nextCursor: null };
    const search: SearchResultPage = { hits: [], total: 0, offset: 0, size: 24, facets: [] };
    const result = vi.fn();
    api.get<CursorPage<string>>('/cursor').subscribe(result);
    http.expectOne('http://gateway.test/cursor').flush(cursor);
    expect(result).toHaveBeenLastCalledWith(cursor);
    api.get<SearchResultPage>('/search').subscribe(result);
    http.expectOne('http://gateway.test/search').flush(search);
    expect(result).toHaveBeenLastCalledWith(search);
  });

  it('preserves problem field errors and extension properties', () => {
    const { api, http } = setup();
    const problem: ProblemBody = {
      type: 'urn:rebyte:problem:validation-failed',
      title: 'validation-failed',
      status: 400,
      detail: 'Validation failed',
      instance: '/resource',
      errors: { username: 'required' },
      conversationId: 'conversation',
      currentTotal: '12.30',
      extra: { retry: false },
    };
    const error = vi.fn();
    api.post('/resource', {}).subscribe({ error });
    http
      .expectOne('http://gateway.test/resource')
      .flush(problem, { status: 400, statusText: 'Bad Request' });
    expect(error.mock.calls[0][0]).toBeInstanceOf(HttpErrorResponse);
    expect(error.mock.calls[0][0].error).toEqual(problem);
  });

  it('models all five address fields', () => {
    setup();
    const address: Address = {
      recipient: 'Buyer',
      street: 'Via Roma 1',
      city: 'Roma',
      postalCode: '00100',
      country: 'IT',
    };
    expect(Object.keys(address)).toEqual(['recipient', 'street', 'city', 'postalCode', 'country']);
  });
  it('decodes numeric money lexemes losslessly while preserving attributes, counts, nulls and existing strings', () => {
    const { api, http } = setup();
    const result = vi.fn();
    api.get('/listings/listing').subscribe(result);
    http
      .expectOne('http://gateway.test/listings/listing')
      .flush(
        '{"price":123456789012345.67,"shippingCost":0.00,"itemPrice":"12.30","pricePaid":100.10,"amount":80.00,"askingPrice":90.00,"listingPrice":20.00,"currentTotal":105.00,"priceHistory":[{"oldPrice":1.20,"newPrice":1.10}],"rating":5,"ratingCount":3,"attr":{"price":1.25},"attributes":[{"name":"price","value":{"amount":7.50}}],"nullable":{"price":null}}',
      );
    expect(result).toHaveBeenCalledWith({
      price: '123456789012345.67',
      shippingCost: '0.00',
      itemPrice: '12.30',
      pricePaid: '100.10',
      amount: '80.00',
      askingPrice: '90.00',
      listingPrice: '20.00',
      currentTotal: '105.00',
      priceHistory: [{ oldPrice: '1.20', newPrice: '1.10' }],
      rating: 5,
      ratingCount: 3,
      attr: { price: 1.25 },
      attributes: [{ name: 'price', value: { amount: 7.5 } }],
      nullable: { price: null },
    });
  });

  it('decodes numeric currentTotal on a problem response and preserves unrelated extras', () => {
    const { api, http } = setup();
    const error = vi.fn();
    api.post('/orders', {}).subscribe({ error });
    http
      .expectOne('http://gateway.test/orders')
      .flush(
        '{"type":"urn:rebyte:problem:price-mismatch","title":"price-mismatch","status":409,"currentTotal":123456789012345.67,"conversationId":"thread","errors":{"price":"wrong"},"custom":{"amount":7.50}}',
        { status: 409, statusText: 'Conflict' },
      );
    expect(error.mock.calls[0][0].error).toEqual({
      type: 'urn:rebyte:problem:price-mismatch',
      title: 'price-mismatch',
      status: 409,
      currentTotal: '123456789012345.67',
      conversationId: 'thread',
      errors: { price: 'wrong' },
      custom: { amount: 7.5 },
    });
  });
  it('falls back to the shortest numeric form when JSON.parse gives no source context', () => {
    const native = JSON.parse;
    const spy = vi
      .spyOn(JSON, 'parse')
      .mockImplementation(
        (text: string, reviver?: (this: unknown, key: string, value: unknown) => unknown) =>
          native(
            text,
            reviver
              ? function (this: unknown, key, value) {
                  return reviver.call(this, key, value);
                }
              : undefined,
          ),
      );
    try {
      const { api, http } = setup();
      const result = vi.fn();
      api.get('/listings/listing').subscribe(result);
      http
        .expectOne('http://gateway.test/listings/listing')
        .flush('{"price":12.50,"shippingCost":4.99}');
      expect(result).toHaveBeenCalledWith({ price: '12.5', shippingCost: '4.99' });
    } finally {
      spy.mockRestore();
    }
  });
});
