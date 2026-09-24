import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiService } from '../http/api.service';
import { AppNotification, PageResponse } from './api.models';

/** The signed-in user's own notifications; the server scopes everything to the access token's user. */
@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly api = inject(ApiService);

  list(status: 'all' | 'unread', page = 0, size = 20): Observable<PageResponse<AppNotification>> {
    return this.api.get('notifications', { params: new HttpParams().set('status', status).set('page', page).set('size', size) });
  }

  unreadCount(): Observable<{ count: number }> {
    return this.api.get('notifications/unread-count');
  }

  markRead(id: number): Observable<AppNotification> {
    return this.api.put(`notifications/${id}/read`, null);
  }

  markAllRead(): Observable<{ updated: number; unread: number }> {
    return this.api.put('notifications/read-all', null);
  }
}
