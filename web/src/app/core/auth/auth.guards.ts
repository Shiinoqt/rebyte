import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthStore } from './auth.store';

export function validateReturnUrl(input: unknown): string {
  if (typeof input !== 'string') {
    return '/';
  }
  let decoded = input;
  for (let depth = 0; depth < 5; depth++) {
    if (
      !decoded.startsWith('/') ||
      decoded.startsWith('//') ||
      /[\\\u0000-\u001f\u007f]/.test(decoded) ||
      /[a-z][a-z0-9+.-]*:/i.test(decoded)
    ) {
      return '/';
    }
    if (!decoded.includes('%')) {
      return input;
    }
    try {
      decoded = decodeURIComponent(decoded);
    } catch {
      return '/';
    }
  }
  return '/';
}

function loginRedirect(router: Router, url: string): UrlTree {
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: validateReturnUrl(url) } });
}

export const authGuard: CanActivateFn = async (_route, state) => {
  const store = inject(AuthStore);
  const router = inject(Router);
  await store.whenResolved();
  return store.isAuthenticated() ? true : loginRedirect(router, state.url);
};

export const guestGuard: CanActivateFn = async () => {
  const store = inject(AuthStore);
  const router = inject(Router);
  await store.whenResolved();
  return store.isAuthenticated() ? router.createUrlTree(['/']) : true;
};

export const adminGuard: CanActivateFn = async (_route, state) => {
  const store = inject(AuthStore);
  const router = inject(Router);
  await store.whenResolved();
  if (!store.isAuthenticated()) {
    return loginRedirect(router, state.url);
  }
  return store.isAdmin() ? true : router.createUrlTree(['/']);
};
