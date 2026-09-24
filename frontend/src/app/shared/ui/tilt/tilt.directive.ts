import { Directive, ElementRef, NgZone, OnDestroy, inject, input } from '@angular/core';

import { pointerEffectsAllowed, relativePointer } from '../spotlight/pointer-effects';

/**
 * Subtle 3D tilt + pointer spotlight for selected cards. Writes CSS variables only (`--tilt-x`, `--tilt-y`,
 * `--mouse-x`, `--mouse-y`); styling lives in CSS. Active only for a hovering fine pointer when reduced motion is
 * not requested — never for touch or keyboard focus. rAF-throttled, fully reset on leave, no loops.
 *
 * Hit-testing uses the card's untilted rectangle (captured on enter): a tilted edge can slide out from under a pointer
 * that is still over the card, which would otherwise fire pointerleave and make the card flicker at its edges.
 */
@Directive({
  selector: '[appTilt]',
  host: {
    class: 'tilt spotlight',
    '(pointerenter)': 'onEnter()',
    '(pointermove)': 'onMove($event)',
    '(pointerleave)': 'onLeave($event)',
  },
})
export class TiltDirective implements OnDestroy {
  /** Maximum rotateX (degrees, clamped 0–3). */
  readonly maxTiltX = input(3, { transform: (v: unknown) => Math.min(3, Math.max(0, Number(v) || 0)) });
  /** Maximum rotateY (degrees, clamped 0–3). */
  readonly maxTiltY = input(3, { transform: (v: unknown) => Math.min(3, Math.max(0, Number(v) || 0)) });

  private readonly el = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
  private readonly zone = inject(NgZone);
  private enabled = false;
  private frame = 0;
  private pending: PointerEvent | null = null;
  private rect: DOMRect | null = null;
  private readonly onScroll = () => this.reset();
  private readonly trackOutside = (event: PointerEvent) => {
    if (!this.inside(event)) {
      this.reset();
    } else {
      this.onMove(event);
    }
  };

  static isSupported(): boolean {
    return pointerEffectsAllowed();
  }

  protected onEnter(): void {
    if (this.rect) {
      // Re-entered while still tracking a tilted edge: keep the original rectangle.
      document.removeEventListener('pointermove', this.trackOutside);
      return;
    }
    this.enabled = pointerEffectsAllowed();
    if (this.enabled) {
      this.rect = this.el.getBoundingClientRect();
      // A scroll invalidates the captured rectangle; simply end the effect.
      this.zone.runOutsideAngular(() => addEventListener('scroll', this.onScroll, { capture: true, passive: true, once: true }));
      this.el.classList.add('is-tilting', 'is-lit');
    }
  }

  protected onLeave(event: PointerEvent): void {
    if (this.enabled && this.inside(event)) {
      // Only the tilted outline moved away; follow the pointer until it really leaves the card's footprint.
      this.zone.runOutsideAngular(() => document.addEventListener('pointermove', this.trackOutside));
      return;
    }
    this.reset();
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
    document.removeEventListener('pointermove', this.trackOutside);
    removeEventListener('scroll', this.onScroll, { capture: true });
    this.frame = 0;
    this.pending = null;
    this.rect = null;
    this.el.classList.remove('is-tilting', 'is-lit');
    for (const prop of ['--tilt-x', '--tilt-y', '--mouse-x', '--mouse-y']) {
      this.el.style.removeProperty(prop);
    }
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
    document.removeEventListener('pointermove', this.trackOutside);
    removeEventListener('scroll', this.onScroll, { capture: true });
  }

  private inside(event: PointerEvent): boolean {
    const r = this.rect;
    return !!r && event.clientX > r.left && event.clientX < r.right && event.clientY > r.top && event.clientY < r.bottom;
  }

  private apply(): void {
    this.frame = 0;
    if (!this.pending) {
      return;
    }
    const { x, y } = relativePointer(this.el, this.pending, this.rect);
    this.el.style.setProperty('--tilt-x', `${((0.5 - y) * 2 * this.maxTiltX()).toFixed(2)}deg`);
    this.el.style.setProperty('--tilt-y', `${((x - 0.5) * 2 * this.maxTiltY()).toFixed(2)}deg`);
    this.el.style.setProperty('--mouse-x', `${(x * 100).toFixed(1)}%`);
    this.el.style.setProperty('--mouse-y', `${(y * 100).toFixed(1)}%`);
  }
}
