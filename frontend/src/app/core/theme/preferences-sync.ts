import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, Observable, catchError, debounceTime, filter, skip, switchMap, tap } from 'rxjs';

import { Preferences, ServerDensity, ServerThemeMode, ServerThemePreset } from '../api/api.models';
import { SettingsApi } from '../api/settings.api';
import { AuthService } from '../auth/auth.service';
import { ApiService } from '../http/api.service';
import { Appearance, Density, ThemeMode, ThemePreset, ThemeService } from './theme.service';

export type SyncState = 'local' | 'syncing' | 'synced' | 'error';

export function toServer(a: Appearance): { themeMode: ServerThemeMode; themePreset: ServerThemePreset; density: ServerDensity } {
  return {
    themeMode: a.mode.toUpperCase() as ServerThemeMode,
    themePreset: a.preset.toUpperCase() as ServerThemePreset,
    density: a.density.toUpperCase() as ServerDensity,
  };
}

export function fromServer(p: Preferences): Appearance {
  return {
    mode: p.themeMode.toLowerCase() as ThemeMode,
    preset: p.themePreset.toLowerCase() as ThemePreset,
    density: p.density.toLowerCase() as Density,
  };
}

/**
 * Keeps the signed-in user's appearance in sync with their account.
 *
 * 1. Startup: the boot script and ThemeService apply the browser-local choice before first paint (no flash).
 * 2. After the session is known, the account's preferences are fetched once per user:
 *    - saved on the account → applied (instantly, transitions suppressed), unless the user already changed
 *      something in this tab meanwhile — the newer local choice wins and is saved instead;
 *    - never saved → this browser's current choice is adopted as the account's first value.
 * 3. Every user change is applied and stored locally at once, then saved to the account (debounced). A save still
 *    pending when the page is hidden or unloaded is sent immediately with a keepalive request.
 * 4. Reconciliation is by time: a local change newer than the account's `updatedAt` (e.g. one whose save was lost
 *    when the tab closed) wins and is pushed; otherwise the account wins.
 * Failures never block the UI: the local choice stays in effect and the state reads "error".
 */
@Injectable({ providedIn: 'root' })
export class PreferencesSync {
  private readonly theme = inject(ThemeService);
  private readonly auth = inject(AuthService);
  private readonly api = inject(SettingsApi);
  private readonly apiUrls = inject(ApiService);
  private readonly document = inject(DOCUMENT);
  /** The user-change count last sent to the account; anything above it is an unsaved local change. */
  private savedChanges = 0;

  private readonly stateSignal = signal<SyncState>('local');
  readonly state = this.stateSignal.asReadonly();
  private syncedUserId: number | null = null;

  constructor() {
    effect(() => {
      const user = this.auth.user();
      const authenticated = this.auth.isAuthenticated();
      untracked(() => {
        if (!authenticated || !user) {
          this.syncedUserId = null;
          this.stateSignal.set('local');
        } else if (user.id !== this.syncedUserId) {
          this.syncedUserId = user.id;
          this.pull();
        }
      });
    });

    const destroyRef = inject(DestroyRef);
    toObservable(this.theme.userChanges)
      .pipe(
        skip(1),
        debounceTime(400),
        filter(() => this.auth.isAuthenticated() && this.theme.userChanges() !== this.savedChanges),
        switchMap(() => this.push()),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe();

    const onPageHide = () => this.flushPendingSave();
    const onVisibility = () => this.document.visibilityState === 'hidden' && this.flushPendingSave();
    this.document.defaultView?.addEventListener('pagehide', onPageHide);
    this.document.addEventListener('visibilitychange', onVisibility);
    destroyRef.onDestroy(() => {
      this.document.defaultView?.removeEventListener('pagehide', onPageHide);
      this.document.removeEventListener('visibilitychange', onVisibility);
    });
  }

  /** Sends a pending (debounced) save right away; keepalive lets it complete while the page unloads. */
  private flushPendingSave(): void {
    const token = this.auth.getAccessToken();
    if (this.theme.userChanges() === this.savedChanges || !token || typeof fetch !== 'function') {
      return;
    }
    this.savedChanges = this.theme.userChanges();
    void fetch(this.apiUrls.url('preferences'), {
      method: 'PUT',
      keepalive: true,
      credentials: 'same-origin',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      body: JSON.stringify(toServer(this.theme.appearance())),
    }).catch(() => undefined);
  }

  private pull(): void {
    const changesAtStart = this.theme.userChanges();
    this.stateSignal.set('syncing');
    this.api.preferences().subscribe({
      next: (prefs) => {
        if (this.theme.userChanges() !== changesAtStart) {
          return; // a newer local choice is already being saved
        }
        const localAt = this.theme.localChangedAt();
        const serverAt = prefs.updatedAt ? Date.parse(prefs.updatedAt) : 0;
        if (prefs.saved && !(localAt !== null && localAt > serverAt)) {
          this.theme.applyRemote(fromServer(prefs));
          this.stateSignal.set('synced');
        } else {
          // Never saved on the account, or this browser holds a newer choice whose save was lost: adopt local.
          this.push().subscribe();
        }
      },
      error: () => this.stateSignal.set('error'),
    });
  }

  private push(): Observable<unknown> {
    this.stateSignal.set('syncing');
    this.savedChanges = this.theme.userChanges();
    return this.api.savePreferences(toServer(this.theme.appearance())).pipe(
      tap(() => {
        this.stateSignal.set('synced');
        this.theme.markSynced();
      }),
      catchError(() => {
        this.stateSignal.set('error');
        return EMPTY;
      }),
    );
  }
}
