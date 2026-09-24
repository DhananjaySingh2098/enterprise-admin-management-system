import { HttpContextToken } from '@angular/common/http';

/** Marks the auth endpoints themselves: no bearer token, no refresh-on-401 (prevents recursion). */
export const SKIP_AUTH_REFRESH = new HttpContextToken<boolean>(() => false);

/** Set on a request that is being retried after a refresh, so it is never retried twice. */
export const AUTH_RETRIED = new HttpContextToken<boolean>(() => false);
