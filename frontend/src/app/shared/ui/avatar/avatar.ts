import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Initials avatar. The tint is derived from the name so people stay recognisable across screens. */
@Component({
  selector: 'app-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'avatar', '[attr.data-size]': 'size()', '[style.--avatar-hue]': 'hue()', 'aria-hidden': 'true' },
  template: `{{ initials() }}`,
})
export class Avatar {
  readonly name = input.required<string>();
  readonly size = input<'sm' | 'md' | 'lg'>('md');

  protected readonly initials = computed(() => {
    const parts = this.name().trim().split(/\s+/).filter(Boolean);
    const letters = parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : (parts[0] ?? '?').slice(0, 2);
    return letters.toUpperCase();
  });

  protected readonly hue = computed(() => {
    let hash = 0;
    for (const char of this.name()) {
      hash = (hash * 31 + char.charCodeAt(0)) % 360;
    }
    return hash;
  });
}
