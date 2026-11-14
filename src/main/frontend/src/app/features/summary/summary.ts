import { Component, input } from '@angular/core';

export interface GradeSummary {
  grade: string;
  /** How many people not retired are on the grade on the sheet's today. */
  count: number;
  /** Their due-totals added up, skipping those with no figure (two decimals). */
  dueTotal: string;
  /** How many of them are active. */
  active: number;
}

export interface PeriodSummary {
  name: string;
  /** The due-total the period's row shows (two decimals). */
  dueTotal: string;
  /** This period's due-total minus that of the period before it by start date (two decimals);
   *  blank for the earliest period. */
  change: string;
}

export interface ClassicPeriodSummary {
  name: string;
  /** The period's due-total counting each claim by its status now; a closed period's figure fixed
   *  when it closed (two decimals). */
  dueTotal: string;
}

export interface StatusSummary {
  status: string;
  /** How many of the sheet's claims have the status. */
  count: number;
}

export interface VoidReasonSummary {
  reason: string;
  /** How many of the sheet's VOID claims were voided with the reason. */
  count: number;
}

// The sheet in figures, each worked out from what the list shows: per grade, per period and per
// claim status and void reason; and beside it the classic summary.
@Component({
  selector: 'app-summary',
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
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</td>
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
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change }}</td>
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
      <p>Late for review: <span data-testid="summary-review-late-count">{{ reviewLateCount() }}</span></p>
      <table>
        <caption>By void reason</caption>
        <thead>
          <tr>
            <th scope="col">Reason</th>
            <th scope="col">Voided claims</th>
          </tr>
        </thead>
        <tbody>
          @for (v of voids(); track v.reason) {
            <tr>
              <th scope="row">{{ v.reason }}</th>
              <td [attr.data-testid]="'summary-void-' + v.reason + '-count'">{{ v.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <section aria-labelledby="classic-summary-heading">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <table>
        <caption>By period</caption>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (p of classicPeriods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Sheet due-total: <span data-testid="classic-due-total">{{ classicDueTotal() }}</span></p>
    </section>
  `,
})
export class Summary {
  readonly grades = input.required<GradeSummary[]>();
  readonly periods = input.required<PeriodSummary[]>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly voids = input.required<VoidReasonSummary[]>();
  /** How many of the sheet's REVIEW claims are late for review. */
  readonly reviewLateCount = input.required<number>();
  readonly classicPeriods = input.required<ClassicPeriodSummary[]>();
  readonly classicDueTotal = input.required<string>();
}
