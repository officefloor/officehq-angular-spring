import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';

// The home page: the active sheet's content. A page is an Angular component like this one, plus
// one entry in app.routes.ts.
@Component({
  selector: 'app-home',
  template: `
    @if (session.sheetId()) {
      <p data-testid="home-empty">Nothing here yet.</p>
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
