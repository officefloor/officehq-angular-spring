import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/locale';

/**
 * A grade's line of the summary: how many people not retired are on it today, their due-totals added up, and how many
 * of them are active.
 */
export interface GradeSummary {
  grade: string;
  count: number;
  dueTotal: string;
  active: number;
}

/**
 * A period's line of the summary: its due-total as the period list shows it, and its change from the period before it
 * by start date (blank for the earliest).
 */
export interface PeriodSummary {
  name: string;
  dueTotal: string;
  change: string;
}

/** A status's line of the summary: how many of the sheet's claims have it. */
export interface StatusSummary {
  status: string;
  count: number;
}

/** A void reason's line of the summary: how many VOID claims were voided with it. */
export interface VoidReasonSummary {
  reason: string;
  count: number;
}

// The sheet at a glance: figures per grade, per period, per claim status and per void reason, each agreeing with the lists.
@Component({
  selector: 'app-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <table>
        <caption>By grade (retired people excluded)</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due total</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade | fig }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count | fig }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal | fig }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name | fig }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal | fig }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By claim status</caption>
        <thead>
          <tr><th scope="col">Status</th><th scope="col">Claims</th></tr>
        </thead>
        <tbody>
          @for (s of statuses(); track s.status) {
            <tr>
              <th scope="row">{{ s.status | fig }}</th>
              <td [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (voidReasons().length > 0) {
        <table>
          <caption>By void reason</caption>
          <thead>
            <tr><th scope="col">Void reason</th><th scope="col">Voided claims</th></tr>
          </thead>
          <tbody>
            @for (v of voidReasons(); track v.reason) {
              <tr>
                <th scope="row">{{ v.reason | fig }}</th>
                <td [attr.data-testid]="'summary-void-' + v.reason + '-count'">{{ v.count | fig }}</td>
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
  readonly voidReasons = input<VoidReasonSummary[]>([]);
}

/** A period's line of the classic summary: its due-total counting each claim by its status now. */
export interface ClassicPeriodSummary {
  name: string;
  dueTotal: string;
}

// The classic summary, shown beside the summary: per period, the due-total counting each claim by its status now (a
// closed period's figure as fixed when it closed), and the sheet's due-total with no trimming.
@Component({
  selector: 'app-classic-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="classic-summary-heading">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <p>Due total <strong data-testid="classic-due-total">{{ dueTotal() | fig }}</strong></p>
      <table>
        <caption>By period (claims counted by their status now)</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name | fig }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal | fig }}</td>
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
