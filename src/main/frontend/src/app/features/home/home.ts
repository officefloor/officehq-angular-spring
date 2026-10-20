import { Component, inject } from '@angular/core';
import { Session } from '../../core/session';
import { Delegations } from '../delegations/delegations';
import { People } from '../people/people';
import { Settings } from '../settings/settings';

// The home page: the active sheet's settings, delegations and people, once a sheet is loaded.
@Component({
  selector: 'app-home',
  imports: [Delegations, People, Settings],
  template: `
    @if (session.sheetId()) {
      <app-settings />
      <app-delegations />
      <app-people />
    } @else {
      <p data-testid="home-empty">Nothing here yet.</p>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
}
