import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, inject, Injector, signal, viewChild } from '@angular/core';

import { THEME_MODES, THEME_PRESETS, ThemeService } from '../../core/theme/theme.service';
import { Icon } from '../../shared/ui/icon/icon';

const MODE_ICON = { system: 'monitor', light: 'sun', dark: 'moon' } as const;

/** Header popover for appearance and visual style. Native radio groups give keyboard and screen-reader support. */
@Component({
  selector: 'app-theme-menu',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'theme-menu',
    '(document:click)': 'onDocumentClick($event)',
    '(keydown.escape)': 'close(true)',
  },
  template: `
    <div class="theme-pill" role="group" aria-label="Appearance">
      <span class="theme-pill-thumb" [attr.data-mode]="effectiveMode()" aria-hidden="true"></span>
      <button type="button" class="theme-pill-btn" [attr.aria-pressed]="effectiveMode() === 'light'" (click)="theme.setMode('light')" aria-label="Light appearance">
        <app-icon name="sun" [size]="15" />
      </button>
      <button type="button" class="theme-pill-btn" [attr.aria-pressed]="effectiveMode() === 'dark'" (click)="theme.setMode('dark')" aria-label="Dark appearance">
        <app-icon name="moon" [size]="15" />
      </button>
    </div>
    <button #trigger type="button" class="icon-btn theme-more" (click)="toggle()" aria-haspopup="true" [attr.aria-expanded]="open()"
            aria-controls="theme-panel" [attr.aria-label]="'Theme settings: ' + theme.label()" [attr.title]="'Theme settings'">
      <app-icon name="monitor" [size]="16" />
    </button>
    @if (open()) {
      <div #panel class="theme-panel" id="theme-panel" role="dialog" aria-label="Theme settings">
        <fieldset class="theme-group">
          <legend>Appearance</legend>
          <div class="segmented">
            @for (mode of modes; track mode.value) {
              <label class="segment" [class.is-selected]="theme.mode() === mode.value">
                <input type="radio" name="theme-mode" [value]="mode.value" [checked]="theme.mode() === mode.value"
                       (change)="theme.setMode(mode.value)" />
                <app-icon [name]="modeIcon[mode.value]" [size]="15" />
                {{ mode.label }}
              </label>
            }
          </div>
        </fieldset>
        <fieldset class="theme-group">
          <legend>Visual style</legend>
          <div class="preset-grid">
            @for (preset of presets; track preset.value) {
              <label class="preset" [class.is-selected]="theme.preset() === preset.value">
                <input type="radio" name="theme-preset" [value]="preset.value" [checked]="theme.preset() === preset.value"
                       (change)="theme.setPreset(preset.value)" />
                <span class="preset-swatch" [attr.data-swatch]="preset.value" aria-hidden="true">
                  <span></span><span></span><span></span>
                </span>
                <span class="preset-text"><strong>{{ preset.label }}</strong><span>{{ preset.description }}</span></span>
                <span class="preset-check" aria-hidden="true"><app-icon name="check" [size]="14" /></span>
              </label>
            }
          </div>
        </fieldset>
      </div>
    }
  `,
})
export class ThemeMenu {
  protected readonly theme = inject(ThemeService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  protected readonly modes = THEME_MODES;
  protected readonly presets = THEME_PRESETS;
  protected readonly modeIcon = MODE_ICON;
  protected readonly open = signal(false);
  protected readonly icon = computed(() => MODE_ICON[this.theme.mode()]);
  /** What is actually showing (System resolves through prefers-color-scheme). */
  protected readonly effectiveMode = computed(() => {
    const mode = this.theme.mode();
    if (mode !== 'system') {
      return mode;
    }
    return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  protected toggle(): void {
    this.open.update((v) => !v);
  }

  /** Opens the panel and moves focus to the selected appearance option (used by the account menu). */
  openPanel(): void {
    this.open.set(true);
    afterNextRender(
      () => (this.host.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[name="theme-mode"]:checked')?.focus(),
      { injector: this.injector },
    );
  }

  close(restoreFocus = false): void {
    if (this.open()) {
      this.open.set(false);
      if (restoreFocus) {
        this.trigger().nativeElement.focus();
      }
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }
}
