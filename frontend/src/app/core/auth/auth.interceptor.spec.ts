import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { authResponse } from './auth.testing';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const login = firstValueFrom(auth.login('ada@example.com', 'correct horse battery'));
    http.expectOne('/api/auth/login').flush(authResponse('token-1'));
    await login;
  });

  afterEach(() => http.verify());

  const unauthorized = { status: 401, statusText: 'Unauthorized' };

  /** The refresh runs through a Promise (Web Locks), so follow-up requests appear a macrotask later. */
  const settle = () => new Promise((resolve) => setTimeout(resolve));

  it('attaches the bearer token and anti-CSRF header to API requests', () => {
    client.get('/api/things').subscribe();
    const req = http.expectOne('/api/things');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(req.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    req.flush({});
  });

  it('never sends the token to other origins', () => {
    client.get('https://cdn.example.com/api/file.json').subscribe();
    const req = http.expectOne('https://cdn.example.com/api/file.json');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.headers.has('X-Requested-With')).toBe(false);
    req.flush({});
  });

  it('refreshes once on 401 and retries the original request with the new token', async () => {
    const result = firstValueFrom(client.get<{ ok: boolean }>('/api/things'));

    http.expectOne('/api/things').flush({}, unauthorized);
    await settle();
    http.expectOne('/api/auth/refresh').flush(authResponse('token-2'));
    await settle();
    const retried = http.expectOne('/api/things');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer token-2');
    retried.flush({ ok: true });

    expect(await result).toEqual({ ok: true });
    expect(auth.getAccessToken()).toBe('token-2');
  });

  it('coordinates one refresh for several simultaneous 401s', async () => {
    const results = Promise.all([
      firstValueFrom(client.get('/api/a')),
      firstValueFrom(client.get('/api/b')),
      firstValueFrom(client.get('/api/c')),
    ]);

    for (const url of ['/api/a', '/api/b', '/api/c']) {
      http.expectOne(url).flush({}, unauthorized);
    }
    await settle();
    http.expectOne('/api/auth/refresh').flush(authResponse('token-2'));
    await settle();
    for (const url of ['/api/a', '/api/b', '/api/c']) {
      const retry = http.expectOne(url);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer token-2');
      retry.flush({ url });
    }

    await results;
    http.expectNone('/api/auth/refresh');
  });

  it('does not loop: a retried request that fails with 401 again is not refreshed a second time', async () => {
    const result = firstValueFrom(client.get('/api/things')).catch((e) => e);

    http.expectOne('/api/things').flush({}, unauthorized);
    await settle();
    http.expectOne('/api/auth/refresh').flush(authResponse('token-2'));
    await settle();
    http.expectOne('/api/things').flush({}, unauthorized);

    expect((await result).status).toBe(401);
    http.expectNone('/api/auth/refresh');
  });

  it('when refresh fails it clears the session and navigates to /login', async () => {
    const result = firstValueFrom(client.get('/api/things')).catch((e) => e);

    http.expectOne('/api/things').flush({}, unauthorized);
    await settle();
    http.expectOne('/api/auth/refresh').flush({}, unauthorized);

    expect((await result).status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('does not refresh on 403 or other errors', async () => {
    const result = firstValueFrom(client.get('/api/admin-only')).catch((e) => e);
    http.expectOne('/api/admin-only').flush({}, { status: 403, statusText: 'Forbidden' });
    expect((await result).status).toBe(403);
    http.expectNone('/api/auth/refresh');
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('never intercepts the refresh endpoint itself (no recursion)', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e) => e);
    await settle();
    const req = http.expectOne('/api/auth/refresh');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({}, unauthorized);
    expect((await result).status).toBe(401);
    await settle();
    http.expectNone('/api/auth/refresh');
  });

  it('does not attempt refresh for anonymous requests', async () => {
    auth.clearSession();
    const result = firstValueFrom(client.get('/api/things')).catch((e) => e);
    const req = http.expectOne('/api/things');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({}, unauthorized);
    expect((await result).status).toBe(401);
    http.expectNone('/api/auth/refresh');
  });

  it('a transient refresh failure surfaces that error without redirecting or retrying', async () => {
    const result = firstValueFrom(client.get('/api/things')).catch((e) => e);
    http.expectOne('/api/things').flush({}, unauthorized);
    await settle();
    http.expectOne('/api/auth/refresh').flush({ code: 'RATE_LIMITED', message: 'Too many requests.' }, { status: 429, statusText: 'Too Many Requests' });

    expect((await result).status).toBe(429);
    expect(auth.isAuthenticated()).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
    http.expectNone('/api/things');
    http.expectNone('/api/auth/refresh');
  });

  it('logout while a refresh is running: the late token is ignored and the request is not retried', async () => {
    const result = firstValueFrom(client.get('/api/things')).catch((e) => e);
    http.expectOne('/api/things').flush({}, unauthorized);
    await settle();
    const refresh = http.expectOne('/api/auth/refresh');
    const out = firstValueFrom(auth.logout(), { defaultValue: undefined });
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await out;
    refresh.flush(authResponse('late'));

    expect((await result).status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
    http.expectNone('/api/things');
  });

  it('many simultaneous 401s cause exactly one refresh and one retry each (no storm)', async () => {
    const results = [1, 2, 3, 4, 5].map((i) => firstValueFrom(client.get(`/api/r${i}`)));
    for (const i of [1, 2, 3, 4, 5]) {
      http.expectOne(`/api/r${i}`).flush({}, unauthorized);
    }
    await settle();
    http.expectOne('/api/auth/refresh').flush(authResponse('token-2'));
    await settle();
    for (const i of [1, 2, 3, 4, 5]) {
      const retry = http.expectOne(`/api/r${i}`);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer token-2');
      retry.flush({ ok: i });
    }
    expect((await Promise.all(results)).length).toBe(5);
    http.expectNone('/api/auth/refresh');
  });
});
