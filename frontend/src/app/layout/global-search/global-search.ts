import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { Icon, IconName } from '../../shared/ui/icon/icon';

interface SearchScope {
  label: string;
  path: string;
  icon: IconName;
}

/**
 * Header search. Honest scope: it hands the term to the existing server-side searches (Employees, Departments and,
 * for administrators, Users) — there is no cross-entity index. ⌘K / Ctrl+K focuses it. Accessible combobox.
 */
@Component({
  selector: 'app-global-search',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'global-search', '(document:keydown)': 'onGlobalKey($event)', '(document:click)': 'onDocumentClick($event)' },
  template: `
    <app-icon class="global-search-icon" name="search" [size]="16" />
    <input #input type="search" class="global-search-input" role="combobox" autocomplete="off" [placeholder]="placeholder()"
           [attr.aria-label]="placeholder()" aria-autocomplete="list" [attr.aria-expanded]="open()" aria-controls="global-search-list"
           [attr.aria-activedescendant]="open() ? 'global-search-opt-' + active() : null"
           [value]="term()" (input)="onInput($any($event.target).value)" (keydown)="onKeydown($event)" (focus)="term() && open.set(true)" />
    <kbd class="global-search-kbd" aria-hidden="true">{{ shortcut }}</kbd>
    @if (open()) {
      <ul class="global-search-list" id="global-search-list" role="listbox" aria-label="Search in">
        @for (scope of scopes(); track scope.path; let i = $index) {
          <li role="option" [id]="'global-search-opt-' + i" [attr.aria-selected]="i === active()" [class.is-active]="i === active()"
              (mousedown)="$event.preventDefault(); go(scope)" (mouseenter)="active.set(i)">
            <span class="icon-badge"><app-icon [name]="scope.icon" [size]="15" /></span>
            <span>Search <strong>{{ scope.label }}</strong> for “{{ term() }}”</span>
            <app-icon class="global-search-go" name="chevron-right" [size]="14" />
          </li>
        }
      </ul>
    }
  `,
})
export class GlobalSearch {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('input');

  protected readonly shortcut = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K';
  protected readonly term = signal('');
  protected readonly open = signal(false);
  protected readonly active = signal(0);

  protected readonly scopes = computed<SearchScope[]>(() => [
    { label: 'employees', path: '/employees', icon: 'briefcase' },
    { label: 'departments', path: '/departments', icon: 'building' },
    ...(this.auth.hasAnyRole(['ADMIN']) ? [{ label: 'users', path: '/users', icon: 'users' as IconName }] : []),
  ]);
  protected readonly placeholder = computed(() =>
    this.auth.hasAnyRole(['ADMIN']) ? 'Search employees, departments or users…' : 'Search employees or departments…',
  );

  protected onInput(value: string): void {
    this.term.set(value);
    this.active.set(0);
    this.open.set(value.trim().length > 0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.scopes().length;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.open.set(this.term().trim().length > 0);
      this.active.update((i) => (i + (event.key === 'ArrowDown' ? 1 : -1) + count) % count);
    } else if (event.key === 'Enter' && this.term().trim()) {
      event.preventDefault();
      this.go(this.scopes()[this.active()]);
    } else if (event.key === 'Escape') {
      event.stopPropagation();
      this.open.set(false);
    }
  }

  protected go(scope: SearchScope): void {
    const term = this.term().trim();
    this.open.set(false);
    this.term.set('');
    this.input().nativeElement.blur();
    void this.router.navigate([scope.path], { queryParams: { search: term } });
  }

  protected onGlobalKey(event: KeyboardEvent): void {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.input().nativeElement.focus();
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }
}
