import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';

import {
  DashboardApi,
  DashboardSummary,
  DepartmentHeadcount,
  HiringTrend,
  RecentEmployee,
  StatusCount,
  UserAnalytics,
} from '../../core/api/dashboard.api';
import { AuthService } from '../../core/auth/auth.service';
import { parseApiError } from '../../core/http/api-error';
import { BarChart, BarDatum } from '../../shared/charts/bar-chart';
import { formatPercent, monthLabel, monthLongLabel, plural } from '../../shared/charts/chart-utils';
import { DonutChart, DonutDatum } from '../../shared/charts/donut-chart';
import { LineChart, LineDatum } from '../../shared/charts/line-chart';
import { EMPLOYEE_STATUS_LABEL, EMPLOYEE_STATUS_TONE } from '../../shared/format/labels';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Badge } from '../../shared/ui/badge/badge';
import { Icon } from '../../shared/ui/icon/icon';
import { Home } from '../home/home';
import { KpiCard, KpiSegment } from './kpi-card';
import { HeroStage } from '../../shared/ui/hero-stage/hero-stage';
import { RevealDirective } from '../../shared/ui/reveal/reveal.directive';
import { SpotlightDirective } from '../../shared/ui/spotlight/spotlight.directive';

interface DashboardData {
  summary: DashboardSummary;
  headcount: DepartmentHeadcount[];
  statuses: StatusCount[];
  trend: HiringTrend;
  recent: RecentEmployee[];
  users: UserAnalytics | null;
}

const MAX_BARS = 8;
const STATUS_COLOR = { ACTIVE: 'var(--app-success)', ON_LEAVE: 'var(--app-warning)', TERMINATED: 'var(--app-text-muted)' } as const;

/**
 * Management dashboard (ADMIN, MANAGER). Every figure is a live aggregate from /api/dashboard; derived values
 * (shares, busiest month) are computed from those numbers only. USER sees the personal workspace instead, and
 * never calls the analytics endpoints (the API would return 403).
 */
