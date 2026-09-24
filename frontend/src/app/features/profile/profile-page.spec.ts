import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Profile } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { authResponse, signInAs, testProviders } from '../../core/auth/auth.testing';
import { ProfilePage } from './profile-page';

const PROFILE: Profile = { id: 7, email: 'ada@example.com', firstName: 'Ada', lastName: 'Lovelace', roles: ['USER'], createdAt: '2026-01-05T00:00:00Z' };

describe('ProfilePage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ProfilePage>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [ProfilePage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(['USER']);
    fixture = TestBed.createComponent(ProfilePage);
    el = fixture.nativeElement;
    await fixture.whenStable();
    const req = http.expectOne('/api/profile');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-USER');
    req.flush(PROFILE);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function type(id: string, value: string) {
    const input = el.querySelector<HTMLInputElement>('#' + id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('shows avatar, name, email and roles', () => {
    expect(el.querySelector('.profile-card h2')?.textContent).toContain('Ada Lovelace');
    expect(el.querySelector('.profile-card .avatar')?.textContent?.trim()).toBe('AL');
    expect(el.querySelector('.profile-card')?.textContent).toContain('USER');
  });

  it('updates own details through PUT /api/profile (no id sent) and syncs the header', async () => {
    type('p-first', 'Augusta');
    el.querySelectorAll('form')[0].dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/profile');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ firstName: 'Augusta', lastName: 'Lovelace', email: 'ada@example.com' });
    req.flush({ ...PROFILE, firstName: 'Augusta' });
    await fixture.whenStable();
    expect(TestBed.inject(AuthService).user()?.firstName).toBe('Augusta');
  });

  it('changes password and adopts the fresh session', async () => {
    type('pw-current', 'old-password-123');
    type('pw-new', 'Brand-New-Password-42');
    type('pw-confirm', 'Brand-New-Password-42');
    el.querySelectorAll('form')[1].dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/profile/password');
    expect(req.request.body).toEqual({ currentPassword: 'old-password-123', newPassword: 'Brand-New-Password-42', confirmPassword: 'Brand-New-Password-42' });
    req.flush(authResponse('fresh-token'));
    await fixture.whenStable();
    expect(TestBed.inject(AuthService).getAccessToken()).toBe('fresh-token');
    expect(el.querySelector<HTMLInputElement>('#pw-current')!.value).toBe('');
  });

  it('shows the server error on the current-password field (400, not a logout)', async () => {
    type('pw-current', 'wrong-password');
    type('pw-new', 'Brand-New-Password-42');
    type('pw-confirm', 'Brand-New-Password-42');
    el.querySelectorAll('form')[1].dispatchEvent(new Event('submit'));
    http.expectOne('/api/profile/password').flush(
      { status: 400, code: 'INVALID_CURRENT_PASSWORD', message: 'Current password is incorrect.',
        fieldErrors: [{ field: 'currentPassword', message: 'Current password is incorrect.' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(el.querySelector('#pw-current-err')?.textContent).toContain('Current password is incorrect.');
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
  });

  it('validates confirmation and length locally', async () => {
    type('pw-current', 'x');
    type('pw-new', 'short');
    type('pw-confirm', 'different');
    el.querySelectorAll('form')[1].dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(el.textContent).toContain('Use 12 to 72 characters');
    expect(el.querySelector('#pw-confirm-err')?.textContent).toContain('Passwords do not match');
    http.expectNone('/api/profile/password');
  });
});
