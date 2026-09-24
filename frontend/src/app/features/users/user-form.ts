import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Observable, concat, finalize, last } from 'rxjs';

import { UserDetail } from '../../core/api/api.models';
import { UsersApi } from '../../core/api/users.api';
import { ROLES, Role } from '../../core/auth/auth.models';
import { applyServerErrors, parseApiError } from '../../core/http/api-error';
import { Dialog } from '../../shared/ui/dialog/dialog';

const MIN_PASSWORD = 12;

function atLeastOneRole(control: AbstractControl): ValidationErrors | null {
  const roles = control.value as Record<Role, boolean>;
  return Object.values(roles).some(Boolean) ? null : { required: true };
}

/** Create/edit drawer for a system account. Roles and status changes go through their dedicated endpoints. */
@Component({
  selector: 'app-user-form',
  imports: [ReactiveFormsModule, Dialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-form.html',
})
export class UserForm {
  private readonly api = inject(UsersApi);

  readonly open = input.required<boolean>();
  /** `null` = create mode. */
  readonly user = input<UserDetail | null>(null);
  readonly currentUserId = input<number | null>(null);
  readonly saved = output<UserDetail>();
  readonly closed = output<void>();

  protected readonly roleOptions = ROLES;
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly isEdit = computed(() => this.user() !== null);
  protected readonly isSelf = computed(() => this.user()?.id === this.currentUserId());

  protected readonly form = inject(NonNullableFormBuilder).group({
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    initialPassword: ['', [Validators.required, Validators.minLength(MIN_PASSWORD), Validators.maxLength(72)]],
    roles: inject(NonNullableFormBuilder).group({ ADMIN: false, MANAGER: false, USER: true }, { validators: atLeastOneRole }),
    enabled: true,
  });

  constructor() {
    effect(() => {
      if (!this.open()) {
        return;
      }
      const user = this.user();
      this.submitted.set(false);
      this.formError.set(null);
      this.form.reset({
        firstName: user?.firstName ?? '',
        lastName: user?.lastName ?? '',
        email: user?.email ?? '',
        initialPassword: '',
        roles: { ADMIN: !!user?.roles.includes('ADMIN'), MANAGER: !!user?.roles.includes('MANAGER'), USER: user ? user.roles.includes('USER') : true },
        enabled: user?.enabled ?? true,
      });
      const password = this.form.controls.initialPassword;
      if (user) {
        password.disable();
      } else {
        password.enable();
      }
      // Guard rails mirrored from the server: an admin cannot demote or disable themselves.
      const self = user !== null && user.id === this.currentUserId();
      const adminRole = this.form.controls.roles.controls.ADMIN;
      if (self && user?.roles.includes('ADMIN')) {
        adminRole.disable();
        this.form.controls.enabled.disable();
      } else {
        adminRole.enable();
        this.form.controls.enabled.enable();
      }
    });
  }

  protected showError(path: string): boolean {
    const control = this.form.get(path);
    return !!control && control.invalid && (control.touched || this.submitted());
  }

  protected selectedRoles(): Role[] {
    const roles = this.form.controls.roles.getRawValue();
    return ROLES.filter((role) => roles[role]);
  }

  protected submit(): void {
    this.submitted.set(true);
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const roles = this.selectedRoles();
    const current = this.user();
    let request: Observable<UserDetail>;

    if (!current) {
      request = this.api.create({
        firstName: value.firstName,
        lastName: value.lastName,
        email: value.email,
        initialPassword: value.initialPassword,
        roles,
        enabled: value.enabled,
      });
    } else {
      const steps: Observable<UserDetail>[] = [
        this.api.update(current.id, { firstName: value.firstName, lastName: value.lastName, email: value.email }),
      ];
      const rolesChanged = [...roles].sort().join() !== [...current.roles].sort().join();
      if (rolesChanged) {
        steps.push(this.api.updateRoles(current.id, roles));
      }
      if (value.enabled !== current.enabled) {
        steps.push(this.api.updateStatus(current.id, value.enabled));
      }
      request = concat(...steps).pipe(last());
    }

    this.saving.set(true);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (user) => {
        this.form.controls.initialPassword.reset();
        this.saved.emit(user);
      },
      error: (error: unknown) => {
        const parsed = parseApiError(error);
        const unmatched = applyServerErrors(this.form, parsed);
        if (parsed.fieldErrors.length === 0 || unmatched.length > 0) {
          this.formError.set(parsed.message);
        }
      },
    });
  }
}
