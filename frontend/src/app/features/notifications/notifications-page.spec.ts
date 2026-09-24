import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AppNotification } from '../../core/api/api.models';
import { page, settle, signInAs, testProviders } from '../../core/auth/auth.testing';
import { NotificationsPage } from './notifications-page';

const now = new Date().toISOString();
const ITEMS: AppNotification[] = [
  { id: 3, type: 'EMPLOYEE_RECORD_UPDATED', title: 'Your employee record was updated', message: 'Your employment status changed from Active to On leave.',
    relatedEntityType: 'EMPLOYEE', relatedEntityId: '15', read: false, createdAt: now },
  { id: 2, type: 'SECURITY_ALERT', title: 'A session was ended for your security', message: 'A previously used sign-in token was presented again.',
    relatedEntityType: 'SESSION', read: true, createdAt: now },
];

describe('NotificationsPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NotificationsPage>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [NotificationsPage], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(['USER']);
    fixture = TestBed.createComponent(NotificationsPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  async function flush(items = ITEMS, unread = 1) {
    const req = http.expectOne((r) => r.url === '/api/notifications');
    req.flush(page(items));
    http.expectOne('/api/notifications/unread-count').flush({ count: unread });
    await fixture.whenStable();
    return req;
  }

  it('lists all notifications as a timeline with unread markers and links to the subject', async () => {
    const req = await flush();
    expect(req.request.params.get('status')).toBe('all');
    const items = el.querySelectorAll('.timeline-item');
    expect(items.length).toBe(2);
    expect(items[0].classList).toContain('is-unread');
    expect(items[0].querySelector('.visually-hidden')?.textContent).toContain('Unread');
    expect(items[0].querySelector('a')?.getAttribute('href')).toBe('/employees?open=15');
    expect(items[1].querySelector('a')?.getAttribute('href')).toBe('/profile');
    expect(el.querySelector('.timeline-day')?.textContent).toContain('Today');
    expect(el.querySelector('.count-pill')?.textContent?.trim()).toBe('1');
  });

  it('switches to the Unread view on the server', async () => {
    await flush();
    const unreadTab = el.querySelectorAll<HTMLButtonElement>('[role="tab"]')[1];
    unreadTab.click();
    await fixture.whenStable();
    const req = await flush([ITEMS[0]]);
    expect(req.request.params.get('status')).toBe('unread');
    expect(unreadTab.getAttribute('aria-selected')).toBe('true');
  });

  it('marks one as read and fades it out of the Unread view', async () => {
    await flush();
    el.querySelectorAll<HTMLButtonElement>('[role="tab"]')[1].click();
    await fixture.whenStable();
    await flush([ITEMS[0]]);
    el.querySelector<HTMLButtonElement>('.timeline-read')!.click();
    await fixture.whenStable();
    expect(el.querySelector('.timeline-item')?.classList).toContain('is-leaving');
    http.expectOne('/api/notifications/3/read').flush({ ...ITEMS[0], read: true });
    await settle(300);
    await flush([], 0);
    expect(el.textContent).toContain('You are all caught up');
  });

  it('marks all as read and reloads', async () => {
    await flush();
    el.querySelector<HTMLButtonElement>('app-page-header button')!.click();
    await fixture.whenStable();
    http.expectOne('/api/notifications/read-all').flush({ updated: 1, unread: 0 });
    await flush(ITEMS.map((n) => ({ ...n, read: true })), 0);
    expect(el.querySelectorAll('.timeline-item.is-unread').length).toBe(0);
    expect(el.querySelector<HTMLButtonElement>('app-page-header button')!.disabled).toBe(true);
  });

  it('shows a polished empty state', async () => {
    await flush([], 0);
    expect(el.querySelector('.notifications-empty h3')?.textContent).toContain('No notifications yet');
    expect(el.querySelector('.empty-bell')).not.toBeNull();
  });
});
