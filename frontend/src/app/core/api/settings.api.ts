import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiService } from '../http/api.service';
import { OrganizationSettings, Preferences, ServerDensity, ServerThemeMode, ServerThemePreset } from './api.models';

@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly api = inject(ApiService);

  /** Any signed-in user: their own appearance preferences. */
  preferences(): Observable<Preferences> {
    return this.api.get('preferences');
  }

  savePreferences(body: { themeMode: ServerThemeMode; themePreset: ServerThemePreset; density: ServerDensity }): Observable<Preferences> {
    return this.api.put('preferences', body);
  }

  /** Any signed-in user: only the organization name. */
  workspace(): Observable<{ organizationName: string }> {
    return this.api.get('settings/workspace');
  }

  /** ADMIN only. */
  organization(): Observable<OrganizationSettings> {
    return this.api.get('settings/organization');
  }

  /** ADMIN only; `version` is the value last read (409 STALE_VERSION otherwise). */
  saveOrganization(body: { organizationName: string; recentHireWindowDays: number; version: number }): Observable<OrganizationSettings> {
    return this.api.put('settings/organization', body);
  }
}
