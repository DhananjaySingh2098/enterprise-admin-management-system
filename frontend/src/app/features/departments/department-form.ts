import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';

import { Department } from '../../core/api/api.models';
import { DepartmentsApi } from '../../core/api/departments.api';
import { applyServerErrors, parseApiError } from '../../core/http/api-error';
import { Dialog } from '../../shared/ui/dialog/dialog';

@Component({
  selector: 'app-department-form',
  imports: [ReactiveFormsModule, Dialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-dialog variant="drawer" [open]="open()" [busy]="saving()" (closed)="closed.emit()"
                [heading]="department() ? 'Edit department' : 'Add department'"
                description="Departments are deactivated rather than deleted, so employee history is preserved.">
      <form id="department-form" [formGroup]="form" (ngSubmit)="submit()" novalidate class="form-grid">
        @if (formError(); as message) { <div class="alert alert-danger span-2" role="alert">{{ message }}</div> }
        <div class="field">
          <label class="field-label" for="d-name">Name <span class="required-mark" aria-hidden="true">*</span></label>
          <input id="d-name" class="input" formControlName="name"
                 [attr.aria-invalid]="showError('name')" [attr.aria-describedby]="showError('name') ? 'd-name-err' : null" />
          @if (showError('name')) { <p class="field-error" id="d-name-err">Name is required.</p> }
        </div>
        <div class="field">
          <label class="field-label" for="d-code">Code <span class="required-mark" aria-hidden="true">*</span></label>
          <input id="d-code" class="input cell-mono" formControlName="code" spellcheck="false" autocomplete="off"
                 [attr.aria-invalid]="showError('code')" aria-describedby="d-code-hint" />
          <p class="field-hint" id="d-code-hint">2–20 letters, digits, “-” or “_”. Stored in upper case.</p>
          @if (showError('code')) {
            <p class="field-error">{{ form.controls.code.errors?.['server'] ?? 'Enter a valid code.' }}</p>
          }
        </div>
        <div class="field span-2">
          <label class="field-label" for="d-desc">Description</label>
          <textarea id="d-desc" class="input textarea" rows="4" formControlName="description"></textarea>
        </div>
      </form>
      <ng-container dialogActions>
        <button type="button" class="btn btn-secondary" (click)="closed.emit()" [disabled]="saving()">Cancel</button>
        <button type="submit" form="department-form" class="btn btn-primary" [disabled]="saving()">
          @if (saving()) { <span class="spinner" aria-hidden="true"></span> }
          {{ department() ? 'Save changes' : 'Create department' }}
        </button>
      </ng-container>
    </app-dialog>
  `,
})
export class DepartmentForm {
  private readonly api = inject(DepartmentsApi);

  readonly open = input.required<boolean>();
  readonly department = input<Department | null>(null);
  readonly saved = output<Department>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly isEdit = computed(() => this.department() !== null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9_-]{1,19}$/)]],
    description: ['', [Validators.maxLength(500)]],
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        const d = this.department();
        this.submitted.set(false);
        this.formError.set(null);
        this.form.reset({ name: d?.name ?? '', code: d?.code ?? '', description: d?.description ?? '' });
      }
    });
  }

  protected showError(name: 'name' | 'code'): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || this.submitted());
  }

  protected submit(): void {
    this.submitted.set(true);
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = { name: v.name.trim(), code: v.code.trim(), description: v.description.trim() || null };
    const current = this.department();
    this.saving.set(true);
    (current ? this.api.update(current.id, body) : this.api.create(body))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (department) => this.saved.emit(department),
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
