import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { signInAs, testProviders } from '../auth/auth.testing';
import { DashboardApi } from './dashboard.api';

describe('DashboardApi', () => {
  it('calls the read-only analytics endpoints with bearer auth', async () => {
    TestBed.configureTestingModule({ providers: testProviders() });
    const http = TestBed.inject(HttpTestingController);
    await signInAs(['ADMIN']);
    const api = TestBed.inject(DashboardApi);

    api.summary().subscribe();
    api.headcountByDepartment().subscribe();
    api.statusBreakdown().subscribe();
    api.hiringTrend(24).subscribe();
    api.recentEmployees(5).subscribe();
    api.users().subscribe();

    for (const url of ['/api/dashboard/summary', '/api/dashboard/headcount-by-department', '/api/dashboard/status-breakdown', '/api/dashboard/users']) {
      const req = http.expectOne(url);
      expect(req.request.method).toBe('GET');
      expect(req.request.headers.get('Authorization')).toBe('Bearer token-ADMIN');
      req.flush({});
    }
    expect(http.expectOne((r) => r.url === '/api/dashboard/hiring-trend').request.params.get('months')).toBe('24');
    expect(http.expectOne((r) => r.url === '/api/dashboard/recent-employees').request.params.get('limit')).toBe('5');
    http.verify();
  });
});
