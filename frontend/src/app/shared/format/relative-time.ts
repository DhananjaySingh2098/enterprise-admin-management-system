const RELATIVE = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
const DATE = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
const FULL = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** "just now", "5 minutes ago", "yesterday" … up to a week; older dates are shown as a date. */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 45) {
    return 'just now';
  }
  if (abs < 3600) {
    return RELATIVE.format(Math.round(seconds / 60), 'minute');
  }
  if (abs < 86_400) {
    return RELATIVE.format(Math.round(seconds / 3600), 'hour');
  }
  if (abs < 7 * 86_400) {
    return RELATIVE.format(Math.round(seconds / 86_400), 'day');
  }
  return DATE.format(new Date(iso));
}

/** Absolute timestamp for tooltips and screen readers. */
export function absoluteTime(iso: string): string {
  return FULL.format(new Date(iso));
}
