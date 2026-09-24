import { AuditAction, AuditEntityType, AuditOutcome, EmployeeStatus, NotificationType } from '../../core/api/api.models';
import { IconName } from '../ui/icon/icon';
import { BadgeTone } from '../ui/badge/badge';

export const EMPLOYEE_STATUS_LABEL: Record<EmployeeStatus, string> = {
  ACTIVE: 'Active',
  ON_LEAVE: 'On leave',
  TERMINATED: 'Terminated',
};

export const EMPLOYEE_STATUS_TONE: Record<EmployeeStatus, BadgeTone> = {
  ACTIVE: 'success',
  ON_LEAVE: 'warning',
  TERMINATED: 'neutral',
};

export type AuditCategory = 'auth' | 'users' | 'people' | 'settings';

/** Human label, badge tone and category for each audited action. */
export const AUDIT_ACTIONS: Record<AuditAction, { label: string; tone: BadgeTone; category: AuditCategory }> = {
  LOGIN_SUCCESS: { label: 'Signed in', tone: 'success', category: 'auth' },
  LOGIN_FAILURE: { label: 'Sign-in failed', tone: 'danger', category: 'auth' },
  LOGOUT: { label: 'Signed out', tone: 'neutral', category: 'auth' },
  REFRESH_TOKEN_REVOKED: { label: 'Session revoked', tone: 'danger', category: 'auth' },
  USER_CREATED: { label: 'User created', tone: 'primary', category: 'users' },
  USER_UPDATED: { label: 'User updated', tone: 'info', category: 'users' },
  USER_ENABLED: { label: 'User enabled', tone: 'success', category: 'users' },
  USER_DISABLED: { label: 'User disabled', tone: 'warning', category: 'users' },
  USER_ROLES_CHANGED: { label: 'Roles changed', tone: 'primary', category: 'users' },
  PASSWORD_CHANGED: { label: 'Password changed', tone: 'warning', category: 'users' },
  EMPLOYEE_CREATED: { label: 'Employee added', tone: 'primary', category: 'people' },
  EMPLOYEE_UPDATED: { label: 'Employee updated', tone: 'info', category: 'people' },
  EMPLOYEE_STATUS_CHANGED: { label: 'Employee status', tone: 'warning', category: 'people' },
  DEPARTMENT_CREATED: { label: 'Department created', tone: 'primary', category: 'people' },
  DEPARTMENT_UPDATED: { label: 'Department updated', tone: 'info', category: 'people' },
  DEPARTMENT_DEACTIVATED: { label: 'Department deactivated', tone: 'warning', category: 'people' },
  DEPARTMENT_REACTIVATED: { label: 'Department reactivated', tone: 'success', category: 'people' },
  ORGANIZATION_SETTINGS_UPDATED: { label: 'Organization settings', tone: 'primary', category: 'settings' },
  USER_PREFERENCES_UPDATED: { label: 'Preferences saved', tone: 'neutral', category: 'settings' },
};

export const AUDIT_ACTION_LIST = Object.keys(AUDIT_ACTIONS) as AuditAction[];

export const AUDIT_ENTITY_LABEL: Record<AuditEntityType, string> = {
  USER: 'User',
  SESSION: 'Session',
  EMPLOYEE: 'Employee',
  DEPARTMENT: 'Department',
  ORGANIZATION_SETTINGS: 'Organization',
  USER_PREFERENCES: 'Preferences',
};

export const AUDIT_OUTCOME: Record<AuditOutcome, { label: string; tone: BadgeTone }> = {
  SUCCESS: { label: 'Success', tone: 'success' },
  FAILURE: { label: 'Failure', tone: 'danger' },
};

export const AUDIT_CATEGORY_ICON: Record<AuditCategory, IconName> = {
  auth: 'lock',
  users: 'users',
  people: 'briefcase',
  settings: 'settings',
};

export const NOTIFICATION_STYLE: Record<NotificationType, { icon: IconName; tone: BadgeTone }> = {
  ROLE_CHANGED: { icon: 'shield', tone: 'primary' },
  ACCOUNT_STATUS_CHANGED: { icon: 'power', tone: 'warning' },
  ACCOUNT_DETAILS_CHANGED: { icon: 'user', tone: 'info' },
  PASSWORD_CHANGED: { icon: 'lock', tone: 'warning' },
  SECURITY_ALERT: { icon: 'alert-circle', tone: 'danger' },
  EMPLOYEE_RECORD_UPDATED: { icon: 'briefcase', tone: 'info' },
  ADMIN_GRANTED: { icon: 'shield', tone: 'primary' },
};

/** "Changed fields" and other detail keys, in words. */
export const AUDIT_FIELD_LABEL: Record<string, string> = {
  firstName: 'First name',
  lastName: 'Last name',
  email: 'Email',
  phone: 'Phone',
  jobTitle: 'Job title',
  department: 'Department',
  hireDate: 'Hire date',
  employeeCode: 'Employee code',
  userId: 'Linked account',
  name: 'Name',
  code: 'Code',
  description: 'Description',
  themeMode: 'Appearance',
  themePreset: 'Visual style',
  density: 'Density',
  organizationName: 'Organization name',
  recentHireWindowDays: 'Recent-hire window',
  linkedAccount: 'Linked account',
  roles: 'Roles',
  enabled: 'Enabled',
  reason: 'Reason',
  revokedCount: 'Sign-in tokens revoked',
  source: 'Source',
  status: 'Status',
};
