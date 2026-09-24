import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { API_BASE_URL } from '../config/api.config';
import { AUTH_RETRIED, SKIP_AUTH_REFRESH } from './auth-context';
import { AuthService, isDefinitiveRejection } from './auth.service';

/**
 * For requests to our API only:
 * - adds `X-Requested-With` (required by the backend's cookie endpoints as CSRF defence),
 * - attaches `Authorization: Bearer <access token>`,
 * - on 401, performs ONE coordinated refresh and retries the request once; if the refresh is rejected (401/403)
 *   the session is cleared and the user is sent to /login. A transient refresh failure (offline, 5xx, 429) keeps
 *   the session and surfaces that error instead, without redirecting and without retrying again.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const baseUrl = inject(API_BASE_URL).replace(/\/+$/, '');
  if (!isApiRequest(req.url, baseUrl)) {
    return next(req);
  }

  const auth = inject(AuthService);
  const router = inject(Router);
  const request = req.clone({ setHeaders: { 'X-Requested-With': 'XMLHttpRequest' } });

  if (req.context.get(SKIP_AUTH_REFRESH)) {
    return next(request);
  }

  const tokenUsed = auth.getAccessToken();
  return next(withBearer(request, tokenUsed)).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401 || !tokenUsed || req.context.get(AUTH_RETRIED)) {
        return throwError(() => error);
      }

      const retry = (token: string) =>
        next(withBearer(request.clone({ context: request.context.set(AUTH_RETRIED, true) }), token));

      // Another request already refreshed while this one was in flight: just retry with the new token.
      const current = auth.getAccessToken();
      if (current && current !== tokenUsed) {
        return retry(current);
      }

      return auth.refresh().pipe(
        catchError((refreshError: unknown) => {
          if (!isDefinitiveRejection(refreshError)) {
            return throwError(() => refreshError);
          }
          redirectToLogin(router);
          return throwError(() => error);
        }),
        switchMap((token) => {
          if (!token) {
            redirectToLogin(router);
            return throwError(() => error);
          }
          return retry(token);
        }),
      );
    }),
  );
};

function isApiRequest(url: string, baseUrl: string): boolean {
  return url === baseUrl || url.startsWith(`${baseUrl}/`);
}

function withBearer<T>(req: HttpRequest<T>, token: string | null): HttpRequest<T> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

function redirectToLogin(router: Router): void {
  const current = router.url;
  const returnUrl = current && !current.startsWith('/login') ? current : undefined;
  void router.navigate(['/login'], { queryParams: returnUrl && returnUrl !== '/' ? { returnUrl } : {} });
}
