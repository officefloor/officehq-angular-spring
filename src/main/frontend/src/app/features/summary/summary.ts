import { Component, computed, input } from '@angular/core';
import type { PersonWithClaims } from '../claims/claims';
import { Period, periodDueTotals } from '../periods/periods';

const GRADES = ['A', 'B', 'C'];
const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'];

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** How many claims the people have in each status, in status order. */
export function statusCounts(people: PersonWithClaims[]): { status: string; count: number }[] {
  const claims = people.flatMap((p) => p.claims);
  return STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === status).length }));
}

// The sheet's figures summed from the list: per grade, the people on it today (retired people left
// out), their due-totals added up (n/a skipped) and how many of them are active; per period, its due-total; per status, how many
// claims have it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-label="Summary">
      <h2>Summary</h2>
      <table>
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due total</th><th scope="col">Active</th></tr>
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
  readonly people = input.required<PersonWithClaims[]>();
  readonly periods = input.required<Period[]>();

  protected readonly byGrade = computed(() =>
    GRADES.map((grade) => {
      const on = this.people().filter((p) => !p.retired && p.grade === grade);
      const due = on.reduce((sum, p) => sum + (p.dueTotal === null ? 0 : cents(p.dueTotal)), 0);
      return { grade, count: on.length, dueTotal: (due / 100).toFixed(2), active: on.filter((p) => p.active).length };
    }),
  );

  protected readonly byPeriod = computed(() => {
    const totals = periodDueTotals(this.periods(), this.people());
    return this.periods().map((p) => ({ name: p.name, dueTotal: totals[p.id] }));
  });

  protected readonly byStatus = computed(() => statusCounts(this.people()));
}
