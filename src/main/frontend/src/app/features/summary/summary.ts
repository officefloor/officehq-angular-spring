import { Component, input } from '@angular/core';
import { Grade } from '../../core/people';

/** A grade's line: how many people not retired are on it today, and their due-totals added up. */
export interface GradeSummary {
  grade: Grade;
  count: number;
  dueTotal: string;
}

/** A period's line: its due-total, as the period's row shows it. */
export interface PeriodSummary {
  name: string;
  dueTotal: string;
}

/** A status's line: how many claims on the sheet are in it. */
export interface StatusSummary {
  status: string;
  count: number;
}

// The sheet's figures at a glance, per grade, per period and per claim status; each agrees with the list.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <ul aria-label="By grade">
        @for (g of grades(); track g.grade) {
          <li>Grade {{ g.grade }}:
            <span [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</span> people, due
            <span [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</span></li>
        }
      </ul>
      <ul aria-label="By period">
        @for (p of periods(); track p.name) {
          <li>Period {{ p.name }}: due
            <span [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal }}</span></li>
        }
      </ul>
      <ul aria-label="By status">
        @for (s of statuses(); track s.status) {
          <li>{{ s.status }}:
            <span [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count }}</span> claims</li>
        }
      </ul>
    </section>
  `,
})
export class SummaryPanel {
  readonly grades = input.required<readonly GradeSummary[]>();
  readonly periods = input.required<readonly PeriodSummary[]>();
  readonly statuses = input.required<readonly StatusSummary[]>();
}
