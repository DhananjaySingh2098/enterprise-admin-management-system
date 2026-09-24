import { APIRequestContext, Page, expect } from '@playwright/test';

import { Account, E2E } from './fixtures';

/** Signs in through the real login form and waits for the application shell. */
export async function login(page: Page, account: Account, path = '/'): Promise<void> {
  await page.goto('/login' + (path !== '/' ? `?returnUrl=${encodeURIComponent(path)}` : ''));
  await page.locator('#email').fill(account.email);
  await page.locator('#password').fill(account.password);
  await page.locator('form button[type="submit"]').click();
  await expect(page.locator('.topbar')).toBeVisible();
}

/** Bearer token for API-level setup and assertions. */
export async function apiToken(request: APIRequestContext, account: Account): Promise<string> {
  const response = await request.post('/api/auth/login', {
    headers: { 'X-Requested-With': 'XMLHttpRequest' },
    data: { email: account.email, password: account.password },
  });
  expect(response.status(), `login ${account.email}`).toBe(200);
  return (await response.json()).accessToken as string;
}

export function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'X-Requested-With': 'XMLHttpRequest' };
}

/** Records CSP violations and unexpected console errors for the whole page lifetime. */
export async function watchPage(page: Page): Promise<{ violations: string[]; errors: string[] }> {
  const state = { violations: [] as string[], errors: [] as string[] };
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      const w = window as unknown as { __cspViolations?: string[] };
      (w.__cspViolations ??= []).push(`${e.violatedDirective} ${e.blockedURI}`);
    });
  });
  page.on('console', (message) => {
    if (message.type() === 'error' && !/status of (401|403|404|409|429)/.test(message.text())) {
      state.errors.push(message.text());
    }
  });
  page.on('pageerror', (error) => state.errors.push(error.message));
  return state;
}

export async function cspViolations(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as unknown as { __cspViolations?: string[] }).__cspViolations ?? []);
}

export function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
}

export { E2E };
