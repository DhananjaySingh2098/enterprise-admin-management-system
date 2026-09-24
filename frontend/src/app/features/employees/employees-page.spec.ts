import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EmployeeSummary } from '../../core/api/api.models';
import { Role } from '../../core/auth/auth.models';
import { page, signInAs, testProviders } from '../../core/auth/auth.testing';
import { EmployeesPage } from './employees-page';

const EMPLOYEES: EmployeeSummary[] = [
  { id: 1, employeeCode: 'ENG-001', firstName: 'Katherine', lastName: 'Johnson', email: 'kj@corp.example', jobTitle: 'Mathematician',
    department: { id: 3, name: 'Engineering', code: 'ENG', active: true }, status: 'ACTIVE', hireDate: '2024-02-01' },
  { id: 2, employeeCode: 'ENG-002', firstName: 'Dorothy', lastName: 'Vaughan', email: 'dv@corp.example', jobTitle: 'Supervisor',
    department: { id: 3, name: 'Engineering', code: 'ENG', active: true }, status: 'ON_LEAVE', hireDate: '2023-06-12' },
];

describe('EmployeesPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<EmployeesPage>;
  let el: HTMLElement;

  async function renderAs(roles: Role[]) {
    TestBed.configureTestingModule({ imports: [EmployeesPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(roles);
    fixture = TestBed.createComponent(EmployeesPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/departments').flush(page([{ id: 3, name: 'Engineering', code: 'ENG', active: true, description: null, headcount: 2, createdAt: '', updatedAt: '' }]));
    http.expectOne((r) => r.url === '/api/employees').flush(page(EMPLOYEES));
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('renders employees with code, department, status pill and hire date', async () => {
    await renderAs(['MANAGER']);
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('[data-label="Code"]')?.textContent).toContain('ENG-001');
    expect(rows[0].querySelector('[data-label="Department"]')?.textContent).toContain('Engineering');
    expect(rows[1].querySelector('[data-label="Status"]')?.textContent).toContain('On leave');
    expect(rows[0].querySelector('[data-label="Hire date"]')?.textContent).toContain('2024');
  });

  it('gives managers add/edit actions', async () => {
    await renderAs(['MANAGER']);
    expect(el.querySelector('.page-header button')?.textContent).toContain('Add employee');
    expect(el.querySelector('button[aria-label="Edit Katherine Johnson"]')).not.toBeNull();
  });

  it('is read-only for USER: no add button and a View action', async () => {
    await renderAs(['USER']);
    expect(el.querySelectorAll('.page-header button').length).toBe(0);
    expect(el.querySelector('button[aria-label="Edit Katherine Johnson"]')).toBeNull();
    expect(el.querySelector('button[aria-label="View Katherine Johnson"]')).not.toBeNull();
  });

  it('filters by department and status on the server', async () => {
    await renderAs(['USER']);
    const [deptSelect, statusSelect] = el.querySelectorAll<HTMLSelectElement>('.toolbar select');
    deptSelect.value = '3';
    deptSelect.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(http.expectOne((r) => r.url === '/api/employees').request.params.get('departmentId')).toBe('3');
    statusSelect.value = 'ON_LEAVE';
    statusSelect.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const req = http.expectOne((r) => r.url === '/api/employees');
    expect(req.request.params.get('status')).toBe('ON_LEAVE');
    expect(req.request.params.get('departmentId')).toBe('3');
    req.flush(page([]));
    await fixture.whenStable();
    expect(el.textContent).toContain('No employees found');
  });
});
