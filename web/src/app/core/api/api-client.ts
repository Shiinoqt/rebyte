import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable } from '@angular/core';
import { catchError, map, Observable, throwError } from 'rxjs';
import { AppConfigService } from '../config/app-config';

export type QueryValue = string | number | boolean;
export type QueryParams = Record<string, QueryValue | readonly QueryValue[] | null | undefined>;
export interface ApiRequestOptions {
  params?: QueryParams;
  headers?: HttpHeaders | Record<string, string | string[]>;
  context?: HttpContext;
}

@Injectable({ providedIn: 'root' })
export class ApiClient {
  constructor(
    private readonly http: HttpClient,
    private readonly config: AppConfigService,
  ) {}

  get<T>(path: string, options: ApiRequestOptions = {}): Observable<T> {
    return this.json<T>(
      this.http.get(this.url(path), { ...this.options(options), responseType: 'text' }),
    );
  }

  post<T>(path: string, body: unknown = null, options: ApiRequestOptions = {}): Observable<T> {
    return this.json<T>(
      this.http.post(this.url(path), body, { ...this.options(options), responseType: 'text' }),
    );
  }

  put<T>(path: string, body: unknown = null, options: ApiRequestOptions = {}): Observable<T> {
    return this.json<T>(
      this.http.put(this.url(path), body, { ...this.options(options), responseType: 'text' }),
    );
  }

  patch<T>(path: string, body: unknown = null, options: ApiRequestOptions = {}): Observable<T> {
    return this.json<T>(
      this.http.patch(this.url(path), body, { ...this.options(options), responseType: 'text' }),
    );
  }

  delete<T>(path: string, options: ApiRequestOptions = {}): Observable<T> {
    return this.json<T>(
      this.http.delete(this.url(path), { ...this.options(options), responseType: 'text' }),
    );
  }

  postResponse<T>(
    path: string,
    body: unknown = null,
    options: ApiRequestOptions = {},
  ): Observable<HttpResponse<T>> {
    return this.http.post<T>(this.url(path), body, {
      ...this.options(options),
      observe: 'response',
    });
  }

  getText(path: string, options: ApiRequestOptions = {}): Observable<string> {
    return this.http.get(this.url(path), { ...this.options(options), responseType: 'text' });
  }

  postText(
    path: string,
    body: unknown = null,
    options: ApiRequestOptions = {},
  ): Observable<string> {
    return this.http.post(this.url(path), body, { ...this.options(options), responseType: 'text' });
  }

  private json<T>(response: Observable<string>): Observable<T> {
    return response.pipe(
      map((body) => parseApiJson(body) as T),
      catchError((error: unknown) => {
        if (!(error instanceof HttpErrorResponse) || typeof error.error !== 'string') {
          return throwError(() => error);
        }
        let body: unknown;
        try {
          body = parseApiJson(error.error);
        } catch {
          return throwError(() => error);
        }
        return throwError(
          () =>
            new HttpErrorResponse({
              error: body,
              headers: error.headers,
              status: error.status,
              statusText: error.statusText,
              url: error.url ?? undefined,
            }),
        );
      }),
    );
  }

  private options(options: ApiRequestOptions) {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(options.params ?? {})) {
      if (value !== null && value !== undefined) {
        for (const item of Array.isArray(value) ? value : [value]) {
          params = params.append(key, item);
        }
      }
    }
    return { ...options, params };
  }

  private url(path: string): string {
    const decoded = decodeURIComponent(path);
    if (
      !path ||
      /^[a-z][a-z\d+.-]*:/i.test(decoded) ||
      decoded.startsWith('//') ||
      /[\\\s\u0000-\u001f\u007f?#]/.test(path) ||
      /[\\\u0000-\u001f\u007f]/.test(decoded) ||
      decoded.split('/').some((part) => part === '.' || part === '..')
    ) {
      throw new Error('ApiClient requires a relative API path');
    }
    return `${this.config.config.apiBaseUrl.replace(/\/+$/, '')}/${path.replace(/^\//, '')}`;
  }
}

const MONEY_FIELDS = new Set([
  'price',
  'shippingCost',
  'oldPrice',
  'newPrice',
  'itemPrice',
  'pricePaid',
  'amount',
  'askingPrice',
  'listingPrice',
  'currentTotal',
]);
const OPAQUE_FIELDS = new Set(['attr', 'value', 'target', 'snapshot', 'errors']);

// BigDecimal is numeric JSON on the server. Original lexemes retain precision and decimal scale.
export function parseApiJson(body: string | null): unknown {
  if (body === null || body === '') return null;
  const sources = new WeakMap<object, Map<string, string>>();
  const value: unknown = JSON.parse(
    body,
    function (this: object, key: string, value: unknown, context?: { source: string }) {
      if (typeof value === 'number' && MONEY_FIELDS.has(key) && context?.source !== undefined) {
        let fields = sources.get(this);
        if (!fields) {
          fields = new Map();
          sources.set(this, fields);
        }
        fields.set(key, context.source);
      }
      return value;
    },
  );

  function decode(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(decode);
    if (value === null || typeof value !== 'object') return value;
    const fields = value as Record<string, unknown>;
    const problem = typeof fields['type'] === 'string' && typeof fields['status'] === 'number';
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => {
        if (problem && key !== 'currentTotal') return [key, item];
        if (OPAQUE_FIELDS.has(key)) return [key, item];
        if (MONEY_FIELDS.has(key) && typeof item === 'number') {
          // Browsers without JSON.parse source access fall back to the shortest round-trip form, exact for EUR amounts.
          return [key, sources.get(value)?.get(key) ?? String(item)];
        }
        return [key, decode(item)];
      }),
    );
  }
  return decode(value);
}
