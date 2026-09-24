import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { settle, signInAs, testProviders } from './core/auth/auth.testing';

describe('App routing', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders(routes) });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    http.match(() => true).forEach((req) => req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true }));
  });

  it('sends signed-out visitors (including direct URLs) to the login page', async () => {
    TestBed.inject(AuthService).clearSession();
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/employees');
    expect(router.url).toBe('/login?returnUrl=%2Femployees');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Sign in');
  });

  it('shows the shell with the current page heading to signed-in users', async () => {
    await signInAs(['USER']);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/');
    http.expectOne('/api/health').flush({ status: 'UP', service: 'x', timestamp: '' });
    await harness.fixture.whenStable();

    const root = harness.fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.sidebar-brand')?.textContent).toContain('Enterprise Admin');
    expect(root.querySelector('.topbar-title')?.textContent).toContain('Dashboard');
    expect(root.querySelector('.account-text strong')?.textContent).toContain('Ada Lovelace');
    expect(root.querySelector('h1')?.textContent).toContain('Welcome, Ada');
  });

  it('keeps non-admins out of /users even by direct URL', async () => {
    await signInAs(['MANAGER']);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/users');
    expect(router.url).toBe('/forbidden');
    expect(harness.fixture.nativeElement.textContent).toContain('Access denied');
  });

  it('lets administrators open /users', async () => {
    await signInAs(['ADMIN']);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/users');
    expect(router.url).toBe('/users');
    await settle();
    expect(http.match((req) => req.url === '/api/users').length).toBe(1);
  });

  it('routes unknown paths to the not-found page', async () => {
    TestBed.inject(AuthService).clearSession();
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/does-not-exist');
    expect(harness.routeNativeElement?.textContent).toContain('Page not found');
  });
});
