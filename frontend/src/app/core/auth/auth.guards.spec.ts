import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { authGuard, guestGuard, roleGuard } from './auth.guards';
import { AuthService } from './auth.service';
import { TEST_USER, authResponse } from './auth.testing';

@Component({ template: 'page' })
class Page {}

describe('auth guards', () => {
  let router: Router;
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: Page, canActivate: [guestGuard] },
          { path: 'forbidden', component: Page },
          { path: 'app', component: Page, canActivate: [authGuard] },
          { path: 'admin', component: Page, canActivate: [roleGuard('ADMIN')] },
          { path: 'team', component: Page, canActivate: [roleGuard('ADMIN', 'MANAGER')] },
          { path: '', component: Page },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  async function signInAs(roles: typeof TEST_USER.roles): Promise<void> {
    const login = firstValueFrom(auth.login('ada@example.com', 'correct horse battery'));
    http.expectOne('/api/auth/login').flush(authResponse('t', { ...TEST_USER, roles }));
    await login;
  }

  it('redirects signed-out users to /login with the requested URL (direct navigation)', async () => {
    auth.clearSession();
    await router.navigateByUrl('/app?tab=1');
    expect(router.url).toBe('/login?returnUrl=%2Fapp%3Ftab%3D1');
  });

  it('waits for session initialization before deciding', async () => {
    const navigation = router.navigateByUrl('/app');
    await Promise.resolve();
    expect(router.url).toBe('/');
    await signInAs(['USER']);
    await navigation;
    expect(router.url).toBe('/app');
  });

  it('lets signed-in users into protected routes', async () => {
    await signInAs(['USER']);
    await router.navigateByUrl('/app');
    expect(router.url).toBe('/app');
  });

  it('keeps signed-in users away from /login', async () => {
    await signInAs(['USER']);
    await router.navigateByUrl('/login');
    expect(router.url).toBe('/');
  });

  it('sends a signed-in user on to a same-app returnUrl, never to another origin', async () => {
    await signInAs(['USER']);
    await router.navigateByUrl('/login?returnUrl=%2Fapp%3Ftab%3D2');
    expect(router.url).toBe('/app?tab=2');
    for (const target of ['//evil.example/x', 'https://evil.example', '/\\evil.example', '/login']) {
      await router.navigateByUrl('/login?returnUrl=' + encodeURIComponent(target));
      expect(router.url).toBe('/');
    }
  });

  it('role guard allows matching roles and sends others to /forbidden', async () => {
    await signInAs(['MANAGER']);
    await router.navigateByUrl('/team');
    expect(router.url).toBe('/team');
    await router.navigateByUrl('/admin');
    expect(router.url).toBe('/forbidden');
  });

  it('role guard blocks USER from ADMIN and MANAGER routes', async () => {
    await signInAs(['USER']);
    await router.navigateByUrl('/admin');
    expect(router.url).toBe('/forbidden');
    await router.navigateByUrl('/team');
    expect(router.url).toBe('/forbidden');
  });

  it('role guard allows ADMIN everywhere', async () => {
    await signInAs(['ADMIN']);
    await router.navigateByUrl('/admin');
    expect(router.url).toBe('/admin');
    await router.navigateByUrl('/team');
    expect(router.url).toBe('/team');
  });

  it('role guard sends signed-out users to /login', async () => {
    auth.clearSession();
    await router.navigateByUrl('/admin');
    expect(router.url).toBe('/login?returnUrl=%2Fadmin');
  });
});
