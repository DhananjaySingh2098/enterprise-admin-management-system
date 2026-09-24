import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { UserDetail } from '../../core/api/api.models';
import { testProviders } from '../../core/auth/auth.testing';
import { UserForm } from './user-form';

const EXISTING: UserDetail = {
  id: 8, email: 'grace@example.com', firstName: 'Grace', lastName: 'Hopper', roles: ['USER'], enabled: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
};

describe('UserForm', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<UserForm>;
  let el: HTMLElement;
  let saved: UserDetail[];

  async function render(user: UserDetail | null, currentUserId = 1) {
    TestBed.configureTestingModule({ imports: [UserForm], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UserForm);
    fixture.componentRef.setInput('open', true);
    fixture.componentRef.setInput('user', user);
    fixture.componentRef.setInput('currentUserId', currentUserId);
    saved = [];
    fixture.componentInstance.saved.subscribe((u) => saved.push(u));
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const input = (id: string) => el.querySelector<HTMLInputElement>('#' + id)!;
  function type(id: string, value: string) {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
  }
  async function submit() {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('validates required fields, email, password length and roles', async () => {
    await render(null);
    (el.querySelector('input[type="checkbox"]:not([formcontrolname="enabled"])') as HTMLInputElement);
    const userRole = [...el.querySelectorAll<HTMLInputElement>('fieldset input')].find((i) => i.parentElement?.textContent?.includes('USER'))!;
    userRole.click();
    type('u-email', 'nope');
    type('u-password', 'short');
    await submit();
    expect(el.querySelector('#u-first-err')?.textContent).toContain('First name is required');
    expect(el.querySelector('#u-email-err')?.textContent).toContain('valid email');
    expect(el.textContent).toContain('Use at least 12 characters');
    expect(el.querySelector('#u-roles-err')?.textContent).toContain('Select at least one role');
    expect(input('u-email').getAttribute('aria-invalid')).toBe('true');
    http.expectNone('/api/users');
  });

  it('creates a user with the selected roles and never keeps the password', async () => {
    await render(null);
    type('u-first', 'Grace');
    type('u-last', 'Hopper');
    type('u-email', 'grace@example.com');
    type('u-password', 'Initial-Password-2026');
    const manager = [...el.querySelectorAll<HTMLInputElement>('fieldset input')].find((i) => i.parentElement?.textContent?.includes('MANAGER'))!;
    manager.click();
    await submit();

    const req = http.expectOne('/api/users');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      firstName: 'Grace', lastName: 'Hopper', email: 'grace@example.com', initialPassword: 'Initial-Password-2026',
      roles: ['MANAGER', 'USER'], enabled: true,
    });
    req.flush({ ...EXISTING, roles: ['MANAGER', 'USER'] });
    await fixture.whenStable();
    expect(saved.length).toBe(1);
    expect(fixture.componentInstance['form'].controls.initialPassword.value).toBe('');
  });

  it('maps a duplicate-email conflict onto the email field', async () => {
    await render(null);
    type('u-first', 'Grace');
    type('u-last', 'Hopper');
    type('u-email', 'taken@example.com');
    type('u-password', 'Initial-Password-2026');
    await submit();
    http.expectOne('/api/users').flush(
      { status: 409, message: 'A user with this email already exists.', code: 'DUPLICATE_EMAIL',
        fieldErrors: [{ field: 'email', message: 'A user with this email already exists.' }] },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.querySelector('#u-email-err')?.textContent).toContain('already exists');
    expect(saved.length).toBe(0);
  });

  it('edit mode hides the password and only calls the endpoints that changed', async () => {
    await render(EXISTING);
    expect(input('u-password')).toBeNull();
    type('u-first', 'Amazing Grace');
    const admin = [...el.querySelectorAll<HTMLInputElement>('fieldset input')].find((i) => i.parentElement?.textContent?.includes('ADMIN'))!;
    admin.click();
    await submit();

    const details = http.expectOne('/api/users/8');
    expect(details.request.method).toBe('PUT');
    expect(details.request.body).toEqual({ firstName: 'Amazing Grace', lastName: 'Hopper', email: 'grace@example.com' });
    details.flush({ ...EXISTING, firstName: 'Amazing Grace' });
    const roles = http.expectOne('/api/users/8/roles');
    expect(roles.request.body).toEqual({ roles: ['ADMIN', 'USER'] });
    roles.flush({ ...EXISTING, roles: ['ADMIN', 'USER'] });
    http.expectNone('/api/users/8/status');
    await fixture.whenStable();
    expect(saved[0].roles).toEqual(['ADMIN', 'USER']);
  });

  it('locks the ADMIN role and enabled switch when an admin edits themselves', async () => {
    await render({ ...EXISTING, id: 1, roles: ['ADMIN'] }, 1);
    const admin = [...el.querySelectorAll<HTMLInputElement>('fieldset input')].find((i) => i.parentElement?.textContent?.includes('ADMIN'))!;
    expect(admin.disabled).toBe(true);
    expect(el.querySelector<HTMLInputElement>('input[formcontrolname="enabled"]')!.disabled).toBe(true);
    expect(el.textContent).toContain('You cannot disable your own account');
  });
});
