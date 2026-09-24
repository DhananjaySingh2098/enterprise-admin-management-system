import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  output,
  untracked,
  viewChild,
} from '@angular/core';

import { Icon } from '../icon/icon';

let nextId = 0;

/**
 * Accessible modal built on the native <dialog> element: focus is contained, the page behind is inert, Escape
 * closes, and focus returns to the element that opened it. `variant="drawer"` slides in from the right
 * (full-width sheet on small screens).
 */
@Component({
  selector: 'app-dialog',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="dialog" [attr.data-variant]="variant()" [attr.aria-labelledby]="titleId"
            [attr.aria-describedby]="description() ? descriptionId : null"
            (cancel)="onCancel($event)" (click)="onBackdropClick($event)">
      <div class="dialog-panel">
        <header class="dialog-header">
          <div>
            <h2 class="dialog-title" [id]="titleId">{{ heading() }}</h2>
            @if (description()) {
              <p class="dialog-description" [id]="descriptionId">{{ description() }}</p>
            }
          </div>
          <button type="button" class="icon-btn" (click)="requestClose()" aria-label="Close dialog">
            <app-icon name="x" />
          </button>
        </header>
        <div class="dialog-body">
          <ng-content />
        </div>
        <footer class="dialog-footer">
          <ng-content select="[dialogActions]" />
        </footer>
      </div>
    </dialog>
  `,
})
export class Dialog {
  readonly open = input.required<boolean>();
  readonly heading = input.required<string>();
  readonly description = input<string>('');
  readonly variant = input<'modal' | 'drawer'>('modal');
  /** Prevent closing (Escape/backdrop) while a request is in flight. */
  readonly busy = input(false);
  readonly closed = output<void>();

  protected readonly titleId = `dialog-title-${++nextId}`;
  protected readonly descriptionId = `dialog-desc-${nextId}`;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private returnFocus: HTMLElement | null = null;

  constructor() {
    afterRenderEffect(() => {
      const open = this.open();
      untracked(() => {
        const el = this.dialog().nativeElement;
        if (open && !(el.open || el.hasAttribute('open'))) {
          this.returnFocus = document.activeElement as HTMLElement | null;
          if (typeof el.showModal === 'function') {
            el.showModal();
          } else {
            el.setAttribute('open', '');
          }
        } else if (!open && (el.open || el.hasAttribute('open'))) {
          if (typeof el.close === 'function') {
            el.close();
          } else {
            el.removeAttribute('open');
          }
          this.returnFocus?.focus?.();
          this.returnFocus = null;
        }
      });
    });
  }

  requestClose(): void {
    if (!this.busy()) {
      this.closed.emit();
    }
  }

  protected onCancel(event: Event): void {
    event.preventDefault();
    this.requestClose();
  }

  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) {
      this.requestClose();
    }
  }
}
