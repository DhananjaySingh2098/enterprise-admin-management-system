import { Directive, ElementRef, NgZone, OnDestroy, inject } from '@angular/core';

import { pointerEffectsAllowed, relativePointer } from './pointer-effects';

/**
 * Soft pointer-following highlight. Writes --mouse-x/--mouse-y (percent) and --mx/--my (0..1, for parallax
 * layers) while a fine pointer moves over the host; rAF-throttled; resets on leave. No work when idle.
 */
@Directive({
  selector: '[appSpotlight]',
  host: {
    class: 'spotlight',
    '(pointerenter)': 'onEnter()',
    '(pointermove)': 'onMove($event)',
    '(pointerleave)': 'reset()',
  },
})
export class SpotlightDirective implements OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
  private readonly zone = inject(NgZone);
  private enabled = false;
  private frame = 0;
  private pending: PointerEvent | null = null;

  protected onEnter(): void {
    this.enabled = pointerEffectsAllowed();
    this.el.classList.toggle('is-lit', this.enabled);
  }

  protected onMove(event: PointerEvent): void {
    if (!this.enabled) {
      return;
    }
    this.pending = event;
    if (!this.frame) {
      this.zone.runOutsideAngular(() => (this.frame = requestAnimationFrame(() => this.apply())));
    }
  }

  reset(): void {
    cancelAnimationFrame(this.frame);
    this.frame = 0;
    this.pending = null;
    this.el.classList.remove('is-lit');
    for (const prop of ['--mouse-x', '--mouse-y', '--mx', '--my']) {
      this.el.style.removeProperty(prop);
    }
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }

  private apply(): void {
    this.frame = 0;
    if (!this.pending) {
      return;
    }
    const { x, y } = relativePointer(this.el, this.pending);
    this.el.style.setProperty('--mouse-x', `${(x * 100).toFixed(1)}%`);
    this.el.style.setProperty('--mouse-y', `${(y * 100).toFixed(1)}%`);
    this.el.style.setProperty('--mx', x.toFixed(3));
    this.el.style.setProperty('--my', y.toFixed(3));
  }
}
