import { Component, computed, inject, input } from '@angular/core';
import { DatedFigure, PeriodStore, periodDueTotals } from '../periods/periods';
import { GRADES, sumSkippingNa } from '../rates/rates';

/** A person as the summary counts them: their grade today, their due-total (null for n/a) and whether retired. */
export interface SummaryPerson {
  grade: string;
  dueTotal: string | null;
  retired: boolean;
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
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</td>
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
    </section>
  `,
})
export class Summary {
  private readonly periods = inject(PeriodStore);

  readonly people = input<readonly SummaryPerson[]>([]);
  // The counted claims of the sheet: each one's date and figure, as the periods total them.
  readonly figures = input<readonly DatedFigure[]>([]);
  // The status of every claim on the sheet.
  readonly statuses = input<readonly string[]>([]);

  // Per grade: the people on it today, leaving out retired ones, and their due-totals added up, skipping n/a.
  protected readonly byGrade = computed(() =>
    GRADES.map((grade) => {
      const on = this.people().filter((p) => !p.retired && p.grade === grade);
      return { grade, count: on.length, dueTotal: sumSkippingNa(on.map((p) => p.dueTotal)) };
    }),
  );
  protected readonly byPeriod = computed(() => {
    const totals = periodDueTotals(this.periods.list(), this.figures());
    return this.periods.list().map((p) => ({ name: p.name, dueTotal: totals.get(p.id) ?? '' }));
  });
  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({ status, count: this.statuses().filter((s) => s === status).length })),
  );
}
