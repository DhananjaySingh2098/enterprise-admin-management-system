import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { finalize } from 'rxjs';

import { Profile } from '../../core/api/api.models';
import { ProfileApi } from '../../core/api/profile.api';
import { AuthService } from '../../core/auth/auth.service';
import { applyServerErrors, parseApiError } from '../../core/http/api-error';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Badge } from '../../shared/ui/badge/badge';
import { Icon } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { ToastService } from '../../shared/ui/toast/toast.service';

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const { newPassword, confirmPassword } = group.value as { newPassword: string; confirmPassword: string };
  return confirmPassword && newPassword !== confirmPassword ? { mismatch: true } : null;
}

@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule, DatePipe, Avatar, Badge, Icon, PageHeader],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './profile-page.html',
})
export class ProfilePage {
  private readonly api = inject(ProfileApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly profile = signal<Profile | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingDetails = signal(false);
  protected readonly detailsSubmitted = signal(false);
  protected readonly detailsError = signal<string | null>(null);
  protected readonly savingPassword = signal(false);
  protected readonly passwordSubmitted = signal(false);
  protected readonly passwordError = signal<string | null>(null);

  protected readonly details = this.fb.group({
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
  });

  protected readonly password = this.fb.group(
    {
      currentPassword: ['', [Validators.required]],
      newPassword: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(72)]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordsMatch },
  );

  constructor() {
    this.api.get().subscribe({
      next: (profile) => this.setProfile(profile),
      error: (error: unknown) => this.loadError.set(parseApiError(error).message),
    });
  }

  protected fullName(p: Profile): string {
    return `${p.firstName} ${p.lastName}`.trim();
  }

  protected detailError(name: 'firstName' | 'lastName' | 'email'): boolean {
    const c = this.details.controls[name];
    return c.invalid && (c.touched || this.detailsSubmitted());
  }

  protected passwordFieldError(name: 'currentPassword' | 'newPassword' | 'confirmPassword'): boolean {
    const c = this.password.controls[name];
    const mismatch = name === 'confirmPassword' && this.password.hasError('mismatch');
    return (c.invalid || mismatch) && (c.touched || this.passwordSubmitted());
  }

  protected saveDetails(): void {
    this.detailsSubmitted.set(true);
    this.detailsError.set(null);
    if (this.details.invalid) {
      this.details.markAllAsTouched();
      return;
    }
    const v = this.details.getRawValue();
    this.savingDetails.set(true);
    this.api
      .update({ firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim() })
      .pipe(finalize(() => this.savingDetails.set(false)))
      .subscribe({
        next: (profile) => {
          this.setProfile(profile);
          this.auth.updateCurrentUser({ firstName: profile.firstName, lastName: profile.lastName, email: profile.email });
          this.toast.success('Profile updated.');
        },
        error: (error: unknown) => {
          const parsed = parseApiError(error);
          if (applyServerErrors(this.details, parsed).length > 0 || parsed.fieldErrors.length === 0) {
            this.detailsError.set(parsed.message);
          }
        },
      });
  }

  protected changePassword(): void {
    this.passwordSubmitted.set(true);
    this.passwordError.set(null);
    if (this.password.invalid) {
      this.password.markAllAsTouched();
      return;
    }
    this.savingPassword.set(true);
    this.api
      .changePassword(this.password.getRawValue())
      .pipe(finalize(() => this.savingPassword.set(false)))
      .subscribe({
        next: (session) => {
          this.auth.acceptSession(session);
          this.password.reset();
          this.passwordSubmitted.set(false);
          this.toast.success('Password changed. Your other sessions were signed out.');
        },
        error: (error: unknown) => {
          const parsed = parseApiError(error);
          if (parsed.status === 429) {
            this.passwordError.set('Too many attempts. Please wait a few minutes and try again.');
            return;
          }
          if (applyServerErrors(this.password, parsed).length > 0 || parsed.fieldErrors.length === 0) {
            this.passwordError.set(parsed.message);
          }
        },
      });
  }

  private setProfile(profile: Profile): void {
    this.profile.set(profile);
    this.details.reset({ firstName: profile.firstName, lastName: profile.lastName, email: profile.email });
    this.detailsSubmitted.set(false);
  }
}
