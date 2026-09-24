import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { E2E, login } from './support/app';
import { PAGES } from './support/fixtures';

async function audit(page: import('@playwright/test').Page, label: string) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(700); // entrance transitions finish before contrast is measured
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const blocking = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  return { label, blocking: blocking.map((v) => `${v.id} (${v.impact}): ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`),
    other: result.violations.filter((v) => !blocking.includes(v)).map((v) => `${v.id} (${v.impact})`) };
}

test.describe('accessibility', () => {
  test('login and every admin page have no serious or critical axe violations (light and dark)', async ({ page }) => {
    const findings = [];
    await page.goto('/login');
    findings.push(await audit(page, 'login'));
    await login(page, E2E.admin);
    for (const theme of ['light', 'dark'] as const) {
      await page.evaluate((t) => localStorage.setItem('enterprise-admin:theme', t), theme);
      for (const path of PAGES.admin) {
        await page.goto(path);
        findings.push(await audit(page, `${theme} ${path}`));
      }
    }
    await page.evaluate(() => localStorage.setItem('enterprise-admin:theme', 'system'));
    console.log(JSON.stringify(findings.filter((f) => f.other.length).map((f) => `${f.label}: ${f.other.join(', ')}`), null, 1));
    expect(findings.flatMap((f) => f.blocking.map((b) => `${f.label} → ${b}`))).toEqual([]);
  });

  test('keyboard: skip link, visible focus, dialog focus trap, Escape and focus return', async ({ page }) => {
    await login(page, E2E.admin, '/employees');
    await page.keyboard.press('Tab');
    const first = await page.evaluate(() => ({ text: document.activeElement?.textContent?.trim(), outline: getComputedStyle(document.activeElement!).outlineStyle }));
    expect(first.text).toMatch(/skip/i);
    expect(first.outline).not.toBe('none');

    const trigger = page.getByRole('button', { name: /Add employee/ });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const dialog = page.locator('dialog[open]');
    await expect(dialog).toBeVisible();
    // Focus never reaches the page behind the modal (past the last control it may move to the browser's own UI,
    // which the document reports as <body>).
    for (let i = 0; i < 25; i++) {
      await page.keyboard.press('Tab');
      const where = await page.evaluate(() => document.activeElement?.closest('dialog[open]') ? 'dialog'
        : document.activeElement === document.body || !document.activeElement ? 'browser' : 'page');
      expect(where).not.toBe('page');
    }
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();

    // Popovers close on Escape and return focus.
    await page.locator('.bell-trigger').focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#notification-panel')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('#notification-panel')).toHaveCount(0);
    await expect(page.locator('.bell-trigger')).toBeFocused();
  });

  test('status is never conveyed by colour alone and tables keep their semantics', async ({ page }) => {
    await login(page, E2E.admin, '/employees');
    const badge = page.locator('tbody [data-label="Status"] .badge').first();
    await expect(badge).toHaveText(/Active|On leave|Terminated/);
    await expect(page.locator('table caption')).toHaveCount(1);
    expect(await page.locator('thead th').count()).toBeGreaterThan(4);
    await page.goto('/audit-logs');
    await expect(page.locator('tbody [data-label="Outcome"] .badge').first()).toHaveText(/Success|Failure/);
  });

  test('reduced motion: content is shown immediately and nothing loops', async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: 'reduce' });
    const page = await context.newPage();
    await login(page, E2E.admin);
    await expect(page.locator('.kpi-card').first()).toBeVisible();
    const state = await page.evaluate(() => ({
      hidden: [...document.querySelectorAll('.reveal')].filter((e) => getComputedStyle(e).opacity !== '1').length,
      infinite: document.getAnimations().filter((a) => {
        const timing = a.effect?.getComputedTiming();
        return a.playState === 'running' && timing?.iterations === Infinity;
      }).length,
    }));
    expect(state).toEqual({ hidden: 0, infinite: 0 });
    await context.close();
  });
});
