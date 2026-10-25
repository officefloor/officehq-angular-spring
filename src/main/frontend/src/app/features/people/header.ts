import { Component, computed, inject, input } from '@angular/core';
import { Claim } from './claims.service';
import { STATUSES } from './summary';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';

// The sheet's header, shown at all times: how many people are on the sheet (retired ones
// included), how many claims are in each status (as the summary counts them), and the sheet's
// due-total and overdue-total (as its totals show them), and the signed-in user's own due-total and
// overdue-total when a person on the sheet has their name or alias, and their crew total (the due-totals
// of the crews that person leads, added up) when they lead one, and how many REVIEW claims are
// assigned to the signed-in user to review.
@Component({
  selector: 'app-header',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <dl>
        <div>
          <dt>People</dt>
          <dd data-testid="header-people-count">{{ (peopleCount()) | figure }}</dd>
        </div>
        @for (s of byStatus(); track s.status) {
          <div>
            <dt>{{ (s.status) | figure }}</dt>
            <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ (s.count) | figure }}</dd>
          </div>
        }
        <div>
          <dt>Due</dt>
          <dd data-testid="header-due-total">{{ (dueTotal()) | figure }}</dd>
        </div>
        <div>
          <dt>Overdue</dt>
          <dd data-testid="header-overdue-total">{{ (overdueTotal()) | figure }}</dd>
        </div>
        <div>
          <dt>My due</dt>
          <dd data-testid="my-due-total">{{ (myDueTotal()) | figure }}</dd>
        </div>
        <div>
          <dt>My overdue</dt>
          <dd data-testid="my-overdue-total">{{ (myOverdueTotal()) | figure }}</dd>
        </div>
        <div>
          <dt>My crew due</dt>
          <dd data-testid="my-crew-due-total">{{ (myCrewDueTotal()) | figure }}</dd>
        </div>
        <div>
          <dt>My reviews</dt>
          <dd data-testid="my-reviews-count">{{ (myReviewsCount()) | figure }}</dd>
        </div>
        <div>
          <dt>Watched due</dt>
          <dd data-testid="watched-due-total">{{ (watchedDueTotal()) | figure }}</dd>
        </div>
      </dl>
    </header>
  `,
  styles: `
    dl {
      display: flex;
      flex-wrap: wrap;
      gap: 1.5rem;
      margin: 0 0 1rem;
    }
    dt {
      font-weight: bold;
    }
    dd {
      margin: 0;
    }
  `,
})
export class Header {
  private readonly session = inject(Session);
  /** How many people are on the sheet, retired ones included. */
  readonly peopleCount = input.required<number>();
  /** The sheet's claims. */
  readonly claims = input.required<Claim[]>();
  /** The sheet's due-total, with two decimals. */
  readonly dueTotal = input.required<string>();
  /** The sheet's overdue-total, with two decimals. */
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own due-total (of the person with their name or alias); blank when none. */
  readonly myDueTotal = input.required<string>();
  /** The signed-in user's own overdue-total; blank when there is no such person. */
  readonly myOverdueTotal = input.required<string>();
  /** The due-totals of the crews the signed-in user's person leads, added up; blank when they lead none. */
  readonly myCrewDueTotal = input.required<string>();
  /** The due-totals of the people the signed-in user watches, added up; 0.00 when they watch no one. */
  readonly watchedDueTotal = input.required<string>();

  /** How many REVIEW claims are assigned to the signed-in user to review. */
  protected readonly myReviewsCount = computed(() => {
    const user = this.session.user();
    return this.claims().filter((c) => c.status === 'REVIEW' && user !== null && c.reviewAssigned === user).length;
  });

  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
}
