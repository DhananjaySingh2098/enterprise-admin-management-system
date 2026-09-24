import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { areaPath, labelStride, linePath, niceScale, plotPoints } from './chart-utils';

export interface LineDatum {
  /** Short axis label, e.g. "Mar". */
  label: string;
  /** Full label for tooltips / announcements, e.g. "March 2026". */
  fullLabel: string;
  value: number;
}

let nextId = 0;
const MARGIN = { top: 16, right: 12, bottom: 28, left: 32 };

/**
 * Line + area chart drawn in SVG at the measured container width (crisp text, no distortion). The y axis starts
 * at zero with whole-number ticks. Pointer hover and ←/→ keys reveal each point; values are announced politely.
 */
@Component({
  selector: 'app-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'line-chart' },
  template: `
    <div class="line-chart-frame" tabindex="0" role="group" [attr.aria-label]="label() + '. Use the left and right arrow keys to explore months.'"
         (keydown)="onKeydown($event)" (pointermove)="onPointer($event)" (pointerleave)="active.set(null)" (blur)="active.set(null)">
      <svg [attr.width]="width()" [attr.height]="height()" [attr.viewBox]="'0 0 ' + width() + ' ' + height()" aria-hidden="true">
        <defs>
          <linearGradient [attr.id]="gradientId" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="var(--chart-1)" stop-opacity="0.28" />
            <stop offset="100%" stop-color="var(--chart-1)" stop-opacity="0" />
          </linearGradient>
        </defs>
        <g [attr.transform]="'translate(' + margin.left + ',' + margin.top + ')'">
          @for (tick of scale().ticks; track tick) {
            <line class="grid-line" x1="0" [attr.x2]="plotWidth()" [attr.y1]="yFor(tick)" [attr.y2]="yFor(tick)" />
            <text class="axis-label" x="-8" [attr.y]="yFor(tick)" text-anchor="end" dominant-baseline="middle">{{ tick }}</text>
          }
          @for (point of points(); track $index; let i = $index) {
            @if ((i % stride() === 0 && i <= points().length - 1 - stride()) || i === points().length - 1 || (i === 0)) {
              <text class="axis-label" [attr.x]="point.x" [attr.y]="plotHeight() + 18"
                    [attr.text-anchor]="i === 0 ? 'start' : i === points().length - 1 ? 'end' : 'middle'">{{ data()[i].label }}</text>
            }
          }
          <path class="area" [attr.d]="area()" [attr.fill]="'url(#' + gradientId + ')'" />
          <path class="line" [attr.d]="line()" pathLength="1" />
          @if (activePoint(); as p) {
            <line class="guide" [attr.x1]="p.x" [attr.x2]="p.x" y1="0" [attr.y2]="plotHeight()" />
          }
          @if (latest(); as last) {
            <circle class="latest-halo" [attr.cx]="last.x" [attr.cy]="last.y" r="11" />
          }
          @for (point of points(); track $index; let i = $index) {
            <circle class="point" [class.is-active]="active() === i" [class.is-latest]="i === points().length - 1"
                    [attr.cx]="point.x" [attr.cy]="point.y" [attr.r]="active() === i || i === points().length - 1 ? 4.5 : 3" />
          }
        </g>
      </svg>
      @if (isEmpty()) {
        <p class="line-empty">No hires recorded in this period</p>
      }
      @if (activePoint(); as p) {
        <div class="chart-tooltip is-visible" [style.left.px]="p.x + margin.left" [style.top.px]="p.y + margin.top">
          <strong>{{ data()[active()!].fullLabel }}</strong> {{ p.value }} {{ p.value === 1 ? unitSingular() : unit() }}
        </div>
      }
    </div>
    <p class="visually-hidden" aria-live="polite">{{ announcement() }}</p>
  `,
})
export class LineChart {
  readonly data = input.required<LineDatum[]>();
  readonly label = input.required<string>();
  readonly unit = input('hires');
  readonly unitSingular = input('hire');
  readonly height = input(240);

  protected readonly margin = MARGIN;
  protected readonly gradientId = `line-gradient-${++nextId}`;
  protected readonly width = signal(640);
  protected readonly active = signal<number | null>(null);

  protected readonly plotWidth = computed(() => Math.max(10, this.width() - MARGIN.left - MARGIN.right));
  protected readonly plotHeight = computed(() => Math.max(10, this.height() - MARGIN.top - MARGIN.bottom));
  protected readonly scale = computed(() => niceScale(Math.max(0, ...this.data().map((d) => d.value)), 4));
  protected readonly points = computed(() =>
    plotPoints(this.data().map((d) => d.value), this.plotWidth(), this.plotHeight(), this.scale().max),
  );
  protected readonly line = computed(() => linePath(this.points()));
  protected readonly area = computed(() => areaPath(this.points(), this.plotHeight()));
  protected readonly stride = computed(() => labelStride(this.data().length, this.plotWidth()));
  protected readonly isEmpty = computed(() => this.data().length > 0 && this.data().every((d) => d.value === 0));
  protected readonly latest = computed(() => {
    const points = this.points();
    return points.length && !this.isEmpty() ? points[points.length - 1] : null;
  });
  protected readonly activePoint = computed(() => {
    const i = this.active();
    return i === null ? null : (this.points()[i] ?? null);
  });
  protected readonly announcement = computed(() => {
    const i = this.active();
    const d = i === null ? null : this.data()[i];
    return d ? `${d.fullLabel}: ${d.value} ${d.value === 1 ? this.unitSingular() : this.unit()}` : '';
  });

  private readonly host = inject(ElementRef<HTMLElement>);

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const el = this.host.nativeElement as HTMLElement;
      const measure = () => this.width.set(Math.max(280, Math.round(el.getBoundingClientRect().width)));
      measure();
      if (typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver(() => measure());
        observer.observe(el);
        destroyRef.onDestroy(() => observer.disconnect());
      }
    });
  }

  protected yFor(value: number): number {
    return this.plotHeight() - (value / this.scale().max) * this.plotHeight();
  }

  protected onPointer(event: PointerEvent): void {
    const points = this.points();
    if (!points.length) {
      return;
    }
    const frame = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const x = event.clientX - frame.left - MARGIN.left;
    let nearest = 0;
    for (let i = 1; i < points.length; i++) {
      if (Math.abs(points[i].x - x) < Math.abs(points[nearest].x - x)) {
        nearest = i;
      }
    }
    this.active.set(nearest);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.data().length;
    if (!count || (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight' && event.key !== 'Home' && event.key !== 'End')) {
      return;
    }
    event.preventDefault();
    const current = this.active() ?? (event.key === 'ArrowLeft' ? count : -1);
    const next =
      event.key === 'Home' ? 0 : event.key === 'End' ? count - 1 : current + (event.key === 'ArrowRight' ? 1 : -1);
    this.active.set(Math.min(count - 1, Math.max(0, next)));
  }
}
