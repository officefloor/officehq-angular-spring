import { Component, input } from '@angular/core';
import { SUMMARY_STATUSES } from './summary';

// The counters always shown at the top of the sheet: how many people it has (retired people included), how
// many claims have each status (as the summary counts them), and the sheet's due-total and overdue-total.
@Component({
  selector: 'app-sheet-header',
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        @for (s of statuses; track s) {
          <dt>{{ s }}</dt>
          <dd [attr.data-testid]="'header-claims-' + s + '-count'">{{ statusCounts().get(s) ?? 0 }}</dd>
        }
        <dt>Due total</dt>
        <dd data-testid="header-due-total">{{ dueTotal() }}</dd>
        <dt>Overdue total</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() }}</dd>
      </dl>
    </header>
  `,
})
export class SheetHeader {
  readonly peopleCount = input.required<number>();
  /** How many of the sheet's claims have each status. */
  readonly statusCounts = input.required<Map<string, number>>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();

  protected readonly statuses = SUMMARY_STATUSES;
}
