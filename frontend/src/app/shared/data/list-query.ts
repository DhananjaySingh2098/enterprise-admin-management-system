import { HttpParams } from '@angular/common/http';
import { DestroyRef, Signal, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, of, switchMap, tap } from 'rxjs';

import { PageResponse } from '../../core/api/api.models';
import { parseApiError } from '../../core/http/api-error';

export type SortDirection = 'asc' | 'desc';
type FilterValue = string | number | boolean | null;

/**
 * Server-side list state: page, size, sort, direction, search and filters. Every change produces a new
 * immutable request; changing anything except the page returns to the first page.
 */
export class ListQuery {
  readonly page = signal(0);
  readonly size = signal(20);
  readonly sort = signal<string>('');
  readonly direction = signal<SortDirection>('asc');
  readonly search = signal('');
  readonly filters = signal<Record<string, FilterValue>>({});
  private readonly revision = signal(0);

  constructor(defaultSort: string, defaultDirection: SortDirection = 'asc', size = 20) {
    this.sort.set(defaultSort);
    this.direction.set(defaultDirection);
    this.size.set(size);
  }

  readonly params: Signal<HttpParams> = computed(() => {
    this.revision();
    let params = new HttpParams()
      .set('page', this.page())
      .set('size', this.size())
      .set('sort', this.sort())
      .set('direction', this.direction());
    const term = this.search().trim();
    if (term) {
      params = params.set('search', term);
    }
    for (const [key, value] of Object.entries(this.filters())) {
      if (value !== null && value !== '') {
        params = params.set(key, value);
      }
    }
    return params;
  });

  setSearch(term: string): void {
    this.search.set(term);
    this.page.set(0);
  }

  setFilter(key: string, value: FilterValue): void {
    this.filters.update((filters) => ({ ...filters, [key]: value }));
    this.page.set(0);
  }

  setPage(page: number): void {
    this.page.set(Math.max(0, page));
  }

  setSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
  }

  /** Clicking the active column flips direction; a new column starts ascending. */
  toggleSort(key: string): void {
    if (this.sort() === key) {
      this.direction.update((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sort.set(key);
      this.direction.set('asc');
    }
    this.page.set(0);
  }

  /** Re-runs the current request (e.g. after a mutation). */
  reload(): void {
    this.revision.update((n) => n + 1);
  }
}

export interface PagedResource<T> {
  data: Signal<PageResponse<T> | null>;
  loading: Signal<boolean>;
  error: Signal<string | null>;
}

/** Loads pages whenever the query changes. Must be called in an injection context. Cancels stale requests. */
export function pagedResource<T>(query: ListQuery, load: (params: HttpParams) => Observable<PageResponse<T>>): PagedResource<T> {
  const data = signal<PageResponse<T> | null>(null);
  const loading = signal(true);
  const error = signal<string | null>(null);

  toObservable(query.params)
    .pipe(
      tap(() => loading.set(true)),
      switchMap((params) =>
        load(params).pipe(
          catchError((err: unknown) => {
            error.set(parseApiError(err).message);
            return of(null);
          }),
        ),
      ),
      takeUntilDestroyed(inject(DestroyRef)),
    )
    .subscribe((page) => {
      if (page) {
        // A page beyond the end (e.g. after the last row on it was filtered away) snaps back.
        if (page.content.length === 0 && page.page > 0 && page.totalPages > 0) {
          query.setPage(page.totalPages - 1);
          return;
        }
        data.set(page);
        error.set(null);
      }
      loading.set(false);
    });

  return { data: data.asReadonly(), loading: loading.asReadonly(), error: error.asReadonly() };
}
