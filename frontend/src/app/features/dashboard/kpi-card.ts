import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Icon, IconName } from '../../shared/ui/icon/icon';
import { RevealDirective } from '../../shared/ui/reveal/reveal.directive';
import { TiltDirective } from '../../shared/ui/tilt/tilt.directive';

const NUMBER = new Intl.NumberFormat();

/** One slice of a real composition meter (e.g. active / on leave / terminated). */
export interface KpiSegment {
  value: number;
  tone: 'primary' | 'success' | 'warning' | 'neutral' | 'accent' | 'info' | 'track';
}

/**
 * Metric card. The meter shows how the current value is composed from real counts — never a trend or a
 * comparison (there is no historical data). Values are also stated in the text, so the meter is decorative.
 */
@Component({
  selector: 'app-kpi-card',
  imports: [Icon],
  hostDirectives: [TiltDirective, { directive: RevealDirective, inputs: ['appReveal: revealDelay', 'soft: revealSoft'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'kpi-card', '[attr.data-tone]': 'tone()', role: 'group', '[attr.aria-label]': 'ariaLabel()' },
  template: `
    <span class="kpi-glow" aria-hidden="true"></span>
    <span class="kpi-watermark" aria-hidden="true"><app-icon [name]="icon()" [size]="76" /></span>
    <div class="kpi-head">
      <span class="kpi-icon"><app-icon [name]="icon()" [size]="19" /></span>
      <span class="kpi-label">{{ label() }}</span>
    </div>
    <p class="kpi-value">{{ formatted() }}</p>
    @if (visibleSegments().length) {
      <div class="kpi-meter" aria-hidden="true">
        @for (segment of visibleSegments(); track $index) {
          <span [attr.data-tone]="segment.tone" [style.flex-grow]="segment.value"></span>
        }
      </div>
    }
    <p class="kpi-detail">{{ detail() }}</p>
  `,
})
export class KpiCard {
  readonly label = input.required<string>();
  readonly value = input.required<number>();
  readonly detail = input('');
  readonly icon = input.required<IconName>();
  readonly tone = input<'primary' | 'success' | 'warning' | 'accent' | 'info'>('primary');
  readonly segments = input<KpiSegment[]>([]);

  protected readonly formatted = computed(() => NUMBER.format(this.value()));
  protected readonly visibleSegments = computed(() => this.segments().filter((s) => s.value > 0));
  protected readonly ariaLabel = computed(() => `${this.label()}: ${this.formatted()}. ${this.detail()}`);
}
