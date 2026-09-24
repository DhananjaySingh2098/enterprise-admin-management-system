const FINE_POINTER = '(hover: hover) and (pointer: fine)';
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)';

/** Pointer effects run only for a hovering fine pointer (mouse/trackpad) when motion is allowed. */
export function pointerEffectsAllowed(): boolean {
  return typeof matchMedia === 'function' && matchMedia(FINE_POINTER).matches && !matchMedia(REDUCED_MOTION).matches;
}

export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia(REDUCED_MOTION).matches;
}

/** Relative pointer position within an element (or a given rectangle), clamped to 0..1. */
export function relativePointer(el: HTMLElement, event: PointerEvent, box?: DOMRect | null): { x: number; y: number } {
  const rect = box ?? el.getBoundingClientRect();
  return {
    x: Math.min(1, Math.max(0, (event.clientX - rect.left) / (rect.width || 1))),
    y: Math.min(1, Math.max(0, (event.clientY - rect.top) / (rect.height || 1))),
  };
}
