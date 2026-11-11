import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';

// The sheet's counters, shown at all times: its people (retired included), its claims per status as the summary
// counts them, and its due-total and overdue-total as the sheet's totals show them;
// and the signed-in user's own due-total and overdue-total, when a person on the sheet has their name.
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
        <dt>Due total</dt>
        <dd data-testid="header-due-total">{{ dueTotal() }}</dd>
        <dt>Overdue total</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() }}</dd>
        <dt>My due total</dt>
        <dd data-testid="my-due-total">{{ myDueTotal() }}</dd>
        <dt>My overdue total</dt>
        <dd data-testid="my-overdue-total">{{ myOverdueTotal() }}</dd>
      </dl>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
}
