import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { SpotlightDirective } from '../spotlight/spotlight.directive';
import { RevealDirective } from './reveal.directive';

@Component({ imports: [RevealDirective, SpotlightDirective], template: `<section appReveal="120" appSpotlight style="width:200px;height:100px"></section>` })
class Host {}

function media(reduced: boolean, fine = true) {
  vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('reduce') ? reduced : fine, media: q, addEventListener() {}, removeEventListener() {} }));
}

describe('RevealDirective / SpotlightDirective', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reveals only once the element intersects, with its stagger delay', async () => {
    media(false);
    let callback: IntersectionObserverCallback = () => {};
    const disconnect = vi.fn();
    vi.stubGlobal('IntersectionObserver', class { constructor(cb: IntersectionObserverCallback) { callback = cb; } observe() {} disconnect = disconnect; });
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const el = fixture.nativeElement.querySelector('section') as HTMLElement;
    expect(el.classList).toContain('reveal');
    expect(el.style.getPropertyValue('--reveal-delay')).toBe('120ms');
    expect(el.classList).not.toContain('is-revealed');
    callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
    expect(el.classList).toContain('is-revealed');
    expect(disconnect).toHaveBeenCalled();
  });

  it('shows content immediately when reduced motion is requested', async () => {
    media(true);
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('section').classList).toContain('is-revealed');
  });

  it('spotlight tracks a fine pointer and resets on leave, but ignores touch', async () => {
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 1; });
    media(false, true);
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const el = fixture.nativeElement.querySelector('section') as HTMLElement;
    el.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 100, right: 200, bottom: 100, x: 0, y: 0, toJSON() {} }) as DOMRect;
    el.dispatchEvent(new Event('pointerenter'));
    el.dispatchEvent(Object.assign(new Event('pointermove'), { clientX: 50, clientY: 50 }));
    expect(el.classList).toContain('is-lit');
    expect(el.style.getPropertyValue('--mouse-x')).toBe('25.0%');
    expect(el.style.getPropertyValue('--my')).toBe('0.500');
    el.dispatchEvent(new Event('pointerleave'));
    expect(el.style.getPropertyValue('--mouse-x')).toBe('');

    media(false, false);
    el.dispatchEvent(new Event('pointerenter'));
    el.dispatchEvent(Object.assign(new Event('pointermove'), { clientX: 50, clientY: 50 }));
    expect(el.classList).not.toContain('is-lit');
    expect(el.style.getPropertyValue('--mouse-x')).toBe('');
  });
});
