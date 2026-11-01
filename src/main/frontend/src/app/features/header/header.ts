import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';
import { FigurePipe } from '../../core/figure';

// The sheet's counters, always on show: its people (retired included), its claims per status and
// its due-total and overdue-total,
// the signed-in user's own due-total and overdue-total, the due-totals of the crews they lead, how many REVIEW claims are assigned to them and the due-totals of the people they watch. Each repeats a figure shown elsewhere and is fed from the same source.
@Component({
  selector: 'app-header',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters">
      <ul>
        <li>People: <strong data-testid="header-people-count">{{ peopleCount() }}</strong></li>
        @for (s of statuses(); track s.status) {
          <li>{{ s.status }}: <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong></li>
        }
        <li>Due: <strong data-testid="header-due-total">{{ (dueTotal()) | figure }}</strong></li>
        <li>Overdue: <strong data-testid="header-overdue-total">{{ (overdueTotal()) | figure }}</strong></li>
        <li>My due: <strong data-testid="my-due-total">{{ (myDueTotal()) | figure }}</strong></li>
        <li>My overdue: <strong data-testid="my-overdue-total">{{ (myOverdueTotal()) | figure }}</strong></li>
        <li>My reviews: <strong data-testid="my-reviews-count">{{ myReviewsCount() }}</strong></li>
        <li>My crews' due: <strong data-testid="my-crew-due-total">{{ (myCrewDueTotal()) | figure }}</strong></li>
        <li>Watched due: <strong data-testid="watched-due-total">{{ (watchedDueTotal()) | figure }}</strong></li>
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
  /** The due-totals of the crews the signed-in user's own person leads, added up; blank when they lead none. */
  readonly myCrewDueTotal = input.required<string>();
  /** How many REVIEW claims are assigned to the signed-in user to review. */
  readonly myReviewsCount = input.required<number>();
  /** The due-totals of the people the signed-in user watches, added up; 0.00 when they watch no one. */
  readonly watchedDueTotal = input.required<string>();
}
