import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { SheetPicker } from '../session/sheet-picker';
import { SheetToday } from '../session/sheet-today';
import { SignIn } from '../session/sign-in';

// The one screen: sign in, then choose a sheet; everything else is kept per sheet.
@Component({
  selector: 'app-home',
  imports: [SignIn, SheetPicker, SheetToday],
  template: `
    @if (session.user(); as user) {
      <header>
        Signed in as <strong data-testid="current-user">{{ user }}</strong>
      </header>
      <app-sheet-picker />
      <app-sheet-today />
      @if (!session.sheet()) {
        <p data-testid="home-empty">Load a sheet to begin.</p>
      }
    } @else {
      <app-sign-in />
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
}
