import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { Icon, IconName } from '../icon/icon';

@Component({
  selector: 'app-empty-state',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty-state">
      <span class="empty-state-icon"><app-icon [name]="icon()" [size]="22" /></span>
      <p class="empty-state-title">{{ heading() }}</p>
      @if (message()) {
        <p class="empty-state-message">{{ message() }}</p>
      }
      <ng-content />
    </div>
  `,
})
export class EmptyState {
  readonly heading = input.required<string>();
  readonly message = input<string>('');
  readonly icon = input<IconName>('inbox');
}
