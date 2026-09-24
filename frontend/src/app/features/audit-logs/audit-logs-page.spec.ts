import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AuditLogEntry } from '../../core/api/api.models';
import { page, settle, signInAs, testProviders } from '../../core/auth/auth.testing';
import { AuditLogsPage } from './audit-logs-page';

const LOGS: AuditLogEntry[] = [
  { id: 42, createdAt: new Date().toISOString(), actor: { id: 1, email: 'root@example.com' }, action: 'USER_ROLES_CHANGED',
    target: { type: 'USER', id: '9', label: 'grace@example.com' }, outcome: 'SUCCESS', ipAddress: '10.0.0.5', requestId: 'req-abc12345',
    details: { from: ['USER'], to: ['MANAGER'] } },
  { id: 41, createdAt: new Date().toISOString(), actor: {}, action: 'LOGIN_FAILURE', outcome: 'FAILURE', ipAddress: '10.0.0.9',
    requestId: 'req-def67890', details: { reason: 'UNKNOWN_ACCOUNT' } },
];

describe('AuditLogsPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AuditLogsPage>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [AuditLogsPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(['ADMIN']);
    fixture = TestBed.createComponent(AuditLogsPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  async function flush(content = LOGS, total = content.length): Promise<TestRequest> {
    const req = http.expectOne((r) => r.url === '/api/audit-logs');
    req.flush(page(content, { totalElements: total, totalPages: Math.max(1, Math.ceil(total / 20)) }));
    await fixture.whenStable();
    return req;
  }

  it('requests newest first and renders time, actor, action, target, outcome, IP and details', async () => {
    const req = await flush();
    expect(req.request.params.toString()).toBe('page=0&size=20&sort=createdAt&direction=desc');
    expect([...el.querySelectorAll('thead th')].map((th) => th.textContent?.trim())).toEqual(
      ['Time', 'Actor', 'Action', 'Target', 'Outcome', 'IP', 'Details']);
    const [first, second] = [...el.querySelectorAll<HTMLElement>('tbody tr.audit-row')];
    expect(first.querySelector('[data-label="Actor"]')?.textContent).toContain('root@example.com');
    expect(first.querySelector('[data-label="Action"]')?.textContent).toContain('Roles changed');
    expect(first.querySelector('[data-label="Target"]')?.textContent).toContain('grace@example.com');
    expect(first.querySelector('[data-label="Outcome"]')?.textContent).toContain('Success');
    expect(first.querySelector('[data-label="IP"]')?.textContent).toContain('10.0.0.5');
    expect(first.querySelector('[data-label="Details"]')?.textContent).toContain('USER → MANAGER');
    expect(second.querySelector('[data-label="Actor"]')?.textContent).toContain('Anonymous');
    expect(second.querySelector('[data-label="Outcome"] .badge')?.getAttribute('data-tone')).toBe('danger');
    expect(second.querySelector('[data-label="Details"]')?.textContent).toContain('Unknown account');
  });

  it('sends filters, date range and search to the server and resets to the first page', async () => {
    await flush();
    const selects = el.querySelectorAll<HTMLSelectElement>('.audit-toolbar select');
    selects[0].value = 'LOGIN_FAILURE';
    selects[0].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect((await flush()).request.params.get('action')).toBe('LOGIN_FAILURE');

    selects[1].value = 'USER';
    selects[1].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect((await flush()).request.params.get('entityType')).toBe('USER');

    [...el.querySelectorAll<HTMLButtonElement>('.pill-tabs button')].find((b) => b.textContent?.trim() === 'Failure')!.click();
    await fixture.whenStable();
    expect((await flush()).request.params.get('outcome')).toBe('FAILURE');

    const [from, to] = el.querySelectorAll<HTMLInputElement>('.date-range input');
    from.value = '2026-03-01';
    from.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    await flush();
    to.value = '2026-03-05';
    to.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const ranged = await flush();
    expect(ranged.request.params.get('from')).toBe('2026-03-01');
    expect(ranged.request.params.get('to')).toBe('2026-03-05');
    expect(to.min).toBe('2026-03-01');

    const search = el.querySelector<HTMLInputElement>('input[type="search"]')!;
    search.value = '10.0.0';
    search.dispatchEvent(new Event('input'));
    await settle(320);
    const searched = await flush();
    expect(searched.request.params.get('search')).toBe('10.0.0');
    expect(searched.request.params.get('page')).toBe('0');

    el.querySelector<HTMLButtonElement>('.audit-toolbar .btn-ghost')!.click();
    await fixture.whenStable();
    expect((await flush()).request.params.toString()).toBe('page=0&size=20&sort=createdAt&direction=desc');
  });

  it('paginates on the server', async () => {
    await flush(LOGS, 45);
    const next = el.querySelector<HTMLButtonElement>('[aria-label="Next page"]');
    expect(next).not.toBeNull();
    next!.click();
    await fixture.whenStable();
    expect((await flush()).request.params.get('page')).toBe('1');
  });

  it('opens a detail drawer with who, what, target, outcome, time, request ID and safe details', async () => {
    await flush();
    el.querySelector<HTMLElement>('tbody tr.audit-row')!.click();
    await fixture.whenStable();
    const detail = http.expectOne('/api/audit-logs/42');
    detail.flush(LOGS[0]);
    await fixture.whenStable();
    const drawer = document.querySelector('dialog[data-variant="drawer"]')!;
    expect(drawer.textContent).toContain('root@example.com');
    expect(drawer.textContent).toContain('grace@example.com');
    expect(drawer.textContent).toContain('req-abc12345');
    expect(drawer.textContent).toContain('USER → MANAGER');
    expect(drawer.textContent).toContain('Secrets are never recorded');
  });

  it('keeps the details button keyboard reachable with a descriptive label', async () => {
    await flush();
    const button = el.querySelector<HTMLButtonElement>('button.audit-open')!;
    expect(button.getAttribute('aria-label')).toContain('View details: Roles changed');
    button.click();
    await fixture.whenStable();
    http.expectOne('/api/audit-logs/42').flush(LOGS[0]);
  });

  it('shows an empty state instead of rows', async () => {
    await flush([]);
    expect(el.querySelector('app-empty-state')?.textContent).toContain('No audit events found');
  });
});
