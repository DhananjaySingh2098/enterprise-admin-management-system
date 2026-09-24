import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { UserSummary } from '../../core/api/api.models';
import { page, settle, signInAs, testProviders } from '../../core/auth/auth.testing';
import { UsersPage } from './users-page';

const USERS: UserSummary[] = [
  { id: 7, email: 'ada@example.com', firstName: 'Ada', lastName: 'Lovelace', roles: ['ADMIN'], enabled: true, createdAt: '2026-01-02T10:00:00Z' },
  { id: 8, email: 'grace@example.com', firstName: 'Grace', lastName: 'Hopper', roles: ['MANAGER', 'USER'], enabled: false, createdAt: '2026-02-03T10:00:00Z' },
];

describe('UsersPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<UsersPage>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [UsersPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(['ADMIN']);
    fixture = TestBed.createComponent(UsersPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function expectList(): TestRequest {
    return http.expectOne((req) => req.url === '/api/users');
  }

  async function flushList(content = USERS, total = content.length) {
    const req = expectList();
    req.flush(page(content, { totalElements: total }));
    await fixture.whenStable();
    return req;
  }

  it('requests the first page sorted by newest and renders rows with badges', async () => {
    const req = await flushList();
    expect(req.request.params.toString()).toBe('page=0&size=20&sort=createdAt&direction=desc');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-ADMIN');

    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Ada Lovelace');
    expect(rows[0].textContent).toContain('You');
    expect(rows[1].querySelector('[data-label="Status"]')?.textContent).toContain('Disabled');
    expect([...rows[1].querySelectorAll('[data-label="Roles"] .badge')].map((b) => b.textContent?.trim())).toEqual(['MANAGER', 'USER']);
    expect(el.querySelector('.pagination-summary')?.textContent?.replace(/\s+/g, ' ')).toContain('Showing 1–2 of 2');
  });

  it('debounces search and applies role/status filters server-side', async () => {
    await flushList();
    const input = el.querySelector<HTMLInputElement>('input[type="search"]')!;
    input.value = 'grace';
    input.dispatchEvent(new Event('input'));
    http.expectNone((req) => req.url === '/api/users');
    await settle(320);
    expect(expectList().request.params.get('search')).toBe('grace');

    const [roleSelect, statusSelect] = el.querySelectorAll<HTMLSelectElement>('.toolbar select');
    roleSelect.value = 'MANAGER';
    roleSelect.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(expectList().request.params.get('role')).toBe('MANAGER');
    statusSelect.value = 'false';
    statusSelect.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const filtered = expectList();
    expect(filtered.request.params.get('enabled')).toBe('false');
    expect(filtered.request.params.get('page')).toBe('0');
    filtered.flush(page([]));
  });

  it('sorts from column headers with aria-sort and paginates', async () => {
    await flushList(USERS, 45);
    const nameHeader = el.querySelector('th[aria-sort]')!;
    nameHeader.querySelector('button')!.click();
    await fixture.whenStable();
    expect(nameHeader.getAttribute('aria-sort')).toBe('ascending');
    expect(expectList().request.params.get('sort')).toBe('name');

    el.querySelector<HTMLButtonElement>('[aria-label="Next page"]')!.click();
    await fixture.whenStable();
    expect(expectList().request.params.get('page')).toBe('1');
  });

  it('prevents disabling your own account in the UI', async () => {
    await flushList();
    const selfDisable = el.querySelector<HTMLButtonElement>('button[aria-label="Disable Ada Lovelace"]')!;
    expect(selfDisable.disabled).toBe(true);
  });

  it('confirms before enabling/disabling and reloads afterwards', async () => {
    await flushList();
    el.querySelector<HTMLButtonElement>('button[aria-label="Enable Grace Hopper"]')!.click();
    await fixture.whenStable();
    const dialog = document.querySelector('dialog[open]') ?? el.querySelector('dialog[open]');
    expect(dialog?.textContent).toContain('Enable user?');
    [...(dialog as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Enable user'))!.click();

    const req = http.expectOne('/api/users/8/status');
    expect(req.request.body).toEqual({ enabled: true });
    req.flush({ ...USERS[1], enabled: true, updatedAt: '' });
    await fixture.whenStable();
    await flushList();
  });

  it('shows safeguard errors from the server inside the confirmation', async () => {
    await flushList([{ ...USERS[1], roles: ['ADMIN'], enabled: true }]);
    el.querySelector<HTMLButtonElement>('button[aria-label="Disable Grace Hopper"]')!.click();
    await fixture.whenStable();
    const dialog = el.querySelector('dialog[open]') as HTMLElement;
    [...dialog.querySelectorAll('button')].find((b) => b.textContent?.includes('Disable user'))!.click();
    http.expectOne('/api/users/8/status').flush(
      { status: 409, message: 'At least one active administrator must remain.', code: 'LAST_ADMIN' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toContain('At least one active administrator must remain.');
  });

  it('shows an empty state when nothing matches', async () => {
    await flushList([]);
    expect(el.textContent).toContain('No users found');
  });
});
