import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { PeopleList } from '../people/people-list';
import { DelegationForm } from '../settings/delegation-form';
import { HolidayForm } from '../settings/holiday-form';
import { SettingsForm } from '../settings/settings-form';
import { VoidReasonForm } from '../settings/void-reason-form';
import { SheetBar } from '../session/sheet-bar';
import { SignIn } from '../session/signin';

// The one screen: sign in by name, then load a sheet by id and keep its people.
@Component({
  selector: 'app-home',
  imports: [SignIn, SheetBar, SettingsForm, DelegationForm, HolidayForm, VoidReasonForm, PeopleList],
  template: `
    @if (session.user()) {
      <app-sheet-bar />
      @if (!session.sheetId()) {
        <p data-testid="home-empty">Nothing here yet.</p>
      } @else {
        <app-settings-form />
        <app-delegation-form />
        <app-holiday-form />
        <app-void-reason-form />
        <app-people-list />
      }
    } @else {
      <app-signin />
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
}
