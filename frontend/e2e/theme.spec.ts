import { expect, test } from '@playwright/test';

import { E2E, apiToken, authHeaders, login } from './support/app';

const PRESETS = ['aurora', 'obsidian', 'pearl', 'midnight', 'emerald'] as const;
const MODES = ['system', 'light', 'dark'] as const;

const attrs = (page: import('@playwright/test').Page) => page.evaluate(() => ({
  theme: document.documentElement.getAttribute('data-theme'),
  preset: document.documentElement.getAttribute('data-preset'),
  density: document.documentElement.getAttribute('data-density'),
  background: getComputedStyle(document.body).backgroundColor,
}));

test.afterAll(async ({ request }) => {
  const headers = authHeaders(await apiToken(request, E2E.manager));
  await request.put('/api/preferences', { headers, data: { themeMode: 'SYSTEM', themePreset: 'AURORA', density: 'COMFORTABLE' } });
});

test('every preset, appearance and density applies, persists across reload and follows the account to a new browser', async ({ page, browser }) => {
  await login(page, E2E.manager, '/settings');
  const backgrounds = new Set<string>();

  for (const preset of PRESETS) {
    await page.locator(`input[name="settings-preset"][value="${preset}"]`).check({ force: true });
    for (const mode of MODES) {
      await page.locator(`input[name="settings-mode"][value="${mode}"]`).check({ force: true });
      await expect.poll(async () => (await attrs(page)).theme).toBe(mode === 'system' ? null : mode);
      const now = await attrs(page);
      expect(now.preset).toBe(preset);
      backgrounds.add(now.background);
    }
  }
  // Presets × light/dark really change the palette (not just an attribute).
  expect(backgrounds.size).toBeGreaterThanOrEqual(8);

  await page.locator('input[name="settings-preset"][value="emerald"]').check({ force: true });
  await page.locator('input[name="settings-mode"][value="dark"]').check({ force: true });
  await page.locator('input[name="settings-density"][value="compact"]').check({ force: true });
  await expect(page.locator('.sync-status')).toContainText('Saved to your account');
  await expect.poll(() => attrs(page)).toMatchObject({ theme: 'dark', preset: 'emerald', density: 'compact' });
  expect(await attrs(page)).toMatchObject({ theme: 'dark', preset: 'emerald', density: 'compact' });

  await page.reload();
  expect(await attrs(page)).toMatchObject({ theme: 'dark', preset: 'emerald', density: 'compact' });

  // A different browser (no local copy) receives the account's appearance after sign-in.
  const other = await browser.newContext();
  const second = await other.newPage();
  await login(second, E2E.manager);
  await expect.poll(() => attrs(second)).toMatchObject({ theme: 'dark', preset: 'emerald', density: 'compact' });
  // Compact density really tightens rows.
  await second.goto('/employees');
  await expect(second.locator('tbody tr').first()).toBeVisible();
  const compactRow = await second.locator('tbody tr').first().boundingBox();
  await second.locator('input[name="settings-density"]').count();
  await other.close();

  await page.goto('/settings');
  await page.locator('input[name="settings-density"][value="comfortable"]').check({ force: true });
  await page.goto('/employees');
  await expect(page.locator('tbody tr').first()).toBeVisible();
  const comfortableRow = await page.locator('tbody tr').first().boundingBox();
  expect(compactRow!.height).toBeLessThan(comfortableRow!.height);
});
