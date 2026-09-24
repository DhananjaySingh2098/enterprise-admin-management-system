import { expect, test } from '@playwright/test';

import { E2E, login } from './support/app';

test.describe('authentication & session', () => {
  test('signs in with valid credentials and lands on the dashboard', async ({ page }) => {
    await login(page, E2E.admin);
    await expect(page.locator('#dash-greeting')).toContainText('E2E');
    await expect(page).toHaveURL(/\/$/);
  });

  test('rejects invalid credentials with a generic message and no session', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#email').fill(E2E.admin.email);
    await page.locator('#password').fill('Definitely-Not-The-Password-1');
    await page.locator('form button[type="submit"]').click();
    await expect(page.getByRole('alert')).toContainText(/invalid|incorrect/i);
    await expect(page).toHaveURL(/\/login/);
    // Nothing sensitive is kept in browser storage.
    const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(storage).not.toContain('Definitely-Not-The-Password-1');
    expect(storage).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
  });

  test('a reload restores the session from the HttpOnly refresh cookie', async ({ page, context }) => {
    await login(page, E2E.admin, '/employees');
    await expect(page).toHaveURL(/\/employees/);
    const cookies = await context.cookies();
    const refresh = cookies.find((c) => c.name === 'ea_refresh_token');
    expect(refresh?.httpOnly).toBe(true);
    expect(refresh?.sameSite).toBe('Strict');
    expect(refresh?.path).toBe('/api/auth');
    // The access token lives only in memory; nothing token-like is in storage.
    const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(storage).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);

    await page.reload();
    await expect(page.locator('.topbar')).toBeVisible();
    await expect(page).toHaveURL(/\/employees/);
  });

  test('an aborted refresh (response lost during a reload) does not sign the user out', async ({ page }) => {
    await login(page, E2E.admin, '/departments');
    // The server rotates the token, but the browser never receives the response: exactly what happens when the
    // page is reloaded or navigated away while the refresh request is in flight.
    let aborted = false;
    await page.route('**/api/auth/refresh', async (route) => {
      if (!aborted) {
        aborted = true;
        await route.fetch(); // reaches the server (rotation happens)
        await route.abort('aborted'); // …but the Set-Cookie never reaches the browser
      } else {
        await route.continue();
      }
    });
    await page.reload();
    await page.waitForTimeout(500);
    await page.unroute('**/api/auth/refresh');
    // Immediate navigation / second reload presents the old cookie: the server recovers the lost rotation.
    await page.reload();
    await expect(page.locator('.topbar')).toBeVisible();
    await expect(page).toHaveURL(/\/departments/);
    await page.goto('/employees');
    await expect(page.locator('tbody tr').first()).toBeVisible();
  });

  test('two rapid reloads keep a single consistent session', async ({ page }) => {
    await login(page, E2E.admin, '/profile');
    await page.reload({ waitUntil: 'commit' });
    await page.reload();
    await expect(page.locator('.topbar')).toBeVisible();
    await expect(page).toHaveURL(/\/profile/);
  });

  test('logout ends the session everywhere in this browser', async ({ page, context }) => {
    await login(page, E2E.admin);
    const second = await context.newPage();
    await second.goto('/departments');
    await expect(second.locator('.topbar')).toBeVisible();

    await page.locator('.account-trigger').click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto('/users');
    await expect(page).toHaveURL(/\/login/);
    // The other tab's session was revoked on the server: a reload cannot restore it.
    await second.reload();
    await expect(second).toHaveURL(/\/login/);
  });
});
