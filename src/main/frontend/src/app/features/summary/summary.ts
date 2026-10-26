import { FigurePipe } from '../../core/figure.pipe';
import { Component, input } from '@angular/core';

/** A grade's line of the summary: how many people are on it today, and their due-totals added up. */
export interface GradeSummary {
  grade: string;
  count: number;
  dueTotal: string;
  /** How many of them are active. */
  active: number;
}

/** A period's line of the summary: its due-total. */
export interface PeriodSummary {
  name: string;
  dueTotal: string;
  /** Its due-total less that of the period before it by start date; empty for the earliest. */
  change: string;
}

/** A status's line of the summary: how many claims have it. */
export interface StatusSummary {
  status: string;
  count: number;
}

/** A void reason's line of the summary: how many VOID claims were voided with it. */
export interface VoidReasonSummary {
  reason: string;
  count: number;
}

// The sheet in figures: per grade, the people on it today who are not retired and their due-totals
// and how many are active; per period, its due-total and its change from the one before it; per status, how many claims have it;
// how many REVIEW claims are review-late (waiting past the review SLA);
// per reason on the sheet's list of void reasons, how many VOID claims were voided with it. Each figure is the list's own.
@Component({
  selector: 'app-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="summary-heading" data-testid="summary">
      <h2 id="summary-heading">Summary</h2>
      <table aria-label="Summary by grade">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal | figure }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table aria-label="Summary by period">
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal | figure }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table aria-label="Summary by status">
        <thead>
          <tr><th scope="col">Status</th><th scope="col">Claims</th></tr>
        </thead>
        <tbody>
          @for (s of statuses(); track s.status) {
            <tr>
              <th scope="row">{{ s.status }}</th>
              <td [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Late in review: <span data-testid="summary-review-late-count">{{ reviewLate() }}</span></p>
      @if (voidReasons().length > 0) {
        <table aria-label="Summary by void reason">
          <thead>
            <tr><th scope="col">Void reason</th><th scope="col">Claims</th></tr>
          </thead>
          <tbody>
            @for (v of voidReasons(); track v.reason) {
              <tr>
                <th scope="row">{{ v.reason }}</th>
                <td [attr.data-testid]="'summary-void-' + v.reason + '-count'">{{ v.count }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
})
export class Summary {
  readonly grades = input.required<GradeSummary[]>();
  readonly periods = input.required<PeriodSummary[]>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly voidReasons = input.required<VoidReasonSummary[]>();
  /** How many REVIEW claims are review-late. */
  readonly reviewLate = input.required<number>();
}
