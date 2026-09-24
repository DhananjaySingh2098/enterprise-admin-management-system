import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, finalize, of, switchMap } from 'rxjs';

import {
  Department,
  EMPLOYEE_STATUSES,
  EmployeeDetail,
  EmployeeStatus,
  LinkedUserRef,
} from '../../core/api/api.models';
import { DepartmentsApi } from '../../core/api/departments.api';
import { EmployeesApi } from '../../core/api/employees.api';
import { applyServerErrors, parseApiError } from '../../core/http/api-error';
import { EMPLOYEE_STATUS_LABEL } from '../../shared/format/labels';
import { Dialog } from '../../shared/ui/dialog/dialog';
import { UserPicker } from './user-picker';

export type EmployeeFormMode = 'create' | 'edit' | 'view';

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9-]{1,29}$/;
const PHONE_PATTERN = /^[+0-9 ()\-.]{5,40}$/;

/**
 * Create / edit / read-only view of an employee. Edits send the `version` that was loaded; a 409 STALE_VERSION
 * is shown as a conflict with an explicit "Reload latest" action — never retried or overwritten silently.
 */
@Component({
  selector: 'app-employee-form',
  imports: [ReactiveFormsModule, Dialog, UserPicker],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './employee-form.html',
})
export class EmployeeForm {
  private readonly employees = inject(EmployeesApi);
  private readonly departmentsApi = inject(DepartmentsApi);

  readonly open = input.required<boolean>();
  readonly mode = input.required<EmployeeFormMode>();
  readonly employeeId = input<number | null>(null);
  readonly isAdmin = input(false);
  readonly saved = output<EmployeeDetail>();
  readonly closed = output<void>();

  protected readonly statuses = EMPLOYEE_STATUSES;
  protected readonly statusLabel = EMPLOYEE_STATUS_LABEL;
  protected readonly departments = signal<Department[]>([]);
  protected readonly loaded = signal<EmployeeDetail | null>(null);
  protected readonly linkedUser = signal<LinkedUserRef | null>(null);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly conflict = signal(false);
  protected readonly readOnly = computed(() => this.mode() === 'view');
  protected readonly heading = computed(() => ({ create: 'Add employee', edit: 'Edit employee', view: 'Employee details' })[this.mode()]);

  /** Active departments, plus the employee's current one even if it has since been deactivated. */
  protected readonly departmentOptions = computed(() => {
    const current = this.loaded()?.department;
    const active = this.departments().filter((d) => d.active);
    return current && !active.some((d) => d.id === current.id) ? [{ ...current, inactive: true }, ...active] : active;
  });

  protected readonly form = inject(NonNullableFormBuilder).group({
    employeeCode: ['', [Validators.required, Validators.pattern(CODE_PATTERN)]],
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    phone: ['', [Validators.pattern(PHONE_PATTERN)]],
    jobTitle: ['', [Validators.required, Validators.maxLength(120)]],
    departmentId: [null as number | null, [Validators.required]],
    hireDate: ['', [Validators.required]],
    status: ['ACTIVE' as EmployeeStatus],
  });

  constructor() {
    effect(() => {
      const open = this.open();
      const id = this.employeeId();
      const mode = this.mode();
      if (!open) {
        return;
      }
      untracked(() => this.initialise(mode, id));
    });
  }

  protected showError(name: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || this.submitted());
  }

  protected serverError(name: keyof typeof this.form.controls): string | null {
    return (this.form.controls[name].errors?.['server'] as string | undefined) ?? null;
  }

  protected reloadLatest(): void {
    const id = this.employeeId();
    if (id !== null) {
      this.initialise(this.mode(), id);
    }
  }

  protected submit(): void {
    if (this.readOnly()) {
      return;
    }
    this.submitted.set(true);
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const base = {
      employeeCode: v.employeeCode.trim(),
      firstName: v.firstName.trim(),
      lastName: v.lastName.trim(),
      email: v.email.trim(),
      phone: v.phone.trim() || null,
      jobTitle: v.jobTitle.trim(),
      departmentId: v.departmentId as number,
      hireDate: v.hireDate,
      userId: this.linkedUser()?.id ?? null,
    };
    const current = this.loaded();
    let request: Observable<EmployeeDetail>;
    if (!current) {
      request = this.employees.create({ ...base, status: this.isAdmin() ? v.status : 'ACTIVE' });
    } else {
      request = this.employees.update(current.id, { ...base, version: current.version }).pipe(
        switchMap((updated) =>
          this.isAdmin() && v.status !== updated.status
            ? this.employees.updateStatus(updated.id, v.status, updated.version)
            : of(updated),
        ),
      );
    }

    this.saving.set(true);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (employee) => this.saved.emit(employee),
      error: (error: unknown) => {
        const parsed = parseApiError(error);
        if (parsed.code === 'STALE_VERSION') {
          this.conflict.set(true);
          return;
        }
        const unmatched = applyServerErrors(this.form, parsed);
        if (parsed.fieldErrors.length === 0 || unmatched.length > 0) {
          this.formError.set(parsed.message);
        }
      },
    });
  }

  private initialise(mode: EmployeeFormMode, id: number | null): void {
    this.submitted.set(false);
    this.formError.set(null);
    this.conflict.set(false);
    this.loaded.set(null);
    this.linkedUser.set(null);
    this.form.reset({ status: 'ACTIVE', departmentId: null, hireDate: new Date().toISOString().slice(0, 10) });
    this.setEditable(mode !== 'view');

    this.departmentsApi.options().subscribe({
      next: (departments) => this.departments.set(departments),
      error: () => this.departments.set([]),
    });

    if (mode !== 'create' && id !== null) {
      this.loading.set(true);
      this.employees
        .get(id)
        .pipe(finalize(() => this.loading.set(false)))
        .subscribe({
          next: (employee) => {
            this.loaded.set(employee);
            this.linkedUser.set(employee.linkedUser);
            this.form.reset({
              employeeCode: employee.employeeCode,
              firstName: employee.firstName,
              lastName: employee.lastName,
              email: employee.email,
              phone: employee.phone ?? '',
              jobTitle: employee.jobTitle,
              departmentId: employee.department.id,
              hireDate: employee.hireDate,
              status: employee.status,
            });
          },
          error: (error: unknown) => this.formError.set(parseApiError(error).message),
        });
    }
  }

  private setEditable(editable: boolean): void {
    if (editable) {
      this.form.enable();
    } else {
      this.form.disable();
    }
    // Status is ADMIN-only (server-enforced); managers always create ACTIVE records.
    if (!this.isAdmin()) {
      this.form.controls.status.disable();
    }
  }
}
