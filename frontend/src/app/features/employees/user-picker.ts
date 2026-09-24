import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpParams } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { catchError, debounceTime, distinctUntilChanged, filter, map, of, switchMap, tap } from 'rxjs';

import { LinkedUserRef } from '../../core/api/api.models';
import { UsersApi } from '../../core/api/users.api';
import { Icon } from '../../shared/ui/icon/icon';

let nextId = 0;

/** ADMIN-only accessible combobox for linking an employee to a system account (search by name/email). */
@Component({
  selector: 'app-user-picker',
  imports: [ReactiveFormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (value(); as selected) {
      <div class="picked-user">
        <app-icon name="link" [size]="16" />
        <span><strong>{{ selected.fullName }}</strong> · {{ selected.email }}</span>
        <button type="button" class="icon-btn icon-btn-sm" (click)="clear()" [disabled]="disabled()"
                [attr.aria-label]="'Unlink ' + selected.email"><app-icon name="x" [size]="14" /></button>
      </div>
    } @else {
      <div class="picker">
        <input [id]="inputId()" class="input" type="search" role="combobox" autocomplete="off" [formControl]="term"
               [attr.aria-expanded]="options().length > 0" [attr.aria-controls]="listId" aria-autocomplete="list"
               [attr.aria-activedescendant]="active() >= 0 ? listId + '-' + active() : null"
               placeholder="Search accounts by name or email" (keydown)="onKeydown($event)" />
        @if (options().length > 0) {
          <ul class="picker-list" role="listbox" [id]="listId" aria-label="Matching accounts">
            @for (option of options(); track option.id; let i = $index) {
              <li role="option" [id]="listId + '-' + i" [attr.aria-selected]="i === active()" [class.is-active]="i === active()"
                  (mousedown)="$event.preventDefault(); choose(option)">
                <strong>{{ option.fullName }}</strong><span>{{ option.email }}</span>
              </li>
            }
          </ul>
        }
      </div>
    }
  `,
})
export class UserPicker {
  private readonly users = inject(UsersApi);

  readonly value = input<LinkedUserRef | null>(null);
  readonly inputId = input('user-picker');
  readonly disabled = input(false);
  readonly valueChange = output<LinkedUserRef | null>();

  protected readonly listId = `user-picker-list-${++nextId}`;
  protected readonly term = new FormControl('', { nonNullable: true });
  protected readonly options = signal<LinkedUserRef[]>([]);
  protected readonly active = signal(-1);

  constructor() {
    this.term.valueChanges
      .pipe(
        map((value) => value.trim()),
        debounceTime(250),
        distinctUntilChanged(),
        tap((value) => value.length < 2 && this.options.set([])),
        filter((value) => value.length >= 2),
        switchMap((search) =>
          this.users.list(new HttpParams().set('search', search).set('size', 6).set('sort', 'name')).pipe(
            map((page) => page.content.map((u) => ({ id: u.id, email: u.email, fullName: `${u.firstName} ${u.lastName}` }))),
            catchError(() => of([])),
          ),
        ),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((options) => {
        this.options.set(options);
        this.active.set(options.length ? 0 : -1);
      });
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.options().length;
    if (!count) {
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      this.active.update((i) => (i + step + count) % count);
    } else if (event.key === 'Enter' && this.active() >= 0) {
      event.preventDefault();
      this.choose(this.options()[this.active()]);
    } else if (event.key === 'Escape') {
      event.stopPropagation();
      this.options.set([]);
    }
  }

  protected choose(option: LinkedUserRef): void {
    this.options.set([]);
    this.term.setValue('', { emitEvent: false });
    this.valueChange.emit(option);
  }

  protected clear(): void {
    this.valueChange.emit(null);
  }
}
