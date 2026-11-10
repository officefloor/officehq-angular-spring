import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';

// The sheet's counters, shown at all times above everything else on it: how many people are on the
// sheet (retired people included), how many claims are in each status (as the summary counts them)
// and the sheet's due- and overdue-totals (as the people list's totals show them). Then the same
// figures consolidated over the sheet and all its descendants (the sheets naming it as parent, and
// theirs): each sheet's as that sheet shows it, added up; a sheet with no children shows its own.
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Sheet counters">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        @for (s of statuses(); track s.status) {
          <dt>{{ s.status }}</dt>
          <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</dd>
        }
        <dt>Due</dt>
        <dd data-testid="header-due-total">{{ dueTotal() }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() }}</dd>
      </dl>
      <dl aria-label="Consolidated over the sheet and its descendants">
        <dt>People, with sub-sheets</dt>
        <dd data-testid="consolidated-people-count">{{ consolidatedPeopleCount() }}</dd>
        <dt>Due, with sub-sheets</dt>
        <dd data-testid="consolidated-due-total">{{ consolidatedDueTotal() }}</dd>
        <dt>Overdue, with sub-sheets</dt>
        <dd data-testid="consolidated-overdue-total">{{ consolidatedOverdueTotal() }}</dd>
      </dl>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  readonly consolidatedPeopleCount = input.required<number>();
  readonly consolidatedDueTotal = input.required<string>();
  readonly consolidatedOverdueTotal = input.required<string>();
}
