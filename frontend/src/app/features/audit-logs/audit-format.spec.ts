import { AuditLogEntry } from '../../core/api/api.models';
import { detailRows, detailsSummary } from './audit-format';

const entry = (details: Record<string, unknown>): AuditLogEntry => ({
  id: 1, createdAt: '2026-03-01T09:00:00Z', actor: {}, action: 'USER_UPDATED', outcome: 'SUCCESS', details,
});

describe('audit formatting', () => {
  it('summarizes the common detail shapes in words', () => {
    expect(detailsSummary(entry({ changedFields: ['jobTitle', 'phone'] }))).toBe('Changed job title, phone');
    expect(detailsSummary(entry({ reason: 'INVALID_CREDENTIALS' }))).toBe('Invalid credentials');
    expect(detailsSummary(entry({ reason: 'REUSE_DETECTED', revokedCount: 2 }))).toBe('Sign-in token reused');
    expect(detailsSummary(entry({ from: ['USER'], to: ['MANAGER', 'USER'] }))).toBe('USER → MANAGER, USER');
    expect(detailsSummary(entry({ roles: ['ADMIN'], enabled: true }))).toBe('Roles: ADMIN');
    expect(detailsSummary(entry({ revokedCount: 1 }))).toBe('1 sign-in token revoked');
    expect(detailsSummary(entry({}))).toBe('—');
  });

  it('lists every detail for the drawer, including nested changes', () => {
    expect(detailRows(entry({
      changedFields: ['recentHireWindowDays'],
      changes: { recentHireWindowDays: { from: 30, to: 7 } },
    }))).toEqual([
      { label: 'Changed fields', value: 'Recent-hire window' },
      { label: 'Recent-hire window', value: '30 → 7' },
    ]);
    expect(detailRows(entry({ from: 'ACTIVE', to: 'ON_LEAVE' }))).toEqual([{ label: 'Change', value: 'ACTIVE → ON_LEAVE' }]);
    expect(detailRows(entry({ linkedAccount: false, source: 'PROFILE' }))).toEqual([
      { label: 'Linked account', value: 'No' },
      { label: 'Source', value: 'PROFILE' },
    ]);
  });
});
