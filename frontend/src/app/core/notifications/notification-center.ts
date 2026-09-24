import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, filter, fromEvent, map, merge, switchMap, tap, timer } from 'rxjs';

import { AppNotification } from '../api/api.models';
import { NotificationsApi } from '../api/notifications.api';
import { AuthService } from '../auth/auth.service';

/** How often the unread count is refreshed while the tab is visible. */
export const UNREAD_POLL_MS = 60_000;
const RECENT_SIZE = 6;

/**
 * Client state for the signed-in user's notifications, shared by the header bell and the notifications page.
 *
 * The unread count is refreshed on start, every minute while the tab is visible (no requests from hidden tabs)
 * and when the tab becomes visible again. Marking as read is optimistic and rolled back if the server refuses.
 * `bump` increments whenever the unread count rises, so the bell can animate once for genuinely new items.
 */
@Injectable({ providedIn: 'root' })
export class NotificationCenter {
  private readonly api = inject(NotificationsApi);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);

  private readonly unreadSignal = signal(0);
  private readonly recentSignal = signal<AppNotification[] | null>(null);
  private readonly bumpSignal = signal(0);
  private readonly recentErrorSignal = signal(false);
  private connected = false;

  readonly unread = this.unreadSignal.asReadonly();
  readonly recent = this.recentSignal.asReadonly();
  readonly recentError = this.recentErrorSignal.asReadonly();
  readonly bump = this.bumpSignal.asReadonly();

  /** Starts polling for the lifetime of the caller (the app shell). Idempotent. */
  connect(destroyRef: DestroyRef): void {
    if (this.connected) {
      return;
    }
    this.connected = true;
    destroyRef.onDestroy(() => (this.connected = false));
    const visible = () => this.document.visibilityState !== 'hidden';
    merge(
      timer(0, UNREAD_POLL_MS).pipe(filter(visible)),
      fromEvent(this.document, 'visibilitychange').pipe(filter(visible)),
    )
      .pipe(
        filter(() => this.auth.isAuthenticated()),
        switchMap(() => this.api.unreadCount()),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe({ next: ({ count }) => this.setUnread(count), error: () => undefined });
  }

  refreshCount(): void {
    this.api.unreadCount().subscribe({ next: ({ count }) => this.setUnread(count), error: () => undefined });
  }

  /** Newest notifications for the header popover. */
  loadRecent(): void {
    this.recentErrorSignal.set(false);
    this.api.list('all', 0, RECENT_SIZE).subscribe({
      next: (page) => this.recentSignal.set(page.content),
      error: () => this.recentErrorSignal.set(true),
    });
    this.refreshCount();
  }

  markRead(notification: AppNotification): Observable<AppNotification> {
    const optimistic = { ...notification, read: true };
    this.patch(optimistic);
    if (!notification.read) {
      this.unreadSignal.update((n) => Math.max(0, n - 1));
    }
    return this.api.markRead(notification.id).pipe(
      tap({
        next: (saved) => this.patch(saved),
        error: () => {
          this.patch(notification);
          this.refreshCount();
        },
      }),
    );
  }

  markAllRead(): Observable<number> {
    const before = this.recentSignal();
    this.recentSignal.update((list) => list?.map((n) => ({ ...n, read: true })) ?? null);
    const unreadBefore = this.unreadSignal();
    this.unreadSignal.set(0);
    return this.api.markAllRead().pipe(
      tap({
        next: ({ unread }) => this.unreadSignal.set(unread),
        error: () => {
          this.recentSignal.set(before);
          this.unreadSignal.set(unreadBefore);
        },
      }),
      map(({ updated }) => updated),
    );
  }

  private setUnread(count: number): void {
    if (count > this.unreadSignal()) {
      this.bumpSignal.update((n) => n + 1);
    }
    this.unreadSignal.set(count);
  }

  private patch(updated: AppNotification): void {
    this.recentSignal.update((list) => list?.map((n) => (n.id === updated.id ? updated : n)) ?? null);
  }
}
