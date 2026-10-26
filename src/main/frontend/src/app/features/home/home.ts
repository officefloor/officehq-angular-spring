import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { AuditViewer } from '../audit/audit-viewer';
import { Activity } from '../audit/activity';
import { GradesList } from '../grades/grades';
import { Holidays } from '../holidays/holidays';
import { People } from '../people/people';
import { Settings } from '../settings/settings';
import { SheetAsOf } from '../session/sheet-as-of';
import { SheetPicker } from '../session/sheet-picker';
import { SheetToday } from '../session/sheet-today';
import { SignIn } from '../session/sign-in';
import { StewardDashboard } from '../summary/steward-dashboard';
import { UserLocaleChoice } from '../session/user-locale';
import { TemplatesList } from '../templates/templates';
import { VoidReasonsList } from '../void-reasons/void-reasons';
import { NotesList } from '../notes/notes';

// The one screen: sign in, then choose a sheet; everything else is kept per sheet.
@Component({
  selector: 'app-home',
  imports: [AuditViewer, Activity, SignIn, UserLocaleChoice, SheetPicker, SheetToday, SheetAsOf, StewardDashboard, Settings, GradesList, Holidays, VoidReasonsList, NotesList, TemplatesList, People],
  template: `
    @if (session.user(); as user) {
      <header>
        Signed in as <strong data-testid="current-user">{{ user }}</strong>
      </header>
      <app-user-locale />
      <app-sheet-picker />
      <app-sheet-today />
      <app-sheet-as-of />
      @if (session.sheet()) {
        <app-steward-dashboard />
        <app-settings />
        <app-grades />
        <app-holidays />
        <app-void-reasons />
        <app-notes />
        <app-templates />
        <app-audit-viewer />
        <app-activity />
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
