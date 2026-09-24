import { expect, test } from '@playwright/test';

import { E2E, apiToken, authHeaders, login } from './support/app';

test('a role change notifies the user: badge, popover, mark read — and nobody else can touch it', async ({ page, request }) => {
  const admin = authHeaders(await apiToken(request, E2E.admin));
  const user = (await (await request.get(`/api/users?search=${encodeURIComponent(E2E.otherUser.email)}`, { headers: admin })).json()).content[0];

  await login(page, E2E.otherUser);
  await page.waitForLoadState('networkidle');
  const before = (await page.locator('.bell-badge').count()) ? Number(await page.locator('.bell-badge').textContent()) : 0;

  // A real notification-producing event: an administrator changes this user's roles (and back).
  expect((await request.put(`/api/users/${user.id}/roles`, { headers: admin, data: { roles: ['USER', 'MANAGER'] } })).status()).toBe(200);
  expect((await request.put(`/api/users/${user.id}/roles`, { headers: admin, data: { roles: ['USER'] } })).status()).toBe(200);

  await page.reload();
  await expect(page.locator('.bell-badge')).toHaveText(String(before + 2));
  await expect(page.locator('.bell-trigger')).toHaveAttribute('aria-label', `Notifications, ${before + 2} unread`);

  await page.locator('.bell-trigger').click();
  const first = page.locator('.notification-item.is-unread').first();
  await expect(first).toContainText('Your access was updated');
  await first.locator('.notification-read').click();
  await expect(page.locator('.bell-badge')).toHaveText(String(before + 1));
  await page.getByRole('button', { name: 'Mark all as read' }).click();
  await expect(page.locator('.bell-badge')).toHaveCount(0);
  await page.keyboard.press('Escape');

  // Another user cannot read or mark it: same 404 as a non-existent id.
  const own = authHeaders(await apiToken(request, E2E.otherUser));
  const id = (await (await request.get('/api/notifications', { headers: own })).json()).content[0].id;
  const intruder = authHeaders(await apiToken(request, E2E.user));
  expect((await request.put(`/api/notifications/${id}/read`, { headers: intruder })).status()).toBe(404);
  const intruderList = await (await request.get('/api/notifications?size=50', { headers: intruder })).json();
  expect(intruderList.content.map((n: { id: number }) => n.id)).not.toContain(id);
  expect((await request.put(`/api/notifications/${id}/read`, { headers: admin })).status()).toBe(404);
});
