import { Component, computed, inject, input } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { Claim, money, reviewLate } from '../claims/claims-api';
import { GRADES } from '../people/people-api';
import { Period, periodDueCents } from '../periods/periods-api';

/** The statuses the summary counts, in the order it lists them. */
export const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'] as const;

/** How many of the claims have each status, in the order the summary lists them. */
export function statusCounts(claims: Claim[]): { status: string; count: number }[] {
  return STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === (status as string)).length }));
}

/** A person as the summary counts them: their grade in force on the sheet's today, whether they
 *  are retired, their due-total in cents (null: n/a), and whether they are active. */
export interface SummaryPerson {
  grade: string;
  retired: boolean;
  due: number | null;
  active: boolean;
}

// The sheet in figures, each as the lists show it: per grade, how many people not retired are on it
// today, their due-totals added up (n/a skipped) and how many of them are active; per period, its due-total and its change from the period before it by start date; per status, how
// many claims have it; per void reason on the sheet's list, how many VOID claims were voided with it.
@Component({
  selector: 'app-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <table data-testid="summary-grades">
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ money(g.cents) | figure }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="summary-periods">
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ money(periodDue(p)) | figure }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ changeText(p) | figure }}</td>
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
      <p>Review claims late <span data-testid="summary-review-late-count">{{ reviewLateCount() }}</span></p>
      <table data-testid="summary-void-reasons">
        <caption>Voided by reason</caption>
        <thead>
          <tr><th scope="col">Reason</th><th scope="col">Claims</th></tr>
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
  readonly people = input.required<SummaryPerson[]>();
  readonly claims = input.required<Claim[]>();
  readonly periods = input.required<Period[]>();
  /** Each open period's live due-total in cents, by period id. */
  readonly dueTotals = input.required<Map<number, number>>();

  protected readonly money = money;

  protected readonly byGrade = computed(() => GRADES.map((grade) => {
    const on = this.people().filter((p) => !p.retired && p.grade === grade);
    return { grade, count: on.length, cents: on.reduce((sum, p) => sum + (p.due ?? 0), 0),
      active: on.filter((p) => p.active).length };
  }));

  private readonly session = inject(Session);

  protected readonly byStatus = computed(() => statusCounts(this.claims()));

  /** How many REVIEW claims are late: their review day plus the review SLA is before the sheet's today. */
  protected readonly reviewLateCount = computed(() =>
    this.claims().filter((c) => reviewLate(c, this.session.today(), this.session.reviewSla())).length);

  protected readonly byVoidReason = computed(() => this.session.voidReasons().map((reason) => ({
    reason, count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === reason).length,
  })));

  protected periodDue(p: Period): number {
    return periodDueCents(p, this.dueTotals());
  }

  /** Each period's change of due-total in cents from the period before it by start date, by
   *  period id; none for the earliest. */
  private readonly changes = computed(() => {
    const byStart = [...this.periods()].sort((a, b) => a.from.localeCompare(b.from) || a.id - b.id);
    const changes = new Map<number, number>();
    byStart.forEach((p, i) => {
      if (i > 0) changes.set(p.id, this.periodDue(p) - this.periodDue(byStart[i - 1]));
    });
    return changes;
  });

  protected changeText(p: Period): string {
    const change = this.changes().get(p.id);
    return change === undefined ? '' : money(change);
  }
}
