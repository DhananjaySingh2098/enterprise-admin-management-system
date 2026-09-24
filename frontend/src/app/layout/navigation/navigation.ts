import { Role } from '../../core/auth/auth.models';
import { IconName } from '../../shared/ui/icon/icon';

export interface NavItem {
  label: string;
  path: string;
  icon: IconName;
  exact?: boolean;
  /** Visible only to these roles (UX only — the API enforces access independently). */
  roles?: readonly Role[];
}

export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', path: '/', icon: 'home', exact: true },
  { label: 'Users', path: '/users', icon: 'users', roles: ['ADMIN'] },
  { label: 'Employees', path: '/employees', icon: 'briefcase' },
  { label: 'Departments', path: '/departments', icon: 'building' },
  { label: 'Audit Logs', path: '/audit-logs', icon: 'activity', roles: ['ADMIN'] },
  { label: 'Profile', path: '/profile', icon: 'user' },
  { label: 'Settings', path: '/settings', icon: 'settings' },
];

const MANAGEMENT: readonly Role[] = ['ADMIN', 'MANAGER'];

/** Items for the given roles. The landing item reads "Home" for USER, who sees a workspace rather than analytics. */
export function visibleNavItems(roles: readonly Role[]): NavItem[] {
  const management = roles.some((role) => MANAGEMENT.includes(role));
  return NAV_ITEMS.filter((item) => !item.roles || item.roles.some((role) => roles.includes(role))).map((item) =>
    item.path === '/' && !management ? { ...item, label: 'Home' } : item,
  );
}
