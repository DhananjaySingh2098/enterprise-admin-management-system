import { ChangeDetectionStrategy, Component } from '@angular/core';

import { Icon } from '../icon/icon';

/**
 * Decorative 3D hero stage (no data): translucent stacked "people" cards, a glossy team tile, a chart tile and a
 * slogan card, each on its own translateZ depth inside one perspective scene. Four depth planes (ring −60px, back
 * card −30px, mid card/slogan 30–60px, tiles 90–110px). The scene tilts ≤3° and each plane shifts in proportion to
 * its depth, both from the parent's --mx/--my (written by appSpotlight, rAF-throttled, only while a fine pointer
 * moves). Idle float is CSS-only; all motion stops for reduced motion. Hidden from assistive technology.
 */
@Component({
  selector: 'app-hero-stage',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'hero-stage', 'aria-hidden': 'true' },
  template: `
    <span class="stage-blob stage-blob-1"></span>
    <span class="stage-blob stage-blob-2"></span>
    <div class="stage-scene">
      <span class="stage-ring"></span>
      <div class="stage-card stage-back">
        @for (row of rows; track row) {
          <span class="stage-row"><i></i><b [style.width.%]="row"></b></span>
        }
      </div>
      <div class="stage-card stage-mid">
        @for (row of rows.slice(0, 3); track row) {
          <span class="stage-row"><i></i><b [style.width.%]="row"></b></span>
        }
      </div>
      <div class="stage-tile stage-team"><app-icon name="users" [size]="34" /></div>
      <div class="stage-tile stage-chart"><app-icon name="chart" [size]="20" /></div>
      <div class="stage-slogan"><strong>Stronger teams,</strong><strong>brighter together.</strong></div>
      <span class="stage-spark stage-spark-1"></span>
      <span class="stage-spark stage-spark-2"></span>
    </div>
  `,
})
export class HeroStage {
  /** Placeholder line widths for the abstract list cards (purely visual). */
  protected readonly rows = [72, 58, 66, 48];
}
