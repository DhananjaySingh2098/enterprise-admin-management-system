import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DashboardSummary, HiringTrend, UserAnalytics } from '../../core/api/dashboard.api';
import { Role } from '../../core/auth/auth.models';
import { settle, signInAs, testProviders } from '../../core/auth/auth.testing';
import { DashboardPage } from './dashboard-page';

const SUMMARY: DashboardSummary = {
  employees: { total: 16, active: 13, onLeave: 2, terminated: 1 },
  departments: { total: 6, active: 5, inactive: 1 },
  recentHires: { count: 1, windowDays: 30, from: '2026-08-24', to: '2026-09-22' },
  generatedAt: '2026-09-22T10:00:00Z',
};
const TREND: HiringTrend = {
  from: '2025-10-01', to: '2026-09-22', total: 3,
  months: Array.from({ length: 12 }, (_, i) => ({ month: `20${i < 3 ? '25' : '26'}-${String(((i + 9) % 12) + 1).padStart(2, '0')}`, hires: i === 11 ? 2 : i === 4 ? 1 : 0 })),
};
const USERS: UserAnalytics = {
  total: 5, enabled: 4, disabled: 1, multiRoleUsers: 1,
  roles: [{ role: 'ADMIN', users: 1, enabledUsers: 1 }, { role: 'MANAGER', users: 2, enabledUsers: 2 }, { role: 'USER', users: 3, enabledUsers: 2 }],
};

