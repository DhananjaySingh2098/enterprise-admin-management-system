import { HttpClient, HttpContext, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import {
  Observable,
  catchError,
  defer,
  filter,
  finalize,
  firstValueFrom,
  from,
  map,
  of,
  shareReplay,
  take,
  switchMap,
  tap,
  throwError,
  timeout,
  timer,
} from 'rxjs';

import { ApiService } from '../http/api.service';
import { SKIP_AUTH_REFRESH } from './auth-context';
import { AuthResponse, AuthStatus, CurrentUser, Role } from './auth.models';

const REFRESH_LOCK = 'enterprise-admin:auth-refresh';
const INIT_TIMEOUT_MS = 10_000;
const RESTORE_RETRY_MS = 600;

/**
 * Single source of truth for authentication state.
 *
 * - The access token lives only in memory (never localStorage/sessionStorage).
 * - The refresh token lives only in an HttpOnly cookie the browser sends to /api/auth; JS can never read it.
 * - After a reload the session is restored via {@link initialize} → POST /api/auth/refresh.
 * - Concurrent refreshes are coalesced into one request per tab, and serialized across tabs with the
 *   Web Locks API so two tabs never present the same (single-use) refresh token.
 * - A session epoch guards against late responses: a refresh that completes after logout (or after a newer login)
 *   is discarded instead of silently signing the user back in.
 * - Only a definitive rejection (401/403) ends the session. Network errors, 5xx and 429 keep it: the user stays
 *   signed in and the next request tries again (no automatic retry loop). If the page itself is reloaded while a
 *   refresh is in flight, the server recovers the lost rotation (see docs/SECURITY.md, "Refresh races").
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly api = inject(ApiService);

  private readonly statusSignal = signal<AuthStatus>('initializing');
  private readonly userSignal = signal<CurrentUser | null>(null);
  private accessToken: string | null = null;
  private refreshInFlight: Observable<string | null> | null = null;
  /** Incremented on logout and on every newly established session; late refresh results from an older epoch are ignored. */
  private epoch = 0;

  readonly status = this.statusSignal.asReadonly();
  readonly user = this.userSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.statusSignal() === 'authenticated');
  readonly roles = computed<readonly Role[]>(() => this.userSignal()?.roles ?? []);
  readonly displayName = computed(() => {
    const user = this.userSignal();
    return user ? `${user.firstName} ${user.lastName}`.trim() || user.email : '';
  });

  /** Emits once, when the initial session check has finished. */
  private readonly resolvedStatus$ = toObservable(this.statusSignal).pipe(filter((s) => s !== 'initializing'));

  getAccessToken(): string | null {
    return this.accessToken;
  }

  hasAnyRole(roles: readonly Role[]): boolean {
    const mine = this.roles();
    return roles.some((role) => mine.includes(role));
  }

  whenResolved(): Observable<AuthStatus> {
    return this.resolvedStatus$.pipe(take(1));
  }

  /** App-startup session restore. Never rejects: any failure simply means "not signed in". */
  initialize(): Promise<void> {
    return firstValueFrom(
      this.refresh().pipe(
        // One bounded retry for transient failures (offline blip, 5xx, 429, an aborted request); a definitive
        // rejection (401/403) is final. Never more than one retry.
        catchError((error: unknown) =>
          isDefinitiveRejection(error) ? throwError(() => error) : timer(RESTORE_RETRY_MS).pipe(switchMap(() => this.refresh())),
        ),
        timeout(INIT_TIMEOUT_MS),
        map(() => undefined),
        catchError(() => {
          this.clearSession();
          return of(undefined);
        }),
      ),
    );
  }

  login(email: string, password: string): Observable<CurrentUser> {
    return this.http
      .post<AuthResponse>(this.api.url('auth/login'), { email: email.trim(), password }, this.authRequestOptions())
      .pipe(map((response) => this.applySession(response)));
  }

  /**
   * Exchanges the refresh cookie for a new access token (rotating the refresh token).
   * Emits the new access token, or `null` when there is no session to restore.
   * Errors (invalid/expired/reused session) clear local state and propagate.
   */
  refresh(): Observable<string | null> {
    if (!this.refreshInFlight) {
      const startedIn = this.epoch;
      const inFlight: Observable<string | null> = defer(() =>
        from(
          withRefreshLock(() =>
            firstValueFrom(
              this.http.post<AuthResponse>(this.api.url('auth/refresh'), null, {
                ...this.authRequestOptions(),
                observe: 'response',
              }),
            ),
          ),
        ),
      ).pipe(
        map((response: HttpResponse<AuthResponse>) => {
          if (this.epoch !== startedIn) {
            return null; // signed out (or signed in again) while this refresh was running
          }
          if (response.status === 204 || !response.body) {
            this.clearSession();
            return null;
          }
          this.applySession(response.body);
          return response.body.accessToken;
        }),
        catchError((error: unknown) => {
          if (this.epoch === startedIn && isDefinitiveRejection(error)) {
            this.clearSession();
          }
          return throwError(() => error);
        }),
        finalize(() => {
          if (this.refreshInFlight === inFlight) {
            this.refreshInFlight = null;
          }
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
      this.refreshInFlight = inFlight;
    }
    return this.refreshInFlight;
  }

  /** Revokes the server-side session and always clears local state, even if the server is unreachable. */
  logout(): Observable<void> {
    this.epoch++;
    this.refreshInFlight = null;
    return this.http.post<void>(this.api.url('auth/logout'), null, this.authRequestOptions()).pipe(
      catchError(() => of(undefined)),
      map(() => undefined),
      finalize(() => this.clearSession()),
    );
  }

  loadCurrentUser(): Observable<CurrentUser> {
    return this.api.get<CurrentUser>('auth/me').pipe(tap((user) => this.userSignal.set(user)));
  }

  /** Adopts a session issued outside login/refresh (e.g. after a password change rotated all sessions). */
  acceptSession(response: AuthResponse): void {
    this.applySession(response);
  }

  /** Keeps the header/menus in sync after the user edits their own profile. */
  updateCurrentUser(changes: Partial<Pick<CurrentUser, 'firstName' | 'lastName' | 'email'>>): void {
    this.userSignal.update((user) => (user ? { ...user, ...changes } : user));
  }

  clearSession(): void {
    this.accessToken = null;
    this.userSignal.set(null);
    this.statusSignal.set('anonymous');
  }

  private applySession(response: AuthResponse): CurrentUser {
    if (this.accessToken === null) {
      this.epoch++; // a new session (login or restore), not a rotation of the current one
    }
    this.accessToken = response.accessToken;
    this.userSignal.set(response.user);
    this.statusSignal.set('authenticated');
    return response.user;
  }

  private authRequestOptions() {
    return {
      // Needed for the refresh cookie when the API is on another origin; harmless on the same origin.
      withCredentials: true,
      context: new HttpContext().set(SKIP_AUTH_REFRESH, true),
    };
  }
}

/** 401/403 mean the server rejected the session; anything else (offline, 5xx, 429) is transient. */
export function isDefinitiveRejection(error: unknown): boolean {
  return error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403);
}

/** Serializes refreshes across browser tabs when the Web Locks API is available. */
function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  return locks ? (locks.request(REFRESH_LOCK, task) as Promise<T>) : task();
}
