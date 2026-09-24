import { expect, test as setup } from '@playwright/test';

import { apiToken, authHeaders } from './support/app';
import { E2E } from './support/fixtures';

/**
 * Deterministic E2E data, created through the public API as the bootstrap administrator. Idempotent, so it is safe
 * when the servers (and schema) are reused between local runs.
 */
setup('seed deterministic data', async ({ request }) => {
  const token = await apiToken(request, E2E.admin);
  const headers = authHeaders(token);

  const departments = [
    { name: 'E2E Engineering', code: 'E2E-ENG', description: 'Seeded for end-to-end tests' },
    { name: 'E2E Operations', code: 'E2E-OPS', description: 'Seeded for end-to-end tests' },
  ];
  const deptIds: Record<string, number> = {};
  for (const dept of departments) {
    const existing = await (await request.get(`/api/departments?search=${dept.code}`, { headers })).json();
    const found = existing.content.find((d: { code: string }) => d.code === dept.code);
    if (found) {
      deptIds[dept.code] = found.id;
    } else {
      const created = await request.post('/api/departments', { headers, data: dept });
      expect(created.status()).toBe(201);
      deptIds[dept.code] = (await created.json()).id;
    }
  }

  const accounts = [
    { ...E2E.manager, roles: ['MANAGER'] },
    { ...E2E.user, roles: ['USER'] },
    { ...E2E.otherUser, roles: ['USER'] },
  ];
  const userIds: Record<string, number> = {};
  for (const account of accounts) {
    const existing = await (await request.get(`/api/users?search=${encodeURIComponent(account.email)}`, { headers })).json();
    if (existing.content.length) {
      userIds[account.email] = existing.content[0].id;
      continue;
    }
    const created = await request.post('/api/users', {
      headers,
      data: { firstName: account.firstName, lastName: account.lastName, email: account.email,
        initialPassword: account.password, roles: account.roles, enabled: true },
    });
    expect(created.status()).toBe(201);
    userIds[account.email] = (await created.json()).id;
  }

  const employees = [
    { employeeCode: 'E2E-001', firstName: 'Ada', lastName: 'Seed', email: 'ada.seed@corp.e2e', jobTitle: 'Engineer', dept: 'E2E-ENG', hireDate: '2024-02-01' },
    { employeeCode: 'E2E-002', firstName: 'Grace', lastName: 'Seed', email: 'grace.seed@corp.e2e', jobTitle: 'Analyst', dept: 'E2E-OPS', hireDate: '2025-05-12' },
    { employeeCode: 'E2E-003', firstName: 'Uma', lastName: 'User', email: 'uma.user@corp.e2e', jobTitle: 'Coordinator', dept: 'E2E-OPS', hireDate: '2025-09-01',
      userId: userIds[E2E.user.email] },
  ];
  for (const e of employees) {
    const existing = await (await request.get(`/api/employees?search=${e.employeeCode}`, { headers })).json();
    if (existing.content.length) {
      continue;
    }
    const { dept, ...fields } = e;
    const created = await request.post('/api/employees', {
      headers, data: { phone: null, userId: null, status: 'ACTIVE', ...fields, departmentId: deptIds[dept] },
    });
    expect(created.status(), await created.text()).toBe(201);
  }
});
