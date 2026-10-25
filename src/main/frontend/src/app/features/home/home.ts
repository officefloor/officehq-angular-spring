import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { AuditViewer } from '../audit/audit-viewer';
import { Holidays } from '../holidays/holidays';
import { People } from '../people/people';
import { Settings } from '../settings/settings';
import { SheetAsOf } from '../session/sheet-as-of';
import { SheetPicker } from '../session/sheet-picker';
import { SheetToday } from '../session/sheet-today';
import { SignIn } from '../session/sign-in';
import { VoidReasonsList } from '../void-reasons/void-reasons';

// The one screen: sign in, then choose a sheet; everything else is kept per sheet.
@Component({
  selector: 'app-home',
  imports: [AuditViewer, SignIn, SheetPicker, SheetToday, SheetAsOf, Settings, Holidays, VoidReasonsList, People],
  template: `
    @if (session.user(); as user) {
      <header>
        Signed in as <strong data-testid="current-user">{{ user }}</strong>
      </header>
      <app-sheet-picker />
      <app-sheet-today />
      <app-sheet-as-of />
      @if (session.sheet()) {
        <app-settings />
        <app-holidays />
        <app-void-reasons />
        <app-audit-viewer />
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
