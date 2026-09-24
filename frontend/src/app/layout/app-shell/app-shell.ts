import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { NotificationCenter } from '../../core/notifications/notification-center';
import { Workspace } from '../../core/settings/workspace';
import { PreferencesSync } from '../../core/theme/preferences-sync';
import { Icon } from '../../shared/ui/icon/icon';
import { ToastHost } from '../../shared/ui/toast/toast-host';
import { AccountMenu } from '../account-menu/account-menu';
import { GlobalSearch } from '../global-search/global-search';
import { visibleNavItems } from '../navigation/navigation';
import { NotificationBell } from '../notification-bell/notification-bell';
import { ThemeMenu } from '../theme-menu/theme-menu';

const ROLE_LABEL = { ADMIN: 'Administrator', MANAGER: 'Manager', USER: 'User' } as const;

/** Authenticated application frame. Every child route is behind authGuard. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, AccountMenu, GlobalSearch, NotificationBell, ThemeMenu, ToastHost],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'navOpen.set(false)' },
  template: `
    <a class="skip-link" href="#main-content">Skip to content</a>
    <div class="shell">
      <aside class="sidebar" [class.is-open]="navOpen()" aria-label="Primary">
        <a class="sidebar-brand" routerLink="/" (click)="navOpen.set(false)">
          <span class="brand-mark" aria-hidden="true">EA</span>
          <span class="brand-text"><strong>Enterprise Admin</strong><span>Manage. People. Grow.</span></span>
        </a>
        <nav class="sidebar-nav" aria-label="Main navigation">
          <span class="nav-slider" aria-hidden="true"></span>
          <ul class="nav-list">
            @for (item of navItems(); track item.path) {
              <li>
                <a class="nav-link" [routerLink]="item.path" routerLinkActive="is-active" ariaCurrentWhenActive="page"
                   [routerLinkActiveOptions]="{ exact: !!item.exact }" (click)="navOpen.set(false)">
                  <span class="nav-icon"><app-icon [name]="item.icon" [size]="18" /></span>
                  <span class="nav-text">{{ item.label }}</span>
                </a>
              </li>
            }
          </ul>
        </nav>

        <div class="sidebar-promo" aria-hidden="true">
          <div class="promo-art">
            <span class="promo-cube"><span></span><span></span><span></span></span>
            <span class="promo-orb"></span>
          </div>
          <p class="promo-title">Build a stronger team together.</p>
          <p class="promo-text">Manage people, streamline operations and drive growth.</p>
        </div>

        <div class="sidebar-help">
          <button type="button" class="help-toggle" (click)="helpOpen.set(!helpOpen())" [attr.aria-expanded]="helpOpen()" aria-controls="help-panel">
            <span class="icon-badge help-badge" aria-hidden="true"><app-icon name="shield" [size]="15" /></span>
            <span class="help-text"><strong>Need help?</strong><span>{{ roleLabel() }} access</span></span>
            <app-icon class="help-caret" name="chevron-right" [size]="15" />
          </button>
          @if (helpOpen()) {
            <p class="help-panel" id="help-panel">
              What you can see and change follows your role. For access changes, contact an administrator.
            </p>
          }
        </div>
      </aside>
      @if (navOpen()) {
        <div class="scrim" (click)="navOpen.set(false)" aria-hidden="true"></div>
      }

      <div class="main-column">
        <header class="topbar">
          <button type="button" class="icon-btn menu-toggle" (click)="navOpen.set(!navOpen())"
                  [attr.aria-expanded]="navOpen()" aria-label="Toggle navigation">
            <app-icon name="menu" />
          </button>
          <div class="topbar-heading">
            <p class="topbar-crumb"><span>{{ workspace.organizationName() ?? 'Workspace' }}</span><app-icon name="chevron-right" [size]="12" /><span>{{ route().section }}</span></p>
            <p class="topbar-title">{{ route().heading }}</p>
          </div>
          <app-global-search />
          <div class="topbar-actions">
            <app-notification-bell />
            <app-theme-menu />
            <span class="topbar-divider" aria-hidden="true"></span>
            <app-account-menu (appearance)="themeMenu().openPanel()" (signOut)="signOut()" />
          </div>
        </header>
        <main id="main-content" class="content" tabindex="-1">
          <router-outlet />
        </main>
      </div>
    </div>
    <app-toast-host />
  `,
})
export class AppShell {
  protected readonly auth = inject(AuthService);
  protected readonly workspace = inject(Workspace);
  private readonly router = inject(Router);
  protected readonly themeMenu = viewChild.required(ThemeMenu);

  protected readonly navOpen = signal(false);
  protected readonly helpOpen = signal(false);
  protected readonly navItems = computed(() => visibleNavItems(this.auth.roles()));
  protected readonly roleLabel = computed(() => this.auth.roles().map((role) => ROLE_LABEL[role]).join(' · '));

  /** Page name and section for the header, from the deepest route's data. */
  protected readonly route = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      startWith(null),
      map(() => {
        let snapshot = this.router.routerState.snapshot.root;
        while (snapshot.firstChild) {
          snapshot = snapshot.firstChild;
        }
        return {
          heading: (snapshot.data['heading'] as string | undefined) ?? 'Enterprise Admin',
          section: (snapshot.data['section'] as string | undefined) ?? 'Overview',
        };
      }),
    ),
    { initialValue: { heading: 'Enterprise Admin', section: 'Overview' } },
  );

  constructor() {
    // Signed-in services: account-synced appearance, notification polling and the organization name.
    inject(PreferencesSync);
    inject(NotificationCenter).connect(inject(DestroyRef));
    this.workspace.load();
  }

  protected signOut(): void {
    this.auth.logout().subscribe(() => void this.router.navigateByUrl('/login'));
  }
}
