import { Component, input } from '@angular/core';

// The sheet at a glance, drawn from the same figures the lists show: per grade, how many people
// not retired are on it today, their due-totals added up and how many of them are active; per period, its due-total and its change from the period before it by start date; per
// status, how many claims on the sheet have it; per void reason on the sheet's list, how many VOID
// claims were voided with it.
export interface GradeSummary {
  grade: string;
  count: number;
  dueTotal: string;
  active: number;
}

export interface PeriodSummary {
  name: string;
  dueTotal: string;
  /** This period's due-total minus the one before it by start date; empty for the earliest. */
  change: string;
}

export interface VoidReasonSummary {
  reason: string;
  count: number;
}

export interface StatusSummary {
  status: string;
  count: number;
}

@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <table aria-label="By grade">
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
      <table aria-label="By period">
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
      <table aria-label="By status">
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
      <table aria-label="By void reason">
        <thead>
          <tr>
            <th scope="col">Void reason</th>
            <th scope="col">Claims</th>
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

// The classic summary, beside the summary: per period, its due-total counting each claim by its
// status now (a closed period shows the figure fixed when it closed), and the sheet's due-total
// untrimmed (everyone's due-total added up).
export interface ClassicPeriodSummary {
  name: string;
  dueTotal: string;
}

@Component({
  selector: 'app-classic-summary',
  template: `
    <section aria-labelledby="classic-summary-heading">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <p>Due in total <strong data-testid="classic-due-total">{{ dueTotal() }}</strong></p>
      <table aria-label="Classic by period">
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class ClassicSummary {
  readonly periods = input.required<ClassicPeriodSummary[]>();
  readonly dueTotal = input.required<string>();
}
