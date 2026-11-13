import { Component, computed, inject } from '@angular/core';
import { Figures } from '../../core/figures';
import { GRADES, People } from '../../core/people';
import { Periods } from '../../core/periods';
import { money } from '../../core/rates';
import { Claims } from '../../core/claims';
import { VoidReasons } from '../../core/void-reasons';

// The sheet at a glance, from the same figures the lists show: per grade, the people on it today
// (retired people left out), their due-totals added up (one that is n/a adds nothing) and how many
// of them are active; per period,
// its due-total and its change from the period before it by from-date (none for the earliest); per status, the claims in it; per void reason on the list, the VOID claims voided with it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-label="Summary" data-testid="summary">
      <h2>Summary</h2>
      <table data-testid="summary-grades">
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
      <table data-testid="summary-periods">
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of byPeriod(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change }}</td>
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
      <table data-testid="summary-void-reasons">
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
  `,
})
export class SummaryPanel {
  private readonly people = inject(People);
  private readonly periods = inject(Periods);
  private readonly figures = inject(Figures);
  private readonly claims = inject(Claims);
  private readonly voidReasons = inject(VoidReasons);

  protected readonly byGrade = computed(() => {
    const current = this.people.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const on = current.filter((p) => this.figures.gradeToday(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: money(on.reduce((sum, p) => sum + (this.figures.dueCents(p) ?? 0n), 0n)),
        active: on.filter((p) => this.figures.active(p)).length,
      };
    });
  });

  protected readonly byPeriod = computed(() => {
    const live = this.figures.periodDueTotals();
    // The periods are held by from-date, so each one's previous is the one before it in the list.
    const totals = this.periods.periods().map((p) => ({ name: p.name, dueTotal: p.closedTotal ?? live.get(p.id) ?? '0.00' }));
    return totals.map((p, i) => ({
      ...p,
      change: i === 0 ? '' : money(cents(p.dueTotal) - cents(totals[i - 1].dueTotal)),
    }));
  });

  protected readonly byStatus = this.figures.statusCounts;

  protected readonly byVoidReason = computed(() => {
    const voided = this.claims.claims().filter((c) => c.status === 'VOID');
    return this.voidReasons.reasons().map(({ reason }) => ({
      reason,
      count: voided.filter((c) => c.voidReason === reason).length,
    }));
  });
}

/** Two-decimal money text as cents. */
function cents(amount: string): bigint {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = amount.replace('-', '').split('.');
  const h = BigInt(whole || '0') * 100n + BigInt((frac + '00').slice(0, 2));
  return negative ? -h : h;
}
