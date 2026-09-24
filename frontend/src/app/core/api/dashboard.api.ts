import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Role } from '../auth/auth.models';
import { ApiService } from '../http/api.service';
import { EmployeeStatus } from './api.models';

export interface DashboardSummary {
  employees: { total: number; active: number; onLeave: number; terminated: number };
  departments: { total: number; active: number; inactive: number };
  recentHires: { count: number; windowDays: number; from: string; to: string };
  generatedAt: string;
}

export interface DepartmentHeadcount {
  departmentId: number;
  name: string;
  code: string;
  active: boolean;
  headcount: number;
}

export interface StatusCount {
  status: EmployeeStatus;
  count: number;
}

export interface HiringTrend {
  from: string;
  to: string;
  total: number;
  months: { month: string; hires: number }[];
}

export interface RecentEmployee {
  id: number;
  employeeCode: string;
  firstName: string;
  lastName: string;
  departmentName: string;
  jobTitle: string;
  status: EmployeeStatus;
  hireDate: string;
}

export interface UserAnalytics {
  total: number;
  enabled: number;
  disabled: number;
  multiRoleUsers: number;
  roles: { role: Role; users: number; enabledUsers: number }[];
}

/** Read-only analytics. Workforce endpoints: ADMIN/MANAGER. `users()`: ADMIN only (server-enforced). */
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly api = inject(ApiService);

  summary(): Observable<DashboardSummary> {
    return this.api.get('dashboard/summary');
  }

  headcountByDepartment(): Observable<DepartmentHeadcount[]> {
    return this.api.get('dashboard/headcount-by-department');
  }

  statusBreakdown(): Observable<StatusCount[]> {
    return this.api.get('dashboard/status-breakdown');
  }

  hiringTrend(months = 12): Observable<HiringTrend> {
    return this.api.get('dashboard/hiring-trend', { params: new HttpParams().set('months', months) });
  }

  recentEmployees(limit = 5): Observable<RecentEmployee[]> {
    return this.api.get('dashboard/recent-employees', { params: new HttpParams().set('limit', limit) });
  }

  users(): Observable<UserAnalytics> {
    return this.api.get('dashboard/users');
  }
}