describe('DashboardPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<DashboardPage>;
  let el: HTMLElement;

  async function renderAs(roles: Role[], summary = SUMMARY) {
    TestBed.configureTestingModule({ imports: [DashboardPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(roles);
    fixture = TestBed.createComponent(DashboardPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
    if (roles.includes('ADMIN') || roles.includes('MANAGER')) {
      http.expectOne('/api/dashboard/summary').flush(summary);
      http.expectOne('/api/dashboard/headcount-by-department').flush([
        { departmentId: 1, name: 'Engineering', code: 'ENG', active: true, headcount: 5 },
        { departmentId: 2, name: 'Legacy Support', code: 'LEGACY', active: false, headcount: 0 },
      ]);
      http.expectOne('/api/dashboard/status-breakdown').flush([
        { status: 'ACTIVE', count: summary.employees.active }, { status: 'ON_LEAVE', count: summary.employees.onLeave },
        { status: 'TERMINATED', count: summary.employees.terminated },
      ]);
      http.expectOne((r) => r.url === '/api/dashboard/hiring-trend').flush(TREND);
      http.expectOne((r) => r.url === '/api/dashboard/recent-employees').flush([
        { id: 9, employeeCode: 'SAL-5015', firstName: 'Jordan', lastName: 'Blake', departmentName: 'Sales', jobTitle: 'Account Manager', status: 'ACTIVE', hireDate: '2026-09-22' },
      ]);
      if (roles.includes('ADMIN')) {
        http.expectOne('/api/dashboard/users').flush(USERS);
      }
      await fixture.whenStable();
    }
  }

  afterEach(() => http.verify());

  const kpis = () => [...el.querySelectorAll('app-kpi-card')].map((k) => [k.querySelector('.kpi-label')?.textContent?.trim(), k.querySelector('.kpi-value')?.textContent?.trim(), k.querySelector('.kpi-detail')?.textContent?.trim()]);

  it('greets the signed-in user and states real context', async () => {
    await renderAs(['ADMIN']);
    expect(el.querySelector('h1')?.textContent).toMatch(/^Good (morning|afternoon|evening), Ada\.$/);
    expect([...el.querySelectorAll('.hero-chips li')].map((li) => li.textContent?.trim()))
      .toEqual(['16 employees', '5 active departments', '1 hire in the last 30 days']);
    expect(el.querySelector('app-hero-stage')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.querySelector('.hero-updated')?.getAttribute('aria-label')).toBe('Refresh dashboard');
  });

  it('KPI meters show only real composition (no invented sparklines)', async () => {
    await renderAs(['MANAGER']);
    const total = el.querySelector('app-kpi-card')!;
    const segments = [...total.querySelectorAll<HTMLElement>('.kpi-meter span')].map((s) => [s.dataset['tone'], s.style.flexGrow]);
    expect(segments).toEqual([['success', '13'], ['warning', '2'], ['neutral', '1']]);
    expect(total.querySelector('.kpi-meter')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.querySelector('svg.sparkline, .trend-delta')).toBeNull();
  });

  it('recent hires are keyboard-reachable links to the employee record', async () => {
    await renderAs(['MANAGER']);
    const row = el.querySelector<HTMLAnchorElement>('.recent-table a.recent-link')!;
    expect(row.getAttribute('href')).toBe('/employees?open=9');
    expect(row.getAttribute('aria-label')).toContain('Open Jordan Blake');
    expect([...el.querySelectorAll('.recent-table thead th')].map((th) => th.textContent?.trim()).slice(0, 4))
      .toEqual(['Employee', 'Department', 'Status', 'Start date']);
  });

  it('renders KPI values and derived shares from the API only (no invented deltas)', async () => {
    await renderAs(['MANAGER']);
    expect(kpis()).toEqual([
      ['Total employees', '16', '15 current · 1 terminated'],
      ['Active', '13', '81% of all employees'],
      ['On leave', '2', '13% of all employees'],
      ['Departments', '6', '5 active · 1 inactive'],
    ]);
    expect(el.textContent).not.toMatch(/[+↑]\s?\d+%|up \d+%/);
  });

  it('shows charts, busiest month and recent hires for managers, but no system-access panel', async () => {
    await renderAs(['MANAGER']);
    expect(el.querySelectorAll('.bar-row').length).toBe(2);
    expect(el.querySelector('.bar-row.is-muted')?.textContent).toContain('Inactive');
    expect(el.querySelector('.donut-center strong')?.textContent).toBe('16');
    expect(el.textContent).toContain('3 hires in the last 12 months');
    expect(el.textContent).toContain('busiest: September 2026 (2 hires)');
    expect(el.querySelector('.recent-table tbody tr')?.textContent).toContain('Jordan Blake');
    expect(el.querySelector('a[href="/employees"]')?.textContent).toContain('View all employees');
    expect(el.querySelector('.access-panel')).toBeNull();
  });

  it('adds the ADMIN-only system-access panel with role semantics', async () => {
    await renderAs(['ADMIN']);
    const panel = el.querySelector('.access-panel')!;
    expect([...panel.querySelectorAll('.access-stats strong')].map((s) => s.textContent)).toEqual(['4', '1']);
    expect([...panel.querySelectorAll('.role-bars li')].map((li) => `${li.querySelector('.role-name')?.textContent} ${li.querySelector('.role-count')?.textContent}`))
      .toEqual(['Admins 1', 'Managers 2', 'Users 3']);
    expect(panel.textContent).toContain('1 account holds several roles');
    expect(panel.querySelector('a[href="/users"]')).not.toBeNull();
  });

  it('never calls analytics endpoints for USER and shows the personal workspace', async () => {
    await renderAs(['USER']);
    http.expectOne('/api/health').flush({ status: 'UP', service: 'x', timestamp: '' });
    http.expectNone((r) => r.url.startsWith('/api/dashboard'));
    expect(el.querySelector('.dash-grid')).toBeNull();
    expect(el.querySelector('app-home')).not.toBeNull();
  });

  it('replaces charts with a compact onboarding state when there are no employees', async () => {
    await renderAs(['ADMIN'], { ...SUMMARY, employees: { total: 0, active: 0, onLeave: 0, terminated: 0 }, departments: { total: 0, active: 0, inactive: 0 } });
    expect(el.querySelector('.dash-onboarding h2')?.textContent).toBe('No employees yet');
    expect(el.querySelector('.dash-onboarding a')?.textContent).toContain('Create department');
    expect(el.querySelector('app-kpi-card')).toBeNull();
    expect(el.querySelector('app-line-chart')).toBeNull();
  });

  it('offers "Add employee" when departments exist', async () => {
    await renderAs(['MANAGER'], { ...SUMMARY, employees: { total: 0, active: 0, onLeave: 0, terminated: 0 } });
    expect(el.querySelector('.dash-onboarding a')?.textContent).toContain('Add employee');
  });

  it('re-queries only the trend when switching to 24 months', async () => {
    await renderAs(['MANAGER']);
    [...el.querySelectorAll<HTMLButtonElement>('.range-toggle button')].find((b) => b.textContent === '24M')!.click();
    const req = http.expectOne((r) => r.url === '/api/dashboard/hiring-trend');
    expect(req.request.params.get('months')).toBe('24');
    req.flush({ ...TREND, total: 7 });
    await fixture.whenStable();
    expect(el.textContent).toContain('7 hires in the last 24 months');
    http.expectNone('/api/dashboard/summary');
  });

  it('shows a retryable error instead of fake data', async () => {
    TestBed.configureTestingModule({ imports: [DashboardPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(['MANAGER']);
    fixture = TestBed.createComponent(DashboardPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
    // forkJoin cancels the sibling requests once one fails.
    http.expectOne('/api/dashboard/summary').flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });
    http.match((r) => r.url.startsWith('/api/dashboard')); // drain the cancelled siblings
    await settle();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Something went wrong');
    expect(el.querySelector('app-kpi-card')).toBeNull();
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Try again'))!.click();
    http.expectOne('/api/dashboard/summary').flush({}, { status: 503, statusText: 'Unavailable' });
    http.match((r) => r.url.startsWith('/api/dashboard'));
  });
});
