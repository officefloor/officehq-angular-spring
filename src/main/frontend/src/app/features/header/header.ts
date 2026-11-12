import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import { StatusSummary } from '../summary/summary';

// The sheet's counters, shown at all times: its people (retired included), its claims per status as the summary
// counts them, and its due-total and overdue-total as the sheet's totals show them;
// and the signed-in user's own due-total and overdue-total, when a person on the sheet has their name; and their crew
// total, the due-totals of the crews that person leads added up, when they lead one; and how many REVIEW claims are
// assigned to them.
@Component({
  selector: 'app-header',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() | fig }}</dd>
        @for (s of statuses(); track s.status) {
          <dt>{{ s.status | fig }}</dt>
          <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count | fig }}</dd>
        }
        <dt>Due total</dt>
        <dd data-testid="header-due-total">{{ dueTotal() | fig }}</dd>
        <dt>Overdue total</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() | fig }}</dd>
        <dt>My due total</dt>
        <dd data-testid="my-due-total">{{ myDueTotal() | fig }}</dd>
        <dt>My overdue total</dt>
        <dd data-testid="my-overdue-total">{{ myOverdueTotal() | fig }}</dd>
        <dt>My crew total</dt>
        <dd data-testid="my-crew-due-total">{{ myCrewDueTotal() | fig }}</dd>
        <dt>My reviews</dt>
        <dd data-testid="my-reviews-count">{{ myReviewsCount() | fig }}</dd>
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
  readonly myCrewDueTotal = input.required<string>();
  readonly myReviewsCount = input.required<number>();
}
