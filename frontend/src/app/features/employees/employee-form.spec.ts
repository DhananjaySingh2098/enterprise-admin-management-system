import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EmployeeDetail } from '../../core/api/api.models';
import { page, testProviders } from '../../core/auth/auth.testing';
import { EmployeeForm, EmployeeFormMode } from './employee-form';

const DETAIL: EmployeeDetail = {
  id: 1, employeeCode: 'ENG-001', firstName: 'Katherine', lastName: 'Johnson', email: 'kj@corp.example', phone: null,
  jobTitle: 'Mathematician', department: { id: 3, name: 'Engineering', code: 'ENG', active: true }, status: 'ACTIVE',
  hireDate: '2024-02-01', linkedUser: null, version: 4, createdAt: '', updatedAt: '',
};
const DEPARTMENTS = page([{ id: 3, name: 'Engineering', code: 'ENG', active: true, description: null, headcount: 1, createdAt: '', updatedAt: '' }]);

describe('EmployeeForm', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<EmployeeForm>;
  let el: HTMLElement;

  async function render(mode: EmployeeFormMode, isAdmin = false, detail: EmployeeDetail | null = DETAIL) {
    TestBed.configureTestingModule({ imports: [EmployeeForm], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmployeeForm);
    fixture.componentRef.setInput('open', true);
    fixture.componentRef.setInput('mode', mode);
    fixture.componentRef.setInput('employeeId', detail?.id ?? null);
    fixture.componentRef.setInput('isAdmin', isAdmin);
    el = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/departments').flush(DEPARTMENTS);
    if (mode !== 'create' && detail) {
      http.expectOne('/api/employees/1').flush(detail);
    }
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const field = (id: string) => el.querySelector<HTMLInputElement>('#' + id)!;
  function type(id: string, value: string) {
    field(id).value = value;
    field(id).dispatchEvent(new Event('input'));
  }
  async function submit() {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('sends the loaded version with updates', async () => {
    await render('edit');
    type('e-title', 'Lead Mathematician');
    await submit();
    const req = http.expectOne('/api/employees/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toMatchObject({ jobTitle: 'Lead Mathematician', version: 4, departmentId: 3, userId: null });
    req.flush({ ...DETAIL, jobTitle: 'Lead Mathematician', version: 5 });
  });

  it('shows a clear conflict on 409 STALE_VERSION and does not retry', async () => {
    await render('edit');
    type('e-title', 'My stale edit');
    await submit();
    http.expectOne('/api/employees/1').flush(
      { status: 409, code: 'STALE_VERSION', message: 'This record was updated by someone else. Reload the latest version before saving again.' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.querySelector('.alert-warning')?.textContent).toContain(
      'This employee was updated by someone else. Reload the latest version before saving again.',
    );
    expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
    http.expectNone('/api/employees/1');

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Reload latest'))!.click();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/departments').flush(DEPARTMENTS);
    http.expectOne('/api/employees/1').flush({ ...DETAIL, jobTitle: 'Changed by someone else', version: 5 });
    await fixture.whenStable();
    expect(field('e-title').value).toBe('Changed by someone else');
    expect(el.querySelector('.alert-warning')).toBeNull();
  });

  it('validates required fields and formats', async () => {
    await render('create', false, null);
    type('e-code', '!');
    type('e-email', 'bad');
    type('e-phone', 'abc');
    await submit();
    expect(el.querySelector('#e-code-err')).not.toBeNull();
    expect(el.querySelector('#e-email-err')?.textContent).toContain('valid email');
    expect(el.querySelector('#e-phone-err')).not.toBeNull();
    expect(el.querySelector('#e-dept-err')).not.toBeNull();
    http.expectNone('/api/employees');
  });

  it('managers create ACTIVE employees without status or account controls', async () => {
    await render('create', false, null);
    expect(el.querySelector<HTMLSelectElement>('#e-status')!.disabled).toBe(true);
    expect(el.querySelector('app-user-picker')).toBeNull();
    type('e-code', 'ENG-009');
    type('e-first', 'Mary');
    type('e-last', 'Jackson');
    type('e-email', 'mj@corp.example');
    type('e-title', 'Engineer');
    const dept = el.querySelector<HTMLSelectElement>('#e-dept')!;
    dept.selectedIndex = 1;
    dept.dispatchEvent(new Event('change'));
    await submit();
    const req = http.expectOne('/api/employees');
    expect(req.request.body).toMatchObject({ employeeCode: 'ENG-009', status: 'ACTIVE', userId: null, departmentId: 3 });
    req.flush({ ...DETAIL, id: 9 });
  });

  it('maps duplicate code errors onto the field', async () => {
    await render('edit');
    await submit();
    http.expectOne('/api/employees/1').flush(
      { status: 409, code: 'DUPLICATE_CODE', message: 'An employee with this code already exists.',
        fieldErrors: [{ field: 'employeeCode', message: 'An employee with this code already exists.' }] },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.querySelector('#e-code-err')?.textContent).toContain('already exists');
  });

  it('view mode is fully read-only', async () => {
    await render('view');
    expect(field('e-title').disabled).toBe(true);
    expect(el.querySelector('button[type="submit"]')).toBeNull();
    expect(el.textContent).toContain('Not linked');
  });
});
