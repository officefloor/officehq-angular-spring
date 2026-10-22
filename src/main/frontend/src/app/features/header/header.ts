import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';

// The sheet's header: its counters, each repeating a figure shown elsewhere on the screen — the people
// on the sheet (retired people included), the summary's claim count per status, and the sheet's
// due-total and overdue-total.
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Sheet counters">
      <p>People <strong data-testid="header-people-count">{{ peopleCount() }}</strong></p>
      <p>
        @for (s of statuses(); track s.status) {
          {{ s.status }} <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong>
        }
      </p>
      <p>Due <strong data-testid="header-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue <strong data-testid="header-overdue-total">{{ overdueTotal() }}</strong></p>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
}
