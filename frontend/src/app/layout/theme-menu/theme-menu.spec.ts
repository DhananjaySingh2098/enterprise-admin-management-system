import { TestBed } from '@angular/core/testing';

import { CHANGED_AT_KEY, DENSITY_KEY, MODE_KEY, PRESET_KEY, ThemeService } from '../../core/theme/theme.service';
import { ThemeMenu } from './theme-menu';

describe('ThemeMenu', () => {
  // Appearance is remembered per browser, and spec files can share that storage depending on how the runner
  // isolates them. Start from the documented defaults so this spec tests the menu, not what ran before it.
  beforeEach(() => {
    for (const key of [MODE_KEY, PRESET_KEY, DENSITY_KEY, CHANGED_AT_KEY]) {
      try {
        localStorage.removeItem(key);
      } catch {
        /* storage can be unavailable; the service then falls back to its defaults anyway */
      }
    }
  });

  it('is an accessible popover with appearance and preset radio groups', async () => {
    const fixture = TestBed.createComponent(ThemeMenu);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const trigger = el.querySelector<HTMLButtonElement>('button[aria-haspopup]')!;
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(trigger.getAttribute('aria-label')).toContain('Theme settings');

    trigger.click();
    await fixture.whenStable();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect([...el.querySelectorAll('legend')].map((l) => l.textContent)).toEqual(['Appearance', 'Visual style']);
    expect(el.querySelectorAll('input[name="theme-mode"]').length).toBe(3);
    expect([...el.querySelectorAll('.preset strong')].map((s) => s.textContent)).toEqual(['Aurora', 'Obsidian', 'Pearl', 'Midnight', 'Emerald']);
    expect(el.querySelectorAll('.preset .preset-check').length).toBe(5);
    expect(el.querySelector('.preset.is-selected strong')?.textContent).toBe('Aurora');

    const midnight = [...el.querySelectorAll<HTMLInputElement>('input[name="theme-preset"]')].find((i) => i.value === 'emerald')!;
    midnight.dispatchEvent(new Event('change'));
    const dark = [...el.querySelectorAll<HTMLInputElement>('input[name="theme-mode"]')].find((i) => i.value === 'dark')!;
    dark.dispatchEvent(new Event('change'));
    const theme = TestBed.inject(ThemeService);
    expect(theme.preset()).toBe('emerald');
    await fixture.whenStable();
    expect(el.querySelector('.preset.is-selected strong')?.textContent).toBe('Emerald');
    expect(theme.mode()).toBe('dark');

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(el.querySelector('.theme-panel')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    theme.setMode('system');
    theme.setPreset('aurora');
  });

  it('exposes a light/dark pill with pressed state', async () => {
    const fixture = TestBed.createComponent(ThemeMenu);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const theme = TestBed.inject(ThemeService);
    const buttons = [...el.querySelectorAll<HTMLButtonElement>('.theme-pill button')];
    expect(buttons.length).toBe(2);
    buttons[1].click();
    await fixture.whenStable();
    expect(theme.mode()).toBe('dark');
    expect(buttons[1].getAttribute('aria-pressed')).toBe('true');
    expect(buttons[0].getAttribute('aria-pressed')).toBe('false');
    theme.setMode('system');
  });
});
