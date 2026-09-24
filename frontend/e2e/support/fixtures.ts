/**
 * E2E fixtures. These credentials exist only in the disposable `enterprise_admin_e2e` schema, which is dropped and
 * recreated on every run (see start-backend.mjs). They are test fixtures, not secrets, and are never used elsewhere.
 */
export const E2E = {
  baseURL: process.env['E2E_BASE_URL'] ?? `http://localhost:${process.env['E2E_WEB_PORT'] ?? '4202'}`,
  // E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD point the suite at an existing stack's bootstrap administrator.
  admin: { email: process.env['E2E_ADMIN_EMAIL'] ?? 'e2e.admin@enterprise-admin.test',
    password: process.env['E2E_ADMIN_PASSWORD'] ?? 'E2e-Admin-Password-2026', name: 'E2E Administrator' },
  manager: { email: 'e2e.manager@enterprise-admin.test', password: 'E2e-Manager-Password-2026', firstName: 'Morgan', lastName: 'Manager' },
  user: { email: 'e2e.user@enterprise-admin.test', password: 'E2e-Regular-Password-2026', firstName: 'Uma', lastName: 'User' },
  otherUser: { email: 'e2e.other@enterprise-admin.test', password: 'E2e-Other-Password-2026', firstName: 'Otto', lastName: 'Other' },
} as const;

export type Account = { email: string; password: string };

/** Every page reachable in the app, per role. */
export const PAGES = {
  admin: ['/', '/users', '/employees', '/departments', '/audit-logs', '/notifications', '/settings', '/profile'],
  manager: ['/', '/employees', '/departments', '/notifications', '/settings', '/profile'],
  user: ['/', '/employees', '/departments', '/notifications', '/settings', '/profile'],
} as const;
