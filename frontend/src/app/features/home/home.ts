import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { HealthService } from '../../core/health/health.service';
import { visibleNavItems } from '../../layout/navigation/navigation';
import { Badge } from '../../shared/ui/badge/badge';
import { Icon } from '../../shared/ui/icon/icon';
import { ConnectionState, StatusBadge } from '../../shared/ui/status-badge/status-badge';

const DESCRIPTIONS: Record<string, string> = {
  '/users': 'Manage accounts, roles and access.',
  '/employees': 'Browse and maintain employee records.',
  '/departments': 'See how the organisation is structured.',
  '/profile': 'Your details and password.',
};

/** Personal workspace for accounts without management analytics (USER): a welcome and role-aware shortcuts. */
@Component({
  selector: 'app-home',
  imports: [RouterLink, Badge, Icon, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (auth.user(); as user) {
      <div class="page">
        <section class="welcome card" aria-labelledby="welcome-title">
          <div>
            <p class="eyebrow">Enterprise Admin</p>
            <h1 id="welcome-title">Welcome, {{ user.firstName }}</h1>
            <p class="welcome-meta">
              <span>{{ user.email }}</span>
              <span class="badge-list">
                @for (role of user.roles; track role) { <app-badge class="role" tone="primary">{{ role }}</app-badge> }
              </span>
            </p>
          </div>
          <app-status-badge label="API" [state]="apiState()" />
        </section>

        <section aria-labelledby="shortcuts-title">
          <h2 class="section-title" id="shortcuts-title">Quick access</h2>
          <ul class="shortcuts">
            @for (item of shortcuts(); track item.path) {
              <li>
                <a class="shortcut card card-interactive" [routerLink]="item.path">
                  <span class="shortcut-icon"><app-icon [name]="item.icon" [size]="20" /></span>
                  <span class="shortcut-text"><strong>{{ item.label }}</strong><span>{{ describe(item.path) }}</span></span>
                  <app-icon name="chevron-right" [size]="16" />
                </a>
              </li>
            }
          </ul>
        </section>
      </div>
    }
  `,
})
export class Home {
  protected readonly auth = inject(AuthService);
  private readonly health = inject(HealthService);

  protected readonly shortcuts = computed(() => visibleNavItems(this.auth.roles()).filter((item) => item.path !== '/'));

  protected readonly apiState = toSignal(
    this.health.check().pipe(
      map((response): ConnectionState => (response.status === 'UP' ? 'online' : 'offline')),
      catchError(() => of<ConnectionState>('offline')),
    ),
    { initialValue: 'checking' as ConnectionState },
  );

  protected describe(path: string): string {
    return DESCRIPTIONS[path] ?? '';
  }
}
