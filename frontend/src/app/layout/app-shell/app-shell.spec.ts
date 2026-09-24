import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { Role } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { signInAs, testProviders } from '../../core/auth/auth.testing';
import { ThemeService } from '../../core/theme/theme.service';
import { AppShell } from './app-shell';

describe('AppShell', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AppShell], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
  });

  /** Background requests the shell starts for every signed-in user (preferences, organization name, unread count). */
  function flushBackground(prefs: object = { themeMode: 'SYSTEM', themePreset: 'AURORA', density: 'COMFORTABLE', saved: true }): void {
    for (const req of http.match((r) => r.url === '/api/preferences' && r.method === 'GET')) {
      req.flush(prefs);
    }
    for (const req of http.match('/api/settings/workspace')) {
      req.flush({ organizationName: 'Acme Corp' });
    }
    for (const req of http.match('/api/notifications/unread-count')) {
      req.flush({ count: 0 });
    }
  }

  afterEach(() => {
    flushBackground();
    http.verify();
  });

  async function renderAs(roles: Role[]) {
    await signInAs(roles);
    const fixture = TestBed.createComponent(AppShell);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const navLabels = (el: HTMLElement) => [...el.querySelectorAll('.nav-link .nav-text')].map((s) => s.textContent?.trim());

  it('shows Users and Audit Logs navigation only to administrators', async () => {
    expect(navLabels((await renderAs(['ADMIN'])).el)).toEqual(['Dashboard', 'Users', 'Employees', 'Departments', 'Audit Logs', 'Profile', 'Settings']);
  });

  it('hides Users and Audit Logs navigation for MANAGER', async () => {
    expect(navLabels((await renderAs(['MANAGER'])).el)).toEqual(['Dashboard', 'Employees', 'Departments', 'Profile', 'Settings']);
  });

  it('shows USER a Home workspace instead of a Dashboard, and no Users item', async () => {
    expect(navLabels((await renderAs(['USER'])).el)).toEqual(['Home', 'Employees', 'Departments', 'Profile', 'Settings']);
  });

  it('shows the user avatar, name and role label in the account control', async () => {
    const { el } = await renderAs(['MANAGER']);
    expect(el.querySelector('.account-trigger .avatar')?.textContent?.trim()).toBe('AL');
    expect(el.querySelector('.account-text strong')?.textContent).toContain('Ada Lovelace');
    expect(el.querySelector('.account-text span')?.textContent).toContain('Manager');
    expect(el.querySelector('.sidebar-help')?.textContent).toContain('Manager access');
  });

  it('toggles the help card and offers role-scoped global search', async () => {
    const { fixture, el } = await renderAs(['MANAGER']);
    const toggle = el.querySelector<HTMLButtonElement>('.help-toggle')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('#help-panel')?.textContent).toContain('contact an administrator');
    expect(el.querySelector('.sidebar-promo')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.querySelector('.global-search-input')?.getAttribute('placeholder')).toBe('Search employees or departments…');
  });

  it('shows the section breadcrumb and page title', async () => {
    const { el } = await renderAs(['USER']);
    expect(el.querySelector('.topbar-crumb')?.textContent).toContain('Workspace');
    expect(el.querySelector('.topbar-title')).not.toBeNull();
  });

  it('account menu is a keyboard-accessible disclosure with Profile, Settings, Appearance and Sign out', async () => {
    const { fixture, el } = await renderAs(['ADMIN']);
    const trigger = el.querySelector<HTMLButtonElement>('.account-trigger')!;
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    trigger.click();
    await fixture.whenStable();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const items = [...el.querySelectorAll('.account-actions a, .account-actions button')].map((i) => i.textContent?.trim());
    expect(items).toEqual(['Profile', 'Settings', 'Appearance', 'Sign out']);
    expect(el.querySelector('.account-actions a')?.getAttribute('href')).toBe('/profile');

    [...el.querySelectorAll<HTMLButtonElement>('.account-actions button')].find((b) => b.textContent?.includes('Appearance'))!.click();
    await fixture.whenStable();
    expect(el.querySelector('.account-panel')).toBeNull();
    expect(el.querySelector('.theme-panel')).not.toBeNull();

    el.querySelector('.theme-menu')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    trigger.click();
    await fixture.whenStable();
    el.querySelector('.account-menu')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(el.querySelector('.account-panel')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('toggles the mobile navigation accessibly and closes it on Escape', async () => {
    const { fixture, el } = await renderAs(['USER']);
    const toggle = el.querySelector<HTMLButtonElement>('.menu-toggle')!;
    toggle.click();
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('.sidebar')?.classList).toContain('is-open');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('signs out through the API and returns to /login', async () => {
    const { fixture, el } = await renderAs(['USER']);
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    el.querySelector<HTMLButtonElement>('.account-trigger')!.click();
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('button[aria-label="Sign out"]')!.click();
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('shows the organization name in the breadcrumb and a notification bell with the unread count', async () => {
    const { fixture, el } = await renderAs(['USER']);
    http.expectOne('/api/settings/workspace').flush({ organizationName: 'Acme Corp' });
    await new Promise((resolve) => setTimeout(resolve, 10)); // the unread poll starts on an async timer(0)
    http.expectOne('/api/notifications/unread-count').flush({ count: 3 });
    await fixture.whenStable();
    expect(el.querySelector('.topbar-crumb')?.textContent).toContain('Acme Corp');
    const bell = el.querySelector<HTMLButtonElement>('.bell-trigger')!;
    expect(bell.getAttribute('aria-label')).toBe('Notifications, 3 unread');
    expect(el.querySelector('.bell-badge')?.textContent?.trim()).toBe('3');
  });

  it('applies appearance saved on the account after the session starts', async () => {
    const { fixture } = await renderAs(['USER']);
    http.expectOne((r) => r.url === '/api/preferences' && r.method === 'GET')
      .flush({ themeMode: 'DARK', themePreset: 'MIDNIGHT', density: 'COMPACT', saved: true });
    await fixture.whenStable();
    const root = document.documentElement;
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(root.getAttribute('data-preset')).toBe('midnight');
    expect(root.getAttribute('data-density')).toBe('compact');
    const theme = TestBed.inject(ThemeService);
    theme.applyRemote({ mode: 'system', preset: 'aurora', density: 'comfortable' });
  });
});
