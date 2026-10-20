import { DOCUMENT } from '@angular/common';
import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, defer, switchMap, throwError } from 'rxjs';
import { AuthAdapter } from '../auth/auth.adapter';
import { validateReturnUrl } from '../auth/auth.guards';
import { AuthStore } from '../auth/auth.store';
import { AppConfigService } from '../config/app-config';

const REFRESH_ATTEMPTED = new HttpContextToken(() => false);
const ANONYMOUS_ATTEMPTED = new HttpContextToken(() => false);

function apiPath(url: string, apiBaseUrl: string, documentBase: string): string | null {
  try {
    const base = new URL(apiBaseUrl, documentBase);
    const target = new URL(url, documentBase);
    const prefix = base.pathname.replace(/\/+$/, '');
    if (
      !['http:', 'https:'].includes(target.protocol) ||
      target.origin !== base.origin ||
      target.username ||
      target.password ||
      (target.pathname !== prefix && !target.pathname.startsWith(prefix + '/'))
    ) {
      return null;
    }
    return target.pathname.slice(prefix.length) || '/';
  } catch {
    return null;
  }
}

function isPublicGet(method: string, path: string): boolean {
  try {
    path = decodeURIComponent(path);
  } catch {
    return false;
  }
  if (method !== 'GET' || path === '/listings/mine' || /^\/users\/me(?:\/|$)/.test(path)) {
    return false;
  }
  return (
    /^\/(?:search|categories)(?:\/|$)/.test(path) ||
    /^\/listings\/[^/]+$/.test(path) ||
    /^\/users\/[^/]+(?:\/(?:reviews|listings))?$/.test(path)
  );
}

function isUnauthorized(error: unknown): error is HttpErrorResponse {
  return error instanceof HttpErrorResponse && error.status === 401;
}

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const config = inject(AppConfigService).config;
  const document = inject(DOCUMENT);
  const path = apiPath(req.url, config.apiBaseUrl, document.baseURI);
  const anonymous = req.clone({ headers: req.headers.delete('Authorization') });
  if (path === null) {
    return next(anonymous);
  }

  const adapter = inject(AuthAdapter);
  const store = inject(AuthStore);
  const router = inject(Router);
  const withToken = (token: string | null) =>
    token ? anonymous.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : anonymous;
  const redirect = (error: HttpErrorResponse) => {
    void router.navigate(['/login'], {
      queryParams: { returnUrl: validateReturnUrl(router.url) },
    });
    return throwError(() => error);
  };
  const expired = (error: HttpErrorResponse) => {
    store.clearSession();
    if (isPublicGet(req.method, path) && !req.context.get(ANONYMOUS_ATTEMPTED)) {
      req.context.set(ANONYMOUS_ATTEMPTED, true);
      return next(anonymous).pipe(
        catchError((failure: unknown) =>
          isUnauthorized(failure) ? redirect(failure) : throwError(() => failure),
        ),
      );
    }
    return redirect(error);
  };

  return defer(() =>
    req.context.get(ANONYMOUS_ATTEMPTED)
      ? Promise.resolve(null)
      : adapter.accessToken().catch(() => null),
  ).pipe(
    switchMap((token) => next(withToken(token))),
    catchError((error: unknown) => {
      if (!isUnauthorized(error)) {
        return throwError(() => error);
      }
      if (req.context.get(REFRESH_ATTEMPTED)) {
        return expired(error);
      }
      // Context survives cloned requests and caller resubscriptions without adding CORS headers.
      req.context.set(REFRESH_ATTEMPTED, true);
      return defer(() => adapter.accessToken({ forceRefresh: true }).catch(() => null)).pipe(
        switchMap((token) =>
          token
            ? next(withToken(token)).pipe(
                catchError((failure: unknown) =>
                  isUnauthorized(failure) ? expired(failure) : throwError(() => failure),
                ),
              )
            : expired(error),
        ),
      );
    }),
  );
};
