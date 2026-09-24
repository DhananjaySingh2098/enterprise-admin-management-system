import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Department } from '../../core/api/api.models';
import { Role } from '../../core/auth/auth.models';
import { page, signInAs, testProviders } from '../../core/auth/auth.testing';
import { DepartmentsPage } from './departments-page';

const DEPTS: Department[] = [
  { id: 1, name: 'Engineering', code: 'ENG', description: 'Builds things', active: true, headcount: 12, createdAt: '', updatedAt: '' },
  { id: 2, name: 'Legacy Ops', code: 'OPS', description: null, active: false, headcount: 0, createdAt: '', updatedAt: '' },
];

describe('DepartmentsPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<DepartmentsPage>;
  let el: HTMLElement;

  async function renderAs(roles: Role[]) {
    TestBed.configureTestingModule({ imports: [DepartmentsPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(roles);
    fixture = TestBed.createComponent(DepartmentsPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/departments').flush(page(DEPTS));
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('shows name, code, description, real headcount and status', async () => {
    await renderAs(['USER']);
    const rows = el.querySelectorAll('tbody tr');
    expect(rows[0].querySelector('[data-label="Headcount"]')?.textContent?.trim()).toBe('12');
    expect(rows[1].querySelector('[data-label="Status"]')?.textContent).toContain('Inactive');
    expect(rows[1].querySelector('[data-label="Description"]')?.textContent).toContain('—');
  });

  it.each([[['MANAGER']], [['USER']]] as [Role[]][])('is read-only for %s', async (roles) => {
    await renderAs(roles);
    expect([...el.querySelectorAll('.page-header button')].map((b) => b.textContent?.trim())).toEqual([]);
    expect(el.querySelector('app-department-form')).toBeNull();
    expect(el.querySelector('button[aria-label^="Edit"]')).toBeNull();
    expect(el.querySelector('button[aria-label^="Deactivate"]')).toBeNull();
  });

  it('lets administrators deactivate after confirming', async () => {
    await renderAs(['ADMIN']);
    el.querySelector<HTMLButtonElement>('button[aria-label="Deactivate Engineering"]')!.click();
    await fixture.whenStable();
    const dialog = el.querySelector('dialog[open]') as HTMLElement;
    expect(dialog.textContent).toContain('Existing employees stay assigned');
    [...dialog.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Deactivate')!.click();
    const req = http.expectOne('/api/departments/1/status');
    expect(req.request.body).toEqual({ active: false });
    req.flush({ ...DEPTS[0], active: false });
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/departments').flush(page(DEPTS));
  });
});
