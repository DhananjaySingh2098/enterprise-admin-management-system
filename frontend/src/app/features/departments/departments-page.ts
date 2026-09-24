import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs';

import { Department } from '../../core/api/api.models';
import { DepartmentsApi } from '../../core/api/departments.api';
import { AuthService } from '../../core/auth/auth.service';
import { parseApiError } from '../../core/http/api-error';
import { ListQuery, pagedResource } from '../../shared/data/list-query';
import { Badge } from '../../shared/ui/badge/badge';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { Icon } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { Pagination } from '../../shared/ui/pagination/pagination';
import { SortHeader } from '../../shared/ui/sort-header/sort-header';
import { ToastService } from '../../shared/ui/toast/toast.service';
import { DepartmentForm } from './department-form';

@Component({
  selector: 'app-departments-page',
  imports: [ReactiveFormsModule, Badge, ConfirmDialog, EmptyState, Icon, PageHeader, Pagination, SortHeader, DepartmentForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-page-header heading="Departments"
                       [subtitle]="isAdmin() ? 'Organise employees into departments.' : 'Departments in your organisation.'">
        @if (isAdmin()) {
          <button type="button" class="btn btn-primary" (click)="openForm(null)"><app-icon name="plus" [size]="16" /> Add department</button>
        }
      </app-page-header>

      <section class="card" aria-label="Departments">
        <div class="toolbar" role="search">
          <label class="search-field">
            <span class="visually-hidden">Search departments</span>
            <app-icon name="search" [size]="16" />
            <input class="input" type="search" [formControl]="search" placeholder="Search name or code" />
          </label>
          <label>
            <span class="visually-hidden">Filter by status</span>
            <select class="select" (change)="setActiveFilter($any($event.target).value)">
              <option value="">All statuses</option>
              <option value="true">Active</option>
              <option value="false">Inactive</option>
            </select>
          </label>
        </div>

        @if (departments.error(); as error) {
          <div class="card-section"><div class="alert alert-danger" role="alert">{{ error }}</div></div>
        }

        <div class="table-wrap">
          <table class="data-table responsive" [attr.aria-busy]="departments.loading()">
            <caption class="visually-hidden">Departments</caption>
            <thead>
              <tr>
                <th appSortHeader="name" [query]="query">Name</th>
                <th appSortHeader="code" [query]="query">Code</th>
                <th scope="col">Description</th>
                <th scope="col">Headcount</th>
                <th appSortHeader="status" [query]="query">Status</th>
                @if (isAdmin()) { <th scope="col" class="col-actions"><span class="visually-hidden">Actions</span></th> }
              </tr>
            </thead>
            <tbody>
              @for (d of departments.data()?.content ?? []; track d.id) {
                <tr>
                  <td class="cell-primary" data-label="Name"><strong>{{ d.name }}</strong></td>
                  <td data-label="Code" class="cell-mono">{{ d.code }}</td>
                  <td data-label="Description" class="cell-muted">{{ d.description || '—' }}</td>
                  <td data-label="Headcount" [attr.title]="'Active and on-leave employees'">{{ d.headcount }}</td>
                  <td data-label="Status"><app-badge [dot]="true" [tone]="d.active ? 'success' : 'neutral'">{{ d.active ? 'Active' : 'Inactive' }}</app-badge></td>
                  @if (isAdmin()) {
                    <td class="col-actions">
                      <span class="row-actions">
                        <button type="button" class="btn btn-ghost btn-sm" (click)="openForm(d)" [attr.aria-label]="'Edit ' + d.name">
                          <app-icon name="pencil" [size]="15" /> Edit
                        </button>
                        <button type="button" class="btn btn-ghost btn-sm" (click)="toggleTarget.set(d); toggleError.set(null)"
                                [attr.aria-label]="(d.active ? 'Deactivate ' : 'Activate ') + d.name">
                          <app-icon name="power" [size]="15" /> {{ d.active ? 'Deactivate' : 'Activate' }}
                        </button>
                      </span>
                    </td>
                  }
                </tr>
              } @empty {
                @if (!departments.loading()) {
                  <tr><td [attr.colspan]="isAdmin() ? 6 : 5">
                    <app-empty-state heading="No departments found" icon="building" message="Adjust the search or filters." />
                  </td></tr>
                }
              }
            </tbody>
          </table>
        </div>
        @if (departments.data(); as page) {
          <div class="table-footer">
            <app-pagination label="Departments pagination" [page]="page.page" [size]="page.size" [total]="page.totalElements"
                            [totalPages]="page.totalPages" (pageChange)="query.setPage($event)" (sizeChange)="query.setSize($event)" />
          </div>
        }
      </section>
    </div>

    @if (isAdmin()) {
      <app-department-form [open]="formOpen()" [department]="editing()" (saved)="onSaved($event)" (closed)="formOpen.set(false)" />
    }
    <app-confirm-dialog [open]="toggleTarget() !== null" [busy]="toggleBusy()" [error]="toggleError()"
                        [heading]="toggleTarget()?.active ? 'Deactivate department?' : 'Activate department?'"
                        [message]="toggleTarget()?.active
                          ? 'Existing employees stay assigned, but no new employees can be added to it.'
                          : 'Employees can be assigned to this department again.'"
                        [confirmLabel]="toggleTarget()?.active ? 'Deactivate' : 'Activate'"
                        [tone]="toggleTarget()?.active ? 'danger' : 'primary'"
                        (confirmed)="confirmToggle()" (cancelled)="toggleTarget.set(null)" />
  `,
})
export class DepartmentsPage {
  private readonly api = inject(DepartmentsApi);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(['ADMIN']));
  /** `?new=1` opens the create drawer (ADMIN only). */
  readonly new = input<string | undefined>();
  protected readonly query = new ListQuery('name');
  protected readonly departments = pagedResource(this.query, (params) => this.api.list(params));
  protected readonly search = new FormControl('', { nonNullable: true });
  /** `?search=` from the header search pre-fills the (server-side) search box. */
  readonly searchParam = input<string | undefined>(undefined, { alias: 'search' });
  protected readonly formOpen = signal(false);
  protected readonly editing = signal<Department | null>(null);
  protected readonly toggleTarget = signal<Department | null>(null);
  protected readonly toggleBusy = signal(false);
  protected readonly toggleError = signal<string | null>(null);

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
      if (this.new() && this.isAdmin()) {
        untracked(() => this.openForm(null));
      }
    });
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((term) => this.query.setSearch(term));
  }

  protected setActiveFilter(value: string): void {
    this.query.setFilter('active', value === '' ? null : value === 'true');
  }

  protected openForm(department: Department | null): void {
    this.editing.set(department);
    this.formOpen.set(true);
  }

  protected onSaved(department: Department): void {
    this.toast.success(this.editing() ? 'Department updated.' : `${department.name} was added.`);
    this.formOpen.set(false);
    this.query.reload();
  }

  protected confirmToggle(): void {
    const target = this.toggleTarget();
    if (!target) {
      return;
    }
    this.toggleBusy.set(true);
    this.api
      .setActive(target.id, !target.active)
      .pipe(finalize(() => this.toggleBusy.set(false)))
      .subscribe({
        next: (d) => {
          this.toggleTarget.set(null);
          this.toast.success(d.active ? 'Department activated.' : 'Department deactivated.');
          this.query.reload();
        },
        error: (error: unknown) => this.toggleError.set(parseApiError(error).message),
      });
  }
}
