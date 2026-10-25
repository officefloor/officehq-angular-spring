import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';
import { Settings } from '../settings/settings';
import { SheetPicker } from '../session/sheet-picker';
import { SheetToday } from '../session/sheet-today';
import { SignIn } from '../session/sign-in';

// The one screen: sign in, then choose a sheet; everything else is kept per sheet.
@Component({
  selector: 'app-home',
  imports: [SignIn, SheetPicker, SheetToday, Settings, People],
  template: `
    @if (session.user(); as user) {
      <header>
        Signed in as <strong data-testid="current-user">{{ user }}</strong>
      </header>
      <app-sheet-picker />
      <app-sheet-today />
      @if (session.sheet()) {
        <app-settings />
      }
      <app-people />
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
