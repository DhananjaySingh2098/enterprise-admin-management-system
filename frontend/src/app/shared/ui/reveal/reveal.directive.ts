import { DestroyRef, Directive, ElementRef, afterNextRender, inject, input } from '@angular/core';

import { prefersReducedMotion } from '../spotlight/pointer-effects';

/**
 * Entrance reveal: adds `.is-revealed` once the host scrolls into view (IntersectionObserver), so below-the-fold
 * panels and chart draws animate when actually seen. Immediate when motion is reduced or IO is unavailable. A safety
 * check reveals anything already inside the viewport after 2.5s (should IO ever miss it), while below-the-fold
 * content keeps waiting for the scroll. One observer per element, disconnected after first reveal.
 */
@Directive({
  selector: '[appReveal]',
  host: { class: 'reveal', '[class.reveal-soft]': 'soft()', '[style.--reveal-delay]': 'delay() + "ms"' },
})
export class RevealDirective {
  /** Stagger delay in milliseconds. */
  readonly delay = input(0, { alias: 'appReveal', transform: (v: unknown) => Number(v) || 0 });
  /** Adds a slight scale + blur to the entrance (hero / KPI only). */
  readonly soft = input(false);

  constructor() {
    const el = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const reveal = () => el.classList.add('is-revealed');
      if (prefersReducedMotion() || typeof IntersectionObserver === 'undefined') {
        reveal();
        return;
      }
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            reveal();
            observer.disconnect();
          }
        },
        { rootMargin: '0px 0px -6% 0px', threshold: 0.08 },
      );
      observer.observe(el);
      const safety = setTimeout(() => {
        const rect = el.getBoundingClientRect();
        if (rect.top < innerHeight && rect.bottom > 0) {
          reveal();
          observer.disconnect();
        }
      }, 2500);
      destroyRef.onDestroy(() => {
        observer.disconnect();
        clearTimeout(safety);
      });
    });
  }
}
