import { Component, input } from '@angular/core';
import { StatusSummary } from './summary-panel';
import { FigurePipe } from '../../core/figure';

// The sheet's counters, always on top: how many people it has (retired included), how many
// claims are in each status, and its due-total and overdue-total, each the figure it repeats;
// and the signed-in user's own due-total and overdue-total, when a person on the sheet is theirs,
// and their crew total, when that person leads a crew; the due-totals of the people they watch, added up;
// and how many claims in review are assigned
// to them.
@Component({
  selector: 'app-header-counters',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters" data-testid="header">
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
        <dt>My crews' due</dt>
        <dd data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</dd>
        <dt>Watched due</dt>
        <dd data-testid="watched-due-total">{{ watchedDueTotal() | figure }}</dd>
        <dt>My reviews</dt>
        <dd data-testid="my-reviews-count">{{ myReviewsCount() }}</dd>
      </dl>
    </header>
  `,
})
export class HeaderCounters {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own due-total and overdue-total: blank when no person on the sheet has
   *  their name or alias. */
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
  /** The due-totals of the crews the signed-in user's person leads, added up: blank when they lead
   *  none. */
  readonly myCrewDueTotal = input.required<string>();
  /** The due-totals of the people the signed-in user watches, added up: 0.00 when they watch no
   *  one. */
  readonly watchedDueTotal = input.required<string>();
  /** How many claims in review on the sheet are assigned to the signed-in user. */
  readonly myReviewsCount = input.required<number>();
}
