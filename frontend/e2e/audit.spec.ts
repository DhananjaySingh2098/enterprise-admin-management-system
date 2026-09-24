import { expect, test } from '@playwright/test';

import { E2E, apiToken, authHeaders, login, unique } from './support/app';

test('a real mutation appears in the audit trail with actor, action, target and request ID — and no secrets', async ({ page, request }) => {
  const headers = authHeaders(await apiToken(request, E2E.admin));
  const code = unique('AUD').toUpperCase().slice(0, 12);
  const created = await request.post('/api/departments', { headers, data: { name: `Audit ${code}`, code } });
  expect(created.status()).toBe(201);
  const requestId = created.headers()['x-request-id'];
  // A UUID from the backend, or nginx's 32-hex $request_id when running behind the production proxy.
  expect(requestId).toMatch(/^([0-9a-f]{32}|[0-9a-f-]{36})$/);
  // A wrong password must never be recorded.
  await request.post('/api/auth/login', { headers: { 'X-Requested-With': 'XMLHttpRequest' },
    data: { email: E2E.user.email, password: 'Leaked-Password-In-Audit-1' } });

  await login(page, E2E.admin, '/audit-logs');
  await page.locator('.toolbar input[type="search"]').fill(code);
  const row = page.locator('tbody tr.audit-row').first();
  await expect(row).toContainText('Department created');
  await expect(row.locator('[data-label="Actor"]')).toContainText(E2E.admin.email);
  await expect(row.locator('[data-label="Target"]')).toContainText(`${code} · Audit ${code}`);
  await expect(row.locator('[data-label="Outcome"]')).toContainText('Success');

  await row.click();
  const drawer = page.locator('dialog[open]');
  await expect(drawer).toContainText(requestId);
  await expect(drawer).toContainText(E2E.admin.email);
  await page.keyboard.press('Escape');
  await expect(page.locator('dialog[open]')).toHaveCount(0);

  const all = await (await request.get('/api/audit-logs?size=100', { headers })).text();
  expect(all).not.toContain('Leaked-Password-In-Audit-1');
  expect(all).not.toContain(E2E.admin.password);
  expect(all).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
  expect(all).toContain('INVALID_CREDENTIALS');
});
