import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { OrganizationSettings } from '../../core/api/api.models';
import { SettingsApi } from '../../core/api/settings.api';
import { AuthService } from '../../core/auth/auth.service';
import { parseApiError } from '../../core/http/api-error';
import { NotificationCenter } from '../../core/notifications/notification-center';
import { Workspace } from '../../core/settings/workspace';
import { PreferencesSync } from '../../core/theme/preferences-sync';
import { DENSITIES, Density, THEME_MODES, THEME_PRESETS, ThemeMode, ThemePreset, ThemeService } from '../../core/theme/theme.service';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Badge } from '../../shared/ui/badge/badge';
import { Icon, IconName } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { RevealDirective } from '../../shared/ui/reveal/reveal.directive';
import { SpotlightDirective } from '../../shared/ui/spotlight/spotlight.directive';
import { ToastService } from '../../shared/ui/toast/toast.service';

const MODE_ICON: Record<ThemeMode, IconName> = { system: 'monitor', light: 'sun', dark: 'moon' };
const ROLE_LABEL = { ADMIN: 'Administrator', MANAGER: 'Manager', USER: 'User' } as const;

/**
 * Personal appearance (synced to the account), account shortcuts and — for ADMIN only — organization settings.
 * The organization section is not rendered for other roles, and the API refuses them independently.
 */
@Component({
  selector: 'app-settings-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, Avatar, Badge, Icon, PageHeader, RevealDirective, SpotlightDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings-page.html',
})
export class SettingsPage {
  protected readonly theme = inject(ThemeService);
  protected readonly sync = inject(PreferencesSync);
  protected readonly auth = inject(AuthService);
  protected readonly center = inject(NotificationCenter);
  private readonly api = inject(SettingsApi);
  private readonly workspace = inject(Workspace);
  private readonly toast = inject(ToastService);

  protected readonly modes = THEME_MODES;
  protected readonly presets = THEME_PRESETS;
  protected readonly densities = DENSITIES;
  protected readonly modeIcon = MODE_ICON;
  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(['ADMIN']));
  protected readonly roleLabels = computed(() => this.auth.roles().map((r) => ROLE_LABEL[r]));
  protected readonly syncLabel = computed(() => {
    switch (this.sync.state()) {
      case 'syncing':
        return 'Saving to your account…';
      case 'synced':
        return 'Saved to your account — follows you to other devices';
      case 'error':
        return 'Could not reach the server — kept in this browser for now';
      default:
        return 'Stored in this browser';
    }
  });

  // ------------------------------------------------------------------ organization (ADMIN)
  protected readonly org = signal<OrganizationSettings | null>(null);
  protected readonly orgError = signal<string | null>(null);
  protected readonly orgSaving = signal(false);
  protected readonly orgStale = signal(false);
  protected readonly orgForm = new FormGroup({
    organizationName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120), Validators.pattern(/\S/)] }),
    recentHireWindowDays: new FormControl(30, { nonNullable: true, validators: [Validators.required, Validators.min(1), Validators.max(365)] }),
  });

  constructor() {
    if (this.isAdmin()) {
      this.loadOrganization();
    }
  }

  protected setMode(mode: ThemeMode): void {
    this.theme.setMode(mode);
  }

  protected setPreset(preset: ThemePreset): void {
    this.theme.setPreset(preset);
  }

  protected setDensity(density: Density): void {
    this.theme.setDensity(density);
  }

  protected loadOrganization(): void {
    this.orgError.set(null);
    this.orgStale.set(false);
    this.api.organization().subscribe({
      next: (settings) => this.applyOrg(settings),
      error: (error: unknown) => this.orgError.set(parseApiError(error).message),
    });
  }

  protected saveOrganization(): void {
    const current = this.org();
    if (!current || this.orgForm.invalid) {
      this.orgForm.markAllAsTouched();
      return;
    }
    const value = this.orgForm.getRawValue();
    this.orgSaving.set(true);
    this.orgError.set(null);
    this.api
      .saveOrganization({ organizationName: value.organizationName.trim(), recentHireWindowDays: Number(value.recentHireWindowDays), version: current.version })
      .pipe(finalize(() => this.orgSaving.set(false)))
      .subscribe({
        next: (saved) => {
          this.applyOrg(saved);
          this.workspace.set(saved.organizationName);
          this.toast.success('Organization settings saved.');
        },
        error: (error: unknown) => {
          const parsed = parseApiError(error);
          this.orgStale.set(parsed.code === 'STALE_VERSION');
          this.orgError.set(parsed.message);
        },
      });
  }

  protected resetOrganization(): void {
    const current = this.org();
    if (current) {
      this.applyOrg(current);
    }
  }

  private applyOrg(settings: OrganizationSettings): void {
    this.org.set(settings);
    this.orgForm.reset({ organizationName: settings.organizationName, recentHireWindowDays: settings.recentHireWindowDays });
    this.orgStale.set(false);
  }
}
