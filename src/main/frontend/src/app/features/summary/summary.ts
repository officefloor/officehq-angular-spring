import { Component, computed, inject, input } from '@angular/core';
import { DatedFigure, PeriodStore, periodDueTotals } from '../periods/periods';
import { GRADES, sumSkippingNa } from '../rates/rates';
import { VoidReasonStore } from '../void-reasons/void-reasons';

/**
 * A person as the summary counts them: their grade today, their due-total (null for n/a), whether retired and
 * whether active.
 */
export interface SummaryPerson {
  grade: string;
  dueTotal: string | null;
  retired: boolean;
  active: boolean;
}

// Every status a claim's count is shown for.
export const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'];

// The sheet's figures at a glance, each worked out the same way as the list shows it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-label="Summary">
      <h2>Summary</h2>
      <table>
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
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
          @for (p of byPeriod(); track p.name) {
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
          @for (s of byStatus(); track s.status) {
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
          <tr><th scope="col">Reason</th><th scope="col">Voided claims</th></tr>
        </thead>
        <tbody>
          @for (r of byVoidReason(); track r.reason) {
            <tr>
              <th scope="row">{{ r.reason }}</th>
              <td [attr.data-testid]="'summary-void-' + r.reason + '-count'">{{ r.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Summary {
  private readonly periods = inject(PeriodStore);
  private readonly voidReasons = inject(VoidReasonStore);

  readonly people = input<readonly SummaryPerson[]>([]);
  // The counted claims of the sheet: each one's date and figure, as the periods total them.
  readonly figures = input<readonly DatedFigure[]>([]);
  // The status of every claim on the sheet.
  readonly statuses = input<readonly string[]>([]);
  // The reason each VOID claim on the sheet was voided with (null for none).
  readonly voidedWith = input<readonly (string | null)[]>([]);

  // Per grade: the people on it today, leaving out retired ones, their due-totals added up, skipping n/a, and how
  // many of them are active.
  protected readonly byGrade = computed(() =>
    GRADES.map((grade) => {
      const on = this.people().filter((p) => !p.retired && p.grade === grade);
      return {
        grade,
        count: on.length,
        dueTotal: sumSkippingNa(on.map((p) => p.dueTotal)),
        active: on.filter((p) => p.active).length,
      };
    }),
  );
  protected readonly byPeriod = computed(() => {
    const totals = periodDueTotals(this.periods.list(), this.figures());
    return this.periods.list().map((p) => ({ name: p.name, dueTotal: totals.get(p.id) ?? '' }));
  });
  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({ status, count: this.statuses().filter((s) => s === status).length })),
  );
  // Per reason on the sheet's list: the VOID claims voided with it.
  protected readonly byVoidReason = computed(() =>
    this.voidReasons.list().map(({ reason }) => ({
      reason,
      count: this.voidedWith().filter((r) => r === reason).length,
    })),
  );
}
