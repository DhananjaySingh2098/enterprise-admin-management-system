import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { Icon } from '../icon/icon';

@Component({
  selector: 'app-pagination',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="pagination" [attr.aria-label]="label()">
      <p class="pagination-summary" aria-live="polite">
        @if (total() === 0) {
          No results
        } @else {
          Showing <strong>{{ from() }}–{{ to() }}</strong> of <strong>{{ total() }}</strong>
        }
      </p>
      <div class="pagination-controls">
        <label class="pagination-size">
          <span>Rows</span>
          <select class="select select-sm" [value]="size()" (change)="sizeChange.emit(+$any($event.target).value)">
            @for (option of sizeOptions; track option) {
              <option [value]="option" [selected]="option === size()">{{ option }}</option>
            }
          </select>
        </label>
        <button type="button" class="icon-btn" (click)="pageChange.emit(page() - 1)" [disabled]="page() <= 0" aria-label="Previous page">
          <app-icon name="chevron-left" />
        </button>
        <span class="pagination-page">Page {{ totalPages() === 0 ? 0 : page() + 1 }} of {{ totalPages() }}</span>
        <button type="button" class="icon-btn" (click)="pageChange.emit(page() + 1)" [disabled]="page() + 1 >= totalPages()" aria-label="Next page">
          <app-icon name="chevron-right" />
        </button>
      </div>
    </nav>
  `,
})
export class Pagination {
  readonly page = input.required<number>();
  readonly size = input.required<number>();
  readonly total = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly label = input('Pagination');
  readonly pageChange = output<number>();
  readonly sizeChange = output<number>();

  protected readonly sizeOptions = [10, 20, 50, 100];
  protected readonly from = computed(() => (this.total() === 0 ? 0 : this.page() * this.size() + 1));
  protected readonly to = computed(() => Math.min(this.total(), (this.page() + 1) * this.size()));
}
