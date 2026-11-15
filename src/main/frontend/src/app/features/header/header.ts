import { Component, computed, inject, input } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { Claim } from '../claims/claims-api';
import { DescendantFigures } from '../people/people-api';
import { STATUSES, statusCounts } from '../summary/summary';

// The sheet's counters, shown at all times: how many people are on it (retired ones too), how many
// claims are in each status (as the summary counts them), and its due-total and overdue-total (as
// the sheet's totals show them); and the same figures consolidated over the sheet and all its
// descendants (each sheet's totals as that sheet shows them, added up, with per grade the due-totals
// of the people on it, retired ones left out, and per status how many claims are in it); and the signed-in user's own
// due-total and overdue-total, when a person on the sheet goes by their name, and their crew total,
// when that person leads a crew; and, for a steward, their dashboard: how many claims are PENDING,
// how many in REVIEW and how many counting ones are overdue, over the sheet and all its descendants
// (the cells empty for anyone else); and
// how many REVIEW claims are assigned to the signed-in user.
/** A steward's dashboard: the PENDING claims, REVIEW claims and counting overdue claims of the
 *  sheet and all its descendants. */
export interface Dashboard {
  pending: number;
  review: number;
  overdue: number;
}

@Component({
  selector: 'app-header',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <p>People <strong data-testid="header-people-count">{{ peopleCount() }}</strong></p>
      <p>Claims
        @for (s of byStatus(); track s.status) {
          {{ s.status }} <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong>
        }
      </p>
      <p>Due total <strong data-testid="header-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue total <strong data-testid="header-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>My due total <strong data-testid="my-due-total">{{ myDueTotal() | figure }}</strong>,
        my overdue total <strong data-testid="my-overdue-total">{{ myOverdueTotal() | figure }}</strong></p>
      <p>My crew total <strong data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</strong></p>
      <p>Watched total <strong data-testid="watched-due-total">{{ watchedDueTotal() | figure }}</strong></p>
      <p>My reviews <strong data-testid="my-reviews-count">{{ myReviews() }}</strong></p>
      <p>Steward's dashboard: pending <strong data-testid="dash-pending-count">{{ dashboard()?.pending ?? '' }}</strong>,
        in review <strong data-testid="dash-review-count">{{ dashboard()?.review ?? '' }}</strong>,
        overdue <strong data-testid="dash-overdue-count">{{ dashboard()?.overdue ?? '' }}</strong></p>
      <p>With its descendants: people <strong data-testid="consolidated-people-count">{{ consolidated().people }}</strong>,
        due total <strong data-testid="consolidated-due-total">{{ consolidated().dueTotal | figure }}</strong>,
        overdue total <strong data-testid="consolidated-overdue-total">{{ consolidated().overdueTotal | figure }}</strong></p>
      <p>With its descendants, due total by grade:
        @for (g of grades(); track g) {
          {{ g }} <strong [attr.data-testid]="'consolidated-grade-' + g + '-due-total'">{{ (consolidated().gradeDueTotals[g] ?? '0.00') | figure }}</strong>
        }
      </p>
      <p>With its descendants, claims:
        @for (s of statuses; track s) {
          {{ s }} <strong [attr.data-testid]="'consolidated-status-' + s + '-count'">{{ consolidated().statusCounts[s] ?? 0 }}</strong>
        }
      </p>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly claims = input.required<Claim[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  readonly consolidated = input.required<DescendantFigures>();
  private readonly session = inject(Session);
  /** The sheet's grades, then any other grade a descendant sheet has a due-total for. */
  protected readonly grades = computed(() => {
    const own = this.session.grades();
    return [...own, ...Object.keys(this.consolidated().gradeDueTotals).filter((g) => !own.includes(g))];
  });
  protected readonly statuses = STATUSES;
  /** The signed-in user's own figures (of the person on the sheet going by their name); null when none. */
  readonly myDueTotal = input<string | null>(null);
  readonly myOverdueTotal = input<string | null>(null);
  /** The due-totals of the crews the signed-in user's person leads, added up; null when they lead none. */
  readonly myCrewDueTotal = input<string | null>(null);
  /** The due-totals of the people the signed-in user watches, added up. */
  readonly watchedDueTotal = input<string>('0.00');
  /** The steward's dashboard counts; null when the signed-in user is not a steward. */
  readonly dashboard = input<Dashboard | null>(null);

  /** The signed-in user's name; null when no one is signed in. */
  readonly user = input<string | null>(null);

  /** How many REVIEW claims are assigned to the signed-in user. */
  protected readonly myReviews = computed(() =>
    this.claims().filter((c) => c.status === 'REVIEW' && c.reviewAssignee !== null && c.reviewAssignee === this.user()).length);
  protected readonly byStatus = computed(() => statusCounts(this.claims()));
}
