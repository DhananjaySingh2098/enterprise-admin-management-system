import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { authInterceptor } from '../../../core/auth/auth.interceptor';
import { AuthService } from '../../../core/auth/auth.service';
import { authResponse } from '../../../core/auth/auth.testing';
import { Login, safeReturnUrl } from './login';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Login);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const email = () => el.querySelector<HTMLInputElement>('#email')!;
  const password = () => el.querySelector<HTMLInputElement>('#password')!;
  const submitButton = () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!;

  function type(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(): Promise<void> {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('renders an accessible form', () => {
    expect(el.querySelector('label[for="email"]')?.textContent).toContain('Email');
    expect(el.querySelector('label[for="password"]')?.textContent).toContain('Password');
    expect(email().getAttribute('autocomplete')).toBe('username');
    expect(password().getAttribute('autocomplete')).toBe('current-password');
    expect(password().type).toBe('password');
    expect(submitButton().textContent).toContain('Sign in');
  });

  it('shows required errors and focuses the first invalid field without calling the API', async () => {
    await submit();
    expect(el.querySelector('#email-error')?.textContent).toContain('Email is required');
    expect(el.querySelector('#password-error')?.textContent).toContain('Password is required');
    expect(email().getAttribute('aria-invalid')).toBe('true');
    expect(email().getAttribute('aria-describedby')).toBe('email-error');
    expect(document.activeElement).toBe(email());
    http.expectNone('/api/auth/login');
  });

  it('validates the email format', async () => {
    type(email(), 'not-an-email');
    type(password(), 'something-long');
    await submit();
    expect(el.querySelector('#email-error')?.textContent).toContain('valid email');
    http.expectNone('/api/auth/login');
  });

  it('submits, disables the button while sending, and navigates on success', async () => {
    fixture.componentRef.setInput('returnUrl', '/reports?q=1');
    type(email(), 'ada@example.com');
    type(password(), 'correct horse battery');
    await submit();

    expect(submitButton().disabled).toBe(true);
    expect(submitButton().textContent).toContain('Signing in');
    const req = http.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({ email: 'ada@example.com', password: 'correct horse battery' });

    req.flush(authResponse());
    await fixture.whenStable();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/reports?q=1');
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
  });

  it('shows a generic message on invalid credentials and clears the password', async () => {
    type(email(), 'ada@example.com');
    type(password(), 'wrong-password');
    await submit();
    http.expectOne('/api/auth/login').flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Invalid email or password.');
    expect(password().value).toBe('');
    expect(el.querySelector('#password-error')).toBeNull();
    expect(submitButton().disabled).toBe(false);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('explains lockouts without revealing account details', async () => {
    type(email(), 'ada@example.com');
    type(password(), 'wrong-password');
    await submit();
    http.expectOne('/api/auth/login').flush({}, { status: 429, statusText: 'Too Many Requests' });
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Too many sign-in attempts');
  });

  it('toggles password visibility accessibly', async () => {
    const toggle = el.querySelector<HTMLButtonElement>('.input-addon')!;
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    toggle.click();
    await fixture.whenStable();
    expect(password().type).toBe('text');
    expect(toggle.getAttribute('aria-label')).toBe('Hide password');
  });

  it('only accepts safe in-app return URLs', () => {
    expect(safeReturnUrl('/reports')).toBe('/reports');
    expect(safeReturnUrl('https://evil.example')).toBe('/');
    expect(safeReturnUrl('//evil.example')).toBe('/');
    expect(safeReturnUrl('/\\evil.example')).toBe('/');
    expect(safeReturnUrl('/login')).toBe('/');
    expect(safeReturnUrl(undefined)).toBe('/');
  });
});
