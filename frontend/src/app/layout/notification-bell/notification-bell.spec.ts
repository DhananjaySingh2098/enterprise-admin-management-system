import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AppNotification } from '../../core/api/api.models';
import { page, settle, signInAs, testProviders } from '../../core/auth/auth.testing';
import { NotificationCenter } from '../../core/notifications/notification-center';
import { NotificationBell } from './notification-bell';

const now = new Date().toISOString();
const ITEMS: AppNotification[] = [
  { id: 11, type: 'ROLE_CHANGED', title: 'Your access was updated', message: 'Your roles are now MANAGER (previously USER).', read: false, createdAt: now },
  { id: 10, type: 'PASSWORD_CHANGED', title: 'Your password was changed', message: 'All other sessions were signed out.', read: true, createdAt: now },
];

describe('NotificationBell', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NotificationBell>;
  let el: HTMLElement;
  let center: NotificationCenter;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [NotificationBell], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    await signInAs(['USER']);
    center = TestBed.inject(NotificationCenter);
    fixture = TestBed.createComponent(NotificationBell);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  async function setUnread(count: number) {
    center.refreshCount();
    http.expectOne('/api/notifications/unread-count').flush({ count });
    await fixture.whenStable();
  }

  async function open(items = ITEMS, unread = 1) {
    el.querySelector<HTMLButtonElement>('.bell-trigger')!.click();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/notifications').flush(page(items));
    http.expectOne('/api/notifications/unread-count').flush({ count: unread });
    await fixture.whenStable();
  }

  it('shows no badge without unread notifications and an accessible count otherwise', async () => {
    const trigger = el.querySelector<HTMLButtonElement>('.bell-trigger')!;
    expect(el.querySelector('.bell-badge')).toBeNull();
    expect(trigger.getAttribute('aria-label')).toBe('Notifications');

    await setUnread(4);
    expect(el.querySelector('.bell-badge')?.textContent?.trim()).toBe('4');
    expect(trigger.getAttribute('aria-label')).toBe('Notifications, 4 unread');
    expect(trigger.getAttribute('data-bump')).toBe('a');

    await setUnread(120);
    expect(el.querySelector('.bell-badge')?.textContent?.trim()).toBe('99+');
    expect(trigger.getAttribute('data-bump')).toBe('b');
    // A lower count (read elsewhere) does not replay the animation.
    await setUnread(3);
    expect(trigger.getAttribute('data-bump')).toBe('b');
  });

  it('opens a popover with the newest notifications, unread first-class', async () => {
    await open();
    const req = el.querySelector('#notification-panel');
    expect(req).not.toBeNull();
    expect(el.querySelector('.bell-trigger')?.getAttribute('aria-expanded')).toBe('true');
    const items = el.querySelectorAll('.notification-item');
    expect(items.length).toBe(2);
    expect(items[0].classList).toContain('is-unread');
    expect(items[0].textContent).toContain('Your access was updated');
    expect(items[0].querySelector('time')?.textContent?.trim()).toBe('just now');
    expect(items[1].querySelector('.notification-read')).toBeNull();
    expect(el.querySelector('a.notification-footer')?.getAttribute('href')).toBe('/notifications');
  });

  it('marks one notification as read optimistically', async () => {
    await open();
    el.querySelector<HTMLButtonElement>('.notification-read')!.click();
    await fixture.whenStable();
    expect(el.querySelector('.notification-item')?.classList).not.toContain('is-unread');
    expect(center.unread()).toBe(0);
    const req = http.expectOne('/api/notifications/11/read');
    expect(req.request.method).toBe('PUT');
    req.flush({ ...ITEMS[0], read: true, readAt: now });
  });

  it('marks all as read', async () => {
    await open(ITEMS, 1);
    const button = [...el.querySelectorAll<HTMLButtonElement>('.notification-panel-head button')][0];
    expect(button.disabled).toBe(false);
    button.click();
    await fixture.whenStable();
    http.expectOne('/api/notifications/read-all').flush({ updated: 1, unread: 0 });
    await fixture.whenStable();
    expect(el.querySelectorAll('.notification-item.is-unread').length).toBe(0);
    expect(el.textContent).toContain('You are all caught up');
    expect(button.disabled).toBe(true);
  });

  it('shows a polished empty state and never invents entries', async () => {
    await open([], 0);
    expect(el.querySelectorAll('.notification-item').length).toBe(0);
    expect(el.querySelector('.notification-empty')?.textContent).toContain('No notifications yet');
  });

  it('closes on Escape and returns focus to the bell', async () => {
    await open();
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(el.querySelector('#notification-panel')).toBeNull();
    expect(document.activeElement).toBe(el.querySelector('.bell-trigger'));
    await settle();
  });
});
