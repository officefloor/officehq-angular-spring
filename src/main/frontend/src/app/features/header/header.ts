import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';

// The sheet's counters, shown at all times above everything else on it: how many people are on the
// sheet (retired people included), how many claims are in each status (as the summary counts them)
// and the sheet's due- and overdue-totals (as the people list's totals show them).
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
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
}
