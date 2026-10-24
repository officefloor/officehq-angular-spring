import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';

/** A steward's dashboard over the sheet: its PENDING claims, its REVIEW claims and its overdue claims
 *  that count; each null for a user who is not a steward. */
export interface DashboardCounts {
  pending: number | null;
  review: number | null;
  overdue: number | null;
}

export const NO_DASHBOARD: DashboardCounts = { pending: null, review: null, overdue: null };

// The signed-in user's steward dashboard, as the server works it out (as of the as-of date, for the
// role they had then, while one is set). A user who is not a steward sees the cells empty.
@Component({
  selector: 'app-dashboard',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="dashboard-heading" data-testid="dashboard">
      <h2 id="dashboard-heading">Steward dashboard</h2>
      <table>
        <tbody>
          <tr>
            <th scope="row">Pending</th>
            <td data-testid="dash-pending-count">{{ (counts().pending ?? '') | figure }}</td>
          </tr>
          <tr>
            <th scope="row">Review</th>
            <td data-testid="dash-review-count">{{ (counts().review ?? '') | figure }}</td>
          </tr>
          <tr>
            <th scope="row">Overdue</th>
            <td data-testid="dash-overdue-count">{{ (counts().overdue ?? '') | figure }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  `,
  styles: `
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Dashboard {
  /** The counts to show. */
  readonly counts = input.required<DashboardCounts>();
}
