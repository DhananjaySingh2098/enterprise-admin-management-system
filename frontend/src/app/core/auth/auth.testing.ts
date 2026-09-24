import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Routes, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { authInterceptor } from './auth.interceptor';
import { AuthResponse, CurrentUser, Role } from './auth.models';
import { AuthService } from './auth.service';

export const TEST_USER: CurrentUser = {
  id: 7,
  email: 'ada@example.com',
  firstName: 'Ada',
  lastName: 'Lovelace',
  roles: ['MANAGER'],
};

export function authResponse(accessToken = 'access-1', user: CurrentUser = TEST_USER): AuthResponse {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, user };
}

/** Router + HttpClient (with the real auth interceptor) + testing backend. */
export function testProviders(routes: Routes = []): (Provider | EnvironmentProviders)[] {
  return [provideRouter(routes), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()];
}

/** Signs in through the real AuthService so the interceptor attaches a token, as in the running app. */
export async function signInAs(roles: Role[], overrides: Partial<CurrentUser> = {}): Promise<CurrentUser> {
  const user = { ...TEST_USER, ...overrides, roles };
  const http = TestBed.inject(HttpTestingController);
  const login = firstValueFrom(TestBed.inject(AuthService).login(user.email, 'correct horse battery'));
  http.expectOne('/api/auth/login').flush(authResponse('token-' + roles.join('-'), user));
  await login;
  return user;
}

/** Waits for debounced inputs / promise-based work (macrotask). */
export const settle = (ms = 0) => new Promise((resolve) => setTimeout(resolve, ms));

export function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number; totalPages: number }> = {}) {
  const size = overrides.size ?? 20;
  const totalElements = overrides.totalElements ?? content.length;
  const totalPages = overrides.totalPages ?? Math.ceil(totalElements / size);
  const current = overrides.page ?? 0;
  return { content, page: current, size, totalElements, totalPages, first: current === 0, last: current + 1 >= totalPages };
}
