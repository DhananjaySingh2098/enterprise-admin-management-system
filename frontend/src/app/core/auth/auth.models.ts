/** Application roles. Mirrors the backend RoleName enum; no other values are valid. */
export type Role = 'ADMIN' | 'MANAGER' | 'USER';

export const ROLES: readonly Role[] = ['ADMIN', 'MANAGER', 'USER'];

export interface CurrentUser {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roles: Role[];
}

/** Body of POST /api/auth/login and /api/auth/refresh. The refresh token itself is only ever in an HttpOnly cookie. */
export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: CurrentUser;
}

export type AuthStatus = 'initializing' | 'authenticated' | 'anonymous';
