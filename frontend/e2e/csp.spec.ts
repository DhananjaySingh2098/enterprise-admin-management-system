import { expect, test } from '@playwright/test';

import { E2E, cspViolations, login, watchPage } from './support/app';
import { PAGES } from './support/fixtures';

test.describe('Content-Security-Policy', () => {
  test('the SPA is served with the strict policy and security headers', async ({ page }) => {
    const response = await page.goto('/login');
    const headers = response!.headers();
    const csp = headers['content-security-policy'];
    expect(csp).toContain("script-src 'self' 'sha256-");
    expect(csp).toContain("style-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).not.toContain('unsafe-inline');
    expect(csp).not.toContain('unsafe-eval');
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['referrer-policy']).toBe('no-referrer');
    expect(headers['permissions-policy']).toContain('camera=()');
  });

  test('every page works with zero CSP violations and no console errors', async ({ page }) => {
    const watch = await watchPage(page);
    await page.goto('/login');
    await expect(page.locator('#email')).toBeVisible();
    await login(page, E2E.admin);
    for (const path of PAGES.admin) {
      await page.goto(path);
      await expect(page.locator('.topbar')).toBeVisible();
      await page.waitForLoadState('networkidle');
    }
    // Interactive surfaces that render dynamically styled content.
    await page.goto('/');
    await page.locator('.bell-trigger').click();
    await page.locator('.theme-more').click();
    await page.goto('/employees');
    await page.getByRole('button', { name: /Add employee/ }).click();
    await expect(page.locator('dialog[open]')).toBeVisible();
    await page.keyboard.press('Escape');

    expect(await cspViolations(page)).toEqual([]);
    expect(watch.errors).toEqual([]);
  });

  test('the pre-paint theme script still runs under CSP (no flash)', async ({ page }) => {
    await page.goto('/login');
    await page.evaluate(() => {
      localStorage.setItem('enterprise-admin:theme', 'dark');
      localStorage.setItem('enterprise-admin:preset', 'midnight');
      localStorage.setItem('enterprise-admin:density', 'compact');
    });
    await page.reload({ waitUntil: 'commit' });
    // Read as soon as the document is parsed — before Angular boots.
    const early = await page.evaluate(() => new Promise<Record<string, string | null>>((resolve) => {
      const read = () => resolve({ theme: document.documentElement.getAttribute('data-theme'),
        preset: document.documentElement.getAttribute('data-preset'), density: document.documentElement.getAttribute('data-density') });
      document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', read) : read();
    }));
    expect(early).toEqual({ theme: 'dark', preset: 'midnight', density: 'compact' });
    expect(await cspViolations(page)).toEqual([]);
  });
});
