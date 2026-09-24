import { Role } from '../auth/auth.models';

/** Standard server page (zero-based `page`). */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface UserSummary {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roles: Role[];
  enabled: boolean;
  createdAt: string;
}

export interface UserDetail extends UserSummary {
  updatedAt: string;
}

export interface CreateUserRequest {
  firstName: string;
  lastName: string;
  email: string;
  initialPassword: string;
  roles: Role[];
  enabled: boolean;
}

export interface UpdateUserRequest {
  firstName: string;
  lastName: string;
  email: string;
}

export interface Profile {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roles: Role[];
  createdAt: string;
}

export interface UpdateProfileRequest {
  firstName: string;
  lastName: string;
  email: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface Department {
  id: number;
  name: string;
  code: string;
  description: string | null;
  active: boolean;
  headcount: number;
  createdAt: string;
  updatedAt: string;
}

export interface DepartmentRef {
  id: number;
  name: string;
  code: string;
  active: boolean;
}

export interface DepartmentRequest {
  name: string;
  code: string;
  description: string | null;
}

export type EmployeeStatus = 'ACTIVE' | 'ON_LEAVE' | 'TERMINATED';

export const EMPLOYEE_STATUSES: readonly EmployeeStatus[] = ['ACTIVE', 'ON_LEAVE', 'TERMINATED'];

export interface EmployeeSummary {
  id: number;
  employeeCode: string;
  firstName: string;
  lastName: string;
  email: string;
  jobTitle: string;
  department: DepartmentRef;
  status: EmployeeStatus;
  hireDate: string;
}

export interface LinkedUserRef {
  id: number;
  email: string;
  fullName: string;
}

export interface EmployeeDetail extends EmployeeSummary {
  phone: string | null;
  linkedUser: LinkedUserRef | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface EmployeeRequest {
  employeeCode: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string | null;
  jobTitle: string;
  departmentId: number;
  hireDate: string;
  userId: number | null;
}

export interface CreateEmployeeRequest extends EmployeeRequest {
  status: EmployeeStatus;
}

export interface UpdateEmployeeRequest extends EmployeeRequest {
  version: number;
}

// ---------------------------------------------------------------- Phase 5: audit, notifications, settings

export type AuditAction =
  | 'LOGIN_SUCCESS'
  | 'LOGIN_FAILURE'
  | 'LOGOUT'
  | 'REFRESH_TOKEN_REVOKED'
  | 'USER_CREATED'
  | 'USER_UPDATED'
  | 'USER_ENABLED'
  | 'USER_DISABLED'
  | 'USER_ROLES_CHANGED'
  | 'PASSWORD_CHANGED'
  | 'EMPLOYEE_CREATED'
  | 'EMPLOYEE_UPDATED'
  | 'EMPLOYEE_STATUS_CHANGED'
  | 'DEPARTMENT_CREATED'
  | 'DEPARTMENT_UPDATED'
  | 'DEPARTMENT_DEACTIVATED'
  | 'DEPARTMENT_REACTIVATED'
  | 'ORGANIZATION_SETTINGS_UPDATED'
  | 'USER_PREFERENCES_UPDATED';

export type AuditEntityType = 'USER' | 'SESSION' | 'EMPLOYEE' | 'DEPARTMENT' | 'ORGANIZATION_SETTINGS' | 'USER_PREFERENCES';
export type AuditOutcome = 'SUCCESS' | 'FAILURE';

/** One audit event. `details` is already redacted by the server; values are plain JSON. */
export interface AuditLogEntry {
  id: number;
  createdAt: string;
  actor: { id?: number; email?: string };
  action: AuditAction;
  target?: { type: AuditEntityType; id?: string; label?: string };
  outcome: AuditOutcome;
  ipAddress?: string;
  requestId?: string;
  details: Record<string, unknown>;
}

export type NotificationType =
  | 'ROLE_CHANGED'
  | 'ACCOUNT_STATUS_CHANGED'
  | 'ACCOUNT_DETAILS_CHANGED'
  | 'PASSWORD_CHANGED'
  | 'SECURITY_ALERT'
  | 'EMPLOYEE_RECORD_UPDATED'
  | 'ADMIN_GRANTED';

export interface AppNotification {
  id: number;
  type: NotificationType;
  title: string;
  message: string;
  relatedEntityType?: AuditEntityType;
  relatedEntityId?: string;
  read: boolean;
  readAt?: string;
  createdAt: string;
}

export type ServerThemeMode = 'SYSTEM' | 'LIGHT' | 'DARK';
export type ServerThemePreset = 'AURORA' | 'OBSIDIAN' | 'PEARL' | 'MIDNIGHT' | 'EMERALD';
export type ServerDensity = 'COMFORTABLE' | 'COMPACT';

export interface Preferences {
  themeMode: ServerThemeMode;
  themePreset: ServerThemePreset;
  density: ServerDensity;
  /** False until the user has saved once; the values are then the defaults. */
  saved: boolean;
  updatedAt?: string;
}

export interface OrganizationSettings {
  organizationName: string;
  recentHireWindowDays: number;
  version: number;
  updatedAt: string;
}
