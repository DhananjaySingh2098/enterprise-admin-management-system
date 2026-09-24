import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { donutSegments, formatPercent } from './chart-utils';

export interface DonutDatum {
  key: string;
  label: string;
  value: number;
  /** CSS colour (token) for the arc and legend swatch. */
  color: string;
}

/** Proportional ring with a text legend (label, count, share) — colour is never the only carrier of meaning. */
@Component({
  selector: 'app-donut-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="donut">
      <svg class="donut-svg" viewBox="0 0 42 42" role="img" [attr.aria-label]="description()">
        <circle class="donut-depth" cx="21" cy="21" r="19.4" />
        <circle class="donut-track" cx="21" cy="21" r="15.9155" />
        <circle class="donut-hole" cx="21" cy="21" r="12.6" />
        @for (segment of segments(); track segment.item.key) {
          <circle class="donut-arc" cx="21" cy="21" r="15.9155" [style.stroke]="segment.item.color"
                  [attr.stroke-dasharray]="segment.length + ' ' + (100 - segment.length)"
                  [attr.stroke-dashoffset]="25 - segment.start"
                  [class.is-dimmed]="active() !== null && active() !== segment.item.key"
                  (pointerenter)="active.set(segment.item.key)" (pointerleave)="active.set(null)" />
        }
      </svg>
      <div class="donut-center" aria-hidden="true">
        <strong>{{ centerValue() }}</strong>
        <span>{{ centerCaption() }}</span>
      </div>
    </div>
    <ul class="donut-legend">
      @for (item of legend(); track item.key) {
        <li tabindex="0" [class.is-active]="active() === item.key" [attr.aria-label]="item.label + ': ' + item.value + ', ' + item.share"
            (pointerenter)="active.set(item.key)" (pointerleave)="active.set(null)" (focus)="active.set(item.key)" (blur)="active.set(null)">
          <span class="legend-swatch" [style.background]="item.color" aria-hidden="true"></span>
          <span class="legend-label">{{ item.label }}</span>
          <span class="legend-value">{{ item.value }}</span>
          <span class="legend-share">{{ item.share }}</span>
        </li>
      }
    </ul>
  `,
})
export class DonutChart {
  readonly data = input.required<DonutDatum[]>();
  readonly label = input.required<string>();
  readonly centerLabel = input('total');

  protected readonly active = signal<string | null>(null);
  protected readonly total = computed(() => this.data().reduce((sum, d) => sum + d.value, 0));
  protected readonly segments = computed(() => donutSegments(this.data(), (d) => d.value));
  protected readonly centerValue = computed(() => {
    const key = this.active();
    const item = key ? this.data().find((d) => d.key === key) : null;
    return item ? item.value : this.total();
  });
  protected readonly centerCaption = computed(() => {
    const key = this.active();
    return key ? (this.data().find((d) => d.key === key)?.label ?? this.centerLabel()) : this.centerLabel();
  });
  protected readonly legend = computed(() =>
    this.data().map((d) => ({ ...d, share: formatPercent(d.value, this.total()) })),
  );
  protected readonly description = computed(
    () => `${this.label()}: ` + this.legend().map((d) => `${d.label} ${d.value} (${d.share})`).join(', '),
  );
}
