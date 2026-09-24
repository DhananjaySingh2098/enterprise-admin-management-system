import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthResponse } from '../auth/auth.models';
import { ApiService } from '../http/api.service';
import { ChangePasswordRequest, Profile, UpdateProfileRequest } from './api.models';

@Injectable({ providedIn: 'root' })
export class ProfileApi {
  private readonly api = inject(ApiService);

  get(): Observable<Profile> {
    return this.api.get('profile');
  }

  update(body: UpdateProfileRequest): Observable<Profile> {
    return this.api.put('profile', body);
  }

  /** Returns a fresh session: all other sessions are revoked server-side. */
  changePassword(body: ChangePasswordRequest): Observable<AuthResponse> {
    return this.api.put('profile/password', body);
  }
}
