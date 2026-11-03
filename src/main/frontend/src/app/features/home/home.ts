import { Component, inject } from '@angular/core';
import { ActivityPanel } from '../audit/activity-panel';
import { AuditViewer } from '../audit/audit-viewer';
import { ClaimTemplateForm } from '../settings/claim-template-form';
import { Session } from '../../core/session';
import { PeopleList } from '../people/people-list';
import { DelegationForm } from '../settings/delegation-form';
import { GradeRetireForm } from '../settings/grade-retire-form';
import { HolidayForm } from '../settings/holiday-form';
import { NoteForm } from '../settings/note-form';
import { SettingsForm } from '../settings/settings-form';
import { VoidReasonForm } from '../settings/void-reason-form';
import { SheetBar } from '../session/sheet-bar';
import { SignIn } from '../session/signin';

// The one screen: sign in by name, then load a sheet by id and keep its people.
@Component({
  selector: 'app-home',
  imports: [SignIn, SheetBar, SettingsForm, DelegationForm, HolidayForm, GradeRetireForm, NoteForm, VoidReasonForm, ClaimTemplateForm, PeopleList, AuditViewer, ActivityPanel],
  template: `
    @if (session.user()) {
      <app-sheet-bar />
      @if (!session.sheetId()) {
        <p data-testid="home-empty">Nothing here yet.</p>
      } @else {
        <app-settings-form />
        <app-delegation-form />
        <app-holiday-form />
        <app-grade-retire-form />
        <app-note-form />
        <app-void-reason-form />
        <app-claim-template-form />
        <app-people-list />
        <app-audit-viewer />
        <app-activity-panel />
      }
    } @else {
      <app-signin />
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
}
