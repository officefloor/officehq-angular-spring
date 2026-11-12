import { Component, computed, inject } from '@angular/core';
import { Claims } from '../../core/claims';
import { Figures } from '../../core/figures';
import { GRADES, People } from '../../core/people';
import { Periods } from '../../core/periods';
import { money } from '../../core/rates';

/** The statuses the summary counts claims by, in the order it shows them. */
const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

// The sheet at a glance, from the same figures the lists show: per grade, the people on it today
// (retired people left out) and their due-totals added up (one that is n/a adds nothing); per period,
// its due-total; per status, the claims in it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-label="Summary" data-testid="summary">
      <h2>Summary</h2>
      <table data-testid="summary-grades">
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due total</th></tr>
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
      <table data-testid="summary-periods">
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th></tr>
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
      <table data-testid="summary-statuses">
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
export class SummaryPanel {
  private readonly people = inject(People);
  private readonly claims = inject(Claims);
  private readonly periods = inject(Periods);
  private readonly figures = inject(Figures);

  protected readonly byGrade = computed(() => {
    const current = this.people.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const on = current.filter((p) => this.figures.gradeToday(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: money(on.reduce((sum, p) => sum + (this.figures.dueCents(p) ?? 0n), 0n)),
      };
    });
  });

  protected readonly byPeriod = computed(() => {
    const live = this.figures.periodDueTotals();
    return this.periods.periods().map((p) => ({ name: p.name, dueTotal: p.closedTotal ?? live.get(p.id) ?? '0.00' }));
  });

  protected readonly byStatus = computed(() => {
    const claims = this.claims.claims();
    return STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === status).length }));
  });
}
