import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { catchError, debounceTime, distinctUntilChanged, of } from 'rxjs';

import { EMPLOYEE_STATUSES, EmployeeDetail, EmployeeSummary } from '../../core/api/api.models';
import { DepartmentsApi } from '../../core/api/departments.api';
import { EmployeesApi } from '../../core/api/employees.api';
import { AuthService } from '../../core/auth/auth.service';
import { ListQuery, pagedResource } from '../../shared/data/list-query';
import { EMPLOYEE_STATUS_LABEL, EMPLOYEE_STATUS_TONE } from '../../shared/format/labels';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Badge } from '../../shared/ui/badge/badge';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { Icon } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { Pagination } from '../../shared/ui/pagination/pagination';
import { SortHeader } from '../../shared/ui/sort-header/sort-header';
import { ToastService } from '../../shared/ui/toast/toast.service';
import { EmployeeForm, EmployeeFormMode } from './employee-form';

@Component({
  selector: 'app-employees-page',
  imports: [ReactiveFormsModule, DatePipe, Avatar, Badge, EmptyState, Icon, PageHeader, Pagination, SortHeader, EmployeeForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './employees-page.html',
})
export class EmployeesPage {
  private readonly api = inject(EmployeesApi);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  /** UX mirror of the server rules: ADMIN/MANAGER write, everyone reads, ADMIN changes status. */
  protected readonly canEdit = computed(() => this.auth.hasAnyRole(['ADMIN', 'MANAGER']));
  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(['ADMIN']));

  protected readonly statuses = EMPLOYEE_STATUSES;
  protected readonly statusLabel = EMPLOYEE_STATUS_LABEL;
  protected readonly statusTone = EMPLOYEE_STATUS_TONE;
  protected readonly query = new ListQuery('name');
  protected readonly employees = pagedResource(this.query, (params) => this.api.list(params));
  protected readonly departments = toSignal(inject(DepartmentsApi).options().pipe(catchError(() => of([]))), { initialValue: [] });
  protected readonly search = new FormControl('', { nonNullable: true });
  /** `?search=` from the header search pre-fills the (server-side) search box. */
  readonly searchParam = input<string | undefined>(undefined, { alias: 'search' });

  /** `?new=1` (e.g. from the dashboard) opens the create drawer for roles that may create. */
  readonly new = input<string | undefined>();
  /** `?open=<id>` opens that record (edit for ADMIN/MANAGER, read-only view for USER). */
  readonly open = input<string | undefined>();

  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<EmployeeFormMode>('create');
  protected readonly selectedId = signal<number | null>(null);

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
    effect(() => {
      if (this.new() && this.canEdit()) {
        untracked(() => this.openCreate());
      }
    });
    effect(() => {
      const id = Number(this.open());
      if (Number.isInteger(id) && id > 0) {
        untracked(() => {
          this.selectedId.set(id);
          this.formMode.set(this.canEdit() ? 'edit' : 'view');
          this.formOpen.set(true);
        });
      }
    });
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((term) => this.query.setSearch(term));
  }

  protected fullName(e: EmployeeSummary): string {
    return `${e.firstName} ${e.lastName}`;
  }

  protected openCreate(): void {
    this.selectedId.set(null);
    this.formMode.set('create');
    this.formOpen.set(true);
  }

  protected openRecord(e: EmployeeSummary): void {
    this.selectedId.set(e.id);
    this.formMode.set(this.canEdit() ? 'edit' : 'view');
    this.formOpen.set(true);
  }

  protected onSaved(employee: EmployeeDetail): void {
    const created = this.formMode() === 'create';
    this.formOpen.set(false);
    this.toast.success(created ? `${employee.firstName} ${employee.lastName} was added.` : 'Employee updated.');
    this.query.reload();
  }
}
