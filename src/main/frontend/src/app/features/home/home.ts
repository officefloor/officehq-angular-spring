import { Component, inject } from '@angular/core';
import { SessionService } from '../../core/session.service';
import { SheetPicker } from '../session/sheet-picker';
import { SignIn } from '../session/signin';

// The one screen: sign in, then choose a sheet.
@Component({
  selector: 'app-home',
  imports: [SignIn, SheetPicker],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <span data-testid="current-user">{{ user }}</span></p>
      <app-sheet-picker />
    } @else {
      <app-signin />
    }
  `,
})
export class Home {
  protected readonly session = inject(SessionService);
}
