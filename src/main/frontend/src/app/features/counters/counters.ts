import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';

// The sheet's counters, shown at the top at all times; each repeats a figure shown elsewhere: the
// people on the sheet (retired included), the summary's claims per status and the sheet's totals;
// then the consolidated figures over the sheet and all its descendants (the sheets naming it as
// their parent, theirs, and so on), each sheet's figures as that sheet shows them, added up; and the
// signed-in user's own figures, when a person on the sheet has their name.
@Component({
  selector: 'app-counters',
  template: `
    <header aria-label="Sheet counters">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        @for (s of statuses(); track s.status) {
          <dt>{{ s.status }} claims</dt>
          <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</dd>
        }
        <dt>Due in total</dt>
        <dd data-testid="header-due-total">{{ dueTotal() }}</dd>
        <dt>Overdue in total</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() }}</dd>
        <dt>My due</dt>
        <dd data-testid="my-due-total">{{ myDueTotal() }}</dd>
        <dt>My overdue</dt>
        <dd data-testid="my-overdue-total">{{ myOverdueTotal() }}</dd>
        <dt>My crews' due</dt>
        <dd data-testid="my-crew-due-total">{{ myCrewDueTotal() }}</dd>
        <dt>People with sub-sheets</dt>
        <dd data-testid="consolidated-people-count">{{ consolidatedPeopleCount() }}</dd>
        <dt>Due in total with sub-sheets</dt>
        <dd data-testid="consolidated-due-total">{{ consolidatedDueTotal() }}</dd>
        <dt>Overdue in total with sub-sheets</dt>
        <dd data-testid="consolidated-overdue-total">{{ consolidatedOverdueTotal() }}</dd>
      </dl>
    </header>
  `,
})
export class Counters {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own figures, when a person on the sheet has their name; blank otherwise. */
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
  /** The due-totals of the crews the user's person leads, added up; blank when they lead none. */
  readonly myCrewDueTotal = input.required<string>();
  readonly consolidatedPeopleCount = input.required<number>();
  readonly consolidatedDueTotal = input.required<string>();
  readonly consolidatedOverdueTotal = input.required<string>();
}
