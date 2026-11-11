import { Component, input } from '@angular/core';
import { StatusSummary } from '../summary/summary';
import { FigurePipe } from '../../core/figure';

// The sheet's counters, shown at all times above everything else on it: how many people are on the
// sheet (retired people included), how many claims are in each status (as the summary counts them)
// and the sheet's due- and overdue-totals (as the people list's totals show them). Then the same
// figures consolidated over the sheet and all its descendants (the sheets naming it as parent, and
// theirs): the people counted once each (people whose names or aliases match, compared without
// regard to case, are one person), and the due- and overdue-totals of every person of the tree
// added up, leaving out the single highest and lowest over the whole tree when three or more have one.
// With them, per grade the due-totals of the people on it (retired left out) and per status the
// count of claims in it, each over the whole tree.
// Last, the signed-in user's own figures: the due- and overdue-totals of the person on the sheet
// with the user's name (by name or alias); blank when there is no such person. And their crew total:
// the due-totals of the crews that person leads, added up; blank when they lead none. And how many
// of the sheet's REVIEW claims are assigned to the user. And their watched total: the due-totals of
// the people they watch added up (n/a skipped); 0.00 when they watch no one.
@Component({
  selector: 'app-header',
  imports: [FigurePipe],
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
        <dd data-testid="header-due-total">{{ dueTotal() | figure }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() | figure }}</dd>
      </dl>
      <dl aria-label="Consolidated over the sheet and its descendants">
        <dt>People, with sub-sheets</dt>
        <dd data-testid="consolidated-people-count">{{ consolidatedPeopleCount() }}</dd>
        <dt>Due, with sub-sheets</dt>
        <dd data-testid="consolidated-due-total">{{ consolidatedDueTotal() | figure }}</dd>
        <dt>Overdue, with sub-sheets</dt>
        <dd data-testid="consolidated-overdue-total">{{ consolidatedOverdueTotal() | figure }}</dd>
        <dt>Settled, with sub-sheets</dt>
        <dd data-testid="consolidated-settled-total">{{ consolidatedSettledTotal() | figure }}</dd>
        <dt>Open, with sub-sheets</dt>
        <dd data-testid="consolidated-open-total">{{ consolidatedOpenTotal() | figure }}</dd>
        <dt>Cut, with sub-sheets</dt>
        <dd data-testid="consolidated-cut-total">{{ consolidatedCutTotal() | figure }}</dd>
        @for (g of consolidatedGrades(); track g.grade) {
          <dt>Grade {{ g.grade }} due, with sub-sheets</dt>
          <dd [attr.data-testid]="'consolidated-grade-' + g.grade + '-due-total'">{{ g.dueTotal | figure }}</dd>
        }
        @for (s of consolidatedStatuses(); track s.status) {
          <dt>{{ s.status }}, with sub-sheets</dt>
          <dd [attr.data-testid]="'consolidated-status-' + s.status + '-count'">{{ s.count }}</dd>
        }
      </dl>
      <dl aria-label="My figures">
        <dt>My due</dt>
        <dd data-testid="my-due-total">{{ myDueTotal() | figure }}</dd>
        <dt>My overdue</dt>
        <dd data-testid="my-overdue-total">{{ myOverdueTotal() | figure }}</dd>
        <dt>My crews' due</dt>
        <dd data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</dd>
        <dt>My reviews</dt>
        <dd data-testid="my-reviews-count">{{ myReviewsCount() }}</dd>
        <dt>Watched due</dt>
        <dd data-testid="watched-due-total">{{ watchedDueTotal() | figure }}</dd>
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
  readonly consolidatedSettledTotal = input.required<string>();
  readonly consolidatedOpenTotal = input.required<string>();
  readonly consolidatedCutTotal = input.required<string>();
  readonly consolidatedGrades = input.required<{ grade: string; dueTotal: string }[]>();
  readonly consolidatedStatuses = input.required<StatusSummary[]>();
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
  readonly myCrewDueTotal = input.required<string>();
  readonly myReviewsCount = input.required<number>();
  readonly watchedDueTotal = input.required<string>();
}
