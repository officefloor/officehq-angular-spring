import { Component, input } from '@angular/core';
import { Grade } from '../../core/people';

/** A grade's line of the summary: how many people are on it, their due-totals added up, and how
 *  many of them are active. */
export interface GradeSummary {
  grade: Grade;
  count: number;
  dueTotal: string;
  active: number;
}

/** A period's line of the summary: its due-total. */
export interface PeriodSummary {
  name: string;
  dueTotal: string;
}

/** A status's line of the summary: how many claims have it. */
export interface StatusSummary {
  status: string;
  count: number;
}

/** A void reason's line of the summary: how many void claims were voided with it. */
export interface VoidReasonSummary {
  reason: string;
  count: number;
}

// The sheet summed up: per grade, per period, per claim status and per void reason, with the figures the list shows.
@Component({
  selector: 'app-summary-panel',
  template: `
    <section aria-labelledby="summary-heading" data-testid="summary">
      <h2 id="summary-heading">Summary</h2>
      <table>
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By status</caption>
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
      @if (voidReasons().length > 0) {
        <table>
          <caption>By void reason</caption>
          <thead>
            <tr><th scope="col">Reason</th><th scope="col">Void claims</th></tr>
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
export class SummaryPanel {
  readonly grades = input.required<GradeSummary[]>();
  readonly periods = input.required<PeriodSummary[]>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly voidReasons = input.required<VoidReasonSummary[]>();
}
