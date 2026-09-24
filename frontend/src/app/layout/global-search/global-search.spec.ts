import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { signInAs, testProviders } from '../../core/auth/auth.testing';
import { GlobalSearch } from './global-search';

describe('GlobalSearch', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [GlobalSearch], providers: testProviders() }));

  async function render(roles: ('ADMIN' | 'MANAGER' | 'USER')[]) {
    await signInAs(roles);
    const fixture = TestBed.createComponent(GlobalSearch);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const input = el.querySelector<HTMLInputElement>('input')!;
    return { fixture, el, input };
  }

  async function type(fixture: { whenStable(): Promise<unknown> }, input: HTMLInputElement, value: string) {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  it('offers only the entities the role can search, including Users for ADMIN', async () => {
    const { fixture, el, input } = await render(['ADMIN']);
    expect(input.getAttribute('role')).toBe('combobox');
    await type(fixture, input, 'ada');
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect([...el.querySelectorAll('[role="option"] strong')].map((s) => s.textContent)).toEqual(['employees', 'departments', 'users']);
  });

  it('hides Users for MANAGER and navigates with the term on Enter', async () => {
    const { fixture, el, input } = await render(['MANAGER']);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await type(fixture, input, 'blake');
    expect(el.querySelectorAll('[role="option"]').length).toBe(2);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(navigate).toHaveBeenCalledWith(['/departments'], { queryParams: { search: 'blake' } });
  });
});
