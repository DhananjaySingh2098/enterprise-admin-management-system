import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';

import { AuditAction, AuditEntityType, AuditLogEntry, AuditOutcome } from '../../core/api/api.models';
import { AuditApi } from '../../core/api/audit.api';
import { parseApiError } from '../../core/http/api-error';
import { ListQuery, pagedResource } from '../../shared/data/list-query';
import {
  AUDIT_ACTION_LIST,
  AUDIT_ACTIONS,
  AUDIT_CATEGORY_ICON,
  AUDIT_ENTITY_LABEL,
  AUDIT_OUTCOME,
  AuditCategory,
} from '../../shared/format/labels';
import { absoluteTime, relativeTime } from '../../shared/format/relative-time';
import { Avatar } from '../../shared/ui/avatar/avatar';
import { Badge } from '../../shared/ui/badge/badge';
import { Dialog } from '../../shared/ui/dialog/dialog';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { Icon } from '../../shared/ui/icon/icon';
import { PageHeader } from '../../shared/ui/page-header/page-header';
import { Pagination } from '../../shared/ui/pagination/pagination';
import { RevealDirective } from '../../shared/ui/reveal/reveal.directive';
import { SortHeader } from '../../shared/ui/sort-header/sort-header';
import { DetailRow, detailRows, detailsSummary } from './audit-format';

const CATEGORY_LABEL: Record<AuditCategory, string> = {
  auth: 'Authentication',
  users: 'Users & access',
  people: 'Employees & departments',
  settings: 'Settings',
};

/** ADMIN-only view of the append-only audit trail (read-only; the API enforces ADMIN). */
@Component({
  selector: 'app-audit-logs-page',
  imports: [ReactiveFormsModule, Avatar, Badge, Dialog, EmptyState, Icon, PageHeader, Pagination, RevealDirective, SortHeader],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './audit-logs-page.html',
})
export class AuditLogsPage {
  private readonly api = inject(AuditApi);

  protected readonly query = new ListQuery('createdAt', 'desc');
  protected readonly logs = pagedResource(this.query, (params) => this.api.list(params));
  protected readonly search = new FormControl('', { nonNullable: true });

  protected readonly actions = AUDIT_ACTIONS;
  protected readonly outcomes = AUDIT_OUTCOME;
  protected readonly entityLabel = AUDIT_ENTITY_LABEL;
  protected readonly categoryIcon = AUDIT_CATEGORY_ICON;
  protected readonly actionGroups = (Object.keys(CATEGORY_LABEL) as AuditCategory[]).map((category) => ({
    label: CATEGORY_LABEL[category],
    actions: AUDIT_ACTION_LIST.filter((a) => AUDIT_ACTIONS[a].category === category),
  }));
  protected readonly entityTypes = Object.keys(AUDIT_ENTITY_LABEL) as AuditEntityType[];
  protected readonly outcomeList = Object.keys(AUDIT_OUTCOME) as AuditOutcome[];

  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly hasFilters = computed(
    () => !!this.query.search().trim() || Object.values(this.query.filters()).some((v) => v !== null && v !== ''),
  );

  protected readonly selected = signal<AuditLogEntry | null>(null);
  protected readonly selectedRows = computed<DetailRow[]>(() => {
    const entry = this.selected();
    return entry ? detailRows(entry) : [];
  });
  protected readonly detailError = signal<string | null>(null);

  protected readonly summary = detailsSummary;
  protected readonly relative = relativeTime;
  protected readonly absolute = absoluteTime;

  constructor() {
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((term) => this.query.setSearch(term));
  }

  protected setFilter(key: 'action' | 'entityType' | 'outcome', value: string): void {
    this.query.setFilter(key, value || null);
  }

  protected setDate(key: 'from' | 'to', value: string): void {
    (key === 'from' ? this.from : this.to).set(value);
    this.query.setFilter(key, value || null);
  }

  protected clearFilters(): void {
    this.search.setValue('', { emitEvent: false });
    this.from.set('');
    this.to.set('');
    this.query.search.set('');
    this.query.filters.set({});
    this.query.setPage(0);
  }

  protected actorName(entry: AuditLogEntry): string {
    return entry.actor.email ?? (entry.action === 'REFRESH_TOKEN_REVOKED' ? 'System' : 'Anonymous');
  }

  /** Re-reads the event so the drawer always shows exactly what the server holds. */
  protected open(entry: AuditLogEntry): void {
    this.selected.set(entry);
    this.detailError.set(null);
    this.api.get(entry.id).subscribe({
      next: (fresh) => this.selected.set(fresh),
      error: (error: unknown) => this.detailError.set(parseApiError(error).message),
    });
  }

  protected copy(text: string | undefined): void {
    if (text) {
      void navigator.clipboard?.writeText(text).catch(() => undefined);
    }
  }
}
