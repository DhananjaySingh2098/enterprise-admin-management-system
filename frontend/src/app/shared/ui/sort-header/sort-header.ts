import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ListQuery } from '../../data/list-query';
import { Icon } from '../icon/icon';

/** Sortable column header: `<th appSortHeader="name" [query]="query">Name</th>`. Exposes aria-sort. */
@Component({
  selector: 'th[appSortHeader]',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.aria-sort]': 'ariaSort()', scope: 'col' },
  template: `
    <button type="button" class="sort-button" (click)="query().toggleSort(key())" [class.is-active]="active()">
      <ng-content />
      <app-icon [name]="icon()" [size]="14" />
    </button>
  `,
})
export class SortHeader {
  readonly key = input.required<string>({ alias: 'appSortHeader' });
  readonly query = input.required<ListQuery>();

  protected readonly active = computed(() => this.query().sort() === this.key());
  protected readonly ariaSort = computed(() =>
    this.active() ? (this.query().direction() === 'asc' ? 'ascending' : 'descending') : 'none',
  );
  protected readonly icon = computed(() =>
    !this.active() ? 'chevrons-up-down' : this.query().direction() === 'asc' ? 'arrow-up' : 'arrow-down',
  );
}
