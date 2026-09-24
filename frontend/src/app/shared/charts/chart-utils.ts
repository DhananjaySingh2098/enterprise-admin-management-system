/** Pure helpers for the hand-written charts. No scaling tricks: axes always start at zero. */

export interface Scale {
  max: number;
  ticks: number[];
}

/** Integer-friendly axis from 0 to a "nice" maximum ≥ the data maximum (counts are whole numbers). */
export function niceScale(dataMax: number, targetTicks = 4): Scale {
  if (!Number.isFinite(dataMax) || dataMax <= 0) {
    return { max: 1, ticks: [0, 1] };
  }
  const rough = dataMax / targetTicks;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const residual = rough / magnitude;
  const niceStep = residual > 5 ? 10 : residual > 2 ? 5 : residual > 1 ? 2 : 1;
  const step = Math.max(1, niceStep * magnitude);
  const max = Math.ceil(dataMax / step) * step;
  const ticks: number[] = [];
  for (let value = 0; value <= max + 1e-9; value += step) {
    ticks.push(Math.round(value));
  }
  return { max, ticks };
}

/** Share of total in [0, 100]; 0 when the total is 0 (never NaN). */
export function percentOf(value: number, total: number): number {
  return total > 0 ? (value / total) * 100 : 0;
}

/** Display form: "0%", "<1%", "33%". Whole numbers so rounded parts may not sum to exactly 100. */
export function formatPercent(value: number, total: number): string {
  const pct = percentOf(value, total);
  if (pct === 0) {
    return '0%';
  }
  return pct < 1 ? '<1%' : `${Math.round(pct)}%`;
}

export interface DonutSegment<T> {
  item: T;
  value: number;
  /** Arc length in a 100-unit circumference (stroke-dasharray friendly). */
  length: number;
  /** Where the arc starts, 0–100 (stroke-dashoffset = 25 - start puts 0 at 12 o'clock). */
  start: number;
}

/** Proportional arcs; zero values produce no arc. `gap` (in circumference units) separates adjacent arcs. */
export function donutSegments<T>(items: T[], valueOf: (item: T) => number, gap = 0.8): DonutSegment<T>[] {
  const total = items.reduce((sum, item) => sum + Math.max(0, valueOf(item)), 0);
  if (total === 0) {
    return [];
  }
  const visible = items.filter((item) => valueOf(item) > 0);
  const effectiveGap = visible.length > 1 ? gap : 0;
  let cursor = 0;
  return visible.map((item) => {
    const value = valueOf(item);
    const share = (value / total) * 100;
    const segment = { item, value, start: cursor, length: Math.max(0, share - effectiveGap) };
    cursor += share;
    return segment;
  });
}

export interface PlotPoint {
  x: number;
  y: number;
  value: number;
}

/** Maps values onto a plot box. Straight segments only — no smoothing that could overshoot the real data. */
export function plotPoints(values: number[], width: number, height: number, max: number): PlotPoint[] {
  if (values.length === 0) {
    return [];
  }
  const step = values.length > 1 ? width / (values.length - 1) : 0;
  return values.map((value, index) => ({
    x: values.length > 1 ? index * step : width / 2,
    y: height - (max > 0 ? (value / max) * height : 0),
    value,
  }));
}

export function linePath(points: PlotPoint[]): string {
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ');
}

export function areaPath(points: PlotPoint[], baseline: number): string {
  if (points.length === 0) {
    return '';
  }
  const first = points[0];
  const last = points[points.length - 1];
  return `${linePath(points)} L${last.x.toFixed(2)},${baseline} L${first.x.toFixed(2)},${baseline} Z`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** "2026-03" → "Mar" (or "Mar 26" with year). */
export function monthLabel(isoMonth: string, withYear = false): string {
  const [year, month] = isoMonth.split('-').map(Number);
  return withYear ? `${MONTHS[month - 1]} ${String(year).slice(2)}` : MONTHS[month - 1];
}

/** "2026-03" → "March 2026". */
export function monthLongLabel(isoMonth: string): string {
  const [year, month] = isoMonth.split('-').map(Number);
  return `${MONTHS_LONG[month - 1]} ${year}`;
}

/** Show every n-th x label so labels never collide at the available width. */
export function labelStride(count: number, width: number, minSpacing = 56): number {
  if (count <= 1 || width <= 0) {
    return 1;
  }
  return Math.max(1, Math.ceil((minSpacing * count) / width));
}

export function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : pluralForm}`;
}
