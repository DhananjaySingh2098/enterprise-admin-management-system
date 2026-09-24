import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { signInAs, testProviders } from '../../core/auth/auth.testing';
import { Home } from './home';

describe('Home (overview)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [Home], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    http.expectOne('/api/health').flush({ status: 'UP', service: 'backend', timestamp: '' });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('greets the user and shows email, roles and API status', async () => {
    await signInAs(['ADMIN', 'MANAGER']);
    const el = await render();
    expect(el.querySelector('h1')?.textContent).toContain('Welcome, Ada');
    expect(el.textContent).toContain('ada@example.com');
    expect([...el.querySelectorAll('.role')].map((r) => r.textContent?.trim())).toEqual(['ADMIN', 'MANAGER']);
    expect(el.querySelector('[role="status"]')?.textContent).toContain('Online');
  });

  it('offers role-aware shortcuts and no analytics', async () => {
    await signInAs(['USER']);
    const el = await render();
    const labels = [...el.querySelectorAll('.shortcut strong')].map((s) => s.textContent?.trim());
    expect(labels).toEqual(['Employees', 'Departments', 'Profile', 'Settings']);
    expect(el.querySelector('canvas, svg.chart')).toBeNull();
  });

  it('shows the Users shortcut to administrators', async () => {
    await signInAs(['ADMIN']);
    const el = await render();
    expect([...el.querySelectorAll('.shortcut strong')].map((s) => s.textContent?.trim())).toContain('Users');
  });
});
