import { TestBed } from '@angular/core/testing';

import { BarChart } from './bar-chart';
import { DonutChart } from './donut-chart';
import { LineChart } from './line-chart';

describe('charts', () => {
  it('BarChart scales bars to the largest value and exposes real text labels', async () => {
    const fixture = TestBed.createComponent(BarChart);
    fixture.componentRef.setInput('label', 'Headcount');
    fixture.componentRef.setInput('data', [
      { label: 'Engineering', sublabel: 'ENG', value: 5 },
      { label: 'Legacy', value: 0, muted: true, tag: 'Inactive' },
    ]);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const fills = [...el.querySelectorAll<HTMLElement>('.bar-fill')].map((f) => f.style.width);
    expect(fills).toEqual(['100%', '0%']);
    const rows = el.querySelectorAll('.bar-row');
    expect(rows[0].getAttribute('aria-label')).toBe('Engineering: 5 employees, 100% of total');
    expect(rows[1].getAttribute('aria-label')).toContain('Inactive');
    expect(rows[1].classList).toContain('is-muted');
  });

  it('DonutChart shows label, count and share for every status, including zeros', async () => {
    const fixture = TestBed.createComponent(DonutChart);
    fixture.componentRef.setInput('label', 'Employees by status');
    fixture.componentRef.setInput('data', [
      { key: 'ACTIVE', label: 'Active', value: 3, color: 'green' },
      { key: 'ON_LEAVE', label: 'On leave', value: 1, color: 'orange' },
      { key: 'TERMINATED', label: 'Terminated', value: 0, color: 'grey' },
    ]);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const legend = [...el.querySelectorAll('.donut-legend li')].map((li) => [...li.querySelectorAll('span')].map((s) => s.textContent?.trim()).filter(Boolean).join(' '));
    expect(legend).toEqual(['Active 3 75%', 'On leave 1 25%', 'Terminated 0 0%']);
    expect(el.querySelectorAll('.donut-arc').length).toBe(2);
    expect(el.querySelector('svg')?.getAttribute('aria-label')).toBe('Employees by status: Active 3 (75%), On leave 1 (25%), Terminated 0 (0%)');
    expect(el.querySelector('.donut-center strong')?.textContent).toBe('4');
  });

  it('LineChart supports keyboard exploration with polite announcements', async () => {
    const fixture = TestBed.createComponent(LineChart);
    fixture.componentRef.setInput('label', 'Hires');
    fixture.componentRef.setInput('data', [
      { label: 'Jan', fullLabel: 'January 2026', value: 0 },
      { label: 'Feb', fullLabel: 'February 2026', value: 2 },
      { label: 'Mar', fullLabel: 'March 2026', value: 1 },
    ]);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('circle.point').length).toBe(3);
    expect(el.querySelectorAll('.axis-label')[0].textContent).toBe('0');
    const frame = el.querySelector<HTMLElement>('.line-chart-frame')!;
    expect(frame.getAttribute('tabindex')).toBe('0');
    frame.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    await fixture.whenStable();
    expect(el.querySelector('[aria-live]')?.textContent).toBe('January 2026: 0 hires');
    frame.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    await fixture.whenStable();
    expect(el.querySelector('[aria-live]')?.textContent).toBe('February 2026: 2 hires');
    frame.dispatchEvent(new KeyboardEvent('keydown', { key: 'End' }));
    await fixture.whenStable();
    expect(el.querySelector('[aria-live]')?.textContent).toBe('March 2026: 1 hire');
    expect(el.querySelector('.chart-tooltip')?.textContent).toContain('March 2026');
  });
});
