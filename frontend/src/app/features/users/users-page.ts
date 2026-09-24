import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs';

import { UserDetail, UserSummary } from '../../core/api/api.models';
import { UsersApi } from '../../core/api/users.api';
import { ROLES } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { parseApiError } from '../../core/http/api-error';
import { ListQuery, pagedResource } from '../../shared/data/list-query';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Badge } from '../../shared/ui/badge/badge';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { Icon } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { Pagination } from '../../shared/ui/pagination/pagination';
import { SortHeader } from '../../shared/ui/sort-header/sort-header';
import { ToastService } from '../../shared/ui/toast/toast.service';
import { UserForm } from './user-form';

@Component({
  selector: 'app-users-page',
  imports: [ReactiveFormsModule, DatePipe, Avatar, Badge, ConfirmDialog, EmptyState, Icon, PageHeader, Pagination, SortHeader, UserForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './users-page.html',
})
export class UsersPage {
  private readonly api = inject(UsersApi);
  private readonly toast = inject(ToastService);
  protected readonly auth = inject(AuthService);

  protected readonly roles = ROLES;
  protected readonly query = new ListQuery('createdAt', 'desc');
  protected readonly users = pagedResource(this.query, (params) => this.api.list(params));
  protected readonly search = new FormControl('', { nonNullable: true });
  /** `?search=` from the header search pre-fills the (server-side) search box. */
  readonly searchParam = input<string | undefined>(undefined, { alias: 'search' });

  protected readonly formOpen = signal(false);
  protected readonly editing = signal<UserDetail | null>(null);
  protected readonly statusTarget = signal<UserSummary | null>(null);
  protected readonly statusBusy = signal(false);
  protected readonly statusError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const term = this.searchParam();
      if (term !== undefined) {
        untracked(() => {
          this.search.setValue(term, { emitEvent: false });
          this.query.setSearch(term);
        });
      }
    });
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((term) => this.query.setSearch(term));
  }

  protected fullName(user: UserSummary): string {
    return `${user.firstName} ${user.lastName}`.trim();
  }

  protected isSelf(user: UserSummary): boolean {
    return user.id === this.auth.user()?.id;
  }

  protected setEnabledFilter(value: string): void {
    this.query.setFilter('enabled', value === '' ? null : value === 'true');
  }

  protected openCreate(): void {
    this.editing.set(null);
    this.formOpen.set(true);
  }

  protected openEdit(user: UserSummary): void {
    this.api.get(user.id).subscribe({
      next: (detail) => {
        this.editing.set(detail);
        this.formOpen.set(true);
      },
      error: (error: unknown) => this.toast.error(parseApiError(error).message),
    });
  }

  protected onSaved(user: UserDetail): void {
    const created = this.editing() === null;
    this.formOpen.set(false);
    this.toast.success(created ? `${user.firstName} ${user.lastName} was added.` : 'User updated.');
    if (user.id === this.auth.user()?.id) {
      this.auth.updateCurrentUser({ firstName: user.firstName, lastName: user.lastName, email: user.email });
    }
    this.query.reload();
  }

  protected askToggleStatus(user: UserSummary): void {
    this.statusError.set(null);
    this.statusTarget.set(user);
  }

  protected confirmToggleStatus(): void {
    const target = this.statusTarget();
    if (!target) {
      return;
    }
    this.statusBusy.set(true);
    this.api
      .updateStatus(target.id, !target.enabled)
      .pipe(finalize(() => this.statusBusy.set(false)))
      .subscribe({
        next: (user) => {
          this.statusTarget.set(null);
          this.toast.success(user.enabled ? 'User enabled.' : 'User disabled and signed out everywhere.');
          this.query.reload();
        },
        error: (error: unknown) => this.statusError.set(parseApiError(error).message),
      });
  }
}
