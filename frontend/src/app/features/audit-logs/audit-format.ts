import { AuditLogEntry } from '../../core/api/api.models';
import { AUDIT_FIELD_LABEL } from '../../shared/format/labels';

const REASON_LABEL: Record<string, string> = {
  INVALID_CREDENTIALS: 'Invalid credentials',
  UNKNOWN_ACCOUNT: 'Unknown account',
  ACCOUNT_DISABLED: 'Account disabled',
  LOCKED_OUT: 'Temporarily locked out',
  INVALID_CURRENT_PASSWORD: 'Wrong current password',
  REUSE_DETECTED: 'Sign-in token reused',
};

export interface DetailRow {
  label: string;
  value: string;
}

export function fieldLabel(key: string): string {
  return AUDIT_FIELD_LABEL[key] ?? key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase());
}

function words(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  if (Array.isArray(value)) {
    return value.length ? value.map(words).join(', ') : 'none';
  }
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    if ('from' in record || 'to' in record) {
      return `${words(record['from'])} → ${words(record['to'])}`;
    }
    return Object.entries(record).map(([k, v]) => `${fieldLabel(k)}: ${words(v)}`).join('; ');
  }
  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }
  const text = String(value);
  return REASON_LABEL[text] ?? text;
}

/** One-line description for the table's Details column. */
export function detailsSummary(entry: AuditLogEntry): string {
  const d = entry.details ?? {};
  if (Array.isArray(d['changedFields']) && d['changedFields'].length) {
    return `Changed ${(d['changedFields'] as string[]).map((f) => fieldLabel(f).toLowerCase()).join(', ')}`;
  }
  if (typeof d['reason'] === 'string') {
    return words(d['reason']);
  }
  if ('from' in d || 'to' in d) {
    return `${words(d['from'])} → ${words(d['to'])}`;
  }
  if (Array.isArray(d['roles'])) {
    return `Roles: ${words(d['roles'])}`;
  }
  if (typeof d['revokedCount'] === 'number') {
    return `${d['revokedCount']} sign-in token${d['revokedCount'] === 1 ? '' : 's'} revoked`;
  }
  return '—';
}

/** Every stored detail as label/value rows for the detail drawer (the server has already redacted them). */
export function detailRows(entry: AuditLogEntry): DetailRow[] {
  const d = entry.details ?? {};
  if ('from' in d || 'to' in d) {
    const rest = Object.entries(d).filter(([k]) => k !== 'from' && k !== 'to');
    return [{ label: 'Change', value: `${words(d['from'])} → ${words(d['to'])}` }, ...rest.map(([k, v]) => ({ label: fieldLabel(k), value: words(v) }))];
  }
  return Object.entries(d).flatMap(([key, value]) => {
    if (key === 'changes' && value && typeof value === 'object' && !Array.isArray(value)) {
      return Object.entries(value as Record<string, unknown>).map(([k, v]) => ({ label: fieldLabel(k), value: words(v) }));
    }
    if (key === 'changedFields' && Array.isArray(value)) {
      return [{ label: 'Changed fields', value: (value as string[]).map(fieldLabel).join(', ') }];
    }
    return [{ label: fieldLabel(key), value: words(value) }];
  });
}
