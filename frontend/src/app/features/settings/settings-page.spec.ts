import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../../core/auth/auth.models';
import { settle, signInAs, testProviders } from '../../core/auth/auth.testing';
import { THEME_STORAGE, ThemeService } from '../../core/theme/theme.service';
import { SettingsPage } from './settings-page';

const ORG = { organizationName: 'Enterprise Admin', recentHireWindowDays: 30, version: 4, updatedAt: '2026-09-20T10:00:00Z' };

describe('SettingsPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SettingsPage>;
  let el: HTMLElement;
  let theme: ThemeService;

  async function render(roles: Role[]) {
    TestBed.configureTestingModule({ imports: [SettingsPage], providers: [...testProviders(), { provide: THEME_STORAGE, useValue: null }] });
    http = TestBed.inject(HttpTestingController);
    await signInAs(roles);
    theme = TestBed.inject(ThemeService);
    fixture = TestBed.createComponent(SettingsPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
    await settle();
    for (const req of http.match((r) => r.url === '/api/preferences' && r.method === 'GET')) {
      req.flush({ themeMode: 'SYSTEM', themePreset: 'AURORA', density: 'COMFORTABLE', saved: true });
    }
    await fixture.whenStable();
  }

  afterEach(() => {
    http.verify();
    theme.applyRemote({ mode: 'system', preset: 'aurora', density: 'comfortable' });
  });

  it('offers appearance, visual style and density as labelled radio groups', async () => {
    await render(['USER']);
    expect([...el.querySelectorAll('.settings-group legend')].map((l) => l.textContent?.trim())).toEqual(['Mode', 'Visual style', 'Density']);
    expect(el.querySelectorAll('input[name="settings-mode"]').length).toBe(3);
    expect(el.querySelectorAll('input[name="settings-preset"]').length).toBe(5);
    expect(el.querySelectorAll('input[name="settings-density"]').length).toBe(2);
    expect(el.querySelector('.pref-choice.is-selected .pref-text')?.textContent).toContain('System');
    expect(el.querySelector('.sync-status')?.textContent).toContain('Saved to your account');
  });

  it('applies selections immediately and saves them to the account', async () => {
    await render(['MANAGER']);
    const pick = (name: string, value: string) => {
      const input = [...el.querySelectorAll<HTMLInputElement>(`input[name="${name}"]`)].find((i) => i.value === value)!;
      input.dispatchEvent(new Event('change'));
    };
    pick('settings-mode', 'dark');
    pick('settings-preset', 'emerald');
    pick('settings-density', 'compact');
    await fixture.whenStable();
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
    expect(theme.appearance()).toEqual({ mode: 'dark', preset: 'emerald', density: 'compact' });
    expect([...el.querySelectorAll('.pref-choice.is-selected .pref-text')].map((c) => c.textContent?.trim()))
      .toEqual([expect.stringContaining('Dark'), expect.stringContaining('Emerald'), expect.stringContaining('Compact')]);

    await settle(450);
    const put = http.expectOne((r) => r.url === '/api/preferences' && r.method === 'PUT');
    expect(put.request.body).toEqual({ themeMode: 'DARK', themePreset: 'EMERALD', density: 'COMPACT' });
    put.flush({ ...put.request.body, saved: true });
  });

  it('hides organization settings from non-administrators and never requests them', async () => {
    for (const roles of [['USER'], ['MANAGER']] as Role[][]) {
      TestBed.resetTestingModule();
      await render(roles);
      expect(el.querySelector('.settings-org')).toBeNull();
      http.expectNone('/api/settings/organization');
    }
  });

  it('lets administrators edit organization settings with validation and the version last read', async () => {
    await render(['ADMIN']);
    http.expectOne('/api/settings/organization').flush(ORG);
    await fixture.whenStable();
    const name = el.querySelector<HTMLInputElement>('#org-name')!;
    const days = el.querySelector<HTMLInputElement>('#org-window')!;
    const save = el.querySelector<HTMLButtonElement>('.settings-org button[type="submit"]')!;
    expect(name.value).toBe('Enterprise Admin');
    expect(save.disabled).toBe(true);

    days.value = '400';
    days.dispatchEvent(new Event('input'));
    days.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    save.click();
    await fixture.whenStable();
    expect(el.textContent).toContain('Enter a whole number from 1 to 365.');
    http.expectNone((r) => r.method === 'PUT');

    days.value = '14';
    days.dispatchEvent(new Event('input'));
    name.value = 'Acme Corp';
    name.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    save.click();
    const put = http.expectOne((r) => r.url === '/api/settings/organization' && r.method === 'PUT');
    expect(put.request.body).toEqual({ organizationName: 'Acme Corp', recentHireWindowDays: 14, version: 4 });
    put.flush({ ...ORG, organizationName: 'Acme Corp', recentHireWindowDays: 14, version: 5 });
    await fixture.whenStable();
    expect(save.disabled).toBe(true);
  });

  it('explains a concurrent change and reloads the latest values', async () => {
    await render(['ADMIN']);
    http.expectOne('/api/settings/organization').flush(ORG);
    await fixture.whenStable();
    const name = el.querySelector<HTMLInputElement>('#org-name')!;
    name.value = 'Stale edit';
    name.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.settings-org button[type="submit"]')!.click();
    http.expectOne((r) => r.method === 'PUT').flush(
      { status: 409, code: 'STALE_VERSION', message: 'This record was changed by someone else.' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    expect(el.querySelector('.settings-org .alert-warning')?.textContent).toContain('changed by someone else');
    [...el.querySelectorAll<HTMLButtonElement>('.settings-org .alert button')][0].click();
    http.expectOne('/api/settings/organization').flush({ ...ORG, organizationName: 'Renamed elsewhere', version: 6 });
    await fixture.whenStable();
    expect(name.value).toBe('Renamed elsewhere');
  });
});
