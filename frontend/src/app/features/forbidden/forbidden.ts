import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="forbidden">
      <h1>Access denied</h1>
      <p>Your account does not have permission to view this page.</p>
      <a routerLink="/">Back to home</a>
    </section>
  `,
})
export class Forbidden {}
