import { Component, input } from '@angular/core';

/** A steward's dashboard counts; null for a user who is not a steward (the cells show empty). */
export interface DashboardCounts {
  pending: number;
  review: number;
  overdue: number;
}

// A steward's dashboard over the sheet and all its descendants: how many of their claims are PENDING, how many are in REVIEW,
// and how many of those that count are overdue. A user who is not a steward (by their own role or a
// delegation in force on the sheet's today) sees the cells empty; under an as-of date it shows the
// counts as they stood then, for the role the user had then.
@Component({
  selector: 'app-dashboard',
  template: `
    <section aria-labelledby="dashboard-heading">
      <h2 id="dashboard-heading">Steward's dashboard</h2>
      <dl>
        <dt>PENDING claims</dt>
        <dd data-testid="dash-pending-count">{{ counts()?.pending ?? '' }}</dd>
        <dt>REVIEW claims</dt>
        <dd data-testid="dash-review-count">{{ counts()?.review ?? '' }}</dd>
        <dt>Overdue claims</dt>
        <dd data-testid="dash-overdue-count">{{ counts()?.overdue ?? '' }}</dd>
      </dl>
    </section>
  `,
})
export class Dashboard {
  readonly counts = input.required<DashboardCounts | null>();
}
