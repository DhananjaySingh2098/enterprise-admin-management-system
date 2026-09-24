import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { TiltDirective } from './tilt.directive';

@Component({ imports: [TiltDirective], template: `<div appTilt style="width:200px;height:100px"></div>` })
class Host {}

function mockMedia(finePointer: boolean, reducedMotion: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('reduce') ? reducedMotion : finePointer,
    media: query, addEventListener() {}, removeEventListener() {},
  }));
}

describe('TiltDirective', () => {
  afterEach(() => vi.unstubAllGlobals());

  async function hover() {
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 1; });
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const el = fixture.nativeElement.querySelector('div') as HTMLElement;
    el.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 100, right: 200, bottom: 100, x: 0, y: 0, toJSON() {} }) as DOMRect;
    el.dispatchEvent(new Event('pointerenter'));
    el.dispatchEvent(Object.assign(new Event('pointermove'), { clientX: 200, clientY: 0 }));
    return el;
  }

  it('tilts at most 3° on X and 3° on Y and positions the spotlight for a fine pointer', async () => {
    mockMedia(true, false);
    const el = await hover();
    expect(el.classList).toContain('is-tilting');
    expect(el.style.getPropertyValue('--tilt-x')).toBe('3.00deg');
    expect(el.classList).toContain('is-lit');
    expect(el.style.getPropertyValue('--tilt-y')).toBe('3.00deg');
    expect(el.style.getPropertyValue('--mouse-x')).toBe('100.0%');
    el.dispatchEvent(new Event('pointerleave'));
    expect(el.style.getPropertyValue('--tilt-x')).toBe('');
    expect(el.classList).not.toContain('is-tilting');
  });

  it('does not flicker when a tilted edge slides out from under a pointer still over the card', async () => {
    mockMedia(true, false);
    const el = await hover();
    el.dispatchEvent(Object.assign(new Event('pointerleave'), { clientX: 198, clientY: 2 }));
    expect(el.classList).toContain('is-tilting');
    document.dispatchEvent(Object.assign(new Event('pointermove'), { clientX: 260, clientY: 40 }));
    expect(el.classList).not.toContain('is-tilting');
    expect(el.style.getPropertyValue('--tilt-y')).toBe('');
  });

  it('does nothing when reduced motion is requested', async () => {
    mockMedia(true, true);
    const el = await hover();
    expect(el.classList).not.toContain('is-tilting');
    expect(el.style.getPropertyValue('--tilt-x')).toBe('');
  });

  it('does nothing for touch / coarse pointers', async () => {
    mockMedia(false, false);
    const el = await hover();
    expect(el.style.getPropertyValue('--tilt-y')).toBe('');
  });
});
