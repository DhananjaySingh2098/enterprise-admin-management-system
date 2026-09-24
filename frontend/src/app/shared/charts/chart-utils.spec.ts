import { areaPath, donutSegments, formatPercent, labelStride, linePath, monthLabel, monthLongLabel, niceScale, percentOf, plotPoints, plural } from './chart-utils';

describe('chart-utils', () => {
  it('builds integer axes from zero and handles empty data', () => {
    expect(niceScale(0)).toEqual({ max: 1, ticks: [0, 1] });
    expect(niceScale(1)).toEqual({ max: 1, ticks: [0, 1] });
    expect(niceScale(5).max).toBeGreaterThanOrEqual(5);
    expect(niceScale(5).ticks[0]).toBe(0);
    expect(niceScale(37)).toEqual({ max: 40, ticks: [0, 10, 20, 30, 40] });
    expect(niceScale(3).ticks.every(Number.isInteger)).toBe(true);
  });

  it('computes shares without NaN at zero total', () => {
    expect(percentOf(1, 0)).toBe(0);
    expect(formatPercent(0, 0)).toBe('0%');
    expect(formatPercent(13, 16)).toBe('81%');
    expect(formatPercent(1, 500)).toBe('<1%');
  });

  it('splits the donut proportionally and skips zero segments', () => {
    const segments = donutSegments([{ v: 3 }, { v: 0 }, { v: 1 }], (d) => d.v, 0);
    expect(segments.map((s) => [s.start, s.length])).toEqual([[0, 75], [75, 25]]);
    expect(donutSegments([{ v: 0 }], (d) => d.v)).toEqual([]);
    const single = donutSegments([{ v: 4 }], (d) => d.v);
    expect(single[0].length).toBe(100); // no gap with a single segment
  });

  it('plots points from a zero baseline with straight segments', () => {
    const points = plotPoints([0, 2, 4], 100, 50, 4);
    expect(points.map((p) => [p.x, p.y])).toEqual([[0, 50], [50, 25], [100, 0]]);
    expect(linePath(points)).toBe('M0.00,50.00 L50.00,25.00 L100.00,0.00');
    expect(areaPath(points, 50)).toContain('Z');
    expect(plotPoints([], 100, 50, 1)).toEqual([]);
  });

  it('formats months and thins labels to fit', () => {
    expect(monthLabel('2026-03')).toBe('Mar');
    expect(monthLabel('2026-03', true)).toBe('Mar 26');
    expect(monthLongLabel('2025-12')).toBe('December 2025');
    expect(labelStride(12, 1000)).toBe(1);
    expect(labelStride(24, 400)).toBeGreaterThan(1);
    expect(plural(1, 'hire')).toBe('1 hire');
    expect(plural(0, 'hire')).toBe('0 hires');
  });
});
