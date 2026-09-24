/** Mirrors the backend's uniform ApiErrorResponse. `code` and `fieldErrors` are optional. */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  code?: string;
  fieldErrors?: { field: string; message: string }[];
}
