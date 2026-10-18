import { Component, computed, inject } from '@angular/core';
import { ClaimsService, money } from '../../core/claims.service';
import { GRADES, PeopleService } from '../../core/people.service';
import { PeriodsService } from '../../core/periods.service';
import { TotalsService } from '../../core/totals.service';

/** Every status the summary counts claims under, in the order shown. */
const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

// The sheet's summary, built from the same figures the lists show. Per grade: how many people (retired
// ones left out) are on it on the sheet's today, and their due-totals added up (skipping n/a). Per
// period: its due-total. Per status: how many claims have it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
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
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ money(g.cents) }}</td>
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
          @for (p of periods.periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ totals.periodDueTotals().get(p.id) }}</td>
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
  protected readonly periods = inject(PeriodsService);
  protected readonly totals = inject(TotalsService);
  private readonly people = inject(PeopleService);
  private readonly claims = inject(ClaimsService);
  protected readonly money = money;

  /** Each grade: the people on it today who are not retired, and their due-totals added up in cents. */
  protected readonly byGrade = computed(() => {
    const dueTotals = this.totals.personDueTotals();
    const current = this.people.people().filter((p) => !p.retiredFrom);
    return GRADES.map((grade) => {
      const on = current.filter((p) => this.totals.gradeToday(p) === grade);
      return {
        grade,
        count: on.length,
        cents: on.reduce((sum, p) => sum + (dueTotals.get(p.id) ?? 0), 0),
      };
    });
  });

  /** Each status: how many of the sheet's claims have it. */
  protected readonly byStatus = computed(() => {
    const all = this.claims.claims();
    return STATUSES.map((status) => ({ status, count: all.filter((c) => c.status === status).length }));
  });
}
