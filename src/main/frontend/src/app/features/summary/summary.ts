import { Component, computed, inject, input } from '@angular/core';
import { Claims, formatCents } from '../../core/claims';
import { GRADES, Person, gradeOn } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';
import { periodDueCents, periodDueTotal } from '../periods/periods';

/** The statuses the summary counts claims by. */
const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

// The sheet at a glance: per grade the people on it today and their due-totals, per period its
// due-total, and per status how many claims have it — each figure as the lists show it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>

      <table>
        <caption>By grade (retired people excluded)</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.due }}</td>
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
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.due }}</td>
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
export class SummaryPanel {
  private readonly session = inject(Session);
  private readonly claims = inject(Claims);
  private readonly periods = inject(Periods);
  private readonly rates = inject(Rates);

  /** The sheet's people. */
  readonly people = input.required<Person[]>();
  /** Each person's due-total in cents, by person id, as their row shows it. */
  readonly dueCents = input.required<ReadonlyMap<number, number>>();

  protected readonly byGrade = computed(() => {
    const today = this.session.today() ?? '';
    const due = this.dueCents();
    const active = this.people().filter((p) => p.retiredFrom === null);
    return GRADES.map((grade) => {
      const on = active.filter((p) => gradeOn(p.grades, today) === grade);
      return {
        grade,
        count: on.length,
        due: formatCents(on.reduce((sum, p) => sum + (due.get(p.id) ?? 0), 0)),
      };
    });
  });

  protected readonly byPeriod = computed(() => {
    const due = periodDueCents(this.people(), this.claims.all(), this.periods, this.rates);
    return this.periods.all().map((p) => ({ name: p.name, due: periodDueTotal(p, due) }));
  });

  protected readonly byStatus = computed(() => {
    const all = this.claims.all();
    return STATUSES.map((status) => ({ status, count: all.filter((c) => c.status === status).length }));
  });
}
