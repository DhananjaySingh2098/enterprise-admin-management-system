import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { settle, signInAs, testProviders } from '../auth/auth.testing';
import { PreferencesSync, fromServer, toServer } from './preferences-sync';
import { THEME_STORAGE, ThemeService } from './theme.service';

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), data };
}

describe('PreferencesSync', () => {
  let http: HttpTestingController;
  let storage: ReturnType<typeof memoryStorage>;

  function setup(local: Record<string, string> = {}) {
    storage = memoryStorage(local);
    TestBed.configureTestingModule({ providers: [...testProviders(), { provide: THEME_STORAGE, useValue: storage }] });
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => {
    http.verify();
    TestBed.inject(ThemeService).applyRemote({ mode: 'system', preset: 'aurora', density: 'comfortable' });
  });

  const prefsRequest = () => http.expectOne((r) => r.url === '/api/preferences' && r.method === 'GET');

  it('maps between client and server values', () => {
    setup();
    expect(toServer({ mode: 'dark', preset: 'midnight', density: 'compact' })).toEqual({ themeMode: 'DARK', themePreset: 'MIDNIGHT', density: 'COMPACT' });
    expect(fromServer({ themeMode: 'LIGHT', themePreset: 'PEARL', density: 'COMFORTABLE', saved: true })).toEqual({ mode: 'light', preset: 'pearl', density: 'comfortable' });
  });

  it('applies saved account preferences after sign-in and keeps them locally', async () => {
    setup({ 'enterprise-admin:theme': 'light' });
    const sync = TestBed.inject(PreferencesSync);
    const theme = TestBed.inject(ThemeService);
    await signInAs(['USER']);
    await settle();
    prefsRequest().flush({ themeMode: 'DARK', themePreset: 'EMERALD', density: 'COMPACT', saved: true });

    expect(theme.appearance()).toEqual({ mode: 'dark', preset: 'emerald', density: 'compact' });
    expect(sync.state()).toBe('synced');
    await settle();
    expect(storage.data.get('enterprise-admin:theme')).toBe('dark');
    expect(storage.data.get('enterprise-admin:density')).toBe('compact');
    // Applying server values is not a user change: nothing is sent back.
    await settle(450);
    http.expectNone((r) => r.method === 'PUT');
  });

  it('adopts this browser’s choice when the account has never saved preferences', async () => {
    setup({ 'enterprise-admin:theme': 'dark', 'enterprise-admin:preset': 'obsidian' });
    TestBed.inject(PreferencesSync);
    await signInAs(['USER']);
    await settle();
    prefsRequest().flush({ themeMode: 'SYSTEM', themePreset: 'AURORA', density: 'COMFORTABLE', saved: false });
    const put = http.expectOne((r) => r.url === '/api/preferences' && r.method === 'PUT');
    expect(put.request.body).toEqual({ themeMode: 'DARK', themePreset: 'OBSIDIAN', density: 'COMFORTABLE' });
    put.flush({ ...put.request.body, saved: true });
  });

  it('saves user changes to the account, debounced into one request', async () => {
    setup();
    const theme = TestBed.inject(ThemeService);
    TestBed.inject(PreferencesSync);
    await signInAs(['MANAGER']);
    await settle();
    prefsRequest().flush({ themeMode: 'SYSTEM', themePreset: 'AURORA', density: 'COMFORTABLE', saved: true });

    theme.setMode('dark');
    theme.setPreset('pearl');
    theme.setDensity('compact');
    TestBed.tick();
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
    await settle(450);
    const put = http.expectOne((r) => r.url === '/api/preferences' && r.method === 'PUT');
    expect(put.request.body).toEqual({ themeMode: 'DARK', themePreset: 'PEARL', density: 'COMPACT' });
    put.flush({ ...put.request.body, saved: true });
  });

  it('keeps a newer local choice made while the account preferences were loading', async () => {
    setup();
    const theme = TestBed.inject(ThemeService);
    TestBed.inject(PreferencesSync);
    await signInAs(['USER']);
    await settle();
    const pending = prefsRequest();
    theme.setPreset('midnight');
    pending.flush({ themeMode: 'LIGHT', themePreset: 'EMERALD', density: 'COMFORTABLE', saved: true });
    expect(theme.preset()).toBe('midnight');
    await settle(450);
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body.themePreset).toBe('MIDNIGHT');
    put.flush({ ...put.request.body, saved: true });
  });

  it('never calls the API while signed out and keeps working locally on errors', async () => {
    setup();
    const theme = TestBed.inject(ThemeService);
    const sync = TestBed.inject(PreferencesSync);
    theme.setMode('dark');
    await settle(450);
    http.expectNone('/api/preferences');
    expect(sync.state()).toBe('local');

    await signInAs(['USER']);
    await settle();
    prefsRequest().flush({ message: 'down' }, { status: 503, statusText: 'Unavailable' });
    expect(sync.state()).toBe('error');
    expect(theme.mode()).toBe('dark');
  });

  it('a newer local change (whose save was lost) wins over older account values and is pushed', async () => {
    setup({ 'enterprise-admin:preset': 'pearl', 'enterprise-admin:appearance-changed-at': '2026-09-22T10:00:05Z' });
    const theme = TestBed.inject(ThemeService);
    TestBed.inject(PreferencesSync);
    await signInAs(['USER']);
    await settle();
    prefsRequest().flush({ themeMode: 'DARK', themePreset: 'EMERALD', density: 'COMFORTABLE', saved: true, updatedAt: '2026-09-22T10:00:00Z' });
    expect(theme.preset()).toBe('pearl');
    const put = http.expectOne((r) => r.url === '/api/preferences' && r.method === 'PUT');
    expect(put.request.body.themePreset).toBe('PEARL');
    put.flush({ ...put.request.body, saved: true, updatedAt: '2026-09-22T10:00:06Z' });
    expect(storage.data.get('enterprise-admin:appearance-changed-at')).toBe('');
  });

  it('an older local change yields to newer account values', async () => {
    setup({ 'enterprise-admin:preset': 'pearl', 'enterprise-admin:appearance-changed-at': '2026-09-01T00:00:00Z' });
    const theme = TestBed.inject(ThemeService);
    TestBed.inject(PreferencesSync);
    await signInAs(['USER']);
    await settle();
    prefsRequest().flush({ themeMode: 'DARK', themePreset: 'EMERALD', density: 'COMFORTABLE', saved: true, updatedAt: '2026-09-22T10:00:00Z' });
    expect(theme.preset()).toBe('emerald');
    http.expectNone((r) => r.method === 'PUT');
  });

  it('a save still pending when the page is hidden is flushed with a keepalive request', async () => {
    setup();
    const theme = TestBed.inject(ThemeService);
    TestBed.inject(PreferencesSync);
    await signInAs(['USER']);
    await settle();
    prefsRequest().flush({ themeMode: 'SYSTEM', themePreset: 'AURORA', density: 'COMFORTABLE', saved: true, updatedAt: '2026-09-22T10:00:00Z' });
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 200 }));
    theme.setPreset('obsidian');
    window.dispatchEvent(new Event('pagehide'));
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/preferences');
    expect(init.keepalive).toBe(true);
    expect(JSON.parse(String(init.body)).themePreset).toBe('OBSIDIAN');
    fetchSpy.mockRestore();
    await settle(450);
    http.expectNone((r) => r.method === 'PUT'); // already flushed: the debounced save does not repeat it
  });
});
