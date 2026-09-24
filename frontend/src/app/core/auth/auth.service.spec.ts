import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { TEST_USER, authResponse } from './auth.testing';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts in the initializing state with no token', () => {
    expect(auth.status()).toBe('initializing');
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getAccessToken()).toBeNull();
  });

  it('login stores the access token in memory only and exposes the user', async () => {
    const storageWrites = vi.spyOn(Storage.prototype, 'setItem');
    const result = firstValueFrom(auth.login('  ada@example.com ', 'correct horse battery'));
    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'ada@example.com', password: 'correct horse battery' });
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(authResponse('access-1'));

    expect(await result).toEqual(TEST_USER);
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.getAccessToken()).toBe('access-1');
    expect(auth.displayName()).toBe('Ada Lovelace');
    expect(auth.hasAnyRole(['ADMIN', 'MANAGER'])).toBe(true);
    expect(auth.hasAnyRole(['ADMIN'])).toBe(false);
    expect(storageWrites).not.toHaveBeenCalled();
    expect(document.cookie).toBe('');
  });

  it('failed login leaves the user signed out', async () => {
    const result = firstValueFrom(auth.login('ada@example.com', 'wrong')).catch((e) => e);
    http.expectOne('/api/auth/login').flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });
    expect((await result).status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getAccessToken()).toBeNull();
  });

  it('initialize restores a session from the refresh cookie', async () => {
    const done = auth.initialize();
    const req = http.expectOne('/api/auth/refresh');
    expect(req.request.withCredentials).toBe(true);
    req.flush(authResponse('restored'));
    await done;

    expect(auth.status()).toBe('authenticated');
    expect(auth.getAccessToken()).toBe('restored');
    expect(auth.user()).toEqual(TEST_USER);
  });

  it('initialize resolves to anonymous when there is no session (204)', async () => {
    const done = auth.initialize();
    http.expectOne('/api/auth/refresh').flush(null, { status: 204, statusText: 'No Content' });
    await done;
    expect(auth.status()).toBe('anonymous');
  });

  it('initialize never rejects, even when refresh fails', async () => {
    const done = auth.initialize();
    http.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    await expect(done).resolves.toBeUndefined();
    expect(auth.status()).toBe('anonymous');
  });

  it('initialize retries once after a transient failure (e.g. an aborted restore) and then succeeds', async () => {
    const done = auth.initialize();
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('abort'));
    await new Promise((resolve) => setTimeout(resolve, 700));
    http.expectOne('/api/auth/refresh').flush(authResponse('restored'));
    await done;
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.getAccessToken()).toBe('restored');
  });

  it('initialize does not retry a definitive rejection, and never retries more than once', async () => {
    const rejected = auth.initialize();
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    await rejected;
    expect(auth.status()).toBe('anonymous');
    await new Promise((resolve) => setTimeout(resolve, 700));
    http.expectNone('/api/auth/refresh');

    const offline = auth.initialize();
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('offline'));
    await new Promise((resolve) => setTimeout(resolve, 700));
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('offline'));
    await offline;
    expect(auth.status()).toBe('anonymous');
    await new Promise((resolve) => setTimeout(resolve, 700));
    http.expectNone('/api/auth/refresh');
  });

  it('coalesces concurrent refresh calls into a single request', async () => {
    const a = firstValueFrom(auth.refresh());
    const b = firstValueFrom(auth.refresh());
    const c = firstValueFrom(auth.refresh());

    http.expectOne('/api/auth/refresh').flush(authResponse('fresh'));
    expect(await Promise.all([a, b, c])).toEqual(['fresh', 'fresh', 'fresh']);

    // A later refresh is a new request (the in-flight one is not cached forever).
    const d = firstValueFrom(auth.refresh());
    http.expectOne('/api/auth/refresh').flush(authResponse('fresher'));
    expect(await d).toBe('fresher');
  });

  it('a failed refresh clears the session and propagates the error', async () => {
    await signIn();
    const result = firstValueFrom(auth.refresh()).catch((e) => e);
    http.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect((await result).status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getAccessToken()).toBeNull();
  });

  it('logout revokes the server session and clears local state', async () => {
    await signIn();
    const done = firstValueFrom(auth.logout(), { defaultValue: undefined });
    const req = http.expectOne('/api/auth/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBe(true);
    req.flush(null, { status: 204, statusText: 'No Content' });
    await done;
    expect(auth.status()).toBe('anonymous');
    expect(auth.user()).toBeNull();
    expect(auth.getAccessToken()).toBeNull();
  });

  it('logout clears local state even if the server call fails', async () => {
    await signIn();
    const done = firstValueFrom(auth.logout(), { defaultValue: undefined });
    http.expectOne('/api/auth/logout').error(new ProgressEvent('offline'));
    await done;
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('loadCurrentUser fetches /me with the bearer token', async () => {
    await signIn();
    const result = firstValueFrom(auth.loadCurrentUser());
    const req = http.expectOne('/api/auth/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    req.flush({ ...TEST_USER, firstName: 'Augusta' });
    expect((await result).firstName).toBe('Augusta');
    expect(auth.user()?.firstName).toBe('Augusta');
  });

  it('a refresh that completes after logout never revives the session', async () => {
    await signIn();
    const refreshing = firstValueFrom(auth.refresh());
    await new Promise((resolve) => setTimeout(resolve));
    const refreshReq = http.expectOne('/api/auth/refresh');
    const out = firstValueFrom(auth.logout(), { defaultValue: undefined });
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await out;
    refreshReq.flush(authResponse('late-token'));
    expect(await refreshing).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getAccessToken()).toBeNull();
  });

  it.each([
    ['offline', 0],
    ['server error', 503],
    ['rate limited', 429],
  ])('a transient refresh failure (%s) keeps the session', async (_label, status) => {
    await signIn();
    const result = firstValueFrom(auth.refresh()).catch((e) => e);
    await new Promise((resolve) => setTimeout(resolve));
    const req = http.expectOne('/api/auth/refresh');
    if (status === 0) {
      req.error(new ProgressEvent('offline'));
    } else {
      req.flush({}, { status, statusText: 'x' });
    }
    expect((await result).status).toBe(status);
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.getAccessToken()).toBe('access-1');
  });

  async function signIn(): Promise<void> {
    const result = firstValueFrom(auth.login('ada@example.com', 'correct horse battery'));
    http.expectOne('/api/auth/login').flush(authResponse('access-1'));
    await result;
  }
});
