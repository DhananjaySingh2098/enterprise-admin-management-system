import { InjectionToken } from '@angular/core';

/**
 * Base URL for all backend API calls.
 *
 * Defaults to the relative path `/api`, so the same build works behind the Angular dev proxy locally
 * and behind a reverse proxy/ingress in deployed environments — no hostnames are baked into the bundle.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => '/api',
});
