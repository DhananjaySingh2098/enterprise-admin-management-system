import { Routes } from '@angular/router';

import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · Enterprise Admin',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    // Everything inside the application shell requires a signed-in user. Guards are UX only:
    // the API authorizes every request independently.
    path: '',
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    loadComponent: () => import('./layout/app-shell/app-shell').then((m) => m.AppShell),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Dashboard · Enterprise Admin',
        data: { heading: 'Dashboard', section: 'Overview' },
        loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'users',
        title: 'Users · Enterprise Admin',
        data: { heading: 'Users', section: 'Administration' },
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/users/users-page').then((m) => m.UsersPage),
      },
      {
        path: 'employees',
        title: 'Employees · Enterprise Admin',
        data: { heading: 'Employees', section: 'People' },
        loadComponent: () => import('./features/employees/employees-page').then((m) => m.EmployeesPage),
      },
      {
        path: 'departments',
        title: 'Departments · Enterprise Admin',
        data: { heading: 'Departments', section: 'Organization' },
        loadComponent: () => import('./features/departments/departments-page').then((m) => m.DepartmentsPage),
      },
      {
        path: 'audit-logs',
        title: 'Audit Logs · Enterprise Admin',
        data: { heading: 'Audit Logs', section: 'Security' },
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/audit-logs/audit-logs-page').then((m) => m.AuditLogsPage),
      },
      {
        path: 'notifications',
        title: 'Notifications · Enterprise Admin',
        data: { heading: 'Notifications', section: 'Account' },
        loadComponent: () => import('./features/notifications/notifications-page').then((m) => m.NotificationsPage),
      },
      {
        path: 'settings',
        title: 'Settings · Enterprise Admin',
        data: { heading: 'Settings', section: 'Account' },
        loadComponent: () => import('./features/settings/settings-page').then((m) => m.SettingsPage),
      },
      {
        path: 'profile',
        title: 'Profile · Enterprise Admin',
        data: { heading: 'Profile', section: 'Account' },
        loadComponent: () => import('./features/profile/profile-page').then((m) => m.ProfilePage),
      },
      {
        path: 'forbidden',
        title: 'Access denied · Enterprise Admin',
        data: { heading: 'Access denied', section: 'Security' },
        loadComponent: () => import('./features/forbidden/forbidden').then((m) => m.Forbidden),
      },
    ],
  },
  {
    path: '**',
    title: 'Page not found · Enterprise Admin',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];
