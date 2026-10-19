import { Component, input } from '@angular/core';
import { Grade } from '../people/people-api';
import { Period } from '../periods/periods-api';

/** The statuses the summary counts claims by, in the order shown. */
export const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/** One grade's line: how many people not retired have it today, and their due-totals added up. */
export interface GradeSummary {
  grade: Grade;
  count: number;
  dueTotal: string;
}

// The sheet at a glance, from the same figures the list shows: per grade, the people on it today (retired
// people excluded) and their due-totals added up; per period, its due-total; per status, how many claims have it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading" data-testid="summary">
      <h2 id="summary-heading">Summary</h2>
      <table data-testid="summary-grades">
        <caption>By grade</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">People</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="summary-periods">
        <caption>By period</caption>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">
                {{ periodDueTotals().get(p.id) ?? '0.00' }}
              </td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="summary-statuses">
        <caption>By status</caption>
        <thead>
          <tr>
            <th scope="col">Status</th>
            <th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (s of statuses; track s) {
            <tr>
              <th scope="row">{{ s }}</th>
              <td [attr.data-testid]="'summary-status-' + s + '-count'">{{ statusCounts().get(s) ?? 0 }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Summary {
  readonly grades = input.required<GradeSummary[]>();
  readonly periods = input.required<Period[]>();
  /** Each period's due-total, by id, as the period list shows it. */
  readonly periodDueTotals = input.required<Map<number, string>>();
  /** How many of the sheet's claims have each status. */
  readonly statusCounts = input.required<Map<string, number>>();

  protected readonly statuses = SUMMARY_STATUSES;
}
