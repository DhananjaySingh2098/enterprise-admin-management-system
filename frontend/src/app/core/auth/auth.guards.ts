import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable, map } from 'rxjs';

import { Role } from './auth.models';
import { AuthService } from './auth.service';

// Guards are a UX convenience only: the backend authorizes every request independently.

function loginRedirect(router: Router, returnUrl: string): UrlTree {
  return router.createUrlTree(['/login'], {
    queryParams: returnUrl && returnUrl !== '/' ? { returnUrl } : {},
  });
}

/** Lets signed-in users through; everyone else goes to /login (remembering where they were heading). */
export const authGuard: CanActivateFn = (_route, state): Observable<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.whenResolved().pipe(map(() => auth.isAuthenticated() || loginRedirect(router, state.url)));
};

/**
 * Keeps signed-in users away from the login page, sending them on to where they were heading (a same-app path
 * only; anything else falls back to the dashboard).
 */
export const guestGuard: CanActivateFn = (route): Observable<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.whenResolved().pipe(
    map(() => (auth.isAuthenticated() ? router.parseUrl(safeReturnUrl(route.queryParamMap.get('returnUrl'))) : true)),
  );
};

/** Only same-app absolute paths are followed; protocol-relative or external targets fall back to '/'. */
export function safeReturnUrl(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\') || value.startsWith('/login')) {
    return '/';
  }
  return value;
}

/**
 * Requires at least one of the given roles. Usage: `canActivate: [roleGuard('ADMIN', 'MANAGER')]`.
 * Signed-out users go to /login; signed-in users without the role go to /forbidden.
 */
export function roleGuard(...allowed: Role[]): CanActivateFn {
  return (_route, state): Observable<boolean | UrlTree> => {
    const auth = inject(AuthService);
    const router = inject(Router);
    return auth.whenResolved().pipe(
      map(() => {
        if (!auth.isAuthenticated()) {
          return loginRedirect(router, state.url);
        }
        return auth.hasAnyRole(allowed) || router.createUrlTree(['/forbidden']);
      }),
    );
  };
}
