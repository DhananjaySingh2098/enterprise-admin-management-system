import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppNotification } from '../../core/api/api.models';
import { NotificationCenter } from '../../core/notifications/notification-center';
import { NOTIFICATION_STYLE } from '../../shared/format/labels';
import { absoluteTime, relativeTime } from '../../shared/format/relative-time';
import { Icon } from '../../shared/ui/icon/icon';
import { ToastService } from '../../shared/ui/toast/toast.service';

/**
 * Header bell: unread badge (animates once when the count rises) and a popover with the newest notifications.
 * Only real, server-generated notifications are shown; there is no placeholder content.
 */
@Component({
  selector: 'app-notification-bell',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'notification-bell', '(document:click)': 'onDocumentClick($event)', '(keydown.escape)': 'close(true)' },
  template: `
    <button #trigger type="button" class="icon-btn bell-trigger" (click)="toggle()" aria-haspopup="true"
            [attr.aria-expanded]="open()" aria-controls="notification-panel" [attr.aria-label]="triggerLabel()"
            [class.has-unread]="center.unread() > 0" [attr.data-bump]="bumpPhase()">
      <app-icon class="bell-icon" name="bell" [size]="18" />
      @if (center.unread() > 0) {
        <span class="bell-badge" aria-hidden="true">{{ badgeText() }}</span>
      }
    </button>
    @if (open()) {
      <div class="notification-panel" id="notification-panel" role="region" aria-label="Notifications">
        <header class="notification-panel-head">
          <div>
            <strong>Notifications</strong>
            <span>{{ center.unread() === 0 ? 'You are all caught up' : center.unread() + ' unread' }}</span>
          </div>
          <button type="button" class="btn btn-ghost btn-sm" (click)="markAll()" [disabled]="center.unread() === 0 || busy()">
            <app-icon name="check" [size]="14" /> Mark all as read
          </button>
        </header>

        @if (center.recent(); as items) {
          @if (items.length) {
            <ul class="notification-list" aria-live="polite">
              @for (n of items; track n.id; let i = $index) {
                <li class="notification-item" [class.is-unread]="!n.read" [style.--i]="i">
                  <span class="notification-icon" [attr.data-tone]="style[n.type].tone" aria-hidden="true">
                    <app-icon [name]="style[n.type].icon" [size]="15" />
                  </span>
                  <div class="notification-body">
                    <p class="notification-title">
                      @if (!n.read) { <span class="unread-dot"><span class="visually-hidden">Unread: </span></span> }
                      {{ n.title }}
                    </p>
                    <p class="notification-message">{{ n.message }}</p>
                    <time [attr.datetime]="n.createdAt" [attr.title]="absolute(n.createdAt)">{{ relative(n.createdAt) }}</time>
                  </div>
                  @if (!n.read) {
                    <button type="button" class="icon-btn notification-read" (click)="markRead(n)" [attr.aria-label]="'Mark as read: ' + n.title">
                      <app-icon name="check" [size]="14" />
                    </button>
                  }
                </li>
              }
            </ul>
          } @else {
            <div class="notification-empty">
              <span class="empty-orb" aria-hidden="true"><app-icon name="bell" [size]="20" /></span>
              <strong>No notifications yet</strong>
              <span>Changes to your account and records will appear here.</span>
            </div>
          }
        } @else if (center.recentError()) {
          <p class="notification-empty" role="alert">Notifications could not be loaded.</p>
        } @else {
          <ul class="notification-list" aria-busy="true" aria-label="Loading notifications">
            @for (i of [0, 1, 2]; track i) { <li class="notification-item is-skeleton"><span class="skeleton"></span></li> }
          </ul>
        }

        <a class="notification-footer link-arrow" routerLink="/notifications" (click)="close()">
          View all notifications <app-icon name="chevron-right" [size]="14" />
        </a>
      </div>
    }
  `,
})
export class NotificationBell {
  protected readonly center = inject(NotificationCenter);
  private readonly toast = inject(ToastService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  protected readonly style = NOTIFICATION_STYLE;
  protected readonly open = signal(false);
  protected readonly busy = signal(false);
  protected readonly badgeText = computed(() => (this.center.unread() > 99 ? '99+' : String(this.center.unread())));
  protected readonly triggerLabel = computed(() =>
    this.center.unread() === 0 ? 'Notifications' : `Notifications, ${this.center.unread()} unread`,
  );
  /** Alternates between two identical keyframes so each rise in the count replays the animation once. */
  protected readonly bumpPhase = computed(() => (this.center.bump() === 0 ? null : this.center.bump() % 2 ? 'a' : 'b'));
  protected readonly relative = relativeTime;
  protected readonly absolute = absoluteTime;

  protected toggle(): void {
    if (this.open()) {
      this.close();
    } else {
      this.open.set(true);
      this.center.loadRecent();
    }
  }

  close(restoreFocus = false): void {
    if (this.open()) {
      this.open.set(false);
      if (restoreFocus) {
        this.trigger().nativeElement.focus();
      }
    }
  }

  protected markRead(notification: AppNotification): void {
    this.center.markRead(notification).subscribe({ error: () => this.toast.error('Could not mark the notification as read.') });
  }

  protected markAll(): void {
    this.busy.set(true);
    this.center.markAllRead().subscribe({
      next: () => this.busy.set(false),
      error: () => {
        this.busy.set(false);
        this.toast.error('Could not mark notifications as read.');
      },
    });
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }
}
