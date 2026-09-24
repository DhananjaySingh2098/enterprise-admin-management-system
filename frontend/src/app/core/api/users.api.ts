import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Role } from '../auth/auth.models';
import { ApiService } from '../http/api.service';
import { CreateUserRequest, PageResponse, UpdateUserRequest, UserDetail, UserSummary } from './api.models';

/** ADMIN-only endpoints (the backend enforces this). */
@Injectable({ providedIn: 'root' })
export class UsersApi {
  private readonly api = inject(ApiService);

  list(params: HttpParams): Observable<PageResponse<UserSummary>> {
    return this.api.get('users', { params });
  }

  get(id: number): Observable<UserDetail> {
    return this.api.get(`users/${id}`);
  }

  create(body: CreateUserRequest): Observable<UserDetail> {
    return this.api.post('users', body);
  }

  update(id: number, body: UpdateUserRequest): Observable<UserDetail> {
    return this.api.put(`users/${id}`, body);
  }

  updateRoles(id: number, roles: Role[]): Observable<UserDetail> {
    return this.api.put(`users/${id}/roles`, { roles });
  }

  updateStatus(id: number, enabled: boolean): Observable<UserDetail> {
    return this.api.put(`users/${id}/status`, { enabled });
  }
}
