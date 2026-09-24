import { expect, test } from '@playwright/test';

import { E2E, login } from './support/app';
import { PAGES } from './support/fixtures';

const WIDTHS = [1440, 1280, 1024, 768, 390];

test('no horizontal page overflow on any page at any width', async ({ page }) => {
  await login(page, E2E.admin);
  const overflow: string[] = [];
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 900 });
    for (const path of PAGES.admin) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      if (wide > 1) {
        overflow.push(`${width}px ${path}: +${wide}px`);
      }
    }
  }
  expect(overflow).toEqual([]);
});

test('popovers and drawers stay on screen and usable on a phone', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  await login(page, E2E.admin);
  const inViewport = async (selector: string) => {
    // Measure after the enter animation (drawers slide in from the right).
    await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running' || a.effect?.getComputedTiming().iterations === Infinity));
    const box = await page.locator(selector).boundingBox();
    expect(box, selector).not.toBeNull();
    expect(box!.x, selector).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width, selector).toBeLessThanOrEqual(390 + 1);
  };
  await page.locator('.bell-trigger').click();
  await inViewport('#notification-panel');
  await page.keyboard.press('Escape');
  await page.locator('.theme-more').click();
  await inViewport('.theme-panel');
  await page.keyboard.press('Escape');
  await page.locator('.account-trigger').click();
  await inViewport('.account-panel');
  await page.keyboard.press('Escape');

  await page.goto('/audit-logs');
  await page.locator('button.audit-open').first().click();
  await inViewport('dialog[open] .dialog-panel');
  await page.keyboard.press('Escape');

  await page.goto('/employees');
  await page.getByRole('button', { name: /Add employee/ }).click();
  await inViewport('dialog[open] .dialog-panel');
  await expect(page.locator('#e-code')).toBeVisible();
  await context.close();
});
