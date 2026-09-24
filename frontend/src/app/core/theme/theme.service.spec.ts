import { TestBed } from '@angular/core/testing';

import { DENSITY_KEY, MODE_KEY, PRESET_KEY, THEME_STORAGE, ThemeService } from './theme.service';

describe('ThemeService', () => {
  const root = document.documentElement;
  let store: Record<string, string>;

  beforeEach(() => {
    store = {};
    root.removeAttribute('data-theme');
    root.removeAttribute('data-preset');
    root.removeAttribute('data-density');
    TestBed.configureTestingModule({
      providers: [{ provide: THEME_STORAGE, useValue: { getItem: (k: string) => store[k] ?? null, setItem: (k: string, v: string) => (store[k] = v) } }],
    });
  });

  it('defaults to system appearance and the Aurora preset', () => {
    TestBed.inject(ThemeService);
    TestBed.tick();
    expect(root.hasAttribute('data-theme')).toBe(false);
    expect(root.getAttribute('data-preset')).toBe('aurora');
  });

  it('applies and remembers appearance and preset', () => {
    const theme = TestBed.inject(ThemeService);
    theme.setMode('dark');
    theme.setPreset('obsidian');
    TestBed.tick();
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(root.getAttribute('data-preset')).toBe('obsidian');
    expect(store[MODE_KEY]).toBe('dark');
    expect(store[PRESET_KEY]).toBe('obsidian');
    theme.setMode('system');
    TestBed.tick();
    expect(root.hasAttribute('data-theme')).toBe(false);
  });

  it('restores saved values and ignores tampered ones', () => {
    store[MODE_KEY] = 'light';
    store[PRESET_KEY] = 'neon';
    const theme = TestBed.inject(ThemeService);
    expect(theme.mode()).toBe('light');
    expect(theme.preset()).toBe('aurora');
  });

  it('keeps working when storage is unavailable', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: THEME_STORAGE, useValue: null }] });
    const theme = TestBed.inject(ThemeService);
    theme.setPreset('pearl');
    TestBed.tick();
    expect(root.getAttribute('data-preset')).toBe('pearl');
  });

  it('applies, remembers and restores density', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.density()).toBe('comfortable');
    theme.setDensity('compact');
    TestBed.tick();
    expect(root.getAttribute('data-density')).toBe('compact');
    expect(store[DENSITY_KEY]).toBe('compact');
    theme.setDensity('comfortable');
    TestBed.tick();
    expect(root.hasAttribute('data-density')).toBe(false);

    // A reload reads the stored value (the boot script applies it before first paint).
    store[DENSITY_KEY] = 'compact';
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: THEME_STORAGE, useValue: { getItem: (k: string) => store[k] ?? null, setItem: (k: string, v: string) => (store[k] = v) } }],
    });
    expect(TestBed.inject(ThemeService).density()).toBe('compact');
  });

  it('counts user changes but not values applied from the account', () => {
    const theme = TestBed.inject(ThemeService);
    theme.setMode('dark');
    expect(theme.userChanges()).toBe(1);
    theme.applyRemote({ mode: 'light', preset: 'pearl', density: 'compact' });
    expect(theme.userChanges()).toBe(1);
    expect(theme.appearance()).toEqual({ mode: 'light', preset: 'pearl', density: 'compact' });
    expect(root.classList.contains('theme-syncing')).toBe(true);
  });
});
