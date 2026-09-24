import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppNotification, PageResponse } from '../../core/api/api.models';
import { NotificationsApi } from '../../core/api/notifications.api';
import { parseApiError } from '../../core/http/api-error';
import { NotificationCenter } from '../../core/notifications/notification-center';
import { NOTIFICATION_STYLE } from '../../shared/format/labels';
import { absoluteTime, relativeTime } from '../../shared/format/relative-time';
import { Icon } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { Pagination } from '../../shared/ui/pagination/pagination';
import { RevealDirective } from '../../shared/ui/reveal/reveal.directive';
import { ToastService } from '../../shared/ui/toast/toast.service';

type Tab = 'all' | 'unread';

/** Full list of the signed-in user's notifications as a compact timeline, with All / Unread views. */
@Component({
  selector: 'app-notifications-page',
  imports: [RouterLink, Icon, PageHeader, Pagination, RevealDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './notifications-page.html',
})
export class NotificationsPage {
  private readonly api = inject(NotificationsApi);
  private readonly toast = inject(ToastService);
  protected readonly center = inject(NotificationCenter);

  protected readonly tab = signal<Tab>('all');
  protected readonly page = signal(0);
  protected readonly size = signal(20);
  protected readonly data = signal<PageResponse<AppNotification> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** Ids fading out of the Unread view after being marked read. */
  protected readonly leaving = signal<ReadonlySet<number>>(new Set());

  protected readonly style = NOTIFICATION_STYLE;
  protected readonly relative = relativeTime;
  protected readonly absolute = absoluteTime;
  protected readonly items = computed(() => this.data()?.content ?? []);

  constructor() {
    this.load();
  }

  protected setTab(tab: Tab): void {
    if (tab !== this.tab()) {
      this.tab.set(tab);
      this.page.set(0);
      this.load();
    }
  }

  protected setPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected setSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.api.list(this.tab(), this.page(), this.size()).subscribe({
      next: (page) => {
        this.data.set(page);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(parseApiError(error).message);
        this.loading.set(false);
      },
    });
    this.center.refreshCount();
  }

  protected markRead(n: AppNotification): void {
    this.data.update((page) => page && { ...page, content: page.content.map((x) => (x.id === n.id ? { ...x, read: true } : x)) });
    if (this.tab() === 'unread') {
      this.leaving.update((set) => new Set(set).add(n.id));
    }
    this.center.markRead(n).subscribe({
      next: () => {
        if (this.tab() === 'unread') {
          // Let the fade finish, then drop it from the unread list.
          setTimeout(() => this.load(), 260);
        }
      },
      error: () => {
        this.toast.error('Could not mark the notification as read.');
        this.load();
      },
    });
  }

  protected markAll(): void {
    this.busy.set(true);
    this.center.markAllRead().subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.toast.success(updated === 1 ? '1 notification marked as read.' : `${updated} notifications marked as read.`);
        this.load();
      },
      error: () => {
        this.busy.set(false);
        this.toast.error('Could not mark notifications as read.');
      },
    });
  }

  /** Where a notification's subject can be viewed, if the user may open it. */
  protected link(n: AppNotification): { path: string; query: Record<string, string> } | null {
    if (n.relatedEntityType === 'EMPLOYEE' && n.relatedEntityId) {
      return { path: '/employees', query: { open: n.relatedEntityId } };
    }
    if (n.type === 'PASSWORD_CHANGED' || n.type === 'ACCOUNT_DETAILS_CHANGED' || n.type === 'SECURITY_ALERT') {
      return { path: '/profile', query: {} };
    }
    return null;
  }

  protected dayLabel(iso: string): string {
    const date = new Date(iso);
    const today = new Date();
    const yesterday = new Date();
    yesterday.setDate(today.getDate() - 1);
    if (date.toDateString() === today.toDateString()) {
      return 'Today';
    }
    if (date.toDateString() === yesterday.toDateString()) {
      return 'Yesterday';
    }
    return date.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
  }

  /** True when this item starts a new day group. */
  protected startsDay(index: number): boolean {
    const list = this.items();
    return index === 0 || new Date(list[index].createdAt).toDateString() !== new Date(list[index - 1].createdAt).toDateString();
  }
}
