import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { StatusSummary } from './summary';

// The sheet's counters, shown at all times: everyone on it (retired people included), the claims in
// each status as the summary counts them, the sheet's due-total and overdue-total, and
// the signed-in user's own when a person on the sheet has their name or alias, and their crew total
// when that person leads a crew.
@Component({
  selector: 'app-sheet-header',
  imports: [FigurePipe],
  template: `
    <section aria-label="Sheet counters" data-testid="sheet-header">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        @for (s of statuses(); track s.status) {
          <dt>{{ s.status }}</dt>
          <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</dd>
        }
        <dt>Due</dt>
        <dd data-testid="header-due-total">{{ dueTotal() | figure }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() | figure }}</dd>
        <dt>My due</dt>
        <dd data-testid="my-due-total">{{ myDueTotal() | figure }}</dd>
        <dt>My overdue</dt>
        <dd data-testid="my-overdue-total">{{ myOverdueTotal() | figure }}</dd>
        <dt>My crew due</dt>
        <dd data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</dd>
      </dl>
    </section>
  `,
})
export class SheetHeader {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own due-total and overdue-total; blank when no one on the sheet has their name. */
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
  /** The due-totals of the crews the signed-in user's person leads, added up; blank when they lead none. */
  readonly myCrewDueTotal = input.required<string>();
}
