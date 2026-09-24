import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormGroup } from '@angular/forms';

import { ApiError } from './api-error.model';

export interface ParsedApiError {
  status: number;
  code: string | null;
  message: string;
  fieldErrors: { field: string; message: string }[];
}

const FALLBACK = 'Something went wrong. Please try again.';
const RATE_LIMITED = 'Too many requests. Please wait a moment and try again.';

/** Normalises any HTTP failure into a safe, displayable shape. */
export function parseApiError(error: unknown): ParsedApiError {
  if (!(error instanceof HttpErrorResponse)) {
    return { status: 0, code: null, message: FALLBACK, fieldErrors: [] };
  }
  if (error.status === 0) {
    return { status: 0, code: null, message: 'Unable to reach the server. Check your connection and try again.', fieldErrors: [] };
  }
  const body = (error.error ?? {}) as Partial<ApiError>;
  const message =
    error.status === 403
      ? 'You do not have permission to perform this action.'
      : error.status === 429
        ? (body.message ?? RATE_LIMITED)
      : error.status >= 500 || !body.message
        ? FALLBACK
        : body.message;
  return {
    status: error.status,
    code: body.code ?? null,
    message,
    fieldErrors: Array.isArray(body.fieldErrors) ? body.fieldErrors : [],
  };
}

/**
 * Shows server-side field errors on matching form controls (as the `server` error key) and returns the
 * messages that could not be attached to a control.
 */
export function applyServerErrors(form: FormGroup, parsed: ParsedApiError): string[] {
  const unmatched: string[] = [];
  for (const { field, message } of parsed.fieldErrors) {
    const control: AbstractControl | null = form.get(field);
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: message });
      control.markAsTouched();
    } else {
      unmatched.push(message);
    }
  }
  return unmatched;
}
