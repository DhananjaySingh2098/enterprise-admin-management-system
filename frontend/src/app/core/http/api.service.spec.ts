import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { API_BASE_URL } from '../config/api.config';
import { ApiService } from './api.service';

describe('ApiService', () => {
  function setup(baseUrl?: string) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ...(baseUrl ? [{ provide: API_BASE_URL, useValue: baseUrl }] : []),
      ],
    });
    return { api: TestBed.inject(ApiService), http: TestBed.inject(HttpTestingController) };
  }

  it('uses the relative /api base URL by default', () => {
    const { api } = setup();
    expect(api.url('health')).toBe('/api/health');
    expect(api.url('/health/db')).toBe('/api/health/db');
  });

  it('respects an overridden base URL without duplicating slashes', () => {
    const { api } = setup('https://api.example.test/api/');
    expect(api.url('/users')).toBe('https://api.example.test/api/users');
  });

  it('issues GET requests with query params', () => {
    const { api, http } = setup();
    let body: unknown;
    api.get<{ ok: boolean }>('items', { params: { page: 2 } }).subscribe((value) => (body = value));

    const req = http.expectOne((r) => r.url === '/api/items');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ ok: true });
    expect(body).toEqual({ ok: true });
    http.verify();
  });
});
