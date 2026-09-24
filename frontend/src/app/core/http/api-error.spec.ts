import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { parseApiError } from './api-error';

describe('parseApiError', () => {
  it('shows the server message and code for a rate-limited request', () => {
    const parsed = parseApiError(new HttpErrorResponse({ status: 429, headers: new HttpHeaders({ 'Retry-After': '30' }),
      error: { status: 429, code: 'RATE_LIMITED', message: 'Too many requests. Please wait a moment and try again.' } }));
    expect(parsed).toMatchObject({ status: 429, code: 'RATE_LIMITED', message: 'Too many requests. Please wait a moment and try again.' });
  });

  it('falls back to a friendly message when a proxy returns 429 without our body', () => {
    expect(parseApiError(new HttpErrorResponse({ status: 429, error: null })).message).toBe('Too many requests. Please wait a moment and try again.');
  });

  it('never surfaces server internals for 5xx', () => {
    expect(parseApiError(new HttpErrorResponse({ status: 500, error: { message: 'NullPointerException at com.x' } })).message)
      .toBe('Something went wrong. Please try again.');
  });

  it('explains unknown fields without trusting other content', () => {
    const parsed = parseApiError(new HttpErrorResponse({ status: 400,
      error: { code: 'UNKNOWN_FIELD', message: 'Unknown field roles', fieldErrors: [{ field: 'roles', message: 'is not accepted by this endpoint' }] } }));
    expect(parsed.code).toBe('UNKNOWN_FIELD');
    expect(parsed.fieldErrors[0].field).toBe('roles');
  });
});
