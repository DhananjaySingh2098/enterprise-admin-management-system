import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiService } from '../http/api.service';
import {
  CreateEmployeeRequest,
  EmployeeDetail,
  EmployeeStatus,
  EmployeeSummary,
  PageResponse,
  UpdateEmployeeRequest,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class EmployeesApi {
  private readonly api = inject(ApiService);

  list(params: HttpParams): Observable<PageResponse<EmployeeSummary>> {
    return this.api.get('employees', { params });
  }

  get(id: number): Observable<EmployeeDetail> {
    return this.api.get(`employees/${id}`);
  }

  create(body: CreateEmployeeRequest): Observable<EmployeeDetail> {
    return this.api.post('employees', body);
  }

  update(id: number, body: UpdateEmployeeRequest): Observable<EmployeeDetail> {
    return this.api.put(`employees/${id}`, body);
  }

  updateStatus(id: number, status: EmployeeStatus, version: number): Observable<EmployeeDetail> {
    return this.api.put(`employees/${id}/status`, { status, version });
  }
}
