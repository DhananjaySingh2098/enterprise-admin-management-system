import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Icon } from '../../shared/ui/icon/icon';

const ROLE_LABEL = { ADMIN: 'Administrator', MANAGER: 'Manager', USER: 'User' } as const;

/** Header account control: identity + a compact disclosure menu (Profile · Settings · Appearance · Sign out). */
@Component({
  selector: 'app-account-menu',
  imports: [RouterLink, Avatar, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'account-menu', '(document:click)': 'onDocumentClick($event)', '(keydown.escape)': 'close(true)' },
  template: `
    <button #trigger type="button" class="account-trigger" (click)="open.set(!open())" aria-haspopup="true"
            [attr.aria-expanded]="open()" aria-controls="account-panel" [attr.aria-label]="'Account menu for ' + auth.displayName()">
      <app-avatar [name]="auth.displayName()" size="sm" />
      <span class="account-text">
        <strong>{{ auth.displayName() }}</strong>
        <span>{{ roleLabel() }}</span>
      </span>
      <app-icon class="account-caret" name="chevrons-up-down" [size]="14" />
    </button>
    @if (open()) {
      <div class="account-panel" id="account-panel">
        <div class="account-summary">
          <app-avatar [name]="auth.displayName()" />
          <div>
            <strong>{{ auth.displayName() }}</strong>
            <span>{{ auth.user()?.email }}</span>
          </div>
        </div>
        <ul class="account-actions">
          <li><a routerLink="/profile" (click)="close()"><app-icon name="user" [size]="16" /> Profile</a></li>
          <li><a routerLink="/settings" (click)="close()"><app-icon name="settings" [size]="16" /> Settings</a></li>
          <li><button type="button" (click)="$event.stopPropagation(); close(); appearance.emit()"><app-icon name="monitor" [size]="16" /> Appearance</button></li>
          <li class="is-separated">
            <button type="button" class="is-danger" (click)="close(); signOut.emit()" aria-label="Sign out">
              <app-icon name="log-out" [size]="16" /> Sign out
            </button>
          </li>
        </ul>
      </div>
    }
  `,
})
export class AccountMenu {
  protected readonly auth = inject(AuthService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  readonly appearance = output<void>();
  readonly signOut = output<void>();

  protected readonly open = signal(false);
  protected readonly roleLabel = computed(() => this.auth.roles().map((role) => ROLE_LABEL[role]).join(' · '));

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
