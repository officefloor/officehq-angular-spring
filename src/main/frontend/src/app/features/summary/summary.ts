import { Component, computed, input } from '@angular/core';
import { Claim, money } from '../claims/claims-api';
import { GRADES } from '../people/people-api';
import { Period, periodDueCents } from '../periods/periods-api';

/** The statuses the summary counts, in the order it lists them. */
export const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/** How many of the claims have each status, in the order the summary lists them. */
export function statusCounts(claims: Claim[]): { status: string; count: number }[] {
  return STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === (status as string)).length }));
}

/** A person as the summary counts them: their grade in force on the sheet's today, whether they
 *  are retired, and their due-total in cents (null: n/a). */
export interface SummaryPerson {
  grade: string;
  retired: boolean;
  due: number | null;
}

// The sheet in figures, each as the lists show it: per grade, how many people not retired are on it
// today and their due-totals added up (n/a skipped); per period, its due-total; per status, how
// many claims have it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <table data-testid="summary-grades">
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
      <table data-testid="summary-periods">
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ money(periodDue(p)) }}</td>
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
export class Summary {
  readonly people = input.required<SummaryPerson[]>();
  readonly claims = input.required<Claim[]>();
  readonly periods = input.required<Period[]>();
  /** Each open period's live due-total in cents, by period id. */
  readonly dueTotals = input.required<Map<number, number>>();

  protected readonly money = money;

  protected readonly byGrade = computed(() => GRADES.map((grade) => {
    const on = this.people().filter((p) => !p.retired && p.grade === grade);
    return { grade, count: on.length, cents: on.reduce((sum, p) => sum + (p.due ?? 0), 0) };
  }));

  protected readonly byStatus = computed(() => statusCounts(this.claims()));

  protected periodDue(p: Period): number {
    return periodDueCents(p, this.dueTotals());
  }
}
