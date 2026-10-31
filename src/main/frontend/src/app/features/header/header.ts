import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';

// The sheet's counters, always on show: its people (retired included), its claims per status and
// its due-total and overdue-total,
// and the signed-in user's own due-total and overdue-total. Each repeats a figure shown elsewhere and is fed from the same source.
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Sheet counters">
      <ul>
        <li>People: <strong data-testid="header-people-count">{{ peopleCount() }}</strong></li>
        @for (s of statuses(); track s.status) {
          <li>{{ s.status }}: <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong></li>
        }
        <li>Due: <strong data-testid="header-due-total">{{ dueTotal() }}</strong></li>
        <li>Overdue: <strong data-testid="header-overdue-total">{{ overdueTotal() }}</strong></li>
        <li>My due: <strong data-testid="my-due-total">{{ myDueTotal() }}</strong></li>
        <li>My overdue: <strong data-testid="my-overdue-total">{{ myOverdueTotal() }}</strong></li>
      </ul>
    </header>
  `,
})
export class SheetHeader {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<readonly StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own person's due-total and overdue-total; blank when no person has their name. */
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
}
