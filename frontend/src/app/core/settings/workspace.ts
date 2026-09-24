import { Injectable, inject, signal } from '@angular/core';

import { SettingsApi } from '../api/settings.api';

/** The organization name shown in the header, loaded once per shell and updated when an admin renames it. */
@Injectable({ providedIn: 'root' })
export class Workspace {
  private readonly api = inject(SettingsApi);
  private readonly nameSignal = signal<string | null>(null);
  readonly organizationName = this.nameSignal.asReadonly();

  load(): void {
    this.api.workspace().subscribe({ next: (w) => this.nameSignal.set(w.organizationName), error: () => undefined });
  }

  set(name: string): void {
    this.nameSignal.set(name);
  }
}
