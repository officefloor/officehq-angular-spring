import { Component, computed, inject, input } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { GradeList } from '../grades/grades';
import type { PersonWithClaims } from '../claims/claims';
import { Period, classicPeriodDueTotals, periodDueTotals } from '../periods/periods';
import { VoidReasonList } from '../void-reasons/void-reasons';

const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'];

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** The grades in order, then any other grade someone of the people is on, in the order first met. */
function gradesOf(grades: readonly string[], people: PersonWithClaims[]): string[] {
  return [...new Set([...grades, ...people.map((p) => p.grade)])];
}

/**
 * Per grade (the grades in order, then any other a person is on), the due-totals of the people on it today
 * (retired people left out, n/a skipped) added up, in cents.
 */
export function gradeDueCents(grades: readonly string[], people: PersonWithClaims[]): { grade: string; due: number }[] {
  return gradesOf(grades, people).map((grade) => ({
    grade,
    due: people
      .filter((p) => !p.retired && p.grade === grade)
      .reduce((sum, p) => sum + (p.dueTotal === null ? 0 : cents(p.dueTotal)), 0),
  }));
}

/** How many claims the people have in each status, in status order. */
export function statusCounts(people: PersonWithClaims[]): { status: string; count: number }[] {
  const claims = people.flatMap((p) => p.claims);
  return STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === status).length }));
}

// The sheet's figures summed from the list: per grade, the people on it today (retired people left
// out), their due-totals added up (n/a skipped) and how many of them are active; per period, its due-total and its change from the period before it by start date (none for the earliest); per month any claim is dated in, the dues of
// its claims that count; per status, how many
// claims have it, and how many REVIEW claims are late in review; per reason on the sheet's void reasons, how many VOID claims were voided with it. Beside it the classic
// summary: per period, its due-total counting each claim by its status now (see classicPeriodDueTotals), and the
// sheet's due-total untrimmed: everyone's due-total added up (n/a skipped).
@Component({
  selector: 'app-summary',
  imports: [FigurePipe],
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
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal | figure }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of byPeriod(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal | figure }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By month</caption>
        <thead>
          <tr><th scope="col">Month</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (m of byMonth(); track m.month) {
            <tr>
              <th scope="row">{{ m.month }}</th>
              <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal | figure }}</td>
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
      <p>Late in review: <span data-testid="summary-review-late-count">{{ reviewLateCount() }}</span></p>
      <p>Late pending: <span data-testid="summary-pending-late-count">{{ pendingLateCount() }}</span></p>
      <table>
        <caption>By void reason</caption>
        <thead>
          <tr><th scope="col">Reason</th><th scope="col">Voided claims</th></tr>
        </thead>
        <tbody>
          @for (v of byVoidReason(); track v.reason) {
            <tr>
              <th scope="row">{{ v.reason }}</th>
              <td [attr.data-testid]="'summary-void-' + v.reason + '-count'">{{ v.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <section aria-label="Classic summary">
      <h2>Classic summary</h2>
      <table>
        <caption>By period, by status now</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of classicByPeriod(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Due total: <span data-testid="classic-due-total">{{ classicDueTotal() | figure }}</span></p>
    </section>
  `,
})
export class Summary {
  private readonly voidReasons = inject(VoidReasonList);
  private readonly grades = inject(GradeList);

  readonly people = input.required<PersonWithClaims[]>();
  readonly periods = input.required<Period[]>();

  protected readonly byGrade = computed(() =>
    gradesOf(this.grades.codes(), this.people()).map((grade) => {
      const on = this.people().filter((p) => !p.retired && p.grade === grade);
      const due = on.reduce((sum, p) => sum + (p.dueTotal === null ? 0 : cents(p.dueTotal)), 0);
      return { grade, count: on.length, dueTotal: (due / 100).toFixed(2), active: on.filter((p) => p.active).length };
    }),
  );

  protected readonly byPeriod = computed(() => {
    const totals = periodDueTotals(this.periods(), this.people());
    const previous = new Map<number, string>();
    const byStart = [...this.periods()].sort((a, b) => a.from.localeCompare(b.from));
    byStart.forEach((p, i) => {
      if (i > 0) previous.set(p.id, totals[byStart[i - 1].id]);
    });
    return this.periods().map((p) => {
      const before = previous.get(p.id);
      const change = before === undefined ? '' : ((cents(totals[p.id]) - cents(before)) / 100).toFixed(2);
      return { name: p.name, dueTotal: totals[p.id], change };
    });
  });

  /** Every month (yyyy-mm) any claim is dated in, earliest first, with the dues of its claims that count added up. */
  protected readonly byMonth = computed(() => {
    const totals = new Map<string, number>();
    for (const c of this.people().flatMap((p) => p.claims)) {
      const month = c.date.slice(0, 7);
      const due = c.counts && c.due !== null ? cents(c.due) : 0;
      totals.set(month, (totals.get(month) ?? 0) + due);
    }
    return [...totals.keys()].sort().map((month) => ({ month, dueTotal: ((totals.get(month) ?? 0) / 100).toFixed(2) }));
  });

  protected readonly classicByPeriod = computed(() => {
    const totals = classicPeriodDueTotals(this.periods(), this.people());
    return this.periods().map((p) => ({ name: p.name, dueTotal: totals[p.id] }));
  });

  protected readonly classicDueTotal = computed(() => {
    const due = this.people().reduce((sum, p) => sum + (p.dueTotal === null ? 0 : cents(p.dueTotal)), 0);
    return (due / 100).toFixed(2);
  });

  protected readonly byStatus = computed(() => statusCounts(this.people()));

  protected readonly reviewLateCount = computed(() => this.people().flatMap((p) => p.claims).filter((c) => c.reviewLate).length);
  protected readonly pendingLateCount = computed(() => this.people().flatMap((p) => p.claims).filter((c) => c.pendingLate).length);

  protected readonly byVoidReason = computed(() => {
    const voided = this.people().flatMap((p) => p.claims).filter((c) => c.status === 'VOID');
    return this.voidReasons.reasons().map((reason) => ({ reason, count: voided.filter((c) => c.voidReason === reason).length }));
  });
}
