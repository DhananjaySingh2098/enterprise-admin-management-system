import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, computed, effect, inject, signal } from '@angular/core';

export type ThemeMode = 'system' | 'light' | 'dark';
export type ThemePreset = 'aurora' | 'obsidian' | 'pearl' | 'midnight' | 'emerald';
export type Density = 'comfortable' | 'compact';

export const DENSITIES: readonly { value: Density; label: string; description: string }[] = [
  { value: 'comfortable', label: 'Comfortable', description: 'Generous spacing, the default' },
  { value: 'compact', label: 'Compact', description: 'Tighter rows, cards and navigation' },
];

/** A complete appearance choice, as stored locally and on the account. */
export interface Appearance {
  mode: ThemeMode;
  preset: ThemePreset;
  density: Density;
}

export const THEME_MODES: readonly { value: ThemeMode; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
];

export const THEME_PRESETS: readonly { value: ThemePreset; label: string; description: string }[] = [
  { value: 'aurora', label: 'Aurora', description: 'Cool neutrals, indigo & violet' },
  { value: 'obsidian', label: 'Obsidian', description: 'Graphite with restrained violet' },
  { value: 'pearl', label: 'Pearl', description: 'Warm ivory & champagne' },
  { value: 'midnight', label: 'Midnight', description: 'Deep navy & cobalt' },
  { value: 'emerald', label: 'Emerald', description: 'Forest neutrals & emerald' },
];

/** Where preferences are remembered. Defaults to localStorage when the browser allows it; `null` = not remembered. */
export const THEME_STORAGE = new InjectionToken<Pick<Storage, 'getItem' | 'setItem'> | null>('THEME_STORAGE', {
  providedIn: 'root',
  factory: () => {
    try {
      return typeof localStorage !== 'undefined' ? localStorage : null;
    } catch {
      return null; // access can throw when storage is blocked
    }
  },
});

// Keep in sync with the boot script in index.html.
export const MODE_KEY = 'enterprise-admin:theme';
export const PRESET_KEY = 'enterprise-admin:preset';
export const DENSITY_KEY = 'enterprise-admin:density';
/** When the user last changed appearance in this browser (ISO time); reconciles against the account's updatedAt. */
export const CHANGED_AT_KEY = 'enterprise-admin:appearance-changed-at';

/**
 * Appearance (system/light/dark → `data-theme`), visual preset (→ `data-preset`) and density (→ `data-density`) on
 * <html>; all are plain token overrides. Values are remembered per browser (applied before first paint by the boot
 * script in index.html) and, when signed in, synced to the account by {@link PreferencesSync}.
 *
 * `userChanges` counts changes made by the user in this tab — the sync persists those; values applied from the
 * server via {@link applyRemote} do not count, so they are never echoed back.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly storage = inject(THEME_STORAGE);

  readonly mode = signal<ThemeMode>(this.read(MODE_KEY, ['light', 'dark'], 'system'));
  readonly preset = signal<ThemePreset>(this.read(PRESET_KEY, ['aurora', 'obsidian', 'pearl', 'midnight', 'emerald'], 'aurora'));
  readonly density = signal<Density>(this.read(DENSITY_KEY, ['compact'], 'comfortable'));
  readonly appearance = computed<Appearance>(() => ({ mode: this.mode(), preset: this.preset(), density: this.density() }));
  private readonly userChangeCount = signal(0);
  readonly userChanges = this.userChangeCount.asReadonly();
  readonly label = computed(() => `${THEME_MODES.find((m) => m.value === this.mode())!.label} appearance`);

  constructor() {
    effect(() => {
      const mode = this.mode();
      const root = this.document.documentElement;
      if (mode === 'system') {
        root.removeAttribute('data-theme');
      } else {
        root.setAttribute('data-theme', mode);
      }
      this.write(MODE_KEY, mode);
    });
    effect(() => {
      const preset = this.preset();
      this.document.documentElement.setAttribute('data-preset', preset);
      this.write(PRESET_KEY, preset);
    });
    effect(() => {
      const density = this.density();
      const root = this.document.documentElement;
      if (density === 'compact') {
        root.setAttribute('data-density', 'compact');
      } else {
        root.removeAttribute('data-density');
      }
      this.write(DENSITY_KEY, density);
    });
  }

  setMode(mode: ThemeMode): void {
    this.userChange(() => this.mode.set(mode));
  }

  setPreset(preset: ThemePreset): void {
    this.userChange(() => this.preset.set(preset));
  }

  setDensity(density: Density): void {
    this.userChange(() => this.density.set(density));
  }

  /**
   * Applies values from the account (another device may have changed them). Transitions are suppressed for one
   * frame so the switch is instant rather than an animated flash; no-op when nothing differs.
   */
  applyRemote(appearance: Appearance): void {
    if (appearance.mode === this.mode() && appearance.preset === this.preset() && appearance.density === this.density()) {
      return;
    }
    const root = this.document.documentElement;
    root.classList.add('theme-syncing');
    this.mode.set(appearance.mode);
    this.preset.set(appearance.preset);
    this.density.set(appearance.density);
    const view = this.document.defaultView;
    const release = () => root.classList.remove('theme-syncing');
    if (view?.requestAnimationFrame) {
      view.requestAnimationFrame(() => view.requestAnimationFrame(release));
    } else {
      release();
    }
  }

  /** Time of the last appearance change made by the user in this browser, if known. */
  localChangedAt(): number | null {
    try {
      const value = Date.parse(this.storage?.getItem(CHANGED_AT_KEY) ?? '');
      return Number.isNaN(value) ? null : value;
    } catch {
      return null;
    }
  }

  /** Called once the account holds this browser's choice: the local marker is no longer needed. */
  markSynced(): void {
    this.write(CHANGED_AT_KEY, '');
  }

  private userChange(apply: () => void): void {
    apply();
    this.write(CHANGED_AT_KEY, new Date().toISOString());
    this.userChangeCount.update((n) => n + 1);
  }

  private read<T extends string>(key: string, allowed: readonly string[], fallback: T): T {
    try {
      const value = this.storage?.getItem(key) ?? null;
      return value !== null && allowed.includes(value) ? (value as T) : fallback;
    } catch {
      return fallback;
    }
  }

  private write(key: string, value: string): void {
    try {
      this.storage?.setItem(key, value);
    } catch {
      // Storage unavailable (private mode, blocked): the choice simply isn't remembered.
    }
  }
}
