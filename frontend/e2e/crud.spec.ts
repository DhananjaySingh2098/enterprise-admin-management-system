import { APIRequestContext, expect, test } from '@playwright/test';

import { E2E, apiToken, authHeaders, login, unique } from './support/app';

async function adminApi(request: APIRequestContext) {
  return authHeaders(await apiToken(request, E2E.admin));
}

test.describe('ADMIN CRUD through the UI', () => {
  test('department: create, edit, deactivate and reactivate', async ({ page, request }) => {
    const code = unique('D').toUpperCase().slice(0, 12);
    await login(page, E2E.admin, '/departments');
    await page.getByRole('button', { name: 'Add department' }).click();
    await page.locator('#d-name').fill(`Dept ${code}`);
    await page.locator('#d-code').fill(code.toLowerCase());
    await page.locator('#d-desc').fill('Created by E2E');
    await page.locator('button[type="submit"][form="department-form"]').click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    await expect(page.getByRole('row', { name: new RegExp(code) })).toBeVisible();

    await page.getByRole('button', { name: `Edit Dept ${code}` }).click();
    await page.locator('#d-name').fill(`Dept ${code} renamed`);
    await page.locator('button[type="submit"][form="department-form"]').click();
    await expect(page.getByRole('row', { name: new RegExp(`Dept ${code} renamed`) })).toBeVisible();

    await page.getByRole('button', { name: `Deactivate Dept ${code} renamed` }).click();
    await page.locator('dialog[open]').getByRole('button', { name: 'Deactivate' }).click();
    await expect(page.getByRole('button', { name: `Activate Dept ${code} renamed` })).toBeVisible();

    const headers = await adminApi(request);
    const found = (await (await request.get(`/api/departments?search=${code}`, { headers })).json()).content[0];
    expect(found).toMatchObject({ code, name: `Dept ${code} renamed`, active: false });
  });

  test('employee: create, edit and change status (with optimistic locking)', async ({ page, request }) => {
    const code = unique('EMP').toUpperCase().slice(0, 14);
    await login(page, E2E.admin, '/employees');
    await page.getByRole('button', { name: /Add employee/ }).click();
    await page.locator('#e-code').fill(code);
    await page.locator('#e-title').fill('QA Engineer');
    await page.locator('#e-first').fill('Edsger');
    await page.locator('#e-last').fill(`Test ${code}`);
    await page.locator('#e-email').fill(`${code.toLowerCase()}@corp.e2e`);
    await page.locator('#e-dept').selectOption({ label: 'E2E Engineering (E2E-ENG)' });
    await page.locator('#e-hire').fill('2025-06-02');
    await page.locator('button[type="submit"][form="employee-form"]').click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);

    await page.locator('.toolbar input[type="search"]').fill(code);
    await expect(page.getByRole('row', { name: new RegExp(code) })).toBeVisible();
    await page.getByRole('button', { name: `Edit Edsger Test ${code}` }).click();
    await expect(page.locator('#e-title')).toHaveValue('QA Engineer');
    await page.locator('#e-title').fill('Senior QA Engineer');
    await page.locator('#e-status').selectOption('ON_LEAVE');
    await page.locator('button[type="submit"][form="employee-form"]').click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    await expect(page.getByRole('row', { name: new RegExp(code) })).toContainText('On leave');

    const headers = await adminApi(request);
    const employee = (await (await request.get(`/api/employees?search=${code}`, { headers })).json()).content[0];
    expect(employee).toMatchObject({ jobTitle: 'Senior QA Engineer', status: 'ON_LEAVE' });
    // A stale version is rejected by the API (the UI shows "Reload latest" in that case).
    const detail = await (await request.get(`/api/employees/${employee.id}`, { headers })).json();
    const stale = await request.put(`/api/employees/${employee.id}/status`, { headers, data: { status: 'ACTIVE', version: detail.version - 1 } });
    expect(stale.status()).toBe(409);
  });

  test('user: create, edit, change roles and status — safeguards respected', async ({ page, request }) => {
    const email = `${unique('crud')}@enterprise-admin.test`;
    await login(page, E2E.admin, '/users');
    await page.getByRole('button', { name: /Add user/ }).click();
    await page.locator('#u-first').fill('Casey');
    await page.locator('#u-last').fill('Crud');
    await page.locator('#u-email').fill(email);
    await page.locator('#u-password').fill('Crud-Initial-Password-2026');
    await page.locator('#user-form').getByLabel('USER', { exact: true }).check();
    await page.locator('button[type="submit"][form="user-form"]').click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);

    await page.locator('.toolbar input[type="search"]').fill(email);
    const row = page.getByRole('row', { name: new RegExp(email) });
    await expect(row).toBeVisible();
    await page.getByRole('button', { name: 'Edit Casey Crud' }).click();
    await page.locator('#u-last').fill('Updated');
    await page.locator('#user-form').getByLabel('MANAGER', { exact: true }).check();
    await page.locator('button[type="submit"][form="user-form"]').click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    await expect(page.getByRole('row', { name: /Casey Updated/ })).toContainText('MANAGER');

    await page.getByRole('button', { name: 'Disable Casey Updated' }).click();
    await page.locator('dialog[open]').getByRole('button', { name: 'Disable user' }).click();
    await expect(page.getByRole('row', { name: /Casey Updated/ })).toContainText('Disabled');

    // Safeguard: an administrator cannot disable themselves (the button is disabled, and the API refuses).
    await page.locator('.toolbar input[type="search"]').fill(E2E.admin.email);
    await expect(page.getByRole('button', { name: /Disable E2E Administrator/ })).toBeDisabled();
    const headers = await adminApi(request);
    const me = (await (await request.get('/api/auth/me', { headers })).json()).id;
    expect((await request.put(`/api/users/${me}/status`, { headers, data: { enabled: false } })).status()).toBe(409);

    const created = (await (await request.get(`/api/users?search=${encodeURIComponent(email)}`, { headers })).json()).content[0];
    expect(created).toMatchObject({ lastName: 'Updated', enabled: false });
    expect([...created.roles].sort()).toEqual(['MANAGER', 'USER']);
  });
});
