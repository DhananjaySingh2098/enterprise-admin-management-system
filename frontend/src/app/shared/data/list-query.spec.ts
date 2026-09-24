import { HttpParams } from '@angular/common/http';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';

import { PageResponse } from '../../core/api/api.models';
import { page } from '../../core/auth/auth.testing';
import { ListQuery, pagedResource } from './list-query';

describe('ListQuery', () => {
  it('builds server params, omitting empty search and filters', () => {
    const q = new ListQuery('name');
    expect(q.params().toString()).toBe('page=0&size=20&sort=name&direction=asc');
    q.setSearch('  ada ');
    q.setFilter('status', 'ACTIVE');
    q.setFilter('departmentId', null);
    expect(q.params().get('search')).toBe('ada');
    expect(q.params().get('status')).toBe('ACTIVE');
    expect(q.params().has('departmentId')).toBe(false);
  });

  it('toggles direction on the same column and resets to asc on a new one', () => {
    const q = new ListQuery('name');
    q.toggleSort('name');
    expect(q.direction()).toBe('desc');
    q.toggleSort('email');
    expect([q.sort(), q.direction()]).toEqual(['email', 'asc']);
  });

  it('returns to the first page whenever the result set changes', () => {
    const q = new ListQuery('name');
    for (const change of [() => q.setSearch('x'), () => q.setFilter('role', 'ADMIN'), () => q.toggleSort('email'), () => q.setSize(50)]) {
      q.setPage(3);
      change();
      expect(q.page()).toBe(0);
    }
    q.setPage(-5);
    expect(q.page()).toBe(0);
  });
});

@Component({ template: '' })
class Host {
  readonly query = new ListQuery('name');
  readonly responses = new Subject<PageResponse<string>>();
  readonly calls: HttpParams[] = [];
  readonly resource = pagedResource(this.query, (params) => {
    this.calls.push(params);
    return this.responses;
  });
}

describe('pagedResource', () => {
  it('loads on every query change and exposes loading/data', async () => {
    const fixture = TestBed.createComponent(Host);
    const host = fixture.componentInstance;
    await fixture.whenStable();
    expect(host.resource.loading()).toBe(true);
    host.responses.next(page(['a', 'b']));
    expect(host.resource.data()?.content).toEqual(['a', 'b']);
    expect(host.resource.loading()).toBe(false);

    host.query.setPage(1);
    await fixture.whenStable();
    expect(host.calls.at(-1)?.get('page')).toBe('1');
    host.query.reload();
    await fixture.whenStable();
    expect(host.calls.length).toBe(3);
  });

  it('snaps back when the requested page no longer exists', async () => {
    const fixture = TestBed.createComponent(Host);
    const host = fixture.componentInstance;
    await fixture.whenStable();
    host.query.setPage(4);
    await fixture.whenStable();
    host.responses.next(page([], { page: 4, totalElements: 30, totalPages: 2 }));
    await fixture.whenStable();
    expect(host.query.page()).toBe(1);
  });
});
