import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiService } from '../http/api.service';
import { AuditLogEntry, PageResponse } from './api.models';

/** ADMIN-only, read-only audit trail (the backend enforces both). */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly api = inject(ApiService);

  list(params: HttpParams): Observable<PageResponse<AuditLogEntry>> {
    return this.api.get('audit-logs', { params });
  }

  get(id: number): Observable<AuditLogEntry> {
    return this.api.get(`audit-logs/${id}`);
  }
}
