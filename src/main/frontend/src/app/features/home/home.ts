import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';

// The home page: the active sheet's people, once a sheet is loaded.
@Component({
  selector: 'app-home',
  imports: [People],
  template: `
    @if (session.sheetId()) {
      <app-people />
    } @else {
      <p data-testid="home-empty">Nothing here yet.</p>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
}
