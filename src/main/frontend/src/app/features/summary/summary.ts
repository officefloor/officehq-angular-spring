import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/locale';

/**
 * A grade's line: how many people are on it today (retired people left out), how many of those are
 * active, and their due-totals added up.
 */
export interface GradeSummary {
  grade: string;
  count: number;
  active: number;
  dueTotal: string;
}

/**
 * A period's line: its due-total, as its row in the periods list shows it, and its change from the
 * period before it by start date (empty for the earliest).
 */
export interface PeriodSummary {
  name: string;
  dueTotal: string;
  change: string;
}

/** A status's line: how many of the sheet's claims have it. */
export interface StatusSummary {
  status: string;
  count: number;
}

/** A void reason's line: how many of the sheet's VOID claims were voided with it. */
export interface VoidReasonSummary {
  reason: string;
  count: number;
}

// The sheet's summary: per grade, per period, per claim status and per void reason, each figure worked from what the
// lists show.
@Component({
  selector: 'app-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <table>
        <caption>By grade</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">People</th>
            <th scope="col">Due</th>
            <th scope="col">Active</th>
          </tr>
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
      <table>
        <caption>By period</caption>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
            <th scope="col">Change</th>
          </tr>
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
      <table>
        <caption>By status</caption>
        <thead>
          <tr>
            <th scope="col">Status</th>
            <th scope="col">Claims</th>
          </tr>
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
      <table>
        <caption>By void reason</caption>
        <thead>
          <tr>
            <th scope="col">Reason</th>
            <th scope="col">Voided claims</th>
          </tr>
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
    </section>
  `,
})
export class Summary {
  readonly grades = input.required<GradeSummary[]>();
  readonly periods = input.required<PeriodSummary[]>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly voidReasons = input.required<VoidReasonSummary[]>();
}
