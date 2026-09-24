import { TestBed } from '@angular/core/testing';

import { Pagination } from './pagination';

describe('Pagination', () => {
  async function render(pageIndex: number, total: number, size = 20) {
    const fixture = TestBed.createComponent(Pagination);
    fixture.componentRef.setInput('page', pageIndex);
    fixture.componentRef.setInput('size', size);
    fixture.componentRef.setInput('total', total);
    fixture.componentRef.setInput('totalPages', Math.ceil(total / size));
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('summarises the visible range', async () => {
    const { el } = await render(1, 45);
    expect(el.querySelector('.pagination-summary')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Showing 21–40 of 45');
    expect(el.querySelector('.pagination-page')?.textContent).toContain('Page 2 of 3');
  });

  it('disables previous on the first page and next on the last', async () => {
    const first = await render(0, 45);
    expect(first.el.querySelector<HTMLButtonElement>('[aria-label="Previous page"]')!.disabled).toBe(true);
    const last = await render(2, 45);
    expect(last.el.querySelector<HTMLButtonElement>('[aria-label="Next page"]')!.disabled).toBe(true);
  });

  it('emits page and size changes', async () => {
    const { fixture, el } = await render(0, 45);
    const pages: number[] = [];
    const sizes: number[] = [];
    fixture.componentInstance.pageChange.subscribe((p) => pages.push(p));
    fixture.componentInstance.sizeChange.subscribe((s) => sizes.push(s));
    el.querySelector<HTMLButtonElement>('[aria-label="Next page"]')!.click();
    const select = el.querySelector<HTMLSelectElement>('select')!;
    select.value = '50';
    select.dispatchEvent(new Event('change'));
    expect(pages).toEqual([1]);
    expect(sizes).toEqual([50]);
  });

  it('handles empty results', async () => {
    const { el } = await render(0, 0);
    expect(el.textContent).toContain('No results');
    expect(el.querySelector<HTMLButtonElement>('[aria-label="Next page"]')!.disabled).toBe(true);
  });
});