@Component({
  selector: 'app-dashboard-page',
  imports: [DatePipe, RouterLink, Avatar, Badge, BarChart, DonutChart, HeroStage, Icon, KpiCard, LineChart, Home, RevealDirective, SpotlightDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.html',
})
export class DashboardPage {
  private readonly api = inject(DashboardApi);
  protected readonly auth = inject(AuthService);

  protected readonly isManagement = computed(() => this.auth.hasAnyRole(['ADMIN', 'MANAGER']));
  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(['ADMIN']));
  protected readonly statusLabel = EMPLOYEE_STATUS_LABEL;
  protected readonly statusTone = EMPLOYEE_STATUS_TONE;

  protected readonly data = signal<DashboardData | null>(null);
  protected readonly trendMonths = signal(12);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const part = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
    return `${part}, ${this.auth.user()?.firstName ?? 'there'}.`;
  });
  protected readonly today = new Date();

  protected readonly summary = computed(() => this.data()?.summary ?? null);
  protected readonly hasEmployees = computed(() => (this.summary()?.employees.total ?? 0) > 0);

  protected readonly context = computed(() => {
    const s = this.summary();
    if (!s) {
      return '';
    }
    if (s.employees.total === 0) {
      return s.departments.total === 0 ? 'No departments or employees recorded yet.' : 'No employees recorded yet.';
    }
    return `${plural(s.employees.total, 'employee')} across ${plural(s.departments.active, 'active department')} · ` +
      `${plural(s.recentHires.count, 'hire')} in the last ${s.recentHires.windowDays} days`;
  });

  protected readonly kpis = computed(() => {
    const s = this.summary();
    if (!s) {
      return [];
    }
    const e = s.employees;
    const current = e.active + e.onLeave;
    const share = (value: number, rest: number): KpiSegment[] => [
      { value, tone: 'primary' },
      { value: rest, tone: 'track' },
    ];
    return [
      { label: 'Total employees', value: e.total, icon: 'users' as const, tone: 'primary' as const,
        detail: e.terminated > 0 ? `${current} current · ${e.terminated} terminated` : `${plural(current, 'current employee')}`,
        segments: [{ value: e.active, tone: 'success' }, { value: e.onLeave, tone: 'warning' }, { value: e.terminated, tone: 'neutral' }] as KpiSegment[] },
      { label: 'Active', value: e.active, icon: 'check' as const, tone: 'success' as const,
        detail: `${formatPercent(e.active, e.total)} of all employees`, segments: share(e.active, e.total - e.active) },
      { label: 'On leave', value: e.onLeave, icon: 'calendar' as const, tone: 'warning' as const,
        detail: e.onLeave === 0 ? 'Nobody is on leave' : `${formatPercent(e.onLeave, e.total)} of all employees`,
        segments: share(e.onLeave, e.total - e.onLeave) },
      { label: 'Departments', value: s.departments.total, icon: 'building' as const, tone: 'info' as const,
        detail: `${s.departments.active} active · ${s.departments.inactive} inactive`,
        segments: [{ value: s.departments.active, tone: 'info' }, { value: s.departments.inactive, tone: 'track' }] as KpiSegment[] },
    ];
  });

  /** Real summary chips for the hero (no invented figures). */
  protected readonly heroChips = computed(() => {
    const s = this.summary();
    if (!s) {
      return [];
    }
    return [
      { icon: 'users' as const, text: plural(s.employees.total, 'employee') },
      { icon: 'building' as const, text: plural(s.departments.active, 'active department') },
      { icon: 'user' as const, text: `${plural(s.recentHires.count, 'hire')} in the last ${s.recentHires.windowDays} days` },
    ];
  });

  protected readonly bars = computed<BarDatum[]>(() =>
    (this.data()?.headcount ?? []).slice(0, MAX_BARS).map((d) => ({
      label: d.name,
      sublabel: d.code,
      value: d.headcount,
      muted: !d.active,
      tag: d.active ? undefined : 'Inactive',
    })),
  );
  protected readonly hiddenDepartments = computed(() => Math.max(0, (this.data()?.headcount.length ?? 0) - MAX_BARS));
  protected readonly currentHeadcount = computed(() => (this.data()?.headcount ?? []).reduce((sum, d) => sum + d.headcount, 0));

  protected readonly donut = computed<DonutDatum[]>(() =>
    (this.data()?.statuses ?? []).map((s) => ({ key: s.status, label: EMPLOYEE_STATUS_LABEL[s.status], value: s.count, color: STATUS_COLOR[s.status] })),
  );

  protected readonly trendPoints = computed<LineDatum[]>(() =>
    (this.data()?.trend.months ?? []).map((m, i, all) => ({
      label: monthLabel(m.month, i === 0 || m.month.endsWith('-01') || i === all.length - 1),
      fullLabel: monthLongLabel(m.month),
      value: m.hires,
    })),
  );
  protected readonly busiestMonth = computed(() => {
    const months = this.data()?.trend.months ?? [];
    const top = months.reduce<{ month: string; hires: number } | null>((best, m) => (!best || m.hires > best.hires ? m : best), null);
    return top && top.hires > 0 ? `${monthLongLabel(top.month)} (${plural(top.hires, 'hire')})` : null;
  });

  protected readonly roleRows = computed(() => {
    const users = this.data()?.users;
    if (!users) {
      return [];
    }
    const labels = { ADMIN: 'Admins', MANAGER: 'Managers', USER: 'Users' } as const;
    const max = Math.max(1, ...users.roles.map((r) => r.users));
    return users.roles.map((r) => ({ ...r, label: labels[r.role], width: (r.users / max) * 100 }));
  });

  constructor() {
    if (this.isManagement()) {
      this.load();
    }
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      summary: this.api.summary(),
      headcount: this.api.headcountByDepartment(),
      statuses: this.api.statusBreakdown(),
      trend: this.api.hiringTrend(this.trendMonths()),
      recent: this.api.recentEmployees(5),
      users: this.isAdmin() ? this.api.users() : of(null),
    }).subscribe({
      next: (data) => {
        this.data.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(parseApiError(err).message);
        this.loading.set(false);
      },
    });
  }

  protected readonly plural = plural;

  /** Re-queries only the trend for the chosen range (12 or 24 real months). */
  protected setTrendRange(months: number): void {
    if (months === this.trendMonths()) {
      return;
    }
    this.trendMonths.set(months);
    this.api.hiringTrend(months).subscribe({
      next: (trend) => this.data.update((d) => (d ? { ...d, trend } : d)),
      error: (err: unknown) => this.error.set(parseApiError(err).message),
    });
  }

  protected fullName(e: RecentEmployee): string {
    return `${e.firstName} ${e.lastName}`;
  }
}
