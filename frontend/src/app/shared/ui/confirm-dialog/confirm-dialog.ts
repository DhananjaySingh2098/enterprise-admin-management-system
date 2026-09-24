import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { Dialog } from '../dialog/dialog';

@Component({
  selector: 'app-confirm-dialog',
  imports: [Dialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-dialog [open]="open()" [heading]="heading()" [busy]="busy()" (closed)="cancelled.emit()">
      <p class="confirm-message">{{ message() }}</p>
      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }
      <ng-container dialogActions>
        <button type="button" class="btn btn-secondary" (click)="cancelled.emit()" [disabled]="busy()">Cancel</button>
        <button type="button" class="btn" [class.btn-danger]="tone() === 'danger'" [class.btn-primary]="tone() !== 'danger'"
                (click)="confirmed.emit()" [disabled]="busy()">
          @if (busy()) {
            <span class="spinner" aria-hidden="true"></span>
          }
          {{ confirmLabel() }}
        </button>
      </ng-container>
    </app-dialog>
  `,
})
export class ConfirmDialog {
  readonly open = input.required<boolean>();
  readonly heading = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input('Confirm');
  readonly tone = input<'primary' | 'danger'>('primary');
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
