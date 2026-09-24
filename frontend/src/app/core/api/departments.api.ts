import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiService } from '../http/api.service';
import { Department, DepartmentRequest, PageResponse } from './api.models';

@Injectable({ providedIn: 'root' })
export class DepartmentsApi {
  private readonly api = inject(ApiService);

  list(params: HttpParams): Observable<PageResponse<Department>> {
    return this.api.get('departments', { params });
  }

  /** Options for pickers/filters: up to the server maximum page size, sorted by name. */
  options(activeOnly = false): Observable<Department[]> {
    let params = new HttpParams().set('size', 100).set('sort', 'name');
    if (activeOnly) {
      params = params.set('active', true);
    }
    return this.list(params).pipe(map((page) => page.content));
  }

  get(id: number): Observable<Department> {
    return this.api.get(`departments/${id}`);
  }

  create(body: DepartmentRequest): Observable<Department> {
    return this.api.post('departments', body);
  }

  update(id: number, body: DepartmentRequest): Observable<Department> {
    return this.api.put(`departments/${id}`, body);
  }

  setActive(id: number, active: boolean): Observable<Department> {
    return this.api.put(`departments/${id}/status`, { active });
  }
}
