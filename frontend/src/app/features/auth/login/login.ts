import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';

import { safeReturnUrl } from '../../../core/auth/auth.guards';
import { AuthService } from '../../../core/auth/auth.service';
import { HeroStage } from '../../../shared/ui/hero-stage/hero-stage';
import { Icon } from '../../../shared/ui/icon/icon';
import { SpotlightDirective } from '../../../shared/ui/spotlight/spotlight.directive';

/** Only same-app absolute paths are accepted as post-login destinations (prevents open redirects). */
export { safeReturnUrl };

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, HeroStage, Icon, SpotlightDirective],
  templateUrl: './login.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Bound from the `?returnUrl=` query parameter by the router. */
  readonly returnUrl = input<string | undefined>();

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
  });

  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly passwordVisible = signal(false);

  private readonly emailInput = viewChild.required<ElementRef<HTMLInputElement>>('emailInput');
  private readonly passwordInput = viewChild.required<ElementRef<HTMLInputElement>>('passwordInput');

  protected showError(control: 'email' | 'password'): boolean {
    const c = this.form.controls[control];
    return c.invalid && (c.touched || this.submitted());
  }

  protected togglePasswordVisibility(): void {
    this.passwordVisible.update((visible) => !visible);
  }

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    this.submitted.set(true);
    this.errorMessage.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      (this.form.controls.email.invalid ? this.emailInput() : this.passwordInput()).nativeElement.focus();
      return;
    }

    const { email, password } = this.form.getRawValue();
    this.submitting.set(true);
    this.auth
      .login(email, password)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
        error: (error: unknown) => {
          this.errorMessage.set(messageFor(error));
          // Clear the password for the retry without flagging the now-empty field as an error.
          this.submitted.set(false);
          this.form.controls.password.reset();
          this.passwordInput().nativeElement.focus();
        },
      });
  }
}

function messageFor(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 400 || error.status === 401) {
      return 'Invalid email or password.';
    }
    if (error.status === 429) {
      return 'Too many sign-in attempts. Please wait a few minutes and try again.';
    }
  }
  return 'We couldn’t sign you in right now. Please try again.';
}
