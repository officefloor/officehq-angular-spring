import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';

// The home page: the active sheet's content. A page is an Angular component like this one, plus
// one entry in app.routes.ts.
@Component({
  selector: 'app-home',
  imports: [People],
  template: `
    @if (session.sheetId()) {
      <app-people />
    } @else if (session.signedIn()) {
      <p data-testid="home-no-sheet">Load a sheet to begin.</p>
    } @else {
      <p data-testid="home-signed-out">Sign in to begin.</p>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
}
