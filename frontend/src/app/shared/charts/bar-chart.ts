import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatPercent } from './chart-utils';

export interface BarDatum {
  label: string;
  sublabel?: string;
  value: number;
  muted?: boolean;
  tag?: string;
}

/**
 * Horizontal bar chart in plain HTML: every label and value is real text (readable by screen readers, never
 * clipped), bars scale from zero, and each row shows its share on hover/focus.
 */
@Component({
  selector: 'app-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="bar-chart" [attr.aria-label]="label()">
      @for (bar of bars(); track bar.label; let i = $index) {
        <li class="bar-row" [class.is-muted]="bar.muted" [style.--i]="i" tabindex="0"
            [attr.aria-label]="bar.label + ': ' + bar.value + ' ' + unit() + ', ' + bar.share + ' of total' + (bar.tag ? ', ' + bar.tag : '')">
          <span class="bar-label" aria-hidden="true">
            <span class="bar-name">{{ bar.label }}</span>
            @if (bar.sublabel) { <span class="bar-sublabel">{{ bar.sublabel }}</span> }
            @if (bar.tag) { <span class="bar-tag">{{ bar.tag }}</span> }
          </span>
          <span class="bar-track" aria-hidden="true"><span class="bar-fill" [style.width.%]="bar.width"></span></span>
          <span class="bar-value" aria-hidden="true">{{ bar.value }}</span>
          <div class="chart-tooltip" role="presentation" aria-hidden="true">
            <strong>{{ bar.label }}</strong> {{ bar.value }} {{ unit() }} · {{ bar.share }} of total
          </div>
        </li>
      }
    </ol>
  `,
})
export class BarChart {
  readonly data = input.required<BarDatum[]>();
  readonly label = input.required<string>();
  readonly unit = input('employees');

  protected readonly bars = computed(() => {
    const data = this.data();
    const max = Math.max(0, ...data.map((d) => d.value));
    const total = data.reduce((sum, d) => sum + d.value, 0);
    return data.map((d) => ({
      ...d,
      width: max > 0 ? (d.value / max) * 100 : 0,
      share: formatPercent(d.value, total),
    }));
  });
}
