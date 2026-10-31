import { Component, input } from '@angular/core';
import { Grade } from '../../core/people';

/** A grade's line: how many people not retired are on it today, their due-totals added up, and how many of
 *  them are active. */
export interface GradeSummary {
  grade: Grade;
  count: number;
  dueTotal: string;
  active: number;
}

/** A period's line: its due-total, as the period's row shows it, and its change from the period before it by start
 *  date (empty for the earliest). */
export interface PeriodSummary {
  name: string;
  dueTotal: string;
  change: string;
}

/** A status's line: how many claims on the sheet are in it. */
export interface StatusSummary {
  status: string;
  count: number;
}

/** A void reason's line: how many VOID claims on the sheet were voided with it. */
export interface VoidReasonSummary {
  reason: string;
  count: number;
}

// The sheet's figures at a glance, per grade, per period, per claim status and per void reason; each agrees with the list.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <ul aria-label="By grade">
        @for (g of grades(); track g.grade) {
          <li>Grade {{ g.grade }}:
            <span [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</span> people, due
            <span [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</span>, active
            <span [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</span></li>
        }
      </ul>
      <ul aria-label="By period">
        @for (p of periods(); track p.name) {
          <li>Period {{ p.name }}: due
            <span [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal }}</span>, change
            <span [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change }}</span></li>
        }
      </ul>
      <ul aria-label="By status">
        @for (s of statuses(); track s.status) {
          <li>{{ s.status }}:
            <span [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count }}</span> claims</li>
        }
      </ul>
      <ul aria-label="By void reason">
        @for (v of voidReasons(); track v.reason) {
          <li>Voided as {{ v.reason }}:
            <span [attr.data-testid]="'summary-void-' + v.reason + '-count'">{{ v.count }}</span> claims</li>
        }
      </ul>
    </section>
  `,
})
export class SummaryPanel {
  readonly grades = input.required<readonly GradeSummary[]>();
  readonly periods = input.required<readonly PeriodSummary[]>();
  readonly statuses = input.required<readonly StatusSummary[]>();
  readonly voidReasons = input.required<readonly VoidReasonSummary[]>();
}
