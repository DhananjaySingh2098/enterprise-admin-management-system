import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type ConnectionState = 'checking' | 'online' | 'offline';

const LABELS: Record<ConnectionState, string> = {
  checking: 'Checking',
  online: 'Online',
  offline: 'Unavailable',
};

@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="badge" [attr.data-state]="state()" role="status">
      <span class="dot" aria-hidden="true"></span>
      {{ label() }}: {{ text() }}
    </span>
  `,
})
export class StatusBadge {
  readonly label = input.required<string>();
  readonly state = input.required<ConnectionState>();

  protected readonly text = computed(() => LABELS[this.state()]);
}
