import { expect, test } from '@playwright/test';

import { E2E, apiToken, authHeaders, login } from './support/app';

const navLabels = (page: import('@playwright/test').Page) => page.locator('.nav-link .nav-text').allTextContents();

test.describe('role-based access', () => {
  test('ADMIN sees every section', async ({ page }) => {
    await login(page, E2E.admin);
    expect((await navLabels(page)).map((t) => t.trim()))
      .toEqual(['Dashboard', 'Users', 'Employees', 'Departments', 'Audit Logs', 'Profile', 'Settings']);
    for (const path of ['/users', '/audit-logs', '/settings']) {
      await page.goto(path);
      await expect(page).toHaveURL(new RegExp(path));
    }
    await page.goto('/settings');
    await expect(page.locator('.settings-org')).toBeVisible();
  });

  test('MANAGER can use management pages but not administration', async ({ page, request }) => {
    await login(page, E2E.manager);
    expect((await navLabels(page)).map((t) => t.trim())).toEqual(['Dashboard', 'Employees', 'Departments', 'Profile', 'Settings']);
    await expect(page.locator('.kpi-card').first()).toBeVisible();
    await page.goto('/employees');
    await expect(page.getByRole('button', { name: /Add employee/ })).toBeVisible();
    for (const path of ['/users', '/audit-logs']) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/forbidden/);
    }
    await page.goto('/settings');
    await expect(page.locator('.settings-org')).toHaveCount(0);

    // The API enforces the same rules, whatever the UI shows.
    const headers = authHeaders(await apiToken(request, E2E.manager));
    for (const path of ['/api/users', '/api/audit-logs', '/api/settings/organization', '/api/dashboard/users']) {
      expect((await request.get(path, { headers })).status(), path).toBe(403);
    }
  });

  test('USER gets a personal workspace and read-only records', async ({ page, request }) => {
    await login(page, E2E.user);
    expect((await navLabels(page)).map((t) => t.trim())).toEqual(['Home', 'Employees', 'Departments', 'Profile', 'Settings']);
    await expect(page.locator('.kpi-card')).toHaveCount(0);
    await page.goto('/employees');
    await expect(page.getByRole('button', { name: /Add employee/ })).toHaveCount(0);
    for (const path of ['/users', '/audit-logs']) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/forbidden/);
    }
    const headers = authHeaders(await apiToken(request, E2E.user));
    for (const path of ['/api/users', '/api/audit-logs', '/api/dashboard/summary', '/api/settings/organization']) {
      expect((await request.get(path, { headers })).status(), path).toBe(403);
    }
    expect((await request.post('/api/employees', { headers, data: {} })).status()).toBe(403);
    expect((await request.post('/api/departments', { headers, data: {} })).status()).toBe(403);
  });

  test('anonymous visitors are sent to sign in and the API answers 401', async ({ page, request }) => {
    await page.goto('/audit-logs');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Faudit-logs/);
    expect((await request.get('/api/employees')).status()).toBe(401);
  });
});
